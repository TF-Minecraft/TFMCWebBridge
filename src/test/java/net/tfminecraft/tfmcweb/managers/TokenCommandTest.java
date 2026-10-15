package net.tfminecraft.tfmcweb.managers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.*;
import net.tfminecraft.tfmcweb.Cache;
import net.tfminecraft.tfmcweb.api.ProvinceSystemClient;
import net.tfminecraft.tfmcweb.api.ProvinceSystemClient.*;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.*;

class TokenCommandTest {
    CommandFixture f;TokenCommand command;
    @BeforeEach void setup() throws Exception {f=new CommandFixture();command=new TokenCommand(f.plugin);Cache.tokenCooldownDefaultDays=0;Cache.tokenCooldownGroups=List.of();Cache.tokenEnabledScopes=List.of("skin","drink","profile","skin_staff");}
    @AfterEach void cleanup() throws Exception {f.close();}
    void run(CommandSender sender,String...args){assertTrue(command.onCommand(sender,null,"token",args));}
    void expect(CommandSender sender,String message,String...args){f.messages.clear();run(sender,args);assertTrue(f.contains(message),f.messages.toString());}
    @Test void usageAndPermissionChecks() {
        expect(f.player,"permission");expect(f.console,"permission");expect(f.player,"permission","create","skin");
        expect(f.console,"Players only","create","skin");expect(f.player,"Usage:","unknown");expect(f.console,"Usage:","unknown");
        expect(f.player,"permission","resetcooldowns","Ada");expect(f.console,"permission","resetcooldowns","Ada");
        f.permissions(f.player,true);f.permissions(f.console,true);expect(f.player,"/token create");expect(f.console,"resetcooldowns");
        expect(f.player,"Usage:","create");
        for(String[] args:List.of(new String[]{"create","profile","extra"},new String[]{"create","drink","extra"},new String[]{"create","skin","bad"},new String[]{"create","bad"}))expect(f.player,"Usage:",args);
        when(f.player.hasPermission("tfmcweb.token.create")).thenReturn(false);
        for(String kind:List.of("profile","drink","skin"))expect(f.player,"permission","create",kind);
        when(f.player.hasPermission("tfmcweb.token.create")).thenReturn(true);when(f.player.hasPermission("tfmcweb.token.create.staff")).thenReturn(false);
        expect(f.player,"staff skins token","create","skin","staff");
        Cache.tokenEnabledScopes=List.of();expect(f.player,"disabled","create","skin");expect(f.player,"disabled");
        Cache.tokenEnabledScopes=List.of("profile");expect(f.player,"not available","create","skin");
    }
    @Test void createsAllFourKindsAndHandlesErrorsExpiryAndDisconnect() {
        f.permissions(f.player,true);
        for(String kind:List.of("skin","drink","profile","skin_staff")) {
            f.api.when(()->ProvinceSystemClient.issueFeatureCode(f.id.toString(),kind)).thenReturn(FeatureCodeResult.success("ABC",Instant.now().plusSeconds(4000).toString(),kind));
            String[] args=kind.equals("skin_staff")?new String[]{"create","skin","staff"}:new String[]{"create",kind};
            expect(f.player,kind.equals("skin_staff")?"curated pack":kind.equals("profile")?"/profile":kind.equals("drink")?"/drinks":"skins website",args);
            assertTrue(f.contains("Expires in"));
        }
        for(String error:Arrays.asList(null,"backend failed")){
            f.api.when(()->ProvinceSystemClient.issueFeatureCode(f.id.toString(),"profile")).thenReturn(FeatureCodeResult.fail(error));
            expect(f.player,error==null?"Could not create token":"backend failed","create","profile");
        }
        f.messages.clear();when(f.player.isOnline()).thenReturn(false);run(f.player,"create","profile");assertTrue(f.messages.isEmpty());when(f.player.isOnline()).thenReturn(true);
        Cache.tokenCooldownDefaultDays=-1;expect(f.player,"rank cannot","create","skin");
        when(f.player.isOnline()).thenReturn(false);f.messages.clear();run(f.player,"create","drink");assertTrue(f.messages.isEmpty());
    }
    @Test void resetChecksTargetAndReportsBothSuccessAndFailure() {
        for(CommandSender sender:List.of(f.player,f.console)){
            f.permissions(sender,true);expect(sender,"Usage:","resetcooldowns");expect(sender,"Unknown player","resetcooldowns"," ");
            expect(sender,"Unknown player","resetcooldowns","missing");
            OfflinePlayer offline=mock(OfflinePlayer.class);f.bukkit.when(()->Bukkit.getOfflinePlayer("old")).thenReturn(offline);
            expect(sender,"Unknown player","resetcooldowns","old");when(offline.getUniqueId()).thenReturn(f.id);expect(sender,"Unknown player","resetcooldowns","old");
            when(offline.hasPlayedBefore()).thenReturn(true);
            String staff=sender==f.player?f.id.toString():null;
            f.api.when(()->ProvinceSystemClient.resetCosmeticMintCooldowns(f.id.toString(),staff)).thenReturn(SimpleResult.success());
            expect(sender,"Reset shared","resetcooldowns","old");expect(sender,"for Ada","resetcooldowns","Ada");
            for(String error:Arrays.asList(null,"denied")){
                f.api.when(()->ProvinceSystemClient.resetCosmeticMintCooldowns(f.id.toString(),staff)).thenReturn(SimpleResult.fail(error));
                expect(sender,error==null?"Could not reset":"denied","resetcooldowns","Ada");
            }
        }
        when(f.player.isOnline()).thenReturn(false);f.messages.clear();run(f.player,"resetcooldowns","Ada");assertTrue(f.messages.isEmpty());
        f.api.when(()->ProvinceSystemClient.resetCosmeticMintCooldowns(f.id.toString(),f.id.toString())).thenReturn(SimpleResult.success());run(f.player,"resetcooldowns","Ada");assertTrue(f.messages.isEmpty());
    }
    @Test void ordinaryCreatePermissionDoesNotHideTheEnabledStaffSkinRoute() {
        f.permissions(f.player,true);
        Cache.tokenEnabledScopes=List.of("skin_staff");
        when(f.player.hasPermission("tfmcweb.token.create")).thenReturn(false);
        assertEquals(List.of("skin"),command.onTabComplete(f.player,null,"token",new String[]{"create",""}));
        when(f.player.hasPermission("tfmcweb.token.create")).thenReturn(true);
        assertEquals(List.of("skin"),command.onTabComplete(f.player,null,"token",new String[]{"create",""}));
        assertEquals(List.of("skin"),command.onTabComplete(f.player,null,"token",new String[]{"CREATE","S"}));
        assertEquals(List.of("staff"),command.onTabComplete(f.player,null,"token",new String[]{"create","skin",""}));
        when(f.player.hasPermission("tfmcweb.token.create.staff")).thenReturn(false);
        assertTrue(command.onTabComplete(f.player,null,"token",new String[]{"create",""}).isEmpty());
        Cache.tokenEnabledScopes=List.of("skin","skin_staff");
        when(f.player.hasPermission("tfmcweb.token.create.staff")).thenReturn(true);
        assertEquals(List.of("skin"),command.onTabComplete(f.player,null,"token",new String[]{"create",""}));
    }

    @Test void completionsRespectPermissionsEnabledScopesAndPrefix() {
        assertTrue(command.onTabComplete(f.player,null,"token",new String[]{""}).isEmpty());f.permissions(f.player,true);
        assertEquals(List.of("create","resetcooldowns"),command.onTabComplete(f.player,null,"token",new String[]{""}));
        assertEquals(List.of("Ada"),command.onTabComplete(f.player,null,"token",new String[]{"resetcooldowns","a"}));
        assertEquals(List.of("skin","drink","profile"),command.onTabComplete(f.player,null,"token",new String[]{"create",""}));
        assertEquals(List.of("staff"),command.onTabComplete(f.player,null,"token",new String[]{"create","skin","s"}));
        assertTrue(command.onTabComplete(f.player,null,"token",new String[]{"create","skin","x"}).isEmpty());
        assertTrue(command.onTabComplete(f.player,null,"token",new String[0]).isEmpty());
        when(f.player.hasPermission("tfmcweb.token.create")).thenReturn(false);assertEquals(List.of("skin"),command.onTabComplete(f.player,null,"token",new String[]{"create",""}));
        Cache.tokenEnabledScopes=List.of();assertTrue(command.onTabComplete(f.player,null,"token",new String[]{"create",""}).isEmpty());
    }
}
