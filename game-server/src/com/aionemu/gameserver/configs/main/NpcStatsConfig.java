package com.aionemu.gameserver.configs.main;

import com.aionemu.commons.configuration.Property;

/**
 * Global NPC stat scaling for private-server difficulty tuning.
 * Multipliers are applied once, at data load, to every non-pet NPC template
 * (pets and summoned minions keep their fixed stats), so dungeons, bosses,
 * fortress garrisons and open-world mobs all scale uniformly.
 * 1.0 = retail values; 0.5 = half HP/attack/defense.
 */
public class NpcStatsConfig {

	/** Max HP multiplier for every non-pet NPC. */
	@Property(key = "gameserver.npcstats.hp.multiplier", defaultValue = "1.0")
	public static float HP_MULTIPLIER;

	/** Physical + magical attack multiplier for every non-pet NPC. */
	@Property(key = "gameserver.npcstats.attack.multiplier", defaultValue = "1.0")
	public static float ATTACK_MULTIPLIER;

	/** Physical + magical defense multiplier for every non-pet NPC. */
	@Property(key = "gameserver.npcstats.defense.multiplier", defaultValue = "1.0")
	public static float DEFENSE_MULTIPLIER;
}
