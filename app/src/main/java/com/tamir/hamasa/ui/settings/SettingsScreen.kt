package com.tamir.hamasa.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.cloud.CloudSync
import com.tamir.hamasa.data.backup.DataBackup
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.material3.Switch
import androidx.compose.foundation.layout.width
import com.tamir.hamasa.data.prefs.Focus
import com.tamir.hamasa.data.prefs.Profile
import com.tamir.hamasa.data.update.ReleaseInfo
import com.tamir.hamasa.data.update.UpdateCenter
import com.tamir.hamasa.data.update.UpdateNotifier
import com.tamir.hamasa.ui.common.HourDropdown
import com.tamir.hamasa.ui.common.focusLabelHe
import com.tamir.hamasa.ui.theme.HColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class SettingsViewModel : ViewModel() {
    val profile: StateFlow<Profile?> = AppGraph.prefs.profile.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val _cloudMessage = MutableStateFlow<String?>(null)
    val cloudMessage: StateFlow<String?> = _cloudMessage
    private val _cloudBusy = MutableStateFlow(false)
    val cloudBusy: StateFlow<Boolean> = _cloudBusy
    private val _lastPush = MutableStateFlow(0L)
    val lastPush: StateFlow<Long> = _lastPush
    private val _localSessions = MutableStateFlow(-1)
    val localSessions: StateFlow<Int> = _localSessions

    fun refreshCloudStatus(context: Context) {
        viewModelScope.launch {
            _lastPush.value = CloudSync.lastPush(context)
            _localSessions.value = runCatching { AppGraph.db.sessions().countAll() }.getOrDefault(0)
        }
    }

    fun backupNow(context: Context) {
        if (_cloudBusy.value) return
        viewModelScope.launch {
            _cloudBusy.value = true
            _cloudMessage.value = "מגבה..."
            val ok = CloudSync.pushAll(context.applicationContext)
            _lastPush.value = CloudSync.lastPush(context)
            _cloudMessage.value = if (ok) "הגיבוי הושלם. נרשם בענן." else "הגיבוי נכשל. אין רשת, או שהענן אינו עונה."
            _cloudBusy.value = false
        }
    }

    fun restoreFromCloud(context: Context) {
        if (_cloudBusy.value) return
        viewModelScope.launch {
            _cloudBusy.value = true
            _cloudMessage.value = "משחזר..."
            val restored = CloudSync.pullAll(context.applicationContext, fillMissing = true)
            _localSessions.value = runCatching { AppGraph.db.sessions().countAll() }.getOrDefault(0)
            _cloudMessage.value = if (restored) "הידד. הנתונים שוחזרו מהענן." else "לא נמצא בענן דבר שחסר כאן."
            _cloudBusy.value = false
        }
    }

    fun save(goal: Float, start: Float, quietFrom: Int, quietTo: Int, focus: Focus) {
        viewModelScope.launch {
            AppGraph.prefs.update { it.copy(goalWeightKg = goal, startWeightKg = start, quietFrom = quietFrom, quietTo = quietTo, focus = focus) }
            _message.value = "נשמר. סגור."
        }
    }

    /**
     * Full JSON export (DataBackup: sessions, sets, weights, meals, badges, inventory, templates, rewards, ledger,
     * measurements...) written to cacheDir/exports and shared as a file, so it can be saved and imported later.
     */
    fun export(context: Context) {
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val json = DataBackup.buildExportJson()
                    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                    File(dir, "hamasa_export.json").apply { writeText(json) }
                }.getOrNull()
            }
            if (file == null) { _message.value = com.tamir.hamasa.domain.game.Addr.t("הייצוא נכשל. נסה שוב.", "הייצוא נכשל. נסי שוב."); return@launch }
            val uri = runCatching { FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file) }.getOrNull()
            if (uri == null) { _message.value = com.tamir.hamasa.domain.game.Addr.t("הייצוא נכשל. נסה שוב.", "הייצוא נכשל. נסי שוב."); return@launch }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_SUBJECT, "המסע הגדול - ייצוא נתונים")
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching { context.startActivity(Intent.createChooser(send, "ייצוא נתונים").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
            _message.value = "הנתונים יוצאו. קובץ אחד, הכול בפנים."
        }
    }

    /** Restores an exported JSON file into an empty database (DataBackup.importJson refuses otherwise). */
    fun importFile(context: Context, uri: Uri) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
            }
            if (text == null) { _message.value = "הקובץ לא נקרא."; return@launch }
            val result = withContext(Dispatchers.IO) { runCatching { DataBackup.importJson(text) }.getOrDefault(DataBackup.ImportResult.BadFile) }
            _message.value = when (result) {
                is DataBackup.ImportResult.Ok -> "יובאו ${result.sessions} אימונים. הכול חזר למקום."
                DataBackup.ImportResult.NotEmpty -> "כבר יש כאן נתונים. ייבוא נעשה רק למכשיר ריק."
                DataBackup.ImportResult.BadFile -> "זה לא קובץ ייצוא של המסע."
            }
            if (result is DataBackup.ImportResult.Ok) CloudSync.pushSoon(context.applicationContext)
        }
    }

    fun setSound(on: Boolean) {
        viewModelScope.launch { AppGraph.prefs.update { it.copy(soundOn = on) } }
    }

    fun saveKcalGoal(kcal: Int) {
        viewModelScope.launch {
            // a goal typed by hand always wins: the energy calculator stops updating it
            AppGraph.prefs.update { it.copy(kcalGoal = kcal.coerceIn(800, 6000), kcalAuto = false) }
            com.tamir.hamasa.widget.Widgets.refreshAll(AppGraph.appContext)
            _message.value = "יעד הקלוריות נשמר. מקובל."
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onOpenEnergy: () -> Unit = {}) {
    val vm: SettingsViewModel = viewModel()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val cloudMessage by vm.cloudMessage.collectAsStateWithLifecycle()
    val cloudBusy by vm.cloudBusy.collectAsStateWithLifecycle()
    val lastPush by vm.lastPush.collectAsStateWithLifecycle()
    val localSessions by vm.localSessions.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.refreshCloudStatus(context) }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importFile(context, uri)
    }

    val p = profile ?: return
    var goalText by remember { mutableStateOf(trimKg(p.goalWeightKg)) }
    var startText by remember { mutableStateOf(trimKg(p.startWeightKg)) }
    var quietFrom by remember { mutableStateOf(p.quietFrom) }
    var quietTo by remember { mutableStateOf(p.quietTo) }
    var focus by remember { mutableStateOf(p.focus) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(p) {
        if (!loaded) {
            goalText = trimKg(p.goalWeightKg); startText = trimKg(p.startWeightKg)
            quietFrom = p.quietFrom; quietTo = p.quietTo; focus = p.focus
            loaded = true
        }
    }
    val updateAvailable by UpdateCenter.available.collectAsStateWithLifecycle()
    val updateLastCheck by UpdateCenter.lastCheck.collectAsStateWithLifecycle()
    val updateChecking by UpdateCenter.checking.collectAsStateWithLifecycle()
    val updateDownloading by UpdateCenter.downloading.collectAsStateWithLifecycle()
    val updateStatus by UpdateCenter.status.collectAsStateWithLifecycle()
    val updateProgress by UpdateCenter.progress.collectAsStateWithLifecycle()
    val updateAuto by UpdateCenter.autoUpdate.collectAsStateWithLifecycle()
    val updateError by UpdateCenter.installError.collectAsStateWithLifecycle()
    val updatingBuild by UpdateCenter.updatingBuild.collectAsStateWithLifecycle()

    val goal = goalText.replace(',', '.').toFloatOrNull()
    val start = startText.replace(',', '.').toFloatOrNull()
    val valid = goal != null && start != null && goal in 30f..300f && start in 30f..300f

    Column(
        Modifier
            .fillMaxSize()
            .background(HColors.Slate)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("הגדרות", style = MaterialTheme.typography.headlineMedium, color = HColors.Gold)
        Spacer(Modifier.height(16.dp))

        SectionTitle("משקל")
        OutlinedTextField(
            value = startText, onValueChange = { startText = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(6) },
            label = { Text("משקל התחלתי (ק\"ג)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = goalText, onValueChange = { goalText = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(6) },
            label = { Text("משקל יעד (ק\"ג)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(20.dp))
        SectionTitle("שעות שקט")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            HourDropdown(label = "מ-", hour = quietFrom) { quietFrom = it }
            HourDropdown(label = "עד", hour = quietTo) { quietTo = it }
        }

        Spacer(Modifier.height(20.dp))
        SectionTitle("דגש")
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Focus.values().forEach { f ->
                FilterChip(selected = focus == f, onClick = { focus = f }, label = { Text(focusLabelHe(f)) })
            }
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { if (goal != null && start != null) vm.save(goal, start, quietFrom, quietTo, focus) },
            enabled = valid,
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text("שמירה", fontWeight = FontWeight.Black, fontSize = 18.sp) }

        val msg = message
        if (msg != null) {
            Spacer(Modifier.height(8.dp))
            Text(msg, color = HColors.Parchment, modifier = Modifier.align(Alignment.CenterHorizontally))
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider(color = HColors.SlateLight)
        Spacer(Modifier.height(16.dp))
        SectionTitle("צלילים ואוכל")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("צלילים", color = HColors.Parchment, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Switch(checked = p.soundOn, onCheckedChange = { vm.setSound(it) })
        }
        Spacer(Modifier.height(8.dp))
        var kcalText by remember(p.kcalGoal) { mutableStateOf(p.kcalGoal.toString()) }
        val kcal = kcalText.toIntOrNull()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = kcalText, onValueChange = { kcalText = it.filter { c -> c.isDigit() }.take(4) },
                label = { Text("יעד קלוריות יומי") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { if (kcal != null) vm.saveKcalGoal(kcal) }, enabled = kcal != null && kcal in 800..6000 && kcal != p.kcalGoal) {
                Text("שמירה", color = HColors.Parchment, fontWeight = FontWeight.Bold)
            }
        }
        Text(
            if (p.kcalAuto) "היעד אוטומטי, מחושב במחשבון האנרגיה. שמירה כאן הופכת אותו לידני."
            else "יעד ידני. במחשבון האנרגיה אפשר לעבור ליעד מחושב.",
            color = HColors.ParchmentDim, fontSize = 13.sp
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onOpenEnergy, modifier = Modifier.fillMaxWidth()) {
            Text("מחשבון אנרגיה: גיל, פעילות, יעדים", color = HColors.Gold, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider(color = HColors.SlateLight)
        Spacer(Modifier.height(16.dp))
        SectionTitle("נתונים")
        Text("שום דבר לא נלקח ממך. אפשר לייצא לקובץ, ולייבא אותו למכשיר ריק.", color = HColors.ParchmentDim, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { vm.export(context) }, modifier = Modifier.fillMaxWidth()) {
            Text("ייצוא נתונים", color = HColors.Parchment, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { showImportDialog = true }, modifier = Modifier.fillMaxWidth()) {
            Text("ייבוא מקובץ", color = HColors.Parchment, fontWeight = FontWeight.Bold)
        }

        // "פנייה": אתה / את and the hero שמביולוק / שמביולוקה (ui/settings/AddressSettingsCard.kt)
        Spacer(Modifier.height(20.dp))
        AddressSettingsCard(userGender = p.userGender, characterVariant = p.characterVariant)

        // "החשבון שלי": the recovery code and restore from code (ui/settings/AccountCard.kt)
        Spacer(Modifier.height(20.dp))
        AccountCard()

        Spacer(Modifier.height(20.dp))
        CloudBackupCard(
            lastPush = lastPush,
            busy = cloudBusy,
            message = cloudMessage,
            onBackup = { vm.backupNow(context) },
            onRestore = { showRestoreDialog = true }
        )

        // "מוזיקה שמגיבה": Spotify connection and workout music (ui/settings/MusicSettingsCard.kt)
        Spacer(Modifier.height(20.dp))
        MusicSettingsCard()

        // "השעון": Galaxy Watch through Health Connect (ui/settings/WatchSettingsCard.kt)
        Spacer(Modifier.height(20.dp))
        WatchSettingsCard()

        // "בינה מלאכותית": Claude / Gemini provider choice and status (ui/settings/AiSettingsCard.kt)
        Spacer(Modifier.height(20.dp))
        AiSettingsCard()

        Spacer(Modifier.height(20.dp))
        UpdateCard(
            available = updateAvailable,
            lastCheck = updateLastCheck,
            checking = updateChecking,
            downloading = updateDownloading,
            progress = updateProgress,
            status = updateStatus,
            autoUpdate = updateAuto,
            installError = updateError,
            updatingBuild = updatingBuild,
            onAutoUpdate = { on -> UpdateCenter.setAutoUpdate(context, on) },
            onCheck = { UpdateCenter.check(context, manual = true) },
            onInstall = { UpdateCenter.downloadAndInstall(context) }
        )

        Spacer(Modifier.height(24.dp))
        Text(UpdateCenter.installedVersionName, color = HColors.ParchmentDim, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(16.dp))
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("ייבוא מקובץ") },
            text = {
                Text(
                    if (localSessions == 0) "בוחרים קובץ ייצוא של המסע. הכול ייטען למכשיר הזה."
                    else "כבר יש כאן אימונים. ייבוא נעשה רק למכשיר ריק, כדי ששום דבר לא יידרס."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showImportDialog = false
                        runCatching { importPicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
                    },
                    enabled = localSessions == 0
                ) { Text("בחירת קובץ", color = HColors.Gold, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { showImportDialog = false }) { Text("ביטול", color = HColors.ParchmentDim) } }
        )
    }

    if (showRestoreDialog) {
        val fresh = localSessions == 0
        AlertDialog(
            onDismissRequest = { showRestoreDialog = false },
            title = { Text("שחזור מהענן") },
            text = {
                Text(
                    if (fresh) "אין כאן אימונים. כל הנתונים מהענן יישמרו במכשיר הזה."
                    else "כבר יש כאן נתונים. השחזור ישלים נתונים שחסרים (משקלים, תגים, ציוד) ולא ימחק דבר. אימונים קיימים לא יוחלפו."
                )
            },
            confirmButton = {
                TextButton(onClick = { showRestoreDialog = false; vm.restoreFromCloud(context) }) { Text("שחזור", color = HColors.Gold, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreDialog = false }) { Text("ביטול", color = HColors.ParchmentDim) }
            }
        )
    }
}

@Composable
private fun CloudBackupCard(lastPush: Long, busy: Boolean, message: String?, onBackup: () -> Unit, onRestore: () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("גיבוי בענן", color = HColors.Gold, fontSize = 18.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(4.dp))
            Text("הנתונים נשמרים גם בענן, אוטומטית כל כמה שעות.", color = HColors.ParchmentDim, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                if (lastPush > 0L) "גיבוי אחרון: ${formatSyncTime(lastPush)}" else "עדיין לא בוצע גיבוי.",
                color = HColors.Parchment, fontSize = 14.sp
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onBackup,
                enabled = !busy,
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) { Text("גיבוי עכשיו", fontWeight = FontWeight.Black, fontSize = 16.sp) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onRestore, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("שחזור מהענן", color = HColors.Parchment, fontWeight = FontWeight.Bold)
            }
            if (message != null) {
                Spacer(Modifier.height(8.dp))
                Text(message, color = HColors.Parchment, fontSize = 14.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
}

@Composable
private fun UpdateCard(
    available: ReleaseInfo?,
    lastCheck: Long,
    checking: Boolean,
    downloading: Boolean,
    progress: Float,
    status: String?,
    autoUpdate: Boolean,
    installError: String?,
    updatingBuild: Int,
    onAutoUpdate: (Boolean) -> Unit,
    onCheck: () -> Unit,
    onInstall: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("עדכון גרסה", color = HColors.Gold, fontSize = 18.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(4.dp))
            Text("גרסה מותקנת: ${UpdateCenter.installedVersionName}", color = HColors.Parchment, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            if (!UpdateCenter.enabled) {
                Text("עדכון אוטומטי לא מוגדר", color = HColors.ParchmentDim, fontSize = 13.sp)
            } else {
                Text(
                    if (lastCheck > 0L) "בדיקה אחרונה: ${formatSyncTime(lastCheck)}" else "עדיין לא בוצעה בדיקה.",
                    color = HColors.ParchmentDim, fontSize = 13.sp
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("עדכון אוטומטי", color = HColors.Parchment, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (autoUpdate) "גרסה חדשה מותקנת לבד. בפעם הראשונה תופיע בקשת אישור אחת."
                            else "גרסה חדשה תחכה ללחיצה.",
                            color = HColors.ParchmentDim, fontSize = 12.sp
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = autoUpdate, onCheckedChange = onAutoUpdate)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (UpdateNotifier.canPost(LocalContext.current)) "התראות על עדכונים: פעיל"
                    else "התראות על עדכונים: כבוי. אין הרשאת התראות.",
                    color = HColors.ParchmentDim, fontSize = 13.sp
                )
                if (autoUpdate) BackgroundUpdateRow()
                if (updatingBuild > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text("מעדכן לגרסה $updatingBuild…", color = HColors.Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                if (installError != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(installError, color = HColors.ParchmentDim, fontSize = 13.sp)
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onCheck, enabled = !checking && !downloading, modifier = Modifier.fillMaxWidth()) {
                    Text(if (checking) "בודק..." else "בדיקה עכשיו", color = HColors.Parchment, fontWeight = FontWeight.Bold)
                }
                if (available != null) {
                    Spacer(Modifier.height(12.dp))
                    Text("גרסה ${available.build} זמינה", color = HColors.Gold, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text("גרסה חדשה. נו. מתקינים.", color = HColors.Parchment, fontSize = 14.sp)
                    if (available.notes.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(available.notes, color = HColors.ParchmentDim, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onInstall,
                        enabled = !downloading,
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) { Text(if (downloading) "מוריד..." else "הורדה והתקנה", fontWeight = FontWeight.Black, fontSize = 16.sp) }
                    if (downloading) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)),
                            color = HColors.Gold,
                            trackColor = HColors.Ink
                        )
                    }
                }
            }
            if (status != null) {
                Spacer(Modifier.height(8.dp))
                Text(status, color = HColors.Parchment, fontSize = 14.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
}

private fun formatSyncTime(millis: Long): String =
    runCatching {
        DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
    }.getOrDefault("")

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = HColors.Gold, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(6.dp))
}

private fun trimKg(kg: Float): String = if (kg == kg.toInt().toFloat()) kg.toInt().toString() else kg.toString()
