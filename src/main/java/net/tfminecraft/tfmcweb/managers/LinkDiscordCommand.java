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
import net.tfminecraft.tfmcweb.utils.ExpiryFormat;

/**
 * /linkdiscord — start Discord account link (async HTTP).
 */
public final class LinkDiscordCommand implements CommandExecutor, TabCompleter {

	private final JavaPlugin plugin;
	private final LinkCache linkCache;
	private final DiscordGateService gateService;

	public LinkDiscordCommand(
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
		String name = player.getName();

		Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
			ProvinceSystemClient.CodeResult result = ProvinceSystemClient.startDiscordLink(uuid, name);
			Bukkit.getScheduler().runTask(plugin, () -> {
				if (!player.isOnline()) {
					return;
				}
				if (!result.ok) {
					ChatMessages.error(player, result.error != null ? result.error : "Link failed.");
					return;
				}
				if (result.alreadyLinked) {
					String dname = result.discordUsername;
					linkCache.putLinked(player.getUniqueId(), null, dname);
					gateService.applyGate(player, true);
					if (dname != null && !dname.isBlank()) {
						ChatMessages.info(
							player,
							"Already linked with Discord user " + ChatColor.AQUA + dname.trim()
						);
					} else {
						ChatMessages.info(player, "Already linked.");
					}
					return;
				}
				ChatMessages.sendCopyableCode(
					player,
					"Your Discord link code (click to copy):",
					result.code
				);
				ChatMessages.info(
					player,
					"In Discord, run " + ChatColor.AQUA + "/linkdiscord <code>"
						+ ChatColor.GRAY + " with that code in any channel on the server."
				);
				String expiry = ExpiryFormat.relativeLabel(result.expiresAt);
				if (expiry != null) {
					ChatMessages.info(player, expiry);
				}
			});
		});
		return true;
	}
}
