package net.tfminecraft.tfmcweb.managers;

import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import net.tfminecraft.tfmcweb.api.ProvinceSystemClient;
import net.tfminecraft.tfmcweb.cache.LinkCache;
import net.tfminecraft.tfmcweb.gate.DiscordGateService;
import net.tfminecraft.tfmcweb.utils.ChatMessages;

/**
 * /unlinkdiscord — remove Discord link for this Minecraft UUID (async HTTP).
 */
public final class UnlinkDiscordCommand implements CommandExecutor, TabCompleter {

	private final JavaPlugin plugin;
	private final LinkCache linkCache;
	private final DiscordGateService gateService;

	public UnlinkDiscordCommand(
		JavaPlugin plugin,
		LinkCache linkCache,
		DiscordGateService gateService
	) {
		this.plugin = plugin;
		this.linkCache = linkCache;
		this.gateService = gateService;
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		return List.of();
	}

	// Keep the existing legacy text representation, formatting, and exact-string comparisons.
	@SuppressWarnings("deprecation")
	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (!(sender instanceof Player)) {
			sender.sendMessage(ChatColor.RED + "Players only.");
			return true;
		}

		Player player = (Player) sender;
		String uuid = player.getUniqueId().toString();

		Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
			ProvinceSystemClient.SimpleResult result = ProvinceSystemClient.unlinkDiscord(uuid);
			Bukkit.getScheduler().runTask(plugin, () -> {
				if (!player.isOnline()) {
					return;
				}
				if (!result.ok) {
					ChatMessages.error(
						player,
						result.error != null ? result.error : "Unlink failed."
					);
					return;
				}
				linkCache.putUnlinked(player.getUniqueId());
				gateService.applyGate(player, false);
				ChatMessages.info(
					player,
					"Discord unlinked. Use " + ChatColor.AQUA + "/linkdiscord"
						+ ChatColor.GRAY + " to link again."
				);
			});
		});
		return true;
	}
}
