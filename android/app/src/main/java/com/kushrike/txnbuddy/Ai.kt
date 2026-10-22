package com.kushrike.txnbuddy

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.common.GenAiException.ErrorCode
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.math.BigDecimal

/**
 * Gemini Nano through ML Kit GenAI (AICore). Runs entirely on-device; this app has no INTERNET permission.
 * Optional: every call degrades to null when the model is missing, so the regex parser stays the primary path.
 */
object Ai {
    const val CHECKING = -1
    var status by mutableIntStateOf(CHECKING)
        private set
    /** `GenAiException.errorCode` behind the last failed check or download, so the setup guide shows the right steps. */
    var error by mutableStateOf<Int?>(null)
        private set
    var downloaded by mutableLongStateOf(0L); private set
    var total by mutableLongStateOf(0L); private set

    private val model: GenerativeModel? by lazy { runCatching { Generation.getClient() }.getOrNull() }

    val ready get() = status == FeatureStatus.AVAILABLE && Store.aiEnabled

    val label: String
        get() = when (status) {
            CHECKING -> "Checking…"
            FeatureStatus.AVAILABLE -> "Available"
            FeatureStatus.DOWNLOADING -> "Downloading"
            FeatureStatus.DOWNLOADABLE -> "Ready to download"
            else -> when (error) {
                ErrorCode.AICORE_INCOMPATIBLE, ErrorCode.NEEDS_SYSTEM_UPDATE -> "Needs an update"
                ErrorCode.NOT_ENOUGH_DISK_SPACE -> "Not enough storage"
                else -> "Not supported on this phone"
            }
        }

    suspend fun refresh() {
        var code: Int? = null
        status = withTimeoutOrNull(5_000) {
            runCatching { model?.checkStatus() }.onFailure { code = it.genAiCode() }.getOrNull()
        } ?: FeatureStatus.UNAVAILABLE
        error = code
    }

    private fun Throwable.genAiCode() = generateSequence(this) { it.cause }.filterIsInstance<GenAiException>().firstOrNull()?.errorCode

    /** AICore fetches the model itself (system service, via Private Compute Services); we only ask for it. */
    suspend fun download() {
        val m = model ?: return
        var failed: Int? = null
        downloaded = 0
        total = 0
        runCatching {
            status = FeatureStatus.DOWNLOADING
            m.download().collect {
                when (it) {
                    is DownloadStatus.DownloadStarted -> total = it.bytesToDownload
                    is DownloadStatus.DownloadProgress -> downloaded = it.totalBytesDownloaded
                    is DownloadStatus.DownloadCompleted -> status = FeatureStatus.AVAILABLE
                    is DownloadStatus.DownloadFailed -> failed = it.e.errorCode
                }
            }
        }.onFailure { failed = it.genAiCode() }
        refresh()
        if (failed != null) error = failed
    }

    private suspend fun ask(prompt: String, maxTokens: Int): String? {
        val m = model ?: return null
        if (!ready) return null
        return withTimeoutOrNull(6_000) {
            runCatching {
                m.generateContent(generateContentRequest(TextPart(prompt)) {
                    temperature = 0f
                    topK = 1
                    maxOutputTokens = maxTokens
                }).candidates.firstOrNull()?.text
            }.getOrNull()
        }
    }

    /** Picks a category id for a transaction the keyword table couldn't place. */
    suspend fun categorize(merchant: String?, body: String, income: Boolean): String? {
        val cats = Store.categories.filter { it.income == income }
        val answer = ask(
            "Pick the single best spending category for this bank transaction. " +
                "Merchant: ${merchant ?: "unknown"}. Message: ${body.take(200)}. " +
                "Answer with exactly one of: ${cats.joinToString(", ") { it.name }}.",
            maxTokens = 12,
        )?.trim()?.trim('.', '"', '*', ' ')?.lowercase() ?: return null
        return (cats.firstOrNull { it.name.lowercase() == answer } ?: cats.firstOrNull { answer.contains(it.name.lowercase()) })
            ?.id?.takeIf { it != "other" && it != "otherIncome" }
    }

    data class Rescue(val amountMinor: Long, val income: Boolean, val merchant: String?)

    /** Extracts a transaction from a message the regex parser couldn't read. */
    suspend fun rescue(body: String): Rescue? {
        val out = ask(
            "Extract the money transaction from this bank SMS. Reply with only JSON like " +
                "{\"isTransaction\": true, \"amount\": 123.45, \"isCredit\": false, \"merchant\": \"Name\"}. " +
                "isTransaction is false for OTPs, offers, reminders and failed payments. SMS: ${body.take(400)}",
            maxTokens = 80,
        ) ?: return null
        return runCatching {
            val o = JSONObject(out.substring(out.indexOf('{'), out.lastIndexOf('}') + 1))
            if (!o.optBoolean("isTransaction")) return null
            val minor = BigDecimal(o.get("amount").toString().replace(",", "")).movePointRight(2).toLong()
            if (minor <= 0) null
            else Rescue(minor, o.optBoolean("isCredit"), o.optString("merchant").trim().take(32).ifBlank { null })
        }.getOrNull()
    }
}
