package net.tfminecraft.tfmcweb.api;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.tfminecraft.tfmcweb.Cache;
import net.tfminecraft.tfmcweb.TestState;
import net.tfminecraft.tfmcweb.api.LuckPermsBridgeClient.CallResult;
import net.tfminecraft.tfmcweb.api.LuckPermsBridgeClient.Change;
import net.tfminecraft.tfmcweb.api.LuckPermsBridgeClient.ChangeResult;
import net.tfminecraft.tfmcweb.api.LuckPermsBridgeClient.ChangesResult;
import net.tfminecraft.tfmcweb.api.LuckPermsBridgeClient.NodeSpec;
import net.tfminecraft.tfmcweb.api.LuckPermsBridgeClient.Op;
import net.tfminecraft.tfmcweb.api.LuckPermsBridgeClient.UnchangedResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LuckPermsBridgeClientTest {
	TestState state;
	HttpFixture http;

	@BeforeEach void setup() throws Exception { state = new TestState(); http = new HttpFixture(); }
	@AfterEach void cleanup() throws Exception { http.close(); state.close(); }

	@Test void snapshotIsPutAsJsonAndNeedsTheSiteToAcceptIt() throws Exception {
		http.reply(200, "{\"ok\":true}");
		assertTrue(LuckPermsBridgeClient.putSnapshot("{\"hash\":\"é\"}").ok);
		var request = http.request();
		assertEquals("PUT", request.method());
		assertEquals("/luckperms/plugin/snapshot", request.path());
		assertEquals("{\"hash\":\"é\"}", request.body());
		assertEquals("application/json", request.contentType());
		assertEquals("unit-test-key", request.key());
		for (String body : List.of("{\"ok\":false}", "{\"ok\":\"true\"}", "{}", "[]", "", "{bad")) {
			http.reply(200, body);
			CallResult refused = LuckPermsBridgeClient.putSnapshot("{}");
			assertFalse(refused.ok);
			assertEquals(200, refused.status);
			assertEquals("Snapshot was not accepted", refused.error);
			http.request();
		}
		http.reply(413, "{\"detail\":\"too big\"}");
		CallResult tooBig = LuckPermsBridgeClient.putSnapshot("{}");
		assertFalse(tooBig.ok);
		assertEquals(413, tooBig.status);
		assertEquals("too big", tooBig.error);
		http.request();
		Cache.pluginKey = "";
		CallResult unconfigured = LuckPermsBridgeClient.putSnapshot("{}");
		assertEquals(0, unconfigured.status);
		assertTrue(unconfigured.error.contains("not configured"));
	}

	@Test void commandListIsUploadedAndRefusalsAreReported() throws Exception {
		http.reply(200, "{\"ok\":true}");
		assertTrue(LuckPermsBridgeClient.putCommands("{\"hash\":\"é\"}").ok);
		var request = http.request();
		assertEquals("PUT", request.method());
		assertEquals("/luckperms/plugin/commands", request.path());
		assertEquals("{\"hash\":\"é\"}", request.body());
		assertEquals("application/json", request.contentType());
		http.reply(200, "{\"ok\":false}");
		CallResult refused = LuckPermsBridgeClient.putCommands("{}");
		assertFalse(refused.ok);
		assertEquals("Command list was not accepted", refused.error);
		http.request();
		http.reply(400, "{\"detail\":\"bad_commands\"}");
		CallResult bad = LuckPermsBridgeClient.putCommands("{}");
		assertEquals(400, bad.status);
		assertEquals("bad_commands", bad.error);
		http.request();
	}

	@Test void unchangedCheckTellsCurrentFromNeedFull() throws Exception {
		http.reply(200, "{\"ok\":true}");
		UnchangedResult current = LuckPermsBridgeClient.snapshotUnchanged("abc", 7L);
		assertTrue(current.ok && current.current);
		var request = http.request();
		assertEquals("POST", request.method());
		assertEquals("/luckperms/plugin/snapshot/unchanged", request.path());
		assertEquals("{\"hash\":\"abc\",\"revision\":7}", request.body());
		http.reply(200, "{\"ok\":false,\"need_full\":true}");
		UnchangedResult stale = LuckPermsBridgeClient.snapshotUnchanged("abc", 7L);
		assertTrue(stale.ok);
		assertFalse(stale.current);
		http.request();
		http.reply(200, "nope");
		UnchangedResult malformed = LuckPermsBridgeClient.snapshotUnchanged("abc", 7L);
		assertFalse(malformed.ok);
		assertEquals("Malformed snapshot check response", malformed.error);
		http.request();
		http.reply(403, "{\"detail\":\"secondary key\"}");
		UnchangedResult denied = LuckPermsBridgeClient.snapshotUnchanged("abc", 7L);
		assertFalse(denied.ok || denied.current);
		assertEquals(403, denied.status);
		assertEquals("secondary key", denied.error);
		http.request();
	}

	@Test void changesAreParsedInIdOrderWithEveryOpShape() throws Exception {
		String user = "{\"id\":12,\"target_type\":\"user\",\"target\":\" 5b0c \",\"target_name\":\"drefvelin\","
			+ "\"actor_name\":\"web:w.o.n\",\"actor_uuid\":null,\"description\":\"parent add staff\",\"ops\":["
			+ "{\"op\":\"add_node\",\"node\":{\"key\":\"group.staff\",\"value\":true,\"contexts\":{\"server\":[\"main\",\"dev\"],\"world\":\"vardera\",\"empty\":[]},\"expiry\":1791321779}},"
			+ "{\"op\":\"remove_node\",\"node\":{\"key\":\"perm.x\"}},"
			+ "{\"op\":\"set_groups\",\"groups\":[\" a \",\"b\"]},"
			+ "\"junk\"]}";
		String track = "{\"id\":3,\"target_type\":\"track\",\"target\":\"staff\",\"ops\":[{\"op\":\"create_track\"}]}";
		http.reply(200, "{\"changes\":[" + user + "," + track + ",null,1,{\"id\":\"7\"},{\"id\":true},{\"target\":\"x\"},{\"id\":9,\"ops\":{}}]}");
		ChangesResult result = LuckPermsBridgeClient.listChanges();
		assertTrue(result.ok);
		var request = http.request();
		assertEquals("GET", request.method());
		assertEquals("/luckperms/plugin/changes", request.path());
		assertEquals(List.of(3L, 9L, 12L), result.changes.stream().map(change -> change.id).toList());
		assertThrows(UnsupportedOperationException.class, () -> result.changes.clear());
		assertTrue(result.changes.get(1).ops.isEmpty());
		assertNull(result.changes.get(1).targetType);
		Change change = result.changes.get(2);
		assertEquals("user", change.targetType);
		assertEquals("5b0c", change.target);
		assertEquals("drefvelin", change.targetName);
		assertEquals("web:w.o.n", change.actorName);
		assertNull(change.actorUuid);
		assertEquals("parent add staff", change.description);
		assertEquals(4, change.ops.size());
		NodeSpec added = change.ops.get(0).node;
		assertEquals("add_node", change.ops.get(0).type);
		assertEquals("group.staff", added.key);
		assertTrue(added.value);
		assertEquals(Map.of("server", List.of("dev", "main"), "world", List.of("vardera")), added.contexts);
		assertEquals(1791321779L, added.expiry);
		NodeSpec removed = change.ops.get(1).node;
		assertEquals("perm.x", removed.key);
		assertTrue(removed.value);
		assertTrue(removed.contexts.isEmpty());
		assertEquals(0L, removed.expiry);
		assertNull(change.ops.get(1).groups);
		assertEquals(List.of("a", "b"), change.ops.get(2).groups);
		assertNull(change.ops.get(2).node);
		Op junk = change.ops.get(3);
		assertNull(junk.type);
		assertNull(junk.node);
		assertNull(junk.groups);

		http.reply(200, "{}"); assertTrue(LuckPermsBridgeClient.listChanges().changes.isEmpty()); http.request();
		http.reply(200, "{\"changes\":null}"); assertTrue(LuckPermsBridgeClient.listChanges().changes.isEmpty()); http.request();
		http.reply(200, "{\"changes\":{}}"); assertEquals("Malformed changes response", LuckPermsBridgeClient.listChanges().error); http.request();
		http.reply(200, "[]"); assertEquals("Malformed changes response", LuckPermsBridgeClient.listChanges().error); http.request();
		http.reply(503, ""); ChangesResult down = LuckPermsBridgeClient.listChanges();
		assertFalse(down.ok);
		assertEquals(503, down.status);
		assertTrue(down.changes.isEmpty());
		http.request();
	}

	@Test void guardsListAdminGroupsAndFailClosedWhenMalformed() throws Exception {
		http.reply(200, "{\"changes\":["
			+ "{\"id\":1,\"guard\":{\"admin_groups\":[\" Default \",\"COMMONER\",\" \",1,null]}},"
			+ "{\"id\":2,\"guard\":null},"
			+ "{\"id\":3},"
			+ "{\"id\":4,\"guard\":[\"default\"]},"
			+ "{\"id\":5,\"guard\":{}},"
			+ "{\"id\":6,\"guard\":{\"admin_groups\":\"default\"}}]}");
		List<Change> changes = LuckPermsBridgeClient.listChanges().changes;
		assertEquals(List.of("default", "commoner"), changes.get(0).guardGroups);
		assertNull(changes.get(1).guardGroups);
		assertNull(changes.get(2).guardGroups);
		for (Change failClosed : changes.subList(3, 6)) {
			assertEquals(List.of(), failClosed.guardGroups);
		}
		assertThrows(UnsupportedOperationException.class, () -> changes.getFirst().guardGroups.clear());
		http.request();
	}

	@Test void malformedNodesAndGroupListsBecomeMissingFields() throws Exception {
		List<String> badNodes = List.of(
			"null", "\"group.x\"", "{}", "{\"key\":\" \"}", "{\"key\":1}",
			"{\"key\":\"a\",\"value\":\"true\"}", "{\"key\":\"a\",\"contexts\":[]}",
			"{\"key\":\"a\",\"contexts\":{\" \":[\"x\"]}}", "{\"key\":\"a\",\"contexts\":{\"server\":[1]}}",
			"{\"key\":\"a\",\"contexts\":{\"server\":[\" \"]}}", "{\"key\":\"a\",\"contexts\":{\"server\":{}}}",
			"{\"key\":\"a\",\"contexts\":{\"server\":null}}",
			"{\"key\":\"a\",\"expiry\":-1}", "{\"key\":\"a\",\"expiry\":\"1\"}", "{\"key\":\"a\",\"expiry\":[]}"
		);
		StringBuilder ops = new StringBuilder();
		for (String node : badNodes) {
			ops.append("{\"op\":\"add_node\",\"node\":").append(node).append("},");
		}
		ops.append("{\"op\":\"set_groups\",\"groups\":[\"a\",1]},");
		ops.append("{\"op\":\"set_groups\",\"groups\":[\"a\",\" \"]},");
		ops.append("{\"op\":\"set_groups\",\"groups\":\"a\"},");
		ops.append("{\"op\":\"add_node\",\"node\":{\"key\":\"a\",\"value\":false,\"contexts\":null,\"expiry\":null}}");
		http.reply(200, "{\"changes\":[{\"id\":1,\"ops\":[" + ops + "]}]}");
		List<Op> parsed = LuckPermsBridgeClient.listChanges().changes.getFirst().ops;
		for (int i = 0; i < badNodes.size(); i++) {
			assertNull(parsed.get(i).node, badNodes.get(i));
		}
		for (int i = badNodes.size(); i < badNodes.size() + 3; i++) {
			assertNull(parsed.get(i).groups);
		}
		NodeSpec negated = parsed.getLast().node;
		assertFalse(negated.value);
		assertTrue(negated.contexts.isEmpty());
		assertEquals(0L, negated.expiry);
		http.request();
	}

	@Test void resultsCarryStateOnlyForSuccess() throws Exception {
		JsonObject group = new JsonObject();
		group.addProperty("name", "staff");
		http.reply(200, "{\"ok\":true}");
		List<ChangeResult> results = List.of(
			ChangeResult.success(12, group).withRevision(100L),
			ChangeResult.success(13, JsonNull.INSTANCE).withRevision(101L),
			ChangeResult.success(14, null),
			ChangeResult.failure(15, "node_exists").withRevision(103L)
		);
		ChangeResult stamped = results.getFirst();
		assertEquals(12L, stamped.id);
		assertTrue(stamped.ok);
		assertSame(group, stamped.state);
		assertTrue(LuckPermsBridgeClient.postResults(results).ok);
		var request = http.request();
		assertEquals("POST", request.method());
		assertEquals("/luckperms/plugin/changes/results", request.path());
		assertEquals(JsonParser.parseString("{\"results\":["
			+ "{\"id\":12,\"ok\":true,\"error\":null,\"revision\":100,\"state\":{\"name\":\"staff\"}},"
			+ "{\"id\":13,\"ok\":true,\"error\":null,\"revision\":101,\"state\":null},"
			+ "{\"id\":14,\"ok\":true,\"error\":null,\"revision\":0,\"state\":null},"
			+ "{\"id\":15,\"ok\":false,\"error\":\"node_exists\",\"revision\":103}]}"), JsonParser.parseString(request.body()));
		assertTrue(request.body().contains("\"error\":null"));
		http.reply(500, "");
		CallResult failed = LuckPermsBridgeClient.postResults(results);
		assertFalse(failed.ok);
		assertEquals(500, failed.status);
		http.request();
	}

	@Test void nodeSpecsSortAndSerialiseCanonically() {
		Map<String, List<String>> contexts = new LinkedHashMap<>();
		contexts.put("world", List.of("b", "a", "a"));
		contexts.put("server", List.of("main"));
		contexts.put("empty", List.of());
		contexts.put("none", null);
		NodeSpec node = new NodeSpec("perm", true, contexts, -5);
		assertEquals(0L, node.expiry);
		assertEquals("{\"key\":\"perm\",\"value\":true,\"contexts\":{\"server\":[\"main\"],\"world\":[\"a\",\"b\"]},\"expiry\":0}",
			node.toJson().toString());
		assertThrows(UnsupportedOperationException.class, () -> node.contexts.clear());
		assertTrue(new NodeSpec("perm", true, null, 0).contexts.isEmpty());
		NodeSpec global = new NodeSpec("perm", true, Map.of(), 0);
		NodeSpec later = new NodeSpec("perm", true, Map.of(), 10);
		NodeSpec negated = new NodeSpec("perm", false, Map.of(), 10);
		NodeSpec server = new NodeSpec("perm", true, Map.of("server", Set.of("main")), 0);
		NodeSpec other = new NodeSpec("alpha", true, Map.of("world", Set.of("x")), 99);
		List<NodeSpec> nodes = new ArrayList<>(Arrays.asList(server, later, global, other, negated));
		nodes.sort(NodeSpec.ORDER);
		assertEquals(List.of(other, server, global, negated, later), nodes);
		List<String> groups = new ArrayList<>(List.of("a"));
		Op op = new Op("set_groups", null, groups);
		groups.clear();
		assertEquals(List.of("a"), op.groups);
		assertTrue(new Change(1, null, null, null, null, null, null, null).ops.isEmpty());
		assertTrue(ChangesResult.success(null).changes.isEmpty());
		assertTrue(CallResult.success().ok);
		assertEquals(200, UnchangedResult.answered(true).status);
	}
}
