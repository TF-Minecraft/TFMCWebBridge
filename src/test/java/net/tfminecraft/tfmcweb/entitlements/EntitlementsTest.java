package net.tfminecraft.tfmcweb.entitlements;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import com.google.gson.JsonParser;
import net.tfminecraft.tfmcweb.*;
import net.tfminecraft.tfmcweb.api.ProvinceSystemClient;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;

class EntitlementsTest {
    TestState state;
    @BeforeEach void setup() throws Exception { state = new TestState(); }
    @AfterEach void cleanup() throws Exception { state.close(); }
    MetaGroupDefinition group(String permission, int tier, Map<String,Integer> perks, List<String> kinds, Boolean helmet, Boolean texture, Boolean message) {
        return new MetaGroupDefinition(permission, tier, perks, kinds, helmet, texture, message);
    }
    @Test void definitionsNormalizeCopyAndFallback() {
        var empty = group(null, -1, null, null, null, null, null);
        assertEquals("", empty.getPermission()); assertEquals(-1, empty.getTier()); assertFalse(empty.hasIntPerk("x")); assertEquals(5,empty.getIntPerk("x",5));
        assertFalse(empty.hasAllowArmor3dHelmet()); assertTrue(empty.getAllowArmor3dHelmet(true));
        assertFalse(empty.hasAllowDrinkTexture()); assertTrue(empty.getAllowDrinkTexture(true));
        assertFalse(empty.hasAllowDrinkMessage()); assertTrue(empty.getAllowDrinkMessage(true));
        assertTrue(empty.getSkinKinds().isEmpty());
        var perks = new HashMap<>(Map.of("x",2)); var kinds = new ArrayList<>(Arrays.asList(null," "," ITEM ","item","book"));
        var full = group(" rank ", 3, perks, kinds, true, false, true); perks.put("x",9); kinds.clear();
        assertEquals("rank", full.getPermission()); assertEquals(2, full.getIntPerk("x",0)); assertEquals(List.of("item","book"),full.getSkinKinds());
        assertTrue(full.hasAllowArmor3dHelmet()); assertTrue(full.getAllowArmor3dHelmet(false));
        assertTrue(full.hasAllowDrinkTexture()); assertFalse(full.getAllowDrinkTexture(true));
        assertTrue(full.hasAllowDrinkMessage()); assertTrue(full.getAllowDrinkMessage(false));
        assertThrows(UnsupportedOperationException.class, () -> full.getSkinKinds().clear());
        assertTrue(MetaGroupDefinition.copyList(null).isEmpty()); assertTrue(MetaGroupDefinition.copyList(List.of()).isEmpty());
        var list = new ArrayList<>(List.of(full)); var copy=MetaGroupDefinition.copyList(list); list.clear(); assertEquals(List.of(full),copy); assertTrue(MetaGroupDefinition.emptyList().isEmpty());
    }
    @Test void defaultAndRankLaddersResolveClampedMaximaAndInheritedSkinPerks() {
        Cache.rpcMetaDefaults=Map.of(); Cache.skinsMetaDefaults=Map.of(); Cache.drinksMetaDefaults=Map.of();
        Cache.rpcMetaGroups=List.of();Cache.skinsMetaGroups=List.of();Cache.drinksMetaGroups=List.of();
        Cache.skinsMetaDefaultKinds=Arrays.asList(null," "," ITEM ");
        var defaults=EntitlementResolver.resolve(null);
        assertEquals(1,defaults.maxAliveCharacters);assertEquals(1,defaults.wardrobeSkinSlots);assertEquals(30720,defaults.max3dPairBytes);assertEquals(-1,defaults.skinTokenCooldownDays);assertEquals(List.of("item"),defaults.skinKinds);
        Player player=mock(Player.class); when(player.hasPermission("rank.high")).thenReturn(true);when(player.hasPermission("allowed")).thenReturn(true);
        var low=group("rank.low",1,Map.of("name-colour-stops",2,"skin-token-cooldown-days",5),List.of("book"),true,false,false);
        var high=group("rank.high",3,Map.of("name-colour-stops",4,"max-alive-characters",8,"wardrobe-skin-slots",8,"max-3d-pair-bytes",60000),List.of("armor"),null,true,true);
        var higher=group("rank.none",4,Map.of("skin-token-cooldown-days",0),List.of("forbidden"),false,true,true);
        var blank=group(null,0,null,null,null,null,null);
        Cache.rpcMetaGroups=Arrays.asList(null,blank,low,high,higher);
        Cache.skinsMetaGroups=Arrays.asList(null,blank,low,high,higher);
        Cache.drinksMetaGroups=Arrays.asList(null,blank,low,high,higher);
        Cache.playerMetaSyncPermissions=Arrays.asList(null," "," allowed ","denied");
        var meta=EntitlementResolver.resolve(player);
        assertEquals(4,meta.nameColourStops);assertEquals(8,meta.maxAliveCharacters);assertEquals(3,meta.wardrobeSkinSlots);assertEquals(60000,meta.max3dPairBytes);
        assertEquals(5,meta.skinTokenCooldownDays);assertEquals(List.of("item","book","armor"),meta.skinKinds);assertTrue(meta.allowArmor3dHelmet && meta.allowDrinkTexture && meta.allowDrinkMessage);
        assertEquals(Map.of("allowed",true,"denied",false),meta.permissionFlags);
        Cache.skinsMetaGroups=List.of(high); assertEquals(-1,EntitlementResolver.resolve(player).skinTokenCooldownDays); assertFalse(EntitlementResolver.resolve(player).allowArmor3dHelmet);
        Cache.drinksMetaGroups=List.of(low);when(player.hasPermission("rank.low")).thenReturn(true);assertFalse(EntitlementResolver.resolve(player).allowDrinkTexture);assertFalse(EntitlementResolver.resolve(player).allowDrinkMessage);
    }
    @Test void syncJsonCopiesValuesAndSkipsBlankKeys() {
        UUID id=UUID.randomUUID(); Cache.realmId="dev";
        var meta=new EntitlementResolver.ResolvedMeta(3,true,false,8,2,40000,5,List.of(" BOOK "," ","item"),true,Map.of("p\"\\",true,"q",false," ",true));
        var json=JsonParser.parseString(PlayerMetaSyncService.toJson(id,meta)).getAsJsonObject();
        assertEquals(id.toString(),json.get("player_uuid").getAsString());assertEquals("dev",json.get("realm_id").getAsString());assertEquals(8,json.get("max_alive_characters").getAsInt());
        assertEquals(2,json.getAsJsonArray("skin_kinds").size());assertEquals(2,json.getAsJsonObject("permission_flags").size());
        var minimal=new EntitlementResolver.ResolvedMeta(0,false,false,null,1,1,0,List.of(),false,Map.of());
        assertFalse(JsonParser.parseString(PlayerMetaSyncService.toJson(id,minimal)).getAsJsonObject().has("max_alive_characters"));
    }
    @Test void syncCapturesPermissionsBeforeSchedulingAndHandlesOfflineAndFailures() {
        UUID id=UUID.randomUUID();Player player=mock(Player.class);when(player.getUniqueId()).thenReturn(id);when(player.isOnline()).thenReturn(true);
        TFMCWeb plugin=mock(TFMCWeb.class);var logger=mock(java.util.logging.Logger.class);when(plugin.getLogger()).thenReturn(logger);
        BukkitScheduler scheduler=mock(BukkitScheduler.class);List<Runnable> async=new ArrayList<>();
        doAnswer(call->{call.getArgument(1,Runnable.class).run();return null;}).when(scheduler).runTask(eq(plugin),any(Runnable.class));
        doAnswer(call->{async.add(call.getArgument(1,Runnable.class));return null;}).when(scheduler).runTaskAsynchronously(eq(plugin),any(Runnable.class));
        try(MockedStatic<Bukkit> b=mockStatic(Bukkit.class);MockedStatic<ProvinceSystemClient> api=mockStatic(ProvinceSystemClient.class)) {
            b.when(Bukkit::getScheduler).thenReturn(scheduler);b.when(()->Bukkit.getPlayer(id)).thenReturn(player);b.when(Bukkit::getOnlinePlayers).thenReturn(Arrays.asList(null,player));
            TFMCWeb.plugin=null;PlayerMetaSyncService.pushForPlayer(player);PlayerMetaSyncService.pushAsync(id);PlayerMetaSyncService.pushAllOnlineAsync();assertTrue(async.isEmpty());
            TFMCWeb.plugin=plugin;PlayerMetaSyncService.pushForPlayer(null);PlayerMetaSyncService.pushAsync(null);
            api.when(()->ProvinceSystemClient.putRpcPlayerMeta(anyString())).thenReturn(ProvinceSystemClient.SimpleResult.success());
            PlayerMetaSyncService.pushAsync(id);assertEquals(1,async.size());async.removeFirst().run();
            when(player.isOnline()).thenReturn(false);PlayerMetaSyncService.pushAsync(id);assertTrue(async.isEmpty());
            b.when(()->Bukkit.getPlayer(id)).thenReturn(null);PlayerMetaSyncService.pushAsync(id);assertTrue(async.isEmpty());
            api.when(()->ProvinceSystemClient.putRpcPlayerMeta(anyString())).thenReturn(ProvinceSystemClient.SimpleResult.fail("unavailable"));
            PlayerMetaSyncService.pushAllOnlineAsync();assertEquals(1,async.size());async.removeFirst().run();verify(logger).warning(contains("unavailable"));
        }
    }
    @Test void changeOnlySyncSkipsRepeatsAndRetriesAfterFailure() {
        UUID id=UUID.randomUUID();Player player=mock(Player.class);when(player.getUniqueId()).thenReturn(id);Cache.playerMetaSyncPermissions=List.of("tfmc.flag");
        TFMCWeb plugin=mock(TFMCWeb.class);when(plugin.getLogger()).thenReturn(mock(java.util.logging.Logger.class));TFMCWeb.plugin=plugin;
        BukkitScheduler scheduler=mock(BukkitScheduler.class);List<Runnable> async=new ArrayList<>();
        doAnswer(call->{async.add(call.getArgument(1,Runnable.class));return null;}).when(scheduler).runTaskAsynchronously(eq(plugin),any(Runnable.class));
        try(MockedStatic<Bukkit> b=mockStatic(Bukkit.class);MockedStatic<ProvinceSystemClient> api=mockStatic(ProvinceSystemClient.class)) {
            b.when(Bukkit::getScheduler).thenReturn(scheduler);
            api.when(()->ProvinceSystemClient.putRpcPlayerMeta(anyString())).thenReturn(ProvinceSystemClient.SimpleResult.success());
            PlayerMetaSyncService.pushIfChanged(null);PlayerMetaSyncService.pushIfChanged(player);assertEquals(1,async.size());async.removeFirst().run();
            PlayerMetaSyncService.pushIfChanged(player);assertTrue(async.isEmpty());
            PlayerMetaSyncService.pushForPlayer(player);assertEquals(1,async.size());async.removeFirst().run();
            when(player.hasPermission("tfmc.flag")).thenReturn(true);PlayerMetaSyncService.pushIfChanged(player);assertEquals(1,async.size());
            api.when(()->ProvinceSystemClient.putRpcPlayerMeta(anyString())).thenReturn(ProvinceSystemClient.SimpleResult.fail("down"));async.removeFirst().run();
            PlayerMetaSyncService.pushIfChanged(player);assertEquals(1,async.size());
            api.when(()->ProvinceSystemClient.putRpcPlayerMeta(anyString())).thenThrow(new IllegalStateException("boom"));async.removeFirst().run();
            verify(plugin.getLogger()).warning(contains("boom"));
            PlayerMetaSyncService.pushIfChanged(player);assertEquals(1,async.size());
            api.verify(()->ProvinceSystemClient.putRpcPlayerMeta(contains("\"tfmc.flag\":true")),times(2));
        }
    }
    @Test void sendsForOnePlayerStayInOrderAndOnlyTheNewestQueuedBodyIsSent() {
        UUID id=UUID.randomUUID();Player player=mock(Player.class);when(player.getUniqueId()).thenReturn(id);Cache.playerMetaSyncPermissions=List.of("tfmc.flag");
        TFMCWeb plugin=mock(TFMCWeb.class);when(plugin.getLogger()).thenReturn(mock(java.util.logging.Logger.class));TFMCWeb.plugin=plugin;
        BukkitScheduler scheduler=mock(BukkitScheduler.class);List<Runnable> async=new ArrayList<>();
        doAnswer(call->{async.add(call.getArgument(1,Runnable.class));return null;}).when(scheduler).runTaskAsynchronously(eq(plugin),any(Runnable.class));
        List<String> sent=new ArrayList<>();
        try(MockedStatic<Bukkit> b=mockStatic(Bukkit.class);MockedStatic<ProvinceSystemClient> api=mockStatic(ProvinceSystemClient.class)) {
            b.when(Bukkit::getScheduler).thenReturn(scheduler);
            api.when(()->ProvinceSystemClient.putRpcPlayerMeta(anyString())).thenAnswer(c->{sent.add(c.getArgument(0));return ProvinceSystemClient.SimpleResult.success();});
            PlayerMetaSyncService.pushForPlayer(player);when(player.hasPermission("tfmc.flag")).thenReturn(true);PlayerMetaSyncService.pushIfChanged(player);
            assertEquals(1,async.size());async.removeFirst().run();
            assertEquals(1,sent.size());assertTrue(sent.getFirst().contains("\"tfmc.flag\":true"));
            boolean[] flipped={false};
            api.when(()->ProvinceSystemClient.putRpcPlayerMeta(anyString())).thenAnswer(c->{
                sent.add(c.getArgument(0));
                if(!flipped[0]){flipped[0]=true;when(player.hasPermission("tfmc.flag")).thenReturn(true);PlayerMetaSyncService.pushIfChanged(player);}
                return ProvinceSystemClient.SimpleResult.success();
            });
            sent.clear();flipped[0]=false;
            // Site holds true; a false send is in flight when the flag flips back, so true must follow it.
            when(player.hasPermission("tfmc.flag")).thenReturn(false);PlayerMetaSyncService.pushIfChanged(player);assertEquals(1,async.size());async.removeFirst().run();
            assertTrue(async.isEmpty());assertEquals(2,sent.size());assertTrue(sent.get(0).contains("\"tfmc.flag\":false"));assertTrue(sent.get(1).contains("\"tfmc.flag\":true"));
            PlayerMetaSyncService.pushIfChanged(player);assertTrue(async.isEmpty());
        }
    }
    @Test void syncEscapesControlCharactersAndUsesRootLocale() {
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));Cache.realmId="MAIN";
        var meta=new EntitlementResolver.ResolvedMeta(0,false,false,1,1,1,0,List.of("ITEM"),false,Map.of("a\n\u0001",true));
        String payload=PlayerMetaSyncService.toJson(UUID.randomUUID(),meta);
        assertFalse(payload.contains("\n"));assertFalse(payload.contains("\u0001"));
        var json=JsonParser.parseString(payload).getAsJsonObject();assertEquals("item",json.getAsJsonArray("skin_kinds").get(0).getAsString());
    }
}
