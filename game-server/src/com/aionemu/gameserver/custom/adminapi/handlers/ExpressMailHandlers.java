package com.aionemu.gameserver.custom.adminapi.handlers;

import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.aionemu.commons.database.DB;
import com.aionemu.commons.database.ParamReadStH;
import com.aionemu.gameserver.configs.main.CustomConfig;
import com.aionemu.gameserver.custom.adminapi.AdminApiServer;
import com.aionemu.gameserver.custom.adminapi.AdminJson;
import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.model.gameobjects.LetterType;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.gameobjects.player.PlayerCommonData;
import com.aionemu.gameserver.model.items.ItemId;
import com.aionemu.gameserver.model.templates.item.ItemTemplate;
import com.aionemu.gameserver.services.mail.SystemMailService;
import com.aionemu.gameserver.services.player.PlayerService;
import com.aionemu.gameserver.world.World;
import com.sun.net.httpserver.HttpExchange;

/**
 * Express mail endpoints (P4): dry-run validation and delivery of admin express mails with
 * an item and/or kinah attachment, single or batch (one letter per entry).
 *
 * Delivery reuses {@link SystemMailService#sendMail} with {@link LetterType#EXPRESS} - the
 * same service item returns and cash-shop mails use, so recipients get the mail live
 * (online: in-memory mailbox + SM_MAIL_SERVICE + postman notification, offline: persisted
 * letter + counter bump picked up on next login).
 *
 * Validation semantics: blocking problems go to {@code errors[]} (missing/unknown item,
 * bad counts, missing text fields, full mailbox); non-blocking ones to {@code warnings[]}
 * (count above the template's max stack, kinah above the configured cap). A letter can still
 * be sent while warnings exist.
 */
public final class ExpressMailHandlers {

	/** Same ceiling SystemMailService enforces internally (it refuses once letters > 199). */
	private static final int MAILBOX_LIMIT = 200;
	private static final int MAX_BATCH_ENTRIES = 50;

	private ExpressMailHandlers() {
	}

	// ------------------------------------------------------------------ endpoints

	public static void validateExpressMail(HttpExchange ex, Map<String, String> query, JSONObject body) throws IOException {
		Recipient recipient = resolveRecipient(body);
		EntryCheck check = checkEntry(optEntryItem(body), optEntryCount(body), optEntryKinah(body));
		String senderName = AdminJson.optString(body, "senderName").trim();
		String title = AdminJson.optString(body, "title").trim();

		List<String> errors = new ArrayList<>(check.errors());
		List<String> warnings = new ArrayList<>(check.warnings());
		addRecipientErrors(recipient, errors, warnings);
		addTextErrors(senderName, title, errors, warnings);

		long kinah = optEntryKinah(body);
		long kinahMaxAttachment = kinahMaxAttachment();

		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("valid", errors.isEmpty());
		fields.put("recipientCharacterId", recipient.id());
		fields.put("recipientName", recipient.name());
		fields.put("online", recipient.player() != null);
		fields.put("delivered", recipient.player() != null ? "online" : "offline");
		fields.put("mailboxLetters", recipient.mailboxLetters());
		fields.put("mailboxLimit", MAILBOX_LIMIT);
		fields.put("itemName", check.itemName());
		fields.put("itemMaxStackCount", check.itemMaxStackCount());
		fields.put("kinah", kinah);
		fields.put("kinahMaxAttachment", kinahMaxAttachment);
		fillKinahCapFields(fields, kinah, recipient.kinah());
		fields.put("errors", errors);
		fields.put("warnings", warnings);
		AdminJson.send(ex, 200, AdminJson.ok(fields));
	}

	public static void sendExpressMail(HttpExchange ex, Map<String, String> query, JSONObject body) throws IOException {
		Recipient recipient = resolveRecipient(body);
		int itemId = optEntryItem(body);
		long itemCount = optEntryCount(body);
		long kinah = optEntryKinah(body);
		String senderName = requireText(body, "senderName");
		String title = requireText(body, "title");
		String message = requireText(body, "message");

		EntryCheck check = checkEntry(itemId, itemCount, kinah);
		List<String> errors = new ArrayList<>(check.errors());
		List<String> warnings = new ArrayList<>(check.warnings());
		addRecipientErrors(recipient, errors, warnings);
		addTextErrors(senderName, title, errors, warnings);
		if (!errors.isEmpty())
			throw AdminApiServer.HttpResponses.badRequest(String.join(" ", errors));

		boolean sent = SystemMailService.sendMail(senderName, recipient.name(), title, message, itemId, itemCount, kinah, LetterType.EXPRESS);
		if (!sent)
			throw AdminApiServer.HttpResponses.conflict("Mail could not be delivered (mailbox full, missing template or database error).");

		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("delivered", recipient.player() != null ? "online" : "offline");
		fields.put("recipientName", recipient.name());
		fields.put("itemId", itemId);
		fields.put("itemCount", itemCount);
		fields.put("kinah", kinah);
		fields.put("warnings", warnings);
		AdminJson.send(ex, 200, AdminJson.ok(fields));
	}

	public static void validateExpressMailBatch(HttpExchange ex, Map<String, String> query, JSONObject body) throws IOException {
		Recipient recipient = resolveRecipient(body);
		String senderName = AdminJson.optString(body, "senderName").trim();
		String title = AdminJson.optString(body, "title").trim();
		List<EntryInput> entries = readEntries(body);

		List<String> errors = new ArrayList<>();
		List<String> warnings = new ArrayList<>();
		List<Map<String, Object>> entryResults = new ArrayList<>();
		int validEntryCount = 0;
		long kinahTotal = 0;
		for (int i = 0; i < entries.size(); i++) {
			EntryInput entry = entries.get(i);
			EntryCheck check = checkEntry(entry.itemId(), entry.itemCount(), entry.kinah());
			kinahTotal += Math.max(0, entry.kinah());
			boolean valid = check.errors().isEmpty();
			if (valid)
				validEntryCount++;

			Map<String, Object> result = new LinkedHashMap<>();
			result.put("index", i);
			result.put("valid", valid);
			result.put("itemId", entry.itemId());
			result.put("itemCount", entry.itemCount());
			result.put("kinah", entry.kinah());
			result.put("itemName", check.itemName());
			result.put("itemMaxStackCount", check.itemMaxStackCount());
			result.put("errors", check.errors());
			result.put("warnings", check.warnings());
			entryResults.add(result);
		}

		addRecipientErrors(recipient, errors, warnings);
		addTextErrors(senderName, title, errors, warnings);
		if (recipient.mailboxLetters() + entries.size() > MAILBOX_LIMIT)
			errors.add("Mail would overflow the recipient's mailbox (" + recipient.mailboxLetters() + " + " + entries.size() + " > " + MAILBOX_LIMIT + ").");

		long kinahMaxAttachment = kinahMaxAttachment();
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("valid", errors.isEmpty() && validEntryCount == entries.size());
		fields.put("recipientCharacterId", recipient.id());
		fields.put("recipientName", recipient.name());
		fields.put("online", recipient.player() != null);
		fields.put("delivered", recipient.player() != null ? "online" : "offline");
		fields.put("mailboxLetters", recipient.mailboxLetters());
		fields.put("mailboxLimit", MAILBOX_LIMIT);
		fields.put("entryCount", entries.size());
		fields.put("validEntryCount", validEntryCount);
		fields.put("kinahTotal", kinahTotal);
		fields.put("kinahMaxAttachment", kinahMaxAttachment);
		fillKinahCapFields(fields, kinahTotal, recipient.kinah());
		fields.put("errors", errors);
		fields.put("warnings", warnings);
		fields.put("entries", entryResults);
		AdminJson.send(ex, 200, AdminJson.ok(fields));
	}

	public static void sendExpressMailBatch(HttpExchange ex, Map<String, String> query, JSONObject body) throws IOException {
		Recipient recipient = resolveRecipient(body);
		String senderName = requireText(body, "senderName");
		String title = requireText(body, "title");
		String message = requireText(body, "message");
		List<EntryInput> entries = readEntries(body);

		// fail before sending anything: every entry must pass validation
		List<String> errors = new ArrayList<>();
		for (int i = 0; i < entries.size(); i++) {
			EntryInput entry = entries.get(i);
			for (String error : checkEntry(entry.itemId(), entry.itemCount(), entry.kinah()).errors())
				errors.add("entry " + i + ": " + error);
		}
		addRecipientErrors(recipient, errors, new ArrayList<>());
		if (!errors.isEmpty())
			throw AdminApiServer.HttpResponses.badRequest(String.join(" ", errors));
		if (recipient.mailboxLetters() + entries.size() > MAILBOX_LIMIT)
			throw AdminApiServer.HttpResponses.conflict("Mail would overflow the recipient's mailbox (" + recipient.mailboxLetters() + " + " + entries.size()
				+ " > " + MAILBOX_LIMIT + ").");

		List<Map<String, Object>> sentEntries = new ArrayList<>();
		List<String> warnings = new ArrayList<>();
		long kinahTotal = 0;
		for (int i = 0; i < entries.size(); i++) {
			EntryInput entry = entries.get(i);
			String itemName = entry.itemId() > 0 && DataManager.ITEM_DATA.getItemTemplate(entry.itemId()) != null
				? DataManager.ITEM_DATA.getItemTemplate(entry.itemId()).getName() : "";
			if (!SystemMailService.sendMail(senderName, recipient.name(), title, message, entry.itemId(), entry.itemCount(), entry.kinah(), LetterType.EXPRESS)) {
				warnings.add("entry " + i + " failed to deliver (mailbox full or database error).");
				continue;
			}
			kinahTotal += Math.max(0, entry.kinah());

			Map<String, Object> result = new LinkedHashMap<>();
			result.put("index", i);
			result.put("itemId", entry.itemId());
			result.put("itemCount", entry.itemCount());
			result.put("kinah", entry.kinah());
			result.put("itemName", itemName);
			sentEntries.add(result);
		}

		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("delivered", recipient.player() != null ? "online" : "offline");
		fields.put("recipientCharacterId", recipient.id());
		fields.put("recipientName", recipient.name());
		fields.put("entryCount", entries.size());
		fields.put("sentCount", sentEntries.size());
		fields.put("kinahTotal", kinahTotal);
		fields.put("sentEntries", sentEntries);
		fields.put("warnings", warnings);
		AdminJson.send(ex, 200, AdminJson.ok(fields));
	}

	// ------------------------------------------------------------------ recipient

	private record Recipient(int id, String name, Player player, long mailboxLetters, long kinah) {
	}

	/**
	 * Resolves the recipient by character id. 400 when the id is missing, 404 when the
	 * character does not exist. Mailbox count comes from the live mailbox when online (the
	 * persisted counter can lag), otherwise from the common data; kinah likewise (DB row for
	 * the kinah item when offline). kinah = -1 means "unknown" and is emitted as JSON null.
	 */
	private static Recipient resolveRecipient(JSONObject body) throws IOException {
		Integer characterId = AdminJson.optInt(body, "recipientCharacterId");
		if (characterId == null || characterId <= 0)
			throw AdminApiServer.HttpResponses.badRequest("recipientCharacterId is required.");

		Player player = World.getInstance().getPlayer(characterId);
		PlayerCommonData pcd = player != null ? player.getCommonData() : PlayerService.getOrLoadPlayerCommonData(characterId);
		if (pcd == null || pcd.getName() == null || pcd.getName().isEmpty())
			throw AdminApiServer.HttpResponses.notFound("Character " + characterId + " not found.");

		long mailboxLetters = player != null ? player.getMailbox().size() : pcd.getMailboxLetters();
		long kinah = player != null ? player.getInventory().getKinah() : loadOfflineKinah(characterId);
		return new Recipient(characterId, pcd.getName(), player, mailboxLetters, kinah);
	}

	private static long loadOfflineKinah(int characterId) {
		final long[] kinah = new long[] { -1 };
		try {
			boolean ok = DB.select("SELECT item_count FROM inventory WHERE item_owner = ? AND item_location = 0 AND item_id = ?", new ParamReadStH() {

				@Override
				public void setParams(PreparedStatement stmt) throws SQLException {
					stmt.setInt(1, characterId);
					stmt.setInt(2, ItemId.KINAH);
				}

				@Override
				public void handleRead(ResultSet rset) throws SQLException {
					if (rset.next())
						kinah[0] = rset.getLong("item_count");
				}
			});
			if (!ok)
				kinah[0] = -1;
		} catch (Exception e) {
			kinah[0] = -1; // storage snapshot is best-effort; validation still works without it
		}
		return kinah[0];
	}

	// ------------------------------------------------------------------ validation

	private record EntryInput(int itemId, long itemCount, long kinah) {
	}

	private record EntryCheck(List<String> errors, List<String> warnings, String itemName, long itemMaxStackCount) {
	}

	private static List<EntryInput> readEntries(JSONObject body) throws IOException {
		JSONArray array = body.getJSONArray("entries");
		if (array == null || array.isEmpty())
			throw AdminApiServer.HttpResponses.badRequest("entries is required (non-empty array).");
		if (array.size() > MAX_BATCH_ENTRIES)
			throw AdminApiServer.HttpResponses.badRequest("Batch too large (max " + MAX_BATCH_ENTRIES + " entries).");

		List<EntryInput> entries = new ArrayList<>();
		for (int i = 0; i < array.size(); i++) {
			JSONObject entry = array.getJSONObject(i);
			if (entry == null)
				throw AdminApiServer.HttpResponses.badRequest("entries[" + i + "] is not an object.");
			entries.add(new EntryInput(AdminJson.optInt(entry, "itemId"), AdminJson.optLong(entry, "itemCount"), AdminJson.optLong(entry, "kinah")));
		}
		return entries;
	}

	/**
	 * Validates one attachment entry. Blocking problems -> errors, soft ones -> warnings.
	 * Sending more than the template's max stack is allowed (retail splits it into several
	 * rows when the letter attachment is collected), so it only warns.
	 */
	static EntryCheck checkEntry(int itemId, long itemCount, long kinah) {
		List<String> errors = new ArrayList<>();
		List<String> warnings = new ArrayList<>();
		String itemName = "";
		long itemMaxStackCount = 0;
		boolean hasItem = itemId > 0;

		if (hasItem) {
			ItemTemplate template = DataManager.ITEM_DATA.getItemTemplate(itemId);
			if (template == null)
				errors.add("Item " + itemId + " does not exist.");
			else {
				itemName = template.getName();
				itemMaxStackCount = template.getMaxStackCount();
				if (itemCount <= 0)
					errors.add("itemCount must be >= 1 when sending item " + itemId + ".");
				else if (itemCount > itemMaxStackCount)
					warnings.add(itemCount + " exceeds the max stack count (" + itemMaxStackCount + ") of item " + itemId + " - it will arrive split into several rows.");
			}
		} else if (itemCount != 0) {
			warnings.add("itemCount is " + itemCount + " but no itemId was given - only kinah will be attached.");
		}

		if (kinah < 0)
			errors.add("kinah must be >= 0.");
		else if (kinah > 0 && hasItem && itemCount <= 0)
			errors.add("Attach an itemCount >= 1 or drop the item to send kinah only.");
		else if (kinah > kinahMaxAttachment())
			warnings.add(kinah + " exceeds the max kinah attachment (" + kinahMaxAttachment() + ").");

		if (!hasItem && kinah <= 0)
			errors.add("Entry has no attachment (set itemId+itemCount and/or kinah).");

		return new EntryCheck(errors, warnings, itemName, itemMaxStackCount);
	}

	private static void addRecipientErrors(Recipient recipient, List<String> errors, List<String> warnings) {
		if (recipient.mailboxLetters() >= MAILBOX_LIMIT)
			errors.add("Recipient's mailbox is full (" + recipient.mailboxLetters() + "/" + MAILBOX_LIMIT + ").");
		else if (recipient.mailboxLetters() >= MAILBOX_LIMIT - 3)
			warnings.add("Recipient's mailbox is almost full (" + recipient.mailboxLetters() + "/" + MAILBOX_LIMIT + ").");
	}

	private static void addTextErrors(String senderName, String title, List<String> errors, List<String> warnings) {
		if (senderName.isEmpty())
			errors.add("senderName is required.");
		else if (!senderName.startsWith("$$") && senderName.length() > 16)
			errors.add("senderName is longer than 16 characters (prefix with $$ to bypass, like system mails do).");
		if (title.isEmpty())
			errors.add("title is required.");
		else if (title.length() > 20)
			warnings.add("title is longer than 20 characters and will be truncated.");
	}

	// ------------------------------------------------------------------ kinah cap

	/**
	 * Kinah attachments are limited by the kinah template's max stack, which the emulator
	 * ties to {@code CustomConfig.KINAH_CAP_VALUE} when the cap is enabled.
	 */
	private static long kinahMaxAttachment() {
		ItemTemplate kinahTemplate = DataManager.ITEM_DATA.getItemTemplate(ItemId.KINAH);
		return kinahTemplate == null ? (CustomConfig.ENABLE_KINAH_CAP ? CustomConfig.KINAH_CAP_VALUE : Long.MAX_VALUE) : kinahTemplate.getMaxStackCount();
	}

	private static void fillKinahCapFields(Map<String, Object> fields, long kinahAttachment, long recipientKinah) {
		fields.put("kinahCapEnabled", CustomConfig.ENABLE_KINAH_CAP);
		fields.put("kinahCapValue", CustomConfig.KINAH_CAP_VALUE);
		fields.put("recipientKinah", recipientKinah < 0 ? null : recipientKinah);
		fields.put("kinahWouldExceedCap", CustomConfig.ENABLE_KINAH_CAP && recipientKinah >= 0 && recipientKinah + kinahAttachment > CustomConfig.KINAH_CAP_VALUE);
	}

	// ------------------------------------------------------------------ body params

	private static int optEntryItem(JSONObject body) {
		Integer value = AdminJson.optInt(body, "itemId");
		return value == null ? 0 : value;
	}

	private static long optEntryCount(JSONObject body) {
		Long value = AdminJson.optLong(body, "itemCount");
		return value == null ? 0 : value;
	}

	private static long optEntryKinah(JSONObject body) {
		Long value = AdminJson.optLong(body, "kinah");
		return value == null ? 0 : value;
	}

	private static String requireText(JSONObject body, String key) throws IOException {
		String value = AdminJson.optString(body, key).trim();
		if (value.isEmpty())
			throw AdminApiServer.HttpResponses.badRequest(key + " is required.");
		return value;
	}
}
