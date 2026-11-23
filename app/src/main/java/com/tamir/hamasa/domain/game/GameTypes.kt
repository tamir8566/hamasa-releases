package com.tamir.hamasa.domain.game

enum class Rarity { COMMON, RARE, LEGENDARY }

data class WorkoutMode(
    val id: String,
    val nameHe: String,
    val descriptionHe: String,
    val unlockedByDefault: Boolean,
    val unlockLevel: Int? = null,     // unlocked at this level
    val fromChest: Boolean = false,   // can drop from chests
    val requiresGym: Boolean = true,
    val timeBased: Boolean = false     // needs endurance stat to roll
)

data class BadgeDef(
    val id: String,
    val nameHe: String,
    val hintHe: String,              // vague hint shown after half progress
    val rarity: Rarity,
    val hidden: Boolean = true,
    val seasonMonth: Int? = null,     // 1..12 when seasonal
    val iconSheet: Int,              // 1..4 which badge sheet image
    val iconIndex: Int               // index within that sheet grid (0-based, row-major)
)

/**
 * Passive buff an equipped gear item gives. Buffs only ever ADD on top of what the player earns;
 * they never take anything away and never touch the coach's weights/reps/rest.
 *  XP          -> +percent on the xp of a finished workout
 *  GOLD        -> +percent on the gold of a finished workout
 *  SHIELD_SAVE -> percent chance that a streak shield covers a missed day without being consumed
 *  CHEST_LUCK  -> +percent moved toward rarer chest drops
 */
enum class BuffType { XP, GOLD, SHIELD_SAVE, CHEST_LUCK }

data class GearBuff(val type: BuffType, val percent: Float)

data class GearItem(
    val id: String,
    val nameHe: String,
    val slot: String,                // "head" | "body" | "weapon" | "accessory"
    val rarity: Rarity,
    val sheet: Int,                  // 1..4 gear sheet
    val index: Int,                  // index within grid
    val requiresUpper: Int = 0,      // stat requirements (branch levels)
    val requiresLower: Int = 0,
    val requiresStability: Int = 0,
    val requiresEndurance: Int = 0,
    val buff: GearBuff = GearBuff(BuffType.XP, 1.0f)   // small passive bonus while equipped
)

data class ChestDrop(val rarity: Rarity, val xp: Int, val gold: Int, val modeId: String?, val itemId: String?, val shield: Boolean, val joker: Boolean)

data class Boss(
    val id: String,
    val nameHe: String,
    val quoteHe: String,             // what the boss says
    val tauntHe: String,             // when you lose a round
    val defeatedHe: String,          // when you beat it
    val sheet: Int,                  // bosses sheet 1..3
    val index: Int,                  // 0..4 inside the sheet
    val maxHp: Int,
    val howToHitHe: String           // which activity damages it
)
