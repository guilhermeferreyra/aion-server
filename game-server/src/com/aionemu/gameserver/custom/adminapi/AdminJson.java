package com.aionemu.gameserver.custom.adminapi;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.sun.net.httpserver.HttpExchange;

/**
 * JSON envelope helpers for the admin API. Every response is an object with
 * {@code ok} and {@code at}; errors add {@code error}.
 */
public final class AdminJson {

	private AdminJson() {
	}

	public static String now() {
		return Instant.now().toString();
	}

	/**
	 * @param fields
	 *            payload fields to merge into the envelope (insertion order kept)
	 */
	public static JSONObject ok(Map<String, Object> fields) {
		JSONObject json = new JSONObject();
		json.put("ok", true);
		json.put("at", now());
		if (fields != null)
			fields.forEach(json::put);
		return json;
	}

	public static JSONObject ok() {
		return ok(null);
	}

	public static JSONObject fail(String message) {
		return fail(message, null);
	}

	/**
	 * @param extra
	 *            optional payload fields besides {@code ok}/{@code at}/{@code error}
	 */
	public static JSONObject fail(String message, Map<String, Object> extra) {
		JSONObject json = new JSONObject();
		json.put("ok", false);
		json.put("at", now());
		json.put("error", message);
		if (extra != null)
			extra.forEach(json::put);
		return json;
	}

	public static void send(HttpExchange ex, int status, JSONObject json) throws IOException {
		byte[] bytes = JSON.toJSONBytes(json);
		ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		ex.sendResponseHeaders(status, bytes.length);
		try (var os = ex.getResponseBody()) {
			os.write(bytes);
		}
	}

	/**
	 * Parses the request body as a JSON object; GET/empty bodies yield an empty object.
	 */
	public static JSONObject readBody(HttpExchange ex) throws IOException {
		try (InputStream in = ex.getRequestBody()) {
			byte[] buf = in.readNBytes(1024 * 1024); // 1 MB cap
			if (buf.length == 0)
				return new JSONObject();
			Object parsed = JSON.parse(new String(buf, StandardCharsets.UTF_8));
			if (parsed instanceof JSONObject obj)
				return obj;
			throw new IllegalArgumentException("Request body must be a JSON object");
		} catch (IllegalArgumentException e) {
			throw new IOException(e.getMessage(), e);
		}
	}

	/**
	 * Decodes the query string of the given URI into a map.
	 */
	public static Map<String, String> readQuery(java.net.URI uri) {
		Map<String, String> params = new LinkedHashMap<>();
		String query = uri.getQuery();
		if (query == null || query.isEmpty())
			return params;
		for (String pair : query.split("&")) {
			int eq = pair.indexOf('=');
			String key = eq < 0 ? pair : pair.substring(0, eq);
			String value = eq < 0 ? "" : pair.substring(eq + 1);
			try {
				params.put(java.net.URLDecoder.decode(key, StandardCharsets.UTF_8), java.net.URLDecoder.decode(value, StandardCharsets.UTF_8));
			} catch (Exception ignored) {
			}
		}
		return params;
	}

	public static Integer optInt(JSONObject json, String key) {
		Object v = json.get(key);
		if (v == null)
			return null;
		try {
			return Integer.parseInt(String.valueOf(v).trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	public static Long optLong(JSONObject json, String key) {
		Object v = json.get(key);
		if (v == null)
			return null;
		try {
			return Long.parseLong(String.valueOf(v).trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	public static String optString(JSONObject json, String key) {
		Object v = json.get(key);
		return v == null ? "" : String.valueOf(v);
	}
}
