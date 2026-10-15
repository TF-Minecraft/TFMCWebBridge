package net.tfminecraft.tfmcweb.luckperms;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.lang.reflect.Field;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.permissions.Permission;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import com.google.gson.JsonArray;
import com.mojang.brigadier.tree.CommandNode;
import com.google.gson.JsonObject;

/**
 * Every command this server knows, and which LuckPerms groups its permission allows,
 * for the staff panel's Commands tab. Only the command's own permission is checked;
 * plugins may refuse a command for other reasons when it runs.
 */
public final class CommandCatalogue {

	static final long READ_TIMEOUT_SECONDS = 10;
	static final int TEXT_MAX = 300;
	/** Sent for display only; access is decided on the whole permission. */
	static final int PERMISSION_MAX = 2048;
	private static final String DEFAULT_USAGE = "/<command>";
	private static final String VANILLA_WRAPPER = "VanillaCommandWrapper";

	/** One registered command. Aliases exclude the name; shadowed means the plain name runs another plugin's command. */
	public record Entry(
		String plugin,
		String name,
		List<String> aliases,
		String description,
		String usage,
		String permission,
		boolean shadowed
	) {}

	/** What the main thread reads: commands, plugin versions, and permissions every non-op player holds by default. */
	public record Server(List<Entry> commands, Map<String, String> versions, Map<String, Boolean> defaults) {}

	private CommandCatalogue() {}

	/** Reads on the main thread and waits for it, from the bridge's worker thread. */
	static Server readOnMainThread(JavaPlugin plugin) throws Exception {
		Future<Server> read = Bukkit.getScheduler().callSyncMethod(plugin, CommandCatalogue::read);
		try {
			return read.get(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} finally {
			read.cancel(false);
		}
	}

	/** Main thread only: the command map and plugin manager are not thread-safe. */
	static Server read() {
		return read(
			Bukkit.getCommandMap().getKnownCommands(),
			Bukkit.getPluginManager().getPlugins(),
			Bukkit.getPluginManager().getDefaultPermissions(false),
			Bukkit.getPluginManager()::getPermission
		);
	}

	static Server read(
		Map<String, Command> known,
		Plugin[] plugins,
		Set<Permission> defaultPermissions,
		Function<String, Permission> lookup
	) {
		Map<String, String> names = new HashMap<>();
		Map<String, String> versions = new TreeMap<>();
		for (Plugin plugin : plugins) {
			names.put(plugin.getName().toLowerCase(Locale.ROOT), plugin.getName());
			versions.put(plugin.getName(), plugin.getPluginMeta().getVersion());
		}
		return new Server(entries(known, names), versions, defaults(defaultPermissions, lookup));
	}

	/**
	 * Groups the command map's labels into one entry per command and owner. Paper wraps each
	 * Brigadier label in its own object, so labels are joined by name: a vanilla alias by the
	 * node it redirects to, a plugin alias by the aliases Paper lists on its main label.
	 */
	static List<Entry> entries(Map<String, Command> live, Map<String, String> pluginNames) {
		// Paper's map wraps Brigadier nodes afresh on every read, so read it once.
		Map<String, Command> known = new LinkedHashMap<>(live);
		Map<Command, String> namespaces = new IdentityHashMap<>();
		// A copy under "ns:label" names the namespace of an unlinked wrapper of the same kind.
		Map<String, String> namespaceByLabel = new HashMap<>();
		for (Map.Entry<String, Command> entry : known.entrySet()) {
			String key = entry.getKey().toLowerCase(Locale.ROOT);
			int colon = key.indexOf(':');
			if (entry.getValue() != null && colon > 0) {
				String namespace = key.substring(0, colon);
				namespaces.putIfAbsent(entry.getValue(), namespace);
				namespaceByLabel.putIfAbsent(sameKind(entry.getValue(), key.substring(colon + 1)), namespace);
			}
		}
		Map<Command, String> owners = new IdentityHashMap<>();
		for (Command command : known.values()) {
			if (command == null || owners.containsKey(command)) {
				continue;
			}
			String name = command.getName().toLowerCase(Locale.ROOT);
			String namespace = name.indexOf(':') > 0 ? name.substring(0, name.indexOf(':')) : namespaces.get(command);
			if (namespace == null) {
				namespace = namespaceByLabel.get(sameKind(command, bare(name)));
			}
			owners.put(command, owner(command, namespace, pluginNames));
		}
		Map<String, Command> aliasOf = new HashMap<>();
		for (Map.Entry<String, Command> entry : known.entrySet()) {
			Command command = entry.getValue();
			if (command == null) {
				continue;
			}
			String owner = owners.get(command);
			String name = bare(command.getName());
			for (String alias : command.getAliases()) {
				if (!bare(alias).equals(name)) {
					aliasOf.putIfAbsent(owner + ':' + bare(alias), command);
				}
			}
		}
		Map<String, Command> main = new TreeMap<>();
		Map<String, TreeSet<String>> labels = new HashMap<>();
		for (Map.Entry<String, Command> entry : known.entrySet()) {
			Command command = entry.getValue();
			if (command == null) {
				continue;
			}
			String owner = owners.get(command);
			String label = bare(command.getName());
			String name = redirectTarget(command);
			if (name == null) {
				Command listing = aliasOf.get(owner + ':' + label);
				// An alias list can outlive the alias: only join a label that runs the same command.
				name = listing != null && sameCommand(listing, command) ? bare(listing.getName()) : label;
			}
			String id = owner + ':' + name;
			boolean plainKey = entry.getKey().indexOf(':') < 0;
			// Prefer the main label's plain wrapper, for its description and usage.
			if (!main.containsKey(id) || label.equals(name) && plainKey) {
				main.put(id, command);
			}
			TreeSet<String> plain = labels.computeIfAbsent(id, ignored -> new TreeSet<>());
			if (plainKey) {
				plain.add(entry.getKey().toLowerCase(Locale.ROOT));
			}
		}
		List<Entry> entries = new ArrayList<>();
		for (Map.Entry<String, Command> entry : main.entrySet()) {
			Command command = entry.getValue();
			String owner = owners.get(command);
			String name = entry.getKey().substring(owner.length() + 1);
			TreeSet<String> aliases = labels.get(entry.getKey());
			boolean shadowed = !aliases.remove(name);
			entries.add(new Entry(
				owner,
				name,
				List.copyOf(aliases),
				text(command.getDescription(), TEXT_MAX),
				usage(command.getUsage(), name),
				text(command.getPermission(), Integer.MAX_VALUE),
				shadowed
			));
		}
		entries.sort(Comparator.comparing(Entry::plugin, String.CASE_INSENSITIVE_ORDER)
			.thenComparing(Entry::name));
		return entries;
	}

	/** The label without a namespace, lower case. */
	static String bare(String label) {
		String lower = label.toLowerCase(Locale.ROOT);
		return lower.substring(lower.indexOf(':') + 1);
	}

	/** Wrappers of one command share class, description and permission; /version exists twice otherwise. */
	private static String sameKind(Command command, String label) {
		return command.getClass().getName() + '\n' + label + '\n' + command.getDescription() + '\n' + command.getPermission();
	}

	private static String owner(Command command, String namespace, Map<String, String> pluginNames) {
		if (command instanceof PluginIdentifiableCommand owned) {
			return owned.getPlugin().getName();
		}
		if (namespace == null) {
			return VANILLA_WRAPPER.equals(command.getClass().getSimpleName()) ? "Minecraft" : "Other";
		}
		String plugin = pluginNames.get(namespace);
		if (plugin != null) {
			return plugin;
		}
		return namespace.substring(0, 1).toUpperCase(Locale.ROOT) + namespace.substring(1);
	}

	/** For a Brigadier wrapper whose node redirects (vanilla /tp to /teleport), the target's name. */
	static String redirectTarget(Command command) {
		return withNode(command, node -> node.getRedirect() == null ? null : bare(node.getRedirect().getName()), null);
	}

	/** Two Brigadier labels with the same executor or the same subcommands. */
	static boolean sameCommand(Command first, Command second) {
		return withNode(first, a -> withNode(second, b -> sameNode(a, b), false), false);
	}

	private static boolean sameNode(CommandNode<?> a, CommandNode<?> b) {
		if (a.getCommand() != null) {
			return a.getCommand() == b.getCommand();
		}
		List<CommandNode<?>> children = List.copyOf(a.getChildren());
		List<CommandNode<?>> others = List.copyOf(b.getChildren());
		if (children.isEmpty() || children.size() != others.size()) {
			return false;
		}
		for (int i = 0; i < children.size(); i++) {
			if (children.get(i) != others.get(i)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Applies {@code use} to the Brigadier node a Paper wrapper holds, read by reflection.
	 * Other commands, and nodes that refuse to be read, give {@code fallback}.
	 */
	private static <T> T withNode(Command command, Function<CommandNode<?>, T> use, T fallback) {
		for (Class<?> type = command.getClass(); type != Command.class; type = type.getSuperclass()) {
			for (Field field : type.getDeclaredFields()) {
				if (!CommandNode.class.isAssignableFrom(field.getType())) {
					continue;
				}
				try {
					field.setAccessible(true);
					CommandNode<?> node = (CommandNode<?>) field.get(command);
					return node == null ? fallback : use.apply(node);
				} catch (ReflectiveOperationException | RuntimeException e) {
					return fallback;
				}
			}
		}
		return fallback;
	}

	/** Drops the usage Bukkit and Paper fill in when a plugin sets none. */
	private static String usage(String usage, String name) {
		String text = text(usage, TEXT_MAX);
		if (text == null || text.equals(DEFAULT_USAGE) || bare(text.startsWith("/") ? text.substring(1) : text).equals(name)) {
			return null;
		}
		return text;
	}

	private static String text(String text, int max) {
		if (text == null || text.isBlank()) {
			return null;
		}
		String trimmed = text.strip();
		return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
	}

	/**
	 * Bukkit's defaults for a non-op player, as LuckPerms resolves them: each default permission
	 * is granted, and the children it brings only fill permissions nothing else set.
	 */
	static Map<String, Boolean> defaults(Set<Permission> permissions, Function<String, Permission> lookup) {
		Map<String, Boolean> values = new HashMap<>();
		for (Permission permission : permissions) {
			addChildren(permission.getName(), true, values, lookup, new HashSet<>());
		}
		for (Permission permission : permissions) {
			values.put(permission.getName().toLowerCase(Locale.ROOT), Boolean.TRUE);
		}
		return values;
	}

	private static void addChildren(
		String name,
		boolean value,
		Map<String, Boolean> values,
		Function<String, Permission> lookup,
		Set<String> seen
	) {
		String key = name.toLowerCase(Locale.ROOT);
		if (!seen.add(key)) {
			return;
		}
		values.putIfAbsent(key, value);
		Permission permission = lookup.apply(key);
		if (permission == null) {
			return;
		}
		for (Map.Entry<String, Boolean> child : permission.getChildren().entrySet()) {
			// As Bukkit does: a child set false under a denied parent ends up allowed.
			addChildren(child.getKey(), child.getValue() == value, values, lookup, seen);
		}
	}

	/**
	 * @param checks each group's LuckPerms answer: true, false, or null when LuckPerms has no node
	 * @return {@code {"groups": [...], "plugins": [...], "commands": [...]}} in a stable order; each
	 *     command's {@code access} has one {@code y} or {@code n} per group, or is null with no permission
	 */
	static JsonObject payload(Server server, Map<String, Function<String, Boolean>> checks) {
		Map<String, Function<String, Boolean>> groups = new TreeMap<>(checks);
		JsonArray groupNames = new JsonArray();
		groups.keySet().forEach(groupNames::add);
		Map<String, Boolean> owners = new LinkedHashMap<>();
		JsonArray commands = new JsonArray();
		for (Entry entry : server.commands()) {
			owners.put(entry.plugin(), Boolean.TRUE);
			JsonObject json = new JsonObject();
			json.addProperty("plugin", entry.plugin());
			json.addProperty("name", entry.name());
			JsonArray aliases = new JsonArray();
			entry.aliases().forEach(aliases::add);
			json.add("aliases", aliases);
			json.addProperty("description", entry.description());
			json.addProperty("usage", entry.usage());
			json.addProperty("permission", text(entry.permission(), PERMISSION_MAX));
			json.addProperty("shadowed", entry.shadowed());
			json.addProperty("access", access(entry.permission(), groups.values(), server.defaults()));
			commands.add(json);
		}
		JsonArray plugins = new JsonArray();
		for (String name : owners.keySet()) {
			JsonObject plugin = new JsonObject();
			plugin.addProperty("name", name);
			plugin.addProperty("version", server.versions().get(name));
			plugins.add(plugin);
		}
		JsonObject root = new JsonObject();
		root.add("groups", groupNames);
		root.add("plugins", plugins);
		root.add("commands", commands);
		return root;
	}

	/** Paper allows a command when any of its {@code ;}-separated permissions is held. */
	static String access(String permission, Iterable<Function<String, Boolean>> groups, Map<String, Boolean> defaults) {
		if (permission == null) {
			return null;
		}
		StringBuilder out = new StringBuilder();
		for (Function<String, Boolean> group : groups) {
			out.append(allowed(permission, group, defaults) ? 'y' : 'n');
		}
		return out.toString();
	}

	private static boolean allowed(String permission, Function<String, Boolean> group, Map<String, Boolean> defaults) {
		for (String part : permission.split(";")) {
			String node = part.strip().toLowerCase(Locale.ROOT);
			if (node.isEmpty()) {
				continue;
			}
			Boolean value = group.apply(node);
			if (value == null) {
				value = defaults.getOrDefault(node, Boolean.FALSE);
			}
			if (value) {
				return true;
			}
		}
		return false;
	}
}
