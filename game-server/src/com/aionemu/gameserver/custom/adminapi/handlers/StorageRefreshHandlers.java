package com.aionemu.gameserver.custom.adminapi.handlers;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.alibaba.fastjson2.JSONObject;
import com.aionemu.gameserver.custom.adminapi.AdminApiServer;
import com.aionemu.gameserver.custom.adminapi.AdminJson;
import com.aionemu.gameserver.custom.adminapi.AdminPlayers;
import com.aionemu.gameserver.dao.InventoryDAO;
import com.aionemu.gameserver.dao.ItemStoneListDAO;
import com.aionemu.gameserver.dao.MailDAO;
import com.aionemu.gameserver.model.gameobjects.Item;
import com.aionemu.gameserver.model.gameobjects.LetterType;
import com.aionemu.gameserver.model.gameobjects.player.Mailbox;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.items.storage.Storage;
import com.aionemu.gameserver.model.items.storage.StorageType;
import com.aionemu.gameserver.network.aion.serverpackets.SM_MAIL_SERVICE;
import com.aionemu.gameserver.services.player.PlayerEnterWorldService;
import com.aionemu.gameserver.utils.PacketSendUtility;
import com.aionemu.gameserver.world.World;
import com.sun.net.httpserver.HttpExchange;

/**
 * Storage refresh endpoints (P3): force the in-memory state of an online player back to the
 * database and push the login-time sync packets so the client UI redraws without relogging.
 * This is what an admin wants after fixing rows directly in the DB.
 *
 * Reload primitives mirror {@code PlayerService.getPlayer} (login): clear the storage,
 * {@code InventoryDAO.loadStorage} + stones, then the exact same packets the enter-world
 * flow sends ({@code SM_INVENTORY_INFO} / {@code SM_WAREHOUSE_INFO} chunks).
 */
public final class StorageRefreshHandlers {

	private StorageRefreshHandlers() {
	}

	// ------------------------------------------------------------------ endpoints

	public static void refreshMailbox(HttpExchange ex, Map<String, String> query, JSONObject body) throws IOException {
		Player player = PlayerActionHandlers.onlinePlayer(body);

		Map<String, Object> before = mailboxSnapshot(player.getMailbox());

		player.setMailbox(MailDAO.loadPlayerMailbox(player));
		player.getCommonData().setMailboxLetters(player.getMailbox().size());
		PacketSendUtility.sendPacket(player, new SM_MAIL_SERVICE()); // serviceId=0: counts/state to the client

		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("recipientCharacterId", player.getObjectId());
		fields.put("recipientName", player.getName());
		fields.put("refreshed", true);
		fields.put("mailboxState", player.getMailbox().mailBoxState);
		fields.put("before", before);
		fields.put("after", mailboxSnapshot(player.getMailbox()));
		AdminJson.send(ex, 200, AdminJson.ok(fields));
	}

	public static void refreshInventory(HttpExchange ex, Map<String, String> query, JSONObject body) throws IOException {
		Player player = PlayerActionHandlers.onlinePlayer(body);

		Storage inventory = player.getInventory();
		clearItems(inventory);
		player.getEquipment().clearForReload();

		// equipped rows come back through PlayerStorage.onLoadHandler -> Equipment.onLoadHandler
		InventoryDAO.loadStorage(player.getObjectId(), inventory);
		ItemStoneListDAO.load(inventory.getItems());
		ItemStoneListDAO.load(player.getEquipment().getEquippedItemsWithoutStigma());

		player.setCubeLimit();
		player.getEquipment().onLoadApplyEquipmentStats();
		player.getLifeStats().updateCurrentStats();
		player.getGameStats().updateStatsAndSpeedVisually();
		PlayerEnterWorldService.sendItemInfos(player.getClientConnection(), player); // full SM_INVENTORY_INFO resync

		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("recipientCharacterId", player.getObjectId());
		fields.put("recipientName", player.getName());
		fields.put("refreshed", true);
		fields.put("inventory", inventorySnapshot(player));
		AdminJson.send(ex, 200, AdminJson.ok(fields));
	}

	public static void refreshWarehouse(HttpExchange ex, Map<String, String> query, JSONObject body) throws IOException {
		Player player = PlayerActionHandlers.onlinePlayer(body);

		Storage characterWh = player.getWarehouse();
		Storage accountWh = player.getStorage(StorageType.ACCOUNT_WAREHOUSE.getId());
		clearItems(characterWh);
		clearItems(accountWh);

		InventoryDAO.loadStorage(player.getObjectId(), characterWh);
		ItemStoneListDAO.load(characterWh.getItems());
		// account warehouse rows are owned by the account id, not the player id
		InventoryDAO.loadStorage(player.getAccount().getId(), accountWh);
		ItemStoneListDAO.load(accountWh.getItems());

		player.setWarehouseLimit();
		PlayerEnterWorldService.sendWarehouseItemInfos(player.getClientConnection(), player); // full SM_WAREHOUSE_INFO resync

		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("recipientCharacterId", player.getObjectId());
		fields.put("recipientName", player.getName());
		fields.put("refreshed", true);
		fields.put("warehouse", warehouseSnapshot(characterWh, accountWh));
		AdminJson.send(ex, 200, AdminJson.ok(fields));
	}

	public static void refreshAccountWarehouse(HttpExchange ex, Map<String, String> query, JSONObject body) throws IOException {
		Integer accountId = AdminJson.optInt(body, "accountId");
		if (accountId == null || accountId <= 0)
			throw AdminApiServer.HttpResponses.badRequest("accountId is required.");

		List<Player> online = new ArrayList<>();
		for (Player p : World.getInstance().getAllPlayers())
			if (p.getAccount().getId() == accountId)
				online.add(p);

		AdminPlayers.AccountMeta meta = AdminPlayers.accountMeta(online.isEmpty() ? null : online.get(0).getAccount(), accountId);
		if (meta.name().isEmpty())
			throw AdminApiServer.HttpResponses.notFound("Account " + accountId + " not found.");

		if (!online.isEmpty()) {
			// one shared in-memory storage for the whole account - reload once, resync everyone
			Storage accountWh = online.get(0).getStorage(StorageType.ACCOUNT_WAREHOUSE.getId());
			clearItems(accountWh);
			InventoryDAO.loadStorage(accountId, accountWh);
			ItemStoneListDAO.load(accountWh.getItems());
			for (Player p : online)
				PlayerEnterWorldService.sendWarehouseItemInfos(p.getClientConnection(), p);
		}

		List<Map<String, Object>> players = new ArrayList<>();
		for (Player p : online) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("recipientCharacterId", p.getObjectId());
			entry.put("recipientName", p.getName());
			entry.put("warehouse", warehouseSnapshot(p.getWarehouse(), p.getStorage(StorageType.ACCOUNT_WAREHOUSE.getId())));
			players.add(entry);
		}

		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("accountId", accountId);
		fields.put("accountName", meta.name());
		fields.put("refreshed", true);
		fields.put("refreshedCount", online.size());
		fields.put("players", players);
		AdminJson.send(ex, 200, AdminJson.ok(fields));
	}

	// ------------------------------------------------------------------ snapshots

	private static Map<String, Object> mailboxSnapshot(Mailbox mailbox) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("totalCount", mailbox.size());
		m.put("unreadCount", mailbox.getUnreadCount());
		m.put("unreadExpressCount", mailbox.getUnreadCountByType(LetterType.EXPRESS));
		m.put("unreadBlackCloudCount", mailbox.getUnreadCountByType(LetterType.BLACKCLOUD));
		return m;
	}

	/**
	 * Post-refresh inventory view. totalPacketItemCount counts every item held in the
	 * character's personal storage (cube + equipped + regular warehouse + pet bags +
	 * cabinets), excluding the shared account warehouse and kinah.
	 */
	static Map<String, Object> inventorySnapshot(Player player) {
		Storage inventory = player.getInventory();
		long total = countItems(inventory) + player.getEquipment().getEquippedItems().stream().mapToLong(Item::getItemCount).sum()
			+ countItems(player.getWarehouse());
		for (Storage petBag : player.getPetBags())
			total += countItems(petBag);
		for (Storage cabinet : player.getCabinets())
			total += countItems(cabinet);

		Map<String, Object> m = new LinkedHashMap<>();
		m.put("cubeItemCount", countItems(inventory));
		m.put("equippedItemCount", player.getEquipment().getEquippedItems().size());
		m.put("totalPacketItemCount", total);
		m.put("cubeLimit", inventory.getLimit());
		m.put("cubeFreeSlots", inventory.getFreeSlots());
		m.put("kinah", inventory.getKinah());
		return m;
	}

	static Map<String, Object> warehouseSnapshot(Storage characterWh, Storage accountWh) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("characterWarehouseItemCount", countItems(characterWh));
		m.put("characterWarehouseLimit", characterWh.getLimit());
		m.put("characterWarehouseFreeSlots", characterWh.getFreeSlots());
		m.put("accountWarehouseItemCount", countItems(accountWh));
		m.put("accountWarehouseLimit", accountWh.getLimit());
		m.put("accountWarehouseFreeSlots", accountWh.getFreeSlots());
		m.put("accountWarehouseKinah", accountWh.getKinah());
		return m;
	}

	// ------------------------------------------------------------------ helpers

	/**
	 * Drops the in-memory item objects only; nothing is written to the database (DB writes
	 * happen through the delete/add paths, not {@link Storage#remove}). The reload that
	 * follows rebuilds the same storage from fresh DB rows.
	 */
	private static void clearItems(Storage storage) {
		for (Item item : new ArrayList<>(storage.getItems()))
			storage.remove(item);
	}

	private static long countItems(Storage storage) {
		long count = 0;
		for (Item item : storage.getItems())
			count += item.getItemCount();
		return count;
	}
}
