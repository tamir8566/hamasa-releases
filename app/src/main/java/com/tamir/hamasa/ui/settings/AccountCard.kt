package com.tamir.hamasa.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.cloud.Account
import com.tamir.hamasa.data.cloud.CloudSync
import com.tamir.hamasa.ui.theme.HColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Hebrew line for a restore result. Shared with onboarding ("יש לי קוד שחזור"). */
fun restoreResultHe(r: Account.RestoreResult): String = when (r) {
    Account.RestoreResult.OK -> "הידד. הכול חזר. אותו מסע, טלפון אחר."
    Account.RestoreResult.BAD_CODE -> "הקוד לא תקין. שתים עשרה אותיות וספרות, בשלוש קבוצות."
    Account.RestoreResult.NOT_FOUND -> "בענן אין דבר עם הקוד הזה. שום דבר לא השתנה."
    Account.RestoreResult.SERVER_NOT_READY -> "השרת עוד לא מוכן לשחזור לפי קוד. שום דבר לא השתנה."
    Account.RestoreResult.NETWORK -> "אין רשת, או שהענן לא עונה. שום דבר לא השתנה."
    Account.RestoreResult.FAILED -> "השחזור נעצר באמצע. הקוד נשמר. לנסות שוב."
    Account.RestoreResult.BACKUP_FAILED -> "הנתונים במכשיר לא הצליחו לעלות לענן, אז שום דבר לא הוחלף. צריך רשת, ואז עוד ניסיון."
}

/** Restore runs in the app scope (not the screen's): leaving settings midway must not cut it off. */
private object AccountUi {
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
}

/**
 * "החשבון שלי": the personal recovery code (data/cloud/Account.kt) with a copy button, and "שחזור מקוד", which
 * replaces the local data with the cloud data of another code after a confirm dialog.
 */
@Composable
fun AccountCard() {
    val context = LocalContext.current
    val busy by AccountUi.busy.collectAsStateWithLifecycle()
    // read again after a restore switched the code
    val code = remember(busy) { Account.profileCode(context) }
    val message by AccountUi.message.collectAsStateWithLifecycle()
    var showEnter by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    var confirmCode by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("החשבון שלי", color = HColors.Gold, fontSize = 18.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(4.dp))
            Text("קוד השחזור שלך:", color = HColors.ParchmentDim, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                code,
                color = HColors.Gold,
                fontSize = 26.sp,
                fontWeight = FontWeight.Black,
                style = TextStyle(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr),
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                com.tamir.hamasa.domain.game.Addr.t("שמור את הקוד. בטלפון חדש מקלידים אותו כדי לשחזר הכול.", "שמרי את הקוד. בטלפון חדש מקלידים אותו כדי לשחזר הכול."),
                color = HColors.Parchment, fontSize = 14.sp
            )
            Text(
                "מי שמחזיק את הקוד רואה את הנתונים שלך. לא לשתף.",
                color = HColors.ParchmentDim, fontSize = 13.sp
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { copied = copyToClipboard(context, code) },
                    modifier = Modifier.weight(1f)
                ) { Text(if (copied) "הועתק" else "העתקת קוד", color = HColors.Parchment, fontWeight = FontWeight.Bold) }
                OutlinedButton(
                    onClick = { typed = ""; showEnter = true },
                    enabled = !busy,
                    modifier = Modifier.weight(1f)
                ) { Text("שחזור מקוד", color = HColors.Parchment, fontWeight = FontWeight.Bold) }
            }
            if (busy) {
                Spacer(Modifier.height(8.dp))
                Text("משחזר... לא לסגור את האפליקציה.", color = HColors.Parchment, fontSize = 14.sp)
            }
            val m = message
            if (m != null && !busy) {
                Spacer(Modifier.height(8.dp))
                Text(m, color = HColors.Parchment, fontSize = 14.sp)
            }
        }
    }

    if (showEnter) {
        val canon = Account.normalize(typed)
        AlertDialog(
            onDismissRequest = { showEnter = false },
            title = { Text("שחזור מקוד") },
            text = {
                Column {
                    Text("מקלידים את הקוד מהטלפון הקודם.")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it.take(20) },
                        singleLine = true,
                        label = { Text("קוד שחזור") },
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr, fontSize = 18.sp),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (typed.isNotBlank() && canon == null) {
                        Spacer(Modifier.height(4.dp))
                        Text("שתים עשרה אותיות וספרות.", color = HColors.Ember, fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showEnter = false; confirmCode = canon },
                    enabled = canon != null
                ) { Text("המשך", color = HColors.Gold, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { showEnter = false }) { Text("ביטול", color = HColors.ParchmentDim) } }
        )
    }

    val target = confirmCode
    if (target != null) {
        AlertDialog(
            onDismissRequest = { confirmCode = null },
            title = { Text("להחליף את הנתונים?") },
            text = {
                val lastPush = remember(target) { CloudSync.lastPush(context) }
                val lastPushText = if (lastPush > 0L) {
                    "הגיבוי האחרון שהצליח: " + DateFormat.format("dd/MM/yyyy HH:mm", lastPush).toString() + "."
                } else {
                    "עוד לא היה גיבוי שהצליח מהמכשיר הזה."
                }
                Column {
                    Text(
                        "כל הנתונים במכשיר הזה יוחלפו בנתונים של הקוד $target מהענן. " +
                            "הקוד הנוכחי ($code) יפסיק לשמש את המכשיר הזה, אז כדאי לשמור אותו קודם."
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(lastPushText, color = HColors.ParchmentDim, fontSize = 13.sp)
                    Text(
                        "לפני ההחלפה יש גיבוי מלא. אם הוא נכשל, שום דבר לא מוחלף.",
                        color = HColors.ParchmentDim, fontSize = 13.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmCode = null
                    startRestore(target)
                }) { Text("החלפה", color = HColors.Gold, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { confirmCode = null }) { Text("ביטול", color = HColors.ParchmentDim) } }
        )
    }
}

private fun startRestore(code: String) {
    if (AccountUi.busy.value) return
    AccountUi.busy.value = true
    AccountUi.message.value = null
    AppGraph.scope.launch {
        val result = runCatching { Account.restoreFromCode(code) }.getOrDefault(Account.RestoreResult.FAILED)
        AccountUi.message.value = restoreResultHe(result)
        AccountUi.busy.value = false
    }
}

private fun copyToClipboard(context: Context, text: String): Boolean = runCatching {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("קוד שחזור", text))
    true
}.getOrDefault(false)
