package com.aionemu.gameserver.configs.main;

import com.aionemu.commons.configuration.Property;

/**
 * Configuration for the LLM bot ecosystem (bots that farm hunts/instances).
 * <p>
 * Bots are characters whose login account name starts with {@code bot_} (see
 * {@code scripts/create-bots.sql}). The decision brain (Node service in
 * {@code bots/brain}) talks to this game server through the admin API routes
 * {@code /admin/bots/*} and receives events via an HTTP webhook.
 */
public class BotApiConfig {

	/**
	 * Enables the bot farming engine and the {@code /admin/bots/*} routes.
	 */
	@Property(key = "gameserver.botapi.enabled", defaultValue = "false")
	public static boolean ENABLED;

	/**
	 * Webhook the game server POSTs bot events to ({@code {botId, type, data}}). The
	 * brain listens on its own HTTP port; point this at its {@code /event} route.
	 */
	@Property(key = "gameserver.botapi.webhook.url", defaultValue = "http://127.0.0.1:7781/event")
	public static String WEBHOOK_URL;

	/**
	 * Master switch for the webhook (the admin API routes work without it).
	 */
	@Property(key = "gameserver.botapi.webhook.enabled", defaultValue = "true")
	public static boolean WEBHOOK_ENABLED;

	/**
	 * Kills per run before the bot leaves the instance on its own.
	 */
	@Property(key = "gameserver.botapi.farm.kills_per_run", defaultValue = "12")
	public static int FARM_KILLS_PER_RUN;

	/**
	 * Interval between combat ticks of a farming bot, in milliseconds.
	 */
	@Property(key = "gameserver.botapi.farm.combat_tick_ms", defaultValue = "1500")
	public static long FARM_COMBAT_TICK_MS;

	/**
	 * After this many real hits on one target the bot finishes it off server-side
	 * (guarantees kill credit even against mobs with much HP).
	 */
	@Property(key = "gameserver.botapi.farm.finish_after_hits", defaultValue = "8")
	public static int FARM_FINISH_AFTER_HITS;

	/**
	 * Hard time cap of a single farm run, in milliseconds.
	 */
	@Property(key = "gameserver.botapi.farm.max_run_ms", defaultValue = "1200000")
	public static long FARM_MAX_RUN_MS;

	/**
	 * Distance covered by one walking step per combat tick (meters). The bot closes
	 * the gap to a target in several small steps, each one broadcast as SM_MOVE to
	 * nearby players (visible walking instead of teleport-blink). 0 = legacy teleport.
	 */
	@Property(key = "gameserver.botapi.farm.walk_step_m", defaultValue = "7")
	public static float FARM_WALK_STEP_M;

	/**
	 * HP potion granted to bots at run start (Life Potion lv30). Set 0 to disable.
	 */
	@Property(key = "gameserver.botapi.farm.potion_item_id", defaultValue = "162000004")
	public static int FARM_POTION_ITEM_ID;

	/**
	 * Bots drink their potion when below this HP percentage.
	 */
	@Property(key = "gameserver.botapi.farm.potion_min_hp_pct", defaultValue = "35")
	public static int FARM_POTION_MIN_HP_PCT;

	/**
	 * Potion count a bot is kept at (topped up on run start).
	 */
	@Property(key = "gameserver.botapi.farm.potion_stock", defaultValue = "25")
	public static int FARM_POTION_STOCK;

	/**
	 * Every Nth basic hit the bot casts a random basic class skill (if learned, off
	 * cooldown and with MP to spare). 0 = skills disabled.
	 */
	@Property(key = "gameserver.botapi.farm.skill_every_n_hits", defaultValue = "4")
	public static int FARM_SKILL_EVERY_N_HITS;
}
