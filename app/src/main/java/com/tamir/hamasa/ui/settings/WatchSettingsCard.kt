package com.tamir.hamasa.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tamir.hamasa.data.health.HcStatus
import com.tamir.hamasa.data.health.HealthConnect
import com.tamir.hamasa.data.health.SamsungHealth
import com.tamir.hamasa.data.health.ShStatus
import com.tamir.hamasa.data.health.WatchPermissionContract
import com.tamir.hamasa.data.health.WatchSleep
import com.tamir.hamasa.data.health.WatchStore
import com.tamir.hamasa.data.health.WatchSync
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.ui.theme.HColors
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * "השעון": Galaxy Watch link (stage 3, data/health). Status, connect button, how to turn on the Samsung Health
 * sharing, last sync and today's steps, and the section "חיבור ישיר לסמסונג הלת'" (Samsung Health Data SDK, the
 * first source when connected). In tests Health Connect and Samsung Health are unavailable: "לא מחובר" twice.
 */
@Composable
fun WatchSettingsCard() {
    val context = LocalContext.current
    val st by WatchStore.state.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val contract = remember { WatchPermissionContract() }
    val launcher = rememberLauncherForActivityResult(contract) { granted ->
        WatchSync.onPermissionsResult(context, granted)
        note = if (granted.containsAll(HealthConnect.REQUIRED)) "מחובר. מושך את הנתונים מהשעון."
        else "לא כל ההרשאות אושרו. צריך צעדים, דופק ואימונים."
    }
    // refreshes the status (and syncs when due)
    LaunchedEffect(Unit) { WatchSync.syncSoon(context) }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("השעון", color = HColors.Gold, fontSize = 18.sp, fontWeight = FontWeight.Black)
            val statusText = when {
                st.status == HcStatus.NEEDS_INSTALL -> "צריך להתקין את הלת' קונקט"
                st.connected -> "מחובר"
                else -> "לא מחובר"
            }
            Text(
                statusText,
                color = if (st.connected) HColors.Moss else HColors.Ember,
                fontSize = 16.sp, fontWeight = FontWeight.Bold
            )
            Text(
                "נקראים צעדים, דופק, אימונים ושינה מהשעון (דרך סמסונג הלת' והלת' קונקט). הזזת מחוון השינה גוברת על השעון. " +
                    "אימון שהשעון רשם ולא נפתח כאן נוסף לבד, עם הניסיון והזהב הרגילים. רק מרגע החיבור והלאה.",
                color = HColors.ParchmentDim, fontSize = 13.sp
            )

            if (st.connected) {
                val today = Days.today()
                val steps = st.steps[today]?.total
                Text(
                    if (steps != null) "היום: ${String.format(Locale.US, "%,d", steps)} צעדים" else "היום: עוד אין צעדים מהשעון.",
                    color = HColors.Parchment, fontSize = 14.sp
                )
                val sleepMin = st.sleep[today]
                if (sleepMin != null) {
                    Text("שינה אמש: ${WatchSleep.clockHe(sleepMin)}", color = HColors.Parchment, fontSize = 14.sp)
                }
                // diagnostic: where the sleep came from (Samsung Health directly / Health Connect), updated on every
                // sync and on "סנכרן עכשיו"
                if (st.sleepDiagHe.isNotBlank()) {
                    Text(st.sleepDiagHe, color = HColors.ParchmentDim, fontSize = 12.sp)
                }
                Text(
                    if (st.lastSync > 0L) "סנכרון אחרון: ${formatTime(st.lastSync)}" else "עוד לא היה סנכרון.",
                    color = HColors.ParchmentDim, fontSize = 13.sp
                )
                if (st.lastImportHe.isNotBlank()) {
                    Text(st.lastImportHe, color = HColors.Parchment, fontSize = 13.sp)
                }
                if (st.importDiagHe.isNotBlank()) {
                    Text(st.importDiagHe, color = HColors.ParchmentDim, fontSize = 12.sp)
                }
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = {
                        if (!busy) {
                            busy = true
                            scope.launch {
                                val ran = runCatching { WatchSync.sync(context, force = true) }.getOrDefault(false)
                                note = if (ran) "סונכרן." else com.tamir.hamasa.domain.game.Addr.t("הסנכרון לא רץ. נסה שוב עוד רגע.", "הסנכרון לא רץ. נסי שוב עוד רגע.")
                                busy = false
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (busy) "מסנכרן..." else "סנכרן עכשיו", color = HColors.Parchment, fontWeight = FontWeight.Bold) }
            } else {
                Spacer(Modifier.height(4.dp))
                Text("איך מחברים:", color = HColors.Gold, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("1. השעון מסונכרן לטלפון דרך גלקסי וורבל, כרגיל.", color = HColors.Parchment, fontSize = 13.sp)
                Text("2. סמסונג הלת' ← הגדרות ← הלת' קונקט ← אפשר שיתוף נתונים (צעדים, דופק, אימונים).", color = HColors.Parchment, fontSize = 13.sp)
                Text(com.tamir.hamasa.domain.game.Addr.t("3. כאן: חבר את השעון, ומאשרים קריאה.", "3. כאן: חברי את השעון, ומאשרים קריאה."), color = HColors.Parchment, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                if (st.status == HcStatus.NEEDS_INSTALL) {
                    Button(
                        onClick = { runCatching { context.startActivity(HealthConnect.installIntent()) } },
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) { Text(com.tamir.hamasa.domain.game.Addr.t("התקן את הלת' קונקט", "התקיני את הלת' קונקט"), fontWeight = FontWeight.Black, fontSize = 16.sp) }
                } else {
                    Button(
                        onClick = {
                            if (st.status != HcStatus.AVAILABLE) {
                                note = "בטלפון הזה אין הלת' קונקט. החיבור הישיר למטה עובד גם בלעדיו."
                            } else {
                                val ok = runCatching { launcher.launch(HealthConnect.permissionsToRequest(context)) }.isSuccess
                                if (!ok) note = com.tamir.hamasa.domain.game.Addr.t("מסך ההרשאות לא נפתח. נסה שוב.", "מסך ההרשאות לא נפתח. נסי שוב.")
                            }
                        },
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) { Text(com.tamir.hamasa.domain.game.Addr.t("חבר את השעון", "חברי את השעון"), fontWeight = FontWeight.Black, fontSize = 16.sp) }
                }
            }
            val n = note
            if (n != null) Text(n, color = HColors.Parchment, fontSize = 14.sp)

            Spacer(Modifier.height(8.dp))
            PhoneSleepRow()
            Spacer(Modifier.height(8.dp))
            RestBubbleRow()
            Spacer(Modifier.height(8.dp))
            WristTestRow()
            Spacer(Modifier.height(8.dp))
            NotifPolicyRow()
            Spacer(Modifier.height(8.dp))
            SamsungDirectSection(context, st.shStatus)
        }
    }
}

/**
 * "חיבור ישיר לסמסונג הלת'": status, "חבר ישירות" (Samsung's own permission screen; when Samsung Health is
 * missing / old / off the SDK's fix screen opens instead) and the developer mode how-to.
 */
@Composable
private fun SamsungDirectSection(context: Context, status: ShStatus) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    Text("חיבור ישיר לסמסונג הלת'", color = HColors.Gold, fontSize = 16.sp, fontWeight = FontWeight.Black)
    Text(
        samsungStatusHe(status),
        color = if (status.connected) HColors.Moss else HColors.Ember,
        fontSize = 15.sp, fontWeight = FontWeight.Bold
    )
    Text(
        "קריאה ישירה מסמסונג הלת': שינה, דופק, אימונים וצעדים. כשהוא מחובר הוא המקור הראשון, והלת' קונקט משלים רק מה שחסר.",
        color = HColors.ParchmentDim, fontSize = 13.sp
    )
    if (status == ShStatus.DEV_MODE_OFF) {
        Text("איך מפעילים מצב מפתח:", color = HColors.Gold, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text("1. סמסונג הלת' ← הגדרות ← אודות סמסונג הלת'.", color = HColors.Parchment, fontSize = 13.sp)
        Text("2. עשר לחיצות מהירות על מספר הגרסה, עד שמופיע מצב מפתח.", color = HColors.Parchment, fontSize = 13.sp)
        Text("3. מצב מפתח ← מפעילים קריאת נתונים.", color = HColors.Parchment, fontSize = 13.sp)
        Text(com.tamir.hamasa.domain.game.Addr.t("4. חוזרים לכאן ולוחצים שוב על חבר ישירות.", "4. חוזרים לכאן ולוחצים שוב על חברי ישירות."), color = HColors.Parchment, fontSize = 13.sp)
    }
    if (status != ShStatus.CONNECTED) {
        OutlinedButton(
            onClick = {
                val activity = context.findActivity()
                if (activity == null) {
                    note = com.tamir.hamasa.domain.game.Addr.t("מסך החיבור לא נפתח. נסה שוב.", "מסך החיבור לא נפתח. נסי שוב.")
                } else if (!busy) {
                    busy = true
                    scope.launch {
                        val result = runCatching { SamsungHealth.connect(activity) }.getOrDefault(ShStatus.ERROR)
                        WatchStore.setSamsungStatus(result)
                        if (result.connected) WatchSync.onSamsungConnected(context)
                        note = when (result) {
                            ShStatus.CONNECTED -> "מחובר ישירות. מושך שינה, דופק ואימונים."
                            ShStatus.PARTIAL -> "מחובר, אבל לא הכול אושר. מה שאושר נקרא."
                            ShStatus.NO_PERMISSION -> "לא אושרה קריאה. בלי אישור אין נתונים."
                            ShStatus.DEV_MODE_OFF -> "סמסונג הלת' חוסם. צריך מצב מפתח, ההוראות למעלה."
                            ShStatus.NEEDS_INSTALL, ShStatus.OLD_VERSION, ShStatus.DISABLED, ShStatus.NOT_INITIALIZED ->
                                com.tamir.hamasa.domain.game.Addr.t("סמסונג הלת' צריך טיפול קודם. אחרי זה לוחצים שוב על חבר ישירות.", "סמסונג הלת' צריך טיפול קודם. אחרי זה לוחצים שוב על חברי ישירות.")
                            else -> com.tamir.hamasa.domain.game.Addr.t("החיבור נכשל. נסה שוב עוד רגע.", "החיבור נכשל. נסי שוב עוד רגע.")
                        }
                        busy = false
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (busy) "מתחבר..." else com.tamir.hamasa.domain.game.Addr.t("חבר ישירות", "חברי ישירות"), color = HColors.Parchment, fontWeight = FontWeight.Bold) }
    }
    val n = note
    if (n != null) Text(n, color = HColors.Parchment, fontSize = 14.sp)
}

private fun samsungStatusHe(s: ShStatus): String = when (s) {
    ShStatus.CONNECTED -> "מחובר ישירות"
    ShStatus.PARTIAL -> "מחובר ישירות. חלק מההרשאות לא אושרו."
    ShStatus.NO_PERMISSION -> "עוד לא אושרה קריאה"
    ShStatus.NEEDS_INSTALL -> "סמסונג הלת' לא מותקן"
    ShStatus.OLD_VERSION -> "צריך לעדכן את סמסונג הלת'"
    ShStatus.DISABLED -> "סמסונג הלת' כבוי בהגדרות הטלפון"
    ShStatus.NOT_INITIALIZED -> "צריך לפתוח את סמסונג הלת' ולסיים את ההגדרה הראשונה"
    ShStatus.DEV_MODE_OFF -> "מצב מפתח כבוי בסמסונג הלת'"
    ShStatus.ERROR -> "שגיאה בחיבור"
    ShStatus.UNAVAILABLE -> "לא מחובר"
}

/** The Activity behind a Compose context (LocalContext can be a ContextWrapper). */
private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c != null) {
        if (c is Activity) return c
        c = (c as? ContextWrapper)?.baseContext
    }
    return null
}

private fun formatTime(millis: Long): String =
    runCatching {
        DateTimeFormatter.ofPattern("dd.MM HH:mm").format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
    }.getOrDefault("")
