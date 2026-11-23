package com.tamir.hamasa.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tamir.hamasa.data.music.MusicChoice
import com.tamir.hamasa.data.music.MusicPrefs
import com.tamir.hamasa.data.music.MusicSettings
import com.tamir.hamasa.data.music.MusicSlot
import com.tamir.hamasa.data.music.SpotifyAuth
import com.tamir.hamasa.data.music.SpotifyConfig
import com.tamir.hamasa.data.music.SpotifyPlayer
import com.tamir.hamasa.data.music.SpotifyPlaylist
import com.tamir.hamasa.data.music.SpotifyTrack
import com.tamir.hamasa.data.music.WorkoutMusic
import com.tamir.hamasa.ui.theme.HColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** "מוזיקה" card of the settings screen: Spotify connection and the three music choices of a workout. */
class MusicSettingsViewModel : ViewModel() {
    val connected: StateFlow<Boolean> = SpotifyAuth.connected
    val authStatus: StateFlow<String?> = SpotifyAuth.status
    val authBusy: StateFlow<Boolean> = SpotifyAuth.busy
    val prefs: StateFlow<MusicPrefs> = MusicSettings.state

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val _playlists = MutableStateFlow<List<SpotifyPlaylist>>(emptyList())
    val playlists: StateFlow<List<SpotifyPlaylist>> = _playlists
    private val _tracks = MutableStateFlow<List<SpotifyTrack>>(emptyList())
    val tracks: StateFlow<List<SpotifyTrack>> = _tracks
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    init {
        viewModelScope.launch(Dispatchers.IO) { runCatching { SpotifyAuth.ensureLoaded() } }
    }

    fun connect(context: Context) {
        _message.value = null
        SpotifyAuth.beginLogin(context)
    }

    fun disconnect() {
        _message.value = null
        _playlists.value = emptyList()
        _tracks.value = emptyList()
        SpotifyAuth.disconnect()
    }

    fun setEnabled(on: Boolean) = MusicSettings.setEnabled(on)
    fun setResumeAfter(on: Boolean) = MusicSettings.setResumeAfter(on)

    fun choose(slot: MusicSlot, choice: MusicChoice?) {
        MusicSettings.setChoice(slot, choice)
        _message.value = if (choice == null) "נמחק." else "נבחר: ${choice.name}"
    }

    /** Opens the picker: his playlists (once per screen) and a clean search. */
    fun openPicker() {
        _tracks.value = emptyList()
        if (_playlists.value.isNotEmpty()) return
        viewModelScope.launch {
            _loading.value = true
            val list = SpotifyPlayer.myPlaylists()
            _playlists.value = list
            if (list.isEmpty()) _message.value = SpotifyPlayer.lastError?.let { SpotifyPlayer.errorLineHe(it) } ?: "לא נמצאו פלייליסטים."
            _loading.value = false
        }
    }

    fun search(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch {
            _loading.value = true
            _tracks.value = SpotifyPlayer.searchTracks(query)
            _loading.value = false
        }
    }

    /** "נסה עכשיו": plays the workout playlist (or the boss song) so he hears that it works. */
    fun test() {
        val p = prefs.value
        val choice = p.playlist ?: p.boss ?: p.lastSet
        if (choice == null) {
            _message.value = "קודם בוחרים מה לנגן. אחר כך בודקים."
            return
        }
        viewModelScope.launch {
            _message.value = "שולח לספוטיפיי..."
            val ok = if (choice == p.playlist) WorkoutMusic.playWorkoutPlaylist(choice, p) else SpotifyPlayer.play(choice.uri)
            _message.value = if (ok) "מתנגן: ${choice.name}. עובד." else SpotifyPlayer.errorLineHe(SpotifyPlayer.lastError)
        }
    }

    fun openSpotify(context: Context) {
        val ok = runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("spotify:")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (!ok) _message.value = "ספוטיפיי לא מותקן בטלפון."
    }
}

@Composable
fun MusicSettingsCard() {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("מוזיקה", color = HColors.Gold, fontSize = 18.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(4.dp))
            if (!SpotifyConfig.enabled) {
                Text(
                    "החיבור לספוטיפיי עוד לא הוגדר. צריך ליצור אפליקציה באתר המפתחים של ספוטיפיי ולהכניס את המזהה שלה לקוד. " +
                        "ההוראות המלאות במסמך המוזיקה שבתיקיית המסמכים של הפרויקט.",
                    color = HColors.ParchmentDim, fontSize = 13.sp
                )
            } else {
                MusicSettingsBody()
            }
        }
    }
}

@Composable
private fun ColumnScope.MusicSettingsBody() {
    val vm: MusicSettingsViewModel = viewModel()
    val connected by vm.connected.collectAsStateWithLifecycle()
    val authStatus by vm.authStatus.collectAsStateWithLifecycle()
    val authBusy by vm.authBusy.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pickerSlot by remember { mutableStateOf<MusicSlot?>(null) }

    Text(
        "שיר כניסה לסט הכבד, קצב עולה בסט האחרון. דורש ספוטיפיי פרימיום.",
        color = HColors.ParchmentDim, fontSize = 13.sp
    )
    Spacer(Modifier.height(10.dp))

    if (!connected) {
        Button(
            onClick = { vm.connect(context) },
            enabled = !authBusy,
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) { Text(if (authBusy) "מתחבר..." else "התחבר לספוטיפיי", fontWeight = FontWeight.Black, fontSize = 16.sp) }
    } else {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("מוזיקה באימון", color = HColors.Parchment, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Switch(checked = prefs.enabled, onCheckedChange = { vm.setEnabled(it) })
        }
        Spacer(Modifier.height(8.dp))
        ChoiceRow("פלייליסט אימון", prefs.playlist, onPick = { pickerSlot = MusicSlot.PLAYLIST; vm.openPicker() }, onClear = { vm.choose(MusicSlot.PLAYLIST, null) })
        if (prefs.playlist != null) {
            ModeRow(
                label = "סדר",
                options = listOf("true" to "ערבוב", "false" to "לפי הסדר"),
                selected = prefs.shuffle.toString(),
                onSelect = { MusicSettings.setShuffle(it == "true") }
            )
            ModeRow(
                label = "לופ",
                options = listOf("off" to "בלי", "context" to "כל הפלייליסט", "track" to "שיר אחד"),
                selected = prefs.repeat,
                onSelect = { MusicSettings.setRepeat(it) }
            )
            Text(
                "ערבוב חכם של ספוטיפיי לא זמין לאפליקציות חיצוניות. אפשר להדליק אותו ידנית בתוך ספוטיפיי.",
                color = HColors.ParchmentDim, fontSize = 12.sp
            )
            Spacer(Modifier.height(4.dp))
        }
        ChoiceRow("שיר כניסת בוס", prefs.boss, onPick = { pickerSlot = MusicSlot.BOSS; vm.openPicker() }, onClear = { vm.choose(MusicSlot.BOSS, null) })
        ChoiceRow("שיר לסט האחרון", prefs.lastSet, onPick = { pickerSlot = MusicSlot.LAST_SET; vm.openPicker() }, onClear = { vm.choose(MusicSlot.LAST_SET, null) })
        Text(
            "הסט הכבד: הסט הראשון של התרגיל המורכב עם המשקל הגבוה ביותר באימון. השיר מתחיל במנוחה שלפניו.",
            color = HColors.ParchmentDim, fontSize = 12.sp
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("בסוף האימון: חזרה לפלייליסט", color = HColors.Parchment, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Switch(checked = prefs.resumeAfter, onCheckedChange = { vm.setResumeAfter(it) })
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.test() }, modifier = Modifier.weight(1f)) {
                Text("נסה עכשיו", color = HColors.Gold, fontWeight = FontWeight.Bold)
            }
            OutlinedButton(onClick = { vm.openSpotify(context) }, modifier = Modifier.weight(1f)) {
                Text("פתח את ספוטיפיי", color = HColors.Parchment, fontWeight = FontWeight.Bold)
            }
        }
        Text(
            "אם ספוטיפיי לא מנגן: פותחים אותו פעם אחת בטלפון, ואז הוא שומע את האפליקציה.",
            color = HColors.ParchmentDim, fontSize = 12.sp
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { vm.disconnect() }, modifier = Modifier.align(Alignment.End)) {
            Text("התנתק מספוטיפיי", color = HColors.ParchmentDim)
        }
    }

    val line = message ?: authStatus
    if (line != null) {
        Spacer(Modifier.height(6.dp))
        Text(line, color = HColors.Parchment, fontSize = 14.sp)
    }

    val slot = pickerSlot
    if (slot != null && connected) {
        MusicPickerDialog(
            vm = vm,
            slot = slot,
            onPick = { choice -> vm.choose(slot, choice); pickerSlot = null },
            onClose = { pickerSlot = null }
        )
    }
}

@Composable
private fun ChoiceRow(label: String, choice: MusicChoice?, onPick: () -> Unit, onClear: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = HColors.Parchment, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(choice?.name?.ifBlank { null } ?: "לא נבחר", color = if (choice != null) HColors.Gold else HColors.ParchmentDim, fontSize = 13.sp)
        }
        if (choice != null) {
            TextButton(onClick = onClear) { Text("נקה", color = HColors.ParchmentDim) }
        }
        OutlinedButton(onClick = onPick) { Text("בחר", color = HColors.Gold, fontWeight = FontWeight.Bold) }
    }
}

/** One small label + a row of pill options; the selected one is gold. */
@Composable
private fun ModeRow(label: String, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(label, color = HColors.Parchment, fontSize = 14.sp, modifier = Modifier.width(36.dp))
        options.forEach { (key, text) ->
            val on = key == selected
            Text(
                text,
                color = if (on) HColors.Ink else HColors.Parchment,
                fontSize = 14.sp,
                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier
                    .background(if (on) HColors.Gold else HColors.Slate, RoundedCornerShape(50))
                    .clickable { onSelect(key) }
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            )
        }
    }
}

/** Playlists only for the workout playlist; track search + playlists for the boss and last-set slots. */
@Composable
private fun MusicPickerDialog(vm: MusicSettingsViewModel, slot: MusicSlot, onPick: (MusicChoice) -> Unit, onClose: () -> Unit) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val withSearch = slot != MusicSlot.PLAYLIST
    LaunchedEffect(slot) { query = "" }

    AlertDialog(
        onDismissRequest = onClose,
        containerColor = HColors.SlateLight,
        titleContentColor = HColors.Parchment,
        title = {
            Text(
                when (slot) {
                    MusicSlot.PLAYLIST -> "פלייליסט אימון"
                    MusicSlot.BOSS -> "שיר כניסת בוס"
                    MusicSlot.LAST_SET -> "שיר לסט האחרון"
                }
            )
        },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                if (withSearch) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it.take(80) },
                            label = { Text("שם השיר או האמן") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { vm.search(query) }),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(6.dp))
                        OutlinedButton(onClick = { vm.search(query) }, enabled = query.isNotBlank() && !loading) {
                            Text("חפש", color = HColors.Gold, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    tracks.forEach { t ->
                        val label = if (t.artist.isNotBlank()) t.name + " · " + t.artist else t.name
                        PickRow(title = t.name, sub = t.artist) { onPick(MusicChoice(t.uri, label)) }
                    }
                    if (playlists.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("או פלייליסט שלך", color = HColors.Gold, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(Modifier.height(4.dp))
                    }
                }
                if (loading) Text("טוען...", color = HColors.ParchmentDim, fontSize = 14.sp)
                playlists.forEach { p ->
                    PickRow(title = p.name, sub = if (p.total > 0) "${p.total} שירים" else "") { onPick(MusicChoice(p.uri, p.name, p.total)) }
                }
                if (!loading && playlists.isEmpty() && !withSearch) {
                    Text("לא נמצאו פלייליסטים.", color = HColors.ParchmentDim, fontSize = 14.sp)
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onClose) { Text("סגור", color = HColors.ParchmentDim) } }
    )
}

@Composable
private fun PickRow(title: String, sub: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .background(HColors.Slate, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(title, color = HColors.Parchment, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        if (sub.isNotBlank()) Text(sub, color = HColors.ParchmentDim, fontSize = 12.sp)
    }
}
