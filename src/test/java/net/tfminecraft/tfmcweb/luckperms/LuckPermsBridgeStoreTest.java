package net.tfminecraft.tfmcweb.luckperms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.actionlog.Action;
import net.luckperms.api.actionlog.ActionLogger;
import net.luckperms.api.context.ImmutableContextSet;
import net.luckperms.api.messaging.MessagingService;
import net.luckperms.api.model.data.DataMutateResult;
import net.luckperms.api.model.data.NodeMap;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.group.GroupManager;
import net.luckperms.api.model.user.User;
import net.luckperms.api.model.user.UserManager;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.NodeBuilder;
import net.luckperms.api.node.NodeBuilderRegistry;
import net.luckperms.api.node.NodeEqualityPredicate;
import net.luckperms.api.node.ScopedNode;
import net.luckperms.api.node.matcher.NodeMatcher;
import net.luckperms.api.node.matcher.NodeMatcherFactory;
import net.luckperms.api.track.Track;
import net.luckperms.api.track.TrackManager;
import net.tfminecraft.tfmcweb.TestState;
import net.tfminecraft.tfmcweb.api.LuckPermsBridgeClient.Change;
import net.tfminecraft.tfmcweb.api.LuckPermsBridgeClient.ChangeResult;
import net.tfminecraft.tfmcweb.api.LuckPermsBridgeClient.NodeSpec;
import net.tfminecraft.tfmcweb.api.LuckPermsBridgeClient.Op;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.function.Function;
import net.luckperms.api.cacheddata.CachedDataManager;
import net.luckperms.api.cacheddata.CachedPermissionData;
import net.luckperms.api.context.ContextManager;
import net.luckperms.api.query.QueryOptions;
import net.luckperms.api.util.Tristate;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

class LuckPermsBridgeStoreTest {
	static final UUID ADA = UUID.fromString("5b0c0000-0000-4000-8000-000000000001");
	static final UUID ACTOR = UUID.fromString("aaaa0000-0000-4000-8000-000000000002");

	TestState state;
	UserManager users;
	GroupManager groups;
	TrackManager tracks;
	MessagingService messaging;
	ActionLogger actions;
	Action.Builder action;
	Logger logger;
	NodeBuilderRegistry registry;
	MockedStatic<LuckPermsProvider> provider;
	Map<UUID, String> serverNames;
	AtomicLong clock;
	User user;
	List<Node> userNodes;
	NodeMap userData;
	Map<String, Group> loadedGroups;

	@BeforeEach void setup() throws Exception {
		state = new TestState();
		users = mock(UserManager.class);
		groups = mock(GroupManager.class);
		tracks = mock(TrackManager.class);
		messaging = mock(MessagingService.class);
		actions = mock(ActionLogger.class);
		action = mock(Action.Builder.class, RETURNS_SELF);
		when(action.build()).thenReturn(mock(Action.class));
		when(actions.actionBuilder()).thenReturn(action);
		when(actions.submit(any())).thenReturn(CompletableFuture.completedFuture(null));
		logger = mock(Logger.class);
		registry = mock(NodeBuilderRegistry.class);
		when(registry.forKey(anyString())).thenAnswer(call -> builder(call.getArgument(0)));
		NodeMatcherFactory matchers = mock(NodeMatcherFactory.class);
		when(matchers.keyStartsWith("")).thenReturn(mock(NodeMatcher.class));
		LuckPerms api = mock(LuckPerms.class);
		when(api.getNodeBuilderRegistry()).thenReturn(registry);
		when(api.getNodeMatcherFactory()).thenReturn(matchers);
		provider = mockStatic(LuckPermsProvider.class);
		provider.when(LuckPermsProvider::get).thenReturn(api);
		serverNames = new HashMap<>();
		clock = new AtomicLong(0L);
		userNodes = new ArrayList<>();
		userData = nodeMap(userNodes);
		user = mock(User.class);
		when(user.data()).thenReturn(userData);
		when(users.loadUser(ADA)).thenReturn(CompletableFuture.completedFuture(user));
		when(users.saveUser(user)).thenReturn(CompletableFuture.completedFuture(null));
		when(users.lookupUsername(any())).thenReturn(CompletableFuture.completedFuture(null));
		loadedGroups = new LinkedHashMap<>();
		when(groups.getGroup(anyString())).thenAnswer(call -> loadedGroups.get(call.<String>getArgument(0)));
		when(groups.loadGroup(anyString())).thenAnswer(call ->
			CompletableFuture.completedFuture(Optional.ofNullable(loadedGroups.get(call.<String>getArgument(0)))));
		when(groups.saveGroup(any())).thenReturn(CompletableFuture.completedFuture(null));
		when(groups.deleteGroup(any())).thenReturn(CompletableFuture.completedFuture(null));
		when(tracks.loadTrack(anyString())).thenReturn(CompletableFuture.completedFuture(Optional.empty()));
		when(tracks.saveTrack(any())).thenReturn(CompletableFuture.completedFuture(null));
		when(tracks.deleteTrack(any())).thenReturn(CompletableFuture.completedFuture(null));
	}

	@AfterEach void cleanup() throws Exception { provider.close(); state.close(); }

	LuckPermsBridgeStore store() { return store(messaging); }

	LuckPermsBridgeStore store(MessagingService messages) {
		return new LuckPermsBridgeStore(users, groups, tracks, messages, actions, serverNames::get, clock::get, logger);
	}

	static Node node(String key, boolean value, Map<String, Set<String>> contexts, long expiry) {
		Node node = mock(ScopedNode.class);
		when(node.getKey()).thenReturn(key);
		when(node.getValue()).thenReturn(value);
		when(node.hasExpiry()).thenReturn(expiry > 0);
		when(node.getExpiry()).thenReturn(expiry > 0 ? Instant.ofEpochSecond(expiry) : null);
		ImmutableContextSet set = mock(ImmutableContextSet.class);
		when(set.toMap()).thenReturn(contexts);
		when(node.getContexts()).thenReturn(set);
		when(node.equals(any(Node.class), eq(NodeEqualityPredicate.EXACT))).thenAnswer(call -> {
			Node other = call.getArgument(0);
			return key.equals(other.getKey()) && value == other.getValue()
				&& other.hasExpiry() == expiry > 0
				&& (expiry == 0 || other.getExpiry().getEpochSecond() == expiry)
				&& contexts.equals(other.getContexts().toMap());
		});
		return node;
	}

	static Node node(String key) { return node(key, true, Map.of(), 0); }

	/** Mirrors LuckPerms' builder: records value, contexts and expiry, then builds a node. */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	static NodeBuilder builder(String key) {
		if (key.contains(" ")) {
			throw new IllegalArgumentException("bad key");
		}
		NodeBuilder builder = mock(NodeBuilder.class);
		boolean[] value = { true };
		long[] expiry = { 0 };
		Map<String, Set<String>> contexts = new TreeMap<>();
		when(builder.value(anyBoolean())).thenAnswer(call -> { value[0] = call.getArgument(0); return builder; });
		when(builder.withContext(anyString(), anyString())).thenAnswer(call -> {
			contexts.computeIfAbsent(call.getArgument(0), k -> new TreeSet<>()).add(call.getArgument(1));
			return builder;
		});
		when(builder.expiry(anyLong())).thenAnswer(call -> { expiry[0] = call.getArgument(0); return builder; });
		when(builder.build()).thenAnswer(call -> node(key, value[0], contexts, expiry[0]));
		return builder;
	}

	static NodeMap nodeMap(List<Node> live) {
		NodeMap map = mock(NodeMap.class);
		when(map.toCollection()).thenAnswer(call -> new ArrayList<>(live));
		when(map.add(any())).thenAnswer(call -> { live.add(call.getArgument(0)); return DataMutateResult.SUCCESS; });
		when(map.remove(any())).thenAnswer(call -> live.remove(call.<Node>getArgument(0))
			? DataMutateResult.SUCCESS : DataMutateResult.FAIL_LACKS);
		return map;
	}

	Group group(String name, String display, OptionalInt weight, Node... nodes) {
		Group group = mock(Group.class);
		when(group.getName()).thenReturn(name);
		when(group.getDisplayName()).thenReturn(display);
		when(group.getWeight()).thenReturn(weight);
		NodeMap data = nodeMap(new ArrayList<>(Arrays.asList(nodes)));
		when(group.data()).thenReturn(data);
		loadedGroups.put(name, group);
		return group;
	}

	Track track(String name, String... members) {
		Track track = mock(Track.class);
		when(track.getName()).thenReturn(name);
		when(track.getGroups()).thenReturn(List.of(members));
		when(track.appendGroup(any())).thenReturn(DataMutateResult.SUCCESS);
		return track;
	}

	static NodeSpec spec(String key) { return new NodeSpec(key, true, Map.of(), 0); }
	static Op add(NodeSpec node) { return new Op("add_node", node, null); }
	static Op remove(NodeSpec node) { return new Op("remove_node", node, null); }
	static Op op(String type) { return new Op(type, null, null); }
	static Op setGroups(String... names) { return new Op("set_groups", null, List.of(names)); }

	static Change change(String type, String target, Op... ops) {
		return new Change(7, type, target, null, null, null, null, List.of(ops));
	}

	static Change userChange(Op... ops) {
		return new Change(7, "user", ADA.toString(), "Ada", "web:won", ACTOR.toString(), "parent add staff", List.of(ops));
	}

	static Change guarded(Op... ops) {
		return new Change(7, "user", ADA.toString(), "Ada", "web:won", null, null, List.of(ops),
			List.of("Default", "commoner", "a", "b", "squire", "loner"));
	}

	static List<String> keys(JsonObject holder) {
		List<String> keys = new ArrayList<>();
		holder.getAsJsonArray("nodes").forEach(node -> keys.add(node.getAsJsonObject().get("key").getAsString()));
		return keys;
	}

	@Test void snapshotListsGroupsTracksAndStoredUsersInAStableOrder() {
		Node permanent = node("z.perm");
		Node contextual = node("a.perm", false, Map.of("world", Set.of("b", "a"), "server", Set.of("main")), 0);
		Node temporary = node("a.perm", true, Map.of(), 1_791_321_779L);
		Node global = node("a.perm");
		group("staff", null, OptionalInt.empty(), permanent, null, contextual, temporary, global);
		group("admin", "Admin", OptionalInt.of(200));
		when(groups.getLoadedGroups()).thenReturn(new java.util.HashSet<>(loadedGroups.values()));
		Track ladder = track("ladder", "staff", "admin");
		Track alpha = track("alpha");
		when(tracks.getLoadedTracks()).thenReturn(Set.of(ladder, alpha));
		UUID known = UUID.fromString("ffffffff-0000-4000-8000-000000000000");
		UUID stored = UUID.fromString("00000000-0000-4000-8000-000000000001");
		UUID empty = UUID.fromString("11111111-0000-4000-8000-000000000000");
		UUID missing = UUID.fromString("22222222-0000-4000-8000-000000000000");
		Map<UUID, Collection<Node>> all = new HashMap<>();
		all.put(known, List.of(node("group.staff")));
		all.put(stored, List.of(node("perm.b"), node("perm.a")));
		all.put(empty, List.of());
		all.put(missing, null);
		doReturn(CompletableFuture.completedFuture(all)).when(users).searchAll(any());
		serverNames.put(known, "Ada");
		when(users.lookupUsername(stored)).thenReturn(CompletableFuture.completedFuture("Bo"));

		LuckPermsBridgeStore store = store();
		JsonObject snapshot = store.snapshot();
		assertEquals(JsonParser.parseString("""
			{"groups":[
			  {"name":"admin","display_name":"Admin","weight":200,"nodes":[]},
			  {"name":"staff","display_name":null,"weight":null,"nodes":[
			    {"key":"a.perm","value":false,"contexts":{"server":["main"],"world":["a","b"]},"expiry":0},
			    {"key":"a.perm","value":true,"contexts":{},"expiry":0},
			    {"key":"a.perm","value":true,"contexts":{},"expiry":1791321779},
			    {"key":"z.perm","value":true,"contexts":{},"expiry":0}]}],
			 "tracks":[{"name":"alpha","groups":[]},{"name":"ladder","groups":["staff","admin"]}],
			 "users":[
			  {"uuid":"00000000-0000-4000-8000-000000000001","name":"Bo","nodes":[
			    {"key":"perm.a","value":true,"contexts":{},"expiry":0},
			    {"key":"perm.b","value":true,"contexts":{},"expiry":0}]},
			  {"uuid":"ffffffff-0000-4000-8000-000000000000","name":"Ada","nodes":[
			    {"key":"group.staff","value":true,"contexts":{},"expiry":0}]}]}
			"""), snapshot);
		assertEquals(List.of("groups", "tracks", "users"), new ArrayList<>(snapshot.keySet()));
		assertEquals(snapshot.toString(), store.snapshot().toString());
		verify(users, times(1)).lookupUsername(stored);
		verify(users, never()).lookupUsername(known);
	}

	@Test void malformedChangesAreRefusedWithoutTouchingLuckPerms() {
		LuckPermsBridgeStore store = store();
		assertEquals("bad_op", store.apply(change("user", ADA.toString())).error);
		assertEquals("bad_target", store.apply(change(null, "x", op("create_group"))).error);
		assertEquals("bad_target", store.apply(change("role", "x", op("create_group"))).error);
		for (String target : Arrays.asList(null, "nope", "5b0c-0-0-0-1", ADA.toString().replace('-', '_'))) {
			assertEquals("bad_target", store.apply(change("user", target, add(spec("a")))).error);
		}
		assertEquals("bad_op", store.apply(userChange(op("create_group"))).error);
		assertEquals("bad_op", store.apply(userChange(add(spec("a")), op("add_node"))).error);
		assertEquals("bad_op", store.apply(userChange(op("remove_node"))).error);
		assertEquals("bad_target", store.apply(change("group", null, op("create_group"))).error);
		assertEquals("bad_op", store.apply(change("group", "g", op("delete_group"), add(spec("a")))).error);
		assertEquals("bad_op", store.apply(change("group", "g", add(spec("a")), op("create_group"))).error);
		assertEquals("bad_op", store.apply(change("group", "g", op("set_groups"))).error);
		assertEquals("bad_target", store.apply(change("track", null, op("create_track"))).error);
		assertEquals("bad_op", store.apply(change("track", "t", op("delete_track"), setGroups("a"))).error);
		assertEquals("bad_op", store.apply(change("track", "t", op("add_node"))).error);
		assertEquals("bad_op", store.apply(change("track", "t", op("set_groups"))).error);
		assertEquals("bad_op", store.apply(change("track", "t", setGroups("a", "A"))).error);
		verifyNoInteractions(users, groups, tracks, actions, messaging);
	}

	@Test void userChangeIsAppliedSavedPushedAndLogged() {
		Node old = node("group.member");
		userNodes.add(old);
		group("staff", null, OptionalInt.empty());
		serverNames.put(ADA, "Ada");
		NodeSpec staff = new NodeSpec("group.staff", true, Map.of("server", List.of("main")), 1_791_321_779L);
		ChangeResult result = store().apply(userChange(remove(spec("group.member")), add(staff)));
		assertTrue(result.ok);
		assertNull(result.error);
		assertEquals(7L, result.id);
		verify(userData).remove(old);
		assertEquals(1, userNodes.size());
		assertEquals("group.staff", userNodes.getFirst().getKey());
		verify(users).saveUser(user);
		verify(messaging).pushUserUpdate(user);
		verify(users).cleanupUser(user);
		assertEquals(JsonParser.parseString("""
			{"uuid":"5b0c0000-0000-4000-8000-000000000001","name":"Ada","nodes":[
			  {"key":"group.staff","value":true,"contexts":{"server":["main"]},"expiry":1791321779}]}
			"""), result.state);
		verify(action).source(ACTOR);
		verify(action).sourceName("web:won");
		verify(action).targetType(Action.Target.Type.USER);
		verify(action).target(ADA);
		verify(action).targetName("Ada");
		verify(action).description("parent add staff");
		verify(action).timestamp(any(Instant.class));
		verify(actions).submit(any(Action.class));
	}

	@Test void preconditionsAreCheckedAgainstAStagedCopyBeforeAnyMutation() {
		Node staff = node("perm.staff");
		userNodes.add(staff);
		LuckPermsBridgeStore store = store();
		assertEquals("node_exists", store.apply(userChange(add(spec("perm.new")), add(spec("perm.staff")))).error);
		assertEquals("node_missing", store.apply(userChange(remove(spec("perm.staff")), remove(spec("perm.staff")))).error);
		assertEquals("node_missing", store.apply(userChange(remove(new NodeSpec("perm.staff", false, Map.of(), 0)))).error);
		assertEquals("node_missing", store.apply(userChange(remove(new NodeSpec("perm.staff", true, Map.of(), 5)))).error);
		assertEquals("node_missing", store.apply(userChange(remove(new NodeSpec("perm.staff", true, Map.of("server", List.of("main")), 0)))).error);
		assertEquals("bad_op", store.apply(userChange(add(spec("perm.new")), add(spec("bad key")))).error);
		assertEquals("group_missing", store.apply(userChange(add(spec("Group.Ghost")))).error);
		when(groups.getGroup("broken")).thenThrow(new IllegalArgumentException("invalid name"));
		assertEquals("group_missing", store.apply(userChange(add(spec("group.broken")))).error);
		verify(userData, never()).add(any());
		verify(userData, never()).remove(any());
		verify(users, never()).saveUser(any());
		verify(users, times(8)).cleanupUser(user);
		verifyNoInteractions(actions, messaging);

		assertTrue(store.apply(userChange(add(spec("perm.temp")), remove(spec("perm.temp")))).ok);
		assertTrue(store.apply(userChange(remove(spec("perm.staff")), add(spec("perm.staff")))).ok);
		assertEquals(List.of("perm.staff"), userNodes.stream().map(Node::getKey).toList());
	}

	@Test void storageOnlyGroupsMissingDetailsAndMissingMessagingStillApply() {
		Group late = mock(Group.class);
		when(groups.loadGroup("late")).thenReturn(CompletableFuture.completedFuture(Optional.of(late)));
		Change change = new Change(9, "user", ADA.toString(), null, null, "not-a-uuid", null, List.of(add(spec("group.late"))));
		ChangeResult result = store(null).apply(change);
		assertTrue(result.ok);
		assertTrue(result.state.getAsJsonObject().get("name").isJsonNull());
		verify(action).source(LuckPermsBridgeStore.NIL);
		verify(action).sourceName("web");
		verify(action).targetName(ADA.toString());
		verify(action).description("web change 9");
		Track solo = track("solo");
		when(tracks.createAndLoadTrack("solo")).thenReturn(CompletableFuture.completedFuture(solo));
		assertTrue(store(null).apply(change("track", "solo", op("create_track"))).ok);
		verifyNoInteractions(messaging);
	}

	@Test void guardKeepsAdminsAwayFromStaffPlayersAndStaffGroups() {
		group("default", null, OptionalInt.empty(), node("perm.chat"), null);
		group("commoner", null, OptionalInt.empty(), node("group.DEFAULT", true, Map.of("server", Set.of("main")), 0));
		group("mod", null, OptionalInt.empty());
		group("squire", null, OptionalInt.empty(), node("group.knight", true, Map.of(), 1_791_321_779L));
		group("knight", null, OptionalInt.empty(), node("group.mod", true, Map.of("world", Set.of("vardera")), 0));
		group("a", null, OptionalInt.empty(), node("group.b"));
		group("b", null, OptionalInt.empty(), node("group.a"), node("group.mod", false, Map.of(), 0));
		group("loner", null, OptionalInt.empty(), node("group.ghost"));
		LuckPermsBridgeStore store = store();

		userNodes.add(node("group.mod", true, Map.of("server", Set.of("dev")), 1_791_321_779L));
		assertEquals("target_is_staff", store.apply(guarded(add(spec("perm.x")))).error);
		userNodes.clear();
		userNodes.add(node("group.Knight"));
		assertEquals("target_is_staff", store.apply(guarded(remove(spec("group.Knight")))).error);
		userNodes.clear();
		userNodes.add(node("Group.SQUIRE", true, Map.of("server", Set.of("main")), 0));
		assertEquals("target_is_staff", store.apply(guarded(remove(spec("Group.SQUIRE")))).error);
		userNodes.clear();
		userNodes.add(node("group.loner"));
		assertEquals("target_is_staff", store.apply(guarded(add(spec("perm.x")))).error);
		userNodes.clear();
		assertEquals("root_only", store.apply(guarded(add(spec("group.mod")))).error);
		assertEquals("root_only", store.apply(guarded(add(spec("group.Squire")))).error);
		assertEquals("root_only", store.apply(guarded(add(spec("group.commoner")), add(spec("group.knight")))).error);
		assertEquals("root_only", store.apply(guarded(add(spec("group.loner")))).error);
		verify(userData, never()).add(any());
		verify(users, never()).saveUser(any());
		verifyNoInteractions(actions);

		assertEquals("root_only", store.apply(new Change(8, "group", "mod", null, null, null, null,
			List.of(op("delete_group")), List.of("mod"))).error);
		assertEquals("root_only", store.apply(new Change(8, "track", "staff", null, null, null, null,
			List.of(op("delete_track")), List.of())).error);
		verify(groups, never()).deleteGroup(any());
		verifyNoInteractions(tracks);

		userNodes.add(node("group.mod", false, Map.of(), 0));
		userNodes.add(node("group.default"));
		userNodes.add(null);
		assertTrue(store.apply(guarded(add(spec("group.Commoner")), add(spec("group.a")), add(spec("perm.x")),
			add(new NodeSpec("group.knight", false, Map.of(), 0)))).ok);
		assertTrue(store.apply(new Change(9, "user", ADA.toString(), null, null, null, null,
			List.of(add(spec("group.knight"))))).ok);
	}

	@Test void changesHoldTheSharedWriteLockAndWaitForIt() throws Exception {
		List<Boolean> held = new ArrayList<>();
		when(users.saveUser(user)).thenAnswer(call -> {
			held.add(LuckPermsWriteLock.LOCK.isHeldByCurrentThread());
			return CompletableFuture.completedFuture(null);
		});
		Track ladder = track("ladder");
		when(tracks.createAndLoadTrack("ladder")).thenAnswer(call -> {
			held.add(LuckPermsWriteLock.LOCK.isHeldByCurrentThread());
			return CompletableFuture.completedFuture(ladder);
		});
		Group bare = mock(Group.class);
		when(bare.getWeight()).thenReturn(OptionalInt.empty());
		NodeMap bareData = nodeMap(new ArrayList<>());
		when(bare.data()).thenReturn(bareData);
		when(groups.createAndLoadGroup("bare")).thenAnswer(call -> {
			held.add(LuckPermsWriteLock.LOCK.isHeldByCurrentThread());
			return CompletableFuture.completedFuture(bare);
		});
		LuckPermsBridgeStore store = store();
		assertTrue(store.apply(userChange(add(spec("perm.a")))).ok);
		assertTrue(store.apply(change("track", "ladder", op("create_track"))).ok);
		assertTrue(store.apply(change("group", "bare", op("create_group"))).ok);
		assertEquals("bad_target", store.apply(change("role", "x", op("create_group"))).error);
		assertEquals(List.of(true, true, true), held);
		assertFalse(LuckPermsWriteLock.LOCK.isLocked());

		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		Thread patreon = new Thread(() -> {
			LuckPermsWriteLock.LOCK.lock();
			try {
				locked.countDown();
				release.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} finally {
				LuckPermsWriteLock.LOCK.unlock();
			}
		});
		patreon.start();
		assertTrue(locked.await(5, TimeUnit.SECONDS));
		Track queued = track("queued");
		when(tracks.createAndLoadTrack("queued")).thenReturn(CompletableFuture.completedFuture(queued));
		CompletableFuture<ChangeResult> waiting = CompletableFuture.supplyAsync(
			() -> store.apply(change("track", "queued", op("create_track"))));
		Thread.sleep(200);
		assertFalse(waiting.isDone());
		verify(tracks, never()).loadTrack("queued");
		release.countDown();
		assertTrue(waiting.get(5, TimeUnit.SECONDS).ok);
		patreon.join(5_000);
	}

	@Test void loadedUserStaysLoadedAndRefusedMutationsAreReloaded() {
		when(users.isLoaded(ADA)).thenReturn(true);
		LuckPermsBridgeStore store = store();
		assertTrue(store.apply(userChange(add(spec("perm.a")))).ok);
		verify(users, never()).cleanupUser(any());
		verify(users, times(1)).loadUser(ADA);

		doReturn(DataMutateResult.FAIL_ALREADY_HAS).when(userData).add(any());
		assertEquals("node_exists", store.apply(userChange(add(spec("perm.b")))).error);
		verify(users, times(3)).loadUser(ADA);
		doReturn(DataMutateResult.FAIL).when(userData).add(any());
		assertEquals("save_failed", store.apply(userChange(add(spec("perm.b")))).error);
		doReturn(DataMutateResult.FAIL_LACKS).when(userData).remove(any());
		assertEquals("node_missing", store.apply(userChange(remove(spec("perm.a")))).error);
		verify(users, times(1)).saveUser(user);
	}

	@Test void failedSaveReloadsALoadedUserAndReportsSaveFailed() {
		when(users.isLoaded(ADA)).thenReturn(true);
		when(users.saveUser(user)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("db down")));
		LuckPermsBridgeStore store = store();
		assertEquals("save_failed", store.apply(userChange(add(spec("perm.a")))).error);
		verify(users, times(2)).loadUser(ADA);
		verify(logger).log(eq(Level.WARNING), eq("[luckperms] change 7 failed"), any(RuntimeException.class));
		verifyNoInteractions(actions, messaging);

		when(users.loadUser(ADA)).thenReturn(
			CompletableFuture.completedFuture(user),
			CompletableFuture.failedFuture(new IllegalStateException("db down")));
		assertEquals("save_failed", store.apply(userChange(add(spec("perm.b")))).error);
		verify(logger).log(eq(Level.WARNING), eq("[luckperms] could not reload user " + ADA), any(RuntimeException.class));
	}

	@Test void failedSaveOfAnOfflineUserIsOnlyCleanedUp() {
		when(users.saveUser(user)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("db down")));
		doThrow(new IllegalStateException("cleanup")).when(users).cleanupUser(user);
		assertEquals("save_failed", store().apply(userChange(add(spec("perm.a")))).error);
		verify(users, times(1)).loadUser(ADA);
		verify(logger).log(eq(Level.WARNING), eq("[luckperms] cleanup failed after change 7"), any(RuntimeException.class));
		when(users.loadUser(ADA)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("down")));
		assertEquals("save_failed", store().apply(userChange(add(spec("perm.a")))).error);
	}

	@Test void messagingAndActionLogFailuresDoNotFailTheChange() {
		doThrow(new IllegalStateException("redis")).when(messaging).pushUserUpdate(user);
		doThrow(new IllegalStateException("redis")).when(messaging).pushUpdate();
		when(actions.submit(any())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("log")));
		Group created = mock(Group.class);
		when(created.getWeight()).thenReturn(OptionalInt.empty());
		NodeMap createdData = nodeMap(new ArrayList<>());
		when(created.data()).thenReturn(createdData);
		when(groups.createAndLoadGroup("new")).thenReturn(CompletableFuture.completedFuture(created));
		LuckPermsBridgeStore store = store();
		assertTrue(store.apply(userChange(add(spec("perm.a")))).ok);
		assertTrue(store.apply(change("group", "new", op("create_group"))).ok);
		verify(logger, times(2)).log(eq(Level.WARNING), eq("[luckperms] messaging update failed for change 7"), any(RuntimeException.class));
		verify(logger, times(2)).log(eq(Level.WARNING), eq("[luckperms] action log failed for change 7"), any(RuntimeException.class));
	}

	@Test void groupsAreCreatedWithNodesEditedAndDeleted() {
		Group created = mock(Group.class);
		when(created.getName()).thenReturn("helper");
		when(created.getWeight()).thenReturn(OptionalInt.of(5));
		List<Node> createdNodes = new ArrayList<>();
		NodeMap createdData = nodeMap(createdNodes);
		when(created.data()).thenReturn(createdData);
		when(groups.createAndLoadGroup("helper")).thenReturn(CompletableFuture.completedFuture(created));
		group("staff", null, OptionalInt.empty());
		LuckPermsBridgeStore store = store();

		ChangeResult made = store.apply(change("group", "Helper", op("create_group"), add(spec("perm.help")), add(spec("group.staff"))));
		assertTrue(made.ok);
		assertEquals(List.of("group.staff", "perm.help"), keys(made.state.getAsJsonObject()));
		assertEquals(5, made.state.getAsJsonObject().get("weight").getAsInt());
		verify(groups).saveGroup(created);
		verify(messaging).pushUpdate();
		verify(action).targetType(Action.Target.Type.GROUP);
		verify(action).targetName("helper");
		verify(action, never()).target(any());

		group("empty", null, OptionalInt.empty());
		Group bare = mock(Group.class);
		when(bare.getWeight()).thenReturn(OptionalInt.empty());
		NodeMap bareData = nodeMap(new ArrayList<>());
		when(bare.data()).thenReturn(bareData);
		when(groups.createAndLoadGroup("bare")).thenReturn(CompletableFuture.completedFuture(bare));
		assertTrue(store.apply(change("group", "bare", op("create_group"))).ok);
		verify(groups, never()).saveGroup(bare);
		assertEquals("group_exists", store.apply(change("group", "staff", op("create_group"))).error);

		Node perm = node("perm.x");
		Group edited = group("mod", null, OptionalInt.empty(), perm);
		ChangeResult edit = store.apply(change("group", "mod", remove(spec("perm.x")), add(spec("perm.y"))));
		assertEquals(List.of("perm.y"), keys(edit.state.getAsJsonObject()));
		verify(groups).saveGroup(edited);
		assertEquals("node_exists", store.apply(change("group", "mod", add(spec("perm.y")))).error);
		assertEquals("group_missing", store.apply(change("group", "ghost", add(spec("perm.y")))).error);
		assertEquals("group_missing", store.apply(change("group", "ghost", op("delete_group"))).error);

		ChangeResult deleted = store.apply(change("group", "mod", op("delete_group")));
		assertTrue(deleted.ok);
		assertEquals(JsonNull.INSTANCE, deleted.state);
		verify(groups).deleteGroup(edited);
		verify(messaging, times(4)).pushUpdate();
	}

	@Test void groupFailuresReloadTheGroupAndInvalidNamesAreBadTargets() {
		Group mod = group("mod", null, OptionalInt.empty());
		NodeMap modData = mod.data();
		when(groups.saveGroup(mod)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("db")));
		LuckPermsBridgeStore store = store();
		assertEquals("save_failed", store.apply(change("group", "mod", add(spec("perm.a")))).error);
		verify(groups, times(2)).loadGroup("mod");

		doReturn(DataMutateResult.FAIL_ALREADY_HAS).when(modData).add(any());
		assertEquals("node_exists", store.apply(change("group", "mod", add(spec("perm.b")))).error);
		verify(groups, times(4)).loadGroup("mod");

		when(groups.loadGroup("mod")).thenReturn(
			CompletableFuture.completedFuture(Optional.of(mod)),
			CompletableFuture.failedFuture(new IllegalStateException("db")));
		doReturn(DataMutateResult.SUCCESS).when(modData).add(any());
		assertEquals("save_failed", store.apply(change("group", "mod", add(spec("perm.c")))).error);
		verify(logger).log(eq(Level.WARNING), eq("[luckperms] could not reload group mod"), any(RuntimeException.class));

		Group created = mock(Group.class);
		List<Node> createdNodes = new ArrayList<>();
		NodeMap createdData = nodeMap(createdNodes);
		when(created.data()).thenReturn(createdData);
		when(groups.createAndLoadGroup("fresh")).thenReturn(CompletableFuture.completedFuture(created));
		when(groups.saveGroup(created)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("db")));
		assertEquals("save_failed", store.apply(change("group", "fresh", op("create_group"), add(spec("perm.a")))).error);
		verify(groups).deleteGroup(created);
		doReturn(DataMutateResult.FAIL).when(createdData).add(any());
		when(groups.deleteGroup(created)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("db")));
		assertEquals("save_failed", store.apply(change("group", "fresh", op("create_group"), add(spec("perm.a")))).error);
		verify(groups, times(2)).deleteGroup(created);
		verify(logger).log(eq(Level.WARNING), eq("[luckperms] could not remove partly created group fresh"), any(RuntimeException.class));
		verify(groups, times(2)).loadGroup("fresh");
		verify(groups, times(1)).saveGroup(created);

		when(groups.loadGroup("bad name")).thenThrow(new IllegalArgumentException("invalid"));
		assertEquals("bad_target", store.apply(change("group", "bad name", op("create_group"))).error);
		Group fallback = group("default", null, OptionalInt.empty());
		when(groups.deleteGroup(fallback)).thenThrow(new IllegalArgumentException("default group"));
		assertEquals("bad_target", store.apply(change("group", "default", op("delete_group"))).error);
		verify(groups, times(1)).loadGroup("default");
		verifyNoInteractions(actions);
	}

	@Test void tracksAreCreatedReorderedAndDeleted() {
		Group staff = group("staff", null, OptionalInt.empty());
		Track existing = track("ladder");
		Track created = track("new");
		Group late = mock(Group.class);
		when(groups.loadGroup("late")).thenReturn(CompletableFuture.completedFuture(Optional.of(late)));
		when(tracks.loadTrack("ladder")).thenReturn(CompletableFuture.completedFuture(Optional.of(existing)));
		when(tracks.createAndLoadTrack("new")).thenReturn(CompletableFuture.completedFuture(created));
		LuckPermsBridgeStore store = store();

		ChangeResult made = store.apply(change("track", "NEW", op("create_track"), setGroups("Staff", "late")));
		assertTrue(made.ok);
		assertEquals(JsonNull.INSTANCE, made.state);
		var order = inOrder(created, tracks);
		order.verify(created).clearGroups();
		order.verify(created).appendGroup(staff);
		order.verify(created).appendGroup(late);
		order.verify(tracks).saveTrack(created);
		verify(messaging).pushUpdate();
		verify(action).targetType(Action.Target.Type.TRACK);
		verify(action).targetName("new");

		assertTrue(store.apply(change("track", "ladder", setGroups("staff"), setGroups("late"))).ok);
		verify(existing, times(2)).clearGroups();
		verify(tracks).saveTrack(existing);
		assertEquals("track_exists", store.apply(change("track", "ladder", op("create_track"))).error);
		assertEquals("track_missing", store.apply(change("track", "ghost", setGroups("staff"))).error);
		assertEquals("track_missing", store.apply(change("track", "ghost", op("delete_track"))).error);
		assertEquals("group_missing", store.apply(change("track", "ladder", setGroups("staff", "ghost"))).error);
		Track blank = track("blank");
		when(tracks.createAndLoadTrack("blank")).thenReturn(CompletableFuture.completedFuture(blank));
		assertTrue(store.apply(change("track", "blank", op("create_track"))).ok);
		verify(tracks, never()).saveTrack(blank);

		assertTrue(store.apply(change("track", "ladder", op("delete_track"))).ok);
		verify(tracks).deleteTrack(existing);
		verify(messaging, times(4)).pushUpdate();
	}

	@Test void trackFailuresReloadTheTrack() {
		group("staff", null, OptionalInt.empty());
		Track ladder = track("ladder");
		when(tracks.loadTrack("ladder")).thenReturn(CompletableFuture.completedFuture(Optional.of(ladder)));
		when(ladder.appendGroup(any())).thenReturn(DataMutateResult.FAIL);
		LuckPermsBridgeStore store = store();
		assertEquals("save_failed", store.apply(change("track", "ladder", setGroups("staff"))).error);
		verify(tracks, times(2)).loadTrack("ladder");
		verify(tracks, never()).saveTrack(any());

		when(ladder.appendGroup(any())).thenReturn(DataMutateResult.SUCCESS);
		when(tracks.saveTrack(ladder)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("db")));
		when(tracks.loadTrack("ladder")).thenReturn(
			CompletableFuture.completedFuture(Optional.of(ladder)),
			CompletableFuture.failedFuture(new IllegalStateException("db")));
		assertEquals("save_failed", store.apply(change("track", "ladder", setGroups("staff"))).error);
		verify(logger).log(eq(Level.WARNING), eq("[luckperms] could not reload track ladder"), any(RuntimeException.class));

		Track fresh = track("fresh");
		when(tracks.createAndLoadTrack("fresh")).thenReturn(CompletableFuture.completedFuture(fresh));
		when(fresh.appendGroup(any())).thenReturn(DataMutateResult.FAIL);
		assertEquals("save_failed", store.apply(change("track", "fresh", op("create_track"), setGroups("staff"))).error);
		verify(tracks).deleteTrack(fresh);
		when(fresh.appendGroup(any())).thenReturn(DataMutateResult.SUCCESS);
		when(tracks.saveTrack(fresh)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("db")));
		when(tracks.deleteTrack(fresh)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("db")));
		assertEquals("save_failed", store.apply(change("track", "fresh", op("create_track"), setGroups("staff"))).error);
		verify(tracks, times(2)).deleteTrack(fresh);
		verify(logger).log(eq(Level.WARNING), eq("[luckperms] could not remove partly created track fresh"), any(RuntimeException.class));
		verify(tracks, times(2)).loadTrack("fresh");

		when(tracks.loadTrack("broken")).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("db")));
		assertEquals("save_failed", store.apply(change("track", "broken", op("delete_track"))).error);
		verifyNoInteractions(actions, messaging);
	}

	@Test void groupChecksAskEachGroupOnThisServer() {
		QueryOptions options = mock(QueryOptions.class);
		ContextManager contexts = mock(ContextManager.class);
		when(contexts.getStaticQueryOptions()).thenReturn(options);
		when(LuckPermsProvider.get().getContextManager()).thenReturn(contexts);
		Group staff = mock(Group.class);
		when(staff.getName()).thenReturn("staff");
		CachedDataManager cached = mock(CachedDataManager.class);
		CachedPermissionData permissions = mock(CachedPermissionData.class);
		when(staff.getCachedData()).thenReturn(cached);
		when(cached.getPermissionData(options)).thenReturn(permissions);
		when(permissions.checkPermission("a")).thenReturn(Tristate.TRUE);
		when(permissions.checkPermission("b")).thenReturn(Tristate.FALSE);
		when(permissions.checkPermission("c")).thenReturn(Tristate.UNDEFINED);
		when(groups.getLoadedGroups()).thenReturn(Set.of(staff));

		Map<String, Function<String, Boolean>> checks = store().groupChecks();

		assertEquals(Set.of("staff"), checks.keySet());
		assertEquals(Boolean.TRUE, checks.get("staff").apply("a"));
		assertEquals(Boolean.FALSE, checks.get("staff").apply("b"));
		assertNull(checks.get("staff").apply("c"));
	}
}
