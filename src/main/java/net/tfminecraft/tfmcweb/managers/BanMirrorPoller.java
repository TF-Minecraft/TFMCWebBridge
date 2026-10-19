package net.tfminecraft.tfmcweb.managers;

import java.io.File;
import java.io.IOException;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import org.bukkit.BanEntry;
import org.bukkit.Bukkit;
import org.bukkit.ban.ProfileBanList;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import com.destroystokyo.paper.profile.PlayerProfile;

import io.papermc.paper.ban.BanListType;
import net.tfminecraft.tfmcweb.Cache;
import net.tfminecraft.tfmcweb.api.ProvinceSystemClient;
import net.tfminecraft.tfmcweb.api.ProvinceSystemClient.BanSync;
import net.tfminecraft.tfmcweb.api.ProvinceSystemClient.IdentityStatus;
import net.tfminecraft.tfmcweb.api.ProvinceSystemClient.MirrorResult;
import net.tfminecraft.tfmcweb.api.ProvinceSystemClient.SyncedBan;
import net.tfminecraft.tfmcweb.cache.LinkCache;

/**
 * Mirrors the server's player ban list to the ProvinceSystem moderation outbox.
 * EssentialsX has no ban event and timed bans expire silently, so the list is
 * diffed on a timer: new entries post a ban, removed or expired entries post an
 * unban (which clears the Discord Banned role). With ban-mirror.sync, bans and
 * unbans the other servers reported are then applied here too.
 */
public final class BanMirrorPoller {

	static final String STATE_FILE = "ban-mirror.yml";
	static final String EXPIRED_STAFF = "Ban expired";
	static final String SYNC_SOURCE = "TFMC ban sync";
	/** Bans whose end times are this close are the same ban (matches the API). */
	static final long SAME_BAN_MS = 10L * 60_000L;

	private static final int REASON_MAX = 500;
	private static final int STAFF_NAME_MAX = 32;
	private static final int MC_NAME_MAX = 16;
	private static final Pattern COLOUR_CODE =
		Pattern.compile("(?i)[§&][0-9a-fk-or]|#[0-9a-f]{6}");

	private final JavaPlugin plugin;
	private final LinkCache linkCache;
	private final File stateFile;
	private final AtomicBoolean busy = new AtomicBoolean();
	private final Set<UUID> warned = ConcurrentHashMap.newKeySet();
	private final AtomicBoolean syncWarned = new AtomicBoolean();
	/** Bans already mirrored; null until the first list has been adopted. */
	private Map<UUID, BanRecord> known;
	private BukkitTask task;

	public BanMirrorPoller(JavaPlugin plugin, LinkCache linkCache) {
		this.plugin = plugin;
		this.linkCache = linkCache;
		this.stateFile = new File(plugin.getDataFolder(), STATE_FILE);
	}

	public void start() {
		if (task != null) {
			return;
		}
		if (!Cache.banMirrorEnabled) {
			plugin.getLogger().info("Ban mirror disabled in config.");
			return;
		}
		known = loadState();
		long period = Cache.banMirrorPollSeconds * 20L;
		task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, period, period);
		plugin.getLogger().info(
			"Ban mirror polling the ban list every " + Cache.banMirrorPollSeconds + "s."
		);
	}

	public void stop() {
		if (task != null) {
			task.cancel();
			task = null;
		}
	}

	/** Main thread: snapshot the ban list, then post differences off-thread. */
	void tick() {
		if (!busy.compareAndSet(false, true)) {
			return;
		}
		long now = System.currentTimeMillis();
		Map<UUID, BanRecord> current;
		try {
			current = readBanList(now);
		} catch (RuntimeException e) {
			plugin.getLogger().warning("[ban-mirror] could not read the ban list: " + e);
			busy.set(false);
			return;
		}
		if (known == null) {
			// First run: adopt existing bans without notifying anyone.
			known = current;
			Bukkit.getScheduler().runTaskAsynchronously(plugin, this::saveAndRelease);
			return;
		}
		List<Change> changes = diff(known, current, now);
		boolean sync = Cache.banMirrorSync;
		if (changes.isEmpty() && !sync) {
			busy.set(false);
			return;
		}
		Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
			boolean applying = false;
			try {
				for (Change change : changes) {
					post(change);
				}
				// Local changes are posted first, so the list fetched next already has them.
				if (sync) {
					BanSync remote = fetchSync();
					if (remote != null) {
						Bukkit.getScheduler().runTask(plugin, () -> applySync(remote));
						applying = true;
					}
				}
			} finally {
				if (!applying) {
					saveAndRelease();
				}
			}
		});
	}

	private BanSync fetchSync() {
		BanSync remote = ProvinceSystemClient.getBanSync();
		if (!remote.ok) {
			if (syncWarned.compareAndSet(false, true)) {
				plugin.getLogger().warning(
					"[ban-sync] could not read bans from the API: " + remote.error + " (retrying quietly)"
				);
			}
			return null;
		}
		syncWarned.set(false);
		return remote;
	}

	/** Main thread: apply bans and unbans another server reported, then save and release. */
	void applySync(BanSync remote) {
		try {
			long now = System.currentTimeMillis();
			ProfileBanList list = Bukkit.getBanList(BanListType.PROFILE);
			Map<UUID, BanRecord> current = readBanList(now);
			Set<UUID> banned = new HashSet<>();
			for (SyncedBan ban : remote.bans) {
				if (shouldApply(ban, known.get(ban.id), current.get(ban.id), now)) {
					applyBan(list, ban);
					banned.add(ban.id);
				}
			}
			for (Map.Entry<UUID, Long> unban : remote.unbans.entrySet()) {
				UUID id = unban.getKey();
				BanRecord local = current.get(id);
				BanRecord was = known.get(id);
				// Only lift a ban this server already mirrored and that is older than the unban.
				if (local == null || was == null || was.created != local.created
					|| local.created <= 0L || local.created > unban.getValue()) {
					continue;
				}
				list.pardon(Bukkit.createProfile(id, local.name));
				known.remove(id);
				plugin.getLogger().info("[ban-sync] lifted the ban on " + local.name + " (unbanned on another server)");
			}
			if (!banned.isEmpty()) {
				Map<UUID, BanRecord> fresh = readBanList(now);
				for (UUID id : banned) {
					BanRecord record = fresh.get(id);
					if (record != null) {
						known.put(id, record);
					}
				}
			}
		} catch (RuntimeException e) {
			plugin.getLogger().warning("[ban-sync] could not apply bans from the API: " + e);
		}
		Bukkit.getScheduler().runTaskAsynchronously(plugin, this::saveAndRelease);
	}

	static boolean shouldApply(SyncedBan ban, BanRecord was, BanRecord local, long now) {
		if (ban.expiresAt > 0L && ban.expiresAt <= now) {
			return false;
		}
		if (local == null) {
			// Mirrored here but gone from the list: a local unban the API has not had yet.
			return was == null;
		}
		// A newer ban with a different length replaces this server's copy.
		return ban.bannedAt > local.created && Math.abs(ban.expiresAt - local.expires) > SAME_BAN_MS;
	}

	private void applyBan(ProfileBanList list, SyncedBan ban) {
		Date expires = ban.expiresAt > 0L ? new Date(ban.expiresAt) : null;
		String source = ban.staffName != null && !ban.staffName.isBlank() ? ban.staffName : SYNC_SOURCE;
		Player online = Bukkit.getPlayer(ban.id);
		if (online != null) {
			online.ban(ban.reason, expires, source, true);
		} else {
			list.addBan(Bukkit.createProfile(ban.id, ban.name), ban.reason, expires, source);
		}
		plugin.getLogger().info(
			"[ban-sync] applied the ban on " + ban.name + " from another server ("
				+ formatDuration(ban.expiresAt, ban.bannedAt) + ")"
		);
	}

	private void saveAndRelease() {
		try {
			saveState(known);
		} finally {
			busy.set(false);
		}
	}

	static Map<UUID, BanRecord> readBanList(long now) {
		Map<UUID, BanRecord> bans = new HashMap<>();
		ProfileBanList list = Bukkit.getBanList(BanListType.PROFILE);
		for (BanEntry<PlayerProfile> entry : list.<BanEntry<PlayerProfile>>getEntries()) {
			PlayerProfile profile = entry.getBanTarget();
			UUID id = profile == null ? null : profile.getId();
			if (id == null) {
				continue;
			}
			long expires = seconds(millis(entry.getExpiration()));
			if (expires > 0L && expires <= now) {
				continue;
			}
			bans.put(id, new BanRecord(
				profile.getName(),
				seconds(millis(entry.getCreated())),
				expires,
				entry.getReason(),
				entry.getSource()
			));
		}
		return bans;
	}

	static List<Change> diff(Map<UUID, BanRecord> known, Map<UUID, BanRecord> current, long now) {
		List<Change> changes = new ArrayList<>();
		for (Map.Entry<UUID, BanRecord> entry : known.entrySet()) {
			BanRecord was = entry.getValue();
			BanRecord is = current.get(entry.getKey());
			if (is == null) {
				String staff = was.expires > 0L && was.expires <= now ? EXPIRED_STAFF : null;
				changes.add(new Change(entry.getKey(), false, was, staff));
			} else if (is.created != was.created || is.expires != was.expires) {
				changes.add(new Change(entry.getKey(), true, is, is.source));
			}
		}
		for (Map.Entry<UUID, BanRecord> entry : current.entrySet()) {
			if (!known.containsKey(entry.getKey())) {
				BanRecord ban = entry.getValue();
				changes.add(new Change(entry.getKey(), true, ban, ban.source));
			}
		}
		return changes;
	}

	private void post(Change change) {
		BanRecord ban = change.record;
		String eventType = change.banned ? "ban" : "unban";
		String name = cleanDisplay(ban.name, MC_NAME_MAX);
		MirrorResult result = ProvinceSystemClient.postBanEvent(
			eventType,
			change.id.toString(),
			resolveDiscordId(change.id),
			name,
			change.banned ? cleanReason(ban.reason) : null,
			change.banned ? formatDuration(ban.expires, banStart(ban)) : null,
			cleanDisplay(change.staff, STAFF_NAME_MAX),
			ban.created,
			ban.expires
		);
		if (!result.ok) {
			if (warned.add(change.id)) {
				plugin.getLogger().warning(
					"[ban-mirror] API failed for " + eventType + " " + name + ": " + result.error
						+ " (retrying quietly)"
				);
			}
			return;
		}
		warned.remove(change.id);
		if (change.banned) {
			known.put(change.id, ban);
		} else {
			known.remove(change.id);
		}
		plugin.getLogger().info(
			"[ban-mirror] " + eventType + " for " + name
				+ (result.duplicate ? " already sent from another server"
					: result.mirrored ? " sent to Discord" : " (no Discord link)")
		);
	}

	private String resolveDiscordId(UUID uuid) {
		LinkCache.Entry cached = linkCache.get(uuid);
		if (cached != null && cached.discordUserId != null && !cached.discordUserId.isBlank()) {
			return cached.discordUserId;
		}
		IdentityStatus status = ProvinceSystemClient.getIdentityStatus(uuid.toString());
		if (status.ok && status.discordUserId != null && !status.discordUserId.isBlank()) {
			linkCache.putFromStatus(uuid, status);
			return status.discordUserId;
		}
		return null;
	}

	private Map<UUID, BanRecord> loadState() {
		if (!stateFile.isFile()) {
			return null;
		}
		Map<UUID, BanRecord> state = new HashMap<>();
		ConfigurationSection bans =
			YamlConfiguration.loadConfiguration(stateFile).getConfigurationSection("bans");
		if (bans == null) {
			return state;
		}
		for (String key : bans.getKeys(false)) {
			UUID id;
			try {
				id = UUID.fromString(key);
			} catch (IllegalArgumentException e) {
				continue;
			}
			state.put(id, new BanRecord(
				bans.getString(key + ".name"),
				seconds(bans.getLong(key + ".created")),
				seconds(bans.getLong(key + ".expires")),
				null,
				null
			));
		}
		return state;
	}

	private void saveState(Map<UUID, BanRecord> state) {
		YamlConfiguration yaml = new YamlConfiguration();
		yaml.createSection("bans");
		for (Map.Entry<UUID, BanRecord> entry : state.entrySet()) {
			String key = "bans." + entry.getKey();
			yaml.set(key + ".name", entry.getValue().name);
			yaml.set(key + ".created", entry.getValue().created);
			yaml.set(key + ".expires", entry.getValue().expires);
		}
		try {
			yaml.save(stateFile);
		} catch (IOException e) {
			plugin.getLogger().warning("[ban-mirror] could not save " + STATE_FILE + ": " + e);
		}
	}

	private static long millis(Date date) {
		return date == null ? 0L : date.getTime();
	}

	/**
	 * Whole seconds. banned-players.json keeps ban times to the second, so a ban
	 * read back after a restart loses the milliseconds it had when it was made.
	 */
	static long seconds(long millis) {
		return millis / 1000L * 1000L;
	}

	/** Full ban length, not time left: a 7 day ban seen a minute late is still "7 days". */
	private static long banStart(BanRecord ban) {
		return ban.created > 0L ? ban.created : System.currentTimeMillis();
	}

	static String formatDuration(long expires, long since) {
		if (expires <= 0L) {
			return "Permanent";
		}
		Duration d = Duration.ofMillis(Math.max(0L, expires - since));
		long days = d.toDays();
		if (days >= 1) {
			return days + (days == 1 ? " day" : " days");
		}
		long hours = d.toHours();
		if (hours >= 1) {
			return hours + "h";
		}
		return Math.max(1, d.toMinutes()) + "m";
	}

	/** Ban reasons may hold symbols the API's prose check rejects (e.g. "|"). */
	static String cleanReason(String reason) {
		if (reason == null) {
			return null;
		}
		String text = COLOUR_CODE.matcher(Normalizer.normalize(reason, Normalizer.Form.NFKC))
			.replaceAll("");
		StringBuilder sb = new StringBuilder(text.length());
		text.codePoints().forEach(cp -> {
			if (allowedInProse(cp)) {
				sb.appendCodePoint(cp);
			} else {
				sb.append(' ');
			}
		});
		String cleaned = sb.toString().replaceAll("\\s+", " ").trim();
		if (cleaned.length() > REASON_MAX) {
			cleaned = cleaned.substring(0, REASON_MAX).trim();
		}
		return cleaned.isEmpty() ? null : cleaned;
	}

	private static boolean allowedInProse(int cp) {
		switch (Character.getType(cp)) {
			case Character.CONTROL:
			case Character.LINE_SEPARATOR:
			case Character.PARAGRAPH_SEPARATOR:
			case Character.MATH_SYMBOL:
			case Character.CURRENCY_SYMBOL:
			case Character.MODIFIER_SYMBOL:
			case Character.OTHER_SYMBOL:
			case Character.FORMAT:
			case Character.SURROGATE:
			case Character.PRIVATE_USE:
			case Character.UNASSIGNED:
			case Character.ENCLOSING_MARK:
				return false;
			default:
				return true;
		}
	}

	/** Keeps the characters the API allows in names: letters, digits and " .-'_". */
	static String cleanDisplay(String value, int max) {
		if (value == null) {
			return null;
		}
		StringBuilder sb = new StringBuilder();
		Normalizer.normalize(value, Normalizer.Form.NFKC).codePoints().forEach(cp -> {
			if (Character.isLetter(cp) || Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER
				|| " .-'_".indexOf(cp) >= 0) {
				sb.appendCodePoint(cp);
			}
		});
		String cleaned = sb.toString().replaceAll("\\s+", " ").trim();
		if (cleaned.length() > max) {
			cleaned = cleaned.substring(0, max).trim();
		}
		return cleaned.isEmpty() ? null : cleaned;
	}

	/** One ban as last seen; expires is 0 for permanent bans. */
	static final class BanRecord {
		final String name;
		final long created;
		final long expires;
		final String reason;
		final String source;

		BanRecord(String name, long created, long expires, String reason, String source) {
			this.name = name;
			this.created = created;
			this.expires = expires;
			this.reason = reason;
			this.source = source;
		}
	}

	static final class Change {
		final UUID id;
		final boolean banned;
		final BanRecord record;
		final String staff;

		Change(UUID id, boolean banned, BanRecord record, String staff) {
			this.id = id;
			this.banned = banned;
			this.record = record;
			this.staff = staff;
		}
	}
}
