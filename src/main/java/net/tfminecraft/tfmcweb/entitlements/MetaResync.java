package net.tfminecraft.tfmcweb.entitlements;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Pushes a player's web entitlements again after LuckPerms recalculates their permissions,
 * so rank changes made while they are online reach the website without a rejoin.
 * Recalculations within one second share one check, and unchanged entitlements are not resent:
 * LuckPerms recalculates every online player on each periodic storage sync.
 */
public final class MetaResync {

	static final long DELAY_TICKS = 20L;

	@FunctionalInterface
	public interface Subscriber {
		AutoCloseable subscribe(Plugin plugin, Consumer<UUID> onChange);
	}

	private final JavaPlugin plugin;
	private final Subscriber subscriber;
	private final Set<UUID> pending = ConcurrentHashMap.newKeySet();
	private AutoCloseable subscription;

	public MetaResync(JavaPlugin plugin) {
		this(plugin, MetaResync::subscribeLuckPerms);
	}

	MetaResync(JavaPlugin plugin, Subscriber subscriber) {
		this.plugin = plugin;
		this.subscriber = subscriber;
	}

	/** Loads the LuckPerms listener by name so this class does not reference the API. */
	static AutoCloseable subscribeLuckPerms(Plugin plugin, Consumer<UUID> onChange) {
		try {
			Class<?> type = Class.forName("net.tfminecraft.tfmcweb.luckperms.LuckPermsRecalculateListener");
			return (AutoCloseable) type.getMethod("subscribe", Plugin.class, Consumer.class)
				.invoke(null, plugin, onChange);
		} catch (ReflectiveOperationException | NoClassDefFoundError e) {
			throw new IllegalStateException("LuckPerms API is not available", e);
		}
	}

	public void start() {
		if (subscription != null || !luckPermsPresent()) {
			return;
		}
		try {
			subscription = subscriber.subscribe(plugin, this::request);
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

	/** Safe from any thread; LuckPerms may fire recalculations asynchronously. */
	void request(UUID playerUuid) {
		if (playerUuid == null || !plugin.isEnabled() || !pending.add(playerUuid)) {
			return;
		}
		Bukkit.getScheduler().runTaskLater(plugin, () -> {
			pending.remove(playerUuid);
			Player online = Bukkit.getPlayer(playerUuid);
			if (online != null && online.isOnline()) {
				PlayerMetaSyncService.pushIfChanged(online);
			}
		}, DELAY_TICKS);
	}

	private static boolean luckPermsPresent() {
		if (Bukkit.getPluginManager() == null) {
			return false;
		}
		Plugin luckPerms = Bukkit.getPluginManager().getPlugin("LuckPerms");
		return luckPerms != null && luckPerms.isEnabled();
	}
}
