package net.tfminecraft.tfmcweb.entitlements;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import net.tfminecraft.tfmcweb.TFMCWeb;
import net.tfminecraft.tfmcweb.api.ProvinceSystemClient;
import net.tfminecraft.tfmcweb.api.ProvinceSystemClient.SimpleResult;
import net.tfminecraft.tfmcweb.entitlements.EntitlementResolver.ResolvedMeta;

/**
 * Push resolved web entitlements to ProvinceSystem on join, reload and rank change (fail-soft).
 */
public final class PlayerMetaSyncService {

	/**
	 * After a failed PUT, unchanged bodies are resent for this long. A timed-out PUT can still
	 * land on the website after a newer one, so the newest body cannot be trusted until a send
	 * succeeds well after the failure; periodic LuckPerms syncs supply those resends.
	 */
	static final long REPAIR_WINDOW_MS = 10 * 60_000L;
	static LongSupplier clock = System::currentTimeMillis;

	/** Newest body handed to the sender per player. Sends run in order. */
	private static final Map<UUID, String> latest = new ConcurrentHashMap<>();
	/** Time of each player's last failed PUT, cleared once the repair window has passed. */
	private static final Map<UUID, Long> failedAt = new ConcurrentHashMap<>();
	/** Newest body waiting to be sent per player. */
	private static final Map<UUID, String> queued = new ConcurrentHashMap<>();
	/** Players with a send task running; one per player keeps PUTs in order. */
	private static final Set<UUID> sending = ConcurrentHashMap.newKeySet();

	private PlayerMetaSyncService() {}

	/** Resolve on the main thread, then PUT async. Producers must stay on the main thread. */
	public static void pushForPlayer(Player player) {
		push(player, true);
	}

	/** Like {@link #pushForPlayer} but skips the PUT when this body was already sent or is on its way. */
	public static void pushIfChanged(Player player) {
		push(player, false);
	}

	private static void push(Player player, boolean force) {
		if (player == null || TFMCWeb.plugin == null) {
			return;
		}
		UUID playerUuid = player.getUniqueId();
		ResolvedMeta meta = EntitlementResolver.resolve(player);
		String body = toJson(playerUuid, meta);
		if (!force && body.equals(latest.get(playerUuid)) && !recentlyFailed(playerUuid)) {
			return;
		}
		latest.put(playerUuid, body);
		queued.put(playerUuid, body);
		if (sending.add(playerUuid)) {
			Bukkit.getScheduler().runTaskAsynchronously(TFMCWeb.plugin, () -> drain(playerUuid));
		}
	}

	/**
	 * Sends the newest queued body until none is left, so an older body never lands last.
	 * The outer check catches a body queued after the inner loop emptied but before release.
	 */
	static void drain(UUID playerUuid) {
		do {
			String body;
			while ((body = queued.remove(playerUuid)) != null) {
				send(playerUuid, body);
			}
			sending.remove(playerUuid);
		} while (queued.containsKey(playerUuid) && sending.add(playerUuid));
	}

	private static void send(UUID playerUuid, String body) {
		SimpleResult result;
		try {
			result = ProvinceSystemClient.putRpcPlayerMeta(body);
		} catch (RuntimeException e) {
			// A stuck sending entry would stop this player's pushes until restart.
			result = SimpleResult.fail(String.valueOf(e));
		}
		if (!result.ok) {
			failedAt.put(playerUuid, clock.getAsLong());
			TFMCWeb.plugin.getLogger().warning(
				"[player-meta] push failed for " + playerUuid + ": " + result.error
			);
		}
	}

	private static boolean recentlyFailed(UUID playerUuid) {
		Long failed = failedAt.get(playerUuid);
		if (failed == null) {
			return false;
		}
		if (clock.getAsLong() - failed < REPAIR_WINDOW_MS) {
			return true;
		}
		failedAt.remove(playerUuid, failed);
		return false;
	}

	public static void pushAsync(UUID playerUuid) {
		if (playerUuid == null || TFMCWeb.plugin == null) {
			return;
		}
		Bukkit.getScheduler().runTask(TFMCWeb.plugin, () -> {
			Player online = Bukkit.getPlayer(playerUuid);
			if (online != null && online.isOnline()) {
				pushForPlayer(online);
			}
		});
	}

	public static void pushAllOnlineAsync() {
		if (TFMCWeb.plugin == null) {
			return;
		}
		Bukkit.getScheduler().runTask(TFMCWeb.plugin, () -> {
			for (Player player : Bukkit.getOnlinePlayers()) {
				if (player != null) {
					pushForPlayer(player);
				}
			}
		});
	}

	static String toJson(UUID playerUuid, ResolvedMeta meta) {
		StringBuilder sb = new StringBuilder(512);
		sb.append("{\"player_uuid\":\"").append(escape(playerUuid.toString())).append('"');
		sb.append(",\"realm_id\":\"").append(escape(TFMCWeb.getRealmId())).append('"');
		sb.append(",\"name_colour_stops\":").append(meta.nameColourStops);
		sb.append(",\"allow_drink_texture\":").append(meta.allowDrinkTexture);
		sb.append(",\"allow_drink_message\":").append(meta.allowDrinkMessage);
		if (meta.maxAliveCharacters != null) {
			sb.append(",\"max_alive_characters\":").append(meta.maxAliveCharacters.intValue());
		}
		sb.append(",\"wardrobe_skin_slots\":").append(meta.wardrobeSkinSlots);
		sb.append(",\"max_3d_pair_bytes\":").append(meta.max3dPairBytes);
		sb.append(",\"skin_token_cooldown_days\":").append(meta.skinTokenCooldownDays);
		sb.append(",\"skin_kinds\":[");
		boolean first = true;
		for (String kind : meta.skinKinds) {
			if (kind == null || kind.isBlank()) {
				continue;
			}
			if (!first) {
				sb.append(',');
			}
			first = false;
			sb.append('"').append(escape(kind.trim().toLowerCase(java.util.Locale.ROOT))).append('"');
		}
		sb.append(']');
		sb.append(",\"allow_armor_3d_helmet\":").append(meta.allowArmor3dHelmet);
		sb.append(",\"permission_flags\":{");
		first = true;
		for (Map.Entry<String, Boolean> entry : meta.permissionFlags.entrySet()) {
			if (entry.getKey() == null || entry.getKey().isBlank()) {
				continue;
			}
			if (!first) {
				sb.append(',');
			}
			first = false;
			sb.append('"').append(escape(entry.getKey())).append("\":")
				.append(Boolean.TRUE.equals(entry.getValue()));
		}
		sb.append("}}");
		return sb.toString();
	}

	private static String escape(String raw) {
		String encoded = new com.google.gson.Gson().toJson(raw);
		return encoded.substring(1, encoded.length() - 1);
	}
}
