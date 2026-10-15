package net.tfminecraft.tfmcweb.managers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.*;
import net.tfminecraft.tfmcweb.*;
import net.tfminecraft.tfmcweb.api.ProvinceSystemClient;
import net.tfminecraft.tfmcweb.api.ProvinceSystemClient.*;
import net.tfminecraft.tfmcweb.entitlements.PlayerMetaSyncService;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandExecutor;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;

class PlayerAndAdminCommandsTest {
    CommandFixture f;
    @BeforeEach void setup() throws Exception {f=new CommandFixture();}
    @AfterEach void cleanup() throws Exception {f.close();}
    void run(CommandExecutor command,org.bukkit.command.CommandSender sender,String...args){assertTrue(command.onCommand(sender,null,"cmd",args));}
    @Test void operandFreeDiscordCommandsNeverSuggestPlayerNames() {
        var link=new LinkDiscordCommand(f.plugin,f.cache,f.gate);
        var unlink=new UnlinkDiscordCommand(f.plugin,f.cache,f.gate);
        for(var sender:List.of(f.player,f.console)) {
            for(String[] args:List.of(new String[0],new String[]{""},new String[]{"A"},new String[]{"Ada",""})) {
                assertTrue(link.onTabComplete(sender,null,"linkdiscord",args).isEmpty());
                assertTrue(unlink.onTabComplete(sender,null,"unlinkdiscord",args).isEmpty());
            }
        }
    }

    @Test void warningOnlyCompletesTheAuthorizedTargetArgument() {
        var warning=new WarningCommand(f.plugin,f.cache);
        assertTrue(warning.onTabComplete(f.console,null,"warning",new String[]{""}).isEmpty());
        f.permissions(f.console,true);
        assertEquals(List.of("Ada"),warning.onTabComplete(f.console,null,"warning",new String[]{""}));
        assertEquals(List.of("Ada"),warning.onTabComplete(f.console,null,"warning",new String[]{"aD"}));
        assertTrue(warning.onTabComplete(f.console,null,"warning",new String[]{"zzz"}).isEmpty());
        for(String[] args:List.of(new String[0],new String[]{"Ada",""},new String[]{"Ada","reason",""})) {
            assertTrue(warning.onTabComplete(f.console,null,"warning",args).isEmpty());
        }
    }

    @Test void linkShowsCodesFailuresOrExistingIdentityAndIgnoresOfflinePlayer() {
        var cmd=new LinkDiscordCommand(f.plugin,f.cache,f.gate);run(cmd,f.console);assertTrue(f.contains("Players only"));
        for(CodeResult result:List.of(CodeResult.fail(null),CodeResult.fail("backend"),CodeResult.alreadyLinked(" AdaDiscord "),CodeResult.alreadyLinked(" "),CodeResult.success("ABC",Instant.now().plusSeconds(4000).toString()),CodeResult.success("ABC",null))){
            f.messages.clear();f.api.when(()->ProvinceSystemClient.startDiscordLink(f.id.toString(),"Ada")).thenReturn(result);run(cmd,f.player);
            assertTrue(f.contains(!result.ok?result.error==null?"Link failed":"backend":result.alreadyLinked?"Already linked":"Discord link code"));
            if(result.alreadyLinked)assertTrue(f.cache.isEligible(f.id));
        }
        f.messages.clear();when(f.player.isOnline()).thenReturn(false);run(cmd,f.player);assertTrue(f.messages.isEmpty());verify(f.gate,times(2)).applyGate(f.player,true);
    }
    @Test void unlinkUpdatesCacheAndGateOnlyWhenSuccessfulAndOnline() {
        var cmd=new UnlinkDiscordCommand(f.plugin,f.cache,f.gate);run(cmd,f.console);assertTrue(f.contains("Players only"));
        for(SimpleResult result:List.of(SimpleResult.fail(null),SimpleResult.fail("backend"),SimpleResult.success())){
            f.messages.clear();f.cache.putLinked(f.id,"d","name");f.api.when(()->ProvinceSystemClient.unlinkDiscord(f.id.toString())).thenReturn(result);run(cmd,f.player);
            assertEquals(result.ok,!f.cache.isEligible(f.id));assertTrue(f.contains(result.ok?"Discord unlinked":result.error==null?"Unlink failed":"backend"));
        }
        f.messages.clear();when(f.player.isOnline()).thenReturn(false);run(cmd,f.player);assertTrue(f.messages.isEmpty());verify(f.gate).applyGate(f.player,false);
    }
    @Test void warningsValidateTargetsAndMirrorCachedOrFetchedIdentity() {
        var cmd=new WarningCommand(f.plugin,f.cache);run(cmd,f.console,"Ada","reason");assertTrue(f.contains("No permission"));
        f.permissions(f.console,true);f.permissions(f.player,true);run(cmd,f.console);assertTrue(f.contains("Usage:"));
        run(cmd,f.console," ","reason");run(cmd,f.console,"missing","reason");assertTrue(f.contains("Unknown player"));
        run(cmd,f.console,"Ada"," ");assertTrue(f.contains("Reason is required"));
        f.api.when(()->ProvinceSystemClient.postWarning(anyString(),anyString(),nullable(String.class),anyString(),nullable(String.class),anyString())).thenReturn(MirrorResult.success(true));
        f.cache.putLinked(f.id,"cached","name");run(cmd,f.player,"Ada","reason","words");
        f.api.verify(()->ProvinceSystemClient.postWarning(f.id.toString(),"reason words",f.id.toString(),"Ada","cached","Ada"));assertTrue(f.contains("[Warning]"));
        var expectedIdentity0=IdentityStatus.fromJson("{\"discord_user_id\":\"fetched\"}");
        f.cache.clear(f.id);f.api.when(()->ProvinceSystemClient.getIdentityStatus(f.id.toString())).thenReturn(expectedIdentity0);
        f.api.when(()->ProvinceSystemClient.postWarning(anyString(),anyString(),nullable(String.class),anyString(),nullable(String.class),anyString())).thenReturn(MirrorResult.success(false));
        run(cmd,f.console,"Ada","warning");assertEquals("fetched",f.cache.get(f.id).discordUserId);assertTrue(f.contains("DM skipped"));
        f.cache.clear(f.id);f.api.when(()->ProvinceSystemClient.getIdentityStatus(f.id.toString())).thenReturn(IdentityStatus.fail("down"));
        f.api.when(()->ProvinceSystemClient.postWarning(anyString(),anyString(),nullable(String.class),anyString(),nullable(String.class),anyString())).thenReturn(MirrorResult.fail("fail"));
        OfflinePlayer offline=mock(OfflinePlayer.class);when(offline.getUniqueId()).thenReturn(f.id);f.bukkit.when(()->Bukkit.getOfflinePlayer("old")).thenReturn(offline);
        run(cmd,f.console,"old","warning");assertTrue(f.contains("Warning store failed"));
    }
    @Test void webValidatesCommandsReloadsBothPluginKindsAndReportsStatus() {
        var cmd=new WebCommand(f.plugin,f.cache,f.gate);run(cmd,f.console,"status");assertTrue(f.contains("No permission"));f.permissions(f.console,true);
        run(cmd,f.console);run(cmd,f.console,"unknown");assertTrue(f.contains("Usage:"));assertTrue(f.contains("Unknown subcommand"));
        Cache.apiBaseUrl="";Cache.pluginKey="";f.api.when(ProvinceSystemClient::ping).thenReturn(SimpleResult.success());run(cmd,f.console,"status");assertTrue(f.contains("API reachable"));assertTrue(f.contains("writer=off"));
        Cache.apiBaseUrl="local";Cache.pluginKey="key";when(f.gate.isRpcAvailable()).thenReturn(true);
        for(String error:Arrays.asList(null,"down")){f.api.when(ProvinceSystemClient::ping).thenReturn(SimpleResult.fail(error));run(cmd,f.console,"status");assertTrue(f.contains(error==null?"unreachable":"down"));}
        run(cmd,f.console,"reload");verify(f.plugin).reloadConfig();assertTrue(f.contains("patreon:"));
        TFMCWeb plugin=mock(TFMCWeb.class);run(new WebCommand(plugin,f.cache,f.gate),f.console,"reload");verify(plugin).reloadLocalConfig();
    }
    @Test void webLookupAndUnlinkHandleOfflineUnknownFailuresAndGating() {
        var cmd=new WebCommand(f.plugin,f.cache,f.gate);f.permissions(f.console,true);
        for(String action:List.of("lookup","unlink")){
            run(cmd,f.console,action);assertTrue(f.contains("Usage:"));run(cmd,f.console,action," ");run(cmd,f.console,action,"missing");assertTrue(f.contains("Unknown player"));
        }
        f.api.when(()->ProvinceSystemClient.getIdentityStatus(f.id.toString())).thenReturn(IdentityStatus.fail("down"));run(cmd,f.console,"lookup","Ada");assertTrue(f.contains("live status: down"));
        var status=IdentityStatus.fromJson("{\"eligible\":true,\"linked\":true,\"grace_until\":\"later\",\"discord_username\":\"Discord\"}");
        f.api.when(()->ProvinceSystemClient.getIdentityStatus(f.id.toString())).thenReturn(status);run(cmd,f.console,"lookup","Ada");assertTrue(f.cache.isEligible(f.id));verify(f.gate).applyGate(f.player,true);
        f.api.when(()->ProvinceSystemClient.unlinkDiscord(f.id.toString())).thenReturn(SimpleResult.fail("down"));run(cmd,f.console,"unlink","Ada");assertTrue(f.contains("Unlink failed"));
        f.api.when(()->ProvinceSystemClient.unlinkDiscord(f.id.toString())).thenReturn(SimpleResult.success());run(cmd,f.console,"unlink","Ada");assertFalse(f.cache.isEligible(f.id));verify(f.gate).applyGate(f.player,false);
        OfflinePlayer offline=mock(OfflinePlayer.class);when(offline.getUniqueId()).thenReturn(f.id);f.bukkit.when(()->Bukkit.getOfflinePlayer("old")).thenReturn(offline);f.bukkit.when(()->Bukkit.getPlayer(f.id)).thenReturn(null);
        run(cmd,f.console,"unlink","old");verify(f.gate).applyGate(f.id,false);
        f.messages.clear();run(cmd,f.console,"lookup","old");
        assertTrue(f.messages.stream().anyMatch(message -> message.endsWith("Discord")));
    }
    @Test void webReconcileAndMetaSyncUseOnlineSnapshotAndSummarizeFailures() {
        var cmd=new WebCommand(f.plugin,f.cache,f.gate);f.permissions(f.console,true);Player failed=mock(Player.class);UUID failedId=UUID.randomUUID();when(failed.getUniqueId()).thenReturn(failedId);
        f.bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(f.player,failed));
        var expectedIdentity1=IdentityStatus.fromJson("{\"eligible\":true}");
        f.api.when(()->ProvinceSystemClient.getIdentityStatus(f.id.toString())).thenReturn(expectedIdentity1);
        f.api.when(()->ProvinceSystemClient.getIdentityStatus(failedId.toString())).thenReturn(IdentityStatus.fail("down"));
        run(cmd,f.console,"reconcile");assertTrue(f.contains("ok=1 fail=1"));assertTrue(f.cache.isEligible(f.id));
        try(var sync=mockStatic(PlayerMetaSyncService.class)){
            run(cmd,f.console,"syncmeta");sync.verify(PlayerMetaSyncService::pushAllOnlineAsync);
            run(cmd,f.console,"syncmeta"," ");run(cmd,f.console,"syncmeta","missing");assertTrue(f.contains("Unknown player"));
            run(cmd,f.console,"syncmeta","Ada");sync.verify(()->PlayerMetaSyncService.pushForPlayer(f.player));
            when(f.player.isOnline()).thenReturn(false);run(cmd,f.console,"syncmeta","Ada");assertTrue(f.contains("must be online"));
        }
    }
    @Test void webCompletionsFilterActionsAndNames() {
        var cmd=new WebCommand(f.plugin,f.cache,f.gate);assertTrue(cmd.onTabComplete(f.console,null,"web",new String[]{""}).isEmpty());f.permissions(f.console,true);
        assertEquals(List.of("status","syncmeta"),cmd.onTabComplete(f.console,null,"web",new String[]{"s"}));
        for(String action:List.of("lookup","unlink","syncmeta"))assertEquals(List.of("Ada"),cmd.onTabComplete(f.console,null,"web",new String[]{action,"A"}));
        assertTrue(cmd.onTabComplete(f.console,null,"web",new String[]{"status",""}).isEmpty());assertTrue(cmd.onTabComplete(f.console,null,"web",new String[0]).isEmpty());
    }
}
