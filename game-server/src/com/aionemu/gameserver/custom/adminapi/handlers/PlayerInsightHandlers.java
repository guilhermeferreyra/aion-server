package com.aionemu.gameserver.custom.adminapi.handlers;

import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.aionemu.commons.database.DB;
import com.alibaba.fastjson2.JSONObject;
import com.aionemu.gameserver.custom.adminapi.AdminApiServer;
import com.aionemu.gameserver.custom.adminapi.AdminJson;
import com.aionemu.gameserver.custom.adminapi.AdminPlayers;
import com.aionemu.gameserver.model.account.Account;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.gameobjects.player.PlayerCommonData;
import com.aionemu.gameserver.services.AccountService;
import com.aionemu.gameserver.world.World;
import com.sun.net.httpserver.HttpExchange;

/**
 * Read-only admin endpoints: capabilities, online players, account/player/storage state.
 */
public final class PlayerInsightHandlers {

	private PlayerInsightHandlers() {
	}

	public static void capabilities(HttpExchange ex, Map<String, String> query, JSONObject body) throws IOException {
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("service", "aion-server-admin-api");
		fields.put("apiVersion", AdminApiServer.apiVersion());
		fields.put("onlinePlayerCount", World.getInstance().getAllPlayers().size());
		fields.put("endpoints", AdminApiServer.capabilityList());
		AdminJson.send(ex, 200, AdminJson.ok(fields));
	}

	public static void onlinePlayers(HttpExchange ex, Map<String, String> query, JSONObject body) throws IOException {
		List<Player> snapshot = new ArrayList<>(World.getInstance().getAllPlayers());
		List<Map<String, Object>> players = new ArrayList<>(snapshot.size());
		for (Player p : snapshot)
			players.add(AdminPlayers.playerSnapshot(p));

		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("count", players.size());
		fields.put("players", players);
		AdminJson.send(ex, 200, AdminJson.ok(fields));
	}

	public static void accountState(HttpExchange ex, Map<String, String> query, JSONObject body) throws IOException {
		int accountId = requiredInt(query, "accountId");

		// the game db only knows accounts that own characters; the login db is the source of truth
		var meta = AdminPlayers.accountMeta(null, accountId);
		if (meta.name().isEmpty())
			throw AdminApiServer.HttpResponses.notFound("Account " + accountId + " not found.");

		Account account = AccountService.loadAccount(accountId);

		List<Player> onlineChars = new ArrayList<>();
		for (Player p : new ArrayList<>(World.getInstance().getAllPlayers())) {
			if (p.getAccount() != null && p.getAccount().getId() == accountId)
				onlineChars.add(p);
		}
		List<Map<String, Object>> players = new ArrayList<>(onlineChars.size());
		for (Player p : onlineChars)
			players.add(AdminPlayers.playerSnapshot(p));

		// an online character carries the login-server-provided account object; prefer it
		if (!onlineChars.isEmpty())
			meta = AdminPlayers.accountMeta(onlineChars.get(0).getAccount(), accountId);

		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("accountId", accountId);
		fields.put("accountName", meta.name());
		fields.put("loaded", true);
		fields.put("online", !players.isEmpty());
		fields.put("onlineCount", players.size());
		fields.put("players", players);
		fields.put("warehouse", AdminPlayers.warehouseSnapshotForAccount(account));
		AdminJson.send(ex, 200, AdminJson.ok(fields));
	}

	public static void playerState(HttpExchange ex, Map<String, String> query, JSONObject body) throws IOException {
		int characterId = requiredInt(query, "recipientCharacterId");
		Player player = World.getInstance().getPlayer(characterId);
		Map<String, Object> fields = new LinkedHashMap<>();
		if (player != null) {
			fields.put("online", true);
			fields.put("recipientCharacterId", characterId);
			fields.put("recipientName", player.getName());
			fields.put("player", AdminPlayers.playerSnapshot(player));
			AdminJson.send(ex, 200, AdminJson.ok(fields));
			return;
		}

		PlayerCommonData pcd = loadCommonData(characterId);
		if (pcd == null)
			throw AdminApiServer.HttpResponses.notFound("Character " + characterId + " not found.");

		fields.put("online", false);
		fields.put("recipientCharacterId", characterId);
		fields.put("recipientName", pcd.getName());
		fields.put("lastKnown", AdminPlayers.lastKnownSnapshot(characterId, pcd));
		AdminJson.send(ex, 200, AdminJson.ok(fields));
	}

	public static void playerStorageState(HttpExchange ex, Map<String, String> query, JSONObject body) throws IOException {
		int characterId = requiredInt(query, "recipientCharacterId");
		Player player = World.getInstance().getPlayer(characterId);
		boolean online = player != null;

		PlayerCommonData pcd = online ? player.getCommonData() : loadCommonData(characterId);
		if (pcd == null && !online)
			throw AdminApiServer.HttpResponses.notFound("Character " + characterId + " not found.");

		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("online", online);
		fields.put("recipientCharacterId", characterId);
		fields.put("recipientName", pcd != null ? pcd.getName() : "");
		fields.put("position", online ? AdminPlayers.destination("current", player.getWorldId(), player.getInstanceId(), player.getX(), player.getY(), player.getZ(),
			player.getHeading()) : AdminPlayers.positionFromCommonData(pcd));
		fields.put("inventory", AdminPlayers.inventorySnapshot(player, pcd));
		fields.put("warehouse", AdminPlayers.warehouseSnapshot(player, pcd));
		fields.put("mailbox", mailboxSnapshot(characterId));
		AdminJson.send(ex, 200, AdminJson.ok(fields));
	}

	// ------------------------------------------------------------------ helpers

	private static PlayerCommonData loadCommonData(int characterId) {
		var playerAccData = AccountService.loadPlayerAccountData(characterId);
		return playerAccData != null ? playerAccData.getPlayerCommonData() : null;
	}

	private static int requiredInt(Map<String, String> query, String key) throws IOException {
		String raw = query.get(key);
		if (raw == null || raw.isBlank())
			throw AdminApiServer.HttpResponses.badRequest(key + " query parameter is required.");
		try {
			return Integer.parseInt(raw.trim());
		} catch (NumberFormatException e) {
			throw AdminApiServer.HttpResponses.badRequest(key + " must be an integer.");
		}
	}

	/**
	 * Mail counter straight from the database so it works for online and offline characters alike.
	 */
	static Map<String, Object> mailboxSnapshot(int characterId) {
		final long[] counts = new long[4]; // total, unread, unread express, unread black cloud
		boolean ok = DB.select("SELECT COUNT(*) AS total, SUM(unread) AS unread, SUM(unread AND express = 1) AS unread_express, SUM(unread AND express = 2) AS unread_blackcloud FROM mail WHERE mail_recipient_id = ?",
			new com.aionemu.commons.database.ParamReadStH() {

				@Override
				public void setParams(PreparedStatement stmt) throws SQLException {
					stmt.setInt(1, characterId);
				}

				@Override
				public void handleRead(ResultSet rset) throws SQLException {
					if (rset.next()) {
						counts[0] = rset.getLong(1);
						counts[1] = rset.getLong(2);
						counts[2] = rset.getLong(3);
						counts[3] = rset.getLong(4);
					}
				}
			});
		if (!ok)
			throw AdminApiServer.HttpResponses.badRequest("Failed to read mailbox counters for character " + characterId);

		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("totalCount", counts[0]);
		snapshot.put("unreadCount", counts[1]);
		snapshot.put("unreadExpressCount", counts[2]);
		snapshot.put("unreadBlackCloudCount", counts[3]);
		return snapshot;
	}
}
