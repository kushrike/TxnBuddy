package com.kushrike.txnbuddy

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.roundToInt

/** Accounts (DESIGN §4.10): SMS identifiers waiting to be linked, then the user's accounts. */
@Composable
fun AccountsScreen() {
    var editing by remember { mutableStateOf<Account?>(null) }
    var creating by remember { mutableStateOf<String?>(null) } // "" = blank, else the SMS identifier to prefill
    var linking by remember { mutableStateOf<Source?>(null) }
    var deleting by remember { mutableStateOf<Account?>(null) }
    val accounts = Store.accounts
    val unlinked = Store.unlinked()
    val counts = remember(Store.txns) { Store.txns.groupingBy { it.accountId }.eachCount() }

    Column(Modifier.fillMaxSize().background(P.bg)) {
        TopBar("Accounts")
        LazyColumn(contentPadding = PaddingValues(bottom = 40.dp)) {
            if (accounts.isEmpty() && unlinked.isEmpty()) item {
                EmptyState("💳", "No accounts yet", "Add your bank accounts, cards and wallets. TxnBuddy matches bank SMS to them by the last 4 digits.") {
                    AddAccountButton { creating = "" }
                }
            }
            if (unlinked.isNotEmpty()) item {
                Group("Found in SMS") {
                    unlinked.forEachIndexed { i, s ->
                        if (i > 0) RowDivider()
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                            EmojiBadge("🔎", 40.dp, P.surface2)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(idLabel(s.id), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = P.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${s.count} ${if (s.count == 1) "transaction" else "transactions"}${spent(s)}",
                                    fontSize = 13.sp, color = P.muted, maxLines = 1,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Link", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clip(CircleShape).background(P.accent).clickable { linking = s }.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
                Caption("Link each one to an account and future messages are sorted automatically.")
            }
            if (accounts.isNotEmpty()) item {
                Group("Your accounts") {
                    accounts.forEachIndexed { i, a ->
                        if (i > 0) RowDivider()
                        AccountRow(a, Modifier.combinedClickable(onLongClick = { deleting = a }) { editing = a }) {
                            val n = counts[a.id] ?: 0
                            Text("$n ${if (n == 1) "transaction" else "transactions"}", fontSize = 13.sp, color = P.muted)
                        }
                    }
                }
            }
            if (accounts.isNotEmpty() || unlinked.isNotEmpty()) item {
                AddAccountButton(Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp)) { creating = "" }
            }
        }
    }

    linking?.let { s -> LinkSheet(s, onCreate = { linking = null; creating = s.id }) { linking = null } }
    creating?.let { id ->
        AccountEditor(null, prefill = id.ifEmpty { null }, onSaved = { a ->
            if (id.isNotEmpty() && id.lowercase() in a.ids) Nav.snack = Snack("Linked ${idLabel(id)} to ${a.name}")
        }) { creating = null }
    }
    editing?.let { AccountEditor(it) { editing = null } }
    deleting?.let { a -> DeleteAccountDialog(a, { deleting = null }) {} }
}

private fun spent(s: Source, suffix: String = "") = if (s.spent > 0) " · ${amt(s.spent)}$suffix" else ""

@Composable
private fun RowDivider() = Box(Modifier.padding(start = 68.dp).fillMaxWidth().height(1.dp).background(P.surface2))

@Composable
private fun AddAccountButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().height(52.dp).clip(CircleShape).background(P.accentSoft).clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Add, null, tint = P.accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text("Add account", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = P.accent)
    }
}

/** Kind badge · name · "Card · ••4521, ••7788". */
@Composable
private fun AccountRow(a: Account, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        EmojiBadge(a.emoji, 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(a.name, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = P.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (a.kind == "cash") a.kindName else a.kindName + " · " + (a.ids.joinToString(", ", transform = ::idLabel).ifEmpty { "No identifiers" }),
                fontSize = 13.sp, color = P.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}

@Composable
private fun DeleteAccountDialog(a: Account, onDismiss: () -> Unit, onDeleted: () -> Unit) =
    ConfirmDialog("Delete ${a.name}?", "Its transactions stay, just unlinked.", "Delete", onDismiss) { Store.deleteAccount(a); onDeleted() }

/** Link sheet (DESIGN §4.12): put an SMS identifier on a new or existing account. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LinkSheet(s: Source, onCreate: () -> Unit, onClose: () -> Unit) {
    val sheet = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    val hide: (() -> Unit) -> Unit = { then -> scope.launch { sheet.hide() }.invokeOnCompletion { then() } }

    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheet, containerColor = P.surface) {
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Text(idLabel(s.id), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = P.text, modifier = Modifier.padding(horizontal = 20.dp))
            Text("Seen in ${s.count} SMS${spent(s, " spent")}", fontSize = 14.sp, color = P.muted, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().clickable { hide(onCreate) }.padding(horizontal = 20.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(P.accentSoft), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Add, null, tint = P.accent)
                }
                Spacer(Modifier.width(12.dp))
                Text("Create new account", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = P.accent)
            }
            if (Store.accounts.isNotEmpty()) {
                Text("Or add it to", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = P.muted, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp))
                Store.accounts.forEach { a ->
                    AccountRow(a, Modifier.clickable { linkWithToast(s.id, a); hide(onClose) }.padding(horizontal = 4.dp))
                }
            }
        }
    }
}

private val NAME_HINTS = mapOf("bank" to "HDFC Savings", "card" to "Amazon ICICI Card", "wallet" to "Paytm Wallet", "cash" to "Cash")

/** Account editor (DESIGN §4.11), add + edit. [prefill] = an SMS identifier being linked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountEditor(existing: Account?, prefill: String? = null, onSaved: (Account) -> Unit = {}, onClose: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var kind by remember { mutableStateOf(existing?.kind ?: if (prefill == null) "bank" else if (prefill.all(Char::isDigit)) "card" else "wallet") }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var ids by remember { mutableStateOf(existing?.ids ?: listOfNotNull(prefill?.lowercase())) }
    var input by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    val shake = remember { Animatable(0f) }
    val nameFocus = remember { FocusRequester() }
    val close: () -> Unit = { scope.launch { sheet.hide() }.invokeOnCompletion { onClose() } }

    fun addId() {
        val id = normId(input)
        if (id == null) {
            haptic.performHapticFeedback(HapticFeedbackType.Reject)
            scope.launch {
                shake.animateTo(0f, keyframes {
                    durationMillis = 360
                    -28f at 45; 28f at 105; -18f at 165; 18f at 225; -8f at 285
                })
            }
        } else {
            ids = (ids + id).distinct()
            input = ""
        }
    }

    fun save() {
        // A valid ID still sitting in the field counts too.
        val all = (ids + listOfNotNull(normId(input))).distinct()
        val a = Account(existing?.id ?: UUID.randomUUID().toString(), name.trim(), kind, if (kind == "cash") emptyList() else all)
        Store.saveAccount(a)
        onSaved(a)
        close()
    }

    if (prefill != null) LaunchedEffect(Unit) { nameFocus.requestFocus() }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheet,
        containerColor = P.surface,
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 4.dp).width(36.dp).height(5.dp).clip(CircleShape).background(P.surface2)) },
    ) {
        Column(
            Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(76.dp)) {
                    Text(
                        "Cancel", color = P.muted, fontSize = 16.sp,
                        modifier = Modifier.clip(CircleShape).clickable(onClick = close).padding(horizontal = 4.dp, vertical = 8.dp),
                    )
                }
                Text(
                    if (existing == null) "New account" else "Edit account", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = P.text,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                )
                Box(Modifier.width(76.dp), contentAlignment = Alignment.CenterEnd) {
                    val ok = name.isNotBlank()
                    Text(
                        "Save", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = if (ok) P.accent else P.muted.copy(alpha = .5f),
                        modifier = Modifier.clip(CircleShape).clickable(enabled = ok) { save() }.padding(horizontal = 4.dp, vertical = 8.dp),
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KINDS.forEach { (k, emoji) ->
                    val sel = k == kind
                    Box(
                        Modifier.weight(1f).height(40.dp).clip(CircleShape).background(if (sel) P.accent else P.surface2).clickable { kind = k },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "$emoji ${k.replaceFirstChar(Char::uppercaseChar)}", fontSize = 14.sp, fontWeight = FontWeight.Medium,
                            color = if (sel) Color.White else P.text, maxLines = 1,
                        )
                    }
                }
            }

            Field(
                name, NAME_HINTS.getValue(kind), Modifier.fillMaxWidth().focusRequester(nameFocus),
                KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            ) { name = it.take(32) }

            if (kind != "cash") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Identifiers", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = P.muted)
                    if (ids.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ids.forEach { id ->
                                Row(
                                    Modifier.height(32.dp).clip(CircleShape).background(P.accentSoft).clickable { ids = ids - id }
                                        .padding(start = 12.dp, end = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(idLabel(id), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = P.accent)
                                    Spacer(Modifier.width(4.dp))
                                    Icon(Icons.Rounded.Close, "Remove", tint = P.accent, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                    Row(Modifier.offset { IntOffset(shake.value.roundToInt(), 0) }, verticalAlignment = Alignment.CenterVertically) {
                        Field(input, "Last 4 digits or wallet ID", Modifier.weight(1f), KeyboardOptions(imeAction = ImeAction.Done), onDone = ::addId) {
                            input = it.take(40)
                        }
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier.height(44.dp).clip(CircleShape).background(P.accentSoft).clickable(onClick = ::addId).padding(horizontal = 18.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text("Add", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = P.accent) }
                    }
                    Text(
                        "Only the last 4 digits are stored. Used to match bank SMS on this device.",
                        fontSize = 12.sp, color = P.muted, lineHeight = 16.sp,
                    )
                }
            }

            if (existing != null) {
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Delete account", color = P.danger, fontSize = 16.sp)
                }
            }
        }
    }

    if (confirmDelete && existing != null) DeleteAccountDialog(existing, { confirmDelete = false }) { close() }
}
