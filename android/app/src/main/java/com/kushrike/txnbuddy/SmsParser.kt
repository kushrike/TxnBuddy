package com.kushrike.txnbuddy

import java.math.BigDecimal
import java.time.LocalDate

/** Result of parsing one bank SMS. Raw text is never kept, only these fields. */
data class Parsed(
    val amountMinor: Long,
    val income: Boolean,
    val account: String?,
    val merchant: String?,
    val categoryId: String,
    val date: LocalDate?,
    val smsKey: String,
)

/** Deterministic bank-SMS parser. Spec: DESIGN.md §5, vectors: shared/sms-samples.tsv. */
object SmsParser {
    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private val WS = Regex("\\s+")
    private val REJECT = rx("""\b(otp|one[- ]?time password|verification code|will be (?:debited|credited|deducted|charged)|is due|are due|due (?:on|by|date)|requested|request money|collect request|pre-?approved|failed|declined|unsuccessful|insufficient|reminder)\b""")
    private val TIER1 = rx("""\b(debited|debit of|spent|paid|sent|withdrawn|purchased?|charged|deducted|transferred|credited|received|deposited|refund(?:ed)?|cashback|reversed)\b""")
    private val TIER1_INCOME = setOf("credited", "received", "deposited", "refund", "refunded", "cashback", "reversed")
    private val TIER2 = rx("""\b(txn|transaction|dr|cr)\b""")

    private const val NUM = """([0-9]+(?:,[0-9]+)*(?:\.[0-9]{1,2})?)"""
    private val AMOUNT = listOf(
        rx("""(?:\brs\.?|\binr|₹|\busd|\$|\beur|€|\bgbp|£|\baed|\bsgd)\s*$NUM"""),
        rx("""\b$NUM\s*(?:rs|inr|usd|eur|gbp|aed)\b"""),
        rx("""\b(?:debited|credited|spent|paid|sent|received|withdrawn|deposited|transferred)\s+(?:by|with|for|of)?\s*(?:rs\.?|inr|₹)?\s*$NUM\b"""),
    )
    private val BALANCE_WORDS = listOf("bal", "avl", "available", "limit", "lmt", "outstanding", "due")

    private val ACCOUNT = rx("""\b(?:account|acct|a/c|card|ac)\s*(?:no\.?|number|num|ending(?:\s+(?:with|in))?)?\s*[:\-#]?\s*[x*•]*(\d{3,})""")

    private const val CAND = """([a-z][a-z0-9&' \-/#*_]{0,39}?)"""
    private const val STOP = """(?=\s+(?:on|ref\w*|via|upi|using|for|avl|bal|is|was|txn|dated|at|from|to|and|thru|by|with|in|of|has|not|card|a/c|rs|inr)\b|\s*[.,;:()!?]|\s*$)"""
    private val VPA = rx("""\b([a-z0-9][a-z0-9.\-_]+)@([a-z][a-z0-9]+)\b""")
    private val AGGREGATORS = listOf("paytmqr", "bharatpe", "phonepe", "gpay", "razorpay", "rzp", "cashfree", "upi")
    private val R2 = rx("""[;.]\s*$CAND\s+credited\b""")
    private val R3_EXPENSE = rx("""\b(?:at|to|towards|with)\s+$CAND$STOP""")
    private val R3_INCOME = rx("""\bfrom\s+$CAND$STOP""")
    private val R4 = rx("""\bon\s+$CAND$STOP""")
    private val DENY = "your you a/c ac acct account card my rs rs. inr usd bank vpa upi date self beneficiary mobile number jan feb mar apr may jun jul aug sep oct nov dec"
        .split(" ").toSet()

    private val KEYWORDS: List<Pair<String, List<Regex>>> = listOf(
        "groceries" to "instamart, blinkit, zepto, bigbasket, grofers, dmart, jiomart, grocery, supermarket, reliance fresh, natures basket, walmart, costco, whole foods, kroger, safeway, trader joe",
        "food" to "uber eats, swiggy, zomato, restaurant, cafe, coffee, chai, starbucks, domino, pizza, mcdonald, kfc, burger, subway, eatsure, bakery, food, dining, haldiram, barbeque, doordash",
        "transport" to "uber, ola, rapido, metro, fuel, petrol, diesel, hpcl, bpcl, iocl, indian oil, shell, fastag, parking, toll, namma yatri, lyft",
        "travel" to "irctc, makemytrip, goibibo, cleartrip, yatra, ixigo, redbus, indigo, air india, vistara, akasa, spicejet, airlines, airways, hotel, oyo, airbnb, booking.com, expedia, agoda",
        "shopping" to "amazon, flipkart, myntra, ajio, meesho, nykaa, tata cliq, croma, reliance digital, decathlon, ikea, lifestyle, westside, zara, h&m, uniqlo",
        "bills" to "electricity, bescom, tneb, msedcl, tata power, airtel, jio, vodafone, bsnl, broadband, recharge, bill, dth, tata play, gas, water, insurance, lic, premium, emi, loan",
        "entertainment" to "netflix, spotify, prime video, hotstar, jiocinema, bookmyshow, pvr, inox, youtube, apple music, gaana, steam, playstation, xbox, disney, sonyliv, zee5, movie, cinema",
        "health" to "pharmacy, apollo, medplus, 1mg, pharmeasy, netmeds, hospital, clinic, diagnostic, practo, cultfit, cult.fit, gym, doctor, lab, medical, chemist",
        "home" to "rent, landlord, nobroker, housing, maintenance, society, urban company, furniture, pepperfry",
        "education" to "school, college, university, tuition, udemy, coursera, byju, unacademy, books, course, fees",
        "transfer" to "neft, imps, rtgs, transfer, trf, atm, cash",
    ).map { (cat, list) ->
        // Keywords of <= 4 chars match whole words; longer ones match as substrings.
        cat to list.split(",").map { it.trim() }.map { kw ->
            if (kw.length <= 4) Regex("\\b" + Regex.escape(kw) + "\\b") else Regex(Regex.escape(kw))
        }
    }
    private val SALARY = Regex("salary|payroll|stipend")
    private val REFUND = Regex("refund|cashback|reversal|reversed")

    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private const val MON = "(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)"
    private val P1 = rx("""\b(\d{4})-(\d{2})-(\d{2})""")
    private val P2 = rx("""\b(\d{1,2})[-/ ]?$MON[a-z]*[-/ ,]*(\d{2,4})?\b""")
    private val P3 = rx("""\b$MON[a-z]*\.?\s+(\d{1,2}),?\s+(\d{4})\b""")
    private val P4 = rx("""\b(\d{1,2})[-/.](\d{1,2})[-/.](\d{2,4})\b""")

    fun normalize(body: String) = body.replace(WS, " ").trim()

    /** FNV-1a 64-bit of the normalized lowercase body. Used to dedupe without storing the SMS. */
    fun key(body: String) = fnv(normalize(body).lowercase())

    fun fnv(s: String): String {
        var h = 0xcbf29ce484222325UL
        for (b in s.toByteArray(Charsets.UTF_8)) {
            h = h xor b.toUByte().toULong()
            h *= 0x100000001b3UL
        }
        return h.toString(16).padStart(16, '0')
    }

    private fun personalSender(sender: String?) = sender != null && sender.count { it.isDigit() } >= 10

    /** True when the text is not a clear transaction but has an amount: a candidate for on-device AI rescue. */
    fun rescuable(body: String, sender: String?): Boolean {
        val text = normalize(body)
        if (personalSender(sender) || REJECT.containsMatchIn(text.lowercase())) return false
        return AMOUNT[0].containsMatchIn(text) || AMOUNT[1].containsMatchIn(text)
    }

    fun parse(body: String, sender: String?, now: LocalDate = LocalDate.now()): Parsed? {
        val text = normalize(body)
        val lower = text.lowercase()
        if (personalSender(sender)) return null
        if (REJECT.containsMatchIn(lower)) return null

        // Direction
        val k = lower.replace("credit card", "card").replace("debit card", "card")
        val income = TIER1.find(k)?.let { it.groupValues[1] in TIER1_INCOME }
            ?: TIER2.find(k)?.let { it.groupValues[1] == "cr" }
            ?: return null

        // Amount (first non-balance, positive match; patterns in order)
        val amount = AMOUNT.firstNotNullOfOrNull { p ->
            p.findAll(text).firstNotNullOfOrNull { m ->
                val v = BigDecimal(m.groupValues[1].replace(",", "")).movePointRight(2).toLong()
                val before = text.substring(maxOf(0, m.range.first - 25), m.range.first).lowercase()
                if (v > 0 && BALANCE_WORDS.none { it in before }) v else null
            }
        } ?: return null

        val account = accountOf(text)

        // Merchant
        var viaVpa = false
        var merchant: String? = VPA.findAll(text).firstNotNullOfOrNull { m ->
            val local = m.groupValues[1].replace(Regex("\\d"), "").replace(Regex("[.\\-_]"), " ")
                .replace(WS, " ").trim().lowercase()
            if (local.count { it.isLetter() } >= 3 && AGGREGATORS.none { local.startsWith(it) })
                local.split(" ").joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
            else null
        }
        if (merchant != null) viaVpa = true
        else {
            val rules = if (income) listOf(R3_INCOME) else listOf(R2, R3_EXPENSE, R4)
            merchant = rules.firstNotNullOfOrNull { r ->
                r.findAll(text).firstNotNullOfOrNull { m ->
                    val c = m.groupValues[1]
                    if (c.split(" ")[0].lowercase() in DENY) null else clean(c)
                }
            }
        }

        return Parsed(amount, income, account, merchant, categoryFor(lower, merchant, income, viaVpa), parseDate(text, now), fnv(lower))
    }

    fun accountOf(text: String): String? = ACCOUNT.find(text)?.groupValues?.get(1)?.takeLast(4)

    /** Source identifier (DESIGN §2.1): the parsed last 4, else the sender header's brand (`VM-PAYTMB-S` → `PAYTMB`). */
    fun sourceId(account: String?, sender: String?): String? =
        account ?: sender?.uppercase()?.split("-")?.firstOrNull { p -> p.length in 4..8 && p.all { it in 'A'..'Z' } }

    /**
     * The account an SMS belongs to. [text] = lowercase sender + " " + body. Digits match digits only, so an HDFC
     * debit paid "to paytmqr…" never lands in a "paytm" wallet; otherwise the first account with a text ID in [text].
     */
    fun accountFor(identifier: String?, text: String, accounts: List<Account>): Account? =
        if (identifier != null && identifier.all(Char::isDigit)) accounts.firstOrNull { identifier in it.ids }
        else accounts.firstOrNull { a -> a.ids.any { id -> !id.all(Char::isDigit) && id in text } }

    fun categoryFor(lower: String, merchant: String?, income: Boolean, viaVpa: Boolean = false): String =
        if (income) when {
            SALARY.containsMatchIn(lower) -> "salary"
            REFUND.containsMatchIn(lower) -> "refund"
            else -> "otherIncome"
        } else {
            val hay = lower + " " + (merchant ?: "").lowercase()
            KEYWORDS.firstOrNull { (_, kws) -> kws.any { it.containsMatchIn(hay) } }?.first
                ?: if (viaVpa) "transfer" else "other"
        }

    private fun clean(raw: String): String {
        var c = raw.replace(WS, " ").trim().replace(Regex("[-/#*_& ]+$"), "")
        if (c.none { it in 'a'..'z' }) {
            c = c.split(" ").joinToString(" ") { w ->
                if (w.isNotEmpty() && w.all { it.isLetter() }) w.lowercase().replaceFirstChar(Char::uppercaseChar) else w
            }
        }
        return c.take(32)
    }

    private fun date(y: Int, m: Int, d: Int): LocalDate? = runCatching { LocalDate.of(y, m, d) }.getOrNull()

    private fun year(s: String) = if (s.length == 2) 2000 + s.toInt() else s.toInt()

    private fun month(s: String) = MONTHS.indexOf(s.lowercase().take(3)) + 1

    fun parseDate(text: String, now: LocalDate): LocalDate? {
        val lo = now.minusDays(400)
        val hi = now.plusDays(1)
        fun ok(d: LocalDate?) = d != null && !d.isBefore(lo) && !d.isAfter(hi)

        P1.findAll(text).map { m -> m.groupValues.let { g -> date(g[1].toInt(), g[2].toInt(), g[3].toInt()) } }
            .firstOrNull(::ok)?.let { return it }
        P2.findAll(text).map { m ->
            val g = m.groupValues
            val mo = month(g[2])
            val day = g[1].toInt()
            if (g[3].isNotEmpty()) date(year(g[3]), mo, day)
            else date(now.year, mo, day)?.let { d -> if (d.isAfter(hi)) date(now.year - 1, mo, day) else d }
        }.firstOrNull(::ok)?.let { return it }
        P3.findAll(text).map { m -> m.groupValues.let { g -> date(g[3].toInt(), month(g[1]), g[2].toInt()) } }
            .firstOrNull(::ok)?.let { return it }
        return P4.findAll(text).map { m ->
            val g = m.groupValues
            var d = g[1].toInt()
            var mo = g[2].toInt()
            val y = year(g[3])
            if (mo > 12) d = mo.also { mo = d }
            val first = date(y, mo, d)
            if (ok(first)) first else date(y, d, mo)?.takeIf(::ok)
        }.firstOrNull(::ok)
    }
}
