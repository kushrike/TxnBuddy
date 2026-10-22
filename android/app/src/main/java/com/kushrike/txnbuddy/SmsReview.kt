package com.kushrike.txnbuddy

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** Review parsed SMS before they're added (DESIGN §4.7). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmsReviewSheet(found: List<Txn>, onClose: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val selected = remember { mutableStateMapOf<String, Boolean>().apply { found.forEach { put(it.id, true) } } }
    val category = remember { mutableStateMapOf<String, String>() }
    val count = selected.count { it.value }
    val close: () -> Unit = { scope.launch { sheet.hide() }.invokeOnCompletion { onClose() } }

    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheet, containerColor = P.bg) {
        Column(Modifier.navigationBarsPadding()) {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text("Found ${found.size} ${if (found.size == 1) "transaction" else "transactions"}", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = P.text)
                Spacer(Modifier.height(4.dp))
                Text("🔒 Parsed on this device. Nothing was uploaded.", fontSize = 13.sp, color = P.muted)
                Spacer(Modifier.height(10.dp))
                Row {
                    val all = count == found.size
                    Text(
                        if (all) "Deselect all" else "Select all", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = P.accent,
                        modifier = Modifier.clip(CircleShape).clickable { found.forEach { selected[it.id] = !all } }.padding(vertical = 6.dp),
                    )
                }
            }
            LazyColumn(Modifier.heightIn(max = 520.dp).padding(horizontal = 20.dp).clip(RoundedCornerShape(20.dp))) {
                items(found, key = { it.id }) { t ->
                    ReviewRow(t, selected[t.id] == true, category[t.id] ?: t.categoryId, { selected[t.id] = selected[t.id] != true }) { id ->
                        // Learned: every row from the same merchant follows, and so will future SMS.
                        val k = Learn.mkey(t.note)
                        found.filter { it.income == t.income && (it.id == t.id || (k != null && Learn.covers(k, it.note))) }.forEach { category[it.id] = id }
                        Store.learn(t.note, id)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            PrimaryButton(if (count == 0) "Nothing selected" else "Add $count ${if (count == 1) "transaction" else "transactions"}",
                Modifier.padding(horizontal = 20.dp), enabled = count > 0) {
                val added = Store.addAll(found.filter { selected[it.id] == true }.map { it.copy(categoryId = category[it.id] ?: it.categoryId) })
                Nav.snack = Snack("Added ${added.size} from SMS")
                close()
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ReviewRow(t: Txn, checked: Boolean, categoryId: String, onToggle: () -> Unit, onCategory: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val c = Store.category(categoryId)
    Row(
        Modifier.fillMaxWidth().background(P.surface).clickable(onClick = onToggle).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(24.dp).clip(CircleShape).background(if (checked) P.accent else P.surface2),
            contentAlignment = Alignment.Center,
        ) { if (checked) Icon(Icons.Rounded.Check, "Selected", tint = Color.White, modifier = Modifier.size(16.dp)) }
        Spacer(Modifier.width(12.dp))
        Box {
            Box(Modifier.clip(CircleShape).clickable { menu = true }) { Badge(c, 40.dp) }
            DropdownMenu(menu, { menu = false }, containerColor = P.surface) {
                Store.categories.filter { it.income == t.income }.forEach { cat ->
                    DropdownMenuItem(text = { Text("${cat.emoji}  ${cat.name}") }, onClick = { onCategory(cat.id); menu = false })
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.note.ifBlank { c.name }, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = P.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(c.name.takeIf { t.note.isNotBlank() }, dayLabel(t.date.toLocal().toLocalDate())).joinToString(" · "),
                fontSize = 13.sp, color = P.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(signed(t, hide = false), style = num(15.sp, FontWeight.SemiBold), color = if (t.income) P.income else P.text)
            accountLabel(t)?.let { a -> Text(if (a.length > 18) a.take(17) + "…" else a, fontSize = 13.sp, color = P.muted, maxLines = 1) }
        }
    }
}
