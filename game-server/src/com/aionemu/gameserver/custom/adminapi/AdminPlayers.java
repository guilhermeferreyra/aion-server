package com.aionemu.gameserver.custom.adminapi;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.aionemu.commons.configs.DatabaseConfig;
import com.aionemu.gameserver.dao.InventoryDAO;
import com.aionemu.gameserver.model.account.Account;
import com.aionemu.gameserver.model.gameobjects.Item;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.gameobjects.player.PlayerCommonData;
import com.aionemu.gameserver.model.items.storage.Storage;
import com.aionemu.gameserver.model.items.storage.StorageType;
import com.aionemu.gameserver.services.AccountService;

/**
 * Snapshot builders turning game objects into the JSON shapes expected by the portal.
 */
public final class AdminPlayers {

	private AdminPlayers() {
	}

	// ------------------------------------------------------------------ live players

	/**
	 * LiveOnlinePlayer: full state of an online character.
	 */
	public static Map<String, Object> playerSnapshot(Player p) {
		Map<String, Object> json = new LinkedHashMap<>();
		json.put("characterId", p.getObjectId());
		json.put("objectId", p.getObjectId());
		json.put("name", p.getName());

		Account account = p.getAccount();
		if (account != null) {
			json.put("accountId", account.getId());
			json.put("accountName", account.getName() != null ? account.getName() : "");
			json.put("accessLevel", account.getAccessLevel());
		} else {
			json.put("accountId", 0);
			json.put("accountName", "");
			json.put("accessLevel", 0);
		}

		json.put("level", p.getCommonData().getLevel());
		json.put("race", p.getRace().name());
		json.put("playerClass", p.getPlayerClass().name());
		json.put("worldId", p.getWorldId());
		json.put("instanceId", p.getInstanceId());
		json.put("x", p.getX());
		json.put("y", p.getY());
		json.put("z", p.getZ());
		json.put("heading", p.getHeading());

		var bindPoint = p.getBindPoint();
		if (bindPoint != null)
			json.put("bindPoint", destination("bind-point", bindPoint.getMapId(), null, bindPoint.getX(), bindPoint.getY(), bindPoint.getZ(), bindPoint.getHeading()));
		return json;
	}

	/**
	 * LiveBindDestination: a world position plus where it came from.
	 */
	public static Map<String, Object> destination(String source, int worldId, Integer instanceId, float x, float y, float z, byte heading) {
		Map<String, Object> json = new LinkedHashMap<>();
		json.put("source", source);
		json.put("worldId", worldId);
		if (instanceId != null)
			json.put("instanceId", instanceId);
		json.put("x", x);
		json.put("y", y);
		json.put("z", z);
		json.put("heading", heading);
		return json;
	}

	/**
	 * Last known position of a character from its persisted common data (offline snapshot).
	 */
	public static Map<String, Object> positionFromCommonData(PlayerCommonData pcd) {
		if (pcd == null)
			return null;
		return destination("last-known", pcd.getMapId(), null, pcd.getX(), pcd.getY(), pcd.getZ(), pcd.getHeading());
	}

	/**
	 * LiveOfflinePlayerState.
	 */
	public static Map<String, Object> lastKnownSnapshot(int characterId, PlayerCommonData pcd) {
		Map<String, Object> json = new LinkedHashMap<>();
		json.put("characterId", characterId);
		json.put("name", pcd.getName());
		json.put("level", pcd.getLevel());
		json.put("race", pcd.getRace().name());
		json.put("playerClass", pcd.getPlayerClass().name());
		json.put("worldId", pcd.getMapId());
		json.put("x", pcd.getX());
		json.put("y", pcd.getY());
		json.put("z", pcd.getZ());
		json.put("heading", pcd.getHeading());
		json.put("lastOnline", pcd.getLastOnline() == null ? "" : pcd.getLastOnline().toInstant().toString());
		return json;
	}

	// ------------------------------------------------------------------ storages

	/**
	 * InventorySnapshot. Works for online players (live storage) and offline ones (DB load).
	 */
	public static Map<String, Object> inventorySnapshot(Player onlinePlayer, PlayerCommonData pcd) {
		int cubeItemCount, equippedItemCount, cubeLimit, cubeFreeSlots;
		long kinah;
		if (onlinePlayer != null) {
			Storage inv = onlinePlayer.getInventory();
			cubeItemCount = inv.getItems().size();
			equippedItemCount = onlinePlayer.getEquipment().getEquippedItems().size();
			cubeLimit = inv.getLimit();
			cubeFreeSlots = Math.max(0, inv.getFreeSlots());
			kinah = inv.getKinah();
		} else {
			List<Item> items = InventoryDAO.loadItems(pcd.getPlayerObjId(), StorageType.CUBE);
			cubeItemCount = countItems(items, false);
			equippedItemCount = countItems(items, true);
			cubeLimit = StorageType.CUBE.getLimit() + (pcd.getNpcExpands() + pcd.getQuestExpands() + pcd.getItemExpands()) * StorageType.CUBE.getLength();
			cubeFreeSlots = Math.max(0, cubeLimit - cubeItemCount);
			kinah = kinahOfItems(items);
		}

		Map<String, Object> json = new LinkedHashMap<>();
		json.put("cubeItemCount", cubeItemCount);
		json.put("equippedItemCount", equippedItemCount);
		json.put("totalPacketItemCount", cubeItemCount + equippedItemCount);
		json.put("cubeLimit", cubeLimit);
		json.put("cubeFreeSlots", cubeFreeSlots);
		json.put("kinah", kinah);
		return json;
	}

	/**
	 * WarehouseSnapshot (character warehouse + account warehouse).
	 */
	public static Map<String, Object> warehouseSnapshot(Player onlinePlayer, PlayerCommonData pcd) {
		int charItem = 0, charLimit = 0, charFree = 0, accItem = 0, accLimit = 0, accFree = 0;
		long accKinah = 0;

		if (onlinePlayer != null) {
			Storage wh = onlinePlayer.getWarehouse();
			charItem = wh.getItems().size();
			charLimit = wh.getLimit();
			charFree = Math.max(0, wh.getFreeSlots());
			Account account = onlinePlayer.getAccount();
			Storage accWh = account != null ? account.getAccountWarehouse() : null;
			if (accWh != null) {
				accItem = accWh.getItems().size();
				accLimit = accWh.getLimit();
				accFree = Math.max(0, accWh.getFreeSlots());
				accKinah = accWh.getKinah();
			}
		} else {
			List<Item> charWhItems = InventoryDAO.loadItems(pcd.getPlayerObjId(), StorageType.REGULAR_WAREHOUSE);
			charItem = countItems(charWhItems, false);
			charLimit = StorageType.REGULAR_WAREHOUSE.getLimit() + (pcd.getWhNpcExpands() + pcd.getWhBonusExpands()) * StorageType.REGULAR_WAREHOUSE.getLength();
			charFree = Math.max(0, charLimit - charItem);

			Account account = loadAccountOfCharacter(pcd.getPlayerObjId());
			Storage accWh = account != null ? account.getAccountWarehouse() : null;
			if (accWh != null) {
				accItem = accWh.getItems().size();
				accLimit = accWh.getLimit();
				accFree = Math.max(0, accWh.getFreeSlots());
				accKinah = accWh.getKinah();
			}
		}

		Map<String, Object> json = new LinkedHashMap<>();
		json.put("characterWarehouseItemCount", charItem);
		json.put("characterWarehouseLimit", charLimit);
		json.put("characterWarehouseFreeSlots", charFree);
		json.put("accountWarehouseItemCount", accItem);
		json.put("accountWarehouseLimit", accLimit);
		json.put("accountWarehouseFreeSlots", accFree);
		json.put("accountWarehouseKinah", accKinah);
		return json;
	}

	/**
	 * WarehouseSnapshot for an account without any specific character: the character warehouse
	 * fields are zeroed, the account warehouse is filled from the loaded account.
	 */
	public static Map<String, Object> warehouseSnapshotForAccount(Account account) {
		int accItem = 0, accLimit = 0, accFree = 0;
		long accKinah = 0;
		Storage accWh = account != null ? account.getAccountWarehouse() : null;
		if (accWh != null) {
			accItem = accWh.getItems().size();
			accLimit = accWh.getLimit();
			accFree = Math.max(0, accWh.getFreeSlots());
			accKinah = accWh.getKinah();
		}

		Map<String, Object> json = new LinkedHashMap<>();
		json.put("characterWarehouseItemCount", 0);
		json.put("characterWarehouseLimit", 0);
		json.put("characterWarehouseFreeSlots", 0);
		json.put("accountWarehouseItemCount", accItem);
		json.put("accountWarehouseLimit", accLimit);
		json.put("accountWarehouseFreeSlots", accFree);
		json.put("accountWarehouseKinah", accKinah);
		return json;
	}

	// ------------------------------------------------------------------ account meta

	public record AccountMeta(String name, int accessLevel) {
	}

	/**
	 * Name and access level of an account. Online accounts carry that information on the
	 * in-memory object (filled by the login server); offline accounts are looked up in the
	 * login database.
	 */
	public static AccountMeta accountMeta(Account account, int accountId) {
		if (account != null && account.getName() != null && !account.getName().isEmpty())
			return new AccountMeta(account.getName(), account.getAccessLevel());

		String url = DatabaseConfig.DATABASE_URL;
		String lsUrl = url.contains("/aion_gs") ? url.replace("/aion_gs", "/aion_ls") : url.substring(0, url.indexOf("?") < 0 ? url.length() : url.indexOf("?"));
		try (Connection con = DriverManager.getConnection(lsUrl, DatabaseConfig.DATABASE_USER, DatabaseConfig.DATABASE_PASSWORD)) {
			try (PreparedStatement stmt = con.prepareStatement("SELECT name, access_level FROM account_data WHERE id = ?")) {
				stmt.setInt(1, accountId);
				try (ResultSet rs = stmt.executeQuery()) {
					if (rs.next())
						return new AccountMeta(rs.getString("name"), rs.getInt("access_level"));
					return new AccountMeta("", 0);
				}
			}
		} catch (Exception e) {
			return new AccountMeta("", 0);
		}
	}

	// ------------------------------------------------------------------ helpers

	/**
	 * Item count in a DB-loaded item list (offline storages), split by equipped state.
	 * Kinah is excluded: the online storage keeps it outside the item map, so counts match.
	 */
	static int countItems(List<Item> items, boolean equipped) {
		int count = 0;
		for (Item item : items)
			if (item != null && !item.getItemTemplate().isKinah() && item.isEquipped() == equipped)
				count++;
		return count;
	}

	/**
	 * Kinah of a DB-loaded item list (kinah is stored as its own stackable item).
	 */
	static long kinahOfItems(List<Item> items) {
		long kinah = 0;
		for (Item item : items)
			if (item != null && item.getItemTemplate() != null && item.getItemTemplate().isKinah())
				kinah += item.getItemCount();
		return kinah;
	}

	/**
	 * Loads the full account (including its warehouse) that owns the given character.
	 */
	private static Account loadAccountOfCharacter(int characterId) {
		final int[] accountIdHolder = new int[1];
		boolean ok = com.aionemu.commons.database.DB.select("SELECT account_id FROM players WHERE id = ?",
			new com.aionemu.commons.database.ParamReadStH() {

				@Override
				public void setParams(java.sql.PreparedStatement stmt) throws java.sql.SQLException {
					stmt.setInt(1, characterId);
				}

				@Override
				public void handleRead(java.sql.ResultSet rset) throws java.sql.SQLException {
					if (rset.next())
						accountIdHolder[0] = rset.getInt(1);
				}
			});
		if (!ok || accountIdHolder[0] == 0)
			return null;
		return AccountService.loadAccount(accountIdHolder[0]);
	}
}
