package com.aionemu.gameserver.custom.adminapi;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.configs.main.AdminApiConfig;
import com.aionemu.gameserver.custom.adminapi.handlers.ExpressMailHandlers;
import com.aionemu.gameserver.custom.adminapi.handlers.ItemOpsHandlers;
import com.aionemu.gameserver.custom.adminapi.handlers.PlayerActionHandlers;
import com.aionemu.gameserver.custom.adminapi.handlers.PlayerInsightHandlers;
import com.aionemu.gameserver.custom.adminapi.handlers.StorageRefreshHandlers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * HTTP admin API consumed by the web portal. Serves {@code /admin/*} on a JDK built-in
 * {@link HttpServer}; every request must carry the shared secret in the
 * {@code x-admin-token} header. Read endpoints, live player actions, broadcasts, storage
 * refresh, express mail and item ops are implemented; the remaining routes stay registered
 * and answer 501 until their phase lands.
 */
public final class AdminApiServer {

	private static final Logger log = LoggerFactory.getLogger(AdminApiServer.class);
	private static final int API_VERSION = 1;
	private static volatile HttpServer server;

	private AdminApiServer() {
	}

	public static synchronized void start() {
		if (!AdminApiConfig.ENABLED || server != null)
			return;
		try {
			server = HttpServer.create(new InetSocketAddress(AdminApiConfig.BIND, AdminApiConfig.PORT), 0);
		} catch (BindException e) {
			log.error("Admin API could not bind {}:{} - admin API stays disabled.", AdminApiConfig.BIND, AdminApiConfig.PORT, e);
			return;
		} catch (Exception e) {
			log.error("Admin API failed to start - admin API stays disabled.", e);
			server = null;
			return;
		}
		server.createContext("/admin", AdminApiServer::dispatch);
		server.setExecutor(Executors.newFixedThreadPool(4));
		server.start();
		log.info("Admin API listening on http://{}:{}/admin", AdminApiConfig.BIND, AdminApiConfig.PORT);
	}

	public static synchronized void stop() {
		HttpServer s = server;
		if (s != null) {
			s.stop(0);
			server = null;
			log.info("Admin API stopped.");
		}
	}

	// ------------------------------------------------------------------ routing

	@FunctionalInterface
	private interface Handler {
		void handle(HttpExchange ex, Map<String, String> query, com.alibaba.fastjson2.JSONObject body) throws IOException;
	}

	private record Route(String method, String path, String category, boolean mutates, List<String> allowedTargets, List<String> allowedScopes,
		String description, Handler handler) {
	}

	private static final List<Route> ROUTES = List.of(
		new Route("GET", "/admin/capabilities", "meta", false, List.of(), List.of(), "Lists this API surface with capability metadata.", PlayerInsightHandlers::capabilities),
		new Route("GET", "/admin/online-players", "read", false, List.of(), List.of(), "Lists every player currently online.", PlayerInsightHandlers::onlinePlayers),
		new Route("GET", "/admin/account-state", "read", false, List.of(), List.of(), "Account summary: online characters and account warehouse snapshot.", PlayerInsightHandlers::accountState),
		new Route("GET", "/admin/player-state", "read", false, List.of(), List.of(), "Live state of one character, with last known position when offline.", PlayerInsightHandlers::playerState),
		new Route("GET", "/admin/player-storage-state", "read", false, List.of(), List.of(), "Full storage snapshot (inventory, warehouses, mailbox) of one character.", PlayerInsightHandlers::playerStorageState),

		new Route("POST", "/admin/notify-player", "player-actions", true, List.of(), List.of(), "Sends an admin message to an online player.", PlayerActionHandlers::notifyPlayer),
		new Route("POST", "/admin/kick-player", "player-actions", true, List.of(), List.of(), "Disconnects an online player.", PlayerActionHandlers::kickPlayer),
		new Route("POST", "/admin/move-to-bind-point", "player-actions", true, List.of(), List.of(), "Teleports an online player to its bind point.", PlayerActionHandlers::moveToBindPoint),
		new Route("POST", "/admin/move-to-instance-exit", "player-actions", true, List.of(), List.of(), "Moves an online player to the exit of its current instance.", PlayerActionHandlers::moveToInstanceExit),
		new Route("POST", "/admin/unstuck-player", "player-actions", true, List.of(), List.of(), "Frees a player stuck in the void or on geometry.", PlayerActionHandlers::unstuckPlayer),

		new Route("POST", "/admin/refresh-mailbox", "storage-refresh", true, List.of(), List.of(), "Re-syncs the recipient's mailbox counter and client UI.", StorageRefreshHandlers::refreshMailbox),
		new Route("POST", "/admin/refresh-inventory", "storage-refresh", true, List.of(), List.of(), "Re-syncs the recipient's inventory with the database.", StorageRefreshHandlers::refreshInventory),
		new Route("POST", "/admin/refresh-warehouse", "storage-refresh", true, List.of(), List.of(), "Re-syncs the recipient's character warehouse with the database.", StorageRefreshHandlers::refreshWarehouse),
		new Route("POST", "/admin/refresh-account-warehouse", "storage-refresh", true, List.of(), List.of(), "Re-syncs the account warehouse for every online character of an account.", StorageRefreshHandlers::refreshAccountWarehouse),

		new Route("POST", "/admin/validate-express-mail", "express-mail", false, List.of(), List.of(), "Dry-run validation of an express mail (item or kinah).", ExpressMailHandlers::validateExpressMail),
		new Route("POST", "/admin/express-mail", "express-mail", true, List.of(), List.of(), "Delivers an express mail with item and/or kinah attachment.", ExpressMailHandlers::sendExpressMail),
		new Route("POST", "/admin/validate-express-mail-batch", "express-mail", false, List.of(), List.of(), "Dry-run validation of multiple express mails.", ExpressMailHandlers::validateExpressMailBatch),
		new Route("POST", "/admin/express-mail-batch", "express-mail", true, List.of(), List.of(), "Delivers multiple express mails (one letter per entry).", ExpressMailHandlers::sendExpressMailBatch),

		new Route("POST", "/admin/validate-player-item-action", "item-ops", false, List.of(), List.of(), "Dry-run validation of a discard/slot/count repair on a stored item.", ItemOpsHandlers::validatePlayerItemAction),
		new Route("POST", "/admin/discard-player-item", "item-ops", true, List.of(), List.of(), "Removes a stored item from the player's storage.", ItemOpsHandlers::discardPlayerItem),
		new Route("POST", "/admin/repair-item-slot", "item-ops", true, List.of(), List.of(), "Fixes a corrupted or duplicated slot of a stored item.", ItemOpsHandlers::repairItemSlot),
		new Route("POST", "/admin/repair-item-count", "item-ops", true, List.of(), List.of(), "Fixes an item count that exceeds its max stack count.", ItemOpsHandlers::repairItemCount),
		new Route("POST", "/admin/validate-item-storage", "item-ops", false, List.of(), List.of(), "Validates whether an item may live in the requested storage.", ItemOpsHandlers::validateItemStorage),

		new Route("POST", "/admin/reload-cache", "server", true, List.of("announcements", "html", "item-restrictions"), List.of(), "Reloads a cached data source from disk/database.", null),
		new Route("POST", "/admin/broadcast-message", "server", true, List.of(), List.of("all", "elyos", "asmodians"), "Broadcasts a message to online players.", PlayerActionHandlers::broadcastMessage),
		new Route("POST", "/admin/maintenance-warning", "server", true, List.of(), List.of("all", "elyos", "asmodians"), "Schedules maintenance warning broadcasts before shutdown.", null));

	private static void dispatch(HttpExchange ex) {
		try {
			String path = ex.getRequestURI().getPath();
			if (!path.startsWith("/admin"))
				throw HttpResponses.notFound("Unknown path");

			if (AdminApiConfig.TOKEN.isEmpty())
				throw HttpResponses.serviceUnavailable("Admin API has no token configured (gameserver.admin.api.token).");

			String token = ex.getRequestHeaders().getFirst("x-admin-token");
			if (token == null || token.isEmpty())
				throw HttpResponses.unauthorized("Missing x-admin-token header.");
			if (!constantTimeEquals(AdminApiConfig.TOKEN, token))
				throw HttpResponses.forbidden("Invalid admin token.");

			Map<String, String> query = AdminJson.readQuery(ex.getRequestURI());
			com.alibaba.fastjson2.JSONObject body = ex.getRequestMethod().equalsIgnoreCase("POST") ? AdminJson.readBody(ex) : new com.alibaba.fastjson2.JSONObject();

			String method = ex.getRequestMethod().toUpperCase();
			for (Route route : ROUTES) {
				if (!route.path().equals(path))
					continue;
				if (!route.method().equals(method))
					throw HttpResponses.badRequest("Method " + method + " not allowed for " + path + " (use " + route.method() + ").");
				if (route.handler() == null)
					throw new HttpResponses(501, AdminJson.fail("Endpoint not implemented yet: " + path));
				route.handler().handle(ex, query, body);
				return;
			}
			throw HttpResponses.notFound("Unknown endpoint: " + path);
		} catch (HttpResponses e) {
			respond(ex, e.status(), e.body());
		} catch (IOException | UncheckedIOException e) {
			log.warn("Admin API request failed for {}: {}", ex.getRequestURI(), e.toString());
			respond(ex, 500, AdminJson.fail("Internal error: " + e.getMessage()));
		} catch (Exception e) {
			log.warn("Admin API request failed for {}: {}", ex.getRequestURI(), e.toString());
			respond(ex, 500, AdminJson.fail("Unexpected error: " + e.getMessage()));
		} finally {
			ex.close();
		}
	}

	private static void respond(HttpExchange ex, int status, com.alibaba.fastjson2.JSONObject body) {
		try {
			AdminJson.send(ex, status, body);
		} catch (Exception e) {
			log.debug("Could not send admin API response for {}: {}", ex.getRequestURI(), e.toString());
		} finally {
			ex.close();
		}
	}

	/**
	 * Capability metadata for the {@code /admin/capabilities} endpoint.
	 */
	public static List<com.alibaba.fastjson2.JSONObject> capabilityList() {
		return ROUTES.stream().map(r -> {
			com.alibaba.fastjson2.JSONObject json = new com.alibaba.fastjson2.JSONObject();
			json.put("method", r.method());
			json.put("path", r.path());
			json.put("category", r.category());
			json.put("mutates", r.mutates());
			json.put("deprecated", false);
			json.put("canonicalPath", r.path());
			json.put("allowedTargets", r.allowedTargets());
			json.put("allowedScopes", r.allowedScopes());
			json.put("description", r.description());
			json.put("implemented", r.handler() != null);
			return json;
		}).toList();
	}

	public static int apiVersion() {
		return API_VERSION;
	}

	/**
	 * Constant-time string comparison so the token check does not leak via timing.
	 */
	private static boolean constantTimeEquals(String a, String b) {
		byte[] x = a.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		byte[] y = b.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		int diff = x.length ^ y.length;
		for (int i = 0; i < Math.min(x.length, y.length); i++)
			diff |= x[i] ^ y[i];
		return diff == 0;
	}

	/**
	 * Error carrier with HTTP status and already-rendered JSON body.
	 */
	public static final class HttpResponses extends RuntimeException {
		private final int status;
		private final com.alibaba.fastjson2.JSONObject body;

		HttpResponses(int status, com.alibaba.fastjson2.JSONObject body) {
			super(null, null, false, false);
			this.status = status;
			this.body = body;
		}

		int status() {
			return status;
		}

		com.alibaba.fastjson2.JSONObject body() {
			return body;
		}

		public static HttpResponses notFound(String msg) {
			return new HttpResponses(404, AdminJson.fail(msg));
		}

		public static HttpResponses badRequest(String msg) {
			return new HttpResponses(400, AdminJson.fail(msg));
		}

		public static HttpResponses unauthorized(String msg) {
			return new HttpResponses(401, AdminJson.fail(msg));
		}

		public static HttpResponses forbidden(String msg) {
			return new HttpResponses(403, AdminJson.fail(msg));
		}

		public static HttpResponses conflict(String msg) {
			return new HttpResponses(409, AdminJson.fail(msg));
		}

		public static HttpResponses serviceUnavailable(String msg) {
			return new HttpResponses(503, AdminJson.fail(msg));
		}
	}
}
