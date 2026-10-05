package com.aionemu.gameserver.custom.botapi;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson2.JSONObject;
import com.aionemu.gameserver.configs.main.BotApiConfig;

/**
 * Fire-and-forget event delivery from the game server to the bot brain
 * ({@code POST {botId, type, data}} on {@code BotApiConfig.WEBHOOK_URL}). Never blocks or
 * throws into game threads; failures are logged at debug level.
 */
public final class BotWebhooks {

	private static final Logger log = LoggerFactory.getLogger(BotWebhooks.class);
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
	private static final java.util.concurrent.ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "bot-webhook");
		t.setDaemon(true);
		return t;
	});

	private BotWebhooks() {
	}

	public static void post(String botId, String type, JSONObject data) {
		if (!BotApiConfig.WEBHOOK_ENABLED || BotApiConfig.WEBHOOK_URL.isEmpty())
			return;
		JSONObject evt = new JSONObject()
			.fluentPut("botId", botId)
			.fluentPut("type", type)
			.fluentPut("data", data == null ? new JSONObject() : data);
		POOL.execute(() -> send(botId, type, evt));
	}

	private static void send(String botId, String type, JSONObject evt) {
		try {
			HttpRequest req = HttpRequest.newBuilder(URI.create(BotApiConfig.WEBHOOK_URL))
				.timeout(Duration.ofSeconds(4))
				.header("content-type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(evt.toJSONString()))
				.build();
			HttpResponse<Void> res = HTTP.send(req, HttpResponse.BodyHandlers.discarding());
			if (res.statusCode() >= 300)
				log.debug("Bot webhook {} for {} got HTTP {}", type, botId, res.statusCode());
		} catch (Exception e) {
			log.debug("Bot webhook {} for {} failed: {}", type, botId, e.toString());
		}
	}
}
