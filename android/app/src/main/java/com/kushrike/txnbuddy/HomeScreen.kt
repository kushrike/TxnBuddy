package com.kushrike.txnbuddy

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

fun weekStart(d: LocalDate): LocalDate = d.with(TemporalAdjusters.previousOrSame(WeekFields.of(Locale.getDefault()).firstDayOfWeek))

fun spent(list: List<Txn>, from: LocalDate, to: LocalDate = LocalDate.now().plusDays(1)): Long {
    val a = from.millis()
    val b = to.millis()
    return list.sumOf { if (!it.income && it.date in a until b) it.amount else 0L }
}

@Composable
fun HomeScreen() {
    val txns = Store.txns
    val hide = Store.hideAmounts
    val today = LocalDate.now()
    val monthStart = today.withDayOfMonth(1)
    val month = remember(txns, today) { txns.filter { it.date >= monthStart.millis() } }
    val monthSpent = month.sumOf { if (it.income) 0 else it.amount }
    val monthIncome = month.sumOf { if (it.income) it.amount else 0 }
    val greeting = when (LocalTime.now().hour) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; else -> "Good evening" }

    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 110.dp)) {
        item {
            Row(Modifier.statusBarsPadding().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(greeting, fontSize = 13.sp, color = P.muted)
                    Text(today.month.getDisplayName(TextStyle.FULL, Locale.getDefault()), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = P.text)
                }
                IconButton(onClick = { Store.hideAmounts = !hide }) {
                    Icon(if (hide) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (hide) "Show amounts" else "Hide amounts", tint = P.muted)
                }
            }
        }
        item { Hero(monthSpent, monthIncome, spent(txns, today), spent(txns, weekStart(today)), today) }

        if (Nav.pending.isNotEmpty()) item { SmsBanner(Nav.pending.size) { Nav.review = Nav.pending; Nav.pending = emptyList() } }
        val sources = Store.unlinked().map { it.id }.filter { it !in Store.dismissedSources }
        if (sources.isNotEmpty()) item { SourcesCard(sources) }

        if (txns.isEmpty()) {
            item {
                val ctx = LocalContext.current
                val scope = rememberCoroutineScope()
                EmptyState("🧾", "No transactions yet", "Tap + to add one, or import from your bank SMS.") {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Chip("Add expense", true) { Nav.editor = Editor() }
                        Chip("Import SMS", false) {
                            if (SmsImport.granted(ctx)) scope.launch { SmsImport.manualScan(ctx) } else Nav.tab = 3
                        }
                    }
                }
            }
        } else {
            val cats = month.filter { !it.income }.groupBy { it.categoryId }
                .map { (id, l) -> Store.category(id) to l.sumOf { it.amount } }.sortedByDescending { it.second }
            if (cats.isNotEmpty()) {
                item { SectionTitle("Top categories", "Insights") { Nav.tab = 2 } }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(cats, key = { it.first.id }) { (c, v) ->
                            Card(Modifier.width(128.dp), padding = PaddingValues(14.dp)) {
                                Badge(c, 40.dp)
                                Spacer(Modifier.height(10.dp))
                                Text(c.name, fontSize = 13.sp, color = P.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(amt(v, hide), style = num(15.sp, FontWeight.SemiBold), color = P.text, maxLines = 1)
                                Spacer(Modifier.height(10.dp))
                                Progress(v.toFloat() / monthSpent.coerceAtLeast(1), c.tint, c.tint.copy(alpha = .15f), 4.dp)
                            }
                        }
                    }
                }
            }
            item { SectionTitle("Recent", "See all") { Nav.tab = 1 } }
            byDay(txns.take(8)).forEach { (day, net, items) ->
                item(key = "h$day") { DayHeader(day, net) }
                item(key = "g$day") {
                    Column(Modifier.padding(horizontal = 20.dp).clip(RoundedCornerShape(20.dp))) {
                        items.forEach { key(it.id) { SwipeRow(it) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun Hero(monthSpent: Long, income: Long, todaySpent: Long, weekSpent: Long, today: LocalDate) {
    val hide = Store.hideAmounts
    val budget = Store.budget
    Column(
        Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(HeroBrush).padding(20.dp),
    ) {
        Text("Spent this month", fontSize = 14.sp, color = Color.White.copy(alpha = .8f))
        Spacer(Modifier.height(4.dp))
        Text(amt(monthSpent, hide), style = num(44.sp), color = Color.White, maxLines = 1)
        Spacer(Modifier.height(14.dp))
        if (budget > 0) {
            val over = monthSpent > budget
            Progress(monthSpent.toFloat() / budget, if (over) Color(0xFFFFB4B4) else Color.White, Color.White.copy(alpha = .25f), 8.dp)
            Spacer(Modifier.height(8.dp))
            val daysLeft = today.lengthOfMonth() - today.dayOfMonth
            Text(
                if (over) "${amt(monthSpent - budget, hide)} over budget"
                else "${amt(budget - monthSpent, hide)} left · $daysLeft ${if (daysLeft == 1) "day" else "days"} to go",
                fontSize = 13.sp, color = Color.White.copy(alpha = .9f),
                modifier = Modifier.clickable { Nav.budgetDialog = true },
            )
        } else {
            Text(
                "Set a monthly budget ›", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White,
                modifier = Modifier.clip(CircleShape).background(Color.White.copy(alpha = .18f))
                    .clickable { Nav.budgetDialog = true }.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = .15f)))
        Spacer(Modifier.height(14.dp))
        Row {
            MiniStat("Today", amt(todaySpent, hide), Modifier.weight(1f))
            MiniStat("This week", amt(weekSpent, hide), Modifier.weight(1f))
            MiniStat("Income", amt(income, hide), Modifier.weight(1f))
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, fontSize = 12.sp, color = Color.White.copy(alpha = .7f))
        Text(value, style = num(15.sp, FontWeight.SemiBold), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SmsBanner(count: Int, onReview: () -> Unit) {
    Card(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp), padding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EmojiBadge("💬", 40.dp)
            Spacer(Modifier.width(12.dp))
            Text(
                "$count new ${if (count == 1) "transaction" else "transactions"} found in SMS",
                fontSize = 15.sp, fontWeight = FontWeight.Medium, color = P.text, modifier = Modifier.weight(1f),
            )
            Text(
                "Review", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(CircleShape).background(P.accent).clickable(onClick = onReview).padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun SourcesCard(ids: List<String>) {
    Card(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp), padding = PaddingValues(start = 14.dp, top = 4.dp, bottom = 12.dp, end = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EmojiBadge("💳", 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (ids.size == 1) "New account found in SMS" else "${ids.size} new accounts found in SMS",
                        fontSize = 15.sp, fontWeight = FontWeight.Medium, color = P.text, modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { Store.dismissedSources += ids }, Modifier.size(36.dp)) {
                        Icon(Icons.Rounded.Close, "Dismiss", tint = P.muted, modifier = Modifier.size(18.dp))
                    }
                }
                Row(Modifier.padding(end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Link ${ids.take(3).joinToString(", ", transform = ::idLabel)}${if (ids.size > 3) "…" else ""} to your accounts",
                        fontSize = 13.sp, color = P.muted, lineHeight = 18.sp, modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Link", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(CircleShape).background(P.accent).clickable { Nav.screen = "accounts" }.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}
