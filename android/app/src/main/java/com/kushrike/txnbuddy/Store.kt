package com.kushrike.txnbuddy

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Currency
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

data class Category(val id: String, val name: String, val emoji: String, val color: String, val income: Boolean) {
    val tint: Color get() = Color(("FF" + color.removePrefix("#")).toLong(16))
    val builtIn: Boolean get() = DEFAULT_CATEGORIES.any { it.id == id }
}

data class Txn(
    val id: String = UUID.randomUUID().toString(),
    val amount: Long, // minor units, always > 0
    val income: Boolean,
    val categoryId: String,
    val note: String = "",
    val date: Long, // epoch millis
    val account: String? = null,
    val source: String = "manual",
    val smsKey: String? = null,
    val accountId: String? = null,
)

/** Where money moves in or out (DESIGN §2.1). `ids` are last-4 digits or lowercased text IDs that tie SMS to it. */
data class Account(val id: String = UUID.randomUUID().toString(), val name: String, val kind: String = "bank", val ids: List<String> = emptyList()) {
    val emoji: String get() = KINDS.firstOrNull { it.first == kind }?.second ?: "🏦"
    val kindName: String get() = kind.replaceFirstChar(Char::uppercaseChar)
}

val KINDS = listOf("bank" to "🏦", "card" to "💳", "wallet" to "👛", "cash" to "💵")

/** An SMS identifier no account claims yet: how many transactions carry it and how much they spent. */
data class Source(val id: String, val count: Int, val spent: Long)

/** `XXXX 4521` → `4521`; `Paytm  Wallet` → `paytm wallet`; too short → null. Only the last 4 digits are ever kept. */
fun normId(s: String): String? {
    val t = s.trim()
    if (t.count(Char::isDigit) >= 4 && t.none { it.isLetter() && it != 'x' && it != 'X' }) return t.filter(Char::isDigit).takeLast(4)
    return t.lowercase().replace(Regex("\\s+"), " ").takeIf { c -> c.count { it != ' ' } >= 3 }
}

/** `4521` → `••4521`; text IDs as they are. */
fun idLabel(id: String) = if (id.all(Char::isDigit)) "••$id" else id

val DEFAULT_CATEGORIES = listOf(
    Category("food", "Food", "🍔", "#FF8A3D", false),
    Category("groceries", "Groceries", "🛒", "#22C55E", false),
    Category("transport", "Transport", "🚕", "#3B82F6", false),
    Category("shopping", "Shopping", "🛍️", "#EC4899", false),
    Category("bills", "Bills", "💡", "#F59E0B", false),
    Category("entertainment", "Fun", "🎬", "#8B5CF6", false),
    Category("health", "Health", "💊", "#EF4444", false),
    Category("home", "Home", "🏠", "#14B8A6", false),
    Category("travel", "Travel", "✈️", "#06B6D4", false),
    Category("education", "Education", "📚", "#6366F1", false),
    Category("transfer", "Transfers", "💸", "#64748B", false),
    Category("other", "Other", "📦", "#9CA3AF", false),
    Category("salary", "Salary", "💼", "#10B981", true),
    Category("refund", "Refunds", "🔁", "#84CC16", true),
    Category("otherIncome", "Other income", "💰", "#0EA5E9", true),
)

val PALETTE = listOf(
    "#FF8A3D", "#22C55E", "#3B82F6", "#EC4899", "#F59E0B", "#8B5CF6", "#EF4444", "#14B8A6",
    "#06B6D4", "#6366F1", "#64748B", "#9CA3AF", "#10B981", "#84CC16", "#0EA5E9",
)
val CURRENCIES = listOf("INR", "USD", "EUR", "GBP", "AED", "SGD", "AUD", "CAD", "JPY")

/** Single source of truth. Compose observes the state fields directly. Data lives in one private JSON file. */
object Store {
    var txns by mutableStateOf(emptyList<Txn>()); private set // newest first
    var categories by mutableStateOf(DEFAULT_CATEGORIES); private set
    var accounts by mutableStateOf(emptyList<Account>()); private set
    var learned by mutableStateOf(emptyMap<String, String>()); private set // mkey → category the user picked (DESIGN §5)
    var currency by mutableStateOf("INR"); private set
    var budget by mutableLongStateOf(0L); private set

    // Device-only preferences (never part of a backup).
    var lockEnabled by Pref("lock", false)
    var hideAmounts by Pref("hide", false)
    var aiEnabled by Pref("ai", true)
    var autoSms by Pref("autoSms", true)
    var onboarded by Pref("onboarded", false)
    var lastScan by Pref("lastScan", 0L)
    var lastExpenseCat by Pref("lastExpenseCat", "food")
    var lastIncomeCat by Pref("lastIncomeCat", "salary")
    var lastAccountId by Pref("lastAccountId", "")
    var dismissedSources by Pref("dismissedSources", emptySet<String>())

    private lateinit var file: File
    internal lateinit var prefs: SharedPreferences
    private val io = Executors.newSingleThreadExecutor()

    fun init(ctx: Context) {
        if (::file.isInitialized) return
        prefs = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        file = File(ctx.filesDir, "txnbuddy.json")
        currency = runCatching { Currency.getInstance(Locale.getDefault()).currencyCode }
            .getOrNull()?.takeIf { it in CURRENCIES } ?: "INR"
        // Unreadable file: move it aside instead of letting the next save overwrite the only copy.
        if (file.exists()) runCatching { load(JSONObject(file.readText())) }
            .onFailure { file.renameTo(File(file.path + ".unreadable-${System.currentTimeMillis()}")) }
    }

    fun category(id: String): Category =
        categories.firstOrNull { it.id == id } ?: categories.first { it.id == "other" }

    fun account(id: String?): Account? = id?.let { i -> accounts.firstOrNull { it.id == i } }

    /** Identifiers seen in SMS that no account claims (DESIGN §2.1), most frequent first. */
    fun unlinked(): List<Source> {
        val claimed = accounts.flatMapTo(HashSet()) { it.ids }
        return txns.filter { it.accountId == null && it.account != null && it.account.lowercase() !in claimed }.groupBy { it.account!! }
            .map { (id, l) -> Source(id, l.size, l.sumOf { if (it.income) 0 else it.amount }) }.sortedByDescending { it.count }
    }

    val knownKeys: Set<String> get() = txns.mapNotNullTo(HashSet()) { it.smsKey }

    fun upsert(t: Txn) {
        txns = (txns.filter { it.id != t.id } + t).sortedByDescending { it.date }
        save()
    }

    /** Adds new transactions, skipping SMS already stored. Returns what was actually added. */
    fun addAll(list: List<Txn>): List<Txn> {
        val keys = knownKeys.toMutableSet()
        val fresh = list.filter { it.smsKey == null || keys.add(it.smsKey) }
        if (fresh.isNotEmpty()) {
            txns = (txns + fresh).sortedByDescending { it.date }
            save()
        }
        return fresh
    }

    fun recategorize(ids: Set<String>, categoryId: String) {
        txns = txns.map { if (it.id in ids) it.copy(categoryId = categoryId) else it }
        save()
    }

    fun delete(ids: Collection<String>) {
        val set = ids.toSet()
        txns = txns.filter { it.id !in set }
        save()
    }

    fun changeBudget(v: Long) { budget = v; save() }
    fun changeCurrency(c: String) { currency = c; save() }

    fun upsertCategory(c: Category) {
        categories = if (categories.any { it.id == c.id }) categories.map { if (it.id == c.id) c else it } else categories + c
        save()
    }

    /** Remembers the category the user picked for this merchant / note. */
    fun learn(note: String, categoryId: String) {
        val k = Learn.mkey(note) ?: return
        if (learned[k] != categoryId) { learned = learned + (k to categoryId); save() }
    }

    fun forgetLearned() { learned = emptyMap(); save() }

    fun deleteCategory(c: Category) {
        if (c.builtIn) return
        val fallback = if (c.income) "otherIncome" else "other"
        categories = categories.filter { it.id != c.id }
        txns = txns.map { if (it.categoryId == c.id) it.copy(categoryId = fallback) else it }
        learned = learned.filterValues { it != c.id }
        save()
    }

    /** Upserts [a], takes its ids away from other accounts, and claims unlinked transactions carrying them. */
    fun saveAccount(a: Account) {
        val ids = a.ids.toSet()
        accounts = (if (accounts.any { it.id == a.id }) accounts else accounts + a).map { if (it.id == a.id) a else it.copy(ids = it.ids - ids) }
        txns = txns.map { if (it.accountId == null && it.account?.lowercase() in ids) it.copy(accountId = a.id) else it }
        save()
    }

    fun link(identifier: String, accountId: String) {
        val a = account(accountId) ?: return
        saveAccount(a.copy(ids = (a.ids + identifier.lowercase()).distinct()))
    }

    /** Its transactions stay, just unlinked (their identifier shows up under "Found in SMS" again). */
    fun deleteAccount(a: Account) {
        accounts = accounts.filter { it.id != a.id }
        txns = txns.map { if (it.accountId == a.id) it.copy(accountId = null) else it }
        save()
    }

    fun eraseAll() {
        txns = emptyList(); categories = DEFAULT_CATEGORIES; accounts = emptyList(); learned = emptyMap(); budget = 0
        lastScan = System.currentTimeMillis()
        save()
    }

    // ---- persistence / backup (DESIGN §2 format, identical on iOS) ----

    fun toJson(): String = JSONObject().apply {
        put("version", 1)
        put("settings", JSONObject().put("currency", currency).put("monthlyBudget", budget))
        put("categories", JSONArray().apply {
            categories.forEach {
                put(JSONObject().put("id", it.id).put("name", it.name).put("emoji", it.emoji).put("color", it.color).put("income", it.income))
            }
        })
        put("transactions", JSONArray().apply {
            txns.forEach {
                put(JSONObject().put("id", it.id).put("amount", it.amount).put("income", it.income)
                    .put("categoryId", it.categoryId).put("note", it.note)
                    .put("date", Instant.ofEpochMilli(it.date).truncatedTo(ChronoUnit.SECONDS).toString())
                    .put("account", it.account ?: JSONObject.NULL).put("source", it.source)
                    .put("smsKey", it.smsKey ?: JSONObject.NULL).put("accountId", it.accountId ?: JSONObject.NULL))
            }
        })
        put("learned", JSONObject(learned))
        put("accounts", JSONArray().apply {
            accounts.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("kind", it.kind).put("ids", JSONArray(it.ids))) }
        })
    }.toString()

    /** Replaces everything with a backup. Throws on anything that isn't a TxnBuddy backup (nothing is changed then). */
    fun restore(json: String) = load(JSONObject(json)).also { save() }

    private fun load(o: JSONObject) {
        require(o.optInt("version") == 1) { "Not a TxnBuddy backup" }
        val cats = o.getJSONArray("categories").let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map {
                Category(it.getString("id"), it.getString("name"), it.getString("emoji"), it.getString("color"), it.getBoolean("income"))
            }
        }
        val list = o.getJSONArray("transactions").let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map {
                Txn(it.getString("id"), it.getLong("amount"), it.getBoolean("income"), it.getString("categoryId"),
                    it.optString("note"), Instant.parse(it.getString("date")).toEpochMilli(),
                    it.optStringOrNull("account"), it.optString("source", "manual"), it.optStringOrNull("smsKey"), it.optStringOrNull("accountId"))
            }
        }
        // Older backups have no accounts.
        val accs = o.optJSONArray("accounts")?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map { j ->
                val ids = j.optJSONArray("ids")
                Account(j.getString("id"), j.getString("name"), j.optString("kind", "bank"), (0 until (ids?.length() ?: 0)).map { ids!!.getString(it) })
            }
        } ?: emptyList()
        val s = o.optJSONObject("settings")
        // Built-ins always exist, even if an older/foreign backup dropped one.
        categories = cats + DEFAULT_CATEGORIES.filter { d -> cats.none { it.id == d.id } }
        accounts = accs
        learned = o.optJSONObject("learned")?.let { j -> j.keys().asSequence().associateWith { j.getString(it) } }
            ?.filterValues { id -> categories.any { it.id == id } } ?: emptyMap()
        txns = list.filter { it.amount > 0 }.map { t -> if (accs.any { it.id == t.accountId }) t else t.copy(accountId = null) }.sortedByDescending { it.date }
        currency = s?.optString("currency")?.takeIf { it.length == 3 } ?: currency
        budget = s?.optLong("monthlyBudget") ?: 0
    }

    private fun JSONObject.optStringOrNull(k: String) = if (isNull(k)) null else optString(k).ifEmpty { null }

    private fun save() {
        val json = toJson()
        io.execute {
            // Atomic replace: a crash mid-write never corrupts the only copy.
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json)
            tmp.renameTo(file)
        }
    }

    fun csv(): String = buildString {
        val f = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        appendLine("date,type,category,amount,currency,note,account,source")
        txns.forEach { t ->
            val cells = listOf(
                LocalDateTime.ofInstant(Instant.ofEpochMilli(t.date), ZoneId.systemDefault()).format(f),
                if (t.income) "income" else "expense", category(t.categoryId).name,
                BigDecimal.valueOf(t.amount, 2).toPlainString(), currency, t.note, account(t.accountId)?.name ?: t.account ?: "", t.source,
            )
            appendLine(cells.joinToString(",") { c -> if (c.any { it in ",\"\n" }) "\"" + c.replace("\"", "\"\"") + "\"" else c })
        }
    }
}

/** A SharedPreferences-backed property that Compose can observe. */
private class Pref<T>(private val key: String, private val def: T) : ReadWriteProperty<Any?, T> {
    private var state: MutableState<T>? = null

    @Suppress("UNCHECKED_CAST")
    private fun state(): MutableState<T> = state ?: mutableStateOf(
        when (def) {
            is Boolean -> Store.prefs.getBoolean(key, def)
            is Long -> Store.prefs.getLong(key, def)
            is String -> Store.prefs.getString(key, def)
            is Set<*> -> Store.prefs.getStringSet(key, null)?.toSet() ?: def
            else -> def
        } as T,
    ).also { state = it }

    override fun getValue(thisRef: Any?, property: KProperty<*>): T = state().value

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        state().value = value
        Store.prefs.edit().apply {
            when (value) {
                is Boolean -> putBoolean(key, value)
                is Long -> putLong(key, value)
                is String -> putString(key, value)
                is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }.apply()
    }
}

/** UI navigation + transient UI state (not persisted). */
object Nav {
    var tab by mutableIntStateOf(0)
    var editor by mutableStateOf<Editor?>(null)
    var review by mutableStateOf<List<Txn>?>(null)
    var pending by mutableStateOf(emptyList<Txn>())
    var screen by mutableStateOf<String?>(null)
    var budgetDialog by mutableStateOf(false)
    var locked by mutableStateOf(false)
    var smsGranted by mutableStateOf(false)
    var scanning by mutableStateOf(false)
    var snack by mutableStateOf<Snack?>(null)
}

data class Editor(val txn: Txn? = null, val income: Boolean = txn?.income ?: false)
data class Snack(val text: String, val action: String? = null, val onAction: () -> Unit = {})

/** Ties an SMS identifier to an account (and every unlinked transaction carrying it), with a toast. */
fun linkWithToast(identifier: String, a: Account) {
    Store.link(identifier, a.id)
    Nav.snack = Snack("Linked ${idLabel(identifier)} to ${a.name}")
}

/** "Swiggy → Groceries from now on", offering to move the other transactions from the same merchant too. */
fun learnedToast(note: String, categoryId: String, income: Boolean) {
    val k = Learn.mkey(note) ?: return
    val others = Store.txns.filter { it.income == income && it.categoryId != categoryId && Learn.covers(k, it.note) }.map { it.id }.toSet()
    val text = "${note.take(24)} → ${Store.category(categoryId).name} from now on"
    Nav.snack = if (others.isEmpty()) Snack(text) else Snack(text, "Apply to ${others.size} more") { Store.recategorize(others, categoryId) }
}

fun deleteWithUndo(t: Txn) {
    Store.delete(listOf(t.id))
    Nav.snack = Snack("Transaction deleted", "Undo") { Store.upsert(t) }
}
