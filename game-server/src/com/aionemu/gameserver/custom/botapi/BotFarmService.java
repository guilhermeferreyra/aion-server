package com.aionemu.gameserver.custom.botapi;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson2.JSONObject;
import com.aionemu.gameserver.configs.main.BotApiConfig;
import com.aionemu.gameserver.model.animations.TeleportAnimation;
import com.aionemu.gameserver.model.gameobjects.Npc;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.services.instance.InstanceService;
import com.aionemu.gameserver.services.player.PlayerReviveService;
import com.aionemu.gameserver.services.teleport.TeleportService;
import com.aionemu.gameserver.utils.PositionUtil;
import com.aionemu.gameserver.utils.ThreadPoolManager;
import com.aionemu.gameserver.world.World;
import com.aionemu.gameserver.world.WorldMapInstance;

/**
 * Server-side "body" of the farming bots: enters the farm instance, hunts mobs on a
 * fixed-rate combat tick (real attacks so kill credit/XP/drops work), finishes stragglers
 * server-side, and exits when the run quota or time cap is reached. The decision layer
 * (bots/brain) only decides start/stop/chat; the loops run here.
 * <p>
 * A bot is any online character whose account name starts with {@code bot_}; its bot id
 * is that suffix (e.g. account {@code bot_vharen} -> bot id {@code vharen}).
 */
public final class BotFarmService {

	private static final Logger log = LoggerFactory.getLogger(BotFarmService.class);

	public static final String ACCOUNT_PREFIX = "bot_";

	/** Default farm target: Nocksana Training Camp. */
	public static final int DEFAULT_FARM_MAP_ID = 300030000;

	/** Instance entry point (portal_loc 3000300 of map 300030000). */
	private static final float ENTRY_X = 513f, ENTRY_Y = 668f, ENTRY_Z = 331f;
	private static final byte ENTRY_HEADING = 100;

	/** Farmable mobs of Nocksana Training Camp (statics/gates/artifacts excluded). */
	private static final Set<Integer> FARM_NPC_IDS = Set.of(256677, 256678, 256679, 256680, 256681, 256682,
		256683, 256684, 256685, 256686, 256687, 256688, 256689, 256690, 256692, 256693);

	/** Teleport closer beyond this range instead of walking (no movement pathfinding in v1). */
	private static final float APPROACH_RANGE = 6f;
	/** Attack is attempted below this range (the server enforces the real one). */
	private static final float ATTACK_RANGE = 4f;
	/** Keep hunting a target at most this far away before switching to the nearest mob. */
	private static final float STICKY_RANGE = 60f;
	/** Interval between periodic status reports pushed to the brain. */
	private static final long REPORT_INTERVAL_MS = 30_000;

	private static final ConcurrentHashMap<String, FarmSession> SESSIONS = new ConcurrentHashMap<>();

	private BotFarmService() {
	}

	// ------------------------------------------------------------------ model

	/** State of one farming run. Only touched by the combat tick + start/stop (synchronized). */
	public static final class FarmSession {
		final String botId;
		final int objectId;
		final long startedAt;
		final long startExp;
		volatile int mapId;
		volatile int instanceId;
		volatile int kills;
		Npc target;
		int hitsOnTarget;
		long lastReportAt;
		ScheduledFuture<?> task;

		FarmSession(String botId, Player player) {
			this.botId = botId;
			this.objectId = player.getObjectId();
			this.startedAt = System.currentTimeMillis();
			this.startExp = player.getCommonData().getExp();
		}

		long xpGained() {
			Player p = World.getInstance().getPlayer(objectId);
			return p == null ? 0 : Math.max(0, p.getCommonData().getExp() - startExp);
		}

		boolean running() {
			return task != null && !task.isDone();
		}
	}

	// ----------------------------------------------------------------- public

	public static boolean isBotAccount(String accountName) {
		return accountName != null && accountName.startsWith(ACCOUNT_PREFIX);
	}

	public static String botIdOf(Player player) {
		String acc = player.getAccount().getName();
		return isBotAccount(acc) ? acc.substring(ACCOUNT_PREFIX.length()) : null;
	}

	public static String botIdForAccountName(String accountName) {
		return isBotAccount(accountName) ? accountName.substring(ACCOUNT_PREFIX.length()) : null;
	}

	/** Resolves a bot id to its online character, or null when offline/unknown. */
	public static Player onlineBot(String botId) {
		if (botId == null || botId.isEmpty())
			return null;
		String accountName = ACCOUNT_PREFIX + botId;
		for (Player p : World.getInstance().getAllPlayers()) {
			if (accountName.equals(p.getAccount().getName()))
				return p;
		}
		return null;
	}

	public static FarmSession session(String botId) {
		return botId == null ? null : SESSIONS.get(botId);
	}

	/**
	 * Starts a farm run for an online bot: creates/joins the instance and teleports it to
	 * the entry point. No-op (returns the existing session) when already farming.
	 */
	public static synchronized FarmSession startRun(Player bot, int mapId) {
		String botId = botIdOf(bot);
		if (botId == null)
			throw new IllegalArgumentException(bot.getName(false) + " is not a bot character (account must start with 'bot_').");
		FarmSession existing = SESSIONS.get(botId);
		if (existing != null && existing.running())
			return existing;

		FarmSession s = new FarmSession(botId, bot);
		s.mapId = mapId;
		SESSIONS.put(botId, s);

		try {
			WorldMapInstance inst = InstanceService.getOrRegisterInstance(mapId, bot);
			inst.register(bot.getObjectId());
			s.instanceId = inst.getInstanceId();
			TeleportService.teleportTo(bot, inst.getMapId(), inst.getInstanceId(), ENTRY_X, ENTRY_Y, ENTRY_Z, ENTRY_HEADING,
				TeleportAnimation.FADE_OUT_BEAM);

			long tick = Math.max(500, BotApiConfig.FARM_COMBAT_TICK_MS);
			s.task = ThreadPoolManager.getInstance().scheduleAtFixedRate(() -> tick(s), tick, tick);
			log.info("BotFarm [{}]: run started in instance {} #{}", bot.getName(false), mapId, inst.getInstanceId());
			emit(botId, "run-started", new JSONObject()
				.fluentPut("mapId", mapId)
				.fluentPut("instanceId", inst.getInstanceId()));
		} catch (Exception e) {
			SESSIONS.remove(botId, s);
			log.warn("BotFarm [{}]: could not start run in map {}", bot.getName(false), mapId, e);
			throw e instanceof RuntimeException re ? re : new IllegalStateException(e.getMessage(), e);
		}
		return s;
	}

	/**
	 * Called from {@code PlayerLeaveWorldService.leaveWorld} when a bot character leaves the
	 * world (logout or client crash): ends its farm session so stale state (old startExp,
	 * inRun=true, scheduled tick) does not survive across logins and block the next run.
	 */
	public static void onPlayerLogout(Player p) {
		String botId = botIdOf(p);
		if (botId == null)
			return;
		FarmSession s = SESSIONS.get(botId);
		if (s == null || s.objectId != p.getObjectId())
			return;
		stopRun(botId, "logout");
	}

	/**
	 * Stops a farm run: cancels the combat loop and teleports the bot out of the instance
	 * (or to its bind point when already outside).
	 */
	public static synchronized void stopRun(String botId, String reason) {
		FarmSession s = SESSIONS.remove(botId);
		if (s == null)
			return;
		if (s.task != null)
			s.task.cancel(true);
		Player p = World.getInstance().getPlayer(s.objectId);
		if (p != null && p.isSpawned()) {
			try {
				if (p.isInInstance())
					InstanceService.moveToExitPoint(p);
				else
					TeleportService.moveToBindLocation(p);
			} catch (Exception e) {
				log.warn("BotFarm [{}]: could not exit instance", botId, e);
			}
		}
		JSONObject data = new JSONObject()
			.fluentPut("reason", reason)
			.fluentPut("kills", s.kills)
			.fluentPut("xpGained", s.xpGained())
			.fluentPut("durationMs", System.currentTimeMillis() - s.startedAt);
		log.info("BotFarm [{}]: run stopped ({}) after {} kills", botId, reason, s.kills);
		emit(botId, "run-completed", data);
	}

	// --------------------------------------------------------------- combat --

	private static void tick(FarmSession s) {
		try {
			combatTick(s);
		} catch (Throwable t) {
			// never let the scheduled task die silently
			log.warn("BotFarm [{}]: combat tick failed", s.botId, t);
		}
	}

	private static void combatTick(FarmSession s) {
		Player p = World.getInstance().getPlayer(s.objectId);
		if (p == null || !p.isSpawned())
			return; // still crossing the map (CM_LEVEL_READY pending) or gone

		if (p.isDead()) {
			PlayerReviveService.revive(p, 20, 20, true, 0);
			emit(s.botId, "death", new JSONObject().fluentPut("kills", s.kills));
			return;
		}

		long elapsed = System.currentTimeMillis() - s.startedAt;
		if (s.kills >= BotApiConfig.FARM_KILLS_PER_RUN || elapsed > BotApiConfig.FARM_MAX_RUN_MS) {
			stopRun(s.botId, s.kills >= BotApiConfig.FARM_KILLS_PER_RUN ? "quota" : "timeout");
			return;
		}

		WorldMapInstance inst = p.getPosition().getWorldMapInstance();
		if (inst == null)
			return;

		Npc target = pickTarget(p, inst, s);
		if (target == null) {
			// all mobs down (respawning) - just keep reporting
			emitReport(s);
			return;
		}

		double dist = PositionUtil.getDistance(p, target);
		if (dist > APPROACH_RANGE) {
			approach(p, target, dist);
			return;
		}

		p.setTarget(target);
		p.getController().attackTarget(target, 0, false); // registers damage in the aggro list
		s.hitsOnTarget++;
		if (s.hitsOnTarget >= BotApiConfig.FARM_FINISH_AFTER_HITS && !target.isDead() && !target.getLifeStats().isAboutToDie())
			target.getController().die(p); // real damage above -> credit + reward pipeline runs

		if (target.isDead()) {
			onKill(s, p, target);
			return;
		}
		emitReport(s);
	}

	/** Keeps the previous target while it is still useful, otherwise picks the nearest farmable mob. */
	private static Npc pickTarget(Player p, WorldMapInstance inst, FarmSession s) {
		Npc current = s.target;
		if (current != null && current.isSpawned() && !current.isDead() && FARM_NPC_IDS.contains(current.getNpcId())
			&& PositionUtil.getDistance(p, current) < STICKY_RANGE)
			return current;
		s.target = null;
		s.hitsOnTarget = 0;

		Npc best = null;
		double bestDist = Double.MAX_VALUE;
		for (Npc npc : inst.getNpcs()) {
			if (!FARM_NPC_IDS.contains(npc.getNpcId()))
				continue;
			if (!npc.isSpawned() || npc.isDead())
				continue;
			double d = PositionUtil.getDistance(p, npc);
			if (d < bestDist) {
				bestDist = d;
				best = npc;
			}
		}
		s.target = best;
		s.hitsOnTarget = 0;
		return best;
	}

	/**
	 * Closes part of the gap with an instant same-instance teleport. Moves in full 3D:
	 * Nocksana mob spots sit on terrain heights (z 331..385), so X/Y-only movement would
	 * stack the bot on a mob's column and never reach 3D attack range.
	 */
	private static void approach(Player p, Npc target, double dist) {
		double dx = target.getX() - p.getX();
		double dy = target.getY() - p.getY();
		double dz = target.getZ() - p.getZ();
		double norm = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (norm < 1e-3)
			return;
		double step = Math.min(dist - ATTACK_RANGE / 2f, Math.max(APPROACH_RANGE + 2f, dist / 2f));
		float nx = (float) (p.getX() + dx / norm * step);
		float ny = (float) (p.getY() + dy / norm * step);
		float nz = (float) (p.getZ() + dz / norm * step);
		byte h = (byte) Math.round(Math.toDegrees(Math.atan2(target.getZ() - p.getZ(), target.getX() - p.getX())) % 360);
		TeleportService.teleportTo(p, p.getPosition().getWorldMapInstance(), nx, ny, nz, h);
	}

	private static void onKill(FarmSession s, Player p, Npc target) {
		s.kills++;
		s.target = null;
		s.hitsOnTarget = 0;
		log.info("BotFarm [{}]: killed {} ({}) - {} kills this run", s.botId, target.getNpcId(),
			target.getName(), s.kills);
		emit(s.botId, "kill", new JSONObject()
			.fluentPut("npcId", target.getNpcId())
			.fluentPut("npcName", target.getName())
			.fluentPut("killsThisRun", s.kills));
	}

	// ---------------------------------------------------------------- state --

	private static void emitReport(FarmSession s) {
		long now = System.currentTimeMillis();
		if (now - s.lastReportAt < REPORT_INTERVAL_MS)
			return;
		s.lastReportAt = now;
		JSONObject data = statusData(s.botId, s.objectId);
		data.fluentPut("reason", "periodic");
		emit(s.botId, "report", data);
	}

	/** Builds the /admin/bots/status payload for a bot (live or last-known session state). */
	public static JSONObject statusData(String botId, int characterId) {
		Player p = characterId > 0 ? World.getInstance().getPlayer(characterId) : null;
		if (p == null)
			p = onlineBot(botId);

		FarmSession s = SESSIONS.get(botId);
		JSONObject j = new JSONObject();
		j.put("botId", botId);
		if (p == null) {
			j.fluentPut("online", false)
				.fluentPut("inRun", s != null && s.running())
				.fluentPut("farming", false);
			return j;
		}
		boolean running = s != null && s.running();
		j.fluentPut("online", true)
			.fluentPut("name", p.getName(false))
			.fluentPut("level", p.getLevel())
			.fluentPut("mapId", p.getWorldId())
			.fluentPut("inInstance", p.isInInstance())
			.fluentPut("hpPct", p.getLifeStats().getMaxHp() > 0 ? (int) (100.0 * p.getLifeStats().getCurrentHp() / p.getLifeStats().getMaxHp()) : 0)
			.fluentPut("inRun", running)
			.fluentPut("farming", running)
			.fluentPut("killsThisRun", s != null ? s.kills : 0)
			.fluentPut("xpThisRun", s != null ? s.xpGained() : 0);
		if (p.getPosition() != null)
			j.put("pos", new JSONObject()
				.fluentPut("x", round1(p.getX()))
				.fluentPut("y", round1(p.getY()))
				.fluentPut("z", round1(p.getZ())));
		return j;
	}

	private static float round1(float v) {
		return Math.round(v * 10f) / 10f;
	}

	/**
	 * Diagnostic snapshot for the farm loop: nearest farmable mobs, current target, and
	 * 3D distances. Lets us tell "no mobs in range", "target picked but stuck" apart from
	 * "in range but not dealing damage" without reading the GS log.
	 */
	public static JSONObject debugState(String botId) {
		JSONObject j = new JSONObject();
		j.put("botId", botId);
		Player p = onlineBot(botId);
		FarmSession s = SESSIONS.get(botId);
		if (p == null || !p.isSpawned()) {
			j.fluentPut("online", p != null)
				.fluentPut("spawned", p != null && p.isSpawned());
			return j;
		}
		j.fluentPut("online", true)
			.fluentPut("pos", new JSONObject().fluentPut("x", round1(p.getX())).fluentPut("y", round1(p.getY())).fluentPut("z", round1(p.getZ())))
			.fluentPut("dead", p.isDead())
			.fluentPut("kills", s != null ? s.kills : 0)
			.fluentPut("hitsOnTarget", s != null ? s.hitsOnTarget : 0);

		WorldMapInstance inst = p.getPosition().getWorldMapInstance();
		if (inst == null) {
			j.put("instance", null);
			return j;
		}
		j.put("instance", new JSONObject()
			.fluentPut("mapId", inst.getMapId())
			.fluentPut("instanceId", inst.getInstanceId())
			.fluentPut("npcCount", inst.getNpcs().size()));

		Npc target = s != null ? s.target : null;
		if (target != null)
			j.put("target", new JSONObject()
				.fluentPut("npcId", target.getNpcId())
				.fluentPut("name", target.getName())
				.fluentPut("dead", dead(target))
				.fluentPut("dist3d", round1((float) PositionUtil.getDistance(p, target))));

		// Nearest few farmable mobs (regardless of current target) to spot range/aggro issues.
		java.util.List<JSONObject> nearest = new java.util.ArrayList<>();
		for (Npc npc : inst.getNpcs()) {
			if (!FARM_NPC_IDS.contains(npc.getNpcId()) || !npc.isSpawned() || npc.isDead())
				continue;
			double d = PositionUtil.getDistance(p, npc);
			nearest.add(new JSONObject()
				.fluentPut("npcId", npc.getNpcId())
				.fluentPut("dist3d", round1((float) d)));
		}
		nearest.sort(java.util.Comparator.comparingDouble(o -> o.getDoubleValue("dist3d")));
		if (nearest.size() > 8)
			nearest = nearest.subList(0, 8);
		j.put("nearestMobs", nearest);
		return j;
	}

	private static boolean dead(Npc n) {
		return n.isDead();
	}

	// --------------------------------------------------------------- webhook --

	private static void emit(String botId, String type, JSONObject data) {
		BotWebhooks.post(botId, type, data);
	}
}
