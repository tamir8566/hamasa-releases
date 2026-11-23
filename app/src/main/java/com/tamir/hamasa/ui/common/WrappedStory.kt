package com.tamir.hamasa.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tamir.hamasa.ui.theme.HColors
import kotlinx.coroutines.launch

/**
 * One slide of a "wrapped" story: a solid loud background, a small kicker line, one giant number or word, and a
 * sentence in Shambiuluk's voice under it. [sub] is an optional small line at the bottom.
 */
data class WrappedSlide(
    val bg: Color,
    val fg: Color,
    val kicker: String,
    val big: String,
    val line: String,
    val sub: String = "",
    /** A smaller "big" for long words or a list of names. */
    val bigSmall: Boolean = false
)

/** The loud palette the slides rotate through: (background, text). */
object WrappedPalette {
    val pairs: List<Pair<Color, Color>> = listOf(
        HColors.Gold to HColors.Ink,
        HColors.Ember to HColors.Parchment,
        HColors.Sky to HColors.Ink,
        HColors.Moss to HColors.Parchment,
        HColors.Legendary to HColors.Ink,
        HColors.Danger to HColors.Parchment,
        HColors.Ink to HColors.Gold
    )

    fun at(i: Int): Pair<Color, Color> = pairs[((i % pairs.size) + pairs.size) % pairs.size]
}

/**
 * The story itself: a horizontal pager of [slides] with thin progress bars on top, like the end-of-year story in
 * music apps. Tap the right half to go forward, the left half to go back; a swipe works too.
 * Each slide pops its big number in when it appears. Owned by the weekly and monthly summaries.
 */
@Composable
fun WrappedStory(slides: List<WrappedSlide>, modifier: Modifier = Modifier, testTag: String = "wrapped") {
    if (slides.isEmpty()) return
    val pager = rememberPagerState(pageCount = { slides.size })
    val scope = rememberCoroutineScope()
    Box(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(HColors.Ink)
            .testTag(testTag)
    ) {
        // The motion is the story convention he knows (owner feedback 2026-11-07): tapping the RIGHT half goes
        // forward and the next slide comes in from the right, the progress bars fill left to right. So the pager
        // and the bars run left-to-right; the text inside every slide stays RTL.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                val s = slides[page]
                val current = pager.currentPage == page
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    WrappedSlideView(s, index = page + 1, total = slides.size, shown = current)
                }
            }
            // progress bars, filling left to right
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp).align(Alignment.TopCenter),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val fg = slides[pager.currentPage].fg
                for (i in slides.indices) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (i <= pager.currentPage) fg else fg.copy(alpha = 0.3f))
                    )
                }
            }
            // tap zones: left half = back, right half = next
            Row(Modifier.fillMaxSize()) {
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .testTag("${testTag}_back")
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            scope.launch { if (pager.currentPage > 0) pager.animateScrollToPage(pager.currentPage - 1) }
                        }
                )
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .testTag("${testTag}_next")
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            scope.launch { if (pager.currentPage < slides.size - 1) pager.animateScrollToPage(pager.currentPage + 1) }
                        }
                )
            }
        }
    }
}

@Composable
private fun WrappedSlideView(s: WrappedSlide, index: Int, total: Int, shown: Boolean) {
    val scale by animateFloatAsState(if (shown) 1f else 0.6f, tween(500), label = "wrapped_scale_$index")
    val fade by animateFloatAsState(if (shown) 1f else 0f, tween(500), label = "wrapped_fade_$index")
    Column(
        Modifier.fillMaxSize().background(s.bg).padding(horizontal = 24.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(1f))
        Text(
            s.kicker, color = s.fg.copy(alpha = 0.85f), fontSize = 18.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(14.dp))
        Text(
            s.big, color = s.fg,
            fontSize = if (s.bigSmall) 34.sp else 76.sp,
            lineHeight = if (s.bigSmall) 40.sp else 80.sp,
            fontWeight = FontWeight.Black, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().scale(scale).alpha(fade)
        )
        Spacer(Modifier.height(18.dp))
        Text(
            s.line, color = s.fg, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().alpha(fade)
        )
        if (s.sub.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                s.sub, color = s.fg.copy(alpha = 0.8f), fontSize = 14.sp, lineHeight = 20.sp,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().alpha(fade)
            )
        }
        Spacer(Modifier.weight(1f))
        Text("\u200E$index / $total", color = s.fg.copy(alpha = 0.6f), fontSize = 12.sp)   // LRM: keeps "2 / 9" in RTL
    }
}
