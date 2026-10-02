package com.aionemu.gameserver.configs.main;

import com.aionemu.commons.configuration.Property;

/**
 * Configuration for the HTTP admin API consumed by the web portal.
 */
public class AdminApiConfig {

	/**
	 * Enables the HTTP admin API on this game server.
	 */
	@Property(key = "gameserver.admin.api.enabled", defaultValue = "false")
	public static boolean ENABLED;

	/**
	 * Address the admin API listens on. Keep it on loopback unless the portal runs on another machine.
	 */
	@Property(key = "gameserver.admin.api.bind", defaultValue = "127.0.0.1")
	public static String BIND;

	/**
	 * Port the admin API listens on.
	 */
	@Property(key = "gameserver.admin.api.port", defaultValue = "7780")
	public static int PORT;

	/**
	 * Shared secret sent by the portal in the {@code x-admin-token} request header.
	 */
	@Property(key = "gameserver.admin.api.token", defaultValue = "")
	public static String TOKEN;
}
