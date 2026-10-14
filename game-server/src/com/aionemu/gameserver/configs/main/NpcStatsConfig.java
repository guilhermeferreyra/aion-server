package com.aionemu.gameserver.configs.main;

import com.aionemu.commons.configuration.Property;
import com.aionemu.gameserver.model.templates.npc.NpcRating;

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

	/** NPCs with this rating or above count as bosses and additionally get the boss multipliers below. */
	@Property(key = "gameserver.npcstats.boss.min_rating", defaultValue = "HERO")
	public static NpcRating BOSS_MIN_RATING;

	/** Extra max HP multiplier for bosses (stacks with the global one). */
	@Property(key = "gameserver.npcstats.boss.hp.multiplier", defaultValue = "1.0")
	public static float BOSS_HP_MULTIPLIER;

	/** Extra physical + magical attack multiplier for bosses (stacks with the global one). */
	@Property(key = "gameserver.npcstats.boss.attack.multiplier", defaultValue = "1.0")
	public static float BOSS_ATTACK_MULTIPLIER;

	/** Extra physical + magical defense multiplier for bosses (stacks with the global one). */
	@Property(key = "gameserver.npcstats.boss.defense.multiplier", defaultValue = "1.0")
	public static float BOSS_DEFENSE_MULTIPLIER;
}
