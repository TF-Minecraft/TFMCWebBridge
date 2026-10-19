package net.tfminecraft.tfmcweb.loaders;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.tfmcweb.Cache;
import net.tfminecraft.tfmcweb.Cache.TokenCooldownGroup;

public final class ConfigLoader {

	public void load(File configFile) {
		FileConfiguration config = new YamlConfiguration();
		try {
			config.load(configFile);
		} catch (IOException | InvalidConfigurationException e) {
			e.printStackTrace();
			return;
		}

		String base = config.getString("api.base-url", "");
		base = base.trim();
		while (base.endsWith("/")) {
			base = base.substring(0, base.length() - 1);
		}
		Cache.apiBaseUrl = base;

		String key = config.getString("api.plugin-key", "");
		Cache.pluginKey = key == null ? "" : key.trim();

		loadRealmAndTokens(config);
		loadTokenCooldowns(config);
		PlayerMetaConfigLoader.load(config);
		loadPatreon(config);
		Cache.banMirrorEnabled = config.getBoolean("ban-mirror.enabled", true);
		Cache.banMirrorPollSeconds = positiveOrDefault(
			config.getInt("ban-mirror.poll-seconds", 30),
			30
		);
		Cache.banMirrorSync = config.getBoolean("ban-mirror.sync", false);
		loadLuckPermsBridge(config);
	}

	private void loadLuckPermsBridge(FileConfiguration config) {
		Cache.luckPermsBridgePublish = config.getBoolean("luckperms-bridge.publish", false);
		Cache.luckPermsBridgeApply = config.getBoolean("luckperms-bridge.apply", false);
		Cache.luckPermsBridgePollSeconds = positiveOrDefault(
			config.getInt("luckperms-bridge.poll-seconds", 3),
			3
		);
		Cache.luckPermsBridgeSnapshotSeconds = positiveOrDefault(
			config.getInt("luckperms-bridge.snapshot-seconds", 30),
			30
		);
	}

	private void loadPatreon(FileConfiguration config) {
		Cache.patreonEnabled = config.getBoolean("patreon.enabled", false);
		Cache.patreonApplyRanks = config.getBoolean("patreon.apply-ranks", false);
		Cache.patreonPollSeconds = positiveOrDefault(
			config.getInt("patreon.poll-seconds", 60),
			60
		);
		Cache.patreonReconcileMinutes = positiveOrDefault(
			config.getInt("patreon.reconcile-minutes", 30),
			30
		);
		Cache.patreonGroups = Map.copyOf(readPatreonGroups(
			config.getConfigurationSection("patreon.groups")
		));
	}

	private static int positiveOrDefault(int value, int fallback) {
		return value < 1 ? fallback : value;
	}

	private static Map<String, String> readPatreonGroups(ConfigurationSection section) {
		Map<String, String> groups = Cache.patreonGroupDefaults();
		if (section == null) {
			return groups;
		}
		for (String rawKey : section.getKeys(false)) {
			String tier = rawKey.trim().toLowerCase(Locale.ROOT);
			if (!"noble".equals(tier) && !"gilded".equals(tier) && !"ascended".equals(tier)) {
				continue;
			}
			String value = section.getString(rawKey);
			if (value == null || value.isBlank()) {
				groups.remove(tier);
				continue;
			}
			groups.put(tier, value.trim());
		}
		return groups;
	}

	private void loadRealmAndTokens(FileConfiguration config) {
		String realm = config.getString("realm.id", "main");
		if (realm == null || realm.isBlank()) {
			realm = "main";
		} else {
			realm = realm.trim().toLowerCase(Locale.ROOT);
		}
		Cache.realmId = realm;

		List<String> enabled = Cache.newStringList();
		List<?> rawEnabled = config.getList("tokens.enabled-scopes");
		if (rawEnabled != null) {
			for (Object item : rawEnabled) {
				if (item == null) {
					continue;
				}
				String scope = String.valueOf(item).trim().toLowerCase(Locale.ROOT);
				if (scope.isEmpty()) {
					continue;
				}
				if (!scope.equals("skin")
					&& !scope.equals("drink")
					&& !scope.equals("profile")
					&& !scope.equals("skin_staff")) {
					continue;
				}
				if (!enabled.contains(scope)) {
					enabled.add(scope);
				}
			}
		} else {
			// Missing key → keep full default (backward compatible).
			enabled.add("skin");
			enabled.add("drink");
			enabled.add("profile");
			enabled.add("skin_staff");
		}
		Cache.tokenEnabledScopes = List.copyOf(enabled);
	}

	private void loadTokenCooldowns(FileConfiguration config) {
		List<String> shared = Cache.newSharedScopeList();
		List<?> rawShared = config.getList("token-cooldowns.shared-scopes");
		if (rawShared != null) {
			for (Object item : rawShared) {
				if (item == null) {
					continue;
				}
				String scope = String.valueOf(item).trim().toLowerCase(Locale.ROOT);
				if (!scope.isEmpty()) {
					shared.add(scope);
				}
			}
		}
		if (shared.isEmpty()) {
			shared.add("skin");
			shared.add("drink");
		}
		Cache.tokenCooldownSharedScopes = List.copyOf(shared);

		Cache.tokenCooldownDefaultDays = config.getInt(
			"token-cooldowns.defaults.cooldown-days",
			-1
		);

		List<TokenCooldownGroup> groups = Cache.newGroupList();
		List<?> rawGroups = config.getList("token-cooldowns.groups");
		if (rawGroups != null) {
			for (Object item : rawGroups) {
				if (!(item instanceof ConfigurationSection)
					&& !(item instanceof java.util.Map)) {
					continue;
				}
				String permission;
				int days;
				if (item instanceof ConfigurationSection section) {
					permission = section.getString("permission", "");
					days = section.getInt("cooldown-days", -1);
				} else {
					@SuppressWarnings("unchecked")
					java.util.Map<String, Object> map = (java.util.Map<String, Object>) item;
					Object permObj = map.get("permission");
					permission = permObj == null ? "" : String.valueOf(permObj);
					Object daysObj = map.get("cooldown-days");
					if (daysObj instanceof Number) {
						days = ((Number) daysObj).intValue();
					} else {
						try {
							days = Integer.parseInt(String.valueOf(daysObj));
						} catch (NumberFormatException e) {
							days = -1;
						}
					}
				}
				if (permission == null || permission.isBlank()) {
					continue;
				}
				groups.add(new TokenCooldownGroup(permission.trim(), days));
			}
		}
		Cache.tokenCooldownGroups = List.copyOf(groups);
	}
}
