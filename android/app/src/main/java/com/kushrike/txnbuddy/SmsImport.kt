package com.kushrike.txnbuddy

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** Reads the SMS inbox on-device. Bodies are parsed in memory and dropped; only extracted fields are stored. */
object SmsImport {
    private val HISTORY_MS = TimeUnit.DAYS.toMillis(180)
    private const val AI_LIMIT = 200
    private const val RESCUE_LIMIT = 30

    fun granted(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    /** New (not yet stored) transactions from messages received after [since]. */
    suspend fun scan(ctx: Context, since: Long): List<Txn> = withContext(Dispatchers.IO) {
        val known = Store.knownKeys
        val today = LocalDate.now()
        val found = LinkedHashMap<String, Pair<Txn, String>>()
        val unreadable = mutableListOf<Triple<String, String?, Long>>()
        ctx.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            "${Telephony.Sms.DATE} > ?", arrayOf(since.toString()), "${Telephony.Sms.DATE} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                val sender = c.getString(0)
                val body = c.getString(1) ?: continue
                val date = c.getLong(2)
                val p = SmsParser.parse(body, sender, today)
                if (p == null) {
                    if (SmsParser.rescuable(body, sender)) unreadable += Triple(body, sender, date)
                    continue
                }
                if (p.smsKey in known || p.smsKey in found) continue
                val src = SmsParser.sourceId(p.account, sender)
                found[p.smsKey] = Txn(
                    amount = p.amountMinor, income = p.income, categoryId = p.categoryId, note = p.merchant ?: "",
                    date = date, account = src, source = "sms", smsKey = p.smsKey, accountId = accountFor(src, sender, body),
                ) to body
            }
        }

        // Learning from you (DESIGN §5): your past picks and history first, on every phone.
        val bayes = mapOf(false to Learn.bayes(false), true to Learn.bayes(true))
        fun learnt(t: Txn) = t.copy(categoryId = Learn.pick(t.note, t.income, t.categoryId, bayes.getValue(t.income)) ?: t.categoryId)
        val learned = found.values.map { (t, body) -> learnt(t) to body }

        if (Ai.status == Ai.CHECKING) Ai.refresh()
        if (!Ai.ready) return@withContext learned.map { it.first }

        // On-device AI: fill in categories the keyword table missed, then try to read leftovers.
        val list = learned.map { (t, body) ->
            if (found.size <= AI_LIMIT && !t.income && t.categoryId == "other")
                Ai.categorize(t.note.ifBlank { null }, body, false)?.let { t.copy(categoryId = it) } ?: t
            else t
        }.toMutableList()
        for ((body, sender, date) in unreadable.take(RESCUE_LIMIT)) {
            val key = SmsParser.key(body)
            if (key in known || list.any { it.smsKey == key }) continue
            val r = Ai.rescue(body) ?: continue
            val text = SmsParser.normalize(body)
            val src = SmsParser.sourceId(SmsParser.accountOf(text), sender)
            list += learnt(Txn(
                amount = r.amountMinor, income = r.income, note = r.merchant ?: "", date = date,
                categoryId = SmsParser.categoryFor(text.lowercase(), r.merchant, r.income),
                account = src, source = "sms", smsKey = key, accountId = accountFor(src, sender, body),
            ))
        }
        list.sortedByDescending { it.date }
    }

    private fun accountFor(src: String?, sender: String?, body: String) =
        SmsParser.accountFor(src, "${sender.orEmpty()} $body".lowercase(), Store.accounts)?.id

    /** On app open: first run → review sheet with 180 days of history; afterwards auto-add (or queue) new ones. */
    suspend fun autoScan(ctx: Context) {
        if (Nav.scanning || !Store.onboarded || !granted(ctx)) return
        Nav.scanning = true
        try {
            val first = Store.lastScan == 0L
            val started = System.currentTimeMillis()
            val found = scan(ctx, if (first) started - HISTORY_MS else Store.lastScan)
            Store.lastScan = started
            when {
                found.isEmpty() -> Unit
                first -> Nav.review = found
                Store.autoSms -> {
                    val added = Store.addAll(found)
                    if (added.isNotEmpty()) Nav.snack = Snack("Added ${added.size} from SMS", "Undo") { Store.delete(added.map { it.id }) }
                }
                else -> Nav.pending = (Nav.pending + found).distinctBy { it.smsKey }
            }
        } finally {
            Nav.scanning = false
        }
    }

    /** "Scan inbox now": full history, always reviewed. */
    suspend fun manualScan(ctx: Context) {
        if (Nav.scanning) return
        Nav.scanning = true
        try {
            val started = System.currentTimeMillis()
            val found = scan(ctx, started - HISTORY_MS)
            Store.lastScan = started
            Nav.pending = emptyList()
            if (found.isEmpty()) Nav.snack = Snack("No new bank transactions found") else Nav.review = found
        } finally {
            Nav.scanning = false
        }
    }
}
