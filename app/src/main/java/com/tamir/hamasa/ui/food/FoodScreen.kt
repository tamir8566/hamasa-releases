package com.tamir.hamasa.ui.food

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tamir.hamasa.data.AppGraph
import com.tamir.hamasa.data.ai.AiClient
import com.tamir.hamasa.data.ai.MealEstimator
import com.tamir.hamasa.data.ai.MealSuggester
import com.tamir.hamasa.data.db.MealEntity
import com.tamir.hamasa.data.repo.MealGame
import com.tamir.hamasa.data.repo.SavedMeal
import com.tamir.hamasa.data.repo.SavedMeals
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.domain.food.FoodItem
import com.tamir.hamasa.domain.food.MacroTargets
import com.tamir.hamasa.domain.food.Nutrition
import com.tamir.hamasa.ui.theme.HColors
import com.tamir.hamasa.widget.Widgets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/** Food log: manual entry, plus photo / text -> Gemini estimate (stage 2 F). Photos are never kept. */
@OptIn(ExperimentalCoroutinesApi::class)
class FoodViewModel : ViewModel() {
    /** The day the screen shows. Follows the clock: refreshed when the screen is shown, every minute and before each insert. */
    private val _day = MutableStateFlow(Days.today())
    val day: StateFlow<Long> = _day
    private val today: Long get() = _day.value
    val meals = _day.flatMapLatest { d -> AppGraph.db.meals().forDay(d) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val profile = AppGraph.prefs.profile.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val bodyweight: StateFlow<Float?> = AppGraph.db.weights().latest().map { it?.kg }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    /** "ארוחות קבועות" from Prefs.savedMealsJson. */
    val saved: StateFlow<List<SavedMeal>> = AppGraph.prefs.profile.map { it.savedMealsJson }.distinctUntilChanged()
        .map { SavedMeals.decode(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _next = MutableStateFlow<NextMealState>(NextMealState.Idle)
    val next: StateFlow<NextMealState> = _next

    /** A just-logged meal whose description was logged 3+ times: "לשמור כארוחה קבועה?" (shown once per description). */
    private val _offer = MutableStateFlow<MealEntity?>(null)
    val offer: StateFlow<MealEntity?> = _offer

    private val _phase = MutableStateFlow<PhotoPhase>(PhotoPhase.Idle)
    val phase: StateFlow<PhotoPhase> = _phase

    /** Manual form request: null = closed; the string is the reason line ("" = opened by the user). */
    private val _manual = MutableStateFlow<String?>(null)
    val manual: StateFlow<String?> = _manual

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val _merchant = MutableStateFlow(false)
    val merchant: StateFlow<Boolean> = _merchant

    /** Description typed for a text estimate that failed: prefills the manual form (null = none). */
    private val _manualPrefill = MutableStateFlow<String?>(null)
    val manualPrefill: StateFlow<String?> = _manualPrefill

    /**
     * File the camera is writing into right now. Deleted as soon as the estimate returns. Kept in plain
     * SharedPreferences too, so a photo survives the camera app killing our process.
     */
    private var pendingPath: String? = loadPending()

    /** The running photo / text estimate (cancelled by "ביטול"). */
    private var estimateJob: Job? = null

    private var pendingAt: Long = runCatching { pendingPrefs().getLong("pendingAt", 0L) }.getOrDefault(0L)

    /** The photo being estimated right now (never deleted by the init cleanup). */
    @Volatile
    private var estimatingPath: String? = null

    init {
        val keep = pendingPath
        viewModelScope.launch {
            refreshDay()
            _merchant.value = MealGame.merchantActive(today)
            // energy calculator: an automatic calorie goal follows the latest weight / workouts / real-data TDEE
            runCatching { com.tamir.hamasa.data.repo.EnergyData.syncAutoGoal() }
            // once: the foods of the meals he already logged go into the food base
            withContext(Dispatchers.IO) { runCatching { com.tamir.hamasa.data.food.FoodBaseStore.backfillOnce() } }
            // leftovers of a run that died mid-estimate: meal photos are never kept (except the one the camera
            // is still writing into, or just wrote into before the process died)
            withContext(Dispatchers.IO) {
                runCatching {
                    mealsDir().listFiles()?.forEach { f ->
                        val path = f.absolutePath
                        if (path != keep && path != pendingPath && path != estimatingPath) f.delete()
                    }
                }
            }
        }
    }

    /** True while he looks at (and adds meals to) a day before today: the clock no longer moves the screen. */
    private var pinned = false

    /** Shows another day (never after today). Meals added from now on belong to that day. */
    fun setDay(d: Long) {
        val t = Days.today()
        val target = d.coerceAtMost(t)
        pinned = target < t
        if (_day.value != target) {
            _day.value = target
            viewModelScope.launch { _merchant.value = MealGame.merchantActive(target) }
        }
    }

    fun shiftDay(delta: Int) = setDay(_day.value + delta)

    /** The day a new meal is saved on, and its time: a past day gets noon (the real time of that meal is not known). */
    private fun mealDay(): Long { refreshDay(); return _day.value }
    private fun mealMillis(day: Long): Long =
        if (day >= Days.today()) System.currentTimeMillis()
        else java.time.LocalDate.ofEpochDay(day).atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** Moves the screen to the current day (after midnight the new day starts empty), unless a past day was picked. */
    fun refreshDay() {
        if (pinned) return
        val d = Days.today()
        if (_day.value != d) {
            _day.value = d
            viewModelScope.launch { _merchant.value = MealGame.merchantActive(d) }
        }
    }

    private fun pendingPrefs() = AppGraph.appContext.getSharedPreferences("food_camera", android.content.Context.MODE_PRIVATE)

    private fun loadPending(): String? = runCatching {
        val sp = pendingPrefs()
        val path = sp.getString("pending", null)
        val at = sp.getLong("pendingAt", 0L)
        // a camera result never comes back after this long: forget it (the file is cleaned up with the rest)
        if (path != null && System.currentTimeMillis() - at < PENDING_MAX_MS) path else {
            if (path != null) sp.edit().remove("pending").remove("pendingAt").apply()
            null
        }
    }.getOrNull()

    private fun setPending(path: String?) {
        pendingPath = path
        pendingAt = if (path != null) System.currentTimeMillis() else 0L
        runCatching {
            val e = pendingPrefs().edit()
            if (path != null) e.putString("pending", path).putLong("pendingAt", pendingAt) else e.remove("pending").remove("pendingAt")
            e.apply()
        }
    }

    private fun mealsDir(): File = File(AppGraph.appContext.cacheDir, "meals").apply { mkdirs() }

    fun aiReady(): Boolean = AiClient.enabled

    /** True when the AI can be used; otherwise opens the manual form with the reason. */
    fun requireAi(): Boolean {
        if (aiReady()) return true
        openManual(NO_KEY)
        return false
    }

    fun openManual(reason: String = "") { _manual.value = reason }
    fun closeManual() { _manual.value = null; _manualPrefill.value = null }
    fun dismissMessage() { _message.value = null }

    /** A new empty file in cache/meals/ and its FileProvider uri for TakePicture; null opens the manual form. */
    fun newCameraTarget(): Uri? {
        if (!aiReady()) { openManual(NO_KEY); return null }
        // a camera is already open for a photo (double tap, widget + button): ignore the second launch
        if (pendingPath != null && System.currentTimeMillis() - pendingAt < DOUBLE_LAUNCH_MS) return null
        if (_phase.value is PhotoPhase.Loading) return null
        return try {
            val f = File(mealsDir(), "meal_${System.currentTimeMillis()}.jpg")
            setPending(f.absolutePath)
            val ctx = AppGraph.appContext
            FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
        } catch (e: Throwable) {
            setPending(null)
            openManual(FAILED)
            null
        }
    }

    fun onCameraResult(ok: Boolean) {
        val path = pendingPath ?: return
        setPending(null)
        val f = File(path)
        if (!ok || !f.exists() || f.length() == 0L) { runCatching { f.delete() }; return }
        estimatePhoto(f)
    }

    /** Gallery pick: copied into cache/meals/ (then handled exactly like a camera photo, and deleted). */
    fun onPicked(uri: Uri?) {
        if (uri == null) return
        if (!aiReady()) { openManual(NO_KEY); return }
        viewModelScope.launch {
            val f = File(mealsDir(), "meal_${System.currentTimeMillis()}.jpg")
            val copied = withContext(Dispatchers.IO) {
                runCatching {
                    AppGraph.appContext.contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { out -> input.copyTo(out) } }
                    f.exists() && f.length() > 0L
                }.getOrDefault(false)
            }
            if (copied) estimatePhoto(f) else { withContext(Dispatchers.IO) { runCatching { f.delete() } }; openManual(FAILED) }
        }
    }

    /**
     * Runs one estimate with a hard limit of [ESTIMATE_TIMEOUT_MS]. The HTTP call may block a thread and ignore
     * cancellation, so it runs in the app scope and we only wait for it; too slow counts as Failed.
     */
    private suspend fun estimateWithTimeout(block: suspend () -> MealEstimator.Result): MealEstimator.Result {
        val job = AppGraph.scope.async { runCatching { block() }.getOrDefault(MealEstimator.Result.Failed) }
        return try {
            withTimeoutOrNull(ESTIMATE_TIMEOUT_MS) { job.await() } ?: run { job.cancel(); MealEstimator.Result.Failed }
        } catch (e: CancellationException) {
            job.cancel()
            throw e
        }
    }

    private fun estimatePhoto(f: File) {
        estimateJob?.cancel()
        _phase.value = PhotoPhase.Loading(com.tamir.hamasa.domain.game.CharacterNames.g("מסתכל על הצלחת. בעיון."))
        estimatingPath = f.absolutePath
        estimateJob = viewModelScope.launch {
            val result = try {
                val bytes = MealEstimator.downscaleJpeg(f)
                if (bytes == null) MealEstimator.Result.Failed else estimateWithTimeout { MealEstimator.fromImage(bytes) }
            } finally {
                // never stored: the photo goes away right after the answer, success, failure or cancel
                estimatingPath = null
                runCatching { f.delete() }
            }
            handle(result, fromPhoto = true, description = "")
        }
    }

    /** "שתי פיתות עם חומוס" -> the same JSON estimate, text only. */
    fun describe(text: String) {
        if (text.isBlank()) return
        // the food base first: when every food in the text is known, the answer is computed on the phone
        val local = com.tamir.hamasa.data.food.FoodBaseStore.parse(text)
        if (local != null) {
            estimateJob?.cancel()
            _phase.value = PhotoPhase.Review(local, 0, BASE_NOTE, fromPhoto = false, descriptionHe = text.trim())
            return
        }
        if (!aiReady()) { _manualPrefill.value = text.trim().take(120); openManual(NO_KEY); return }
        estimateJob?.cancel()
        _phase.value = PhotoPhase.Loading(com.tamir.hamasa.domain.game.CharacterNames.g("קורא את התפריט. לאט."))
        estimateJob = viewModelScope.launch {
            val r = estimateWithTimeout { MealEstimator.fromText(text) }
            handle(r, fromPhoto = false, description = text.trim())
        }
    }

    /** "ביטול" on the loading dialog: the estimate is dropped and the screen goes back to normal. */
    fun cancelEstimate() {
        estimateJob?.cancel()
        estimateJob = null
        _phase.value = PhotoPhase.Idle
    }

    private fun handle(r: MealEstimator.Result, fromPhoto: Boolean, description: String) {
        // a failed text estimate keeps what was typed: the manual form opens with it
        if (r !is MealEstimator.Result.Ok && description.isNotBlank()) _manualPrefill.value = description.take(120)
        when (r) {
            is MealEstimator.Result.Ok -> {
                // foods the base already knows get its numbers (same food, same numbers); the rest stay the AI's
                val fixed = com.tamir.hamasa.data.food.FoodBaseStore.applyKnown(r.items)
                val note = if (fixed.second > 0) (r.noteHe + " " + baseKnownNote(fixed.second, r.items.size)).trim() else r.noteHe
                _phase.value = PhotoPhase.Review(fixed.first, Nutrition.sumKcal(r.items), note, fromPhoto, description)
            }
            MealEstimator.Result.NoKey -> { _phase.value = PhotoPhase.Idle; openManual(NO_KEY) }
            MealEstimator.Result.NotFood -> { _phase.value = PhotoPhase.Idle; openManual("לא הצלחתי לזהות. רושמים ביד.") }
            MealEstimator.Result.Failed -> { _phase.value = PhotoPhase.Idle; openManual(FAILED) }
        }
    }

    fun cancelReview() { _phase.value = PhotoPhase.Idle }

    /** Saves the reviewed estimate as one meal. A photo is source "photo"; a text estimate stays "manual". */
    fun saveReview(items: List<FoodItem>, description: String, keepAsFixed: Boolean = false) {
        val review = _phase.value as? PhotoPhase.Review ?: return
        _phase.value = PhotoPhase.Idle
        viewModelScope.launch {
            refreshDay()
            val mDay = mealDay()
            val meal = MealEntity(
                dateEpochDay = mDay, atMillis = mealMillis(mDay),
                description = description.ifBlank { items.joinToString(", ") { it.nameHe } }.take(120),
                kcal = Nutrition.sumKcal(items).coerceAtLeast(0),
                proteinG = Nutrition.sumProtein(items), carbsG = Nutrition.sumCarbs(items), fatG = Nutrition.sumFat(items),
                source = if (review.fromPhoto) "photo" else "manual",
                aiKcal = review.aiKcal,
                itemsJson = MealEstimator.encodeItems(items)
            )
            val id = AppGraph.db.meals().insert(meal)
            // new foods of this meal go into the food base, so next time they need no AI
            runCatching { com.tamir.hamasa.data.food.FoodBaseStore.learn(items) }
            // game rewards (the sly merchant, good guesses) only for today's meals
            val res = if (mDay == Days.today()) runCatching { MealGame.onMealSaved(meal.copy(id = id)) }.getOrNull() else null
            _message.value = when {
                res?.merchantDefeated == true -> "הסוחר הערמומי נפל. הצלחת דיברה, הוא שתק."
                res != null && res.merchantDamage > 0 -> "נרשם. הסוחר הערמומי ספג מכה. הוא מתלונן על המחיר."
                res?.goodGuess == true && review.fromPhoto -> "נרשם. הניחוש החזיק. התמונה נמחקה."
                review.fromPhoto -> "נרשם. התמונה נמחקה. הצלחת נשארת בזיכרון. רק שלי."
                else -> "נרשם. מילים הפכו למספרים."
            }
            if (keepAsFixed) {
                val ok = runCatching { SavedMeals.save(meal.description, items, meal.kcal, meal.proteinG, meal.carbsG, meal.fatG) }.getOrDefault(false)
                _message.value = if (ok) "נרשם, ונשמר כארוחה קבועה. בפעם הבאה: נגיעה אחת." else FIXED_FULL
            } else {
                offerIfRepeated(meal.copy(id = id))
            }
            _next.value = NextMealState.Idle
            _merchant.value = MealGame.merchantActive(today)
            com.tamir.hamasa.data.cloud.CloudSync.pushSoon(AppGraph.appContext)
            Widgets.refreshAll(AppGraph.appContext)
        }
    }

    fun add(f: MealForm) {
        viewModelScope.launch {
            refreshDay()
            val mDay = mealDay()
            val meal = MealEntity(
                dateEpochDay = mDay, atMillis = mealMillis(mDay), description = f.description, kcal = f.kcal,
                proteinG = f.proteinG, carbsG = f.carbsG, fatG = f.fatG, source = "manual"
            )
            val id = AppGraph.db.meals().insert(meal)
            offerIfRepeated(meal.copy(id = id))
            _next.value = NextMealState.Idle
            com.tamir.hamasa.data.cloud.CloudSync.pushSoon(AppGraph.appContext)
            Widgets.refreshAll(AppGraph.appContext)
        }
    }

    // ------------------------------------------------------------ "ארוחות קבועות"

    private suspend fun offerIfRepeated(meal: MealEntity) {
        if (SavedMeals.shouldOffer(meal.description)) _offer.value = meal
    }

    fun dismissOffer() { _offer.value = null }

    /** Saves a logged meal (row long-press, edit dialog, or the automatic offer) as a saved meal. */
    fun saveAsFixed(m: MealEntity) {
        _offer.value = null
        viewModelScope.launch {
            val ok = runCatching { SavedMeals.saveFromMeal(m) }.getOrDefault(false)
            _message.value = if (ok) "נשמר בארוחות הקבועות. נגיעה אחת, והיא שוב בצלחת." else FIXED_FULL
            com.tamir.hamasa.data.cloud.CloudSync.pushSoon(AppGraph.appContext)
        }
    }

    /** One tap on a saved meal: logged for today like a manual meal (never a photo meal: no photo bonuses). */
    fun logSaved(s: SavedMeal) {
        viewModelScope.launch {
            refreshDay()
            val mDay = mealDay()
            val meal = SavedMeals.toMeal(s, mDay).copy(atMillis = mealMillis(mDay))
            val id = AppGraph.db.meals().insert(meal)
            if (mDay == Days.today()) runCatching { MealGame.onMealSaved(meal.copy(id = id)) }
            _message.value = "נרשם. ${meal.description}, ${meal.kcal} קלוריות. " + com.tamir.hamasa.domain.game.Addr.t("אותה ארוחה, אותו גיבור.", "אותה ארוחה, אותה גיבורה.")
            _next.value = NextMealState.Idle
            com.tamir.hamasa.data.cloud.CloudSync.pushSoon(AppGraph.appContext)
            Widgets.refreshAll(AppGraph.appContext)
        }
    }

    fun setSavedMultiplier(s: SavedMeal, multiplier: Float) {
        viewModelScope.launch {
            runCatching { SavedMeals.setMultiplier(s.id, multiplier) }
            com.tamir.hamasa.data.cloud.CloudSync.pushSoon(AppGraph.appContext)
        }
    }

    fun deleteSaved(s: SavedMeal) {
        viewModelScope.launch {
            runCatching { SavedMeals.delete(s.id) }
            _message.value = "${s.nameHe} ירדה מהארוחות הקבועות. היא תסתדר."
            com.tamir.hamasa.data.cloud.CloudSync.pushSoon(AppGraph.appContext)
        }
    }

    // ------------------------------------------------------------ "מה לאכול עכשיו?"

    /** [targets] are the same goals the screen shows. Runs in the background; the card shows a small spinner. */
    fun askNext(targets: MacroTargets, kind: MealSuggester.MealKind = MealSuggester.defaultKind()) {
        if (_next.value == NextMealState.Loading) return
        _next.value = NextMealState.Loading
        viewModelScope.launch {
            refreshDay()
            val bw = bodyweight.value ?: profile.value?.startWeightKg ?: AppGraph.prefs.current().startWeightKg
            val todays = runCatching { AppGraph.db.meals().forDayOnce(today) }.getOrDefault(meals.value)
            val result = runCatching { MealSuggester.suggest(targets, todays, saved.value, bw, kind) }.getOrElse {
                MealSuggester.offline(MealSuggester.budget(targets, todays), saved.value, noKey = !aiReady())
            }
            _next.value = NextMealState.Ready(result)
        }
    }

    fun closeNext() { _next.value = NextMealState.Idle }

    /** "רשום את זה": opens the regular review dialog with the option's numbers (saved as a manual meal). */
    fun logSuggestion(s: MealSuggester.Suggestion) {
        val fromSaved = s.savedMealId?.let { id -> saved.value.firstOrNull { it.id == id } }
        val items = fromSaved?.scaledItems()?.takeIf { it.isNotEmpty() }
            ?: listOf(FoodItem(s.nameHe.take(40), s.grams, s.kcal, s.proteinG, s.carbsG, s.fatG, 1f))
        _phase.value = PhotoPhase.Review(items, 0, "אפשר לשנות כמויות לפני השמירה.", fromPhoto = false, descriptionHe = s.nameHe, titleHe = "ההצעה")
    }

    /** "הכנתי את זה" on a chef recipe: the regular review dialog with its numbers (saved as a manual meal). */
    fun logChefRecipe(r: com.tamir.hamasa.data.ai.ChefRecipe) {
        val items = listOf(FoodItem(r.nameHe.take(40), r.grams, r.kcal, r.proteinG, r.carbsG, r.fatG, 1f))
        _phase.value = PhotoPhase.Review(items, 0, "אפשר לשנות כמויות לפני השמירה.", fromPhoto = false, descriptionHe = r.nameHe, titleHe = "מהשף")
    }

    fun update(m: MealEntity, f: MealForm) {
        viewModelScope.launch {
            val newKcal = f.kcal.coerceAtLeast(0)
            // a hand-edited kcal: the item breakdown follows it (scaled), or is dropped when it cannot be scaled
            val itemsJson = if (newKcal == m.kcal || m.itemsJson.isBlank()) m.itemsJson else {
                val items = MealEstimator.decodeItems(m.itemsJson)
                if (items.isNotEmpty() && m.kcal > 0 && newKcal > 0) MealEstimator.encodeItems(SavedMeals.scaleItems(items, newKcal.toFloat() / m.kcal))
                else ""
            }
            AppGraph.db.meals().update(
                m.copy(description = f.description, kcal = newKcal, proteinG = f.proteinG, carbsG = f.carbsG, fatG = f.fatG, itemsJson = itemsJson)
            )
            com.tamir.hamasa.data.cloud.CloudSync.pushSoon(AppGraph.appContext)
            Widgets.refreshAll(AppGraph.appContext)
        }
    }

    /**
     * Saves the corrected items of a logged meal: the meal totals become the sum of the items.
     * Source, time and the original AI estimate stay as they were.
     */
    fun updateItems(m: MealEntity, items: List<FoodItem>, description: String) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            AppGraph.db.meals().update(
                m.copy(
                    description = description.trim().ifBlank { m.description }.take(120),
                    kcal = Nutrition.sumKcal(items).coerceAtLeast(0),
                    proteinG = Nutrition.sumProtein(items), carbsG = Nutrition.sumCarbs(items), fatG = Nutrition.sumFat(items),
                    itemsJson = MealEstimator.encodeItems(items)
                )
            )
            runCatching { com.tamir.hamasa.data.food.FoodBaseStore.learn(items) }
            _message.value = "נרשם. כל פריט קיבל את המספר שלו. הצלחת לא ערערה."
            com.tamir.hamasa.data.cloud.CloudSync.pushSoon(AppGraph.appContext)
            Widgets.refreshAll(AppGraph.appContext)
        }
    }

    fun delete(m: MealEntity) {
        viewModelScope.launch {
            AppGraph.db.meals().deleteById(m.id)
            com.tamir.hamasa.data.cloud.CloudSync.pushSoon(AppGraph.appContext)
            Widgets.refreshAll(AppGraph.appContext)
        }
    }

    /** 0 = automatic. */
    fun saveGoals(protein: Int, carbs: Int, fat: Int) {
        viewModelScope.launch {
            AppGraph.prefs.update { it.copy(proteinGoal = protein.coerceAtLeast(0), carbsGoal = carbs.coerceAtLeast(0), fatGoal = fat.coerceAtLeast(0)) }
            com.tamir.hamasa.data.cloud.CloudSync.pushSoon(AppGraph.appContext)
            Widgets.refreshAll(AppGraph.appContext)
        }
    }

    private companion object {
        const val NO_KEY = "הזיהוי האוטומטי כבוי כרגע. רושמים ביד, זה עובד מצוין."
        const val FAILED = "אין תשובה מהשרת. אולי אין חיבור. רושמים ביד."
        /** A text meal answered by the food base alone. */
        const val BASE_NOTE = "חושב מהמאגר שלך, בלי בינה מלאכותית. כדאי לבדוק את הכמויות לפני השמירה."

        fun baseKnownNote(known: Int, all: Int): String =
            if (known >= all) "כל הפריטים חושבו לפי המאגר שלך." else "$known מתוך $all פריטים חושבו לפי המאגר שלך."
        /** Longest wait for a photo / text estimate. */
        const val ESTIMATE_TIMEOUT_MS = 35_000L
        /** A second camera launch within this time of the first is ignored. */
        const val DOUBLE_LAUNCH_MS = 60_000L
        /** A pending camera file older than this is forgotten (its result never came back). */
        const val PENDING_MAX_MS = 30L * 60L * 1000L
        val FIXED_FULL ="אין מקום. ${SavedMeals.MAX} ארוחות קבועות זה הגבול. לחיצה ארוכה על אחת מוחקת אותה."
    }
}

@Composable
fun FoodScreen(vm: FoodViewModel = viewModel(), onOpenEnergy: () -> Unit = {}) {
    val meals by vm.meals.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val bodyweight by vm.bodyweight.collectAsStateWithLifecycle()
    val phase by vm.phase.collectAsStateWithLifecycle()
    val manual by vm.manual.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val merchant by vm.merchant.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<MealEntity?>(null) }
    var editingItems by remember { mutableStateOf<MealEntity?>(null) }
    var deleting by remember { mutableStateOf<MealEntity?>(null) }
    var describing by remember { mutableStateOf(false) }
    var goalsOpen by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(setOf<Long>()) }
    val saved by vm.saved.collectAsStateWithLifecycle()
    val next by vm.next.collectAsStateWithLifecycle()
    val offer by vm.offer.collectAsStateWithLifecycle()
    var savingFixed by remember { mutableStateOf<MealEntity?>(null) }
    var editingSavedId by remember { mutableStateOf<Long?>(null) }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> vm.onCameraResult(ok) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> vm.onPicked(uri) }

    // home-screen widget "צלם ארוחה": open the camera once per tap (consumed, so rotation / back never relaunch it)
    val snapMeal by Widgets.snapMeal.collectAsStateWithLifecycle()
    LaunchedEffect(snapMeal) {
        if (snapMeal && Widgets.consumeSnapMeal()) {
            vm.newCameraTarget()?.let { uri -> runCatching { camera.launch(uri) }.onFailure { vm.onCameraResult(false) } }
        }
    }

    // every time the food tab is shown (the tab keeps its view model): the automatic goal follows the latest weigh-in
    LaunchedEffect(Unit) { runCatching { com.tamir.hamasa.data.repo.EnergyData.syncAutoGoal() } }
    // the day follows the clock while the screen is shown (after midnight the list starts a new day)
    LaunchedEffect(Unit) {
        while (true) {
            vm.refreshDay()
            delay(60_000L)
        }
    }
    val manualPrefill by vm.manualPrefill.collectAsStateWithLifecycle()

    val p = profile
    val kcalGoal = (p?.kcalGoal ?: 2200).coerceAtLeast(1)
    val bw = bodyweight ?: p?.startWeightKg ?: 90f
    // macro goals from the energy model (domain/energy); manual values (> 0) win
    val heightCm = p?.heightCm ?: 0
    val bodyFat = p?.bodyFatPct ?: 0f
    // "הכיוון" (ירידה / שמירה / עלייה) shapes the automatic protein and fat, same as the energy screen
    val direction = if (p != null) com.tamir.hamasa.data.repo.EnergyData.directionOf(p, bw) else com.tamir.hamasa.domain.energy.GoalDirection.CUT
    val targets = Nutrition.targets(kcalGoal, bw, p?.proteinGoal ?: 0, p?.carbsGoal ?: 0, p?.fatGoal ?: 0, heightCm, bodyFat, direction)
    val autoTargets = Nutrition.targets(kcalGoal, bw, 0, 0, 0, heightCm, bodyFat, direction)
    val today by vm.day.collectAsStateWithLifecycle()

    LazyColumn(
        Modifier.fillMaxSize().background(HColors.Slate).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(Modifier.height(16.dp))
            val realToday = Days.today()
            val dayTitle = when (today) {
                realToday -> "אוכל של היום"
                realToday - 1 -> "אוכל של אתמול"
                else -> "אוכל של יום " + Days.hebrewDayName(today) + " " + Days.of(today).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM"))
            }
            Text(dayTitle, style = MaterialTheme.typography.headlineMedium, color = HColors.Gold)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { vm.shiftDay(-1) }) { Text("יום קודם", color = HColors.Gold, fontWeight = FontWeight.Bold) }
                TextButton(onClick = { vm.shiftDay(1) }, enabled = today < realToday) { Text("יום הבא", color = if (today < realToday) HColors.Gold else HColors.ParchmentDim, fontWeight = FontWeight.Bold) }
                if (today < realToday) {
                    TextButton(onClick = { vm.setDay(realToday) }) { Text("חזרה להיום", color = HColors.Parchment) }
                }
            }
            if (today < realToday) {
                Text("הארוחות שתוסיף עכשיו נרשמות ליום הזה.", color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
            }
        }
        item { DayTotalsCard(meals, targets, onClick = { goalsOpen = true }, onEnergy = onOpenEnergy, kcalAuto = p?.kcalAuto ?: false) }
        message?.let { line ->
            item {
                Card(
                    shape = FoodCardShape,
                    colors = CardDefaults.cardColors(containerColor = HColors.Ink),
                    border = BorderStroke(2.dp, HColors.Gold)
                ) {
                    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(line, color = HColors.Parchment, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.dismissMessage() }) { Text("סגור", color = HColors.Gold) }
                    }
                }
            }
        }
        item {
            MealCaptureButtons(
                onCamera = { vm.newCameraTarget()?.let { uri -> runCatching { camera.launch(uri) }.onFailure { vm.onCameraResult(false) } } },
                onGallery = {
                    if (vm.requireAi()) runCatching { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                },
                onDescribe = { describing = true },
                onManual = { vm.openManual("") }
            )
        }
        if (saved.isNotEmpty()) {
            item {
                SavedMealsStrip(saved, onLog = { s -> vm.logSaved(s) }, onEdit = { s -> editingSavedId = s.id })
            }
        }
        item {
            // "השף": what to make with what is left of today (replaced "מה לאכול עכשיו?")
            ChefCard(
                targets = targets,
                meals = meals,
                bodyweightKg = bw,
                onCook = { r -> vm.logChefRecipe(r) }
            )
        }
        item { CalorieHuntCard(meals, kcalGoal, huntDone = p?.lastCalorieHuntDay == today, photoBonusDone = p?.lastPhotoDayBonus == today, merchant = merchant) }
        items(meals, key = { it.id }) { m ->
            MealCard(
                m = m,
                expanded = m.id in expanded,
                onToggle = { expanded = if (m.id in expanded) expanded - m.id else expanded + m.id },
                onEdit = { editing = m },
                onLongPress = { savingFixed = m },
                onEditItems = { editingItems = m }
            )
        }
        item { FoodBaseCard() }
        item {
            if (meals.isNotEmpty()) Text("נגיעה בארוחה: עריכה, תיקון פריטים או מחיקה. לחיצה ארוכה: שמירה כארוחה קבועה.", color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(24.dp))
        }
    }

    when (val ph = phase) {
        is PhotoPhase.Loading -> MealLoadingDialog(ph.line, onCancel = { vm.cancelEstimate() })
        is PhotoPhase.Review -> MealReviewDialog(ph, onSave = { list, desc, keep -> vm.saveReview(list, desc, keep) }, onCancel = { vm.cancelReview() })
        PhotoPhase.Idle -> Unit
    }

    if (describing) {
        DescribeMealDialog(onSend = { t -> describing = false; vm.describe(t) }, onDismiss = { describing = false })
    }

    manual?.let { reason ->
        MealFormDialog(
            title = "הוספת ארוחה",
            reason = reason.ifBlank { null },
            initial = manualPrefill?.let { d -> MealForm(d, 0, 0f, 0f, 0f) },
            onSave = { f -> vm.add(f); vm.closeManual() },
            onDismiss = { vm.closeManual() }
        )
    }

    editing?.let { m ->
        androidx.compose.runtime.key(m.id) {
            MealFormDialog(
                title = "עריכת ארוחה",
                reason = null,
                initial = MealForm(m.description, m.kcal, m.proteinG, m.carbsG, m.fatG),
                onSave = { f -> vm.update(m, f); editing = null },
                onDismiss = { editing = null },
                onDelete = { deleting = m; editing = null },
                onSaveFixed = { vm.saveAsFixed(m); editing = null },
                onEditItems = { editingItems = m; editing = null }
            )
        }
    }

    editingItems?.let { m ->
        androidx.compose.runtime.key(m.id) {
            MealItemsEditor(
                meal = m,
                onSave = { list, desc -> vm.updateItems(m, list, desc); editingItems = null },
                onDismiss = { editingItems = null }
            )
        }
    }

    savingFixed?.let { m ->
        SaveFixedDialog(
            description = m.description, kcal = m.kcal, auto = false,
            onSave = { vm.saveAsFixed(m); savingFixed = null },
            onDismiss = { savingFixed = null }
        )
    }

    // automatic offer: the same description logged 3+ times (shown once per description)
    if (savingFixed == null) {
        offer?.let { m ->
            SaveFixedDialog(
                description = m.description, kcal = m.kcal, auto = true,
                onSave = { vm.saveAsFixed(m) },
                onDismiss = { vm.dismissOffer() }
            )
        }
    }

    // a deleted meal simply shows no dialog (the id is replaced by the next long-press)
    editingSavedId?.let { id ->
        saved.firstOrNull { it.id == id }?.let { s ->
            SavedMealEditDialog(
                s = s,
                onMultiplier = { mult -> vm.setSavedMultiplier(s, mult) },
                onDelete = { vm.deleteSaved(s); editingSavedId = null },
                onDismiss = { editingSavedId = null }
            )
        }
    }

    deleting?.let { m ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("למחוק את הארוחה?") },
            text = { Text("${m.description}, ${m.kcal} קלוריות. נמחק, ונשכח.") },
            confirmButton = { TextButton(onClick = { vm.delete(m); deleting = null }) { Text(com.tamir.hamasa.domain.game.Addr.t("מחק", "מחקי"), color = HColors.Danger) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("ביטול") } }
        )
    }

    if (goalsOpen) {
        MacroGoalsDialog(
            auto = autoTargets,
            protein = p?.proteinGoal ?: 0, carbs = p?.carbsGoal ?: 0, fat = p?.fatGoal ?: 0,
            onSave = { a, b, c -> vm.saveGoals(a, b, c); goalsOpen = false },
            onDismiss = { goalsOpen = false }
        )
    }
}

// ---------------------------------------------------------------- top card: kcal ring + macro bars

private val ProteinColor = HColors.Ember
private val CarbsColor = HColors.Sky
private val FatColor = HColors.Moss

@Composable
private fun DayTotalsCard(meals: List<MealEntity>, t: MacroTargets, onClick: () -> Unit, onEnergy: () -> Unit = {}, kcalAuto: Boolean = false) {
    val total = meals.sumOf { it.kcal }
    val protein = meals.sumOf { it.proteinG.toDouble() }.toFloat()
    val carbs = meals.sumOf { it.carbsG.toDouble() }.toFloat()
    val fat = meals.sumOf { it.fatG.toDouble() }.toFloat()
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
        border = BorderStroke(4.dp, HColors.GoldDeep)
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            KcalRing(total, t.kcal)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MacroBar("חלבון", protein, t.proteinG, ProteinColor)
                MacroBar("פחמימות", carbs, t.carbsG, CarbsColor)
                MacroBar("שומן", fat, t.fatG, FatColor)
            }
        }
        val diff = t.kcal - total
        Text(
            when {
                meals.isEmpty() -> "הצלחת ריקה. היום עוד צעיר."
                diff in -100..100 -> "בטווח. בדיוק איפה שצריך."
                diff < -100 -> "עברנו ב-${-diff}. טוב. לא קרה כלום. מחר ממשיכים."
                else -> "נשארו $diff קלוריות."
            },
            color = HColors.Parchment, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp)
        )
        Text(
            "נגיעה: יעדי חלבון, פחמימות ושומן." + if (kcalAuto) " יעד הקלוריות אוטומטי." else "",
            color = HColors.ParchmentDim, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp)
        )
        TextButton(onClick = onEnergy, modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 6.dp)) {
            Text("מחשבון אנרגיה: כמה שרפתי, כמה לאכול", color = HColors.Gold, fontWeight = FontWeight.Bold)
        }
        TextButton(
            onClick = { com.tamir.hamasa.ui.progress.ProgressNav.open(com.tamir.hamasa.ui.Routes.WEEKLY) },
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 6.dp)
        ) {
            Text("הקלוריות של השבוע: חרגתי או הצלחתי", color = HColors.Gold, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun KcalRing(total: Int, goal: Int) {
    val frac = (total.toFloat() / goal.coerceAtLeast(1)).coerceIn(0f, 1f)
    val over = total > goal + Nutrition.HUNT_RANGE
    Box(Modifier.size(116.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 12.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(HColors.Ink, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            drawArc(
                if (over) HColors.Ember else HColors.Gold, -90f, 360f * frac, false, Offset(inset, inset), arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$total", style = MaterialTheme.typography.headlineMedium, color = HColors.Parchment, fontWeight = FontWeight.Black)
            Text("מתוך $goal", style = MaterialTheme.typography.labelSmall, color = HColors.ParchmentDim)
        }
    }
}

@Composable
private fun MacroBar(label: String, have: Float, goal: Int, color: Color) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(label, color = HColors.Parchment, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text("${fmtG(have)} / $goal גרם", color = HColors.ParchmentDim, style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.height(3.dp))
        LinearProgressIndicator(
            progress = { (have / goal.coerceAtLeast(1)).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = color,
            trackColor = HColors.Ink,
            strokeCap = StrokeCap.Round
        )
    }
}

// ---------------------------------------------------------------- daily mini quest

@Composable
private fun CalorieHuntCard(meals: List<MealEntity>, goal: Int, huntDone: Boolean, photoBonusDone: Boolean, merchant: Boolean) {
    val total = meals.sumOf { it.kcal }
    val low = goal - Nutrition.HUNT_RANGE
    val high = goal + Nutrition.HUNT_RANGE
    val photos = meals.count { it.source == "photo" }
    Card(
        shape = FoodCardShape,
        colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
        border = BorderStroke(2.dp, HColors.Ink)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("ציד קלוריות", style = MaterialTheme.typography.titleMedium, color = HColors.Gold)
            Text("יעד היום: לנחות בטווח ±${Nutrition.HUNT_RANGE} מהיעד ($low עד $high).", color = HColors.Parchment)
            Text(
                when {
                    huntDone -> "הושג. הציד נרשם."
                    meals.size < Nutrition.HUNT_MIN_MEALS -> "צריך לפחות שתי ארוחות רשומות."
                    total in low..high -> "כרגע בטווח. נשאר רק לסגור את היום."
                    total < low -> "עוד ${low - total} עד הטווח."
                    else -> "עברנו את הטווח ב-${total - high}. מחר עוד ציד."
                },
                color = if (huntDone) HColors.Moss else HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "פרס בסגירת היום: ${Nutrition.HUNT_XP} ניסיון ו-${Nutrition.HUNT_GOLD} זהב.",
                color = HColors.ParchmentDim, style = MaterialTheme.typography.labelSmall
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (photoBonusDone) "כל הארוחות צולמו היום. הבונוס נרשם."
                else com.tamir.hamasa.domain.game.Addr.t("צלם", "צלמי") + " את כל הארוחות, לפחות ${Nutrition.PHOTO_DAY_MIN_MEALS}: עוד ${Nutrition.PHOTO_DAY_XP} ניסיון. צולמו: $photos מתוך ${meals.size}.",
                color = HColors.ParchmentDim, style = MaterialTheme.typography.labelSmall
            )
            if (merchant) {
                Text(
                    "הסוחר הערמומי הוא הבוס של השבוע. כל ארוחה מצולמת היא מכה.",
                    color = HColors.Ember, style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

// ---------------------------------------------------------------- meal list

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MealCard(m: MealEntity, expanded: Boolean, onToggle: () -> Unit, onEdit: () -> Unit, onLongPress: () -> Unit = {}, onEditItems: () -> Unit = {}) {
    val items = remember(m.itemsJson) { MealEstimator.decodeItems(m.itemsJson) }
    val photo = m.source == "photo"
    Card(
        Modifier.fillMaxWidth().combinedClickable(onClick = onEdit, onLongClick = onLongPress),
        shape = FoodCardShape,
        colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
        border = BorderStroke(2.dp, if (photo) HColors.GoldDeep else HColors.Ink)
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (photo) Icons.Filled.PhotoCamera else Icons.Filled.Edit,
                    contentDescription = if (photo) "צולם" else "נרשם ביד",
                    tint = if (photo) HColors.Gold else HColors.ParchmentDim,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(m.description, style = MaterialTheme.typography.bodyLarge, color = HColors.Parchment)
                    if (m.proteinG > 0f || m.carbsG > 0f || m.fatG > 0f) {
                        Text(
                            "חלבון ${fmtG(m.proteinG)} · פחמימות ${fmtG(m.carbsG)} · שומן ${fmtG(m.fatG)}",
                            style = MaterialTheme.typography.labelSmall, color = HColors.ParchmentDim
                        )
                    }
                }
                Text("${m.kcal}", style = MaterialTheme.typography.titleMedium, color = HColors.Gold)
                if (items.isNotEmpty()) {
                    IconButton(onClick = onToggle) {
                        Icon(
                            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = if (expanded) "הסתר פריטים" else "הצג פריטים",
                            tint = HColors.Gold
                        )
                    }
                }
            }
            if (expanded && items.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                for (x in items) {
                    Row(Modifier.fillMaxWidth().padding(start = 30.dp, top = 2.dp)) {
                        Text(if (x.grams > 0) "${x.nameHe} · ${x.grams} גרם" else x.nameHe, color = HColors.Parchment, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text("${x.kcal}", color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (m.aiKcal > 0 && abs(m.aiKcal - m.kcal) > 0) {
                    Text(
                        "ההערכה המקורית: ${m.aiKcal}",
                        color = HColors.ParchmentDim, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(start = 30.dp, top = 4.dp)
                    )
                }
                TextButton(onClick = onEditItems, modifier = Modifier.padding(start = 22.dp)) {
                    Text("עריכת פריטים", color = HColors.Gold, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
