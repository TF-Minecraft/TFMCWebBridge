package net.tfminecraft.tfmcweb.managers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import net.tfminecraft.tfmcweb.Cache;
import net.tfminecraft.tfmcweb.api.ProvinceSystemClient;
import net.tfminecraft.tfmcweb.api.ProvinceSystemClient.FeatureCodeResult;
import net.tfminecraft.tfmcweb.api.ProvinceSystemClient.SimpleResult;
import net.tfminecraft.tfmcweb.utils.ChatMessages;
import net.tfminecraft.tfmcweb.utils.ExpiryFormat;

/**
 * /token create … — scoped feature codes.
 * /token resetcooldowns &lt;player&gt; — staff clear of shared skin+drink cooldown.
 */
public final class TokenCommand implements CommandExecutor, TabCompleter {

	private static final String PERM_CREATE = "tfmcweb.token.create";
	private static final String PERM_CREATE_STAFF = "tfmcweb.token.create.staff";
	private static final String PERM_RESET = "tfmcweb.token.resetcooldowns";
	private static final String USAGE_RESET = "/token resetcooldowns <player>";

	private final JavaPlugin plugin;

	public TokenCommand(JavaPlugin plugin) {
		this.plugin = plugin;
	}

	private static String usageCreate() {
		List<String> parts = new ArrayList<>();
		if (Cache.isTokenScopeEnabled("skin")) {
			parts.add("skin");
		}
		if (Cache.isTokenScopeEnabled("drink")) {
			parts.add("drink");
		}
		if (Cache.isTokenScopeEnabled("profile")) {
			parts.add("profile");
		}
		if (Cache.isTokenScopeEnabled("skin_staff")) {
			parts.add("skin staff");
		}
		if (parts.isEmpty()) {
			return "/token create (disabled on this server)";
		}
		return "/token create <" + String.join("|", parts) + ">";
	}

	private static boolean rejectIfScopeDisabled(Player player, String apiScope) {
		if (Cache.isTokenScopeEnabled(apiScope)) {
			return false;
		}
		if (Cache.tokenEnabledScopes.isEmpty()) {
			ChatMessages.error(player, "Token minting is disabled on this server.");
		} else {
			ChatMessages.error(
				player,
				"This token type is not available on this server. "
					+ "Usage: " + usageCreate()
			);
		}
		return true;
	}

	// Keep the existing legacy text representation, formatting, and exact-string comparisons.
	@SuppressWarnings("deprecation")
	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (args.length == 0) {
			sendUsage(sender);
			return true;
		}

		String sub = args[0].trim().toLowerCase(Locale.ROOT);
		if ("resetcooldowns".equals(sub)) {
			return handleResetCooldowns(sender, args);
		}
		if ("create".equals(sub)) {
			return handleCreate(sender, args);
		}

		if (sender instanceof Player) {
			ChatMessages.error((Player) sender, "Usage: " + usageCreate() + " or " + USAGE_RESET);
		} else {
			sender.sendMessage(ChatColor.RED + "Usage: " + USAGE_RESET);
		}
		return true;
	}

	// Keep the existing legacy text representation, formatting, and exact-string comparisons.
	@SuppressWarnings("deprecation")
	private void sendUsage(CommandSender sender) {
		boolean canCreate = sender.hasPermission(PERM_CREATE) || sender.hasPermission(PERM_CREATE_STAFF);
		boolean canReset = sender.hasPermission(PERM_RESET);
		if (sender instanceof Player) {
			Player player = (Player) sender;
			if (canCreate) {
				ChatMessages.info(player, "Usage: " + ChatColor.AQUA + usageCreate());
			}
			if (canReset) {
				ChatMessages.info(player, "Usage: " + ChatColor.AQUA + USAGE_RESET);
			}
			if (!canCreate && !canReset) {
				ChatMessages.error(player, "You do not have permission to use /token.");
			}
			return;
		}
		if (canReset) {
			sender.sendMessage(ChatColor.YELLOW + "Usage: " + USAGE_RESET);
		} else {
			sender.sendMessage(ChatColor.RED + "No permission.");
		}
	}

	// Keep the existing legacy text representation, formatting, and exact-string comparisons.
	@SuppressWarnings("deprecation")
	private boolean handleResetCooldowns(CommandSender sender, String[] args) {
		if (!sender.hasPermission(PERM_RESET)) {
			if (sender instanceof Player) {
				ChatMessages.error((Player) sender, "You do not have permission to reset cooldowns.");
			} else {
				sender.sendMessage(ChatColor.RED + "No permission.");
			}
			return true;
		}
		if (args.length != 2) {
			if (sender instanceof Player) {
				ChatMessages.error((Player) sender, "Usage: " + USAGE_RESET);
			} else {
				sender.sendMessage(ChatColor.YELLOW + "Usage: " + USAGE_RESET);
			}
			return true;
		}

		OfflinePlayer target = resolvePlayer(args[1]);
		if (target == null || target.getUniqueId() == null
			|| (!target.hasPlayedBefore() && !target.isOnline())) {
			if (sender instanceof Player) {
				ChatMessages.error((Player) sender, "Unknown player.");
			} else {
				sender.sendMessage(ChatColor.RED + "Unknown player.");
			}
			return true;
		}

		String uuid = target.getUniqueId().toString();
		String name = target.getName() != null ? target.getName() : uuid;
		String staffUuid = sender instanceof Player
			? ((Player) sender).getUniqueId().toString()
			: null;

		Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
			SimpleResult result = ProvinceSystemClient.resetCosmeticMintCooldowns(uuid, staffUuid);
			Bukkit.getScheduler().runTask(plugin, () -> {
				if (!result.ok) {
					String err = result.error != null ? result.error : "Could not reset cooldowns.";
					if (sender instanceof Player) {
						Player p = (Player) sender;
						if (p.isOnline()) {
							ChatMessages.error(p, err);
						}
					} else {
						sender.sendMessage(ChatColor.RED + err);
					}
					return;
				}
				String msg = "Reset shared skin/drink mint cooldown for " + name + ".";
				if (sender instanceof Player) {
					Player p = (Player) sender;
					if (p.isOnline()) {
						ChatMessages.info(p, msg);
					}
				} else {
					sender.sendMessage(ChatColor.GREEN + msg);
				}
			});
		});
		return true;
	}

	// Keep the existing legacy text representation, formatting, and exact-string comparisons.
	@SuppressWarnings("deprecation")
	private boolean handleCreate(CommandSender sender, String[] args) {
		if (!(sender instanceof Player)) {
			sender.sendMessage(ChatColor.RED + "Players only.");
			return true;
		}
		Player player = (Player) sender;
		boolean canCreate = player.hasPermission(PERM_CREATE);
		boolean canStaff = player.hasPermission(PERM_CREATE_STAFF);
		if (!canCreate && !canStaff) {
			ChatMessages.error(player, "You do not have permission to create a token.");
			return true;
		}
		if (args.length < 2) {
			ChatMessages.error(player, "Usage: " + usageCreate());
			return true;
		}

		String kind = args[1].trim().toLowerCase(Locale.ROOT);
		String apiScope;
		boolean staffMint = false;

		if ("profile".equals(kind)) {
			if (args.length != 2) {
				ChatMessages.error(player, "Usage: " + usageCreate());
				return true;
			}
			if (!canCreate) {
				ChatMessages.error(player, "You do not have permission to create a token.");
				return true;
			}
			apiScope = "profile";
		} else if ("drink".equals(kind)) {
			if (args.length != 2) {
				ChatMessages.error(player, "Usage: " + usageCreate());
				return true;
			}
			if (!canCreate) {
				ChatMessages.error(player, "You do not have permission to create a token.");
				return true;
			}
			apiScope = "drink";
		} else if ("skin".equals(kind)) {
			if (args.length == 2) {
				if (!canCreate) {
					ChatMessages.error(player, "You do not have permission to create a token.");
					return true;
				}
				apiScope = "skin";
			} else if (args.length == 3
				&& "staff".equalsIgnoreCase(args[2].trim())) {
				if (!canStaff) {
					ChatMessages.error(
						player,
						"You do not have permission to create a staff skins token."
					);
					return true;
				}
				apiScope = "skin_staff";
				staffMint = true;
			} else {
				ChatMessages.error(player, "Usage: " + usageCreate());
				return true;
			}
		} else {
			ChatMessages.error(player, "Usage: " + usageCreate());
			return true;
		}

		if (rejectIfScopeDisabled(player, apiScope)) {
			return true;
		}

		String uuid = player.getUniqueId().toString();
		final boolean staff = staffMint;
		final String scope = apiScope;
		Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
			if (Cache.isSharedMintScope(scope)) {
				String cooldownError = TokenCooldownService.checkSharedMint(player, scope);
				if (cooldownError != null) {
					Bukkit.getScheduler().runTask(plugin, () -> {
						if (player.isOnline()) {
							ChatMessages.error(player, cooldownError);
						}
					});
					return;
				}
			}

			FeatureCodeResult result = ProvinceSystemClient.issueFeatureCode(uuid, scope);
			Bukkit.getScheduler().runTask(plugin, () -> {
				if (!player.isOnline()) {
					return;
				}
				if (!result.ok) {
					ChatMessages.error(
						player,
						result.error != null ? result.error : "Could not create token."
					);
					return;
				}
				if (staff) {
					ChatMessages.sendCopyableCode(
						player,
						"Your staff skins code (click to copy):",
						result.code
					);
					ChatMessages.info(
						player,
						"Redeem on the skins website - choose category and scroll; "
							+ "applies to the curated pack (no Discord review)."
					);
				} else if ("profile".equals(scope)) {
					ChatMessages.sendCopyableCode(
						player,
						"Your profile code (click to copy):",
						result.code
					);
					ChatMessages.info(
						player,
						"Redeem on the website /profile page (optional Remember me keeps you signed in 30 days)."
					);
				} else if ("drink".equals(scope)) {
					ChatMessages.sendCopyableCode(
						player,
						"Your drink code (click to copy):",
						result.code
					);
					ChatMessages.info(player, "Redeem on the website /drinks page.");
				} else {
					ChatMessages.sendCopyableCode(
						player,
						"Your skins upload code (click to copy):",
						result.code
					);
					ChatMessages.info(player, "Redeem on the skins website.");
				}
				String expiry = ExpiryFormat.relativeLabel(result.expiresAt);
				if (expiry != null) {
					ChatMessages.info(player, expiry);
				}
			});
		});
		return true;
	}

	@Override
	public List<String> onTabComplete(
		CommandSender sender,
		Command command,
		String alias,
		String[] args
	) {
		boolean canCreate = sender.hasPermission(PERM_CREATE);
		boolean canStaff = sender.hasPermission(PERM_CREATE_STAFF);
		boolean canReset = sender.hasPermission(PERM_RESET);
		if (!canCreate && !canStaff && !canReset) {
			return Collections.emptyList();
		}
		if (args.length == 1) {
			String p = args[0].toLowerCase(Locale.ROOT);
			List<String> out = new ArrayList<>();
			if ((canCreate || canStaff) && "create".startsWith(p)) {
				out.add("create");
			}
			if (canReset && "resetcooldowns".startsWith(p)) {
				out.add("resetcooldowns");
			}
			return out;
		}
		if (args.length == 2 && "resetcooldowns".equalsIgnoreCase(args[0]) && canReset) {
			String p = args[1].toLowerCase(Locale.ROOT);
			List<String> out = new ArrayList<>();
			for (Player online : Bukkit.getOnlinePlayers()) {
				String name = online.getName();
				if (name != null && name.toLowerCase(Locale.ROOT).startsWith(p)) {
					out.add(name);
				}
			}
			return out;
		}
		if (args.length == 2 && "create".equalsIgnoreCase(args[0])) {
			String p = args[1].toLowerCase(Locale.ROOT);
			List<String> out = new ArrayList<>();
			if (((canCreate && Cache.isTokenScopeEnabled("skin"))
				|| (canStaff && Cache.isTokenScopeEnabled("skin_staff"))) && "skin".startsWith(p)) {
				out.add("skin");
			}
			if (canCreate) {
				if (Cache.isTokenScopeEnabled("drink") && "drink".startsWith(p)) {
					out.add("drink");
				}
				if (Cache.isTokenScopeEnabled("profile") && "profile".startsWith(p)) {
					out.add("profile");
				}
			}
			return out;
		}
		if (args.length == 3
			&& "create".equalsIgnoreCase(args[0])
			&& "skin".equalsIgnoreCase(args[1])
			&& canStaff
			&& Cache.isTokenScopeEnabled("skin_staff")) {
			String p = args[2].toLowerCase(Locale.ROOT);
			if ("staff".startsWith(p)) {
				return Collections.singletonList("staff");
			}
		}
		return Collections.emptyList();
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
