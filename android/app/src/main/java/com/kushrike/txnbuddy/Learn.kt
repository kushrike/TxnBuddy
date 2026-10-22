package com.kushrike.txnbuddy

import kotlin.math.exp
import kotlin.math.ln

/**
 * "Learning from you" (DESIGN §5): the category the user last picked for each merchant, then naive Bayes over
 * their own history. Runs on every phone; nothing to download.
 */
object Learn {
    private val STOP = setOf(
        "the", "and", "for", "with", "from", "upi", "pvt", "ltd", "limited", "private", "india",
        "payment", "payments", "txn", "ref", "to", "of", "at", "by", "in", "on",
    )
    private val NON_LETTERS = Regex("[^\\p{L}]+")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

    /** `Starbucks #1234` → `starbucks`; null when no letters are left. */
    fun mkey(s: String): String? = s.lowercase().replace(NON_LETTERS, " ").trim().ifEmpty { null }

    /** `starbucks` covers `Starbucks` and `Starbucks Coffee`: the same key, or the start of it word by word. */
    fun covers(key: String, note: String): Boolean = mkey(note)?.let { it == key || it.startsWith("$key ") } == true

    /** The category learned for this note: its own key, else the longest learned key it starts with. */
    fun recall(note: String, learned: Map<String, String>): String? {
        val k = mkey(note) ?: return null
        return learned[k] ?: learned.entries.filter { k.startsWith(it.key + " ") }.maxByOrNull { it.key.length }?.value
    }

    fun tokens(s: String): List<String> =
        s.lowercase().split(NON_WORD).filter { it.length >= 2 && !it.all(Char::isDigit) && it !in STOP }

    /** Multinomial naive Bayes over (note, categoryId) pairs of one kind, add-0.1 smoothing. */
    class Bayes(docs: List<Pair<String, String>>) {
        private val docsPer = HashMap<String, Int>()
        private val counts = HashMap<String, HashMap<String, Int>>() // category → token → count
        private val totals = HashMap<String, Int>()
        private val vocab = HashSet<String>()
        private var n = 0

        init {
            for ((note, cat) in docs) {
                val ts = tokens(note).ifEmpty { continue }
                n++
                docsPer.merge(cat, 1, Int::plus)
                val m = counts.getOrPut(cat) { HashMap() }
                ts.forEach { m.merge(it, 1, Int::plus); vocab += it }
                totals.merge(cat, ts.size, Int::plus)
            }
        }

        /** Best category if its posterior is ≥ 0.6; null when no input token was ever seen. */
        fun predict(s: String): String? {
            val ts = tokens(s).filter { it in vocab }.ifEmpty { return null }
            val v = vocab.size
            val scores = docsPer.map { (c, d) ->
                val m = counts.getValue(c)
                val tot = totals.getValue(c)
                c to ln(d.toDouble() / n) + ts.sumOf { ln(((m[it] ?: 0) + 0.1) / (tot + 0.1 * v)) }
            }
            val best = scores.maxBy { it.second }
            return best.first.takeIf { 1 / scores.sumOf { exp(it.second - best.second) } >= 0.6 }
        }
    }

    /** Trained on the user's transactions of one kind that have a note and a real category. */
    fun bayes(income: Boolean) = Bayes(
        Store.txns.filter { it.income == income && it.note.isNotBlank() && it.categoryId != "other" && it.categoryId != "otherIncome" }
            .map { it.note to it.categoryId },
    )

    /** Memory → keyword category → classifier; null = nothing learned (the LLM or `other` decides). */
    fun pick(note: String, income: Boolean, keyword: String, bayes: Bayes): String? =
        recall(note, Store.learned)?.takeIf { id -> Store.categories.any { it.id == id && it.income == income } }
            ?: keyword.takeIf { it != "other" && it != "otherIncome" }
            ?: bayes.predict(note)
}
