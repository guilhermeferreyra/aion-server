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
}
