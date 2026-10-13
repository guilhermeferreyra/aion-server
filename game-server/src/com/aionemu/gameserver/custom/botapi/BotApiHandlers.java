package com.aionemu.gameserver.custom.botapi;

import java.io.IOException;
import java.util.Map;

import com.aionemu.gameserver.custom.adminapi.AdminApiServer;
import com.aionemu.gameserver.custom.adminapi.AdminJson;
import com.aionemu.gameserver.model.ChatType;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.team.group.PlayerGroupService;
import com.aionemu.gameserver.network.aion.serverpackets.SM_MESSAGE;
import com.aionemu.gameserver.services.NameRestrictionService;
import com.aionemu.gameserver.services.player.PlayerChatService;
import com.aionemu.gameserver.utils.ChatUtil;
import com.aionemu.gameserver.utils.PacketSendUtility;
import com.aionemu.gameserver.world.World;
import com.alibaba.fastjson2.JSONObject;
import com.sun.net.httpserver.HttpExchange;

/**
 * Routes under {@code /admin/bots/*} consumed by the bot brain (see {@code bots/brain}).
 * The brain decides, these routes act: farm run start/stop, live status and chat.
 */
public final class BotApiHandlers {

	private BotApiHandlers() {
	}

	// ------------------------------------------------------------------ chat

	public static void chat(HttpExchange ex, Map<String, String> query, JSONObject body) throws IOException {
		Player bot = requireOnlineBot(body);
		String channel = body.getString("channel");
		String text = NameRestrictionService.filterMessage(String.valueOf(body.getString("text") == null ? "" : body.getString("text")));
		if (text.isEmpty())
			throw AdminApiServer.HttpResponses.badRequest("text is required.");

		if ("whisper".equalsIgnoreCase(channel)) {
			String targetName = body.getString("target");
			if (targetName == null || targetName.isEmpty())
				throw AdminApiServer.HttpResponses.badRequest("whisper requires a target.");
			Player target = World.getInstance().getPlayer(targetName);
			if (target == null)
				throw AdminApiServer.HttpResponses.notFound("Whisper target '" + targetName + "' is not online.");
			PacketSendUtility.sendPacket(target, new SM_MESSAGE(bot, text, ChatType.WHISPER));
			PlayerChatService.logWhisper(bot, target, text);
			AdminJson.send(ex, 200, AdminJson.ok(Map.of("sent", true, "channel", "whisper", "to", target.getName(false))));
			return;
		}

		// shout (default): broadcast to everyone who can see the bot
		PacketSendUtility.broadcastPacket(bot, new SM_MESSAGE(bot, text, ChatType.SHOUT), true, p -> true);
		PlayerChatService.logMessage(bot, ChatType.SHOUT, text);
		AdminJson.send(ex, 200, AdminJson.ok(Map.of("sent", true, "channel", "shout")));
	}

	// ------------------------------------------------------------------ farm

	public static void startRun(HttpExchange ex, Map<String, String> query, JSONObject body) throws IOException {
		Player bot = requireOnlineBot(body);
		Integer mapId = AdminJson.optInt(body, "instanceMapId");
		int dest = mapId != null ? mapId : BotFarmService.DEFAULT_FARM_MAP_ID;

		BotFarmService.FarmSession s = BotFarmService.startRun(bot, dest);
		AdminJson.send(ex, 200, AdminJson.ok(Map.of(
			"started", true,
			"botId", BotFarmService.botIdOf(bot),
			"mapId", s.mapId,
			"instanceId", s.instanceId)));
	}

	public static void stopFarm(HttpExchange ex, Map<String, String> query, JSONObject body) throws IOException {
		Player bot = requireOnlineBot(body);
		String botId = BotFarmService.botIdOf(bot);
		boolean wasRunning = BotFarmService.session(botId) != null;
		BotFarmService.stopRun(botId, "stopped-by-brain");
		AdminJson.send(ex, 200, AdminJson.ok(Map.of(
			"stopped", true,
			"wasFarming", wasRunning)));
	}

	/**
	 * POST /admin/bots/invite-to-group - makes one online player send a group invitation to another
	 * online player (same flow as a player's party UI). Body: {@code inviterCharacterId} (any online
	 * character) + {@code targetName}. Headless bots auto-accept; real clients see the usual dialog.
	 */
	public static void inviteToGroup(HttpExchange ex, Map<String, String> query, JSONObject body) throws IOException {
		int inviterId = body.getIntValue("inviterCharacterId", 0);
		String targetName = body.getString("targetName");
		if (inviterId <= 0 || targetName == null || targetName.isEmpty())
			throw AdminApiServer.HttpResponses.badRequest("inviterCharacterId and targetName are required.");
		Player inviter = World.getInstance().getPlayer(inviterId);
		if (inviter == null)
			throw AdminApiServer.HttpResponses.conflict("Inviter is not online (characterId=" + inviterId + ").");
		Player target = World.getInstance().getPlayer(ChatUtil.getRealCharName(targetName));
		if (target == null)
			throw AdminApiServer.HttpResponses.conflict("Target is not online (name=\"" + targetName + "\").");
		PlayerGroupService.inviteToGroup(inviter, target);
		AdminJson.send(ex, 200, AdminJson.ok(Map.of(
			"invited", true,
			"inviter", inviter.getName(false),
			"target", target.getName(false))));
	}

	// ---------------------------------------------------------------- status

	public static void status(HttpExchange ex, Map<String, String> query, JSONObject body) throws IOException {
		String botId = body.getString("botId");
		Integer characterId = AdminJson.optInt(body, "characterId");
		if (botId == null || botId.isEmpty()) {
			if (characterId == null)
				throw AdminApiServer.HttpResponses.badRequest("botId (or characterId) is required.");
			Player p = World.getInstance().getPlayer(characterId);
			botId = p == null ? null : BotFarmService.botIdOf(p);
			if (botId == null)
				throw AdminApiServer.HttpResponses.notFound("Character " + characterId + " is not a bot.");
		}
		JSONObject data = BotFarmService.statusData(botId, characterId == null ? 0 : characterId);
		AdminJson.send(ex, 200, AdminJson.ok(Map.of("bot", data)));
	}

	// -------------------------------------------------------------- helpers --

	/** GET /admin/bots/farm-debug?botId=X - diagnostics for the farm combat loop. */
	public static void farmDebug(HttpExchange ex, Map<String, String> query, JSONObject body) throws IOException {
		String botId = query.get("botId");
		if (botId == null || botId.isEmpty())
			throw AdminApiServer.HttpResponses.badRequest("botId query param is required.");
		JSONObject data = BotFarmService.debugState(botId);
		AdminJson.send(ex, 200, AdminJson.ok(Map.of("debug", data)));
	}

	/** Resolves the requesting bot from {@code botId} or {@code characterId}; it must be online and a bot. */
	private static Player requireOnlineBot(JSONObject body) throws IOException {
		String botId = body.getString("botId");
		Player p = botId == null || botId.isEmpty() ? null : BotFarmService.onlineBot(botId);
		if (p == null && body.getIntValue("characterId", 0) > 0) {
			p = World.getInstance().getPlayer(body.getIntValue("characterId"));
		}
		if (p == null)
			throw AdminApiServer.HttpResponses.conflict("Bot is not online (botId=" + botId + ").");
		if (!BotFarmService.isBotAccount(p.getAccount().getName()))
			throw AdminApiServer.HttpResponses.badRequest(p.getName(false) + " is not a bot character (account must start with 'bot_').");
		return p;
	}
}
