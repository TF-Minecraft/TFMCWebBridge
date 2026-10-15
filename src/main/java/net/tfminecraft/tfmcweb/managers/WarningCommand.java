package net.tfminecraft.tfmcweb.managers;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import net.tfminecraft.tfmcweb.api.ProvinceSystemClient;
import net.tfminecraft.tfmcweb.api.ProvinceSystemClient.IdentityStatus;
import net.tfminecraft.tfmcweb.api.ProvinceSystemClient.MirrorResult;
import net.tfminecraft.tfmcweb.cache.LinkCache;

/**
 * /warning &lt;player&gt; &lt;reason…&gt; — in-game chat + web store + Discord DM via bot.
 */
public final class WarningCommand implements CommandExecutor, TabCompleter {

	private final JavaPlugin plugin;
	private final LinkCache linkCache;

	public WarningCommand(JavaPlugin plugin, LinkCache linkCache) {
		this.plugin = plugin;
		this.linkCache = linkCache;
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		if (args.length != 1 || !sender.hasPermission("tfmcweb.warning")) return List.of();
		String prefix = args[0].toLowerCase(Locale.ROOT);
		return Bukkit.getOnlinePlayers().stream().map(Player::getName)
			.filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
	}

	// Keep the existing legacy text representation, formatting, and exact-string comparisons.
	@SuppressWarnings("deprecation")
	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (!sender.hasPermission("tfmcweb.warning")) {
			sender.sendMessage(ChatColor.RED + "No permission.");
			return true;
		}
		if (args.length < 2) {
			sender.sendMessage(ChatColor.YELLOW + "Usage: /warning <player> <reason>");
			return true;
		}

		OfflinePlayer target = resolvePlayer(args[0]);
		if (target == null || target.getUniqueId() == null) {
			sender.sendMessage(ChatColor.RED + "Unknown player.");
			return true;
		}

		String reason = Arrays.stream(args).skip(1).collect(Collectors.joining(" ")).trim();
		if (reason.isEmpty()) {
			sender.sendMessage(ChatColor.RED + "Reason is required.");
			return true;
		}

		UUID uuid = target.getUniqueId();
		String mcName = target.getName() != null ? target.getName() : args[0];
		Player online = target.isOnline() ? target.getPlayer() : null;
		if (online != null && online.isOnline()) {
			online.sendMessage(
				ChatColor.GOLD + "[Warning] " + ChatColor.YELLOW + reason
			);
		}

		String staffUuid = sender instanceof Player
			? ((Player) sender).getUniqueId().toString()
			: null;
		String staffName = sender.getName();

		sender.sendMessage(ChatColor.GREEN + "[TFMCWeb] Warning issued to "
			+ ChatColor.AQUA + mcName + ChatColor.GREEN + ".");

		Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
			String discordId = resolveDiscordId(uuid);
			MirrorResult result = ProvinceSystemClient.postWarning(
				uuid.toString(),
				reason,
				staffUuid,
				staffName,
				discordId,
				mcName
			);
			Bukkit.getScheduler().runTask(plugin, () -> {
				if (!result.ok) {
					sender.sendMessage(ChatColor.RED + "[TFMCWeb] Warning store failed: "
						+ result.error);
					return;
				}
				if (!result.mirrored) {
					plugin.getLogger().info(
						"[warning] stored for " + mcName
							+ " but no Discord link — skip DM"
					);
					sender.sendMessage(ChatColor.YELLOW
						+ "[TFMCWeb] Stored; no Discord link (DM skipped).");
				}
			});
		});
		return true;
	}

	private String resolveDiscordId(UUID uuid) {
		LinkCache.Entry cached = linkCache.get(uuid);
		if (cached != null && cached.discordUserId != null && !cached.discordUserId.isBlank()) {
			return cached.discordUserId;
		}
		IdentityStatus status = ProvinceSystemClient.getIdentityStatus(uuid.toString());
		if (status.ok && status.discordUserId != null && !status.discordUserId.isBlank()) {
			linkCache.putFromStatus(uuid, status);
			return status.discordUserId;
		}
		return null;
	}

	private static OfflinePlayer resolvePlayer(String name) {
		if (name == null || name.isBlank()) {
			return null;
		}
		Player online = Bukkit.getPlayerExact(name);
		if (online != null) {
			return online;
		}
		@SuppressWarnings("deprecation")
		OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
		return offline;
	}
}
