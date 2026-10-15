package net.tfminecraft.tfmcweb.luckperms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.google.gson.JsonObject;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.CommandNode;
import io.papermc.paper.plugin.configuration.PluginMeta;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import net.tfminecraft.tfmcweb.luckperms.CommandCatalogue.Entry;
import net.tfminecraft.tfmcweb.luckperms.CommandCatalogue.Server;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class CommandCatalogueTest {

	static class Plain extends Command {
		Plain(String name) {
			super(name);
		}

		@Override
		public boolean execute(CommandSender sender, String label, String[] args) {
			return true;
		}
	}

	static final class Owned extends Plain implements PluginIdentifiableCommand {
		final Plugin owner;

		Owned(String name, Plugin owner) {
			super(name);
			this.owner = owner;
		}

		@Override
		public Plugin getPlugin() {
			return owner;
		}
	}

	/** Named like Paper's wrapper: one object per label, holding that label's Brigadier node. */
	static final class VanillaCommandWrapper extends Command {
		final String note = "not a node";
		final CommandNode<Object> node;

		VanillaCommandWrapper(String label, CommandNode<Object> node) {
			super(label);
			this.node = node;
			setPermission("minecraft.command." + CommandCatalogue.bare(label));
		}

		@Override
		public boolean execute(CommandSender sender, String label, String[] args) {
			return true;
		}
	}

	/** Like Paper's plugin wrapper: a Brigadier node owned by a plugin. */
	static final class BrigWrapper extends Command implements PluginIdentifiableCommand {
		final CommandNode<Object> node;
		final Plugin owner;

		BrigWrapper(String label, CommandNode<Object> node, Plugin owner, String... aliases) {
			super(label);
			this.node = node;
			this.owner = owner;
			setAliases(List.of(aliases));
		}

		@Override
		public boolean execute(CommandSender sender, String label, String[] args) {
			return true;
		}

		@Override
		public Plugin getPlugin() {
			return owner;
		}
	}

	static CommandNode<Object> literal(String label, com.mojang.brigadier.Command<Object> run) {
		return LiteralArgumentBuilder.literal(label).executes(run).build();
	}

	static Plugin plugin(String name, String version) {
		Plugin plugin = mock(Plugin.class);
		PluginMeta meta = mock(PluginMeta.class);
		when(plugin.getName()).thenReturn(name);
		when(plugin.getPluginMeta()).thenReturn(meta);
		when(meta.getVersion()).thenReturn(version);
		return plugin;
	}

	@Test void groupsLabelsByCommandAndNamesTheOwningPlugin() {
		Plugin disguises = plugin("LibsDisguises", "11.0.14");
		Owned clone = new Owned("DisguiseClone", disguises);
		clone.setPermission("libsdisguises.seecmd.disguiseclone");
		clone.setDescription("  Copy a disguise.  ");
		Plain tp = new Plain("tp");
		tp.setPermission("minecraft.command.teleport");
		tp.setUsage("/tp <target>");
		Plain region = new Plain("region");
		region.setDescription(" ");
		Plain me = new Plain("me");
		Plain loose = new Plain("loose");
		loose.setUsage("/<command>");
		Plain other = new Plain("other");
		Map<String, Command> known = new LinkedHashMap<>();
		known.put("disguiseclone", clone);
		known.put("dclone", clone);
		known.put("libsdisguises:disguiseclone", clone);
		known.put("libsdisguises:dclone", clone);
		known.put("TP", tp);
		known.put("minecraft:tp", tp);
		known.put("worldguard:region", region);
		known.put("rg", region);
		known.put("rpcharacters:me", me);
		known.put("gone", null);
		known.put("loose", loose);
		known.put("custom:other", other);

		List<Entry> entries = CommandCatalogue.entries(known, Map.of("worldguard", "WorldGuard"));

		assertEquals(List.of(
			new Entry("Custom", "other", List.of(), null, null, null, true),
			new Entry("LibsDisguises", "disguiseclone", List.of("dclone"), "Copy a disguise.", null,
				"libsdisguises.seecmd.disguiseclone", false),
			new Entry("Minecraft", "tp", List.of(), null, "/tp <target>", "minecraft.command.teleport", false),
			new Entry("Other", "loose", List.of(), null, null, null, false),
			new Entry("Rpcharacters", "me", List.of(), null, null, null, true),
			new Entry("WorldGuard", "region", List.of("rg"), null, null, null, true)
		), entries);
	}

	@Test void longTextIsCut() {
		Plain command = new Plain("long");
		command.setDescription("x".repeat(CommandCatalogue.TEXT_MAX + 5));
		Entry entry = CommandCatalogue.entries(Map.of("long", command), Map.of()).get(0);
		assertEquals(CommandCatalogue.TEXT_MAX, entry.description().length());
	}

	@Test void defaultsFollowBukkitChildInheritance() {
		Permission seecmd = new Permission("libsdisguises.seecmd", PermissionDefault.TRUE,
			Map.of("libsdisguises.seecmd.disguise", true, "libsdisguises.seecmd.hidden", false));
		Permission hidden = new Permission("libsdisguises.seecmd.hidden", PermissionDefault.OP,
			Map.of("deep.denied", false, "deep.allowed", true));
		Permission loop = new Permission("loop", PermissionDefault.TRUE, Map.of("libsdisguises.seecmd", true, "loop", false));
		Map<String, Permission> registered = new HashMap<>();
		for (Permission permission : List.of(seecmd, hidden, loop)) {
			registered.put(permission.getName(), permission);
		}

		Map<String, Boolean> defaults = CommandCatalogue.defaults(Set.of(seecmd, loop), registered::get);

		assertEquals(Map.of(
			"libsdisguises.seecmd", true,
			"libsdisguises.seecmd.disguise", true,
			"libsdisguises.seecmd.hidden", false,
			"deep.denied", true,
			"deep.allowed", false,
			"loop", true
		), defaults);
	}

	@Test void accessAsksLuckPermsThenFallsBackToDefaults() {
		Function<String, Boolean> staff = permission -> permission.startsWith("admin.") ? Boolean.TRUE : null;
		Function<String, Boolean> player = permission -> permission.equals("blocked") ? Boolean.FALSE : null;
		Map<String, Boolean> defaults = Map.of("blocked", true, "open", true);

		assertNull(CommandCatalogue.access(null, List.of(staff, player), defaults));
		assertEquals("yn", CommandCatalogue.access("admin.x", List.of(staff, player), defaults));
		assertEquals("yy", CommandCatalogue.access("OPEN", List.of(staff, player), defaults));
		assertEquals("yn", CommandCatalogue.access("blocked", List.of(staff, player), defaults));
		assertEquals("yn", CommandCatalogue.access(" ; nothing;admin.y", List.of(staff, player), defaults));
	}

	@Test void payloadListsGroupsPluginsAndCommandsInAStableOrder() {
		Server server = new Server(
			List.of(
				new Entry("Essentials", "anvil", List.of("eanvil"), "Opens an anvil.", null, "essentials.anvil", false),
				new Entry("Essentials", "god", List.of(), null, "/god [player]", "essentials.god", false),
				new Entry("Minecraft", "me", List.of(), null, null, null, true)
			),
			Map.of("Essentials", "2.22.0"),
			Map.of()
		);
		Map<String, Function<String, Boolean>> checks = new LinkedHashMap<>();
		checks.put("staff", permission -> Boolean.TRUE);
		checks.put("default", permission -> permission.equals("essentials.anvil") ? Boolean.TRUE : null);

		JsonObject payload = CommandCatalogue.payload(server, checks);

		assertEquals("[\"default\",\"staff\"]", payload.getAsJsonArray("groups").toString());
		assertEquals("[{\"name\":\"Essentials\",\"version\":\"2.22.0\"},{\"name\":\"Minecraft\",\"version\":null}]",
			payload.getAsJsonArray("plugins").toString());
		assertEquals("{\"plugin\":\"Essentials\",\"name\":\"anvil\",\"aliases\":[\"eanvil\"],"
			+ "\"description\":\"Opens an anvil.\",\"usage\":null,\"permission\":\"essentials.anvil\",\"shadowed\":false,"
			+ "\"access\":\"yy\"}", payload.getAsJsonArray("commands").get(0).toString());
		assertEquals("ny", payload.getAsJsonArray("commands").get(1).getAsJsonObject().get("access").getAsString());
		assertEquals("/god [player]", payload.getAsJsonArray("commands").get(1).getAsJsonObject().get("usage").getAsString());
		assertTrue(payload.getAsJsonArray("commands").get(2).getAsJsonObject().get("shadowed").getAsBoolean());
	}

	@Test void readsTheLiveServerOnTheMainThread() throws Exception {
		Plugin core = plugin("TFMCCore", "3.5.3");
		Owned tfmc = new Owned("tfmc", core);
		Permission open = new Permission("tfmc.open", PermissionDefault.TRUE);
		CommandMap map = mock(CommandMap.class);
		when(map.getKnownCommands()).thenReturn(Map.of("tfmc", tfmc, "tfmccore:tfmc", tfmc));
		PluginManager manager = mock(PluginManager.class);
		when(manager.getPlugins()).thenReturn(new Plugin[] {core});
		when(manager.getDefaultPermissions(false)).thenReturn(Set.of(open));
		when(manager.getPermission("tfmc.open")).thenReturn(open);
		BukkitScheduler scheduler = mock(BukkitScheduler.class);
		JavaPlugin web = mock(JavaPlugin.class);
		CompletableFuture<Server> answer = new CompletableFuture<>();
		when(scheduler.<Server>callSyncMethod(eq(web), any())).thenAnswer(call -> {
			answer.complete(call.<Callable<Server>>getArgument(1).call());
			return answer;
		});
		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			bukkit.when(Bukkit::getCommandMap).thenReturn(map);
			bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
			bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);

			Server server = CommandCatalogue.readOnMainThread(web);

			assertEquals(List.of(new Entry("TFMCCore", "tfmc", List.of(), null, null, null, false)), server.commands());
			assertEquals(Map.of("TFMCCore", "3.5.3"), server.versions());
			assertEquals(Map.of("tfmc.open", true), server.defaults());
		}
	}

	@Test void joinsPaperWrappersByRedirectAndListedAliases() {
		CommandNode<Object> teleport = LiteralArgumentBuilder.literal("teleport").executes(c -> 1).build();
		CommandNode<Object> tp = LiteralArgumentBuilder.literal("tp").redirect(teleport).build();
		Plugin probe = plugin("Probe", "1");
		com.mojang.brigadier.Command<Object> run = c -> 1;
		Map<String, Command> known = new LinkedHashMap<>();
		known.put("probe:pb1", new BrigWrapper("probe:pb1", literal("probe:pb1", run), probe));
		known.put("pb1", new BrigWrapper("pb1", literal("pb1", run), probe));
		known.put("probe:probebrig", new BrigWrapper("probe:probebrig", literal("probe:probebrig", run), probe,
			"pb1", "probe:pb1"));
		known.put("probebrig", new BrigWrapper("probebrig", literal("probebrig", run), probe, "pb1", "probe:pb1"));
		known.put("tp", new VanillaCommandWrapper("tp", tp));
		known.put("minecraft:tp", new VanillaCommandWrapper("minecraft:tp", tp));
		known.put("teleport", new VanillaCommandWrapper("teleport", teleport));
		known.put("minecraft:teleport", new VanillaCommandWrapper("minecraft:teleport", teleport));
		known.put("minecraft:me", new VanillaCommandWrapper("minecraft:me", null));

		assertEquals(List.of(
			new Entry("Minecraft", "me", List.of(), null, null, "minecraft.command.me", true),
			new Entry("Minecraft", "teleport", List.of("tp"), null, null, "minecraft.command.teleport", false),
			new Entry("Probe", "probebrig", List.of("pb1"), null, null, null, false)
		), CommandCatalogue.entries(known, Map.of()));
	}

	@Test void unreadableNodesHaveNoRedirect() {
		@SuppressWarnings("unchecked")
		CommandNode<Object> broken = mock(CommandNode.class);
		when(broken.getRedirect()).thenThrow(new IllegalStateException("shadow"));
		assertNull(CommandCatalogue.redirectTarget(new VanillaCommandWrapper("x", broken)));
		assertNull(CommandCatalogue.redirectTarget(new Plain("y")));
	}

	@Test void aDefaultPermissionBeatsAChildSetFalseElsewhere() {
		Permission parent = new Permission("example.parent", PermissionDefault.TRUE, Map.of("example.child", false));
		Permission child = new Permission("example.child", PermissionDefault.TRUE);
		Map<String, Permission> registered = Map.of(parent.getName(), parent, child.getName(), child);
		Set<Permission> roots = new LinkedHashSet<>(List.of(parent, child));

		assertEquals(Map.of("example.parent", true, "example.child", true),
			CommandCatalogue.defaults(roots, registered::get));
	}

	@Test void accessReadsTheWholePermissionButOnlyDisplaysPartOfIt() {
		String permission = "example.denied;".repeat(150) + "example.allowed";
		Function<String, Boolean> member = node -> node.equals("example.allowed") ? Boolean.TRUE : null;
		Server server = new Server(List.of(new Entry("Example", "long", List.of(), null, null, permission, false)),
			Map.of(), Map.of());

		JsonObject command = CommandCatalogue.payload(server, Map.of("member", member))
			.getAsJsonArray("commands").get(0).getAsJsonObject();

		assertEquals("y", command.get("access").getAsString());
		assertEquals(CommandCatalogue.PERMISSION_MAX, command.get("permission").getAsString().length());
	}

	@Test void aStaleAliasDoesNotSwallowAnotherCommand() {
		Plugin first = plugin("First", "1");
		Owned alpha = new Owned("alpha", first);
		alpha.setAliases(List.of("beta"));
		alpha.setPermission("first.alpha");
		Owned beta = new Owned("beta", first);
		beta.setPermission("first.beta");
		CommandNode<Object> child = LiteralArgumentBuilder.literal("list").executes(c -> 1).build();
		CommandNode<Object> sub = LiteralArgumentBuilder.literal("sub").then(child).build();
		CommandNode<Object> s = LiteralArgumentBuilder.literal("s").then(child).build();
		Map<String, Command> known = new LinkedHashMap<>();
		known.put("alpha", alpha);
		known.put("beta", beta);
		known.put("gamma", new BrigWrapper("gamma", literal("gamma", c -> 1), first, "delta"));
		known.put("delta", new BrigWrapper("delta", literal("delta", c -> 2), first));
		known.put("sub", new BrigWrapper("sub", sub, first, "s", "bare", "empty", "other", "twin", "runs"));
		known.put("runs", new BrigWrapper("runs", LiteralArgumentBuilder.literal("runs").executes(c -> 3).then(child)
			.build(), first, "sub"));
		known.put("twin", new BrigWrapper("twin", LiteralArgumentBuilder.literal("twin").then(literal("list", c -> 1))
			.build(), first));
		known.put("s", new BrigWrapper("s", s, first));
		known.put("bare", new Owned("bare", first));
		known.put("empty", new BrigWrapper("empty", LiteralArgumentBuilder.literal("empty").build(), first));
		known.put("other", new BrigWrapper("other", LiteralArgumentBuilder.literal("other").then(literal("x", c -> 1))
			.then(literal("y", c -> 1)).build(), first));

		assertEquals(
			List.of("alpha[]", "bare[]", "beta[]", "delta[]", "empty[]", "gamma[]", "other[]", "runs[]", "sub[s]",
				"twin[]"),
			CommandCatalogue.entries(known, Map.of()).stream().map(e -> e.name() + e.aliases()).toList());
	}
}
