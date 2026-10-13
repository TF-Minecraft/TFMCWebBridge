package net.tfminecraft.tfmcweb.api;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.tfminecraft.tfmcweb.Cache;
import net.tfminecraft.tfmcweb.TestState;
import org.junit.jupiter.api.*;

class ProvinceSystemGatewayTest {
    TestState state;
    HttpFixture http;
    @BeforeEach void setup() throws Exception { state = new TestState(); http = new HttpFixture(); Cache.realmId = "dev"; }
    @AfterEach void cleanup() throws Exception { http.close(); state.close(); }
    @Test void requestsUseMethodsHeadersBodiesAndTrimBaseSlash() throws Exception {
        Cache.apiBaseUrl += "/";
        http.reply(201, "one\ntwo");
        var result = ProvinceSystemGateway.request(" post ", "/other", "{\"a\":1}");
        assertTrue(result.ok); assertEquals(201, result.status); assertEquals("one\ntwo", result.body); assertNull(result.error);
        var request = http.request(); assertEquals("POST", request.method()); assertEquals("/other", request.path());
        assertEquals("unit-test-key", request.key()); assertEquals("application/json", request.contentType()); assertEquals("{\"a\":1}", request.body());
        http.reply(200, ""); assertTrue(ProvinceSystemGateway.request(null, "/other", null).ok);
        assertEquals("GET", http.request().method());
        http.reply(204, ""); assertTrue(ProvinceSystemGateway.request(" ", "/other", null).ok); assertEquals("GET", http.request().method());
        assertEquals("", ProvinceSystemGateway.GatewayResult.success(200, null).body);
        assertEquals(0, ProvinceSystemGateway.GatewayResult.fail("bad").status);
    }
    @Test void uploadsAndDownloadsPreserveBytes() throws Exception {
        byte[] bytes = new byte[]{0, 1, -1, 42};
        http.reply(200, "ok"); assertTrue(ProvinceSystemGateway.requestBytes(null, "/file", bytes, null).ok);
        var request = http.request(); assertEquals("PUT", request.method()); assertEquals("application/octet-stream", request.contentType());
        http.reply(200, "ok"); assertTrue(ProvinceSystemGateway.requestBytes("POST", "/file", bytes, "image/png").ok);
        assertEquals("image/png", http.request().contentType());
        http.reply(200, bytes); assertArrayEquals(bytes, ProvinceSystemGateway.download("/file").data); http.request();
        http.reply(200, ""); assertEquals("Empty file download", ProvinceSystemGateway.download("/file").error); http.request();
    }
    @Test void failuresKeepStatusAndBackendDetails() throws Exception {
        for (int status : new int[]{400, 401, 500}) {
            http.reply(status, status == 500 ? "" : "{\"detail\":\"denied\"}");
            var result = ProvinceSystemGateway.request("GET", "/fail", null);
            assertFalse(result.ok); assertEquals(status, result.status); assertTrue(result.error.contains(status == 500 ? "HTTP 500" : "denied")); http.request();
            http.reply(status, "plain error"); assertTrue(ProvinceSystemGateway.download("/fail").error.contains("plain error")); http.request();
        }
        for (String base : Arrays.asList(null, "")) {
            Cache.apiBaseUrl = base; assertTrue(ProvinceSystemGateway.request("GET", "/", null).error.contains("not configured"));
            assertTrue(ProvinceSystemGateway.download("/").error.contains("not configured"));
        }
        Cache.apiBaseUrl = "bad URL";
        assertTrue(ProvinceSystemGateway.request("GET", "/", null).error.startsWith("Could not reach API:"));
        assertTrue(ProvinceSystemGateway.download("/").error.startsWith("Could not download:"));
    }
    @Test void injectsQueryOnlyOnAllowlistedGetsAndPreservesExplicitRealm() {
        assertNull(ProvinceSystemGateway.injectRealmPath("GET", null));
        assertEquals(" ", ProvinceSystemGateway.injectRealmPath("GET", " "));
        for (String path : List.of("/characters/plugin/pending", "/skins/plugin/approved", "/drinks/plugin/pending-apply", "/characters/plugin/lore-items/pending", "/characters/plugin/wills/pending")) {
            assertEquals(path+"?realm_id=dev", ProvinceSystemGateway.injectRealmPath("GET", path));
            assertEquals(path+"/?realm_id=dev", ProvinceSystemGateway.injectRealmPath("GET", path+"/"));
            assertEquals(path+"?a=1&realm_id=dev", ProvinceSystemGateway.injectRealmPath("GET", path+"?a=1"));
            assertEquals(path+"?realm_id=dev", ProvinceSystemGateway.injectRealmPath("GET", path+"?"));
            assertEquals(path+"?realm_id=main", ProvinceSystemGateway.injectRealmPath("GET", path+"?realm_id=main"));
            assertEquals(path+"?REALM_ID", ProvinceSystemGateway.injectRealmPath("GET", path+"?REALM_ID"));
            assertEquals(path, ProvinceSystemGateway.injectRealmPath("POST", path));
        }
        assertEquals("/other", ProvinceSystemGateway.injectRealmPath("GET", "/other"));
        Cache.realmId = " dev space ";
        assertEquals("/characters/plugin/pending?realm_id=dev+space", ProvinceSystemGateway.injectRealmPath("GET", "/characters/plugin/pending"));
    }
    @Test void injectsBodiesOnlyOnAllowlistedWrites() {
        for (String method : List.of("PUT", "POST")) {
            List<String> paths = method.equals("PUT") ? List.of("/characters/plugin/roster", "/characters/plugin/rpc-player-meta") : List.of("/wars/declare-codes/validate", "/wars/declare-codes/redeem", "/characters/plugin/wills/applied");
            for (String path : paths) {
                assertEquals("{\"realm_id\":\"dev\"}", ProvinceSystemGateway.injectRealmBody(method, path, "{}"));
                assertEquals("{\"a\":1,\"realm_id\":\"dev\"}", ProvinceSystemGateway.injectRealmBody(method, path+"?x=1", " { \"a\":1 } "));
                assertEquals("{\"realm_id\":\"main\"}", ProvinceSystemGateway.injectRealmBody(method, path, "{\"realm_id\":\"main\"}"));
                for (String body : Arrays.asList(null, "", " ", "[]", "{bad", "bad}")) assertEquals(body, ProvinceSystemGateway.injectRealmBody(method, path, body));
            }
        }
        assertEquals("{}", ProvinceSystemGateway.injectRealmBody("GET", "/characters/plugin/roster", "{}"));
        assertEquals("{}", ProvinceSystemGateway.injectRealmBody("PUT", "/other", "{}"));
        assertEquals("{}", ProvinceSystemGateway.injectRealmBody("PUT", null, "{}"));
    }
    @Test void willRoutesCarryTheRealmAndKeepAnExplicitOne() throws Exception {
        http.reply(200, "{\"requests\":[]}");
        assertTrue(ProvinceSystemGateway.request("GET", "/characters/plugin/wills/pending", null).ok);
        assertEquals("/characters/plugin/wills/pending?realm_id=dev", http.request().path());
        http.reply(200, "{\"requests\":[]}");
        assertTrue(ProvinceSystemGateway.request("GET", "/characters/plugin/wills/pending?realm_id=main", null).ok);
        assertEquals("/characters/plugin/wills/pending?realm_id=main", http.request().path());
        http.reply(200, "{\"ok\":true}");
        assertTrue(ProvinceSystemGateway.request("POST", "/characters/plugin/wills/applied", "{\"results\":[]}").ok);
        var injected = com.google.gson.JsonParser.parseString(http.request().body()).getAsJsonObject();
        assertEquals("dev", injected.get("realm_id").getAsString()); assertTrue(injected.get("results").isJsonArray());
        http.reply(200, "{\"ok\":true}");
        assertTrue(ProvinceSystemGateway.request("POST", "/characters/plugin/wills/applied", "{\"realm_id\":\"main\",\"results\":[]}").ok);
        assertEquals("{\"realm_id\":\"main\",\"results\":[]}", http.request().body());
        // The pull is GET-only and the ack is POST-only.
        assertEquals("/characters/plugin/wills/applied", ProvinceSystemGateway.injectRealmPath("GET", "/characters/plugin/wills/applied"));
        assertEquals("{}", ProvinceSystemGateway.injectRealmBody("PUT", "/characters/plugin/wills/applied", "{}"));
        assertEquals("{}", ProvinceSystemGateway.injectRealmBody("POST", "/characters/plugin/wills/pending", "{}"));
    }
    @Test void patchWorksOnSupportedJavaWithoutOpeningJdkModules() throws Exception {
        http.reply(200, "patched");
        var result = ProvinceSystemGateway.request("PATCH", "/resource", "{}");
        assertTrue(result.ok, result.error); assertEquals("patched", result.body);
        assertEquals("PATCH", http.request().method());
    }
    @Test void backendUnicodeErrorsAreDecoded() throws Exception {
        http.reply(400, "{\"detail\":\"bad\\n\\t\\r\\u0041\\\\\\\"\"}");
        assertEquals("bad\n\t\rA\\\"", ProvinceSystemGateway.request("GET", "/", null).error); http.request();
    }
    @Test void nestedRealmDoesNotSuppressTopLevelInjection() {
        String actual = ProvinceSystemGateway.injectRealmBody("PUT", "/characters/plugin/roster", "{\"nested\":{\"realm_id\":\"other\"}}");
        assertEquals("dev", com.google.gson.JsonParser.parseString(actual).getAsJsonObject().get("realm_id").getAsString());
    }

    @Test void patchHandlesHttpErrorsInvalidUrlsAndInterruption() throws Exception {
        http.reply(400,"{\"detail\":\"no\"}");
        assertEquals("no",ProvinceSystemGateway.request("PATCH","/",null).error);http.request();
        String base=Cache.apiBaseUrl;Cache.apiBaseUrl="invalid URL";
        assertTrue(ProvinceSystemGateway.request("PATCH","/",null).error.startsWith("Could not reach API:"));Cache.apiBaseUrl=base;
        try {
            Thread.currentThread().interrupt();
            assertEquals("Could not reach API: request interrupted",ProvinceSystemGateway.request("PATCH","/",null).error);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
}
