package net.tfminecraft.tfmcweb.luckperms;

import java.util.UUID;
import java.util.function.Consumer;

import org.bukkit.plugin.Plugin;

import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.event.user.UserDataRecalculateEvent;

/**
 * Reports players whose LuckPerms permissions were recalculated.
 * Loaded by name so LuckPerms stays optional.
 */
public final class LuckPermsRecalculateListener {

	private LuckPermsRecalculateListener() {}

	public static AutoCloseable subscribe(Plugin plugin, Consumer<UUID> onChange) {
		return LuckPermsProvider.get().getEventBus().subscribe(
			plugin,
			UserDataRecalculateEvent.class,
			event -> onChange.accept(event.getUser().getUniqueId())
		);
	}
}
