package net.tfminecraft.tfmcweb.luckperms;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.permissions.Permission;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Every command this server knows, and which LuckPerms groups its permission allows,
 * for the staff panel's Commands tab. Only the command's own permission is checked;
 * plugins may refuse a command for other reasons when it runs.
 */
public final class CommandCatalogue {

	static final long READ_TIMEOUT_SECONDS = 10;
	static final int TEXT_MAX = 300;
	private static final String DEFAULT_USAGE = "/<command>";

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

	/** Groups the command map's labels by command, so each command appears once with its aliases. */
	static List<Entry> entries(Map<String, Command> known, Map<String, String> pluginNames) {
		Map<Command, List<String>> labels = new IdentityHashMap<>();
		Map<Command, String> namespaces = new IdentityHashMap<>();
		for (Map.Entry<String, Command> entry : known.entrySet()) {
			Command command = entry.getValue();
			if (command == null) {
				continue;
			}
			labels.computeIfAbsent(command, ignored -> new ArrayList<>());
			String key = entry.getKey().toLowerCase(Locale.ROOT);
			int colon = key.indexOf(':');
			if (colon < 0) {
				labels.get(command).add(key);
			} else {
				namespaces.putIfAbsent(command, key.substring(0, colon));
			}
		}
		List<Entry> entries = new ArrayList<>();
		for (Map.Entry<Command, List<String>> entry : labels.entrySet()) {
			Command command = entry.getKey();
			String name = command.getName().toLowerCase(Locale.ROOT);
			TreeSet<String> aliases = new TreeSet<>(entry.getValue());
			boolean shadowed = !aliases.remove(name);
			entries.add(new Entry(
				owner(command, namespaces.get(command), pluginNames),
				name,
				List.copyOf(aliases),
				text(command.getDescription()),
				usage(command.getUsage(), name),
				text(command.getPermission()),
				shadowed
			));
		}
		entries.sort(Comparator.comparing(Entry::plugin, String.CASE_INSENSITIVE_ORDER)
			.thenComparing(Entry::name)
			.thenComparing(Entry::shadowed));
		return entries;
	}

	private static String owner(Command command, String namespace, Map<String, String> pluginNames) {
		if (command instanceof PluginIdentifiableCommand owned) {
			return owned.getPlugin().getName();
		}
		if (namespace == null) {
			return "Other";
		}
		String plugin = pluginNames.get(namespace);
		if (plugin != null) {
			return plugin;
		}
		return namespace.substring(0, 1).toUpperCase(Locale.ROOT) + namespace.substring(1);
	}

	/** Drops the usage Bukkit and Paper fill in when a plugin sets none. */
	private static String usage(String usage, String name) {
		String text = text(usage);
		if (text == null || text.equals(DEFAULT_USAGE) || text.equalsIgnoreCase("/" + name)) {
			return null;
		}
		return text;
	}

	private static String text(String text) {
		if (text == null || text.isBlank()) {
			return null;
		}
		String trimmed = text.strip();
		return trimmed.length() > TEXT_MAX ? trimmed.substring(0, TEXT_MAX) : trimmed;
	}

	/** Bukkit's defaults for a non-op player: each default permission and, recursively, its children. */
	static Map<String, Boolean> defaults(Set<Permission> permissions, Function<String, Permission> lookup) {
		Map<String, Boolean> values = new HashMap<>();
		for (Permission permission : permissions) {
			addDefault(permission.getName(), true, values, lookup);
		}
		return values;
	}

	private static void addDefault(String name, boolean value, Map<String, Boolean> values, Function<String, Permission> lookup) {
		String key = name.toLowerCase(Locale.ROOT);
		if (values.containsKey(key)) {
			return;
		}
		values.put(key, value);
		Permission permission = lookup.apply(key);
		if (permission == null) {
			return;
		}
		for (Map.Entry<String, Boolean> child : permission.getChildren().entrySet()) {
			// As Bukkit does: a child set false under a denied parent ends up allowed.
			addDefault(child.getKey(), child.getValue() == value, values, lookup);
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
			json.addProperty("permission", entry.permission());
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
