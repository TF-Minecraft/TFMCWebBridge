package net.tfminecraft.tfmcweb.entitlements;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Pushes web entitlements again after LuckPerms recalculates a player's or a group's
 * permissions, so rank changes made while players are online reach the website without a rejoin.
 * Recalculations within one second share one check, and unchanged entitlements are not resent:
 * LuckPerms recalculates every online player and group on each periodic storage sync.
 */
public final class MetaResync {

	static final long DELAY_TICKS = 20L;

	@FunctionalInterface
	public interface Subscriber {
		AutoCloseable subscribe(Plugin plugin, Consumer<UUID> onUser, Runnable onGroup);
	}

	private final JavaPlugin plugin;
	private final Subscriber subscriber;
	private final Set<UUID> pending = ConcurrentHashMap.newKeySet();
	private final AtomicBoolean allPending = new AtomicBoolean();
	private AutoCloseable subscription;

	public MetaResync(JavaPlugin plugin) {
		this(plugin, MetaResync::subscribeLuckPerms);
	}

	MetaResync(JavaPlugin plugin, Subscriber subscriber) {
		this.plugin = plugin;
		this.subscriber = subscriber;
	}

	/** Loads the LuckPerms listener by name so this class does not reference the API. */
	static AutoCloseable subscribeLuckPerms(Plugin plugin, Consumer<UUID> onUser, Runnable onGroup) {
		try {
			Class<?> type = Class.forName("net.tfminecraft.tfmcweb.luckperms.LuckPermsRecalculateListener");
			return (AutoCloseable) type.getMethod("subscribe", Plugin.class, Consumer.class, Runnable.class)
				.invoke(null, plugin, onUser, onGroup);
		} catch (ReflectiveOperationException | NoClassDefFoundError e) {
			throw new IllegalStateException("LuckPerms API is not available", e);
		}
	}

	public void start() {
		if (subscription != null || !luckPermsPresent()) {
			return;
		}
		try {
			subscription = subscriber.subscribe(plugin, this::request, this::requestAll);
		} catch (RuntimeException e) {
			plugin.getLogger().log(
				Level.WARNING,
				"[player-meta] LuckPerms is unavailable; rank changes reach the website on next join",
				e
			);
		}
	}

	public void stop() {
		pending.clear();
		allPending.set(false);
		if (subscription == null) {
			return;
		}
		try {
			subscription.close();
		} catch (Exception e) {
			plugin.getLogger().log(Level.WARNING, "[player-meta] could not unsubscribe from LuckPerms", e);
		}
		subscription = null;
	}

	/** Safe from any thread; LuckPerms fires recalculations asynchronously. */
	void request(UUID playerUuid) {
		if (playerUuid == null || !plugin.isEnabled() || !pending.add(playerUuid)) {
			return;
		}
		if (!schedule(() -> {
			pending.remove(playerUuid);
			Player online = Bukkit.getPlayer(playerUuid);
			if (online != null && online.isOnline()) {
				PlayerMetaSyncService.pushIfChanged(online);
			}
		})) {
			pending.remove(playerUuid);
		}
	}

	/** Checks every online player; a group change can reach any member. */
	void requestAll() {
		if (!plugin.isEnabled() || !allPending.compareAndSet(false, true)) {
			return;
		}
		if (!schedule(() -> {
			allPending.set(false);
			for (Player online : Bukkit.getOnlinePlayers()) {
				PlayerMetaSyncService.pushIfChanged(online);
			}
		})) {
			allPending.set(false);
		}
	}

	/** False when the plugin was disabled between the check and scheduling. */
	private boolean schedule(Runnable task) {
		try {
			Bukkit.getScheduler().runTaskLater(plugin, task, DELAY_TICKS);
			return true;
		} catch (IllegalPluginAccessException e) {
			return false;
		}
	}

	private static boolean luckPermsPresent() {
		if (Bukkit.getPluginManager() == null) {
			return false;
		}
		Plugin luckPerms = Bukkit.getPluginManager().getPlugin("LuckPerms");
		return luckPerms != null && luckPerms.isEnabled();
	}
}
