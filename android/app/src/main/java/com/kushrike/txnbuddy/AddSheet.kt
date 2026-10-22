package com.kushrike.txnbuddy

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** Keypad rules (DESIGN §4.3): ≤ 2 decimals, ≤ 9 integer digits, single ".", leading "0" replaced. */
fun keypad(s: String, key: String): String = when (key) {
    "⌫" -> s.dropLast(1)
    "." -> when { "." in s -> s; s.isEmpty() -> "0."; else -> "$s." }
    else -> {
        val dec = s.substringAfter('.', "")
        when {
            "." in s && dec.length >= 2 -> s
            "." !in s && s.length >= 9 -> s
            s == "0" -> key
            else -> s + key
        }
    }
}

fun inputToMinor(s: String): Long = s.trimEnd('.').ifEmpty { "0" }.let { BigDecimal(it).movePointRight(2).toLong() }

fun minorToInput(m: Long): String = BigDecimal.valueOf(m, 2).stripTrailingZeros().toPlainString()

private fun display(s: String): String {
    if (s.isEmpty()) return "0"
    val int = NumberFormat.getIntegerInstance().format(s.substringBefore('.').ifEmpty { "0" }.toLong())
    return if ('.' in s) int + "." + s.substringAfter('.') else int
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSheet(e: Editor, onClose: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val editing = e.txn
    val today = LocalDate.now()
    var income by remember { mutableStateOf(e.income) }
    var input by remember { mutableStateOf(editing?.let { minorToInput(it.amount) } ?: "") }
    var catId by remember { mutableStateOf(editing?.categoryId ?: if (e.income) Store.lastIncomeCat else Store.lastExpenseCat) }
    var note by remember { mutableStateOf(editing?.note ?: "") }
    var date by remember { mutableStateOf(editing?.date?.toLocal()?.toLocalDate() ?: today) }
    var picker by remember { mutableStateOf(false) }
    // New entries default to the last picked account; edits keep their own.
    var accountId by remember { mutableStateOf(if (editing != null) editing.accountId else Store.account(Store.lastAccountId)?.id) }
    var accountMenu by remember { mutableStateOf(false) }
    var newAccount by remember { mutableStateOf(false) }
    // An SMS identifier that no account claims yet ("••4521 · Link").
    val unlinkedId = editing?.account?.takeIf { id -> Store.accounts.none { id.lowercase() in it.ids } }
    val shake = remember { Animatable(0f) }
    // New entries: until a category is tapped, typing a note picks one from what TxnBuddy learned (DESIGN §5).
    var picked by remember { mutableStateOf(editing != null) }
    val bayes = remember(income) { Learn.bayes(income) }
    val grid = rememberLazyGridState()
    val close: () -> Unit = { scope.launch { sheet.hide() }.invokeOnCompletion { onClose() } }

    val cats = Store.categories.filter { it.income == income }
    if (cats.none { it.id == catId }) {
        val last = if (income) Store.lastIncomeCat else Store.lastExpenseCat
        catId = cats.firstOrNull { it.id == last }?.id ?: cats.first().id
    }

    fun suggest(n: String) {
        val id = Learn.pick(n, income, SmsParser.categoryFor(n.lowercase(), null, income), bayes) ?: return
        val i = cats.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
        catId = id
        if (grid.layoutInfo.visibleItemsInfo.none { it.index == i }) scope.launch { grid.animateScrollToItem(i) }
    }

    fun save() {
        val minor = inputToMinor(input)
        if (minor <= 0) {
            haptic.performHapticFeedback(HapticFeedbackType.Reject)
            scope.launch {
                shake.animateTo(0f, keyframes {
                    durationMillis = 360
                    -28f at 45; 28f at 105; -18f at 165; 18f at 225; -8f at 285
                })
            }
            return
        }
        val time = editing?.date?.toLocal()?.toLocalTime() ?: LocalTime.now()
        val ms = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
        val base = editing ?: Txn(amount = minor, income = income, categoryId = catId, date = ms)
        Store.upsert(base.copy(amount = minor, income = income, categoryId = catId, note = note.trim(), date = ms, accountId = accountId))
        if (income) Store.lastIncomeCat = catId else Store.lastExpenseCat = catId
        if (note.isNotBlank()) Store.learn(note, catId)
        val acc = Store.account(accountId)
        if (acc != null && unlinkedId != null) linkWithToast(unlinkedId, acc)
        else if (editing != null && editing.categoryId != catId && note.isNotBlank()) learnedToast(note.trim(), catId, income)
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        close()
    }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheet,
        containerColor = P.surface,
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 4.dp).width(36.dp).height(5.dp).clip(CircleShape).background(P.surface2)) },
    ) {
        // Read these inside the sheet: it has its own window, so the activity's focus/keyboard owners don't apply.
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        val dismissKeyboard = { focus.clearFocus(); keyboard?.hide() }
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(76.dp)) {
                    Text(
                        "Cancel", color = P.muted, fontSize = 16.sp,
                        modifier = Modifier.clip(CircleShape).clickable(onClick = close).padding(horizontal = 4.dp, vertical = 8.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Segmented(listOf("Expense", "Income"), if (income) 1 else 0, Modifier.width(196.dp)) {
                    income = it == 1
                    haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                }
                Spacer(Modifier.weight(1f))
                Box(Modifier.width(76.dp), contentAlignment = Alignment.CenterEnd) {
                    if (editing != null) IconButton(onClick = { deleteWithUndo(editing); close() }) {
                        Icon(Icons.Rounded.Delete, "Delete", tint = P.danger)
                    }
                }
            }

            // Amount
            val shown = display(input)
            val size = when { shown.length > 12 -> 36.sp; shown.length > 9 -> 44.sp; else -> 56.sp }
            Row(
                Modifier.fillMaxWidth().height(84.dp).offset { IntOffset(shake.value.roundToInt(), 0) },
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(symbol(), fontSize = 28.sp, color = P.muted, modifier = Modifier.padding(end = 6.dp, top = 6.dp))
                AnimatedContent(
                    shown,
                    transitionSpec = { (slideInVertically(tween(160)) { it / 4 } + fadeIn(tween(160))) togetherWith fadeOut(tween(90)) },
                    label = "amount",
                ) { Text(it, style = num(size), color = if (input.isEmpty()) P.muted.copy(alpha = .6f) else P.text, maxLines = 1) }
            }

            // Account chip (2b)
            Box(Modifier.fillMaxWidth().padding(bottom = 10.dp), contentAlignment = Alignment.Center) {
                val acc = Store.account(accountId)
                Box {
                    Row(
                        Modifier.height(32.dp).clip(CircleShape).background(if (acc != null) P.accentSoft else P.surface2)
                            .clickable { dismissKeyboard(); accountMenu = true }.padding(start = 14.dp, end = if (acc == null && unlinkedId == null) 14.dp else 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            when {
                                acc != null -> "${acc.emoji} ${acc.name}"
                                unlinkedId != null -> "${idLabel(unlinkedId)} · Link"
                                else -> "＋ Account"
                            },
                            fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = when { acc != null -> P.accent; unlinkedId != null -> P.text; else -> P.muted },
                            modifier = Modifier.widthIn(max = 180.dp),
                        )
                        if (acc != null || unlinkedId != null) {
                            Icon(Icons.Rounded.KeyboardArrowDown, null, tint = if (acc != null) P.accent else P.muted, modifier = Modifier.size(20.dp))
                        }
                    }
                    DropdownMenu(accountMenu, { accountMenu = false }, containerColor = P.surface) {
                        val pick = { id: String? -> accountId = id; Store.lastAccountId = id ?: ""; accountMenu = false }
                        val check: @Composable (Boolean) -> Unit = { if (it) Icon(Icons.Rounded.Check, null, tint = P.accent) }
                        DropdownMenuItem(text = { Text("No account") }, trailingIcon = { check(accountId == null) }, onClick = { pick(null) })
                        Store.accounts.forEach { a ->
                            DropdownMenuItem(
                                text = { Text("${a.emoji}  ${a.name}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                trailingIcon = { check(a.id == accountId) }, onClick = { pick(a.id) },
                            )
                        }
                        HorizontalDivider(color = P.surface2)
                        DropdownMenuItem(text = { Text("New account…", color = P.accent) }, onClick = { accountMenu = false; newAccount = true })
                    }
                }
            }

            // Category grid: 4 columns, 2 rows, scrolls sideways
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val cell = maxWidth / 4.4f // a peek of the 5th column hints that it scrolls
                LazyHorizontalGrid(
                    rows = GridCells.Fixed(2), modifier = Modifier.fillMaxWidth().height(184.dp), state = grid,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(cats, key = { it.id }) { c ->
                        val sel = c.id == catId
                        Column(
                            Modifier.width(cell).clip(RoundedCornerShape(16.dp)).clickable {
                                catId = c.id
                                picked = true
                                dismissKeyboard()
                                haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            }.padding(vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Badge(c, 52.dp, ring = sel)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                c.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = if (sel) P.text else P.muted, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }

            // Note + date
            Spacer(Modifier.height(8.dp))
            BasicTextField(
                value = note, onValueChange = { note = it.take(60); if (!picked) suggest(note) }, singleLine = true,
                textStyle = TextStyle(fontSize = 15.sp, color = P.text),
                cursorBrush = SolidColor(P.accent),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { dismissKeyboard() }),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    Box(
                        Modifier.height(48.dp).clip(RoundedCornerShape(16.dp)).background(P.surface2).padding(horizontal = 16.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (note.isEmpty()) Text("Add a note", fontSize = 15.sp, color = P.muted)
                        inner()
                    }
                },
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val yesterday = today.minusDays(1)
                val custom = date != today && date != yesterday
                DateChip("Today", date == today) { date = today }
                DateChip("Yesterday", date == yesterday) { date = yesterday }
                Row(
                    Modifier.height(40.dp).clip(CircleShape).background(if (custom) P.accent else P.surface2)
                        .clickable { picker = true }.padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.CalendarMonth, null, tint = if (custom) Color.White else P.text, modifier = Modifier.width(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (custom) date.format(DateTimeFormatter.ofPattern("d MMM yyyy")) else "Pick date",
                        color = if (custom) Color.White else P.text, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    )
                }
            }

            // Keypad
            Spacer(Modifier.height(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", ".", "0", "⌫").chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { k ->
                            Box(
                                Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(18.dp)).background(P.surface2)
                                    .combinedClickable(
                                        onLongClick = if (k == "⌫") ({ input = "" }) else null,
                                        onClick = {
                                            dismissKeyboard()
                                            input = keypad(input, k)
                                            haptic.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                                        },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (k == "⌫") Icon(Icons.AutoMirrored.Rounded.Backspace, "Delete digit", tint = P.text)
                                else Text(k, style = num(26.sp, FontWeight.Medium), color = P.text)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            PrimaryButton(if (editing != null) "Update" else if (income) "Save income" else "Save expense") { dismissKeyboard(); save() }
            Spacer(Modifier.height(12.dp))
        }
    }

    if (newAccount) {
        AccountEditor(null, prefill = unlinkedId, onSaved = { accountId = it.id; Store.lastAccountId = it.id }) { newAccount = false }
    }

    if (picker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) =
                    utcTimeMillis <= today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            },
        )
        DatePickerDialog(
            onDismissRequest = { picker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    picker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picker = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
}

@Composable
private fun DateChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(40.dp).clip(CircleShape).background(if (selected) P.accent else P.surface2).clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (selected) Color.White else P.text) }
}
