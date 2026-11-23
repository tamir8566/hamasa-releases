package com.tamir.hamasa.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A real-life reward the user defines for himself and buys with gold in the shop
 * ("ערב סרט" for 300 gold, etc). Every purchase is also written to the ledger
 * (reason "reward:<id>") by GameRepository.spendGold, so no separate redemption table is needed.
 */
@Entity(tableName = "rewards")
data class RewardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nameHe: String,
    val priceGold: Int,
    val note: String = "",
    val createdAt: Long,
    val redeemedCount: Int = 0
)
