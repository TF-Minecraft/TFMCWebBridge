package net.tfminecraft.tfmcweb.api;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import net.tfminecraft.tfmcweb.api.ProvinceSystemGateway.GatewayResult;

/**
 * ProvinceSystem staff-panel LuckPerms routes. HTTP goes through {@link ProvinceSystemGateway}.
 * The site's protocol is described in ProvinceSystem backend/src/luckperms/README.md.
 */
public final class LuckPermsBridgeClient {

	static final String SNAPSHOT = "/luckperms/plugin/snapshot";
	static final String SNAPSHOT_UNCHANGED = "/luckperms/plugin/snapshot/unchanged";
	static final String CHANGES = "/luckperms/plugin/changes";
	static final String RESULTS = "/luckperms/plugin/changes/results";
	static final String COMMANDS = "/luckperms/plugin/commands";

	private LuckPermsBridgeClient() {}

	/**
	 * One LuckPerms node as the site sees it. Contexts map each key to its sorted values;
	 * expiry is unix seconds, 0 when permanent.
	 */
	public static final class NodeSpec {
		/** Key, then contexts, then expiry, then value: the snapshot's stable node order. */
		public static final Comparator<NodeSpec> ORDER = Comparator
			.comparing((NodeSpec node) -> node.key)
			.thenComparing(node -> node.contextsJson().toString())
			.thenComparingLong(node -> node.expiry)
			.thenComparing(node -> node.value);

		public final String key;
		public final boolean value;
		public final Map<String, List<String>> contexts;
		public final long expiry;

		public NodeSpec(String key, boolean value, Map<String, ? extends Collection<String>> contexts, long expiry) {
			this.key = key;
			this.value = value;
			Map<String, List<String>> sorted = new TreeMap<>();
			if (contexts != null) {
				for (Map.Entry<String, ? extends Collection<String>> entry : contexts.entrySet()) {
					if (entry.getValue() == null || entry.getValue().isEmpty()) {
						continue;
					}
					sorted.put(entry.getKey(), List.copyOf(new TreeSet<>(entry.getValue())));
				}
			}
			this.contexts = Collections.unmodifiableMap(sorted);
			this.expiry = Math.max(0L, expiry);
		}

		public JsonObject toJson() {
			JsonObject json = new JsonObject();
			json.addProperty("key", key);
			json.addProperty("value", value);
			json.add("contexts", contextsJson());
			json.addProperty("expiry", expiry);
			return json;
		}

		private JsonObject contextsJson() {
			JsonObject json = new JsonObject();
			for (Map.Entry<String, List<String>> entry : contexts.entrySet()) {
				JsonArray values = new JsonArray();
				entry.getValue().forEach(values::add);
				json.add(entry.getKey(), values);
			}
			return json;
		}

		/** @return the node, or null when the JSON is not a well-formed node */
		static NodeSpec parse(JsonElement element) {
			if (element == null || !element.isJsonObject()) {
				return null;
			}
			JsonObject obj = element.getAsJsonObject();
			JsonElement key = obj.get("key");
			if (!isString(key) || key.getAsString().isBlank()) {
				return null;
			}
			Boolean value = parseValue(obj.get("value"));
			Map<String, List<String>> contexts = parseContexts(obj.get("contexts"));
			Long expiry = parseExpiry(obj.get("expiry"));
			if (value == null || contexts == null || expiry == null) {
				return null;
			}
			return new NodeSpec(key.getAsString(), value.booleanValue(), contexts, expiry.longValue());
		}

		private static Boolean parseValue(JsonElement element) {
			if (element == null || element.isJsonNull()) {
				return Boolean.TRUE;
			}
			if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()) {
				return Boolean.valueOf(element.getAsBoolean());
			}
			return null;
		}

		private static Map<String, List<String>> parseContexts(JsonElement element) {
			Map<String, List<String>> contexts = new TreeMap<>();
			if (element == null || element.isJsonNull()) {
				return contexts;
			}
			if (!element.isJsonObject()) {
				return null;
			}
			for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
				if (entry.getKey().isBlank()) {
					return null;
				}
				List<String> values = strings(entry.getValue());
				if (values == null) {
					return null;
				}
				contexts.put(entry.getKey(), values);
			}
			return contexts;
		}

		private static List<String> strings(JsonElement element) {
			if (isString(element)) {
				element = singleton(element);
			}
			if (element == null || !element.isJsonArray()) {
				return null;
			}
			List<String> values = new ArrayList<>();
			for (JsonElement item : element.getAsJsonArray()) {
				if (!isString(item) || item.getAsString().isBlank()) {
					return null;
				}
				values.add(item.getAsString());
			}
			return values;
		}

		private static JsonArray singleton(JsonElement element) {
			JsonArray array = new JsonArray();
			array.add(element);
			return array;
		}

		private static Long parseExpiry(JsonElement element) {
			if (element == null || element.isJsonNull()) {
				return Long.valueOf(0L);
			}
			if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
				return null;
			}
			long expiry = element.getAsLong();
			return expiry < 0 ? null : Long.valueOf(expiry);
		}
	}

	/**
	 * One op of a queued change. Fields the op does not carry, or carries malformed, are null;
	 * the applier answers those with {@code bad_op}.
	 */
	public static final class Op {
		public final String type;
		public final NodeSpec node;
		public final List<String> groups;

		public Op(String type, NodeSpec node, List<String> groups) {
			this.type = type;
			this.node = node;
			this.groups = groups == null ? null : List.copyOf(groups);
		}
	}

	/** One staff-queued change: ops for one user, group or track, applied and saved together. */
	public static final class Change {
		public final long id;
		public final String targetType;
		public final String target;
		public final String targetName;
		public final String actorName;
		public final String actorUuid;
		public final String description;
		public final List<Op> ops;
		/**
		 * Lower-case groups an admin (not root) may manage, from the change's guard;
		 * null when the change has no guard.
		 */
		public final List<String> guardGroups;

		public Change(
			long id,
			String targetType,
			String target,
			String targetName,
			String actorName,
			String actorUuid,
			String description,
			List<Op> ops
		) {
			this(id, targetType, target, targetName, actorName, actorUuid, description, ops, null);
		}

		public Change(
			long id,
			String targetType,
			String target,
			String targetName,
			String actorName,
			String actorUuid,
			String description,
			List<Op> ops,
			List<String> guardGroups
		) {
			this.id = id;
			this.targetType = targetType;
			this.target = target;
			this.targetName = targetName;
			this.actorName = actorName;
			this.actorUuid = actorUuid;
			this.description = description;
			this.ops = ops == null ? List.of() : List.copyOf(ops);
			this.guardGroups = guardGroups == null ? null : List.copyOf(guardGroups);
		}
	}

	/** Outcome of one change, posted back to the site. */
	public static final class ChangeResult {
		public final long id;
		public final boolean ok;
		public final String error;
		/** Target after the change; JSON null for deleted targets and tracks. Null when not sent. */
		public final JsonElement state;
		/** Orders this result against snapshots; stamped by the bridge, 0 until then. */
		public final long revision;

		private ChangeResult(long id, boolean ok, String error, JsonElement state, long revision) {
			this.id = id;
			this.ok = ok;
			this.error = error;
			this.state = state;
			this.revision = revision;
		}

		public static ChangeResult success(long id, JsonElement state) {
			return new ChangeResult(id, true, null, state == null ? JsonNull.INSTANCE : state, 0L);
		}

		public static ChangeResult failure(long id, String error) {
			return new ChangeResult(id, false, error, null, 0L);
		}

		public ChangeResult withRevision(long revision) {
			return new ChangeResult(id, ok, error, state, revision);
		}

		JsonObject toJson() {
			JsonObject json = new JsonObject();
			json.addProperty("id", id);
			json.addProperty("ok", ok);
			json.addProperty("error", error);
			json.addProperty("revision", revision);
			if (state != null) {
				json.add("state", state);
			}
			return json;
		}
	}

	/** Changes waiting on the site, in id order. */
	public static final class ChangesResult {
		public final boolean ok;
		public final int status;
		public final List<Change> changes;
		public final String error;

		private ChangesResult(boolean ok, int status, List<Change> changes, String error) {
			this.ok = ok;
			this.status = status;
			this.changes = changes == null ? List.of() : List.copyOf(changes);
			this.error = error;
		}

		public static ChangesResult success(List<Change> changes) {
			return new ChangesResult(true, 200, changes, null);
		}

		public static ChangesResult fail(int status, String error) {
			return new ChangesResult(false, status, null, error);
		}
	}

	/** Plain call outcome; status is 0 when the site could not be reached. */
	public static final class CallResult {
		public final boolean ok;
		public final int status;
		public final String error;

		private CallResult(boolean ok, int status, String error) {
			this.ok = ok;
			this.status = status;
			this.error = error;
		}

		public static CallResult success() {
			return new CallResult(true, 200, null);
		}

		public static CallResult fail(int status, String error) {
			return new CallResult(false, status, error);
		}
	}

	/** Answer to an unchanged-snapshot check. {@code current} false means upload in full. */
	public static final class UnchangedResult {
		public final boolean ok;
		public final boolean current;
		public final int status;
		public final String error;

		private UnchangedResult(boolean ok, boolean current, int status, String error) {
			this.ok = ok;
			this.current = current;
			this.status = status;
			this.error = error;
		}

		public static UnchangedResult answered(boolean current) {
			return new UnchangedResult(true, current, 200, null);
		}

		public static UnchangedResult fail(int status, String error) {
			return new UnchangedResult(false, false, status, error);
		}
	}

	/**
	 * Replaces the site's mirror. Snapshots can be about a megabyte, so this uses the
	 * gateway's longer upload timeout.
	 */
	public static CallResult putSnapshot(String json) {
		GatewayResult raw = ProvinceSystemGateway.requestBytes(
			"PUT",
			SNAPSHOT,
			json.getBytes(StandardCharsets.UTF_8),
			"application/json"
		);
		if (!raw.ok) {
			return CallResult.fail(raw.status, raw.error);
		}
		JsonObject root = object(raw.body);
		if (root == null || !isTrue(root.get("ok"))) {
			return CallResult.fail(raw.status, "Snapshot was not accepted");
		}
		return CallResult.success();
	}

	/** Replaces the site's command list for the Commands tab. */
	public static CallResult putCommands(String json) {
		GatewayResult raw = ProvinceSystemGateway.requestBytes(
			"PUT",
			COMMANDS,
			json.getBytes(StandardCharsets.UTF_8),
			"application/json"
		);
		if (!raw.ok) {
			return CallResult.fail(raw.status, raw.error);
		}
		JsonObject root = object(raw.body);
		if (root == null || !isTrue(root.get("ok"))) {
			return CallResult.fail(raw.status, "Command list was not accepted");
		}
		return CallResult.success();
	}

	public static UnchangedResult snapshotUnchanged(String hash, long revision) {
		JsonObject body = new JsonObject();
		body.addProperty("hash", hash);
		body.addProperty("revision", revision);
		GatewayResult raw = ProvinceSystemGateway.request("POST", SNAPSHOT_UNCHANGED, body.toString());
		if (!raw.ok) {
			return UnchangedResult.fail(raw.status, raw.error);
		}
		JsonObject root = object(raw.body);
		if (root == null) {
			return UnchangedResult.fail(raw.status, "Malformed snapshot check response");
		}
		return UnchangedResult.answered(isTrue(root.get("ok")));
	}

	public static ChangesResult listChanges() {
		GatewayResult raw = ProvinceSystemGateway.request("GET", CHANGES, null);
		if (!raw.ok) {
			return ChangesResult.fail(raw.status, raw.error);
		}
		JsonObject root = object(raw.body);
		if (root == null) {
			return ChangesResult.fail(raw.status, "Malformed changes response");
		}
		JsonElement changes = root.get("changes");
		if (changes == null || changes.isJsonNull()) {
			return ChangesResult.success(List.of());
		}
		if (!changes.isJsonArray()) {
			return ChangesResult.fail(raw.status, "Malformed changes response");
		}
		List<Change> parsed = new ArrayList<>();
		for (JsonElement element : changes.getAsJsonArray()) {
			Change change = parseChange(element);
			if (change != null) {
				parsed.add(change);
			}
		}
		parsed.sort(Comparator.comparingLong(change -> change.id));
		return ChangesResult.success(parsed);
	}

	public static CallResult postResults(Collection<ChangeResult> results) {
		JsonArray array = new JsonArray();
		for (ChangeResult result : results) {
			array.add(result.toJson());
		}
		JsonObject body = new JsonObject();
		body.add("results", array);
		GatewayResult raw = ProvinceSystemGateway.request("POST", RESULTS, body.toString());
		if (!raw.ok) {
			return CallResult.fail(raw.status, raw.error);
		}
		return CallResult.success();
	}

	private static Change parseChange(JsonElement element) {
		if (element == null || !element.isJsonObject()) {
			return null;
		}
		JsonObject obj = element.getAsJsonObject();
		JsonElement id = obj.get("id");
		if (id == null || !id.isJsonPrimitive() || !id.getAsJsonPrimitive().isNumber()) {
			return null;
		}
		List<Op> ops = new ArrayList<>();
		JsonElement rawOps = obj.get("ops");
		if (rawOps != null && rawOps.isJsonArray()) {
			for (JsonElement op : rawOps.getAsJsonArray()) {
				ops.add(parseOp(op));
			}
		}
		return new Change(
			id.getAsLong(),
			jsonText(obj, "target_type"),
			jsonText(obj, "target"),
			jsonText(obj, "target_name"),
			jsonText(obj, "actor_name"),
			jsonText(obj, "actor_uuid"),
			jsonText(obj, "description"),
			ops,
			guardGroups(obj.get("guard"))
		);
	}

	/**
	 * A guard that is present but malformed allows no groups, so a broken guard
	 * can only refuse a change, never widen what an admin may do.
	 */
	private static List<String> guardGroups(JsonElement guard) {
		if (guard == null || guard.isJsonNull()) {
			return null;
		}
		List<String> groups = new ArrayList<>();
		if (!guard.isJsonObject()) {
			return groups;
		}
		JsonElement names = guard.getAsJsonObject().get("admin_groups");
		if (names == null || !names.isJsonArray()) {
			return groups;
		}
		for (JsonElement name : names.getAsJsonArray()) {
			if (isString(name) && !name.getAsString().isBlank()) {
				groups.add(name.getAsString().trim().toLowerCase(Locale.ROOT));
			}
		}
		return groups;
	}

	private static Op parseOp(JsonElement element) {
		if (element == null || !element.isJsonObject()) {
			return new Op(null, null, null);
		}
		JsonObject obj = element.getAsJsonObject();
		return new Op(jsonText(obj, "op"), NodeSpec.parse(obj.get("node")), groups(obj.get("groups")));
	}

	private static List<String> groups(JsonElement element) {
		if (element == null || !element.isJsonArray()) {
			return null;
		}
		List<String> groups = new ArrayList<>();
		for (JsonElement item : element.getAsJsonArray()) {
			if (!isString(item) || item.getAsString().isBlank()) {
				return null;
			}
			groups.add(item.getAsString().trim());
		}
		return groups;
	}

	private static boolean isString(JsonElement element) {
		return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString();
	}

	private static String jsonText(JsonObject obj, String key) {
		JsonElement element = obj.get(key);
		if (element == null || !element.isJsonPrimitive()) {
			return null;
		}
		String text = element.getAsString().trim();
		return text.isEmpty() ? null : text;
	}

	private static boolean isTrue(JsonElement element) {
		if (element == null || !element.isJsonPrimitive()) {
			return false;
		}
		JsonPrimitive primitive = element.getAsJsonPrimitive();
		return primitive.isBoolean() && primitive.getAsBoolean();
	}

	private static JsonObject object(String body) {
		try {
			JsonElement element = JsonParser.parseString(body);
			return element.isJsonObject() ? element.getAsJsonObject() : null;
		} catch (JsonParseException e) {
			return null;
		}
	}
}
