package net.tfminecraft.tfmcweb.luckperms;

import java.util.Map;
import java.util.function.Function;

import com.google.gson.JsonObject;

import net.tfminecraft.tfmcweb.api.LuckPermsBridgeClient.Change;
import net.tfminecraft.tfmcweb.api.LuckPermsBridgeClient.ChangeResult;

/**
 * LuckPerms access used by the bridge, so the bridge itself never references the
 * LuckPerms API and TFMCWeb still loads without it.
 */
public interface BridgeStore {

	String NODE_EXISTS = "node_exists";
	String NODE_MISSING = "node_missing";
	String GROUP_EXISTS = "group_exists";
	String GROUP_MISSING = "group_missing";
	String TRACK_EXISTS = "track_exists";
	String TRACK_MISSING = "track_missing";
	String BAD_OP = "bad_op";
	String BAD_TARGET = "bad_target";
	String SAVE_FAILED = "save_failed";

	/**
	 * @return {@code {"groups": [...], "tracks": [...], "users": [...]}} in a stable order,
	 *     so equal LuckPerms data always serialises to the same JSON
	 */
	JsonObject snapshot();

	/**
	 * Checks every precondition against live LuckPerms data, then applies all ops and
	 * saves once, or changes nothing. Never throws for a refused change.
	 */
	ChangeResult apply(Change change);

	/**
	 * @return each group's check on this server: true or false as LuckPerms resolves the
	 *     permission, or null when the group has no node for it
	 */
	Map<String, Function<String, Boolean>> groupChecks();
}
