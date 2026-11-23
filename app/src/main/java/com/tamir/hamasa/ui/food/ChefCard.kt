package com.tamir.hamasa.ui.food

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import com.tamir.hamasa.data.ai.RecipeImport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tamir.hamasa.data.ai.Chef
import com.tamir.hamasa.data.ai.ChefRunner
import com.tamir.hamasa.data.ai.ChefLine
import com.tamir.hamasa.data.ai.ChefRecipe
import com.tamir.hamasa.data.ai.ChefStore
import com.tamir.hamasa.data.ai.MealSuggester
import com.tamir.hamasa.data.db.MealEntity
import com.tamir.hamasa.domain.Days
import com.tamir.hamasa.domain.food.MacroTargets
import com.tamir.hamasa.domain.game.Addr
import com.tamir.hamasa.ui.theme.HColors

/**
 * "השף" (data/ai/Chef + ChefStore): a short chat on the food screen that helps decide what to make with what is
 * left of today. Recipes come with "הכנתי את זה" (logs it through the review dialog: [onCook]), "אהבתי" (recipe
 * book) and "לא בשבילי" (with an optional reason; never suggested again). "מה השף יודע עליי" shows and edits the
 * taste facts he taught it; "ספר המתכונים" the recipes he liked or cooked. All of it belongs to this user only.
 * No dialogs: everything opens inline.
 */
@Composable
fun ChefCard(
    targets: MacroTargets,
    meals: List<MealEntity>,
    bodyweightKg: Float,
    onCook: (ChefRecipe) -> Unit
) {
    val st by ChefStore.state.collectAsState()
    val context = LocalContext.current
    var kind by remember { mutableStateOf(MealSuggester.defaultKind()) }
    var input by remember { mutableStateOf("") }
    // the question runs in the app (ChefRunner): leaving the screen or the app does not stop it
    val pending by ChefRunner.pending.collectAsState()
    val busy = pending != null
    LifecycleResumeEffect(Unit) {
        ChefRunner.cardVisible = true
        ChefRunner.clearNotification(context)
        onPauseOrDispose { ChefRunner.cardVisible = false }
    }
    var factsOpen by remember { mutableStateOf(false) }
    var bookOpen by remember { mutableStateOf(false) }
    val budget = MealSuggester.budget(targets, meals)
    val chat = if (st.chatDay == Days.today()) st.chat else emptyList()

    fun send(text: String) {
        val msg = text.trim()
        if (msg.isEmpty() || busy) return
        input = ""
        // "לארוחת ערב" in the message wins over the chip (and moves the chip)
        val k = Chef.kindFromText(msg) ?: kind
        kind = k
        ChefRunner.send(context, msg, targets, meals, bodyweightKg, k)
    }

    Card(
        shape = FoodCardShape,
        colors = CardDefaults.cardColors(containerColor = HColors.SlateLight),
        border = BorderStroke(2.dp, HColors.GoldDeep)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("השף", style = MaterialTheme.typography.titleLarge, color = HColors.Gold, fontWeight = FontWeight.Black)
            Text(
                "נשארו להיום ${budget.kcal} קלוריות ו-${budget.proteinG} גרם חלבון.",
                color = HColors.Parchment, style = MaterialTheme.typography.bodyMedium
            )
            Text("איזו ארוחה?", style = MaterialTheme.typography.labelLarge, color = HColors.Gold)
            MealKindChips(selected = kind, onSelect = { kind = it })

            if (chat.isEmpty() && pending == null) {
                Text(
                    Addr.t(
                        "ספר לי מה בא לך, או בחר אחת השאלות למטה. אני יודע כמה נשאר לך היום ומה אתה אוהב.",
                        "ספרי לי מה בא לך, או בחרי אחת השאלות למטה. אני יודע כמה נשאר לך היום ומה את אוהבת."
                    ),
                    color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium
                )
            }
            for (line in chat.takeLast(8)) ChatLine(line, onCook = onCook, onAdapt = { r -> send("תתאים לי את ${r.nameHe} למה שנשאר היום") }, onRethink = { r -> send("תחשוב מחדש ותציע משהו אחר במקום ${r.nameHe}") })
            val p = pending
            if (p != null) {
                ChatLine(ChefLine(false, p), onCook = onCook, onAdapt = {})
                // the seconds count up, so a slow answer is visibly slow and not stuck
                var seconds by remember(p) { mutableStateOf(0) }
                LaunchedEffect(p) {
                    while (true) {
                        kotlinx.coroutines.delay(1000)
                        seconds++
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = HColors.Gold, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (seconds < 3) "השף חושב..." else "השף חושב... $seconds שניות. אפשר לצאת מהמסך, התשובה תחכה כאן.",
                        color = HColors.Parchment
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (q in listOf("מה להכין עכשיו?", "משהו מהיר", "משהו מתוק")) {
                    Text(
                        q,
                        color = HColors.Parchment,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .background(HColors.Slate, RoundedCornerShape(50))
                            .clickable(enabled = !busy) { send(q) }
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    )
                }
            }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it.take(300) },
                label = { Text("הודעה לשף") },
                placeholder = { Text("למשל: יש לי עוף ואורז, משהו מהיר") },
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { send(input) },
                    enabled = !busy && input.isNotBlank(),
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = FoodCardShape,
                    colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink)
                ) { Text("שליחה", fontWeight = FontWeight.Black) }
                if (chat.isNotEmpty()) {
                    TextButton(onClick = { ChefStore.clearChat() }, enabled = !busy) { Text("שיחה חדשה", color = HColors.ParchmentDim) }
                }
            }

            // what the chef knows about him
            TextButton(onClick = { factsOpen = !factsOpen }) {
                Text("מה השף יודע עליי (${st.facts.size})", color = HColors.Gold, fontWeight = FontWeight.Bold)
            }
            if (factsOpen) FactsSection(st.facts)

            // the recipe book
            TextButton(onClick = { bookOpen = !bookOpen }) {
                Text("ספר המתכונים (${st.book.size})", color = HColors.Gold, fontWeight = FontWeight.Bold)
            }
            if (bookOpen) {
                AddRecipeSection()
                if (st.book.isEmpty()) {
                    Text(
                        "עוד ריק. מתכון שאהבת או הכנת נכנס לכאן.",
                        color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium
                    )
                }
                for (r in st.book) BookRow(r, onCook = onCook, onAdapt = { send("תתאים לי את ${r.nameHe} למה שנשאר היום") })
            }
        }
    }
}

@Composable
private fun ChatLine(line: ChefLine, onCook: (ChefRecipe) -> Unit, onAdapt: (ChefRecipe) -> Unit, onRethink: (ChefRecipe) -> Unit = {}) {
    if (!line.fromChef) {
        Text(
            line.textHe,
            color = HColors.Ink,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .fillMaxWidth()
                .background(HColors.Parchment, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(line.textHe, color = HColors.Parchment, style = MaterialTheme.typography.bodyLarge)
        for (r in line.recipes) RecipeCard(r, inBookView = false, onCook = onCook, onAdapt = onAdapt, onRethink = onRethink)
    }
}

/** One recipe with its actions. [inBookView]: shown inside "ספר המתכונים" (adapt / remove instead of like / nope). */
@Composable
private fun RecipeCard(r: ChefRecipe, inBookView: Boolean, onCook: (ChefRecipe) -> Unit, onAdapt: (ChefRecipe) -> Unit, onRethink: (ChefRecipe) -> Unit = {}) {
    val st by ChefStore.state.collectAsState()
    val busyNow by ChefRunner.pending.collectAsState()
    val booked = st.book.firstOrNull { it.nameHe.trim() == r.nameHe.trim() }
    val nope = st.nope.any { it == r.nameHe.trim() || it.startsWith(r.nameHe.trim() + ":") }
    var asking by remember(r.nameHe) { mutableStateOf(false) }
    var reason by remember(r.nameHe) { mutableStateOf("") }
    Card(
        shape = FoodCardShape,
        colors = CardDefaults.cardColors(containerColor = HColors.Slate),
        border = BorderStroke(2.dp, HColors.Ink)
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.nameHe, style = MaterialTheme.typography.titleMedium, color = HColors.Parchment, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (r.kcal > 0) Text("${r.kcal}", style = MaterialTheme.typography.titleMedium, color = HColors.Gold)
            }
            val time = if (r.minutes > 0) " · ${r.minutes} דקות" else ""
            if (r.kcal > 0) Text(
                (if (r.servings > 1) "למנה: " else "") + "חלבון ${fmtG(r.proteinG)} · פחמימות ${fmtG(r.carbsG)} · שומן ${fmtG(r.fatG)}$time",
                color = HColors.ParchmentDim, style = MaterialTheme.typography.labelSmall
            )
            if (r.servings > 1) PortionCard(r)
            if (r.ingredientsHe.isNotEmpty()) {
                Text(if (r.servings > 1) "מצרכים (לכל המתכון, ${r.servings} מנות)" else "מצרכים", color = HColors.Gold, style = MaterialTheme.typography.labelLarge)
                for (i in r.ingredientsHe) Text("• $i", color = HColors.Parchment, style = MaterialTheme.typography.bodyMedium)
            }
            if (r.stepsHe.isNotEmpty()) {
                Text("הכנה", color = HColors.Gold, style = MaterialTheme.typography.labelLarge)
                r.stepsHe.forEachIndexed { n, s -> Text("${n + 1}. $s", color = HColors.Parchment, style = MaterialTheme.typography.bodyMedium) }
            }
            if (r.whyHe.isNotBlank()) Text(r.whyHe, color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)

            // logs ONE portion (kcal and macros are per portion), whatever the recipe size
            Button(
                onClick = { ChefStore.cooked(r); onCook(r) },
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = FoodCardShape,
                colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink)
            ) { Text(if (r.servings > 1) "הכנתי ואכלתי מנה אחת" else "הכנתי את זה", fontWeight = FontWeight.Bold) }
            if (inBookView) {
                Row {
                    TextButton(onClick = { onAdapt(r) }) { Text("להתאים להיום", color = HColors.Gold) }
                    TextButton(onClick = { ChefStore.removeFromBook(r.nameHe) }) { Text("הסרה מהספר", color = HColors.ParchmentDim) }
                }
            } else if (nope) {
                Text("רשמתי. לא אציע את זה שוב.", color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (booked?.liked == true) {
                        Text("בספר המתכונים", color = HColors.Moss, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 12.dp))
                    } else {
                        TextButton(onClick = { ChefStore.like(r) }) { Text("אהבתי", color = HColors.Gold, fontWeight = FontWeight.Bold) }
                    }
                    TextButton(onClick = { asking = !asking }) { Text("לא בשבילי", color = HColors.ParchmentDim) }
                    TextButton(onClick = { onRethink(r) }, enabled = busyNow == null) { Text("חשוב שוב", color = HColors.Gold, fontWeight = FontWeight.Bold) }
                }
                if (asking) {
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it.take(60) },
                        label = { Text("למה? (לא חובה)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(onClick = { ChefStore.nope(r, reason); asking = false }, modifier = Modifier.fillMaxWidth()) {
                        Text("שמירה", color = HColors.Parchment)
                    }
                }
            }
        }
    }
}

/** The small "one portion" card of a recipe that makes several: how much of it is one portion and what it costs. */
@Composable
private fun PortionCard(r: ChefRecipe) {
    Column(
        Modifier.fillMaxWidth().background(HColors.Ink, FoodCardShape).padding(10.dp).testTag("portion_card"),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text("המתכון מספיק ל-${r.servings} מנות", color = HColors.Gold, style = MaterialTheme.typography.labelLarge)
        Text(
            "מנה אחת היא חלק אחד מ-${r.servings}" + (if (r.grams > 0) ", בערך ${r.grams} גרם" else "") + ".",
            color = HColors.Parchment, style = MaterialTheme.typography.bodyMedium
        )
        if (r.kcal > 0) Text(
            "במנה: ${r.kcal} קלוריות, ${fmtG(r.proteinG)} חלבון.",
            color = HColors.Parchment, style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun BookRow(r: ChefRecipe, onCook: (ChefRecipe) -> Unit, onAdapt: (ChefRecipe) -> Unit) {
    var open by remember(r.nameHe) { mutableStateOf(false) }
    val tag = when {
        r.cooked > 0 && r.liked -> " · אהבת, הכנת ${r.cooked}"
        r.cooked > 0 -> " · הכנת ${r.cooked}"
        else -> " · אהבת"
    }
    Text(
        "${r.nameHe}" + (if (r.kcal > 0) " · ${r.kcal} קלוריות" + (if (r.servings > 1) " למנה" else "") else "") + tag,
        color = HColors.Parchment,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 6.dp)
    )
    if (open) RecipeCard(r, inBookView = true, onCook = onCook, onAdapt = onAdapt)
}

@Composable
private fun FactsSection(facts: List<String>) {
    var add by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (facts.isEmpty()) {
            Text(
                Addr.t(
                    "עוד לא יודע כלום. אפשר לכתוב כאן, או פשוט לספר לי בשיחה (\"אני לא אוכל דגים\").",
                    "עוד לא יודע כלום. אפשר לכתוב כאן, או פשוט לספר לי בשיחה (\"אני לא אוכלת דגים\")."
                ),
                color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium
            )
        }
        for (f in facts) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("• $f", color = HColors.Parchment, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { ChefStore.removeFact(f) }) { Text("הסרה", color = HColors.ParchmentDim) }
            }
        }
        OutlinedTextField(
            value = add,
            onValueChange = { add = it.take(ChefStore.MAX_FACT_LEN) },
            label = { Text("משהו שהשף צריך לדעת") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedButton(
            onClick = { if (add.isNotBlank()) { ChefStore.addFacts(listOf(add)); add = "" } },
            enabled = add.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("הוספה", color = HColors.Parchment) }
    }
}

/** "הוספת מתכון משלי": a recipe he already loves goes straight into the book, and the chef learns his taste from it. */
@Composable
private fun AddRecipeSection() {
    var open by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var ingredients by remember { mutableStateOf("") }
    var steps by remember { mutableStateOf("") }
    var kcal by remember { mutableStateOf("") }
    var protein by remember { mutableStateOf("") }
    var servings by remember { mutableStateOf("1") }
    var source by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    // what the import read (minutes are kept from it when he saves)
    var imported by remember { mutableStateOf<ChefRecipe?>(null) }
    // the WHOLE recipe's numbers (all portions): the per-portion fields are these divided by the servings, so a
    // change of the servings count re-divides them (owner feedback 2026-11-08: 5 portions still showed the whole)
    var wholeKcal by remember { mutableStateOf(0f) }
    var wholeProtein by remember { mutableStateOf(0f) }
    var wholeCarbs by remember { mutableStateOf(0f) }
    var wholeFat by remember { mutableStateOf(0f) }
    var wholeGrams by remember { mutableStateOf(0f) }
    val scope = rememberCoroutineScope()

    fun servingsNow(): Int = (servings.toIntOrNull() ?: 1).coerceIn(1, 50)
    fun showPerPortion() {
        val n = servingsNow()
        kcal = if (wholeKcal > 0f) (wholeKcal / n).roundToInt().toString() else ""
        protein = if (wholeProtein > 0f) (wholeProtein / n).roundToInt().toString() else ""
    }

    fun fill(r: RecipeImport.Result) {
        note = RecipeImport.messageHe(r)
        if (r is RecipeImport.Result.Ok) {
            val x = r.recipe
            imported = x
            name = x.nameHe
            ingredients = x.ingredientsHe.joinToString("\n")
            steps = x.stepsHe.joinToString("\n")
            val n = x.servings.coerceAtLeast(1)
            servings = n.toString()
            wholeKcal = x.kcal * n.toFloat()
            wholeProtein = x.proteinG * n
            wholeCarbs = x.carbsG * n
            wholeFat = x.fatG * n
            wholeGrams = x.grams * n.toFloat()
            showPerPortion()
        }
    }

    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4)) { uris ->
        if (uris.isNotEmpty() && !busy) {
            busy = true
            note = null
            scope.launch {
                val images = withContext(Dispatchers.IO) {
                    val app = com.tamir.hamasa.data.AppGraph.appContext
                    uris.mapNotNull { uri ->
                        val f = java.io.File(app.cacheDir, "recipe_" + System.nanoTime() + ".jpg")
                        try {
                            app.contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { out -> input.copyTo(out) } }
                            com.tamir.hamasa.data.ai.MealEstimator.downscaleJpeg(f)
                        } catch (t: Throwable) {
                            null
                        } finally {
                            runCatching { f.delete() }
                        }
                    }
                }
                fill(if (images.isEmpty()) RecipeImport.Result.Failed else RecipeImport.fromImages(images))
                busy = false
            }
        }
    }

    TextButton(onClick = { open = !open }) {
        Text(if (open) "סגירת ההוספה" else "הוספת מתכון משלי לספר", color = HColors.Gold, fontWeight = FontWeight.Bold)
    }
    if (!open) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("מילוי אוטומטי: צילום מסך של מתכון, קישור ליוטיוב, או טקסט שהעתקת (גם מאינסטגרם).", color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(
            onClick = { runCatching { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(44.dp),
            shape = FoodCardShape
        ) { Text("מתמונות (עד 4)", color = HColors.Parchment, fontWeight = FontWeight.Bold) }
        OutlinedTextField(
            value = source, onValueChange = { source = it.take(6000) },
            label = { Text("קישור ליוטיוב או טקסט של מתכון") }, minLines = 2, maxLines = 6, modifier = Modifier.fillMaxWidth()
        )
        OutlinedButton(
            onClick = {
                if (!busy) {
                    busy = true
                    note = null
                    val text = source
                    scope.launch {
                        fill(RecipeImport.fromText(text))
                        busy = false
                    }
                }
            },
            enabled = !busy && source.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(44.dp),
            shape = FoodCardShape
        ) { Text("קרא את המתכון", color = HColors.Parchment, fontWeight = FontWeight.Bold) }
        if (busy) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = HColors.Gold, strokeWidth = 2.dp)
                Text("קורא את המתכון. סרטון לוקח עד דקה.", color = HColors.ParchmentDim, style = MaterialTheme.typography.bodyMedium)
            }
        }
        note?.let { Text(it, color = HColors.Gold, style = MaterialTheme.typography.bodyMedium) }
        OutlinedTextField(
            value = name, onValueChange = { name = it.take(60) },
            label = { Text("שם המתכון") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = ingredients, onValueChange = { ingredients = it.take(1200) },
            label = { Text("מצרכים (כל מצרך בשורה, לא חובה)") }, minLines = 3, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = steps, onValueChange = { steps = it.take(2500) },
            label = { Text("הכנה (כל שלב בשורה, לא חובה)") }, minLines = 3, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = servings,
            onValueChange = { v ->
                servings = v.filter { c -> c.isDigit() }.take(2)
                // the whole recipe stays what it is; one portion is now a different share of it
                if (servings.isNotEmpty()) showPerPortion()
            },
            label = { Text("לכמה מנות המתכון") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        val n = servingsNow()
        Text(
            if (wholeKcal > 0f) "כל המתכון: ${wholeKcal.roundToInt()} קלוריות, ${wholeProtein.roundToInt()} חלבון. למטה: למנה אחת מתוך $n."
            else "המצרכים נשמרים לכל המתכון. הקלוריות והחלבון למטה הם למנה אחת.",
            color = HColors.ParchmentDim, style = MaterialTheme.typography.bodySmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = kcal,
                onValueChange = { v ->
                    kcal = v.filter { c -> c.isDigit() }.take(4)
                    // a hand-typed per-portion value redefines the whole
                    wholeKcal = (kcal.toIntOrNull() ?: 0) * servingsNow().toFloat()
                },
                label = { Text("קלוריות למנה") }, singleLine = true, modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = protein,
                onValueChange = { v ->
                    protein = v.filter { c -> c.isDigit() }.take(3)
                    wholeProtein = (protein.toIntOrNull() ?: 0) * servingsNow().toFloat()
                },
                label = { Text("חלבון למנה בגרם") }, singleLine = true, modifier = Modifier.weight(1f)
            )
        }
        Button(
            onClick = {
                val base = imported
                val n = servingsNow()
                val r = ChefRecipe(
                    nameHe = name.trim(),
                    kcal = kcal.toIntOrNull() ?: 0,
                    proteinG = protein.toFloatOrNull() ?: 0f,
                    // carbs, fat and grams: the whole recipe's share for one of the n portions
                    carbsG = if (wholeCarbs > 0f) ((wholeCarbs / n) * 10f).roundToInt() / 10f else 0f,
                    fatG = if (wholeFat > 0f) ((wholeFat / n) * 10f).roundToInt() / 10f else 0f,
                    grams = if (wholeGrams > 0f) (wholeGrams / n).roundToInt() else 0,
                    minutes = base?.minutes ?: 0,
                    ingredientsHe = ingredients.lines().map { it.trim() }.filter { it.isNotEmpty() }.take(30),
                    stepsHe = steps.lines().map { it.trim() }.filter { it.isNotEmpty() }.take(30),
                    servings = n
                )
                ChefStore.like(r)
                name = ""; ingredients = ""; steps = ""; kcal = ""; protein = ""; source = ""; servings = "1"
                wholeKcal = 0f; wholeProtein = 0f; wholeCarbs = 0f; wholeFat = 0f; wholeGrams = 0f
                imported = null; note = null; open = false
            },
            enabled = name.isNotBlank() && !busy,
            modifier = Modifier.fillMaxWidth().height(44.dp),
            shape = FoodCardShape,
            colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink)
        ) { Text("הוספה לספר", fontWeight = FontWeight.Bold) }
    }
}
