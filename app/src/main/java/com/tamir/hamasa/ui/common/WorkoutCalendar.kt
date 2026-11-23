package com.tamir.hamasa.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.db.SessionEntity
import com.tamir.hamasa.data.db.SessionType
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.ui.theme.HColors
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/** Hebrew date helpers shared by the calendar and the session detail screen. */
object HebrewDates {
    private val MONTHS = listOf(
        "ינואר", "פברואר", "מרץ", "אפריל", "מאי", "יוני",
        "יולי", "אוגוסט", "ספטמבר", "אוקטובר", "נובמבר", "דצמבר"
    )

    /** Column headers, Sunday first. */
    val DAY_LETTERS = listOf("א", "ב", "ג", "ד", "ה", "ו", "ש")

    fun monthName(month: Int): String = MONTHS[(month - 1).coerceIn(0, 11)]

    /** Sunday = 0 .. Saturday = 6. */
    fun columnOf(dow: DayOfWeek): Int = dow.value % 7

    /** "יום ו׳, 30 באוקטובר" (Saturday is "שבת, 31 באוקטובר"). */
    fun longTitle(day: Long): String {
        val d = LocalDate.ofEpochDay(day)
        val col = columnOf(d.dayOfWeek)
        val dayPart = if (col == 6) "שבת" else "יום ${DAY_LETTERS[col]}׳"
        return "$dayPart, ${d.dayOfMonth} ב${monthName(d.monthValue)}"
    }

    /** "30 באוקטובר". */
    fun shortDate(day: Long): String {
        val d = LocalDate.ofEpochDay(day)
        return "${d.dayOfMonth} ב${monthName(d.monthValue)}"
    }
}

/** Hebrew label of a session type (calendar chooser). */
fun calendarTypeName(t: SessionType): String = when (t) {
    SessionType.GYM -> "חדר כושר"
    SessionType.YOGA -> "יוגה"
    SessionType.WALK -> "הליכה"
    SessionType.MINI -> "אימון זעיר"
    SessionType.RUN -> "ריצה"
}

private fun typeIcon(t: SessionType): ImageVector = when (t) {
    SessionType.GYM -> Icons.Filled.FitnessCenter
    SessionType.YOGA -> Icons.Filled.SelfImprovement
    SessionType.WALK -> Icons.Filled.DirectionsWalk
    SessionType.MINI -> Icons.Filled.Bolt
    SessionType.RUN -> Icons.Filled.DirectionsRun
}

/** The session that represents a day in the grid: the first GYM one, otherwise the first one. */
private fun primaryOf(list: List<SessionEntity>): SessionEntity? =
    list.firstOrNull { it.type == SessionType.GYM } ?: list.firstOrNull()

private val CalShape = RoundedCornerShape(20.dp)
private val CellShape = RoundedCornerShape(10.dp)

/**
 * Month calendar of finished sessions. Weeks run Sunday → Saturday (Sunday is the right-most column in RTL).
 * Each day with a session shows a gold badge with the session-type icon (cheap: no bitmaps in the grid).
 * Tapping a day with one session calls [onOpenSession]; with two or more a small chooser opens.
 * [sessions] = finished sessions (any order).
 */
@Composable
fun WorkoutCalendar(
    sessions: List<SessionEntity>,
    onOpenSession: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val today = remember { Days.today() }
    val todayDate = remember(today) { LocalDate.ofEpochDay(today) }
    val currentIndex = todayDate.year * 12 + (todayDate.monthValue - 1)
    var monthIndex by rememberSaveable { mutableStateOf(currentIndex) }
    var chooserDay by remember { mutableStateOf<Long?>(null) }

    val byDay: Map<Long, List<SessionEntity>> = remember(sessions) {
        sessions.groupBy { it.dateEpochDay }.mapValues { e -> e.value.sortedBy { it.id } }
    }

    val ym = YearMonth.of(monthIndex / 12, monthIndex % 12 + 1)
    val firstDay = ym.atDay(1)
    val leading = HebrewDates.columnOf(firstDay.dayOfWeek)
    val length = ym.lengthOfMonth()
    val rows = (leading + length + 6) / 7
    val firstEpoch = firstDay.toEpochDay()
    val monthCount = remember(byDay, monthIndex) {
        var n = 0
        for (i in 0 until length) n += byDay[firstEpoch + i]?.size ?: 0
        n
    }

    Card(
        shape = CalShape,
        colors = CardDefaults.cardColors(containerColor = HColors.Ink),
        border = BorderStroke(2.dp, HColors.GoldDeep),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Header: previous (on the right in RTL) | month name | next (on the left in RTL)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { monthIndex -= 1 }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "החודש הקודם", tint = HColors.Gold)
                }
                Text(
                    "${HebrewDates.monthName(ym.monthValue)} ${ym.year}",
                    color = HColors.Gold,
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { monthIndex += 1 }, enabled = monthIndex < currentIndex) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "החודש הבא",
                        tint = if (monthIndex < currentIndex) HColors.Gold else HColors.ParchmentDim.copy(alpha = 0.3f)
                    )
                }
            }
            Row(Modifier.fillMaxWidth()) {
                HebrewDates.DAY_LETTERS.forEach { letter ->
                    Text(
                        letter,
                        color = HColors.ParchmentDim,
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            for (r in 0 until rows) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (c in 0 until 7) {
                        val dayOfMonth = r * 7 + c - leading + 1
                        if (dayOfMonth < 1 || dayOfMonth > length) {
                            Spacer(Modifier.weight(1f).aspectRatio(1f))
                        } else {
                            val epoch = firstEpoch + dayOfMonth - 1
                            val list = byDay[epoch].orEmpty()
                            DayCell(
                                dayOfMonth = dayOfMonth,
                                isToday = epoch == today,
                                sessions = list,
                                modifier = Modifier.weight(1f).aspectRatio(1f),
                                onClick = {
                                    when {
                                        list.size == 1 -> onOpenSession(list[0].id)
                                        list.size > 1 -> chooserDay = epoch
                                    }
                                }
                            )
                        }
                    }
                }
            }
            Text(
                when (monthCount) {
                    0 -> "החודש: עדיין שקט."
                    1 -> "החודש: אימון אחד."
                    else -> "החודש: $monthCount אימונים."
                },
                color = HColors.Parchment,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }

    val cd = chooserDay
    if (cd != null) {
        SessionChooserDialog(
            day = cd,
            sessions = byDay[cd].orEmpty(),
            onPick = { id -> chooserDay = null; onOpenSession(id) },
            onDismiss = { chooserDay = null }
        )
    }
}

@Composable
private fun DayCell(
    dayOfMonth: Int,
    isToday: Boolean,
    sessions: List<SessionEntity>,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val primary = primaryOf(sessions)
    val bg = if (primary != null) HColors.SlateLight else HColors.Slate
    var m = modifier.background(bg, CellShape)
    if (isToday) m = m.border(2.dp, HColors.Gold, CellShape)
    if (sessions.isNotEmpty()) m = m.clickable(onClick = onClick)
    Box(m, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                dayOfMonth.toString(),
                color = if (isToday) HColors.Gold else HColors.Parchment,
                fontSize = 13.sp,
                fontWeight = if (isToday || primary != null) FontWeight.Bold else FontWeight.Normal
            )
            if (primary != null) {
                SessionBadge(type = primary.type, extra = sessions.size - 1, size = 20)
            }
        }
    }
}

/** Gold circle with the session-type icon; a tiny "+N" when the day has more sessions. */
@Composable
private fun SessionBadge(type: SessionType, extra: Int, size: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(size.dp).background(HColors.Gold, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(typeIcon(type), contentDescription = calendarTypeName(type), tint = HColors.Ink, modifier = Modifier.size((size - 6).dp))
        }
        if (extra > 0) {
            Text("+$extra", color = HColors.Gold, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SessionChooserDialog(
    day: Long,
    sessions: List<SessionEntity>,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = HColors.SlateLight,
        titleContentColor = HColors.Parchment,
        textContentColor = HColors.Parchment,
        title = { Text(HebrewDates.longTitle(day), fontWeight = FontWeight.Black) },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("כמה אימונים ביום אחד. איזה מהם?", color = HColors.ParchmentDim)
                sessions.forEach { s ->
                    OutlinedButton(
                        onClick = { onPick(s.id) },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(2.dp, HColors.GoldDeep),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = HColors.Parchment)
                    ) {
                        Icon(typeIcon(s.type), contentDescription = null, tint = HColors.Gold, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.size(8.dp))
                        val minutes = s.durationSec / 60
                        Text("${calendarTypeName(s.type)} · $minutes דק'", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("סגור") } }
    )
}

/**
 * Compact one-row variant: the week that contains today, Sunday → Saturday.
 * Tapping any day calls [onDayClick] with its epoch day.
 */
@Composable
fun WorkoutWeekStrip(
    sessions: List<SessionEntity>,
    onDayClick: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val today = remember { Days.today() }
    val weekStart = remember(today) { Days.weekStartOf(today) }
    val byDay: Map<Long, List<SessionEntity>> = remember(sessions, weekStart) {
        sessions.filter { it.dateEpochDay in weekStart..(weekStart + 6) }
            .groupBy { it.dateEpochDay }
            .mapValues { e -> e.value.sortedBy { it.id } }
    }
    val count = byDay.values.sumOf { it.size }

    Card(
        shape = CalShape,
        colors = CardDefaults.cardColors(containerColor = HColors.Ink),
        border = BorderStroke(2.dp, HColors.GoldDeep),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("השבוע", color = HColors.Gold, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    when (count) {
                        0 -> "עדיין כלום. השבוע צעיר."
                        1 -> "אימון אחד"
                        else -> "$count אימונים"
                    },
                    color = HColors.ParchmentDim,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (c in 0 until 7) {
                    val epoch = weekStart + c
                    val list = byDay[epoch].orEmpty()
                    val primary = primaryOf(list)
                    val isToday = epoch == today
                    var m = Modifier
                        .weight(1f)
                        .background(if (primary != null) HColors.SlateLight else HColors.Slate, CellShape)
                    if (isToday) m = m.border(2.dp, HColors.Gold, CellShape)
                    m = m.clickable { onDayClick(epoch) }
                    Column(
                        m.padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(HebrewDates.DAY_LETTERS[c], color = HColors.ParchmentDim, style = MaterialTheme.typography.labelLarge)
                        Text(
                            LocalDate.ofEpochDay(epoch).dayOfMonth.toString(),
                            color = if (isToday) HColors.Gold else HColors.Parchment,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                            if (primary != null) SessionBadge(type = primary.type, extra = 0, size = 22)
                        }
                    }
                }
            }
        }
    }
}

/** Home variant: reads finished sessions itself, so the caller only adds one line. */
@Composable
fun HomeWeekStrip(onDayClick: (Long) -> Unit, modifier: Modifier = Modifier) {
    val flow = remember { AppGraph.workouts.doneSessions }
    val sessions by flow.collectAsStateWithLifecycle(initialValue = emptyList())
    WorkoutWeekStrip(sessions = sessions, onDayClick = onDayClick, modifier = modifier)
}
