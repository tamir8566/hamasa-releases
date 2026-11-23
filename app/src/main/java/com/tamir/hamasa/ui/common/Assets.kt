package com.tamir.hamasa.ui.common

/**
 * Drawable resource names (res/drawable-nodpi). Agent E fills the files; others reference by name via
 * context.resources.getIdentifier(name, "drawable", packageName) through [drawableId].
 */
object Assets {
    // heaviest..fit, 10 transparent PNGs (900x1400, feet on a common baseline). Count must match BodyStage.ANCHORS.
    val characterAnchors = (1..10).map { "sh_anchor_$it" }
    const val homeBgFar = "bg_home_far"       // mountains layer
    const val homeBgMid = "bg_home_mid"       // forest layer
    const val homeBgNear = "bg_home_near"     // ledge layer
    const val mapLower = "map_lower"
    const val mapUpper = "map_upper"
    val bossSheets = listOf("bosses_1", "bosses_2", "bosses_3")   // 5 bosses per sheet in a row
    val gearSheets = listOf("gear_1", "gear_2", "gear_3", "gear_4") // 4x3 / 4x4 grids
    val badgeSheets = listOf("badges_1", "badges_2", "badges_3", "badges_4") // 4x4 / 5x4 grids
    const val treeGrowth = "tree_growth"
}

fun android.content.Context.drawableId(name: String): Int = resources.getIdentifier(name, "drawable", packageName)
