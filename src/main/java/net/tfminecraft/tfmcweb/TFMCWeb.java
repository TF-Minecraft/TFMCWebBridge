package net.tfminecraft.tfmcweb;

import java.io.File;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import net.tfminecraft.tfmcweb.cache.LinkCache;
import net.tfminecraft.tfmcweb.gate.DiscordGateService;
import net.tfminecraft.tfmcweb.listeners.PlayerJoinListener;
import net.tfminecraft.tfmcweb.loaders.ConfigLoader;
import net.tfminecraft.tfmcweb.luckperms.LuckPermsBridge;
import net.tfminecraft.tfmcweb.entitlements.MetaResync;
import net.tfminecraft.tfmcweb.entitlements.PlayerMetaSyncService;
import net.tfminecraft.tfmcweb.managers.BanMirrorPoller;
import net.tfminecraft.tfmcweb.managers.LinkDiscordCommand;
import net.tfminecraft.tfmcweb.managers.PatreonCommand;
import net.tfminecraft.tfmcweb.managers.PluginNoticePoller;
import net.tfminecraft.tfmcweb.managers.TokenCommand;
import net.tfminecraft.tfmcweb.managers.UnlinkDiscordCommand;
import net.tfminecraft.tfmcweb.managers.WarningCommand;
import net.tfminecraft.tfmcweb.managers.WebCommand;
import net.tfminecraft.tfmcweb.patreon.PatreonRankWriter;

public class TFMCWeb extends JavaPlugin {

	public static TFMCWeb plugin;

	private final ConfigLoader configLoader = new ConfigLoader();
	private final LinkCache linkCache = new LinkCache();
	private DiscordGateService gateService;
	private PluginNoticePoller noticePoller;
	private PatreonRankWriter rankWriter;
	private LuckPermsBridge luckPermsBridge;
	private BanMirrorPoller banMirror;
	private MetaResync metaResync;

	@Override
	public void onEnable() {
		plugin = this;
		if (!getDataFolder().exists()) {
			getDataFolder().mkdirs();
		}
		saveDefaultConfigFile();
		reloadLocalConfig();

		gateService = new DiscordGateService(this, linkCache);
		if (!gateService.isRpcAvailable()) {
			getLogger().info(
				"Discord Survival gate waiting for RPCharacters; availability is checked when used."
			);
		}

		noticePoller = new PluginNoticePoller(this, linkCache, gateService);
		noticePoller.start();
		rankWriter = new PatreonRankWriter(this);
		rankWriter.refresh();
		luckPermsBridge = new LuckPermsBridge(this);
		luckPermsBridge.refresh();

		if (getCommand("linkdiscord") != null) {
			getCommand("linkdiscord").setExecutor(new LinkDiscordCommand(this, linkCache, gateService));
		} else {
			getLogger().severe("Command linkdiscord missing from plugin.yml");
		}
		if (getCommand("unlinkdiscord") != null) {
			getCommand("unlinkdiscord").setExecutor(
				new UnlinkDiscordCommand(this, linkCache, gateService)
			);
		} else {
			getLogger().severe("Command unlinkdiscord missing from plugin.yml");
		}
		if (getCommand("web") != null) {
			WebCommand web = new WebCommand(
				this,
				linkCache,
				gateService,
				rankWriter,
				luckPermsBridge
			);
			getCommand("web").setExecutor(web);
			getCommand("web").setTabCompleter(web);
		} else {
			getLogger().severe("Command web missing from plugin.yml");
		}
		if (getCommand("token") != null) {
			TokenCommand token = new TokenCommand(this);
			getCommand("token").setExecutor(token);
			getCommand("token").setTabCompleter(token);
		} else {
			getLogger().severe("Command token missing from plugin.yml");
		}
		if (getCommand("warning") != null) {
			getCommand("warning").setExecutor(new WarningCommand(this, linkCache));
		} else {
			getLogger().severe("Command warning missing from plugin.yml");
		}
		if (getCommand("patreon") != null) {
			PatreonCommand patreon = new PatreonCommand(this);
			getCommand("patreon").setExecutor(patreon);
			getCommand("patreon").setTabCompleter(patreon);
		} else {
			getLogger().severe("Command patreon missing from plugin.yml");
		}

		Bukkit.getPluginManager().registerEvents(
			new PlayerJoinListener(this, gateService),
			this
		);
		banMirror = new BanMirrorPoller(this, linkCache);
		banMirror.start();
		metaResync = new MetaResync(this);
		metaResync.start();

		getLogger().info(
			"TFMCWeb enabled (api="
				+ (Cache.apiBaseUrl.isEmpty() ? "(unset)" : Cache.apiBaseUrl)
				+ ", rpc="
				+ gateService.isRpcAvailable()
				+ ")"
		);
	}

	@Override
	public void onDisable() {
		if (noticePoller != null) {
			noticePoller.stop();
		}
		if (rankWriter != null) {
			rankWriter.stop();
		}
		if (luckPermsBridge != null) {
			luckPermsBridge.stop();
		}
		if (banMirror != null) {
			banMirror.stop();
		}
		if (metaResync != null) {
			metaResync.stop();
		}
	}

	public LinkCache getLinkCache() {
		return linkCache;
	}

	public DiscordGateService getGateService() {
		return gateService;
	}

	public void reloadLocalConfig() {
		configLoader.load(new File(getDataFolder(), "config.yml"));
		if (rankWriter != null) {
			rankWriter.refresh();
		}
		if (luckPermsBridge != null) {
			luckPermsBridge.refresh();
		}
		PlayerMetaSyncService.pushAllOnlineAsync();
	}

	private void saveDefaultConfigFile() {
		File configFile = new File(getDataFolder(), "config.yml");
		if (!configFile.exists()) {
			saveResource("config.yml", false);
		}
	}

	/** True when another plugin should yield Discord link ownership to TFMCWeb. */
	public static boolean isPresent() {
		Plugin p = Bukkit.getPluginManager().getPlugin("TFMCWeb");
		return p != null && p.isEnabled();
	}

	/**
	 * Realm id for this box (from config {@code realm.id}).
	 * Soft-depend callers: use {@link #isPresent()} then this; if TFMCWeb is
	 * absent, fall back to {@code "main"} locally.
	 * Prefer {@link net.tfminecraft.tfmcweb.api.ProvinceSystemGateway} for HTTP —
	 * it injects realm_id on allowlisted routes.
	 */
	public static String getRealmId() {
		String realm = Cache.realmId;
		if (realm == null || realm.isBlank()) {
			return "main";
		}
		return realm.trim().toLowerCase(java.util.Locale.ROOT);
	}
}
