package net.tfminecraft.tfmcweb.luckperms;

import java.util.UUID;
import java.util.function.Consumer;

import org.bukkit.plugin.Plugin;

import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.event.EventBus;
import net.luckperms.api.event.EventSubscription;
import net.luckperms.api.event.group.GroupDataRecalculateEvent;
import net.luckperms.api.event.user.UserDataRecalculateEvent;

/**
 * Reports LuckPerms permission recalculations: one player's, or a group's, which can change
 * any member's permissions. Loaded by name so LuckPerms stays optional.
 */
public final class LuckPermsRecalculateListener {

	private LuckPermsRecalculateListener() {}

	public static AutoCloseable subscribe(Plugin plugin, Consumer<UUID> onUser, Runnable onGroup) {
		EventBus events = LuckPermsProvider.get().getEventBus();
		EventSubscription<UserDataRecalculateEvent> users = events.subscribe(
			plugin,
			UserDataRecalculateEvent.class,
			event -> onUser.accept(event.getUser().getUniqueId())
		);
		EventSubscription<GroupDataRecalculateEvent> groups = events.subscribe(
			plugin,
			GroupDataRecalculateEvent.class,
			event -> onGroup.run()
		);
		return () -> {
			users.close();
			groups.close();
		};
	}
}
