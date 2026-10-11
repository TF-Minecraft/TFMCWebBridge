package net.tfminecraft.tfmcweb.entitlements;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.entity.Player;

import net.tfminecraft.tfmcweb.Cache;

/**
 * Resolve web entitlements from duplicated player-meta ladders in TFMCWeb config.
 */
public final class EntitlementResolver {

	public static final String KEY_NAME_COLOUR_STOPS = "name-colour-stops";
	public static final String KEY_MAX_ALIVE = "max-alive-characters";
	public static final String KEY_WARDROBE_SLOTS = "wardrobe-skin-slots";
	public static final String KEY_MAX_3D_PAIR = "max-3d-pair-bytes";
	public static final String KEY_SKIN_COOLDOWN = "skin-token-cooldown-days";

	private EntitlementResolver() {}

	/** Resolved payload ready for JSON encode / PUT. */
	public static final class ResolvedMeta {
		public final int nameColourStops;
		public final boolean allowDrinkTexture;
		public final boolean allowDrinkMessage;
		public final Integer maxAliveCharacters;
		public final int wardrobeSkinSlots;
		public final int max3dPairBytes;
		public final int skinTokenCooldownDays;
		public final List<String> skinKinds;
		public final boolean allowArmor3dHelmet;
		public final Map<String, Boolean> permissionFlags;

		public ResolvedMeta(
			int nameColourStops,
			boolean allowDrinkTexture,
			boolean allowDrinkMessage,
			Integer maxAliveCharacters,
			int wardrobeSkinSlots,
			int max3dPairBytes,
			int skinTokenCooldownDays,
			List<String> skinKinds,
			boolean allowArmor3dHelmet,
			Map<String, Boolean> permissionFlags
		) {
			this.nameColourStops = nameColourStops;
			this.allowDrinkTexture = allowDrinkTexture;
			this.allowDrinkMessage = allowDrinkMessage;
			this.maxAliveCharacters = maxAliveCharacters;
			this.wardrobeSkinSlots = wardrobeSkinSlots;
			this.max3dPairBytes = max3dPairBytes;
			this.skinTokenCooldownDays = skinTokenCooldownDays;
			this.skinKinds = List.copyOf(skinKinds);
			this.allowArmor3dHelmet = allowArmor3dHelmet;
			this.permissionFlags = Map.copyOf(permissionFlags);
		}
	}

	public static ResolvedMeta resolve(Player player) {
		int rpcColour = maxInt(player, Cache.rpcMetaGroups, Cache.rpcMetaDefaults, KEY_NAME_COLOUR_STOPS);
		int skinsColour = maxInt(player, Cache.skinsMetaGroups, Cache.skinsMetaDefaults, KEY_NAME_COLOUR_STOPS);
		int drinksColour = maxInt(player, Cache.drinksMetaGroups, Cache.drinksMetaDefaults, KEY_NAME_COLOUR_STOPS);
		int nameColourStops = Math.max(rpcColour, Math.max(skinsColour, drinksColour));

		int maxAlive = maxInt(player, Cache.rpcMetaGroups, Cache.rpcMetaDefaults, KEY_MAX_ALIVE);
		int wardrobeSlots = Math.max(
			1,
			Math.min(3, maxInt(player, Cache.rpcMetaGroups, Cache.rpcMetaDefaults, KEY_WARDROBE_SLOTS))
		);

		int pairBytes = maxInt(player, Cache.skinsMetaGroups, Cache.skinsMetaDefaults, KEY_MAX_3D_PAIR);
		if (pairBytes <= 0) {
			pairBytes = 30720;
		}
		int cooldown = skinCooldownDays(player);
		List<String> kinds = skinKinds(player);
		boolean helmet = allowArmor3dHelmet(player);
		boolean drinkTexture = allowDrinkTexture(player);
		boolean drinkMessage = allowDrinkMessage(player);

		Map<String, Boolean> flags = new LinkedHashMap<>();
		if (player != null) {
			for (String node : Cache.playerMetaSyncPermissions) {
				if (node == null || node.isBlank()) {
					continue;
				}
				String key = node.trim();
				flags.put(key, Boolean.valueOf(player.hasPermission(key)));
			}
		}

		return new ResolvedMeta(
			nameColourStops,
			drinkTexture,
			drinkMessage,
			Integer.valueOf(Math.max(1, maxAlive)),
			wardrobeSlots,
			pairBytes,
			cooldown,
			kinds,
			helmet,
			flags
		);
	}

	private static int maxInt(
		Player player,
		List<MetaGroupDefinition> groups,
		Map<String, Integer> defaults,
		String perkKey
	) {
		int value = defaults.getOrDefault(perkKey, 0);
		if (player == null) {
			return value;
		}
		for (MetaGroupDefinition group : groups) {
			if (group == null || group.getPermission().isEmpty()) {
				continue;
			}
			if (!player.hasPermission(group.getPermission())) {
				continue;
			}
			int groupValue = group.hasIntPerk(perkKey)
				? group.getIntPerk(perkKey, value)
				: value;
			value = Math.max(value, groupValue);
		}
		return value;
	}

	private static MetaGroupDefinition highestMatching(
		Player player,
		List<MetaGroupDefinition> groups
	) {
		if (player == null) {
			return null;
		}
		MetaGroupDefinition best = null;
		for (MetaGroupDefinition group : groups) {
			if (group == null || group.getPermission().isEmpty()) {
				continue;
			}
			if (!player.hasPermission(group.getPermission())) {
				continue;
			}
			if (best == null || group.getTier() > best.getTier()) {
				best = group;
			}
		}
		return best;
	}

	private static int skinCooldownDays(Player player) {
		int fallback = Cache.skinsMetaDefaults.getOrDefault(KEY_SKIN_COOLDOWN, -1);
		MetaGroupDefinition highest = highestMatching(player, Cache.skinsMetaGroups);
		if (highest == null) {
			return fallback;
		}
		MetaGroupDefinition found = null;
		int maxTier = highest.getTier();
		for (MetaGroupDefinition group : Cache.skinsMetaGroups) {
			if (group == null || group.getTier() > maxTier) {
				continue;
			}
			if (!group.hasIntPerk(KEY_SKIN_COOLDOWN)) {
				continue;
			}
			if (found == null || group.getTier() > found.getTier()) {
				found = group;
			}
		}
		if (found == null) {
			return fallback;
		}
		return found.getIntPerk(KEY_SKIN_COOLDOWN, fallback);
	}

	private static List<String> skinKinds(Player player) {
		LinkedHashSet<String> kinds = new LinkedHashSet<>();
		for (String kind : Cache.skinsMetaDefaultKinds) {
			if (kind != null && !kind.isBlank()) {
				kinds.add(kind.trim().toLowerCase(Locale.ROOT));
			}
		}
		MetaGroupDefinition highest = highestMatching(player, Cache.skinsMetaGroups);
		if (highest == null) {
			return new ArrayList<>(kinds);
		}
		int maxTier = highest.getTier();
		for (MetaGroupDefinition group : Cache.skinsMetaGroups) {
			if (group == null || group.getTier() > maxTier) {
				continue;
			}
			kinds.addAll(group.getSkinKinds());
		}
		return new ArrayList<>(kinds);
	}

	private static boolean allowArmor3dHelmet(Player player) {
		boolean fallback = Cache.skinsMetaDefaultAllowArmor3dHelmet;
		MetaGroupDefinition highest = highestMatching(player, Cache.skinsMetaGroups);
		if (highest == null) {
			return fallback;
		}
		MetaGroupDefinition found = null;
		int maxTier = highest.getTier();
		for (MetaGroupDefinition group : Cache.skinsMetaGroups) {
			if (group == null || group.getTier() > maxTier) {
				continue;
			}
			if (!group.hasAllowArmor3dHelmet()) {
				continue;
			}
			if (found == null || group.getTier() > found.getTier()) {
				found = group;
			}
		}
		if (found == null) {
			return fallback;
		}
		return found.getAllowArmor3dHelmet(fallback);
	}

	private static boolean allowDrinkTexture(Player player) {
		boolean value = Cache.drinksMetaDefaultAllowTexture;
		if (player == null) {
			return value;
		}
		for (MetaGroupDefinition group : Cache.drinksMetaGroups) {
			if (group == null || group.getPermission().isEmpty()) {
				continue;
			}
			if (!player.hasPermission(group.getPermission())) {
				continue;
			}
			if (group.hasAllowDrinkTexture() && group.getAllowDrinkTexture(false)) {
				return true;
			}
		}
		return value;
	}

	private static boolean allowDrinkMessage(Player player) {
		boolean value = Cache.drinksMetaDefaultAllowMessage;
		if (player == null) {
			return value;
		}
		for (MetaGroupDefinition group : Cache.drinksMetaGroups) {
			if (group == null || group.getPermission().isEmpty()) {
				continue;
			}
			if (!player.hasPermission(group.getPermission())) {
				continue;
			}
			if (group.hasAllowDrinkMessage() && group.getAllowDrinkMessage(false)) {
				return true;
			}
		}
		return value;
	}
}
