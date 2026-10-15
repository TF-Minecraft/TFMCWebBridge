package net.tfminecraft.tfmcweb.luckperms;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import com.google.gson.JsonObject;

import net.tfminecraft.tfmcweb.Cache;
import net.tfminecraft.tfmcweb.TFMCWeb;
import net.tfminecraft.tfmcweb.api.LuckPermsBridgeClient;
import net.tfminecraft.tfmcweb.api.LuckPermsBridgeClient.CallResult;
import net.tfminecraft.tfmcweb.api.LuckPermsBridgeClient.Change;
import net.tfminecraft.tfmcweb.api.LuckPermsBridgeClient.ChangeResult;
import net.tfminecraft.tfmcweb.api.LuckPermsBridgeClient.ChangesResult;
import net.tfminecraft.tfmcweb.api.LuckPermsBridgeClient.UnchangedResult;

/**
 * Bridges LuckPerms and the staff panel. It publishes LuckPerms snapshots to this
 * server's site and, on the one server configured to apply, applies staff-queued
 * changes. One worker thread does both, so a snapshot never predates an applied change.
 */
public final class LuckPermsBridge {

	static final int REMEMBERED_RESULTS = 500;
	/** Short, so a reload never stalls the main thread; an older pass that outlives it is harmless. */
	static final long STOP_WAIT_SECONDS = 1;
	/** Commands change rarely: check each minute, upload when changed or every ten minutes. */
	static final long COMMANDS_CHECK_MILLIS = 60_000L;
	static final long COMMANDS_RESEND_MILLIS = 600_000L;

	/** Reads the server's commands; blocks the worker until the main thread answers. */
	@FunctionalInterface
	public interface CommandReader {
		CommandCatalogue.Server read() throws Exception;
	}

	@FunctionalInterface
	public interface StoreOpener {
		BridgeStore open();
	}

	private final JavaPlugin plugin;
	private final StoreOpener opener;
	private final Supplier<ScheduledExecutorService> workers;
	private final LongSupplier clock;
	private final CommandReader commands;

	/** Recent results by change id: a guard so a change seen twice is never applied twice. */
	private final Map<Long, ChangeResult> remembered = new LinkedHashMap<>() {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, ChangeResult> eldest) {
			return size() > REMEMBERED_RESULTS;
		}
	};
	/** Results the site has not acknowledged yet; posted again every tick until it does. */
	private final Map<Long, ChangeResult> unposted = new LinkedHashMap<>();
	private final Set<String> failing = new HashSet<>();

	/**
	 * One worker generation: what refresh() started it with. A pass from an older
	 * generation can outlive stop() while blocked on storage or the site.
	 */
	private record Run(long generation, BridgeStore store, boolean applying, long snapshotMillis) {}

	/** Held for a whole pass, so passes from two generations never interleave. */
	private final Object passLock = new Object();

	private ScheduledExecutorService worker;
	/** Advanced by every stop(); a pass whose generation is older has been superseded. */
	private volatile long generation;
	private volatile long lastPublishedAt;
	private boolean loggedLuckPermsMissing;
	// Guarded by passLock.
	private long stateGeneration = -1L;
	private long nextSnapshotAt;
	private String uploadedHash;
	private long nextCommandsAt;
	private String commandsHash;
	private long commandsUploadedAt;
	/** Last revision handed out; survives reloads, and the clock carries it across restarts. */
	private long lastRevision;

	public LuckPermsBridge(JavaPlugin plugin) {
		this(
			plugin,
			LuckPermsBridge::openLuckPerms,
			LuckPermsBridge::newWorker,
			System::currentTimeMillis,
			mainThreadReader(plugin)
		);
	}

	static CommandReader mainThreadReader(JavaPlugin plugin) {
		return () -> CommandCatalogue.readOnMainThread(plugin);
	}

	LuckPermsBridge(
		JavaPlugin plugin,
		StoreOpener opener,
		Supplier<ScheduledExecutorService> workers,
		LongSupplier clock,
		CommandReader commands
	) {
		this.plugin = plugin;
		this.opener = opener;
		this.workers = workers;
		this.clock = clock;
		this.commands = commands;
	}

	/**
	 * Loads the LuckPerms store by name so this class does not reference the API.
	 * Absent LuckPerms then disables only the bridge.
	 */
	static BridgeStore openLuckPerms() {
		try {
			Class<?> type = Class.forName("net.tfminecraft.tfmcweb.luckperms.LuckPermsBridgeStore");
			return (BridgeStore) type.getMethod("open").invoke(null);
		} catch (ReflectiveOperationException | NoClassDefFoundError e) {
			throw new IllegalStateException("LuckPerms API is not available", e);
		}
	}

	static ScheduledExecutorService newWorker() {
		return Executors.newSingleThreadScheduledExecutor(task -> {
			Thread thread = new Thread(task, "TFMCWeb-LuckPerms");
			thread.setDaemon(true);
			return thread;
		});
	}

	public void refresh() {
		stop();
		if (!enabled()) {
			return;
		}
		if (!luckPermsPresent()) {
			if (!loggedLuckPermsMissing) {
				plugin.getLogger().warning("LuckPerms is not installed; LuckPerms bridge disabled.");
				loggedLuckPermsMissing = true;
			}
			return;
		}
		BridgeStore opened;
		try {
			opened = opener.open();
		} catch (RuntimeException e) {
			plugin.getLogger().log(Level.WARNING, "[luckperms] LuckPerms is unavailable; bridge disabled", e);
			return;
		}
		if (opened == null) {
			plugin.getLogger().warning("[luckperms] LuckPerms is unavailable; bridge disabled");
			return;
		}
		boolean applying = Cache.luckPermsBridgeApply;
		long period = applying ? Cache.luckPermsBridgePollSeconds : Cache.luckPermsBridgeSnapshotSeconds;
		Run run = new Run(generation, opened, applying, Cache.luckPermsBridgeSnapshotSeconds * 1000L);
		worker = workers.get();
		worker.scheduleWithFixedDelay(() -> tick(run), 1L, Math.max(1L, period), TimeUnit.SECONDS);
	}

	/**
	 * Stops scheduling passes and supersedes the running one, which stops before
	 * fetching, applying or publishing anything more. It is never interrupted, so a
	 * LuckPerms save in progress completes and its result is still posted.
	 */
	public void stop() {
		generation++;
		ScheduledExecutorService current = worker;
		worker = null;
		if (current == null) {
			return;
		}
		current.shutdown();
		try {
			current.awaitTermination(STOP_WAIT_SECONDS, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	public boolean isRunning() {
		return worker != null;
	}

	public String statusDetail() {
		String text = statusText(isRunning());
		if (!isRunning()) {
			return text;
		}
		long at = lastPublishedAt;
		if (at == 0L) {
			return text + " last-snapshot=never";
		}
		return text + " last-snapshot=" + Math.max(0L, (clock.getAsLong() - at) / 1000L) + "s ago";
	}

	public static String statusText(boolean running) {
		String text = "publish=" + Cache.luckPermsBridgePublish
			+ " apply=" + Cache.luckPermsBridgeApply
			+ " poll=" + Cache.luckPermsBridgePollSeconds + "s"
			+ " snapshot=" + Cache.luckPermsBridgeSnapshotSeconds + "s"
			+ " bridge=";
		if (running) {
			return text + "on";
		}
		if (enabled()) {
			return text + "off (LuckPerms unavailable)";
		}
		return text + "off";
	}

	/** Applying needs current snapshots on the site too, so apply implies publish. */
	private static boolean enabled() {
		return Cache.luckPermsBridgePublish || Cache.luckPermsBridgeApply;
	}

	private boolean current(Run run) {
		return generation == run.generation();
	}

	private void tick(Run run) {
		synchronized (passLock) {
			if (!current(run)) {
				return;
			}
			if (stateGeneration != run.generation()) {
				stateGeneration = run.generation();
				nextSnapshotAt = 0L;
				uploadedHash = null;
				// Wait a minute, so a starting server has registered every plugin's commands.
				nextCommandsAt = clock.getAsLong() + COMMANDS_CHECK_MILLIS;
				commandsHash = null;
				failing.clear();
			}
			long now = clock.getAsLong();
			boolean applied = false;
			if (run.applying()) {
				try {
					applied = pollChanges(run);
				} catch (RuntimeException e) {
					fail("changes", "[luckperms] change poll failed", e);
				}
			}
			if (current(run) && (applied || now >= nextSnapshotAt)) {
				nextSnapshotAt = now + run.snapshotMillis();
				publish(run);
			}
			if (current(run) && now >= nextCommandsAt) {
				nextCommandsAt = now + COMMANDS_CHECK_MILLIS;
				publishCommands(run, now);
			}
		}
	}

	private void publishCommands(Run run, long now) {
		try {
			JsonObject data = CommandCatalogue.payload(commands.read(), run.store().groupChecks());
			if (!current(run)) {
				return;
			}
			String hash = sha256(data.toString());
			if (hash.equals(commandsHash) && now - commandsUploadedAt < COMMANDS_RESEND_MILLIS) {
				return;
			}
			data.addProperty("server", TFMCWeb.getRealmId());
			data.addProperty("generated_at", now / 1000L);
			data.addProperty("hash", hash);
			CallResult upload = LuckPermsBridgeClient.putCommands(data.toString());
			if (!upload.ok) {
				fail("commands", "[luckperms] command list upload failed (" + describe(upload.status) + ")", null);
				return;
			}
			commandsHash = hash;
			commandsUploadedAt = now;
			recover("commands");
		} catch (Exception e) {
			fail("commands", "[luckperms] command list failed", e);
		}
	}

	/**
	 * The site hands out each change once, so every fetched change gets a result:
	 * once this pass is superseded, the rest of its batch is answered as not applied.
	 *
	 * @return true when at least one change was applied in this pass
	 */
	private boolean pollChanges(Run run) {
		boolean applied = false;
		ChangesResult batch = LuckPermsBridgeClient.listChanges();
		if (batch.ok) {
			recover("changes");
			for (Change change : batch.changes) {
				Long id = Long.valueOf(change.id);
				if (remembered.containsKey(id)) {
					continue;
				}
				ChangeResult result = current(run) ? applyChange(run.store(), change) : notApplied(change);
				result = result.withRevision(nextRevision());
				remembered.put(id, result);
				unposted.put(id, result);
				applied |= result.ok;
			}
		} else {
			fail("changes", "[luckperms] change poll failed (" + describe(batch.status) + ")", null);
		}
		postResults();
		return applied;
	}

	/** Strictly increasing across snapshots and results, and across restarts via the clock. */
	private long nextRevision() {
		lastRevision = Math.max(lastRevision + 1, clock.getAsLong());
		return lastRevision;
	}

	private ChangeResult notApplied(Change change) {
		plugin.getLogger().warning("[luckperms] change " + change.id + " not applied: the bridge stopped");
		return ChangeResult.failure(change.id, BridgeStore.SAVE_FAILED);
	}

	private ChangeResult applyChange(BridgeStore current, Change change) {
		ChangeResult result;
		try {
			result = current.apply(change);
		} catch (RuntimeException e) {
			plugin.getLogger().log(Level.WARNING, "[luckperms] change " + change.id + " failed", e);
			result = ChangeResult.failure(change.id, BridgeStore.SAVE_FAILED);
		}
		if (result.ok) {
			plugin.getLogger().info("[luckperms] applied change " + change.id
				+ (change.description == null ? "" : ": " + change.description));
		} else {
			plugin.getLogger().warning("[luckperms] change " + change.id + " refused: " + result.error);
		}
		return result;
	}

	private void postResults() {
		if (unposted.isEmpty()) {
			return;
		}
		CallResult posted = LuckPermsBridgeClient.postResults(new ArrayList<>(unposted.values()));
		if (!posted.ok) {
			fail("results", "[luckperms] change results post failed (" + describe(posted.status) + ")", null);
			return;
		}
		recover("results");
		unposted.clear();
	}

	private void publish(Run run) {
		try {
			JsonObject data = run.store().snapshot();
			if (!current(run)) {
				return;
			}
			String hash = sha256(data.toString());
			long revision = nextRevision();
			if (hash.equals(uploadedHash)) {
				UnchangedResult check = LuckPermsBridgeClient.snapshotUnchanged(hash, revision);
				if (!check.ok) {
					fail("snapshot", "[luckperms] snapshot check failed (" + describe(check.status) + ")", null);
					return;
				}
				if (check.current) {
					published();
					return;
				}
			}
			JsonObject payload = new JsonObject();
			payload.addProperty("server", TFMCWeb.getRealmId());
			payload.addProperty("generated_at", clock.getAsLong() / 1000L);
			payload.addProperty("revision", revision);
			payload.addProperty("hash", hash);
			payload.add("groups", data.get("groups"));
			payload.add("tracks", data.get("tracks"));
			payload.add("users", data.get("users"));
			CallResult upload = LuckPermsBridgeClient.putSnapshot(payload.toString());
			if (!upload.ok) {
				fail("snapshot", "[luckperms] snapshot upload failed (" + describe(upload.status) + ")", null);
				return;
			}
			uploadedHash = hash;
			published();
		} catch (Exception e) {
			fail("snapshot", "[luckperms] snapshot failed", e);
		}
	}

	private void published() {
		lastPublishedAt = clock.getAsLong();
		recover("snapshot");
	}

	static String sha256(String text) throws NoSuchAlgorithmException {
		byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
		return HexFormat.of().formatHex(digest);
	}

	/** Logs the first failure of a kind until it recovers, so an outage does not flood the log. */
	private void fail(String kind, String message, Exception e) {
		if (!failing.add(kind)) {
			return;
		}
		if (e == null) {
			plugin.getLogger().warning(message);
		} else {
			plugin.getLogger().log(Level.WARNING, message, e);
		}
	}

	private void recover(String kind) {
		if (failing.remove(kind)) {
			plugin.getLogger().info("[luckperms] " + kind + " working again");
		}
	}

	private static String describe(int status) {
		return status > 0 ? "HTTP " + status : "site unreachable";
	}

	private static boolean luckPermsPresent() {
		if (Bukkit.getPluginManager() == null) {
			return false;
		}
		Plugin luckPerms = Bukkit.getPluginManager().getPlugin("LuckPerms");
		return luckPerms != null && luckPerms.isEnabled();
	}
}
