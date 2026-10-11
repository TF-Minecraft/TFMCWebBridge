package net.tfminecraft.tfmcweb.entitlements;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.event.EventBus;
import net.luckperms.api.event.EventSubscription;
import net.luckperms.api.event.group.GroupDataRecalculateEvent;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import net.luckperms.api.model.user.User;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;

class MetaResyncTest {
    final JavaPlugin plugin=mock(JavaPlugin.class);
    final Logger logger=mock(Logger.class);
    final PluginManager plugins=mock(PluginManager.class);
    final Plugin luckPerms=mock(Plugin.class);
    final BukkitScheduler scheduler=mock(BukkitScheduler.class);
    final Player player=mock(Player.class);
    final UUID id=UUID.randomUUID();
    final List<Runnable> later=new ArrayList<>();
    MockedStatic<Bukkit> bukkit;MockedStatic<PlayerMetaSyncService> sync;

    @BeforeEach void setup() {
        when(plugin.getLogger()).thenReturn(logger);when(plugin.isEnabled()).thenReturn(true);
        when(plugins.getPlugin("LuckPerms")).thenReturn(luckPerms);when(luckPerms.isEnabled()).thenReturn(true);
        when(player.isOnline()).thenReturn(true);
        doAnswer(c->{later.add(c.getArgument(1,Runnable.class));return null;}).when(scheduler).runTaskLater(eq(plugin),any(Runnable.class),eq(MetaResync.DELAY_TICKS));
        bukkit=mockStatic(Bukkit.class);sync=mockStatic(PlayerMetaSyncService.class);
        bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);bukkit.when(()->Bukkit.getPlayer(id)).thenReturn(player);
    }
    @AfterEach void cleanup() {sync.close();bukkit.close();}

    @Test void startSkipsWhenLuckPermsIsMissingOrDisabled() {
        MetaResync.Subscriber subscriber=mock(MetaResync.Subscriber.class);var resync=new MetaResync(plugin,subscriber);
        bukkit.when(Bukkit::getPluginManager).thenReturn(null);resync.start();
        bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);when(plugins.getPlugin("LuckPerms")).thenReturn(null);resync.start();
        when(plugins.getPlugin("LuckPerms")).thenReturn(luckPerms);when(luckPerms.isEnabled()).thenReturn(false);resync.start();
        verifyNoInteractions(subscriber);resync.stop();
    }

    @Test void recalculationsPushOnlinePlayersOncePerSecond() throws Exception {
        List<Consumer<UUID>> handlers=new ArrayList<>();AutoCloseable subscription=mock(AutoCloseable.class);
        var resync=new MetaResync(plugin,(p,onUser,onGroup)->{assertSame(plugin,p);handlers.add(onUser);return subscription;});
        resync.start();resync.start();assertEquals(1,handlers.size());Consumer<UUID> handler=handlers.getFirst();

        handler.accept(null);handler.accept(id);handler.accept(id);assertEquals(1,later.size());
        later.removeFirst().run();sync.verify(()->PlayerMetaSyncService.pushIfChanged(player));

        handler.accept(id);assertEquals(1,later.size());when(player.isOnline()).thenReturn(false);later.removeFirst().run();
        handler.accept(id);bukkit.when(()->Bukkit.getPlayer(id)).thenReturn(null);later.removeFirst().run();
        sync.verify(()->PlayerMetaSyncService.pushIfChanged(player),times(1));

        when(plugin.isEnabled()).thenReturn(false);handler.accept(id);assertTrue(later.isEmpty());
        when(plugin.isEnabled()).thenReturn(true);handler.accept(id);resync.stop();verify(subscription).close();handler.accept(id);assertEquals(2,later.size());
        resync.stop();verify(subscription,times(1)).close();
    }

    @Test void subscribeAndUnsubscribeFailuresAreLogged() throws Exception {
        new MetaResync(plugin,(p,onUser,onGroup)->{throw new IllegalStateException("no api");}).start();
        verify(logger).log(eq(Level.WARNING),contains("next join"),any(IllegalStateException.class));
        AutoCloseable subscription=mock(AutoCloseable.class);doThrow(new Exception("closed")).when(subscription).close();
        var resync=new MetaResync(plugin,(p,onUser,onGroup)->subscription);resync.start();resync.stop();resync.stop();
        verify(logger).log(eq(Level.WARNING),contains("unsubscribe"),any(Exception.class));verify(subscription,times(1)).close();
    }

    @Test @SuppressWarnings("unchecked") void luckPermsListenerForwardsUserAndGroupRecalculations() throws Exception {
        LuckPerms api=mock(LuckPerms.class);EventBus events=mock(EventBus.class);when(api.getEventBus()).thenReturn(events);
        EventSubscription<UserDataRecalculateEvent> userSub=mock(EventSubscription.class);EventSubscription<GroupDataRecalculateEvent> groupSub=mock(EventSubscription.class);
        List<Consumer<? super UserDataRecalculateEvent>> userHandlers=new ArrayList<>();List<Consumer<? super GroupDataRecalculateEvent>> groupHandlers=new ArrayList<>();
        when(events.subscribe(eq(plugin),eq(UserDataRecalculateEvent.class),any())).thenAnswer(c->{userHandlers.add(c.getArgument(2));return userSub;});
        when(events.subscribe(eq(plugin),eq(GroupDataRecalculateEvent.class),any())).thenAnswer(c->{groupHandlers.add(c.getArgument(2));return groupSub;});
        List<UUID> changed=new ArrayList<>();int[] groups={0};
        try(MockedStatic<LuckPermsProvider> provider=mockStatic(LuckPermsProvider.class)){
            provider.when(LuckPermsProvider::get).thenReturn(api);
            AutoCloseable subscription=MetaResync.subscribeLuckPerms(plugin,changed::add,()->groups[0]++);
            User user=mock(User.class);when(user.getUniqueId()).thenReturn(id);UserDataRecalculateEvent event=mock(UserDataRecalculateEvent.class);when(event.getUser()).thenReturn(user);
            userHandlers.getFirst().accept(event);groupHandlers.getFirst().accept(mock(GroupDataRecalculateEvent.class));
            assertEquals(List.of(id),changed);assertEquals(1,groups[0]);
            subscription.close();verify(userSub).close();verify(groupSub).close();
            provider.when(LuckPermsProvider::get).thenThrow(new IllegalStateException("not loaded"));
            assertThrows(IllegalStateException.class,()->MetaResync.subscribeLuckPerms(plugin,changed::add,()->{}));
        }
    }

    @Test void groupRecalculationsCheckEveryOnlinePlayerOncePerSecond() {
        List<Runnable> groupHandlers=new ArrayList<>();
        var resync=new MetaResync(plugin,(p,onUser,onGroup)->{groupHandlers.add(onGroup);return mock(AutoCloseable.class);});resync.start();
        Player other=mock(Player.class);bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player,other));
        groupHandlers.getFirst().run();groupHandlers.getFirst().run();assertEquals(1,later.size());later.removeFirst().run();
        sync.verify(()->PlayerMetaSyncService.pushIfChanged(player));sync.verify(()->PlayerMetaSyncService.pushIfChanged(other));
        groupHandlers.getFirst().run();assertEquals(1,later.size());later.clear();
        resync.stop();when(plugin.isEnabled()).thenReturn(false);groupHandlers.getFirst().run();assertTrue(later.isEmpty());
    }

    @Test void schedulingRejectedDuringShutdownLeavesNothingPending() {
        var resync=new MetaResync(plugin,(p,onUser,onGroup)->mock(AutoCloseable.class));
        doThrow(new IllegalPluginAccessException("disabled")).when(scheduler).runTaskLater(eq(plugin),any(Runnable.class),eq(MetaResync.DELAY_TICKS));
        resync.request(id);resync.requestAll();
        doAnswer(c->{later.add(c.getArgument(1,Runnable.class));return null;}).when(scheduler).runTaskLater(eq(plugin),any(Runnable.class),eq(MetaResync.DELAY_TICKS));
        resync.request(id);resync.requestAll();assertEquals(2,later.size());
    }

    @Test void defaultConstructorUsesLuckPermsSubscriber() {
        new MetaResync(plugin).start();
        verify(logger).log(eq(Level.WARNING),contains("next join"),any(IllegalStateException.class));
    }
}
