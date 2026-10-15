package net.tfminecraft.tfmcweb.luckperms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.tfminecraft.tfmcweb.Cache;
import net.tfminecraft.tfmcweb.TestState;
import net.tfminecraft.tfmcweb.api.LuckPermsBridgeClient;
import net.tfminecraft.tfmcweb.api.LuckPermsBridgeClient.CallResult;
import net.tfminecraft.tfmcweb.api.LuckPermsBridgeClient.Change;
import net.tfminecraft.tfmcweb.api.LuckPermsBridgeClient.ChangeResult;
import net.tfminecraft.tfmcweb.api.LuckPermsBridgeClient.ChangesResult;
import net.tfminecraft.tfmcweb.api.LuckPermsBridgeClient.UnchangedResult;
import net.tfminecraft.tfmcweb.cache.LinkCache;
import net.tfminecraft.tfmcweb.gate.DiscordGateService;
import net.tfminecraft.tfmcweb.managers.WebCommand;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

class LuckPermsBridgeTest {
	TestState state;
	JavaPlugin plugin;
	Logger logger;
	PluginManager plugins;
	Plugin luckPerms;
	ScheduledExecutorService worker;
	List<Runnable> scheduled;
	MockedStatic<Bukkit> bukkit;
	MockedStatic<LuckPermsBridgeClient> api;
	RecordingStore store;
	AtomicLong clock;
	LuckPermsBridge bridge;
	/** Snapshots the site accepted. */
	List<String> uploads;
	/** Every results post, accepted or not. */
	List<List<ChangeResult>> posts;
	CallResult uploadAnswer = CallResult.success();
	CallResult postAnswer = CallResult.success();
	/** Command lists the site accepted. */
	List<String> commandUploads;
	CallResult commandsAnswer = CallResult.success();
	CommandCatalogue.Server commandList = server("tfmc");

	/** In-memory LuckPerms: the snapshot is whatever {@link #data} holds. */
	static final class RecordingStore implements BridgeStore {
		JsonObject data = data("staff");
		final List<Long> applied = new ArrayList<>();
		final Set<Long> refuse = new HashSet<>();
		final Set<Long> boom = new HashSet<>();
		RuntimeException snapshotFailure;
		Runnable duringApply = () -> {};
		Runnable duringSnapshot = () -> {};

		static JsonObject data(String group) {
			JsonObject root = new JsonObject();
			JsonArray groups = new JsonArray();
			JsonObject staff = new JsonObject();
			staff.addProperty("name", group);
			groups.add(staff);
			root.add("groups", groups);
			root.add("tracks", new JsonArray());
			root.add("users", new JsonArray());
			return root;
		}

		@Override public JsonObject snapshot() {
			duringSnapshot.run();
			if (snapshotFailure != null) {
				throw snapshotFailure;
			}
			return data.deepCopy();
		}

		@Override public Map<String, Function<String, Boolean>> groupChecks() {
			return Map.of("default", permission -> null, "staff", permission -> Boolean.TRUE);
		}

		@Override public ChangeResult apply(Change change) {
			duringApply.run();
			if (boom.contains(change.id)) {
				throw new IllegalStateException("boom");
			}
			applied.add(change.id);
			if (refuse.contains(change.id)) {
				return ChangeResult.failure(change.id, NODE_EXISTS);
			}
			return ChangeResult.success(change.id, null);
		}
	}

	@BeforeEach void setup() throws Exception {
		state = new TestState();
		plugin = mock(JavaPlugin.class);
		logger = mock(Logger.class);
		when(plugin.getLogger()).thenReturn(logger);
		plugins = mock(PluginManager.class);
		luckPerms = mock(Plugin.class);
		when(luckPerms.isEnabled()).thenReturn(true);
		when(plugins.getPlugin("LuckPerms")).thenReturn(luckPerms);
		worker = mock(ScheduledExecutorService.class);
		scheduled = new ArrayList<>();
		when(worker.scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any())).thenAnswer(call -> {
			scheduled.add(call.getArgument(0));
			return null;
		});
		when(worker.awaitTermination(anyLong(), any())).thenReturn(true);
		bukkit = mockStatic(Bukkit.class);
		bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
		uploads = new ArrayList<>();
		posts = new ArrayList<>();
		api = mockStatic(LuckPermsBridgeClient.class);
		api.when(LuckPermsBridgeClient::listChanges).thenReturn(ChangesResult.success(List.of()));
		api.when(() -> LuckPermsBridgeClient.putSnapshot(anyString())).thenAnswer(call -> {
			if (uploadAnswer.ok) {
				uploads.add(call.getArgument(0));
			}
			return uploadAnswer;
		});
		commandUploads = new ArrayList<>();
		api.when(() -> LuckPermsBridgeClient.putCommands(anyString())).thenAnswer(call -> {
			if (commandsAnswer.ok) {
				commandUploads.add(call.getArgument(0));
			}
			return commandsAnswer;
		});
		api.when(() -> LuckPermsBridgeClient.snapshotUnchanged(anyString(), anyLong()))
			.thenReturn(UnchangedResult.answered(true));
		api.when(() -> LuckPermsBridgeClient.postResults(anyCollection())).thenAnswer(call -> {
			posts.add(new ArrayList<>(call.<Collection<ChangeResult>>getArgument(0)));
			return postAnswer;
		});
		store = new RecordingStore();
		clock = new AtomicLong(1_791_321_779_000L);
		bridge = new LuckPermsBridge(plugin, () -> store, () -> worker, clock::get, () -> commandList);
		Cache.luckPermsBridgePublish = true;
		Cache.luckPermsBridgeApply = true;
		Cache.luckPermsBridgePollSeconds = 3;
		Cache.luckPermsBridgeSnapshotSeconds = 30;
		Cache.realmId = "main";
	}

	@AfterEach void cleanup() throws Exception {
		api.close();
		bukkit.close();
		state.close();
	}

	void start() {
		bridge.refresh();
		assertTrue(bridge.isRunning());
	}

	void tick() { scheduled.getLast().run(); }

	static Change change(long id, String description) {
		return new Change(id, "user", "u", "Ada", "web:ada", null, description, List.of());
	}

	void queue(Change... changes) {
		api.when(LuckPermsBridgeClient::listChanges).thenReturn(ChangesResult.success(List.of(changes)));
	}

	static CommandCatalogue.Server server(String command) {
		return new CommandCatalogue.Server(
			List.of(new CommandCatalogue.Entry("TFMCCore", command, List.of(), null, null, "tfmc.use", false)),
			Map.of("TFMCCore", "3.5.3"),
			Map.of()
		);
	}

	JsonObject lastUpload() { return JsonParser.parseString(uploads.getLast()).getAsJsonObject(); }

	@Test void staysIdleUntilEnabledAndLuckPermsIsInstalled() {
		Cache.luckPermsBridgePublish = false;
		Cache.luckPermsBridgeApply = false;
		bridge.refresh();
		assertFalse(bridge.isRunning());
		assertTrue(bridge.statusDetail().endsWith("bridge=off"));
		Cache.luckPermsBridgeApply = true;
		when(plugins.getPlugin("LuckPerms")).thenReturn(null);
		bridge.refresh();
		bridge.refresh();
		verify(logger, times(1)).warning(contains("not installed"));
		when(plugins.getPlugin("LuckPerms")).thenReturn(luckPerms);
		when(luckPerms.isEnabled()).thenReturn(false);
		bridge.refresh();
		bukkit.when(Bukkit::getPluginManager).thenReturn(null);
		bridge.refresh();
		assertFalse(bridge.isRunning());
		assertTrue(bridge.statusDetail().endsWith("bridge=off (LuckPerms unavailable)"));
		assertTrue(scheduled.isEmpty());
	}

	@Test void publishOnlyServersSnapshotWithoutPollingChanges() {
		Cache.luckPermsBridgeApply = false;
		start();
		verify(worker).scheduleWithFixedDelay(scheduled.getFirst(), 1L, 30L, TimeUnit.SECONDS);
		tick();
		api.verify(LuckPermsBridgeClient::listChanges, never());
		assertEquals(1, uploads.size());
		assertTrue(bridge.statusDetail().contains("publish=true apply=false poll=3s snapshot=30s bridge=on"));
	}

	@Test void applyingServersPollEveryFewSecondsAndOnlyOneWorkerRuns() throws Exception {
		Cache.luckPermsBridgePublish = false;
		Cache.luckPermsBridgePollSeconds = 0;
		start();
		verify(worker).scheduleWithFixedDelay(scheduled.getFirst(), 1L, 1L, TimeUnit.SECONDS);
		bridge.refresh();
		verify(worker).shutdown();
		verify(worker).awaitTermination(1L, TimeUnit.SECONDS);
		assertTrue(bridge.isRunning());
		when(worker.awaitTermination(anyLong(), any())).thenReturn(false);
		bridge.stop();
		verify(worker, times(2)).shutdown();
		verify(worker, never()).shutdownNow();
		bridge.stop();
		assertFalse(bridge.isRunning());
		tick();
		api.verify(LuckPermsBridgeClient::listChanges, never());
		assertTrue(uploads.isEmpty());
	}

	@Test void interruptedStopStillStopsTheWorker() throws Exception {
		start();
		when(worker.awaitTermination(anyLong(), any())).thenThrow(new InterruptedException());
		bridge.stop();
		assertTrue(Thread.interrupted());
		assertFalse(bridge.isRunning());
		verify(worker, never()).shutdownNow();
	}

	@Test void unavailableLuckPermsLeavesTheBridgeOff() {
		bridge = new LuckPermsBridge(plugin, () -> null, () -> worker, clock::get, () -> commandList);
		bridge.refresh();
		verify(logger).warning(contains("unavailable"));
		bridge = new LuckPermsBridge(plugin, () -> { throw new IllegalStateException("not loaded"); }, () -> worker, clock::get, () -> commandList);
		bridge.refresh();
		verify(logger).log(eq(Level.WARNING), contains("unavailable"), any(IllegalStateException.class));
		assertFalse(bridge.isRunning());
		assertTrue(scheduled.isEmpty());
		assertThrows(IllegalStateException.class, LuckPermsBridge::openLuckPerms);
		LuckPerms luckPermsApi = mock(LuckPerms.class);
		when(luckPermsApi.getMessagingService()).thenReturn(Optional.empty());
		try (MockedStatic<LuckPermsProvider> provider = mockStatic(LuckPermsProvider.class)) {
			provider.when(LuckPermsProvider::get).thenReturn(luckPermsApi);
			assertInstanceOf(LuckPermsBridgeStore.class, LuckPermsBridge.openLuckPerms());
		}
		LuckPermsBridge real = new LuckPermsBridge(plugin);
		real.refresh();
		assertFalse(real.isRunning());
		// No scheduler outside a server: the real reader fails rather than blocking.
		assertThrows(NullPointerException.class, () -> LuckPermsBridge.mainThreadReader(plugin).read());
	}

	@Test void workerIsOneNamedDaemonThread() throws Exception {
		ScheduledExecutorService real = LuckPermsBridge.newWorker();
		try {
			Thread thread = real.submit(Thread::currentThread).get();
			assertEquals("TFMCWeb-LuckPerms", thread.getName());
			assertTrue(thread.isDaemon());
		} finally {
			real.shutdownNow();
		}
	}

	@Test void snapshotIsUploadedInFullThenOnlyConfirmedWhileUnchanged() throws Exception {
		start();
		tick();
		JsonObject first = lastUpload();
		assertEquals("main", first.get("server").getAsString());
		assertEquals(1_791_321_779L, first.get("generated_at").getAsLong());
		assertEquals(1_791_321_779_000L, first.get("revision").getAsLong());
		assertEquals(LuckPermsBridge.sha256(RecordingStore.data("staff").toString()), first.get("hash").getAsString());
		assertEquals(64, first.get("hash").getAsString().length());
		assertEquals("staff", first.getAsJsonArray("groups").get(0).getAsJsonObject().get("name").getAsString());
		assertTrue(first.getAsJsonArray("tracks").isEmpty());
		assertTrue(first.getAsJsonArray("users").isEmpty());
		assertTrue(bridge.statusDetail().endsWith("last-snapshot=0s ago"));

		clock.addAndGet(29_999L);
		tick();
		assertEquals(1, uploads.size());
		api.verify(() -> LuckPermsBridgeClient.snapshotUnchanged(anyString(), anyLong()), never());
		clock.addAndGet(1L);
		tick();
		api.verify(() -> LuckPermsBridgeClient.snapshotUnchanged(first.get("hash").getAsString(), 1_791_321_809_000L));
		assertEquals(1, uploads.size());
		assertTrue(bridge.statusDetail().endsWith("last-snapshot=0s ago"));
		clock.addAndGet(5_000L);
		assertTrue(bridge.statusDetail().endsWith("last-snapshot=5s ago"));

		api.when(() -> LuckPermsBridgeClient.snapshotUnchanged(anyString(), anyLong()))
			.thenReturn(UnchangedResult.answered(false));
		clock.addAndGet(30_000L);
		tick();
		assertEquals(2, uploads.size());
		assertEquals(first.get("hash"), lastUpload().get("hash"));
		assertEquals(1_791_321_844_000L, lastUpload().get("revision").getAsLong());

		store.data = RecordingStore.data("admin");
		clock.addAndGet(30_000L);
		tick();
		assertEquals(3, uploads.size());
		assertNotEquals(first.get("hash"), lastUpload().get("hash"));
		api.verify(() -> LuckPermsBridgeClient.snapshotUnchanged(anyString(), anyLong()), times(2));
	}

	@Test void snapshotFailuresAreLoggedOncePerOutageAndRetried() {
		assertTrue(bridge.statusDetail().endsWith("bridge=off (LuckPerms unavailable)"));
		start();
		assertTrue(bridge.statusDetail().endsWith("last-snapshot=never"));
		uploadAnswer = CallResult.fail(0, "down");
		tick();
		clock.addAndGet(30_000L);
		tick();
		verify(logger, times(1)).warning("[luckperms] snapshot upload failed (site unreachable)");
		uploadAnswer = CallResult.success();
		clock.addAndGet(30_000L);
		tick();
		assertEquals(1, uploads.size());
		verify(logger).info("[luckperms] snapshot working again");
		api.verify(() -> LuckPermsBridgeClient.snapshotUnchanged(anyString(), anyLong()), never());

		api.when(() -> LuckPermsBridgeClient.snapshotUnchanged(anyString(), anyLong()))
			.thenReturn(UnchangedResult.fail(502, "bad gateway"));
		clock.addAndGet(30_000L);
		tick();
		verify(logger).warning("[luckperms] snapshot check failed (HTTP 502)");
		assertEquals(1, uploads.size());

		store.snapshotFailure = new IllegalStateException("storage down");
		api.when(() -> LuckPermsBridgeClient.snapshotUnchanged(anyString(), anyLong()))
			.thenReturn(UnchangedResult.answered(true));
		clock.addAndGet(30_000L);
		tick();
		verify(logger, never()).log(eq(Level.WARNING), eq("[luckperms] snapshot failed"), any(Exception.class));
		store.snapshotFailure = null;
		clock.addAndGet(30_000L);
		tick();
		verify(logger, times(2)).info("[luckperms] snapshot working again");
		store.snapshotFailure = new IllegalStateException("storage down");
		clock.addAndGet(30_000L);
		tick();
		verify(logger).log(eq(Level.WARNING), eq("[luckperms] snapshot failed"), same(store.snapshotFailure));
	}

	@Test void appliedChangesArePostedWithRevisionsAndFollowedBySnapshot() {
		start();
		tick();
		assertEquals(1, uploads.size());
		long snapshotRevision = lastUpload().get("revision").getAsLong();
		store.refuse.add(4L);
		queue(change(3, "parent add staff"), change(4, null), change(5, "x"));
		store.boom.add(5L);
		store.data = RecordingStore.data("changed");
		tick();
		assertEquals(List.of(3L, 4L), store.applied);
		List<ChangeResult> posted = posts.getLast();
		assertEquals(List.of(3L, 4L, 5L), posted.stream().map(result -> result.id).toList());
		assertTrue(posted.get(0).ok);
		assertEquals(BridgeStore.NODE_EXISTS, posted.get(1).error);
		assertEquals(BridgeStore.SAVE_FAILED, posted.get(2).error);
		assertEquals(List.of(snapshotRevision + 1, snapshotRevision + 2, snapshotRevision + 3),
			posted.stream().map(result -> result.revision).toList());
		assertEquals(2, uploads.size());
		assertEquals(snapshotRevision + 4, lastUpload().get("revision").getAsLong());
		verify(logger).info("[luckperms] applied change 3: parent add staff");
		verify(logger).warning("[luckperms] change 4 refused: node_exists");
		verify(logger).log(eq(Level.WARNING), eq("[luckperms] change 5 failed"), any(IllegalStateException.class));

		store.boom.clear();
		tick();
		assertEquals(List.of(3L, 4L), store.applied);
		assertEquals(1, posts.size());
		assertEquals(2, uploads.size());

		queue(change(4, null));
		store.refuse.clear();
		tick();
		assertEquals(List.of(3L, 4L), store.applied);
		queue(change(6, null));
		store.refuse.add(6L);
		tick();
		assertEquals(2, uploads.size());
		assertEquals(2, posts.size());
		verify(logger).info(contains("applied change"));
	}

	@Test void unpostedResultsAreKeptUntilTheSiteTakesThem() {
		start();
		tick();
		postAnswer = CallResult.fail(503, "down");
		queue(change(1, null));
		tick();
		queue(change(2, null));
		tick();
		verify(logger, times(1)).warning("[luckperms] change results post failed (HTTP 503)");
		assertEquals(List.of(1L, 2L), posts.getLast().stream().map(result -> result.id).toList());
		long firstRevision = posts.getLast().getFirst().revision;

		api.when(LuckPermsBridgeClient::listChanges).thenReturn(ChangesResult.fail(0, "down"));
		postAnswer = CallResult.success();
		tick();
		verify(logger, times(1)).warning("[luckperms] change poll failed (site unreachable)");
		assertEquals(List.of(1L, 2L), posts.getLast().stream().map(result -> result.id).toList());
		assertEquals(firstRevision, posts.getLast().getFirst().revision);
		verify(logger).info("[luckperms] results working again");
		int count = posts.size();
		tick();
		assertEquals(count, posts.size());
		api.when(LuckPermsBridgeClient::listChanges).thenReturn(ChangesResult.success(List.of()));
		tick();
		verify(logger).info("[luckperms] changes working again");
		assertEquals(List.of(1L, 2L), store.applied);
	}

	@Test void unexpectedPollErrorsAreCaughtAndTheSnapshotStillRuns() {
		api.when(LuckPermsBridgeClient::listChanges).thenThrow(new IllegalStateException("boom"));
		start();
		tick();
		verify(logger).log(eq(Level.WARNING), eq("[luckperms] change poll failed"), any(IllegalStateException.class));
		assertEquals(1, uploads.size());
	}

	@Test void stoppingMidBatchAnswersTheRestAsNotAppliedAndSkipsTheSnapshot() {
		start();
		queue(change(1, null), change(2, null));
		store.duringApply = bridge::stop;
		tick();
		assertEquals(List.of(1L), store.applied);
		List<ChangeResult> posted = posts.getLast();
		assertEquals(List.of(1L, 2L), posted.stream().map(result -> result.id).toList());
		assertTrue(posted.get(0).ok);
		assertEquals(BridgeStore.SAVE_FAILED, posted.get(1).error);
		assertTrue(posted.get(1).revision > posted.get(0).revision);
		verify(logger).warning("[luckperms] change 2 not applied: the bridge stopped");
		assertTrue(uploads.isEmpty());
	}

	@Test void aPassOutlivingARefreshNeverRunsBesideTheNewWorker() {
		start();
		Runnable oldPass = scheduled.getLast();
		queue(change(1, null), change(2, null));
		store.duringApply = () -> {
			store.duringApply = () -> {};
			bridge.refresh();
		};
		oldPass.run();
		assertEquals(List.of(1L), store.applied);
		assertEquals(List.of(1L, 2L), posts.getLast().stream().map(result -> result.id).toList());
		assertTrue(uploads.isEmpty());
		Runnable newPass = scheduled.getLast();
		assertNotSame(oldPass, newPass);

		oldPass.run();
		api.verify(LuckPermsBridgeClient::listChanges, times(1));
		newPass.run();
		api.verify(LuckPermsBridgeClient::listChanges, times(2));
		assertEquals(List.of(1L), store.applied);
		assertEquals(1, uploads.size());

		store.data = RecordingStore.data("changed");
		store.duringSnapshot = bridge::stop;
		clock.addAndGet(30_000L);
		newPass.run();
		assertEquals(1, uploads.size());
		api.verify(() -> LuckPermsBridgeClient.snapshotUnchanged(anyString(), anyLong()), never());
	}

	@Test void revisionsKeepRisingWhenTheClockDoesNot() {
		start();
		tick();
		long previous = lastUpload().get("revision").getAsLong();
		for (int i = 0; i < 3; i++) {
			queue(change(10 + i, null));
			store.data = RecordingStore.data("group" + i);
			tick();
			long result = posts.getLast().getFirst().revision;
			long snapshot = lastUpload().get("revision").getAsLong();
			assertTrue(result > previous);
			assertEquals(result + 1, snapshot);
			previous = snapshot;
		}
		bridge.refresh();
		clock.addAndGet(-60_000L);
		tick();
		assertEquals(previous + 1, lastUpload().get("revision").getAsLong());
	}

	@Test void dedupeGuardForgetsOldIds() {
		start();
		List<Change> many = new ArrayList<>();
		for (long id = 1; id <= LuckPermsBridge.REMEMBERED_RESULTS + 1; id++) {
			many.add(change(id, null));
		}
		queue(many.toArray(Change[]::new));
		tick();
		queue(change(1, null), change(LuckPermsBridge.REMEMBERED_RESULTS + 1, null));
		tick();
		assertEquals(LuckPermsBridge.REMEMBERED_RESULTS + 2, store.applied.size());
		assertEquals(1L, store.applied.getLast());
	}

	@Test void webStatusShowsTheBridge() {
		CommandSender sender = mock(CommandSender.class);
		when(sender.hasPermission(anyString())).thenReturn(true);
		List<String> messages = new ArrayList<>();
		doAnswer(call -> { messages.add(call.getArgument(0)); return null; }).when(sender).sendMessage(anyString());
		BukkitScheduler scheduler = mock(BukkitScheduler.class);
		bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
		when(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
			call.getArgument(1, Runnable.class).run();
			return null;
		});
		when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
			call.getArgument(1, Runnable.class).run();
			return null;
		});
		try (var client = mockStatic(net.tfminecraft.tfmcweb.api.ProvinceSystemClient.class)) {
			client.when(net.tfminecraft.tfmcweb.api.ProvinceSystemClient::ping)
				.thenReturn(net.tfminecraft.tfmcweb.api.ProvinceSystemClient.SimpleResult.success());
			WebCommand unwired = new WebCommand(plugin, new LinkCache(), mock(DiscordGateService.class), null);
			assertTrue(unwired.onCommand(sender, null, "web", new String[] { "status" }));
			start();
			WebCommand live = new WebCommand(plugin, new LinkCache(), mock(DiscordGateService.class), null, bridge);
			assertTrue(live.onCommand(sender, null, "web", new String[] { "reload" }));
		}
		assertTrue(messages.stream().anyMatch(line -> line.contains("luckperms-bridge: ") && line.endsWith("bridge=off (LuckPerms unavailable)")));
		assertTrue(messages.stream().anyMatch(line -> line.contains("luckperms-bridge: ") && line.contains("bridge=on last-snapshot=never")));
	}

	JsonObject lastCommands() { return JsonParser.parseString(commandUploads.getLast()).getAsJsonObject(); }

	@Test void commandListWaitsAMinuteThenUploadsWhenChangedOrEveryTenMinutes() {
		Cache.luckPermsBridgeApply = false;
		start();
		tick();
		clock.addAndGet(59_999L);
		tick();
		assertTrue(commandUploads.isEmpty());
		clock.addAndGet(1L);
		tick();
		JsonObject first = lastCommands();
		assertEquals("main", first.get("server").getAsString());
		assertEquals(1_791_321_839L, first.get("generated_at").getAsLong());
		assertEquals(64, first.get("hash").getAsString().length());
		assertEquals("[\"default\",\"staff\"]", first.getAsJsonArray("groups").toString());
		JsonObject command = first.getAsJsonArray("commands").get(0).getAsJsonObject();
		assertEquals("tfmc", command.get("name").getAsString());
		assertEquals("ny", command.get("access").getAsString());

		clock.addAndGet(60_000L);
		tick();
		assertEquals(1, commandUploads.size());
		commandList = server("guide");
		clock.addAndGet(60_000L);
		tick();
		assertEquals(2, commandUploads.size());
		assertEquals("guide", lastCommands().getAsJsonArray("commands").get(0).getAsJsonObject().get("name").getAsString());
		clock.addAndGet(600_000L);
		tick();
		assertEquals(3, commandUploads.size());
	}

	@Test void commandListFailuresAreLoggedOnceAndRetried() {
		Cache.luckPermsBridgeApply = false;
		commandsAnswer = CallResult.fail(503, "down");
		start();
		tick();
		clock.addAndGet(60_000L);
		tick();
		clock.addAndGet(60_000L);
		tick();
		verify(logger, times(1)).warning("[luckperms] command list upload failed (HTTP 503)");
		commandsAnswer = CallResult.success();
		clock.addAndGet(60_000L);
		tick();
		assertEquals(1, commandUploads.size());
		verify(logger).info("[luckperms] commands working again");

		IllegalStateException boom = new IllegalStateException("timed out");
		bridge = new LuckPermsBridge(plugin, () -> store, () -> worker, clock::get, () -> { throw boom; });
		bridge.refresh();
		tick();
		clock.addAndGet(60_000L);
		tick();
		verify(logger).log(Level.WARNING, "[luckperms] command list failed", boom);
	}

	@Test void supersededCommandReadUploadsNothing() {
		Cache.luckPermsBridgeApply = false;
		bridge = new LuckPermsBridge(plugin, () -> store, () -> worker, clock::get, () -> {
			bridge.stop();
			return commandList;
		});
		bridge.refresh();
		tick();
		clock.addAndGet(60_000L);
		tick();
		assertTrue(commandUploads.isEmpty());
	}
}
