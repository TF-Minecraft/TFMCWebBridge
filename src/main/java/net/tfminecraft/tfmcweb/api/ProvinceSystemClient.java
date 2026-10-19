package net.tfminecraft.tfmcweb.api;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.tfminecraft.tfmcweb.Cache;

/**
 * HTTP client for ProvinceSystem identity + plugin notice routes.
 */
public final class ProvinceSystemClient {

	private static final int TIMEOUT_MS = 8000;

	private ProvinceSystemClient() {}

	public static final class CodeResult {
		public final boolean ok;
		public final boolean alreadyLinked;
		public final String code;
		public final String expiresAt;
		public final String discordUsername;
		public final String error;

		private CodeResult(
			boolean ok,
			boolean alreadyLinked,
			String code,
			String expiresAt,
			String discordUsername,
			String error
		) {
			this.ok = ok;
			this.alreadyLinked = alreadyLinked;
			this.code = code;
			this.expiresAt = expiresAt;
			this.discordUsername = discordUsername;
			this.error = error;
		}

		public static CodeResult success(String code, String expiresAt) {
			return new CodeResult(true, false, code, expiresAt, null, null);
		}

		public static CodeResult alreadyLinked(String discordUsername) {
			return new CodeResult(true, true, null, null, discordUsername, null);
		}

		public static CodeResult fail(String error) {
			return new CodeResult(false, false, null, null, null, error);
		}
	}

	/** Result for scoped feature tokens (/token create). */
	public static final class FeatureCodeResult {
		public final boolean ok;
		public final String code;
		public final String expiresAt;
		public final String scope;
		public final String error;

		private FeatureCodeResult(
			boolean ok,
			String code,
			String expiresAt,
			String scope,
			String error
		) {
			this.ok = ok;
			this.code = code;
			this.expiresAt = expiresAt;
			this.scope = scope;
			this.error = error;
		}

		public static FeatureCodeResult success(String code, String expiresAt, String scope) {
			return new FeatureCodeResult(true, code, expiresAt, scope, null);
		}

		public static FeatureCodeResult fail(String error) {
			return new FeatureCodeResult(false, null, null, null, error);
		}
	}

	/** Last shared cosmetic mint (skin + drink) for cooldown checks. */
	public static final class CosmeticMintStatus {
		public final boolean ok;
		public final String lastMintAt;
		public final String error;

		private CosmeticMintStatus(boolean ok, String lastMintAt, String error) {
			this.ok = ok;
			this.lastMintAt = lastMintAt;
			this.error = error;
		}

		public static CosmeticMintStatus success(String lastMintAt) {
			return new CosmeticMintStatus(true, lastMintAt, null);
		}

		public static CosmeticMintStatus fail(String error) {
			return new CosmeticMintStatus(false, null, error);
		}
	}

	/** Result for moderation POSTs (warn / ban mirror). */
	public static final class MirrorResult {
		public final boolean ok;
		public final boolean mirrored;
		/** The API already had this ban or unban from another server. */
		public final boolean duplicate;
		public final String error;

		private MirrorResult(boolean ok, boolean mirrored, boolean duplicate, String error) {
			this.ok = ok;
			this.mirrored = mirrored;
			this.duplicate = duplicate;
			this.error = error;
		}

		public static MirrorResult success(boolean mirrored) {
			return new MirrorResult(true, mirrored, false, null);
		}

		public static MirrorResult duplicate() {
			return new MirrorResult(true, false, true, null);
		}

		public static MirrorResult fail(String error) {
			return new MirrorResult(false, false, false, error);
		}
	}

	/** One active ban from GET /skins/moderation/bans; expiresAt is 0 for permanent. */
	public static final class SyncedBan {
		public final UUID id;
		public final String name;
		public final String reason;
		public final String staffName;
		public final long bannedAt;
		public final long expiresAt;

		public SyncedBan(UUID id, String name, String reason, String staffName, long bannedAt, long expiresAt) {
			this.id = id;
			this.name = name;
			this.reason = reason;
			this.staffName = staffName;
			this.bannedAt = bannedAt;
			this.expiresAt = expiresAt;
		}
	}

	/** Active bans and recent unbans (player → unban time) recorded from every server. */
	public static final class BanSync {
		public final boolean ok;
		public final List<SyncedBan> bans;
		public final Map<UUID, Long> unbans;
		public final String error;

		private BanSync(boolean ok, List<SyncedBan> bans, Map<UUID, Long> unbans, String error) {
			this.ok = ok;
			this.bans = bans;
			this.unbans = unbans;
			this.error = error;
		}

		public static BanSync success(List<SyncedBan> bans, Map<UUID, Long> unbans) {
			return new BanSync(true, bans, unbans, null);
		}

		public static BanSync fail(String error) {
			return new BanSync(false, Collections.emptyList(), Collections.emptyMap(), error);
		}

		static BanSync fromJson(String json) {
			List<SyncedBan> bans = new ArrayList<>();
			Map<UUID, Long> unbans = new HashMap<>();
			try {
				JsonObject root = JsonParser.parseString(json).getAsJsonObject();
				for (JsonElement el : arrayOrEmpty(root, "bans")) {
					JsonObject ban = el.getAsJsonObject();
					UUID id = uuidOrNull(ban, "player_uuid");
					if (id == null) {
						continue;
					}
					bans.add(new SyncedBan(
						id,
						stringOrNull(ban, "minecraft_name"),
						stringOrNull(ban, "reason"),
						stringOrNull(ban, "staff_name"),
						longOrZero(ban, "banned_at_ms"),
						longOrZero(ban, "expires_at_ms")
					));
				}
				for (JsonElement el : arrayOrEmpty(root, "unbans")) {
					JsonObject unban = el.getAsJsonObject();
					UUID id = uuidOrNull(unban, "player_uuid");
					if (id != null) {
						unbans.put(id, longOrZero(unban, "unbanned_at_ms"));
					}
				}
			} catch (RuntimeException e) {
				return fail("Bad ban list from API: " + e.getMessage());
			}
			return success(bans, unbans);
		}

		private static JsonArray arrayOrEmpty(JsonObject obj, String key) {
			JsonElement el = obj.get(key);
			return el != null && el.isJsonArray() ? el.getAsJsonArray() : new JsonArray();
		}

		private static String stringOrNull(JsonObject obj, String key) {
			JsonElement el = obj.get(key);
			return el == null || el.isJsonNull() ? null : el.getAsString();
		}

		private static long longOrZero(JsonObject obj, String key) {
			JsonElement el = obj.get(key);
			return el == null || el.isJsonNull() ? 0L : el.getAsLong();
		}

		private static UUID uuidOrNull(JsonObject obj, String key) {
			String raw = stringOrNull(obj, key);
			if (raw == null) {
				return null;
			}
			try {
				return UUID.fromString(raw.trim());
			} catch (IllegalArgumentException e) {
				return null;
			}
		}
	}

	public static final class SimpleResult {
		public final boolean ok;
		public final String error;

		private SimpleResult(boolean ok, String error) {
			this.ok = ok;
			this.error = error;
		}

		public static SimpleResult success() {
			return new SimpleResult(true, null);
		}

		public static SimpleResult fail(String error) {
			return new SimpleResult(false, error);
		}
	}

	public static final class IdentityStatus {
		public final boolean ok;
		public final boolean linked;
		public final boolean eligible;
		public final boolean inGrace;
		public final String playerUuid;
		public final String discordUserId;
		public final String discordUsername;
		public final String minecraftName;
		public final String graceUntil;
		public final String leftGuildAt;
		public final String error;

		private IdentityStatus(
			boolean ok,
			boolean linked,
			boolean eligible,
			boolean inGrace,
			String playerUuid,
			String discordUserId,
			String discordUsername,
			String minecraftName,
			String graceUntil,
			String leftGuildAt,
			String error
		) {
			this.ok = ok;
			this.linked = linked;
			this.eligible = eligible;
			this.inGrace = inGrace;
			this.playerUuid = playerUuid;
			this.discordUserId = discordUserId;
			this.discordUsername = discordUsername;
			this.minecraftName = minecraftName;
			this.graceUntil = graceUntil;
			this.leftGuildAt = leftGuildAt;
			this.error = error;
		}

		public static IdentityStatus fail(String error) {
			return new IdentityStatus(
				false, false, false, false, null, null, null, null, null, null, error
			);
		}

		public static IdentityStatus fromJson(String json) {
			return new IdentityStatus(
				true,
				"true".equalsIgnoreCase(jsonString(json, "linked")),
				"true".equalsIgnoreCase(jsonString(json, "eligible")),
				"true".equalsIgnoreCase(jsonString(json, "in_grace")),
				jsonString(json, "player_uuid"),
				jsonString(json, "discord_user_id"),
				jsonString(json, "discord_username"),
				jsonString(json, "minecraft_name"),
				jsonString(json, "grace_until"),
				jsonString(json, "left_guild_at"),
				null
			);
		}
	}

	public static final class PluginNotice {
		public final int id;
		public final String type;
		public final String playerUuid;
		public final String discordUsername;
		public final String discordUserId;
		public final String graceUntil;
		public final String createdAt;

		public PluginNotice(
			int id,
			String type,
			String playerUuid,
			String discordUsername,
			String discordUserId,
			String graceUntil,
			String createdAt
		) {
			this.id = id;
			this.type = type;
			this.playerUuid = playerUuid;
			this.discordUsername = discordUsername;
			this.discordUserId = discordUserId;
			this.graceUntil = graceUntil;
			this.createdAt = createdAt;
		}
	}

	public static final class PluginNoticesResult {
		public final boolean ok;
		public final List<PluginNotice> notices;
		public final String error;

		private PluginNoticesResult(boolean ok, List<PluginNotice> notices, String error) {
			this.ok = ok;
			this.notices = notices == null
				? Collections.emptyList()
				: Collections.unmodifiableList(new ArrayList<>(notices));
			this.error = error;
		}

		public static PluginNoticesResult success(List<PluginNotice> notices) {
			return new PluginNoticesResult(true, notices, null);
		}

		public static PluginNoticesResult fail(String error) {
			return new PluginNoticesResult(false, null, error);
		}
	}

	public static CodeResult startDiscordLink(String playerUuid, String minecraftName) {
		String body = "{"
			+ "\"player_uuid\":\"" + escapeJson(playerUuid) + "\","
			+ "\"minecraft_name\":\"" + escapeJson(minecraftName == null ? "" : minecraftName) + "\""
			+ "}";
		return postForCode(
			"/skins/discord/link/start",
			body,
			"API returned OK but no link code."
		);
	}

	public static FeatureCodeResult issueFeatureCode(String playerUuid, String scope) {
		String uuid = playerUuid == null ? "" : playerUuid.trim();
		String sc = scope == null ? "" : scope.trim().toLowerCase(Locale.ROOT);
		if (uuid.isEmpty()) {
			return FeatureCodeResult.fail("player_uuid is required");
		}
		if (!"skin".equals(sc)
			&& !"drink".equals(sc)
			&& !"profile".equals(sc)
			&& !"skin_staff".equals(sc)) {
			return FeatureCodeResult.fail("scope must be skin, drink, skin_staff, or profile");
		}
		String realm = Cache.realmId == null || Cache.realmId.isBlank()
			? "main"
			: Cache.realmId.trim().toLowerCase(Locale.ROOT);
		String body = "{"
			+ "\"player_uuid\":\"" + escapeJson(uuid) + "\","
			+ "\"scope\":\"" + escapeJson(sc) + "\","
			+ "\"realm_id\":\"" + escapeJson(realm) + "\""
			+ "}";
		return postForFeatureCode(
			"/skins/codes",
			body,
			"API returned OK but no feature code.",
			sc
		);
	}

	/**
	 * Last mint timestamp across shared cosmetic scopes (skin + drink).
	 * {@code lastMintAt} may be null when the player has never minted.
	 */
	public static CosmeticMintStatus getCosmeticMintStatus(String playerUuid) {
		String uuid = playerUuid == null ? "" : playerUuid.trim();
		if (uuid.isEmpty()) {
			return CosmeticMintStatus.fail("player_uuid is required");
		}
		String base = Cache.apiBaseUrl;
		String key = Cache.pluginKey;
		if (base == null || base.isEmpty() || key == null || key.isEmpty()) {
			return CosmeticMintStatus.fail(notConfiguredMessage());
		}

		HttpURLConnection connection = null;
		try {
			String encoded = URLEncoder.encode(uuid, StandardCharsets.UTF_8);
			@SuppressWarnings("deprecation")
			URL url = new URL(base + "/skins/plugin/cosmetic-mint-status?player_uuid=" + encoded);
			connection = (HttpURLConnection) url.openConnection();
			connection.setRequestMethod("GET");
			connection.setConnectTimeout(TIMEOUT_MS);
			connection.setReadTimeout(TIMEOUT_MS);
			connection.setRequestProperty("X-Plugin-Key", key);
			connection.setRequestProperty("Accept", "application/json");

			int status = connection.getResponseCode();
			String response = readBody(
				status >= 200 && status < 300
					? connection.getInputStream()
					: connection.getErrorStream()
			);

			if (status == 200) {
				return CosmeticMintStatus.success(jsonString(response, "last_mint_at"));
			}
			return CosmeticMintStatus.fail(detailOrHttp(response, status));
		} catch (Exception e) {
			return CosmeticMintStatus.fail("Could not reach API: " + e.getMessage());
		} finally {
			if (connection != null) {
				connection.disconnect();
			}
		}
	}

	/**
	 * Staff clear of shared skin+drink mint cooldown for a player.
	 */
	public static SimpleResult resetCosmeticMintCooldowns(String playerUuid, String staffUuid) {
		String uuid = playerUuid == null ? "" : playerUuid.trim();
		if (uuid.isEmpty()) {
			return SimpleResult.fail("player_uuid is required");
		}
		StringBuilder sb = new StringBuilder("{");
		sb.append("\"player_uuid\":\"").append(escapeJson(uuid)).append("\"");
		appendOptionalJson(sb, "staff_uuid", staffUuid);
		sb.append("}");
		return postSimple("/skins/plugin/cosmetic-mint-reset", sb.toString());
	}

	public static SimpleResult unlinkDiscord(String playerUuid) {
		String uuid = playerUuid == null ? "" : playerUuid.trim();
		if (uuid.isEmpty()) {
			return SimpleResult.fail("player_uuid is required");
		}
		String body = "{\"player_uuid\":\"" + escapeJson(uuid) + "\"}";
		return postSimple("/skins/discord/link/unlink", body);
	}

	public static MirrorResult postWarning(
		String playerUuid,
		String reason,
		String staffUuid,
		String staffName,
		String discordUserId,
		String minecraftName
	) {
		String uuid = playerUuid == null ? "" : playerUuid.trim();
		String text = reason == null ? "" : reason.trim();
		if (uuid.isEmpty()) {
			return MirrorResult.fail("player_uuid is required");
		}
		if (text.isEmpty()) {
			return MirrorResult.fail("reason is required");
		}
		StringBuilder sb = new StringBuilder("{");
		sb.append("\"player_uuid\":\"").append(escapeJson(uuid)).append("\",");
		sb.append("\"reason\":\"").append(escapeJson(text)).append("\"");
		appendOptionalJson(sb, "staff_uuid", staffUuid);
		appendOptionalJson(sb, "staff_name", staffName);
		appendOptionalJson(sb, "discord_user_id", discordUserId);
		appendOptionalJson(sb, "minecraft_name", minecraftName);
		sb.append('}');
		return postMirror("/skins/moderation/warnings", sb.toString());
	}

	public static MirrorResult postBanEvent(
		String event,
		String playerUuid,
		String discordUserId,
		String minecraftName,
		String reason,
		String duration,
		String staffName,
		long bannedAtMs,
		long expiresAtMs
	) {
		String etype = event == null ? "" : event.trim().toLowerCase(Locale.ROOT);
		if (!"ban".equals(etype) && !"unban".equals(etype)) {
			return MirrorResult.fail("event must be ban or unban");
		}
		StringBuilder sb = new StringBuilder("{");
		sb.append("\"event\":\"").append(escapeJson(etype)).append("\"");
		appendOptionalJson(sb, "player_uuid", playerUuid);
		appendOptionalJson(sb, "discord_user_id", discordUserId);
		appendOptionalJson(sb, "minecraft_name", minecraftName);
		appendOptionalJson(sb, "reason", reason);
		appendOptionalJson(sb, "duration", duration);
		appendOptionalJson(sb, "staff_name", staffName);
		if (bannedAtMs > 0L) {
			sb.append(",\"banned_at_ms\":").append(bannedAtMs);
			sb.append(",\"expires_at_ms\":").append(Math.max(0L, expiresAtMs));
		}
		sb.append('}');
		return postMirror("/skins/moderation/ban-events", sb.toString());
	}

	/** Bans and unbans recorded from every server, for {@code ban-mirror.sync}. */
	public static BanSync getBanSync() {
		String base = Cache.apiBaseUrl;
		String key = Cache.pluginKey;
		if (base == null || base.isEmpty() || key == null || key.isEmpty()) {
			return BanSync.fail(notConfiguredMessage());
		}

		HttpURLConnection connection = null;
		try {
			@SuppressWarnings("deprecation")
			URL url = new URL(base + "/skins/moderation/bans");
			connection = (HttpURLConnection) url.openConnection();
			connection.setRequestMethod("GET");
			connection.setConnectTimeout(TIMEOUT_MS);
			connection.setReadTimeout(TIMEOUT_MS);
			connection.setRequestProperty("X-Plugin-Key", key);
			connection.setRequestProperty("Accept", "application/json");

			int status = connection.getResponseCode();
			String response = readBody(
				status >= 200 && status < 300
					? connection.getInputStream()
					: connection.getErrorStream()
			);

			if (status == 200) {
				return BanSync.fromJson(response);
			}
			return BanSync.fail(detailOrHttp(response, status));
		} catch (Exception e) {
			return BanSync.fail("Could not reach API: " + e.getMessage());
		} finally {
			if (connection != null) {
				connection.disconnect();
			}
		}
	}

	public static MirrorResult postBirdMail(
		String playerUuid,
		String discordUserId,
		String addresseeCharacter,
		String senderMinecraftName,
		String contentsPreview
	) {
		String uuid = playerUuid == null ? "" : playerUuid.trim();
		String discordId = discordUserId == null ? "" : discordUserId.trim();
		String character = addresseeCharacter == null ? "" : addresseeCharacter.trim();
		if (discordId.isEmpty()) {
			return MirrorResult.success(false);
		}
		if (character.isEmpty()) {
			return MirrorResult.fail("addressee_character is required");
		}
		StringBuilder sb = new StringBuilder("{");
		sb.append("\"addressee_character\":\"").append(escapeJson(character)).append('"');
		appendOptionalJson(sb, "player_uuid", uuid);
		appendOptionalJson(sb, "discord_user_id", discordId);
		appendOptionalJson(sb, "sender_minecraft_name", senderMinecraftName);
		appendOptionalJson(sb, "contents_preview", contentsPreview);
		sb.append('}');
		return postMirror("/skins/moderation/bird-mail", sb.toString());
	}

	private static void appendOptionalJson(StringBuilder sb, String key, String value) {
		if (value == null) {
			return;
		}
		String trimmed = value.trim();
		if (trimmed.isEmpty()) {
			return;
		}
		sb.append(",\"").append(key).append("\":\"").append(escapeJson(trimmed)).append('"');
	}

	private static MirrorResult postMirror(String path, String jsonBody) {
		String base = Cache.apiBaseUrl;
		String key = Cache.pluginKey;
		if (base == null || base.isEmpty() || key == null || key.isEmpty()) {
			return MirrorResult.fail(notConfiguredMessage());
		}
		HttpURLConnection connection = null;
		try {
			@SuppressWarnings("deprecation")
			URL url = new URL(base + path);
			connection = (HttpURLConnection) url.openConnection();
			connection.setRequestMethod("POST");
			connection.setConnectTimeout(TIMEOUT_MS);
			connection.setReadTimeout(TIMEOUT_MS);
			connection.setDoOutput(true);
			connection.setRequestProperty("Content-Type", "application/json");
			connection.setRequestProperty("X-Plugin-Key", key);
			connection.setRequestProperty("Accept", "application/json");

			byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
			connection.setFixedLengthStreamingMode(bytes.length);
			try (OutputStream out = connection.getOutputStream()) {
				out.write(bytes);
			}

			int status = connection.getResponseCode();
			String response = readBody(
				status >= 200 && status < 300
					? connection.getInputStream()
					: connection.getErrorStream()
			);
			if (status == 200) {
				if ("true".equalsIgnoreCase(jsonString(response, "duplicate"))) {
					return MirrorResult.duplicate();
				}
				boolean mirrored = "true".equalsIgnoreCase(jsonString(response, "mirrored"));
				return MirrorResult.success(mirrored);
			}
			return MirrorResult.fail(detailOrHttp(response, status));
		} catch (Exception e) {
			return MirrorResult.fail("Could not reach API: " + e.getMessage());
		} finally {
			if (connection != null) {
				connection.disconnect();
			}
		}
	}

	public static IdentityStatus getIdentityStatus(String playerUuid) {
		String uuid = playerUuid == null ? "" : playerUuid.trim();
		if (uuid.isEmpty()) {
			return IdentityStatus.fail("player_uuid is required");
		}
		String base = Cache.apiBaseUrl;
		String key = Cache.pluginKey;
		if (base == null || base.isEmpty() || key == null || key.isEmpty()) {
			return IdentityStatus.fail(notConfiguredMessage());
		}

		HttpURLConnection connection = null;
		try {
			@SuppressWarnings("deprecation")
			URL url = new URL(base + "/skins/discord/status/" + uuid);
			connection = (HttpURLConnection) url.openConnection();
			connection.setRequestMethod("GET");
			connection.setConnectTimeout(TIMEOUT_MS);
			connection.setReadTimeout(TIMEOUT_MS);
			connection.setRequestProperty("X-Plugin-Key", key);
			connection.setRequestProperty("Accept", "application/json");

			int status = connection.getResponseCode();
			String response = readBody(
				status >= 200 && status < 300
					? connection.getInputStream()
					: connection.getErrorStream()
			);

			if (status == 200) {
				return IdentityStatus.fromJson(response);
			}
			return IdentityStatus.fail(detailOrHttp(response, status));
		} catch (Exception e) {
			return IdentityStatus.fail("Could not reach API: " + e.getMessage());
		} finally {
			if (connection != null) {
				connection.disconnect();
			}
		}
	}

	/** Lightweight reachability check for /web status. */
	public static SimpleResult ping() {
		PluginNoticesResult notices = listPluginNotices();
		if (notices.ok) {
			return SimpleResult.success();
		}
		return SimpleResult.fail(notices.error != null ? notices.error : "ping failed");
	}

	public static PluginNoticesResult listPluginNotices() {
		String base = Cache.apiBaseUrl;
		String key = Cache.pluginKey;
		if (base == null || base.isEmpty() || key == null || key.isEmpty()) {
			return PluginNoticesResult.fail(notConfiguredMessage());
		}

		HttpURLConnection connection = null;
		try {
			@SuppressWarnings("deprecation")
			URL url = new URL(base + "/skins/plugin/notices");
			connection = (HttpURLConnection) url.openConnection();
			connection.setRequestMethod("GET");
			connection.setConnectTimeout(TIMEOUT_MS);
			connection.setReadTimeout(TIMEOUT_MS);
			connection.setRequestProperty("X-Plugin-Key", key);
			connection.setRequestProperty("Accept", "application/json");

			int status = connection.getResponseCode();
			String response = readBody(
				status >= 200 && status < 300
					? connection.getInputStream()
					: connection.getErrorStream()
			);

			if (status == 200) {
				return PluginNoticesResult.success(parsePluginNotices(response));
			}
			return PluginNoticesResult.fail(detailOrHttp(response, status));
		} catch (Exception e) {
			return PluginNoticesResult.fail("Could not reach API: " + e.getMessage());
		} finally {
			if (connection != null) {
				connection.disconnect();
			}
		}
	}

	public static SimpleResult ackPluginNotices(List<Integer> ids) {
		if (ids == null || ids.isEmpty()) {
			return SimpleResult.success();
		}
		StringBuilder sb = new StringBuilder("{\"ids\":[");
		for (int i = 0; i < ids.size(); i++) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append(ids.get(i).intValue());
		}
		sb.append("]}");
		return postSimple("/skins/plugin/notices/ack", sb.toString());
	}

	/** Upsert web entitlements for a player (TFMCWeb join sync). */
	public static SimpleResult putRpcPlayerMeta(String jsonBody) {
		if (jsonBody == null || jsonBody.isBlank()) {
			return SimpleResult.fail("body is required");
		}
		ProvinceSystemGateway.GatewayResult raw = ProvinceSystemGateway.request(
			"PUT",
			"/characters/plugin/rpc-player-meta",
			jsonBody
		);
		if (raw.ok) {
			return SimpleResult.success();
		}
		return SimpleResult.fail(raw.error);
	}

	private static SimpleResult postSimple(String path, String jsonBody) {
		return writeSimple("POST", path, jsonBody);
	}

	private static SimpleResult writeSimple(String method, String path, String jsonBody) {
		String base = Cache.apiBaseUrl;
		String key = Cache.pluginKey;
		if (base == null || base.isEmpty() || key == null || key.isEmpty()) {
			return SimpleResult.fail(notConfiguredMessage());
		}

		HttpURLConnection connection = null;
		try {
			@SuppressWarnings("deprecation")
			URL url = new URL(base + path);
			connection = (HttpURLConnection) url.openConnection();
			connection.setRequestMethod(method);
			connection.setConnectTimeout(TIMEOUT_MS);
			connection.setReadTimeout(TIMEOUT_MS);
			connection.setDoOutput(true);
			connection.setRequestProperty("Content-Type", "application/json");
			connection.setRequestProperty("X-Plugin-Key", key);
			connection.setRequestProperty("Accept", "application/json");

			byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
			connection.setFixedLengthStreamingMode(bytes.length);
			try (OutputStream out = connection.getOutputStream()) {
				out.write(bytes);
			}

			int status = connection.getResponseCode();
			String response = readBody(
				status >= 200 && status < 300
					? connection.getInputStream()
					: connection.getErrorStream()
			);

			if (status >= 200 && status < 300) {
				return SimpleResult.success();
			}
			return SimpleResult.fail(detailOrHttp(response, status));
		} catch (Exception e) {
			return SimpleResult.fail("Could not reach API: " + e.getMessage());
		} finally {
			if (connection != null) {
				connection.disconnect();
			}
		}
	}

	private static CodeResult postForCode(String path, String jsonBody, String missingCodeMsg) {
		String base = Cache.apiBaseUrl;
		String key = Cache.pluginKey;
		if (base == null || base.isEmpty() || key == null || key.isEmpty()) {
			return CodeResult.fail(notConfiguredMessage());
		}

		HttpURLConnection connection = null;
		try {
			@SuppressWarnings("deprecation")
			URL url = new URL(base + path);
			connection = (HttpURLConnection) url.openConnection();
			connection.setRequestMethod("POST");
			connection.setConnectTimeout(TIMEOUT_MS);
			connection.setReadTimeout(TIMEOUT_MS);
			connection.setDoOutput(true);
			connection.setRequestProperty("Content-Type", "application/json");
			connection.setRequestProperty("X-Plugin-Key", key);
			connection.setRequestProperty("Accept", "application/json");

			byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
			connection.setFixedLengthStreamingMode(bytes.length);
			try (OutputStream out = connection.getOutputStream()) {
				out.write(bytes);
			}

			int status = connection.getResponseCode();
			String response = readBody(
				status >= 200 && status < 300
					? connection.getInputStream()
					: connection.getErrorStream()
			);

			if (status == 200) {
				String already = jsonString(response, "already_linked");
				if ("true".equalsIgnoreCase(already)) {
					return CodeResult.alreadyLinked(jsonString(response, "discord_username"));
				}
				String code = jsonString(response, "code");
				String expires = jsonString(response, "expires_at");
				if (code == null || code.isEmpty()) {
					return CodeResult.fail(missingCodeMsg);
				}
				return CodeResult.success(code, expires);
			}
			return CodeResult.fail(detailOrHttp(response, status));
		} catch (Exception e) {
			return CodeResult.fail("Could not reach API: " + e.getMessage());
		} finally {
			if (connection != null) {
				connection.disconnect();
			}
		}
	}

	private static FeatureCodeResult postForFeatureCode(
		String path,
		String jsonBody,
		String missingCodeMsg,
		String fallbackScope
	) {
		String base = Cache.apiBaseUrl;
		String key = Cache.pluginKey;
		if (base == null || base.isEmpty() || key == null || key.isEmpty()) {
			return FeatureCodeResult.fail(notConfiguredMessage());
		}

		HttpURLConnection connection = null;
		try {
			@SuppressWarnings("deprecation")
			URL url = new URL(base + path);
			connection = (HttpURLConnection) url.openConnection();
			connection.setRequestMethod("POST");
			connection.setConnectTimeout(TIMEOUT_MS);
			connection.setReadTimeout(TIMEOUT_MS);
			connection.setDoOutput(true);
			connection.setRequestProperty("Content-Type", "application/json");
			connection.setRequestProperty("X-Plugin-Key", key);
			connection.setRequestProperty("Accept", "application/json");

			byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
			connection.setFixedLengthStreamingMode(bytes.length);
			try (OutputStream out = connection.getOutputStream()) {
				out.write(bytes);
			}

			int status = connection.getResponseCode();
			String response = readBody(
				status >= 200 && status < 300
					? connection.getInputStream()
					: connection.getErrorStream()
			);

			if (status == 200) {
				String code = jsonString(response, "code");
				String expires = jsonString(response, "expires_at");
				String scope = jsonString(response, "scope");
				if (scope == null || scope.isEmpty()) {
					scope = fallbackScope;
				}
				if (code == null || code.isEmpty()) {
					return FeatureCodeResult.fail(missingCodeMsg);
				}
				return FeatureCodeResult.success(code, expires, scope);
			}
			return FeatureCodeResult.fail(detailOrHttp(response, status));
		} catch (Exception e) {
			return FeatureCodeResult.fail("Could not reach API: " + e.getMessage());
		} finally {
			if (connection != null) {
				connection.disconnect();
			}
		}
	}

	static List<PluginNotice> parsePluginNotices(String json) {
		List<PluginNotice> out = new ArrayList<>();
		if (json == null || json.isEmpty()) {
			return out;
		}
		String array = jsonArrayBody(json, "notices");
		if (array == null) {
			return out;
		}
		for (String obj : splitJsonObjects(array)) {
			String idRaw = jsonString(obj, "id");
			if (idRaw == null || idRaw.isEmpty()) {
				continue;
			}
			int id;
			try {
				id = Integer.parseInt(idRaw.trim());
			} catch (NumberFormatException e) {
				continue;
			}
			String uuid = jsonString(obj, "player_uuid");
			if (uuid == null || uuid.isEmpty()) {
				continue;
			}
			out.add(new PluginNotice(
				id,
				jsonString(obj, "type"),
				uuid,
				jsonString(obj, "discord_username"),
				jsonString(obj, "discord_user_id"),
				jsonString(obj, "grace_until"),
				jsonString(obj, "created_at")
			));
		}
		return out;
	}

	private static String notConfiguredMessage() {
		return "API is not configured (api.base-url / api.plugin-key in config.yml).";
	}

	private static String detailOrHttp(String response, int status) {
		String detail = jsonString(response, "detail");
		if (detail == null || detail.isEmpty()) {
			detail = response == null || response.isEmpty()
				? ("HTTP " + status)
				: response;
		}
		if (status == 401) {
			return "Unauthorized (check api.plugin-key). " + detail;
		}
		return detail;
	}

	static String jsonArrayBody(String json, String key) {
		if (json == null || key == null) {
			return null;
		}
		String needle = "\"" + key + "\"";
		int keyIdx = json.indexOf(needle);
		if (keyIdx < 0) {
			return null;
		}
		int colon = json.indexOf(':', keyIdx + needle.length());
		if (colon < 0) {
			return null;
		}
		int i = colon + 1;
		while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
			i++;
		}
		if (i >= json.length() || json.charAt(i) != '[') {
			return null;
		}
		int start = i + 1;
		int depth = 1;
		boolean inString = false;
		boolean escape = false;
		for (i = start; i < json.length(); i++) {
			char ch = json.charAt(i);
			if (inString) {
				if (escape) {
					escape = false;
				} else if (ch == '\\') {
					escape = true;
				} else if (ch == '"') {
					inString = false;
				}
				continue;
			}
			if (ch == '"') {
				inString = true;
			} else if (ch == '[') {
				depth++;
			} else if (ch == ']') {
				depth--;
				if (depth == 0) {
					return json.substring(start, i);
				}
			}
		}
		return null;
	}

	static List<String> splitJsonObjects(String arrayBody) {
		List<String> objects = new ArrayList<>();
		if (arrayBody == null) {
			return objects;
		}
		int depth = 0;
		int objStart = -1;
		boolean inString = false;
		boolean escape = false;
		for (int i = 0; i < arrayBody.length(); i++) {
			char ch = arrayBody.charAt(i);
			if (inString) {
				if (escape) {
					escape = false;
				} else if (ch == '\\') {
					escape = true;
				} else if (ch == '"') {
					inString = false;
				}
				continue;
			}
			if (ch == '"') {
				inString = true;
			} else if (ch == '{') {
				if (depth == 0) {
					objStart = i;
				}
				depth++;
			} else if (ch == '}') {
				depth--;
				if (depth == 0 && objStart >= 0) {
					objects.add(arrayBody.substring(objStart, i + 1));
					objStart = -1;
				}
			}
		}
		return objects;
	}

	static String jsonString(String json, String key) {
		if (json == null || key == null) {
			return null;
		}
		try {
			JsonElement root = JsonParser.parseString(json);
			if (!root.isJsonObject()) {
				return null;
			}
			JsonElement value = root.getAsJsonObject().get(key);
			return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
		} catch (com.google.gson.JsonParseException e) {
			return null;
		}
	}

	static String escapeJson(String raw) {
		String encoded = new Gson().toJson(raw == null ? "" : raw);
		return encoded.substring(1, encoded.length() - 1);
	}

	private static String readBody(InputStream stream) throws Exception {
		if (stream == null) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		try (BufferedReader in = new BufferedReader(
			new InputStreamReader(stream, StandardCharsets.UTF_8)
		)) {
			String line;
			while ((line = in.readLine()) != null) {
				sb.append(line);
			}
		}
		return sb.toString();
	}
}
