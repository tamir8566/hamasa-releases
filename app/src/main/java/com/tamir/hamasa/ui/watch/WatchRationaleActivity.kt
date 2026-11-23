package com.tamir.hamasa.ui.watch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tamir.hamasa.ui.theme.HColors
import com.tamir.hamasa.ui.theme.HamasaTheme

/**
 * Health Connect privacy rationale (required): opened from the Health Connect permission screen
 * (androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE, Android 13 and below) and from the
 * "ViewPermissionUsageActivity" alias (Android 14+). See AndroidManifest.xml and docs/watch.md.
 */
class WatchRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HamasaTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    RationaleContent(onClose = { finish() })
                }
            }
        }
    }
}

@Composable
private fun RationaleContent(onClose: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(HColors.Slate)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("מה המסע הגדול קורא מהשעון", color = HColors.Gold, fontSize = 24.sp, fontWeight = FontWeight.Black)
        Line("צעדים: לחישוב הפעילות היומית במחשבון האנרגיה, ולמונה הצעדים במסך הבית.")
        Line("דופק: דופק ממוצע ושיא של אימון, לחישוב הקלוריות של הליכה וריצה.")
        Line("אימונים: אימון שהשעון רשם ולא נפתח באפליקציה נוסף כאימון שהסתיים, רק מרגע החיבור והלאה.")
        Line("מרחק וקלוריות של אימון: מוצגים ליד האימון, אם יש.")
        Line("שינה: כמה ישנת בלילה, למחוון השינה של הג'ים ברו ושל הגרלת הבוקר. הזזת המחוון ביד גוברת.")
        Line("האפליקציה רק קוראת. היא לא כותבת דבר להלת' קונקט.")
        Line("שום נתון מהשעון לא יוצא מהטלפון, חוץ מהגיבוי הקיים בענן של האפליקציה (אימונים, צעדים יומיים, שעות שינה לכל לילה ודופק ממוצע לאימון), שנועד רק לשחזור.")
        Line("אפשר לבטל את ההרשאות בכל רגע בהגדרות הלת' קונקט.")
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onClose,
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(containerColor = HColors.Gold, contentColor = HColors.Ink),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text("סגור", fontWeight = FontWeight.Black, fontSize = 18.sp) }
    }
}

@Composable
private fun Line(text: String) {
    Text(text, color = HColors.Parchment, fontSize = 16.sp)
}
