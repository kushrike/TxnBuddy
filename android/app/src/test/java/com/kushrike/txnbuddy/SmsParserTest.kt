package com.kushrike.txnbuddy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate

/** Runs the shared cross-platform vectors (shared/sms-samples.tsv) through the parser. */
class SmsParserTest {
    private val now = LocalDate.of(2026, 10, 12)

    private fun samples(): File {
        var dir: File? = File(System.getProperty("user.dir")!!).absoluteFile
        while (dir != null) {
            File(dir, "shared/sms-samples.tsv").takeIf { it.exists() }?.let { return it }
            dir = dir.parentFile
        }
        error("shared/sms-samples.tsv not found above ${System.getProperty("user.dir")}")
    }

    @Test fun fnv() {
        assertEquals("cbf29ce484222325", SmsParser.fnv(""))
        assertEquals("af63dc4c8601ec8c", SmsParser.fnv("a"))
        assertEquals("85944171f73967e8", SmsParser.fnv("foobar"))
    }

    @Test fun sharedVectors() {
        val rows = samples().readLines().drop(1).filter { it.isNotBlank() }
        assertEquals(26, rows.size)
        rows.forEach { line ->
            val f = line.split("\t")
            val (sender, type, amount, account, merchant) = f
            val (category, date, body) = f.drop(5)
            val p = SmsParser.parse(body, sender, now)
            val ctx = "row: $body"
            if (type == "none") { assertNull(ctx, p); return@forEach }
            requireNotNull(p) { "expected $type, got null — $ctx" }
            assertEquals(ctx, type == "credit", p.income)
            assertEquals(ctx, BigDecimal(amount).movePointRight(2).toLong(), p.amountMinor)
            assertEquals(ctx, account.orNull(), p.account)
            assertEquals(ctx, merchant.orNull(), p.merchant)
            assertEquals(ctx, category, p.categoryId)
            assertEquals(ctx, date.orNull()?.let(LocalDate::parse), p.date)
            assertEquals(ctx, SmsParser.fnv(SmsParser.normalize(body).lowercase()), p.smsKey)
        }
    }

    /** DESIGN §2.1: identifier normalisation, sender brand, and matching at import. */
    /** DESIGN §5 "Learning from you": merchant keys and the naive Bayes check. */
    @Test fun learn() {
        assertEquals("starbucks", Learn.mkey("Starbucks #1234"))
        assertEquals("uber india", Learn.mkey("UBER INDIA"))
        assertNull(Learn.mkey("#1234"))
        assertEquals("groceries", Learn.recall("Starbucks Coffee", mapOf("starbucks" to "groceries", "starbucks coffee x" to "food")))
        assertNull(Learn.recall("Starbuck", mapOf("starbucks" to "groceries")))
        val nb = Learn.Bayes(listOf("Swiggy order" to "food", "Zomato" to "food", "Uber ride" to "transport", "Ola cabs" to "transport", "Lenskart" to "health"))
        assertEquals("health", nb.predict("lenskart store"))
        assertEquals("transport", nb.predict("uber auto"))
        assertNull(nb.predict("random xyz"))
    }

    @Test fun accounts() {
        assertEquals("4521", normId("XXXX XXXX 4521"))
        assertEquals("4521", normId(" **** 1234-4521 "))
        assertEquals("paytm wallet", normId("  Paytm   Wallet "))
        assertEquals("hdfcbk", normId("HDFCBK"))
        assertNull(normId("ab"))
        assertNull(normId("  "))

        assertEquals("4521", SmsParser.sourceId("4521", "VM-HDFCBK"))
        assertEquals("PAYTMB", SmsParser.sourceId(null, "VM-PAYTMB-S"))
        assertEquals("HDFCBK", SmsParser.sourceId(null, "AX-HDFCBK"))
        assertNull(SmsParser.sourceId(null, "56767"))
        assertNull(SmsParser.sourceId(null, null))

        val hdfc = Account("h", "HDFC Savings", "bank", listOf("4521", "hdfcbk"))
        val paytm = Account("p", "Paytm Wallet", "wallet", listOf("paytm"))
        val all = listOf(hdfc, paytm)
        assertEquals(hdfc, SmsParser.accountFor("4521", "ax-hdfcbk rs 250 debited from a/c xx4521", all))
        // Digits match digits only: paying "paytmqr…" from an unknown card must not land in the wallet.
        assertNull(SmsParser.accountFor("9999", "ax-icicib rs 250 debited from card xx9999 to paytmqr123@paytm", all))
        assertEquals(paytm, SmsParser.accountFor("PAYTMB", "vm-paytmb-s paid rs 99 to swiggy", all))
        assertEquals(hdfc, SmsParser.accountFor(null, "ax-hdfcbk rs 99 spent", all))
        assertNull(SmsParser.accountFor(null, "jd-sbiinb rs 99 spent", all))
    }

    private fun String.orNull() = takeIf { it != "-" }

}
