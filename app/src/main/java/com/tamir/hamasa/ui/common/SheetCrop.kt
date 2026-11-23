package com.tamir.hamasa.ui.common

import android.graphics.Bitmap

/**
 * Crops one cell out of a sprite sheet laid out as a [cols] x [rows] grid.
 * [index] is 0-based, row-major. Out-of-range indices are clamped to the last cell.
 * The returned bitmap is a copy (safe to keep after the sheet is recycled).
 */
fun cropCell(bitmap: Bitmap, cols: Int, rows: Int, index: Int): Bitmap {
    val c = cols.coerceAtLeast(1)
    val r = rows.coerceAtLeast(1)
    val i = index.coerceIn(0, c * r - 1)
    val cellW = bitmap.width / c
    val cellH = bitmap.height / r
    val x = (i % c) * cellW
    val y = (i / c) * cellH
    val w = cellW.coerceAtMost(bitmap.width - x).coerceAtLeast(1)
    val h = cellH.coerceAtMost(bitmap.height - y).coerceAtLeast(1)
    return Bitmap.createBitmap(bitmap, x, y, w, h)
}

/** Same as [cropCell] but scales the result so its longer side is at most [maxSide] px. */
fun cropCellScaled(bitmap: Bitmap, cols: Int, rows: Int, index: Int, maxSide: Int): Bitmap {
    val cell = cropCell(bitmap, cols, rows, index)
    val longest = maxOf(cell.width, cell.height)
    if (longest <= maxSide || maxSide <= 0) return cell
    val scale = maxSide.toFloat() / longest.toFloat()
    val w = (cell.width * scale).toInt().coerceAtLeast(1)
    val h = (cell.height * scale).toInt().coerceAtLeast(1)
    val scaled = Bitmap.createScaledBitmap(cell, w, h, true)
    if (scaled !== cell) cell.recycle()
    return scaled
}
