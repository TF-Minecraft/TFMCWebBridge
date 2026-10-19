package net.tfminecraft.tfmcweb.api;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import java.util.*;
import net.tfminecraft.tfmcweb.Cache;
import net.tfminecraft.tfmcweb.TestState;
import net.tfminecraft.tfmcweb.api.ProvinceSystemClient.*;
import org.junit.jupiter.api.*;

class ProvinceSystemClientTest {
    TestState state;
    HttpFixture http;
    @BeforeEach void setup() throws Exception { state = new TestState(); http = new HttpFixture(); }
    @AfterEach void cleanup() throws Exception { http.close(); state.close(); }

    @Test void linkResponsesAndRequestEscaping() throws Exception {
        http.reply(200, "{\"code\":\"ABC\",\"expires_at\":\"tomorrow\"}");
        CodeResult result = ProvinceSystemClient.startDiscordLink("id", "name\"\\\n\r\t");
        assertTrue(result.ok); assertFalse(result.alreadyLinked); assertEquals("ABC", result.code);
        assertEquals("tomorrow", result.expiresAt); assertNull(result.error);
        var req = http.request();
        assertEquals("POST", req.method()); assertEquals("/skins/discord/link/start", req.path());
        assertEquals("unit-test-key", req.key()); assertEquals("application/json", req.contentType());
        assertEquals("name\"\\\n\r\t", JsonParser.parseString(req.body()).getAsJsonObject().get("minecraft_name").getAsString());
        http.reply(200, "{\"already_linked\":true,\"discord_username\":\"ada\"}");
        result = ProvinceSystemClient.startDiscordLink(null, null);
        assertTrue(result.alreadyLinked); assertEquals("ada", result.discordUsername); http.request();
        for (String body : List.of("{}", "{\"code\":\"\"}")) {
            http.reply(200, body); assertFalse(ProvinceSystemClient.startDiscordLink("id", "a").ok); http.request();
        }
    }
    @Test void featuresValidateScopeAndFallbackResponse() throws Exception {
        assertFalse(ProvinceSystemClient.issueFeatureCode(null, null).ok);
        assertFalse(ProvinceSystemClient.issueFeatureCode("id", null).ok);
        assertFalse(ProvinceSystemClient.issueFeatureCode("id", "unknown").ok);
        for (String scope : List.of("skin", "drink", "profile", "skin_staff")) {
            Cache.realmId = " DEV ";
            http.reply(200, "{\"code\":\"x\",\"expires_at\":\"soon\"}");
            var result = ProvinceSystemClient.issueFeatureCode(" id ", scope.toUpperCase(Locale.ROOT));
            assertTrue(result.ok); assertEquals(scope, result.scope); assertEquals("x", result.code); assertEquals("soon", result.expiresAt);
            var body = JsonParser.parseString(http.request().body()).getAsJsonObject();
            assertEquals("dev", body.get("realm_id").getAsString()); assertEquals("id", body.get("player_uuid").getAsString());
        }
        for (String realm : Arrays.asList(null, " ")) {
            Cache.realmId = realm;
            http.reply(200, "{\"code\":\"x\",\"scope\":\"profile\"}");
            assertEquals("profile", ProvinceSystemClient.issueFeatureCode("id", "skin").scope);
            assertTrue(http.request().body().contains("\"main\""));
        }
        for (String body : List.of("{}", "{\"code\":\"\",\"scope\":\"\"}")) {
            http.reply(200, body); assertFalse(ProvinceSystemClient.issueFeatureCode("id", "skin").ok); http.request();
        }
    }
    @Test void identityMintStatusAndValidation() throws Exception {
        assertFalse(ProvinceSystemClient.getIdentityStatus(null).ok);
        assertFalse(ProvinceSystemClient.getCosmeticMintStatus(" ").ok);
        http.reply(200, "{\"linked\":true,\"eligible\":true,\"in_grace\":true,\"player_uuid\":\"id\",\"discord_user_id\":\"d\",\"discord_username\":\"Ada\",\"minecraft_name\":\"mc\",\"grace_until\":\"g\",\"left_guild_at\":\"l\"}");
        IdentityStatus result = ProvinceSystemClient.getIdentityStatus(" id ");
        assertTrue(result.ok && result.linked && result.eligible && result.inGrace);
        assertEquals("id", result.playerUuid); assertEquals("d", result.discordUserId); assertEquals("Ada", result.discordUsername);
        assertEquals("mc", result.minecraftName); assertEquals("g", result.graceUntil); assertEquals("l", result.leftGuildAt); assertNull(result.error);
        assertEquals("/skins/discord/status/id", http.request().path());
        http.reply(200, "{\"last_mint_at\":null}");
        assertNull(ProvinceSystemClient.getCosmeticMintStatus("a b").lastMintAt);
        assertEquals("/skins/plugin/cosmetic-mint-status?player_uuid=a+b", http.request().path());
    }
    @Test void simpleOperationsModerationAndOptionalFields() throws Exception {
        assertFalse(ProvinceSystemClient.unlinkDiscord(null).ok);
        assertFalse(ProvinceSystemClient.resetCosmeticMintCooldowns(null, null).ok);
        assertFalse(ProvinceSystemClient.postWarning(null, "reason", null, null, null, null).ok);
        assertFalse(ProvinceSystemClient.postWarning("id", null, null, null, null, null).ok);
        assertFalse(ProvinceSystemClient.postBanEvent(null, null, null, null, null, null, null, 0L, 0L).ok);
        assertTrue(ProvinceSystemClient.postBirdMail(null, null, null, null, null).ok);
        assertFalse(ProvinceSystemClient.postBirdMail("id", "discord", null, null, null).ok);
        http.reply(204, ""); assertTrue(ProvinceSystemClient.unlinkDiscord(" id ").ok);
        assertEquals("{\"player_uuid\":\"id\"}", http.request().body());
        http.reply(200, "{}"); assertTrue(ProvinceSystemClient.resetCosmeticMintCooldowns("id", " staff ").ok);
        assertTrue(http.request().body().contains("\"staff_uuid\":\"staff\""));
        http.reply(200, "{\"mirrored\":true}");
        assertTrue(ProvinceSystemClient.postWarning("id", " reason ", "staff", "Ada", "disc", "mc").mirrored);
        assertEquals("reason", JsonParser.parseString(http.request().body()).getAsJsonObject().get("reason").getAsString());
        http.reply(200, "{}"); assertFalse(ProvinceSystemClient.postBanEvent(" BAN ", null, " ", "mc", "why", "1d", "staff", 0L, 0L).mirrored);
        var banRequest = http.request();
        assertEquals("/skins/moderation/ban-events", banRequest.path());
        assertFalse(banRequest.body().contains("banned_at_ms"));
        http.reply(200, "{}"); ProvinceSystemClient.postBanEvent("ban", "id", null, null, null, null, null, 1000L, -5L);
        var timed = JsonParser.parseString(http.request().body()).getAsJsonObject();
        http.reply(200, "{\"mirrored\":false,\"duplicate\":true}");
        assertTrue(ProvinceSystemClient.postBanEvent("unban", "id", null, null, null, null, null, 0L, 0L).duplicate); http.request();
        assertEquals(1000L, timed.get("banned_at_ms").getAsLong()); assertEquals(0L, timed.get("expires_at_ms").getAsLong());
        http.reply(200, "{\"mirrored\":true}");
        assertTrue(ProvinceSystemClient.postBirdMail(" id ", "disc", " Char ", "sender", "letter").ok);
        assertEquals("/skins/moderation/bird-mail", http.request().path());
        assertTrue(ProvinceSystemClient.ackPluginNotices(null).ok); assertTrue(ProvinceSystemClient.ackPluginNotices(List.of()).ok);
        http.reply(200, "{}"); assertTrue(ProvinceSystemClient.ackPluginNotices(List.of(1, 2)).ok);
        assertEquals("{\"ids\":[1,2]}", http.request().body());
        assertFalse(ProvinceSystemClient.putRpcPlayerMeta(null).ok); assertFalse(ProvinceSystemClient.putRpcPlayerMeta(" ").ok);
        http.reply(200, "{}"); assertTrue(ProvinceSystemClient.putRpcPlayerMeta("{}").ok); assertEquals("PUT", http.request().method());
        http.reply(500, "failure"); assertEquals("failure", ProvinceSystemClient.putRpcPlayerMeta("{}").error); http.request();
    }
    @Test void noticesParseFieldsAndCopyResults() throws Exception {
        String valid = "{\"id\":12,\"player_uuid\":\"id\",\"type\":\"linked\",\"discord_username\":\"Ada\",\"discord_user_id\":\"d\",\"grace_until\":\"g\",\"created_at\":\"c\"}";
        http.reply(200, "{\"notices\":[{}, {\"id\":\"bad\"}, {\"id\":1},"+valid+"]}");
        var result = ProvinceSystemClient.listPluginNotices(); assertTrue(result.ok); assertEquals(1, result.notices.size());
        var notice = result.notices.getFirst(); assertEquals(12, notice.id); assertEquals("id", notice.playerUuid);
        assertEquals("linked", notice.type); assertEquals("Ada", notice.discordUsername); assertEquals("d", notice.discordUserId);
        assertEquals("g", notice.graceUntil); assertEquals("c", notice.createdAt); http.request();
        assertThrows(UnsupportedOperationException.class, () -> result.notices.clear());
        var mutable = new ArrayList<>(result.notices); var copy = PluginNoticesResult.success(mutable); mutable.clear(); assertEquals(1, copy.notices.size());
        assertTrue(PluginNoticesResult.success(null).notices.isEmpty());
        for (String json : Arrays.asList(null, "", "{}")) assertTrue(ProvinceSystemClient.parsePluginNotices(json).isEmpty());
        http.reply(200, "{}"); assertTrue(ProvinceSystemClient.ping().ok); http.request();
        http.reply(503, ""); assertEquals("HTTP 503", ProvinceSystemClient.ping().error); http.request();
    }
    @Test void banSyncParsesBansAndUnbans() throws Exception {
        UUID ada = UUID.randomUUID(), bob = UUID.randomUUID();
        http.reply(200, "{\"bans\":[{\"player_uuid\":\"bad\"},{},{\"player_uuid\":\"" + ada + "\",\"minecraft_name\":\"Ada\","
            + "\"reason\":null,\"staff_name\":\"Mod\",\"banned_at_ms\":5,\"expires_at_ms\":0}],"
            + "\"unbans\":[{\"player_uuid\":\"" + bob + "\",\"unbanned_at_ms\":7}]}");
        var sync = ProvinceSystemClient.getBanSync();
        assertEquals("/skins/moderation/bans", http.request().path());
        assertTrue(sync.ok); assertEquals(1, sync.bans.size());
        var ban = sync.bans.getFirst();
        assertEquals(ada, ban.id); assertEquals("Ada", ban.name); assertNull(ban.reason); assertEquals("Mod", ban.staffName);
        assertEquals(5L, ban.bannedAt); assertEquals(0L, ban.expiresAt);
        assertEquals(Map.of(bob, 7L), sync.unbans);
        assertTrue(ProvinceSystemClient.BanSync.fromJson("{}").bans.isEmpty());
        assertTrue(ProvinceSystemClient.BanSync.fromJson("[]").error.startsWith("Bad ban list"));
    }
    @Test void allTransportFamiliesReportHttpFailuresConfigurationAndExceptions() throws Exception {
        List<java.util.function.Supplier<String>> operations = List.of(
            () -> ProvinceSystemClient.startDiscordLink("id", "mc").error,
            () -> ProvinceSystemClient.issueFeatureCode("id", "skin").error,
            () -> ProvinceSystemClient.getCosmeticMintStatus("id").error,
            () -> ProvinceSystemClient.getIdentityStatus("id").error,
            () -> ProvinceSystemClient.listPluginNotices().error,
            () -> ProvinceSystemClient.getBanSync().error,
            () -> ProvinceSystemClient.unlinkDiscord("id").error,
            () -> ProvinceSystemClient.postWarning("id", "reason", null, null, null, null).error);
        String base = Cache.apiBaseUrl;
        for (var operation : operations) {
            for (int status : new int[]{400, 401, 500}) {
                http.reply(status, status == 500 ? "" : "{\"detail\":\"denied\"}");
                String error = operation.get(); assertNotNull(error);
                assertTrue(error.contains(status == 500 ? "HTTP 500" : status == 401 ? "Unauthorized" : "denied"), error); http.request();
            }
            Cache.apiBaseUrl = "invalid URL"; assertTrue(operation.get().startsWith("Could not reach API:"));
            Cache.apiBaseUrl = null; assertTrue(operation.get().contains("not configured"));
            Cache.apiBaseUrl = base; Cache.pluginKey = ""; assertTrue(operation.get().contains("not configured"));
            Cache.pluginKey = "unit-test-key";
        }
    }
    @Test void jsonEscapesAndRootFieldsAreDecodedCorrectly() {
        assertEquals("line\n\t\r\b\f/A", ProvinceSystemClient.jsonString("{\"value\":\"line\\n\\t\\r\\b\\f\\/\\u0041\"}", "value"));
        assertEquals("root", ProvinceSystemClient.jsonString("{\"nested\":{\"value\":\"wrong\"},\"value\":\"root\"}", "value"));
        String control = "a\u0001\b\f";
        assertEquals(control, JsonParser.parseString("\""+ProvinceSystemClient.escapeJson(control)+"\"").getAsString());
        assertFalse(ProvinceSystemClient.escapeJson(control).contains("\u0001"));
    }
    @Test void scopeAndRealmAreLocaleIndependent() throws Exception {
        Locale.setDefault(Locale.forLanguageTag("tr-TR")); Cache.realmId = "MAIN";
        http.reply(200, "{\"code\":\"yes\"}");
        assertTrue(ProvinceSystemClient.issueFeatureCode("id", "SKIN").ok);
        assertTrue(http.request().body().contains("\"main\""));
    }

    @Test void noticesWithoutAPlayerUuidAreSkipped() {
        assertEquals("3", ProvinceSystemClient.jsonString("{\"id\":\"3\"}", "id"));
        assertTrue(ProvinceSystemClient.parsePluginNotices("{\"notices\":[{\"id\":\"3\",\"player_uuid\":\"\"}]}").isEmpty());
        assertTrue(ProvinceSystemClient.parsePluginNotices("{\"notices\":[{\"id\":\"3\",\"player_uuid\":null}]}").isEmpty());
    }
    @Test void parserHandlesEmptyMalformedAndNestedDocuments() {
        assertNull(ProvinceSystemClient.jsonString(null,"x")); assertNull(ProvinceSystemClient.jsonString("{}",null));
        for(String json:List.of("[]","null","{bad","{\"x\":null}","{\"x\":{}}")) assertNull(ProvinceSystemClient.jsonString(json,"x"));
        assertEquals("42",ProvinceSystemClient.jsonString("{\"x\":42}","x"));
        assertTrue(ProvinceSystemClient.parsePluginNotices("{\"notices\":[{}, {\"id\":1}, {\"id\":\"\"}, {\"id\":\"no\"}]}").isEmpty());
        assertNull(ProvinceSystemClient.jsonArrayBody(null,"x")); assertNull(ProvinceSystemClient.jsonArrayBody("{}",null));
        for(String json:List.of("{}","{\"x\"}","{\"x\":", "{\"x\":1}","{\"x\":[")) assertNull(ProvinceSystemClient.jsonArrayBody(json,"x"));
        String array="[1],{\"text\":\"quoted\\\" ] \\\\ \",\"nested\":{\"a\":1}}";
        assertEquals(array,ProvinceSystemClient.jsonArrayBody("{\"x\": ["+array+"]}","x"));
        assertTrue(ProvinceSystemClient.splitJsonObjects(null).isEmpty());
        assertEquals(List.of("{\"text\":\"quoted\\\" ] \\\\ \",\"nested\":{\"a\":1}}"),ProvinceSystemClient.splitJsonObjects(array));
    }
}
