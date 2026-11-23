package com.tamir.hamasa.ui.progress

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.db.MeasurementEntity
import com.tamir.hamasa.data.db.ProgressPhotoEntity
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.ui.theme.HColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.format.DateTimeFormatter
import java.util.Locale

class ProgressViewModel(app: Application) : AndroidViewModel(app) {
    private val dao get() = AppGraph.db.progress()

    val photos: StateFlow<List<ProgressPhotoEntity>> =
        dao.photos().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val measurements: StateFlow<List<MeasurementEntity>> =
        dao.measurements().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    /** File that the camera is writing into right now (kept across the camera activity). */
    var pendingPath: String? = null

    private fun photosDir(): File = File(getApplication<Application>().filesDir, "photos").apply { mkdirs() }

    /** A new empty file in files/photos/ and its FileProvider uri, for ActivityResultContracts.TakePicture. */
    fun newCameraTarget(): Uri? = try {
        val f = File(photosDir(), "photo_${System.currentTimeMillis()}.jpg")
        pendingPath = f.absolutePath
        val ctx = getApplication<Application>()
        FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
    } catch (e: Throwable) {
        pendingPath = null
        null
    }

    fun onCameraResult(ok: Boolean) {
        val path = pendingPath ?: return
        pendingPath = null
        val f = File(path)
        if (!ok || !f.exists() || f.length() == 0L) { runCatching { f.delete() }; return }
        savePhoto(path)
    }

    /** Copies a gallery image into files/photos/ (the gallery copy may disappear later). */
    fun onPicked(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val path = withContext(Dispatchers.IO) {
                runCatching {
                    val f = File(photosDir(), "photo_${System.currentTimeMillis()}.jpg")
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                        f.outputStream().use { out -> input.copyTo(out) }
                    }
                    if (f.exists() && f.length() > 0L) f.absolutePath else null
                }.getOrNull()
            }
            if (path != null) savePhoto(path) else _message.value = com.tamir.hamasa.domain.game.Addr.t("התמונה לא נשמרה. נסה שוב.", "התמונה לא נשמרה. נסי שוב.")
        }
    }

    private fun savePhoto(path: String) {
        viewModelScope.launch {
            dao.insertPhoto(ProgressPhotoEntity(dateEpochDay = Days.today(), filePath = path))
            runCatching { AppGraph.game.evaluateBadges(null) }
            _message.value = "צולם. נשמר. בעוד חודש נשווה."
        }
    }

    fun deletePhoto(p: ProgressPhotoEntity) {
        viewModelScope.launch {
            dao.deletePhoto(p)
            withContext(Dispatchers.IO) { runCatching { File(p.filePath).delete() } }
        }
    }

    fun saveMeasurement(m: MeasurementEntity) {
        viewModelScope.launch {
            dao.insertMeasurement(m)
            _message.value = "המידות נרשמו. הסנטימטרים לא משקרים."
        }
    }

    fun deleteMeasurement(m: MeasurementEntity) {
        viewModelScope.launch { dao.deleteMeasurement(m) }
    }

    fun dismissMessage() { _message.value = null }
}

private val CardShape = RoundedCornerShape(20.dp)
private val DateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yy")
private fun dateHe(day: Long): String = Days.of(day).format(DateFmt)

private fun fmtCm(v: Float): String {
    if (v <= 0f) return "-"
    val whole = v.toInt()
    return if (v == whole.toFloat()) whole.toString() else String.format(Locale.US, "%.1f", v)
}

/** Parses "82", "82.5", "82,5"; empty or invalid = 0 (not measured). */
private fun parseCm(s: String): Float = s.trim().replace(',', '.').toFloatOrNull()?.takeIf { it in 1f..300f } ?: 0f

/** "תמונות ומידות": monthly progress photos (same pose, side by side with the previous one) and body measurements. */
@Composable
fun ProgressScreen() {
    val vm: ProgressViewModel = viewModel()
    val photos by vm.photos.collectAsStateWithLifecycle()
    val measurements by vm.measurements.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var toDelete by remember { mutableStateOf<ProgressPhotoEntity?>(null) }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> vm.onCameraResult(ok) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> vm.onPicked(uri) }

    Column(
        Modifier.fillMaxSize().background(HColors.Slate).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("תמונות ומידות", color = HColors.Gold, style = MaterialTheme.typography.headlineMedium)
        Text("פעם בחודש. אותה תנוחה, אותו מקום, אותו אור. ככה רואים באמת.", color = HColors.ParchmentDim)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { vm.newCameraTarget()?.let { uri -> runCatching { camera.launch(uri) }.onFailure { vm.onCameraResult(false) } } },
                modifier = Modifier.weight(1f).height(52.dp),
                shape = CardShape,
                colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink)
            ) { Text(com.tamir.hamasa.domain.game.Addr.t("צלם עכשיו", "צלמי עכשיו"), fontWeight = FontWeight.Black) }
            OutlinedButton(
                onClick = { runCatching { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } },
                modifier = Modifier.weight(1f).height(52.dp),
                shape = CardShape,
                border = BorderStroke(2.dp, HColors.GoldDeep)
            ) { Text("מהגלריה", color = HColors.Gold, fontWeight = FontWeight.Bold) }
        }

        // Side by side: latest vs the one before it.
        val latest = photos.getOrNull(0)
        val previous = photos.getOrNull(1)
        if (latest == null) {
            Text("עדיין אין תמונות. הראשונה תמיד מביכה. זה בדיוק הטעם שלה.", color = HColors.ParchmentDim)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PhotoTile(latest, "עכשיו", Modifier.weight(1f), onLongDelete = { toDelete = latest })
                if (previous != null) PhotoTile(previous, "הקודמת", Modifier.weight(1f), onLongDelete = { toDelete = previous })
                else Box(Modifier.weight(1f))
            }
            if (photos.size > 2) Text("${photos.size} תמונות בסך הכול", color = HColors.ParchmentDim)
        }

        MeasurementsCard(onSave = { vm.saveMeasurement(it) })

        if (measurements.isNotEmpty()) {
            Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = HColors.SlateLight)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("היסטוריית מידות", color = HColors.Gold, style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth()) {
                        for (h in listOf("תאריך", "מותן", "חזה", "יד", "ירך", "אגן")) {
                            Text(h, color = HColors.ParchmentDim, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    measurements.take(12).forEach { m ->
                        Row(Modifier.fillMaxWidth()) {
                            Text(dateHe(m.dateEpochDay), color = HColors.Parchment, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            for (v in listOf(m.waistCm, m.chestCm, m.armCm, m.thighCm, m.hipsCm)) {
                                Text(fmtCm(v), color = HColors.Parchment, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { vm.dismissMessage() },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { vm.dismissMessage() }) { Text("סגור") } }
        )
    }
    toDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("למחוק את התמונה?") },
            text = { Text("מ-${dateHe(p.dateEpochDay)}. אין דרך חזרה.") },
            confirmButton = { TextButton(onClick = { vm.deletePhoto(p); toDelete = null }) { Text(com.tamir.hamasa.domain.game.Addr.t("מחק", "מחקי"), color = HColors.Danger) } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("ביטול") } }
        )
    }
}

@Composable
private fun PhotoTile(p: ProgressPhotoEntity, label: String, modifier: Modifier, onLongDelete: () -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        AsyncImage(
            model = File(p.filePath),
            contentDescription = label,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(0.75f).clip(RoundedCornerShape(16.dp)).background(HColors.Ink)
        )
        Text("$label · ${dateHe(p.dateEpochDay)}", color = HColors.Parchment, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onLongDelete) { Text(com.tamir.hamasa.domain.game.Addr.t("מחק", "מחקי"), color = HColors.ParchmentDim) }
    }
}

@Composable
private fun MeasurementsCard(onSave: (MeasurementEntity) -> Unit) {
    var waist by rememberSaveable { mutableStateOf("") }
    var chest by rememberSaveable { mutableStateOf("") }
    var arm by rememberSaveable { mutableStateOf("") }
    var thigh by rememberSaveable { mutableStateOf("") }
    var hips by rememberSaveable { mutableStateOf("") }
    val any = listOf(waist, chest, arm, thigh, hips).any { parseCm(it) > 0f }
    Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = HColors.SlateLight), border = BorderStroke(2.dp, HColors.GoldDeep)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("מידות היום (ס\"מ)", color = HColors.Gold, style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CmField("מותן", waist, Modifier.weight(1f)) { waist = it }
                CmField("חזה", chest, Modifier.weight(1f)) { chest = it }
                CmField("יד", arm, Modifier.weight(1f)) { arm = it }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CmField("ירך", thigh, Modifier.weight(1f)) { thigh = it }
                CmField("אגן", hips, Modifier.weight(1f)) { hips = it }
                Box(Modifier.weight(1f))
            }
            Button(
                onClick = {
                    onSave(MeasurementEntity(dateEpochDay = Days.today(), waistCm = parseCm(waist), chestCm = parseCm(chest),
                        armCm = parseCm(arm), thighCm = parseCm(thigh), hipsCm = parseCm(hips)))
                    waist = ""; chest = ""; arm = ""; thigh = ""; hips = ""
                },
                enabled = any,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = CardShape,
                colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink)
            ) { Text(com.tamir.hamasa.domain.game.Addr.t("שמור מידות", "שמרי מידות"), fontWeight = FontWeight.Black) }
        }
    }
}

@Composable
private fun CmField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() || it == '.' || it == ',' }.take(5)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier
    )
}
