package net.tfminecraft.tfmcweb.entitlements;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import net.tfminecraft.tfmcweb.TFMCWeb;
import net.tfminecraft.tfmcweb.api.ProvinceSystemClient;
import net.tfminecraft.tfmcweb.api.ProvinceSystemClient.SimpleResult;
import net.tfminecraft.tfmcweb.entitlements.EntitlementResolver.ResolvedMeta;

/**
 * Push resolved web entitlements to ProvinceSystem on join, reload and rank change (fail-soft).
 */
public final class PlayerMetaSyncService {

	/** Last body sent per player; a failed push drops its entry so the next one retries. */
	private static final Map<UUID, String> lastSent = new ConcurrentHashMap<>();

	private PlayerMetaSyncService() {}

	/** Resolve on the calling thread (prefer main), then PUT async. */
	public static void pushForPlayer(Player player) {
		push(player, true);
	}

	/** Like {@link #pushForPlayer} but skips the PUT when nothing changed since the last push. */
	public static void pushIfChanged(Player player) {
		push(player, false);
	}

	private static void push(Player player, boolean force) {
		if (player == null || TFMCWeb.plugin == null) {
			return;
		}
		UUID playerUuid = player.getUniqueId();
		ResolvedMeta meta = EntitlementResolver.resolve(player);
		String body = toJson(playerUuid, meta);
		String previous = lastSent.put(playerUuid, body);
		if (!force && body.equals(previous)) {
			return;
		}
		Bukkit.getScheduler().runTaskAsynchronously(
			TFMCWeb.plugin,
			() -> {
				SimpleResult result = ProvinceSystemClient.putRpcPlayerMeta(body);
				if (!result.ok) {
					lastSent.remove(playerUuid, body);
					TFMCWeb.plugin.getLogger().warning(
						"[player-meta] push failed for " + playerUuid + ": " + result.error
					);
				}
			}
		);
	}

	public static void pushAsync(UUID playerUuid) {
		if (playerUuid == null || TFMCWeb.plugin == null) {
			return;
		}
		Bukkit.getScheduler().runTask(TFMCWeb.plugin, () -> {
			Player online = Bukkit.getPlayer(playerUuid);
			if (online != null && online.isOnline()) {
				pushForPlayer(online);
			}
		});
	}

	public static void pushAllOnlineAsync() {
		if (TFMCWeb.plugin == null) {
			return;
		}
		Bukkit.getScheduler().runTask(TFMCWeb.plugin, () -> {
			for (Player player : Bukkit.getOnlinePlayers()) {
				if (player != null) {
					pushForPlayer(player);
				}
			}
		});
	}

	static String toJson(UUID playerUuid, ResolvedMeta meta) {
		StringBuilder sb = new StringBuilder(512);
		sb.append("{\"player_uuid\":\"").append(escape(playerUuid.toString())).append('"');
		sb.append(",\"realm_id\":\"").append(escape(TFMCWeb.getRealmId())).append('"');
		sb.append(",\"name_colour_stops\":").append(meta.nameColourStops);
		sb.append(",\"allow_drink_texture\":").append(meta.allowDrinkTexture);
		sb.append(",\"allow_drink_message\":").append(meta.allowDrinkMessage);
		if (meta.maxAliveCharacters != null) {
			sb.append(",\"max_alive_characters\":").append(meta.maxAliveCharacters.intValue());
		}
		sb.append(",\"wardrobe_skin_slots\":").append(meta.wardrobeSkinSlots);
		sb.append(",\"max_3d_pair_bytes\":").append(meta.max3dPairBytes);
		sb.append(",\"skin_token_cooldown_days\":").append(meta.skinTokenCooldownDays);
		sb.append(",\"skin_kinds\":[");
		boolean first = true;
		for (String kind : meta.skinKinds) {
			if (kind == null || kind.isBlank()) {
				continue;
			}
			if (!first) {
				sb.append(',');
			}
			first = false;
			sb.append('"').append(escape(kind.trim().toLowerCase(java.util.Locale.ROOT))).append('"');
		}
		sb.append(']');
		sb.append(",\"allow_armor_3d_helmet\":").append(meta.allowArmor3dHelmet);
		sb.append(",\"permission_flags\":{");
		first = true;
		for (Map.Entry<String, Boolean> entry : meta.permissionFlags.entrySet()) {
			if (entry.getKey() == null || entry.getKey().isBlank()) {
				continue;
			}
			if (!first) {
				sb.append(',');
			}
			first = false;
			sb.append('"').append(escape(entry.getKey())).append("\":")
				.append(Boolean.TRUE.equals(entry.getValue()));
		}
		sb.append("}}");
		return sb.toString();
	}

	private static String escape(String raw) {
		String encoded = new com.google.gson.Gson().toJson(raw);
		return encoded.substring(1, encoded.length() - 1);
	}
}
