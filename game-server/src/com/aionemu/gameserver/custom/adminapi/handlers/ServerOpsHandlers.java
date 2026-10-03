package com.aionemu.gameserver.custom.adminapi.handlers;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.sun.net.httpserver.HttpExchange;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.cache.HTMLCache;
import com.aionemu.gameserver.custom.adminapi.AdminJson;
import com.aionemu.gameserver.custom.adminapi.AdminApiServer;
import com.aionemu.gameserver.model.ChatType;
import com.aionemu.gameserver.model.Race;
import com.aionemu.gameserver.network.aion.serverpackets.SM_MESSAGE;
import com.aionemu.gameserver.services.AdminService;
import com.aionemu.gameserver.services.AnnouncementService;
import com.aionemu.gameserver.utils.PacketSendUtility;
import com.aionemu.gameserver.utils.ThreadPoolManager;
import com.alibaba.fastjson2.JSONObject;

/**
 * P6 server ops: cache reloads (announcements, html templates, GM item restrictions) and the
 * scheduled maintenance warning countdown.
 * <p>
 * Cache reloads go through the exact same entry points the engine itself uses at startup:
 * {@link AnnouncementService#reload()} (DB), {@link HTMLCache#reload(boolean)} (disk .xhtml files)
 * and {@link AdminService#reload()} (config/administration/item.restriction.txt). The endpoint is
 * best-effort by design: it reports what actually reloaded, with counts, in {@code detail}.
 */
public final class ServerOpsHandlers {

	private static final Logger log = LoggerFactory.getLogger("ADMIN_API_LOG");
	private static final String ADMIN_NAME = "Administrator";
	private static final Pattern HTML_FILES_PATTERN = Pattern.compile("on (\\d+) file\\(s\\) loaded");
	/** checkpoints in minutes before maintenance when a warning broadcast goes out */
	private static final List<Integer> MAINTENANCE_CHECKPOINTS = List.of(15, 5, 1);

	private static final Map<String, Schedule> ACTIVE_SCHEDULES = new ConcurrentHashMap<>();

	/** A scheduled warning countdown: its scope plus the futures that fire it. */
	private record Schedule(String scope, List<ScheduledFuture<?>> futures) {
	}

	private ServerOpsHandlers() {
	}

	public static void reloadCache(HttpExchange ex, Map<String, String> query, JSONObject body) throws IOException {
		String target = AdminJson.optString(body, "target").trim();
		String reason = AdminJson.optString(body, "reason").trim();
		if (!target.equals("announcements") && !target.equals("html") && !target.equals("item-restrictions"))
			throw AdminApiServer.HttpResponses.badRequest("target must be one of: announcements, html, item-restrictions");

		log.info("[ADMIN] reload-cache target={}{}", reason.isEmpty() ? "" : " reason=" + reason);

		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("target", target);
		fields.put("reloaded", true);

		switch (target) {
			case "announcements" -> {
				AnnouncementService.getInstance().reload();
				int count = AnnouncementService.getInstance().getAnnouncements().size();
				fields.put("itemCount", count);
				fields.put("detail", count + " announcement(s) reloaded from the database and their timers rescheduled.");
			}
			case "html" -> {
				HTMLCache.getInstance().reload(true); // delete the on-disk cache file and reparse the .xhtml sources
				String summary = HTMLCache.getInstance().toString();
				Integer files = parseLoadedFileCount(summary);
				if (files != null)
					fields.put("itemCount", files);
				fields.put("detail", "HTML template cache rebuilt from disk. " + summary);
			}
			case "item-restrictions" -> {
				AdminService.getInstance().reload();
				int count = AdminService.getInstance().getRestrictedItemCount();
				fields.put("itemCount", count);
				fields.put("detail", count + " restricted item id(s) reloaded from config/administration/item.restriction.txt.");
			}
		}

		AdminJson.send(ex, 200, AdminJson.ok(fields));
	}

	public static void maintenanceWarning(HttpExchange ex, Map<String, String> query, JSONObject body) throws IOException {
		String scope = AdminJson.optString(body, "scope").trim().toLowerCase();
		Race raceFilter;
		switch (scope) {
			case "all" -> raceFilter = null;
			case "elyos" -> raceFilter = Race.ELYOS;
			case "asmodians" -> raceFilter = Race.ASMODIANS;
			default -> throw AdminApiServer.HttpResponses.badRequest("scope must be one of: all, elyos, asmodians");
		}

		Long minutes = AdminJson.optLong(body, "minutesUntilMaintenance");
		if (minutes == null || minutes < 1 || minutes > 1440)
			throw AdminApiServer.HttpResponses.badRequest("minutesUntilMaintenance must be between 1 and 1440.");

		String template = AdminJson.optString(body, "messageTemplate").trim();
		if (template.isEmpty())
			throw AdminApiServer.HttpResponses.badRequest("messageTemplate is required.");

		String scheduleId = "mw-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		List<ScheduledFuture<?>> futures = new ArrayList<>();
		List<Map<String, Object>> warnings = new ArrayList<>();

		// A warning goes out at each checkpoint that still fits inside the requested window.
		for (Integer checkpoint : MAINTENANCE_CHECKPOINTS) {
			if (checkpoint > minutes)
				continue;
			long delaySeconds = (minutes - checkpoint) * 60L;
			String message = render(template, checkpoint);
			ScheduledFuture<?> future = ThreadPoolManager.getInstance().schedule(
				() -> sendMaintenanceWarning(raceFilter, message), delaySeconds, TimeUnit.SECONDS);
			futures.add(future);

			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("remainingMinutes", checkpoint);
			entry.put("delaySeconds", delaySeconds);
			entry.put("message", message);
			warnings.add(entry);
		}
		if (warnings.isEmpty())
			throw AdminApiServer.HttpResponses.badRequest("No warning checkpoint fits inside " + minutes + " minute(s); use at least 1.");

		synchronized (ACTIVE_SCHEDULES) {
			// a new schedule replaces any pending schedule of the same scope; also drop finished ones
			ACTIVE_SCHEDULES.entrySet().removeIf(entry -> {
				boolean done = entry.getValue().futures().stream().allMatch(f -> f.isDone() || f.isCancelled());
				if (done || entry.getValue().scope().equals(scope)) {
					entry.getValue().futures().forEach(f -> f.cancel(false));
					return true;
				}
				return false;
			});
			ACTIVE_SCHEDULES.put(scheduleId, new Schedule(scope, futures));
		}

		log.info("[ADMIN] maintenance-warning schedule {} (scope={}, in {} min): {} warning(s)", scheduleId, scope, minutes, warnings.size());

		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("scheduleId", scheduleId);
		fields.put("scope", scope);
		fields.put("minutesUntilMaintenance", minutes);
		fields.put("warningCount", warnings.size());
		fields.put("warnings", warnings);
		AdminJson.send(ex, 200, AdminJson.ok(fields));
	}

	private static String render(String template, int remainingMinutes) {
		String label = remainingMinutes == 1 ? "minute" : "minutes";
		return template.replace("{minutes}", String.valueOf(remainingMinutes)).replace("{minuteLabel}", label);
	}

	private static void sendMaintenanceWarning(Race raceFilter, String message) {
		try {
			var text = new SM_MESSAGE(1, ADMIN_NAME, clip(message), ChatType.YELLOW_CENTER);
			PacketSendUtility.broadcastToWorld(text, raceFilter == null ? p -> true : p -> p.getRace() == raceFilter);
			log.info("[ADMIN] maintenance warning broadcast to {}: {}", raceFilter == null ? "all" : raceFilter.name(), message);
		} catch (Exception e) {
			log.warn("[ADMIN] maintenance warning broadcast failed", e);
		}
	}

	private static String clip(String message) {
		return message.length() <= SM_MESSAGE.MESSAGE_SIZE_LIMIT ? message : message.substring(0, SM_MESSAGE.MESSAGE_SIZE_LIMIT);
	}

	private static Integer parseLoadedFileCount(String summary) {
		Matcher matcher = HTML_FILES_PATTERN.matcher(summary);
		if (matcher.find())
			return Integer.parseInt(matcher.group(1));
		return null;
	}
}
