package com.tamir.hamasa.live

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.tamir.hamasa.data.ai.AiClient
import com.tamir.hamasa.data.ai.ClaudeClient
import com.tamir.hamasa.data.ai.ClaudeConfig
import com.tamir.hamasa.data.ai.GeminiClient
import com.tamir.hamasa.data.ai.GeminiConfig
import com.tamir.hamasa.data.ai.MealEstimator
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Live check of the real AI code path through AiClient (only when GEMINI_LIVE=1): once with Gemini first and once
 * with Claude first, for every provider whose key the build has.
 * Writes a report to build/gemini_live.txt; the "Gemini check" workflow posts it as an issue.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class GeminiLiveTest {

    @Test
    fun live() {
        assumeTrue(System.getenv("GEMINI_LIVE") == "1")
        val report = StringBuilder()
        report.append("gemini enabled=${GeminiConfig.enabled} model=${GeminiConfig.MODEL} lite=${GeminiConfig.MODEL_LITE}\n")
        report.append("claude enabled=${ClaudeConfig.enabled} model=${ClaudeConfig.MODEL} lite=${ClaudeConfig.MODEL_LITE}\n")

        try {
            if (GeminiConfig.enabled) {
                report.append("\n== provider forced: gemini (falls back to Claude; see 'answered by')\n")
                runChecks("gemini", report)
            }
            if (ClaudeConfig.enabled) {
                report.append("\n== provider forced: claude (falls back to Gemini; see 'answered by')\n")
                runChecks("claude", report)
            }
            if (!GeminiConfig.enabled && !ClaudeConfig.enabled) report.append("no key at all\n")
        } finally {
            AiClient.forcedProvider = null
        }
        val out = File("build/gemini_live.txt")
        out.parentFile?.mkdirs()
        out.writeText(report.toString())
        println(report)
    }

    /** The three checks (meal text, meal photo, short lite line) through [AiClient] with [provider] first. */
    private fun runChecks(provider: String, report: StringBuilder) {
        AiClient.forcedProvider = provider
        runBlocking {
            AiClient.lastProvider = null
            val t0 = System.currentTimeMillis()
            val text = MealEstimator.fromText("שתי פיתות עם חומוס וסלט ישראלי")
            report.append("fromText (${System.currentTimeMillis() - t0} ms): $text\n")
            report.append("  answered by=${AiClient.lastProvider} lastError=${AiClient.lastError}\n")

            val bmp = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(Color.rgb(200, 170, 120))
            val p = Paint().apply { color = Color.WHITE; isAntiAlias = true }
            c.drawCircle(400f, 300f, 250f, p)
            p.color = Color.rgb(230, 190, 90)
            c.drawCircle(400f, 300f, 120f, p)
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, bos)
            AiClient.lastProvider = null
            val t1 = System.currentTimeMillis()
            val img = MealEstimator.fromImage(bos.toByteArray())
            report.append("fromImage (${System.currentTimeMillis() - t1} ms, ${bos.size()} bytes): $img\n")
            report.append("  answered by=${AiClient.lastProvider} lastError=${AiClient.lastError}\n")

            AiClient.lastProvider = null
            val t2 = System.currentTimeMillis()
            val line = AiClient.generate(
                listOf(AiClient.Part.Text("כתוב משפט קצר אחד בעברית על אימון בבוקר.")),
                tier = AiClient.Tier.LITE, temperature = 0.9, maxOutputTokens = 120
            )
            report.append("lite line (${System.currentTimeMillis() - t2} ms): $line\n")
            report.append("  answered by=${AiClient.lastProvider} lastError=${AiClient.lastError}\n")
            report.append("  raw: claude=${ClaudeClient.lastError} gemini=${GeminiClient.lastError}\n")
        }
    }
}
