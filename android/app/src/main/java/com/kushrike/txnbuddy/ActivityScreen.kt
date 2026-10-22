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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ActivityScreen() {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableIntStateOf(0) } // 0 all, 1 expenses, 2 income, 3 sms
    var catFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var accFilter by rememberSaveable { mutableStateOf<String?>(null) } // null all, "" not linked, else account id
    val txns = Store.txns
    val cats = Store.categories
    val accounts = Store.accounts
    val list = remember(txns, cats, accounts, query, filter, catFilter, accFilter) {
        val q = query.trim().lowercase()
        txns.filter { t ->
            when (filter) { 1 -> !t.income; 2 -> t.income; 3 -> t.source == "sms"; else -> true } &&
                (catFilter == null || t.categoryId == catFilter) &&
                (accFilter == null || (t.accountId ?: "") == accFilter) &&
                (q.isEmpty() || t.note.lowercase().contains(q) || Store.category(t.categoryId).name.lowercase().contains(q) ||
                    Store.account(t.accountId)?.name?.lowercase()?.contains(q) == true || t.account?.lowercase()?.contains(q) == true)
        }
    }

    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 110.dp)) {
        item {
            Column(Modifier.statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 12.dp)) {
                Text("Activity", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = P.text)
                Spacer(Modifier.height(12.dp))
                SearchField(query) { query = it }
            }
        }
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("All", "Expenses", "Income", "From SMS").forEachIndexed { i, label ->
                    item { Chip(label, filter == i) { filter = i } }
                }
                item {
                    val c = catFilter?.let { Store.category(it) }
                    MenuChip(c?.let { "${it.emoji} ${it.name}" } ?: "Category", c != null,
                        listOf("All categories" to { catFilter = null }) + cats.map { cat -> "${cat.emoji}  ${cat.name}" to { catFilter = cat.id } })
                }
                if (accounts.isNotEmpty()) item {
                    val a = Store.account(accFilter)
                    MenuChip(a?.let { "${it.emoji} ${it.name}" } ?: if (accFilter == "") "Not linked" else "Account", accFilter != null,
                        listOf("All accounts" to { accFilter = null }) + accounts.map { "${it.emoji}  ${it.name}" to { accFilter = it.id } } +
                            ("Not linked" to { accFilter = "" }))
                }
            }
        }
        if (txns.isEmpty()) {
            item { EmptyState("🧾", "No transactions yet", "Tap + to add your first one.") }
        } else if (list.isEmpty()) {
            item { EmptyState("🔍", "Nothing matches", "Try another search or filter.") }
        } else {
            byDay(list).forEach { (day, net, items) ->
                item(key = "h$day") { DayHeader(day, net) }
                item(key = "g$day") {
                    Column(Modifier.padding(horizontal = 20.dp).clip(RoundedCornerShape(20.dp))) { items.forEach { key(it.id) { SwipeRow(it) } } }
                }
            }
            item {
                val out = list.sumOf { if (it.income) 0 else it.amount }
                val inc = list.sumOf { if (it.income) it.amount else 0 }
                Text(
                    "${list.size} ${if (list.size == 1) "transaction" else "transactions"} · ${amt(out)} spent · ${amt(inc)} received",
                    fontSize = 13.sp, color = P.muted, modifier = Modifier.fillMaxWidth().padding(24.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(14.dp)).background(P.surface2).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = P.muted)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text("Search notes, categories, accounts", color = P.muted, fontSize = 15.sp, maxLines = 1)
            BasicTextField(value, onChange, singleLine = true, textStyle = TextStyle(fontSize = 15.sp, color = P.text), cursorBrush = SolidColor(P.accent), modifier = Modifier.fillMaxWidth())
        }
        if (value.isNotEmpty()) Icon(Icons.Rounded.Close, "Clear", tint = P.muted, modifier = Modifier.clip(CircleShape).clickable { onChange("") })
    }
}

/** Filter chip with a dropdown ("Category ▾", "Account ▾"); accent when a filter is active. */
@Composable
private fun MenuChip(label: String, active: Boolean, options: List<Pair<String, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.height(36.dp).clip(CircleShape).background(if (active) P.accent else P.surface).clickable { open = true }
                .padding(start = 16.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (active) Color.White else P.text,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp),
            )
            Icon(Icons.Rounded.KeyboardArrowDown, null, tint = if (active) Color.White else P.muted)
        }
        DropdownMenu(open, onDismissRequest = { open = false }, containerColor = P.surface) {
            options.forEach { (text, onClick) -> DropdownMenuItem(text = { Text(text) }, onClick = { onClick(); open = false }) }
        }
    }
}
