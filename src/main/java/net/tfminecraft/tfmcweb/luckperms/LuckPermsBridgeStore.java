package net.tfminecraft.tfmcweb.luckperms;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.actionlog.Action;
import net.luckperms.api.actionlog.ActionLogger;
import net.luckperms.api.cacheddata.CachedPermissionData;
import net.luckperms.api.messaging.MessagingService;
import net.luckperms.api.model.data.DataMutateResult;
import net.luckperms.api.model.data.NodeMap;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.group.GroupManager;
import net.luckperms.api.model.user.User;
import net.luckperms.api.model.user.UserManager;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.NodeBuilder;
import net.luckperms.api.node.NodeEqualityPredicate;
import net.luckperms.api.node.matcher.NodeMatcher;
import net.luckperms.api.query.QueryOptions;
import net.luckperms.api.track.Track;
import net.luckperms.api.track.TrackManager;
import net.luckperms.api.util.Tristate;
import net.tfminecraft.tfmcweb.api.LuckPermsBridgeClient.Change;
import net.tfminecraft.tfmcweb.api.LuckPermsBridgeClient.ChangeResult;
import net.tfminecraft.tfmcweb.api.LuckPermsBridgeClient.NodeSpec;
import net.tfminecraft.tfmcweb.api.LuckPermsBridgeClient.Op;

/**
 * Reads and changes LuckPerms for the staff panel through the LuckPerms API.
 * Only the bridge's single worker thread calls it.
 */
public final class LuckPermsBridgeStore implements BridgeStore {

	static final UUID NIL = new UUID(0L, 0L);
	static final String ADD_NODE = "add_node";
	static final String REMOVE_NODE = "remove_node";
	static final String CREATE_GROUP = "create_group";
	static final String DELETE_GROUP = "delete_group";
	static final String CREATE_TRACK = "create_track";
	static final String DELETE_TRACK = "delete_track";
	static final String SET_GROUPS = "set_groups";
	static final String TARGET_IS_STAFF = "target_is_staff";
	static final String ROOT_ONLY = "root_only";

	private record Step(boolean add, Node node) {}

	private final UserManager users;
	private final GroupManager groups;
	private final TrackManager tracks;
	private final MessagingService messaging;
	private final ActionLogger actions;
	private final NameCache names;
	private final Logger logger;

	public LuckPermsBridgeStore(
		UserManager users,
		GroupManager groups,
		TrackManager tracks,
		MessagingService messaging,
		ActionLogger actions,
		Function<UUID, String> serverNames,
		LongSupplier clock,
		Logger logger
	) {
		this.users = users;
		this.groups = groups;
		this.tracks = tracks;
		this.messaging = messaging;
		this.actions = actions;
		this.names = new NameCache(serverNames, this::storedName, clock);
		this.logger = logger;
	}

	public static BridgeStore open() {
		LuckPerms luckPerms = LuckPermsProvider.get();
		return new LuckPermsBridgeStore(
			luckPerms.getUserManager(),
			luckPerms.getGroupManager(),
			luckPerms.getTrackManager(),
			luckPerms.getMessagingService().orElse(null),
			luckPerms.getActionLogger(),
			NameCache::bukkitName,
			System::currentTimeMillis,
			Logger.getLogger("TFMCWeb")
		);
	}

	private String storedName(UUID uuid) {
		return users.lookupUsername(uuid).join();
	}

	@Override
	public JsonObject snapshot() {
		List<Group> loadedGroups = new ArrayList<>(groups.getLoadedGroups());
		loadedGroups.sort(Comparator.comparing(Group::getName));
		JsonArray groupArray = new JsonArray();
		for (Group group : loadedGroups) {
			groupArray.add(groupState(group));
		}
		List<Track> loadedTracks = new ArrayList<>(tracks.getLoadedTracks());
		loadedTracks.sort(Comparator.comparing(Track::getName));
		JsonArray trackArray = new JsonArray();
		for (Track track : loadedTracks) {
			JsonObject json = new JsonObject();
			json.addProperty("name", track.getName());
			JsonArray members = new JsonArray();
			track.getGroups().forEach(members::add);
			json.add("groups", members);
			trackArray.add(json);
		}
		Map<UUID, Collection<Node>> stored = users.searchAll(NodeMatcher.keyStartsWith("")).join();
		List<UUID> ids = new ArrayList<>(stored.keySet());
		ids.sort(Comparator.comparing(UUID::toString));
		JsonArray userArray = new JsonArray();
		for (UUID id : ids) {
			Collection<Node> nodes = stored.get(id);
			if (nodes == null || nodes.isEmpty()) {
				continue;
			}
			userArray.add(userState(id, nodes));
		}
		JsonObject root = new JsonObject();
		root.add("groups", groupArray);
		root.add("tracks", trackArray);
		root.add("users", userArray);
		return root;
	}

	@Override
	public Map<String, Function<String, Boolean>> groupChecks() {
		QueryOptions options = LuckPermsProvider.get().getContextManager().getStaticQueryOptions();
		Map<String, Function<String, Boolean>> checks = new TreeMap<>();
		for (Group group : groups.getLoadedGroups()) {
			CachedPermissionData data = group.getCachedData().getPermissionData(options);
			checks.put(group.getName(), permission -> answer(data.checkPermission(permission)));
		}
		return checks;
	}

	static Boolean answer(Tristate value) {
		if (value == Tristate.UNDEFINED) {
			return null;
		}
		return Boolean.valueOf(value.asBoolean());
	}

	@Override
	public ChangeResult apply(Change change) {
		if (change.ops.isEmpty()) {
			return ChangeResult.failure(change.id, BAD_OP);
		}
		String type = change.targetType == null ? "" : change.targetType;
		if (change.guardGroups != null && !"user".equals(type)) {
			// Admins only manage players' groups; group and track edits are root's.
			return ChangeResult.failure(change.id, ROOT_ONLY);
		}
		LuckPermsWriteLock.LOCK.lock();
		try {
			switch (type) {
				case "user":
					return applyUser(change);
				case "group":
					return applyGroup(change);
				case "track":
					return applyTrack(change);
				default:
					return ChangeResult.failure(change.id, BAD_TARGET);
			}
		} finally {
			LuckPermsWriteLock.LOCK.unlock();
		}
	}

	private ChangeResult applyUser(Change change) {
		UUID uuid = parseUuid(change.target);
		if (uuid == null) {
			return ChangeResult.failure(change.id, BAD_TARGET);
		}
		if (!nodeOps(change.ops)) {
			return ChangeResult.failure(change.id, BAD_OP);
		}
		boolean loaded = false;
		User user = null;
		try {
			loaded = users.isLoaded(uuid);
			user = users.loadUser(uuid).join();
			NodeMap data = user.data();
			List<Step> steps = new ArrayList<>();
			String error = guard(change, data.toCollection());
			if (error == null) {
				error = plan(data.toCollection(), change.ops, steps);
			}
			if (error != null) {
				return ChangeResult.failure(change.id, error);
			}
			error = execute(data, steps);
			if (error != null) {
				reloadUser(uuid, loaded);
				return ChangeResult.failure(change.id, error);
			}
			users.saveUser(user).join();
			pushUser(user, change.id);
			String targetName = change.targetName != null ? change.targetName : uuid.toString();
			logAction(change, Action.Target.Type.USER, uuid, targetName);
			return ChangeResult.success(change.id, userState(uuid, user.data().toCollection()));
		} catch (RuntimeException e) {
			logger.log(Level.WARNING, "[luckperms] change " + change.id + " failed", e);
			reloadUser(uuid, loaded);
			return ChangeResult.failure(change.id, failureCode(e));
		} finally {
			if (!loaded && user != null) {
				cleanup(user, change.id);
			}
		}
	}

	private ChangeResult applyGroup(Change change) {
		String name = holderName(change.target);
		if (name == null) {
			return ChangeResult.failure(change.id, BAD_TARGET);
		}
		List<Op> ops = change.ops;
		boolean create = CREATE_GROUP.equals(ops.getFirst().type);
		boolean delete = DELETE_GROUP.equals(ops.getFirst().type);
		List<Op> nodeOps = create || delete ? ops.subList(1, ops.size()) : ops;
		if (delete && !nodeOps.isEmpty() || !nodeOps(nodeOps)) {
			return ChangeResult.failure(change.id, BAD_OP);
		}
		Group group = null;
		try {
			Group existing = groups.loadGroup(name).join().orElse(null);
			if (create && existing != null) {
				return ChangeResult.failure(change.id, GROUP_EXISTS);
			}
			if (!create && existing == null) {
				return ChangeResult.failure(change.id, GROUP_MISSING);
			}
			if (delete) {
				groups.deleteGroup(existing).join();
				return finishFullUpdate(change, Action.Target.Type.GROUP, name, JsonNull.INSTANCE);
			}
			List<Step> steps = new ArrayList<>();
			String error = plan(create ? List.of() : existing.data().toCollection(), nodeOps, steps);
			if (error != null) {
				return ChangeResult.failure(change.id, error);
			}
			group = create ? groups.createAndLoadGroup(name).join() : existing;
			error = execute(group.data(), steps);
			if (error != null) {
				discardGroup(group, name, create);
				return ChangeResult.failure(change.id, error);
			}
			if (!steps.isEmpty()) {
				groups.saveGroup(group).join();
			}
			return finishFullUpdate(change, Action.Target.Type.GROUP, name, groupState(group));
		} catch (RuntimeException e) {
			logger.log(Level.WARNING, "[luckperms] change " + change.id + " failed", e);
			if (group != null) {
				discardGroup(group, name, create);
			}
			return ChangeResult.failure(change.id, failureCode(e));
		}
	}

	private ChangeResult applyTrack(Change change) {
		String name = holderName(change.target);
		if (name == null) {
			return ChangeResult.failure(change.id, BAD_TARGET);
		}
		List<Op> ops = change.ops;
		boolean create = CREATE_TRACK.equals(ops.getFirst().type);
		boolean delete = DELETE_TRACK.equals(ops.getFirst().type);
		List<Op> setOps = create || delete ? ops.subList(1, ops.size()) : ops;
		if (delete && !setOps.isEmpty() || !groupListOps(setOps)) {
			return ChangeResult.failure(change.id, BAD_OP);
		}
		Track track = null;
		try {
			Track existing = tracks.loadTrack(name).join().orElse(null);
			if (create && existing != null) {
				return ChangeResult.failure(change.id, TRACK_EXISTS);
			}
			if (!create && existing == null) {
				return ChangeResult.failure(change.id, TRACK_MISSING);
			}
			if (delete) {
				tracks.deleteTrack(existing).join();
				return finishFullUpdate(change, Action.Target.Type.TRACK, name, JsonNull.INSTANCE);
			}
			List<List<Group>> lists = new ArrayList<>();
			for (Op op : setOps) {
				List<Group> members = new ArrayList<>();
				for (String member : op.groups) {
					Group group = findGroup(member.toLowerCase(Locale.ROOT));
					if (group == null) {
						return ChangeResult.failure(change.id, GROUP_MISSING);
					}
					members.add(group);
				}
				lists.add(members);
			}
			track = create ? tracks.createAndLoadTrack(name).join() : existing;
			for (List<Group> members : lists) {
				track.clearGroups();
				for (Group member : members) {
					if (track.appendGroup(member) != DataMutateResult.SUCCESS) {
						throw new IllegalStateException("LuckPerms refused track group " + member.getName());
					}
				}
			}
			if (!lists.isEmpty()) {
				tracks.saveTrack(track).join();
			}
			return finishFullUpdate(change, Action.Target.Type.TRACK, name, JsonNull.INSTANCE);
		} catch (RuntimeException e) {
			logger.log(Level.WARNING, "[luckperms] change " + change.id + " failed", e);
			if (track != null) {
				discardTrack(track, name, create);
			}
			return ChangeResult.failure(change.id, failureCode(e));
		}
	}

	private ChangeResult finishFullUpdate(Change change, Action.Target.Type type, String name, JsonElement state) {
		pushAll(change.id);
		logAction(change, type, null, name);
		return ChangeResult.success(change.id, state);
	}

	/**
	 * Limits an admin's change to players whose groups, with everything those groups
	 * inherit, stay within the admin groups, and to added groups that do the same.
	 * Checks live data, so a group that just gained a staff parent counts as staff;
	 * changes nothing.
	 *
	 * @return the refusal, or null when the change has no guard or passes it
	 */
	private String guard(Change change, Collection<Node> live) {
		if (change.guardGroups == null) {
			return null;
		}
		Set<String> allowed = new HashSet<>();
		for (String group : change.guardGroups) {
			allowed.add(group.toLowerCase(Locale.ROOT));
		}
		for (Node node : live) {
			String group = node == null || !node.getValue() ? null : inheritedGroup(node.getKey());
			if (group != null && !allowed.containsAll(inheritance(group))) {
				return TARGET_IS_STAFF;
			}
		}
		for (Op op : change.ops) {
			String group = ADD_NODE.equals(op.type) && op.node.value ? inheritedGroup(op.node.key) : null;
			if (group != null && !allowed.containsAll(inheritance(group))) {
				return ROOT_ONLY;
			}
		}
		return null;
	}

	/** The group and every group it inherits through positive nodes in any context. */
	private Set<String> inheritance(String root) {
		Set<String> seen = new HashSet<>();
		List<String> pending = new ArrayList<>(List.of(root));
		while (!pending.isEmpty()) {
			String name = pending.removeLast();
			if (!seen.add(name)) {
				continue;
			}
			Group group = findGroup(name);
			if (group == null) {
				continue;
			}
			for (Node node : group.data().toCollection()) {
				String parent = node == null || !node.getValue() ? null : inheritedGroup(node.getKey());
				if (parent != null) {
					pending.add(parent);
				}
			}
		}
		return seen;
	}

	/** Node ops only, each with a well-formed node. */
	private static boolean nodeOps(List<Op> ops) {
		for (Op op : ops) {
			if (!ADD_NODE.equals(op.type) && !REMOVE_NODE.equals(op.type) || op.node == null) {
				return false;
			}
		}
		return true;
	}

	/** set_groups ops only, each naming every group once. */
	private static boolean groupListOps(List<Op> ops) {
		for (Op op : ops) {
			if (!SET_GROUPS.equals(op.type) || op.groups == null) {
				return false;
			}
			Set<String> seen = new HashSet<>();
			for (String group : op.groups) {
				if (!seen.add(group.toLowerCase(Locale.ROOT))) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * Checks node ops in order against the holder's stored nodes, as each earlier op
	 * would leave them, and records the mutations to make.
	 *
	 * @return the failed precondition, or null when every op can apply
	 */
	private String plan(Collection<Node> live, List<Op> ops, List<Step> steps) {
		List<Node> working = new ArrayList<>(live);
		for (Op op : ops) {
			Node requested = build(op.node);
			if (requested == null) {
				return BAD_OP;
			}
			Node match = find(working, requested);
			if (ADD_NODE.equals(op.type)) {
				if (match != null) {
					return NODE_EXISTS;
				}
				String group = inheritedGroup(op.node.key);
				if (group != null && findGroup(group) == null) {
					return GROUP_MISSING;
				}
				working.add(requested);
				steps.add(new Step(true, requested));
			} else {
				if (match == null) {
					return NODE_MISSING;
				}
				working.remove(match);
				steps.add(new Step(false, match));
			}
		}
		return null;
	}

	/** @return the error for the first mutation LuckPerms refuses, or null */
	private static String execute(NodeMap data, List<Step> steps) {
		for (Step step : steps) {
			DataMutateResult result = step.add() ? data.add(step.node()) : data.remove(step.node());
			if (result == DataMutateResult.SUCCESS) {
				continue;
			}
			if (result == DataMutateResult.FAIL_ALREADY_HAS) {
				return NODE_EXISTS;
			}
			if (result == DataMutateResult.FAIL_LACKS) {
				return NODE_MISSING;
			}
			return SAVE_FAILED;
		}
		return null;
	}

	private static Node find(Collection<Node> nodes, Node requested) {
		for (Node node : nodes) {
			if (node != null && node.equals(requested, NodeEqualityPredicate.EXACT)) {
				return node;
			}
		}
		return null;
	}

	/** @return the LuckPerms node for the spec, or null when LuckPerms rejects it */
	private static Node build(NodeSpec spec) {
		try {
			NodeBuilder<?, ?> builder = Node.builder(spec.key).value(spec.value);
			for (Map.Entry<String, List<String>> context : spec.contexts.entrySet()) {
				for (String value : context.getValue()) {
					builder = builder.withContext(context.getKey(), value);
				}
			}
			if (spec.expiry > 0) {
				builder = builder.expiry(spec.expiry);
			}
			return builder.build();
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static String inheritedGroup(String key) {
		String lower = key.toLowerCase(Locale.ROOT);
		return lower.startsWith("group.") ? lower.substring("group.".length()) : null;
	}

	private Group findGroup(String name) {
		try {
			Group group = groups.getGroup(name);
			return group != null ? group : groups.loadGroup(name).join().orElse(null);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private JsonObject groupState(Group group) {
		JsonObject json = new JsonObject();
		json.addProperty("name", group.getName());
		json.addProperty("display_name", group.getDisplayName());
		json.addProperty("weight", group.getWeight().isPresent() ? Integer.valueOf(group.getWeight().getAsInt()) : null);
		json.add("nodes", nodes(group.data().toCollection()));
		return json;
	}

	private JsonObject userState(UUID uuid, Collection<Node> stored) {
		JsonObject json = new JsonObject();
		json.addProperty("uuid", uuid.toString());
		json.addProperty("name", names.name(uuid));
		json.add("nodes", nodes(stored));
		return json;
	}

	private static JsonArray nodes(Collection<Node> stored) {
		List<NodeSpec> specs = new ArrayList<>();
		for (Node node : stored) {
			if (node != null) {
				specs.add(spec(node));
			}
		}
		specs.sort(NodeSpec.ORDER);
		JsonArray array = new JsonArray();
		for (NodeSpec spec : specs) {
			array.add(spec.toJson());
		}
		return array;
	}

	private static NodeSpec spec(Node node) {
		long expiry = node.hasExpiry() ? node.getExpiry().getEpochSecond() : 0L;
		return new NodeSpec(node.getKey(), node.getValue(), node.getContexts().toMap(), expiry);
	}

	private void logAction(Change change, Action.Target.Type type, UUID target, String targetName) {
		try {
			Action.Builder builder = actions.actionBuilder()
				.timestamp(Instant.now())
				.source(actorUuid(change.actorUuid))
				.sourceName(change.actorName != null ? change.actorName : "web")
				.targetType(type)
				.targetName(targetName)
				.description(change.description != null ? change.description : "web change " + change.id);
			if (target != null) {
				builder = builder.target(target);
			}
			actions.submit(builder.build()).join();
		} catch (RuntimeException e) {
			logger.log(Level.WARNING, "[luckperms] action log failed for change " + change.id, e);
		}
	}

	private void pushUser(User user, long id) {
		if (messaging == null) {
			return;
		}
		try {
			messaging.pushUserUpdate(user);
		} catch (RuntimeException e) {
			logger.log(Level.WARNING, "[luckperms] messaging update failed for change " + id, e);
		}
	}

	private void pushAll(long id) {
		if (messaging == null) {
			return;
		}
		try {
			messaging.pushUpdate();
		} catch (RuntimeException e) {
			logger.log(Level.WARNING, "[luckperms] messaging update failed for change " + id, e);
		}
	}

	/** Discards unsaved in-memory edits of a user who stays loaded. */
	private void reloadUser(UUID uuid, boolean loaded) {
		if (!loaded) {
			return;
		}
		try {
			users.loadUser(uuid).join();
		} catch (RuntimeException e) {
			logger.log(Level.WARNING, "[luckperms] could not reload user " + uuid, e);
		}
	}

	/** Undoes a failed change: a group it created is deleted, an existing one reloaded. */
	private void discardGroup(Group group, String name, boolean created) {
		if (!created) {
			reloadGroup(name);
			return;
		}
		try {
			groups.deleteGroup(group).join();
		} catch (RuntimeException e) {
			logger.log(Level.WARNING, "[luckperms] could not remove partly created group " + name, e);
		}
	}

	/** Undoes a failed change: a track it created is deleted, an existing one reloaded. */
	private void discardTrack(Track track, String name, boolean created) {
		if (!created) {
			reloadTrack(name);
			return;
		}
		try {
			tracks.deleteTrack(track).join();
		} catch (RuntimeException e) {
			logger.log(Level.WARNING, "[luckperms] could not remove partly created track " + name, e);
		}
	}

	private void reloadGroup(String name) {
		try {
			groups.loadGroup(name).join();
		} catch (RuntimeException e) {
			logger.log(Level.WARNING, "[luckperms] could not reload group " + name, e);
		}
	}

	private void reloadTrack(String name) {
		try {
			tracks.loadTrack(name).join();
		} catch (RuntimeException e) {
			logger.log(Level.WARNING, "[luckperms] could not reload track " + name, e);
		}
	}

	private void cleanup(User user, long id) {
		try {
			users.cleanupUser(user);
		} catch (RuntimeException e) {
			logger.log(Level.WARNING, "[luckperms] cleanup failed after change " + id, e);
		}
	}

	/** LuckPerms rejects invalid names and the default group's deletion with IllegalArgumentException. */
	private static String failureCode(RuntimeException e) {
		return e instanceof IllegalArgumentException ? BAD_TARGET : SAVE_FAILED;
	}

	private static String holderName(String target) {
		return target == null ? null : target.toLowerCase(Locale.ROOT);
	}

	private static UUID actorUuid(String raw) {
		UUID uuid = parseUuid(raw);
		return uuid == null ? NIL : uuid;
	}

	private static UUID parseUuid(String raw) {
		if (raw == null) {
			return null;
		}
		try {
			UUID uuid = UUID.fromString(raw);
			return uuid.toString().equalsIgnoreCase(raw) ? uuid : null;
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
