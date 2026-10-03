package com.aionemu.gameserver.custom.adminapi.handlers;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson2.JSONObject;
import com.aionemu.gameserver.custom.adminapi.AdminApiServer;
import com.aionemu.gameserver.custom.adminapi.AdminJson;
import com.aionemu.gameserver.dao.InventoryDAO;
import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.model.gameobjects.Item;
import com.aionemu.gameserver.model.gameobjects.Persistable.PersistentState;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.items.ItemMask;
import com.aionemu.gameserver.model.items.storage.Storage;
import com.aionemu.gameserver.model.items.storage.StorageType;
import com.aionemu.gameserver.model.templates.item.ItemTemplate;
import com.aionemu.gameserver.services.item.ItemPacketService;
import com.aionemu.gameserver.services.item.ItemPacketService.ItemAddType;
import com.aionemu.gameserver.services.item.ItemPacketService.ItemDeleteType;
import com.aionemu.gameserver.services.item.ItemPacketService.ItemUpdateType;
import com.sun.net.httpserver.HttpExchange;

/**
 * Item operation endpoints (P5): dry-run validation and live mutation of stored items for an
 * online player - discard, warehouse slot repair and stack count repair - plus a pure
 * template/storage rules check that needs no character at all.
 *
 * All live mutations happen through the regular storage API so the client sees exactly what a
 * normal game action would produce: {@link Storage#delete} with the DISCARD delete type
 * (SM_DELETE_ITEM / SM_DELETE_WAREHOUSE_ITEM + cube size), MOVE+ADD packets for slot moves, and
 * {@link ItemPacketService#sendItemUpdatePacket} for count changes. Every change is persisted
 * immediately with {@link InventoryDAO#store} instead of waiting for the periodic save.
 */
public final class ItemOpsHandlers {

	private static final Logger log = LoggerFactory.getLogger("ADMIN_API_LOG");

	private static final String ACTION_DISCARD = "discard";
	private static final String ACTION_REPAIR_SLOT = "repair-slot";
	private static final String ACTION_REPAIR_COUNT = "repair-count";

	private ItemOpsHandlers() {
	}

	// ------------------------------------------------------------------ storage context

	private record StorageContext(Storage storage, int storageId, StorageType type, String name) {
	}

	/**
	 * The three storages item ops operate on: 0 = cube, 1 = character warehouse, 2 = account
	 * warehouse (the ids of {@code item_location} in the inventory table).
	 */
	private static StorageContext resolveStorage(Player player, Integer storageId) throws IOException {
		if (storageId == null)
			throw AdminApiServer.HttpResponses.badRequest("storageId is required (0=cube, 1=character warehouse, 2=account warehouse).");
		if (storageId < 0 || storageId > 2)
			throw AdminApiServer.HttpResponses.badRequest("storageId must be 0 (cube), 1 (character warehouse) or 2 (account warehouse).");

		Storage storage = player.getStorage(storageId);
		if (storage == null)
			throw AdminApiServer.HttpResponses.notFound("Storage " + storageId + " not available on this character.");

		String name = switch (storageId) {
			case 0 -> "cube";
			case 1 -> "character warehouse";
			default -> "account warehouse";
		};
		return new StorageContext(storage, storageId, StorageType.getStorageTypeById(storageId), name);
	}

	private static Item requireItem(StorageContext ctx, Integer itemUniqueId) throws IOException {
		if (itemUniqueId == null)
			throw AdminApiServer.HttpResponses.badRequest("itemUniqueId is required.");

		Item item = ctx.storage.getItemByObjId(itemUniqueId);
		if (item == null)
			throw AdminApiServer.HttpResponses.notFound("Item " + itemUniqueId + " not found in " + ctx.name() + ".");
		return item;
	}

	private static int firstFreeSlot(Storage storage) {
		Set<Long> used = new HashSet<>();
		for (Item item : storage.getItems())
			used.add(item.getEquipmentSlot());
		for (long slot = 1; slot <= storage.getLimit(); slot++)
			if (!used.contains(slot))
				return (int) slot;
		return -1;
	}

	private static int findSlotOwner(Storage storage, long slot) {
		for (Item item : storage.getItems())
			if (item.getEquipmentSlot() == slot)
				return item.getObjectId();
		return -1;
	}

	/**
	 * Slot for display: 0 / -1 means "no assigned slot" and renders as an empty string, which is
	 * what the portal expects.
	 */
	private static String slotString(long slot) {
		return slot <= 0 ? "" : String.valueOf(slot);
	}

	// ------------------------------------------------------------------ validate-item-storage (template only)

	public static void validateItemStorage(HttpExchange ex, Map<String, String> query, JSONObject body) throws IOException {
		Integer itemId = AdminJson.optInt(body, "itemId");
		if (itemId == null || itemId <= 0)
			throw AdminApiServer.HttpResponses.badRequest("itemId is required.");

		ItemTemplate template = DataManager.ITEM_DATA.getItemTemplate(itemId);
		boolean rowSoulBound = body.getBooleanValue("isSoulBound", false);
		long targetStorageId = optLongOrZero(body, "targetStorageId");
		String targetPolicy = AdminJson.optString(body, "targetPolicy").trim();
		String itemCountStr = AdminJson.optString(body, "itemCount").trim();
		long currentStorageId = optLongOrZero(body, "currentStorageId");
		String currentSlotStr = AdminJson.optString(body, "currentSlot").trim();
		Long currentStorageLimitLong = AdminJson.optLong(body, "currentStorageLimit");
		int currentStorageLimit = currentStorageLimitLong == null ? 0 : currentStorageLimitLong.intValue();

		List<String> errors = new ArrayList<>();
		List<String> warnings = new ArrayList<>();

		boolean templateSoulBound = template != null && template.isSoulBound();
		boolean effectiveSoulBound = rowSoulBound || templateSoulBound;

		if (template == null)
			errors.add("Item " + itemId + " does not exist.");

		int mask = template == null ? 0 : template.getMask();
		boolean kinah = template != null && template.isKinah();
		boolean limitOne = (mask & ItemMask.LIMIT_ONE) == ItemMask.LIMIT_ONE;
		long maxStackCount = template == null ? 1 : template.getMaxStackCount();
		boolean storableInCharacterWarehouse = template != null && (mask & ItemMask.STORABLE_IN_WH) == ItemMask.STORABLE_IN_WH;
		boolean storableInAccountWarehouse = template != null && (mask & ItemMask.STORABLE_IN_AWH) == ItemMask.STORABLE_IN_AWH && !effectiveSoulBound;
		if (template != null && (mask & ItemMask.STORABLE_IN_AWH) == ItemMask.STORABLE_IN_AWH && effectiveSoulBound)
			warnings.add("Soul-bound items cannot be stored in the account warehouse.");

		Boolean countAllowed = null;
		if (!itemCountStr.isEmpty()) {
			Long count = parseCount(itemCountStr);
			if (count == null) {
				errors.add("itemCount must be a number.");
				countAllowed = false;
			} else if (count < 1) {
				errors.add("itemCount must be >= 1.");
				countAllowed = false;
			} else if (template == null) {
				countAllowed = false; // nothing to check against, but the item error already fires
			} else if (maxStackCount > 1 && count > maxStackCount) {
				warnings.add(count + " exceeds the max stack count (" + maxStackCount + ") - the item will be split into several rows.");
				countAllowed = false;
			} else if (maxStackCount <= 1 && count > 1) {
				warnings.add("Not a stackable item - a count of " + count + " occupies " + count + " slots/rows.");
				countAllowed = false;
			} else {
				countAllowed = true;
			}
		}

		Boolean slotAllowed = null;
		if (!currentSlotStr.isEmpty() && currentStorageId >= 0 && currentStorageId <= 2) {
			long slot = -1;
			try {
				slot = Long.parseLong(currentSlotStr.trim());
			} catch (NumberFormatException e) {
				errors.add("currentSlot must be a number.");
				slotAllowed = false;
			}
			if (!Boolean.FALSE.equals(slotAllowed)) {
				int limit = currentStorageLimit > 0 ? currentStorageLimit : StorageType.getStorageTypeById((int) currentStorageId).getLimit();
				slotAllowed = slot >= 1 && slot <= limit;
				if (!slotAllowed)
					errors.add("currentSlot " + slot + " is outside 1.." + limit + " for storage " + currentStorageId + ".");
			}
		}

		Boolean targetAllowed = null;
		switch (targetPolicy) {
			case "" -> {
				// no target requested
			}
			case "characterWarehouse" -> targetAllowed = storableInCharacterWarehouse;
			case "accountWarehouse" -> targetAllowed = storableInAccountWarehouse;
			default -> throw AdminApiServer.HttpResponses.badRequest("targetPolicy must be \"\", \"characterWarehouse\" or \"accountWarehouse\".");
		}
		if (targetAllowed != null && !targetAllowed)
			errors.add("Target storage " + targetPolicy + " is not allowed for this item.");
		if (targetPolicy.isEmpty() && targetStorageId > 0 && targetStorageId <= 2) {
			targetAllowed = targetStorageId == 1 ? storableInCharacterWarehouse : storableInAccountWarehouse;
			if (!targetAllowed)
				errors.add("Storage " + targetStorageId + " is not allowed for this item.");
		}

		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("valid", errors.isEmpty());
		fields.put("itemId", itemId);
		fields.put("itemName", template == null ? "" : template.getName());
		fields.put("itemMask", mask);
		fields.put("itemQuality", template == null ? "" : template.getItemQuality().name());
		fields.put("itemType", kinah ? "kinah" : template != null && template.isWeapon() ? "weapon" : template != null && template.isArmor() ? "armor" : "other");
		fields.put("itemGroup", template == null ? "" : template.getItemGroup().name());
		fields.put("maxStackCount", maxStackCount);
		fields.put("kinah", kinah);
		fields.put("limitOne", limitOne);
		fields.put("canSplit", template != null && template.canSplit());
		fields.put("breakable", template != null && template.isBreakable());
		fields.put("deletable", template != null && template.isDeletable());
		fields.put("itemCount", itemCountStr);
		fields.put("countAllowed", countAllowed);
		fields.put("currentStorageId", currentStorageId);
		fields.put("currentSlot", currentSlotStr);
		fields.put("currentStorageLimit", currentStorageLimit);
		fields.put("slotAllowed", slotAllowed);
		fields.put("rowSoulBound", rowSoulBound);
		fields.put("templateSoulBound", templateSoulBound);
		fields.put("effectiveSoulBound", effectiveSoulBound);
		fields.put("tradeable", template != null && template.isTradeable() && !effectiveSoulBound);
		fields.put("storableInCharacterWarehouse", storableInCharacterWarehouse);
		fields.put("storableInAccountWarehouse", storableInAccountWarehouse);
		fields.put("targetPolicy", targetPolicy);
		fields.put("targetAllowed", targetAllowed);
		fields.put("errors", errors);
		fields.put("warnings", warnings);
		AdminJson.send(ex, 200, AdminJson.ok(fields));
	}

	// ------------------------------------------------------------------ validate-player-item-action

	public static void validatePlayerItemAction(HttpExchange ex, Map<String, String> query, JSONObject body) throws IOException {
		Player player = PlayerActionHandlers.onlinePlayer(body);
		StorageContext ctx = resolveStorage(player, AdminJson.optInt(body, "storageId"));
		Item item = requireItem(ctx, AdminJson.optInt(body, "itemUniqueId"));
		String action = AdminJson.optString(body, "action").trim();
		if (!ACTION_DISCARD.equals(action) && !ACTION_REPAIR_SLOT.equals(action) && !ACTION_REPAIR_COUNT.equals(action))
			throw AdminApiServer.HttpResponses.badRequest("action must be \"discard\", \"repair-slot\" or \"repair-count\".");

		Integer targetSlot = AdminJson.optInt(body, "targetSlot");
		if (targetSlot == null)
			targetSlot = -1;
		String targetCountStr = AdminJson.optString(body, "targetCount").trim();

		ItemTemplate template = item.getItemTemplate();
		long maxStackCount = template.getMaxStackCount();
		long currentSlot = item.getEquipmentSlot();

		List<String> errors = new ArrayList<>();
		List<String> warnings = new ArrayList<>();
		boolean changed;

		switch (action) {
			case ACTION_DISCARD -> {
				changed = true; // the item would be gone after the action
				if (!template.isDeletable())
					warnings.add("The item is not deletable by normal rules - the server will force the removal.");
				if (item.isSoulBound() || template.isSoulBound())
					warnings.add("The item is soul-bound.");
			}
			case ACTION_REPAIR_SLOT -> {
				int resolved = targetSlot;
				if (resolved < 1)
					resolved = firstFreeSlot(ctx.storage());
				if (resolved < 1) {
					errors.add("Storage has no free slot available.");
				} else if (resolved > ctx.storage().getLimit()) {
					errors.add("targetSlot " + resolved + " is outside 1.." + ctx.storage().getLimit() + ".");
				} else {
					int owner = findSlotOwner(ctx.storage(), resolved);
					if (owner != -1 && owner != item.getObjectId())
						errors.add("Slot " + resolved + " is already occupied by item " + owner + ".");
				}
				changed = resolved != currentSlot;
			}
			default -> { // ACTION_REPAIR_COUNT
				Long targetCount = parseCount(targetCountStr);
				if (targetCountStr.isEmpty())
					errors.add("targetCount is required for repair-count.");
				else if (targetCount == null)
					errors.add("targetCount must be a number.");
				else if (targetCount < 1)
					errors.add("targetCount must be >= 1.");
				else if (targetCount > maxStackCount)
					errors.add("targetCount " + targetCount + " exceeds the max stack count (" + maxStackCount + ").");
				changed = targetCount != null && targetCount != item.getItemCount();
			}
		}

		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("valid", errors.isEmpty());
		fields.put("action", action);
		fields.put("recipientCharacterId", player.getObjectId());
		fields.put("recipientName", player.getName());
		fields.put("itemUniqueId", item.getObjectId());
		fields.put("itemId", item.getItemId());
		fields.put("itemName", template.getName());
		fields.put("itemCount", String.valueOf(item.getItemCount()));
		fields.put("maxStackCount", String.valueOf(maxStackCount));
		fields.put("storageId", ctx.storageId());
		fields.put("storageName", ctx.name());
		fields.put("currentSlot", slotString(currentSlot));
		fields.put("targetSlot", targetSlot);
		fields.put("targetCount", targetCountStr);
		fields.put("storageLimit", ctx.storage().getLimit());
		fields.put("changed", changed);
		fields.put("errors", errors);
		fields.put("warnings", warnings);
		AdminJson.send(ex, 200, AdminJson.ok(fields));
	}

	// ------------------------------------------------------------------ discard-player-item

	public static void discardPlayerItem(HttpExchange ex, Map<String, String> query, JSONObject body) throws IOException {
		Player player = PlayerActionHandlers.onlinePlayer(body);
		StorageContext ctx = resolveStorage(player, AdminJson.optInt(body, "storageId"));
		Item item = requireItem(ctx, AdminJson.optInt(body, "itemUniqueId"));
		String reason = AdminJson.optString(body, "reason").trim();

		ItemTemplate template = item.getItemTemplate();
		long previousSlot = item.getEquipmentSlot();

		Item deleted = ctx.storage().delete(item, ItemDeleteType.DISCARD, player);
		if (deleted == null)
			throw AdminApiServer.HttpResponses.conflict("Item " + item.getObjectId() + " is no longer in " + ctx.name() + ".");
		boolean persisted = InventoryDAO.store(item, player);

		log.info("[ADMIN] discarded item {} ({}x {}) from {} {} by admin api: {}", item.getObjectId(), item.getItemId(), item.getItemCount(), ctx.name(),
			player.getName(), reason.isEmpty() ? "(no reason)" : reason);

		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("recipientCharacterId", player.getObjectId());
		fields.put("recipientName", player.getName());
		fields.put("itemUniqueId", item.getObjectId());
		fields.put("itemId", item.getItemId());
		fields.put("itemName", template.getName());
		fields.put("itemCount", String.valueOf(deleted.getItemCount()));
		fields.put("storageId", ctx.storageId());
		fields.put("storageName", ctx.name());
		fields.put("slot", slotString(previousSlot));
		fields.put("discarded", true);
		fields.put("persisted", persisted);
		AdminJson.send(ex, 200, AdminJson.ok(fields));
	}

	// ------------------------------------------------------------------ repair-item-slot

	public static void repairItemSlot(HttpExchange ex, Map<String, String> query, JSONObject body) throws IOException {
		Player player = PlayerActionHandlers.onlinePlayer(body);
		StorageContext ctx = resolveStorage(player, AdminJson.optInt(body, "storageId"));
		Item item = requireItem(ctx, AdminJson.optInt(body, "itemUniqueId"));
		String reason = AdminJson.optString(body, "reason").trim();

		Integer targetSlot = AdminJson.optInt(body, "targetSlot");
		if (targetSlot == null || targetSlot < 1)
			targetSlot = firstFreeSlot(ctx.storage());
		if (targetSlot < 1)
			throw AdminApiServer.HttpResponses.conflict("Storage has no free slot available.");
		if (targetSlot > ctx.storage().getLimit())
			throw AdminApiServer.HttpResponses.badRequest("targetSlot " + targetSlot + " is outside 1.." + ctx.storage().getLimit() + ".");
		int owner = findSlotOwner(ctx.storage(), targetSlot);
		if (owner != -1 && owner != item.getObjectId())
			throw AdminApiServer.HttpResponses.conflict("Slot " + targetSlot + " is already occupied by item " + owner + ".");

		long previousSlot = item.getEquipmentSlot();
		boolean changed = previousSlot != targetSlot;
		boolean persisted = false;

		if (changed) {
			item.setEquipmentSlot(targetSlot);
			ctx.storage().setPersistentState(PersistentState.UPDATE_REQUIRED);
			// same two-packet pattern the game uses when an item moves between storages:
			// remove it from the old slot, then display it at the new one
			ItemPacketService.sendItemDeletePacket(player, ctx.type(), item, ItemDeleteType.MOVE);
			ItemPacketService.sendStorageUpdatePacket(player, ctx.type(), item, ItemAddType.ITEM_COLLECT);
			persisted = InventoryDAO.store(item, player);

			log.info("[ADMIN] repaired slot of item {} ({}x {}): {} -> {} in {} {} by admin api: {}", item.getObjectId(), item.getItemId(), item.getItemCount(),
				previousSlot, targetSlot, ctx.name(), player.getName(), reason.isEmpty() ? "(no reason)" : reason);
		}

		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("recipientCharacterId", player.getObjectId());
		fields.put("recipientName", player.getName());
		fields.put("itemUniqueId", item.getObjectId());
		fields.put("itemId", item.getItemId());
		fields.put("itemName", item.getItemTemplate().getName());
		fields.put("itemCount", String.valueOf(item.getItemCount()));
		fields.put("storageId", ctx.storageId());
		fields.put("storageName", ctx.name());
		fields.put("previousSlot", slotString(previousSlot));
		fields.put("slot", slotString(changed ? targetSlot : previousSlot));
		fields.put("changed", changed);
		fields.put("persisted", persisted);
		fields.put("warehouse", StorageRefreshHandlers.warehouseSnapshot(player.getWarehouse(), player.getStorage(StorageType.ACCOUNT_WAREHOUSE.getId())));
		AdminJson.send(ex, 200, AdminJson.ok(fields));
	}

	// ------------------------------------------------------------------ repair-item-count

	public static void repairItemCount(HttpExchange ex, Map<String, String> query, JSONObject body) throws IOException {
		Player player = PlayerActionHandlers.onlinePlayer(body);
		StorageContext ctx = resolveStorage(player, AdminJson.optInt(body, "storageId"));
		Item item = requireItem(ctx, AdminJson.optInt(body, "itemUniqueId"));
		String reason = AdminJson.optString(body, "reason").trim();

		String targetCountStr = AdminJson.optString(body, "targetCount").trim();
		if (targetCountStr.isEmpty())
			throw AdminApiServer.HttpResponses.badRequest("targetCount is required.");
		Long targetCount = parseCount(targetCountStr);
		if (targetCount == null)
			throw AdminApiServer.HttpResponses.badRequest("targetCount must be a number.");
		long maxStackCount = item.getItemTemplate().getMaxStackCount();
		if (targetCount < 1)
			throw AdminApiServer.HttpResponses.badRequest("targetCount must be >= 1.");
		if (targetCount > maxStackCount)
			throw AdminApiServer.HttpResponses.badRequest("targetCount " + targetCount + " exceeds the max stack count (" + maxStackCount + ").");

		long previousCount = item.getItemCount();
		boolean changed = previousCount != targetCount;
		boolean persisted = false;

		if (changed) {
			item.setItemCount(targetCount);
			ctx.storage().setPersistentState(PersistentState.UPDATE_REQUIRED);
			ItemPacketService.sendItemUpdatePacket(player, ctx.type(), item, targetCount > previousCount ? ItemUpdateType.INC_ITEM_COLLECT
				: ItemUpdateType.DEC_ITEM_USE);
			persisted = InventoryDAO.store(item, player);

			log.info("[ADMIN] repaired count of item {} ({}): {} -> {} in {} {} by admin api: {}", item.getObjectId(), item.getItemId(), previousCount,
				targetCount, ctx.name(), player.getName(), reason.isEmpty() ? "(no reason)" : reason);
		}

		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("recipientCharacterId", player.getObjectId());
		fields.put("recipientName", player.getName());
		fields.put("itemUniqueId", item.getObjectId());
		fields.put("itemId", item.getItemId());
		fields.put("itemName", item.getItemTemplate().getName());
		fields.put("previousCount", String.valueOf(previousCount));
		fields.put("itemCount", String.valueOf(changed ? targetCount : previousCount));
		fields.put("maxStackCount", String.valueOf(maxStackCount));
		fields.put("storageId", ctx.storageId());
		fields.put("storageName", ctx.name());
		fields.put("changed", changed);
		fields.put("persisted", persisted);
		if (ctx.storageId() == 0)
			fields.put("inventory", StorageRefreshHandlers.inventorySnapshot(player));
		else
			fields.put("warehouse", StorageRefreshHandlers.warehouseSnapshot(player.getWarehouse(), player.getStorage(StorageType.ACCOUNT_WAREHOUSE.getId())));
		AdminJson.send(ex, 200, AdminJson.ok(fields));
	}

	// ------------------------------------------------------------------ helpers

	private static Long parseCount(String value) {
		if (value.isEmpty())
			return null;
		try {
			return Long.parseLong(value.trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static long optLongOrZero(JSONObject json, String key) {
		Long value = AdminJson.optLong(json, key);
		return value == null ? 0 : value;
	}
}
