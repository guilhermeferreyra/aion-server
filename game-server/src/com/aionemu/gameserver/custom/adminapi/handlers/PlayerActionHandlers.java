package com.aionemu.gameserver.custom.adminapi.handlers;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import com.alibaba.fastjson2.JSONObject;
import com.aionemu.gameserver.custom.adminapi.AdminApiServer;
import com.aionemu.gameserver.custom.adminapi.AdminJson;
import com.aionemu.gameserver.custom.adminapi.AdminPlayers;
import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.model.ChatType;
import com.aionemu.gameserver.model.Race;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.network.aion.serverpackets.SM_MESSAGE;
import com.aionemu.gameserver.network.aion.serverpackets.SM_QUIT_RESPONSE;
import com.aionemu.gameserver.services.instance.InstanceService;
import com.aionemu.gameserver.services.teleport.TeleportService;
import com.aionemu.gameserver.utils.PacketSendUtility;
import com.aionemu.gameserver.world.World;
import com.sun.net.httpserver.HttpExchange;

/**
 * Player action endpoints: notify, kick, move-to-bind-point, move-to-instance-exit, unstuck,
 * plus the server-wide broadcast. All mutating endpoints require the player to be online.
 * Teleports reuse {@link TeleportService} (same code path as the //unstuck GM command), which
 * runs with {@code TeleportAnimation.NONE}, so positions are updated synchronously and can be
 * snapshotted right after the call.
 */
public final class PlayerActionHandlers {

	private static final String ADMIN_NAME = "Administrator";

	private PlayerActionHandlers() {
	}

	// ------------------------------------------------------------------ endpoints

	public static void notifyPlayer(HttpExchange ex, Map<String, String> query, JSONObject body) throws IOException {
		String message = requiredText(body, "message"); // validate input before touching game state
		Player player = onlinePlayer(body);

		PacketSendUtility.sendPacket(player, new SM_MESSAGE(0, ADMIN_NAME, clip(message), ChatType.GOLDEN_YELLOW));

		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("recipientCharacterId", player.getObjectId());
		fields.put("recipientName", player.getName());
		fields.put("delivered", "online");
		AdminJson.send(ex, 200, AdminJson.ok(fields));
	}

	public static void kickPlayer(HttpExchange ex, Map<String, String> query, JSONObject body) throws IOException {
		Player player = onlinePlayer(body);
		int characterId = player.getObjectId();
		String name = player.getName();
		String reason = AdminJson.optString(body, "reason").trim();

		// tell the client why, then disconnect (same pattern as char bans)
		String text = reason.isEmpty() ? "You have been disconnected by an administrator." : "Disconnected by an administrator: " + reason;
		PacketSendUtility.sendPacket(player, new SM_MESSAGE(0, ADMIN_NAME, clip(text), ChatType.GOLDEN_YELLOW));
		player.getClientConnection().close(new SM_QUIT_RESPONSE());

		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("recipientCharacterId", characterId);
		fields.put("recipientName", name);
		fields.put("disconnected", true);
		AdminJson.send(ex, 200, AdminJson.ok(fields));
	}

	public static void moveToBindPoint(HttpExchange ex, Map<String, String> query, JSONObject body) throws IOException {
		Player player = onlinePlayer(body);
		Map<String, Object> from = currentPosition(player);

		TeleportService.moveToBindLocation(player);

		Map<String, Object> fields = new LinkedHashMap<>();
		fillMovedFields(fields, player, from, "bind-point");
		AdminJson.send(ex, 200, AdminJson.ok(fields));
	}

	public static void moveToInstanceExit(HttpExchange ex, Map<String, String> query, JSONObject body) throws IOException {
		Player player = onlinePlayer(body);
		if (!player.isInInstance())
			throw AdminApiServer.HttpResponses.conflict(player.getName() + " is not inside an instance.");

		Map<String, Object> from = currentPosition(player);
		InstanceService.moveToExitPoint(player);

		Map<String, Object> fields = new LinkedHashMap<>();
		fillMovedFields(fields, player, from, "instance-exit");
		AdminJson.send(ex, 200, AdminJson.ok(fields));
	}

	public static void unstuckPlayer(HttpExchange ex, Map<String, String> query, JSONObject body) throws IOException {
		Player player = onlinePlayer(body);

		// where moveToBindLocation will take the player
		var bindPoint = player.getBindPoint();
		var spawn = bindPoint == null ? DataManager.PLAYER_INITIAL_DATA.getSpawnLocation(player.getRace()) : null;
		int destWorld = bindPoint != null ? bindPoint.getMapId() : spawn.getMapId();
		float dx = bindPoint != null ? bindPoint.getX() : spawn.getX();
		float dy = bindPoint != null ? bindPoint.getY() : spawn.getY();
		float dz = bindPoint != null ? bindPoint.getZ() : spawn.getZ();

		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("recipientCharacterId", player.getObjectId());
		fields.put("recipientName", player.getName());
		Map<String, Object> from = currentPosition(player);

		if (!isMoved(from, destWorld, dx, dy, dz)) {
			// already standing at the safe spot - nothing to do
			fields.put("moved", false);
			fields.put("action", "none");
			fields.put("from", from);
			fields.put("destination", from);
			AdminJson.send(ex, 200, AdminJson.ok(fields));
			return;
		}

		TeleportService.moveToBindLocation(player);
		fields.put("moved", true);
		fields.put("action", bindPoint != null ? "bind-point" : "spawn");
		fields.put("from", from);
		Map<String, Object> to = currentPosition(player);
		fields.put("to", to);
		fields.put("destination", to);
		AdminJson.send(ex, 200, AdminJson.ok(fields));
	}

	public static void broadcastMessage(HttpExchange ex, Map<String, String> query, JSONObject body) throws IOException {
		String scope = AdminJson.optString(body, "scope").trim().toLowerCase();
		String message = requiredText(body, "message");

		Race raceFilter;
		switch (scope) {
			case "all" -> raceFilter = null;
			case "elyos" -> raceFilter = Race.ELYOS;
			case "asmodians" -> raceFilter = Race.ASMODIANS;
			default -> throw AdminApiServer.HttpResponses.badRequest("scope must be one of: all, elyos, asmodians");
		}

		var text = new SM_MESSAGE(1, ADMIN_NAME, clip(message), ChatType.YELLOW_CENTER);
		PacketSendUtility.broadcastToWorld(text, raceFilter == null ? p -> true : p -> p.getRace() == raceFilter);

		int deliveredCount = 0;
		for (Player p : World.getInstance().getAllPlayers())
			if (raceFilter == null || p.getRace() == raceFilter)
				deliveredCount++;

		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("scope", scope);
		fields.put("deliveredCount", deliveredCount);
		AdminJson.send(ex, 200, AdminJson.ok(fields));
	}

	// ------------------------------------------------------------------ helpers

	/**
	 * Looks up the recipient; 404 when the character does not exist at all, 409 when it exists
	 * but is not logged in (actions are live-only in P2).
	 */
	private static Player onlinePlayer(JSONObject body) throws IOException {
		Integer characterId = AdminJson.optInt(body, "recipientCharacterId");
		if (characterId == null)
			throw AdminApiServer.HttpResponses.badRequest("recipientCharacterId is required.");

		Player player = World.getInstance().getPlayer(characterId);
		if (player != null)
			return player;

		boolean exists = AdminPlayers.characterExists(characterId);
		throw exists
			? AdminApiServer.HttpResponses.conflict("Character " + characterId + " is not online.")
			: AdminApiServer.HttpResponses.notFound("Character " + characterId + " not found.");
	}

	/**
	 * LiveBindDestination of the player's current position.
	 */
	private static Map<String, Object> currentPosition(Player player) {
		return AdminPlayers.destination("current", player.getWorldId(), player.getInstanceId(), player.getX(), player.getY(), player.getZ(), player.getHeading());
	}

	private static void fillMovedFields(Map<String, Object> fields, Player player, Map<String, Object> from, String action) {
		fields.put("recipientCharacterId", player.getObjectId());
		fields.put("recipientName", player.getName());
		boolean moved = isMoved(from, player.getWorldId(), player.getX(), player.getY(), player.getZ());
		fields.put("moved", moved);
		fields.put("action", action);
		fields.put("from", from);
		Map<String, Object> to = currentPosition(player);
		fields.put("to", to);
		fields.put("destination", to);
	}

	/**
	 * Teleport results are synchronous (TeleportAnimation.NONE), so a small epsilon tells us
	 * whether anything actually changed.
	 */
	private static boolean isMoved(Map<String, Object> from, int toWorldId, float x, float y, float z) {
		Object w = from.get("worldId");
		if (!(w instanceof Integer wi) || wi != toWorldId)
			return true;
		return delta(from.get("x"), x) > 0.5f || delta(from.get("y"), y) > 0.5f || delta(from.get("z"), z) > 0.5f;
	}

	private static float delta(Object fromValue, float toValue) {
		return Math.abs(((Number) fromValue).floatValue() - toValue);
	}

	/**
	 * The client drops chat packets above this size, so keep messages within the limit.
	 */
	private static String clip(String message) {
		return message.length() <= SM_MESSAGE.MESSAGE_SIZE_LIMIT ? message : message.substring(0, SM_MESSAGE.MESSAGE_SIZE_LIMIT);
	}

	private static String requiredText(JSONObject body, String key) throws IOException {
		String value = AdminJson.optString(body, key).trim();
		if (value.isEmpty())
			throw AdminApiServer.HttpResponses.badRequest(key + " is required.");
		return value;
	}
}
