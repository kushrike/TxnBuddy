package com.kushrike.txnbuddy

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CurrencyExchange
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException.ErrorCode
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlin.random.Random

@Composable
fun SettingsScreen(activity: MainActivity) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf<String?>(null) } // "erase" | "restore"
    var pendingRestore by remember { mutableStateOf<String?>(null) }
    var currencyMenu by remember { mutableStateOf(false) }
    val today = LocalDate.now()

    val smsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        Nav.smsGranted = ok
        if (ok) scope.launch { if (Store.lastScan == 0L) SmsImport.autoScan(ctx) else SmsImport.manualScan(ctx) }
        else if (!activity.shouldShowRequestPermissionRationale(Manifest.permission.READ_SMS)) {
            activity.expectingReturn = true
            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
        }
    }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { write(ctx, it, Store.csv()); Nav.snack = Snack("Exported ${Store.txns.size} transactions") }
    }
    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { write(ctx, it, Store.toJson()); Nav.snack = Snack("Backup saved") }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { u ->
            pendingRestore = runCatching { ctx.contentResolver.openInputStream(u)?.bufferedReader()?.use { it.readText() } }.getOrNull()
            if (pendingRestore != null) confirm = "restore"
        }
    }

    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 40.dp)) {
        item {
            Text(
                "Settings", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = P.text,
                modifier = Modifier.statusBarsPadding().padding(start = 20.dp, top = 12.dp, bottom = 12.dp),
            )
        }
        item { PrivacyCard() }

        item {
            Group("Security") {
                ToggleRow(Icons.Rounded.Fingerprint, P.accent, "App lock", "Fingerprint, face or screen lock", Store.lockEnabled) { on ->
                    if (!on) Store.lockEnabled = false
                    else if (!activity.deviceSecure()) Nav.snack = Snack("Set a screen lock in system settings first")
                    else activity.authenticate { Store.lockEnabled = true }
                }
                Divider()
                ToggleRow(Icons.Rounded.VisibilityOff, Color(0xFF64748B), "Hide amounts", "Show ${symbol()}•••• everywhere", Store.hideAmounts) { Store.hideAmounts = it }
            }
        }

        item {
            Group("Bank SMS") {
                val granted = Nav.smsGranted
                Row_(Icons.Rounded.Sms, Color(0xFF22C55E), "Read bank SMS", if (granted) "Allowed · parsed only on this phone" else "Tap to allow",
                    onClick = if (granted) null else ({ activity.expectingReturn = true; smsPermission.launch(Manifest.permission.READ_SMS) })) {
                    if (granted) Icon(Icons.Rounded.Check, null, tint = P.income) else Chevron()
                }
                Divider()
                Row_(Icons.Rounded.Inbox, Color(0xFF3B82F6), "Scan inbox now", "Looks back 180 days",
                    onClick = if (granted && !Nav.scanning) ({ scope.launch { SmsImport.manualScan(ctx) } }) else null) {
                    if (Nav.scanning) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = P.accent) else Chevron()
                }
                Divider()
                ToggleRow(Icons.Rounded.Sync, Color(0xFF06B6D4), "Auto-add new SMS on open", null, Store.autoSms) { Store.autoSms = it }
            }
            Caption(if (Store.lastScan > 0) "Last scanned ${DateUtils.getRelativeTimeSpanString(Store.lastScan)}" else "Not scanned yet")
        }

        item {
            Group("On-device AI") {
                Row_(Icons.Rounded.AutoAwesome, Color(0xFF8B5CF6), "Gemini Nano", Ai.label, onClick = { Nav.screen = "ai" }) {
                    if (Ai.status == FeatureStatus.AVAILABLE) Chevron()
                    else Text("Set up ›", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = P.accent)
                }
                Divider()
                ToggleRow(
                    Icons.Rounded.Tune, Color(0xFFEC4899), "Smart categorization", null, Store.aiEnabled && Ai.status == FeatureStatus.AVAILABLE,
                    enabled = Ai.status == FeatureStatus.AVAILABLE,
                ) { Store.aiEnabled = it }
                Divider()
                val n = Store.learned.size
                Row_(Icons.Rounded.Psychology, Color(0xFF10B981), "Learned from you", null, onClick = if (n > 0) ({ confirm = "forget" }) else null) {
                    Text(if (n == 0) "Nothing yet" else if (n == 1) "1 merchant" else "$n merchants", fontSize = 15.sp, color = P.muted)
                }
            }
            Caption("Learns the categories you pick, on this device. On supported phones Gemini Nano helps with new merchants. Nothing is sent anywhere.")
        }

        item {
            Group("Budget & currency") {
                Row_(Icons.Rounded.Savings, Color(0xFFF59E0B), "Monthly budget", null, onClick = { Nav.budgetDialog = true }) {
                    Value(if (Store.budget > 0) money(Store.budget) else "Not set")
                }
                Divider()
                Box {
                    Row_(Icons.Rounded.CurrencyExchange, Color(0xFF10B981), "Currency", null, onClick = { currencyMenu = true }) {
                        Value("${Store.currency} ${symbol()}")
                    }
                    DropdownMenu(currencyMenu, { currencyMenu = false }, Modifier.align(Alignment.CenterEnd), containerColor = P.surface) {
                        CURRENCIES.forEach { c ->
                            DropdownMenuItem(text = { Text("$c  ${symbol(c)}") }, onClick = { Store.changeCurrency(c); currencyMenu = false })
                        }
                    }
                }
            }
        }

        item {
            Group("Accounts & categories") {
                Row_(Icons.Rounded.AccountBalanceWallet, Color(0xFF14B8A6), "Accounts", "Banks, cards and wallets", onClick = { Nav.screen = "accounts" }) {
                    if (Store.unlinked().isNotEmpty()) Box(Modifier.padding(end = 8.dp).size(8.dp).clip(CircleShape).background(P.accent))
                    Value("${Store.accounts.size}")
                    Chevron()
                }
                Divider()
                Row_(Icons.Rounded.Category, Color(0xFFFF8A3D), "Categories", "${Store.categories.size} categories", onClick = { Nav.screen = "categories" }) { Chevron() }
            }
        }

        item {
            Group("Your data") {
                Row_(Icons.Rounded.Download, Color(0xFF3B82F6), "Export CSV", "For spreadsheets", onClick = {
                    activity.expectingReturn = true; exportCsv.launch("txnbuddy-$today.csv")
                }) { Chevron() }
                Divider()
                Row_(Icons.Rounded.Backup, Color(0xFF14B8A6), "Back up", "Save a file you control (works on iOS too)", onClick = {
                    activity.expectingReturn = true; backup.launch("txnbuddy-backup-$today.json")
                }) { Chevron() }
                Divider()
                Row_(Icons.Rounded.Restore, Color(0xFF6366F1), "Restore from backup", null, onClick = {
                    activity.expectingReturn = true; restore.launch(arrayOf("application/json", "application/octet-stream", "text/plain"))
                }) { Chevron() }
                Divider()
                Row_(Icons.Rounded.DeleteForever, P.danger, "Erase all data", null, titleColor = P.danger, onClick = { confirm = "erase" }) {}
            }
        }

        item {
            Group("Updates") {
                val r = Updater.latest
                Row_(
                    Icons.Rounded.SystemUpdate, Color(0xFF3B82F6), if (r != null) "Update to ${r.version}" else "Check for updates",
                    Updater.progress?.let { "Downloading… ${(it * 100).toInt()}%" } ?: Updater.status ?: "Version ${BuildConfig.VERSION_NAME}",
                    onClick = if (Updater.busy) null else ({ scope.launch { if (r != null) Updater.install(activity) else Updater.check() } }),
                ) {
                    if (Updater.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = P.accent)
                    else if (r != null) Text("Install", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = P.accent)
                    else Chevron()
                }
                Divider()
                ToggleRow(Icons.Rounded.Update, Color(0xFF8B5CF6), "Check automatically", "Once a day, when you open the app", Store.autoUpdate) { Store.autoUpdate = it }
            }
            Caption("Downloads new versions from github.com/kushrike/TxnBuddy. Only the version is asked for; none of your data is sent. Android installs an update only if it's signed with the same key as this app.")
        }

        if (BuildConfig.DEBUG) item {
            Group("Debug") {
                Row_(Icons.Rounded.Science, Color(0xFF9CA3AF), "Load demo data", "≈120 sample transactions", onClick = {
                    Store.addAll(demoData()); demoAccounts().forEach(Store::saveAccount); Nav.snack = Snack("Demo data loaded")
                }) { Chevron() }
            }
        }

        item {
            Text(
                "TxnBuddy ${BuildConfig.VERSION_NAME}\nNo analytics. No ads. No account.", fontSize = 12.sp, color = P.muted,
                textAlign = TextAlign.Center, lineHeight = 18.sp, modifier = Modifier.fillMaxWidth().padding(top = 28.dp),
            )
        }
    }

    when (confirm) {
        "erase" -> ConfirmDialog(
            "Erase all data?", "Every transaction, account, category and budget on this phone will be deleted. This can't be undone.", "Erase",
            onDismiss = { confirm = null },
        ) { Store.eraseAll(); Nav.snack = Snack("All data erased") }
        "forget" -> ConfirmDialog(
            "Forget what TxnBuddy learned?", "Your transactions stay as they are.", "Forget",
            onDismiss = { confirm = null },
        ) { Store.forgetLearned(); Nav.snack = Snack("Forgot learned categories") }
        "restore" -> ConfirmDialog(
            "Restore this backup?", "It replaces all transactions, accounts, categories and budget currently on this phone.", "Restore",
            onDismiss = { confirm = null; pendingRestore = null },
        ) {
            runCatching { Store.restore(pendingRestore!!) }
                .onSuccess { Nav.snack = Snack("Restored ${Store.txns.size} transactions") }
                .onFailure { Nav.snack = Snack("That file isn't a TxnBuddy backup") }
        }
    }
}

private fun write(ctx: android.content.Context, uri: Uri, text: String) {
    runCatching { ctx.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(text) } }
        .onFailure { Nav.snack = Snack("Couldn't save the file") }
}

@Composable
fun ConfirmDialog(title: String, body: String, action: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = P.surface,
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        text = { Text(body, color = P.muted) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(action, color = P.danger, fontWeight = FontWeight.SemiBold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = P.text) } },
    )
}

@Composable
private fun PrivacyCard() {
    Card(Modifier.padding(horizontal = 20.dp), color = P.accentSoft) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EmojiBadge("🛡️", 52.dp, bg = P.surface)
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Private by design", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = P.text)
                Text("Your money data never leaves this device.", fontSize = 14.sp, color = P.muted)
            }
        }
        Spacer(Modifier.height(14.dp))
        listOf("Stored only on this device", "On-device AI", "Internet only for app updates", "Not in cloud backups").forEach {
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Check, null, tint = P.accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(it, fontSize = 14.sp, color = P.text)
            }
        }
    }
}

@Composable
fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = P.muted, modifier = Modifier.padding(start = 24.dp, top = 24.dp, bottom = 8.dp))
    Column(Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(P.surface), content = content)
}

@Composable
fun Caption(text: String) =
    Text(text, fontSize = 12.sp, color = P.muted, lineHeight = 16.sp, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp))

@Composable
private fun Divider() = Box(Modifier.padding(start = 62.dp).fillMaxWidth().height(1.dp).background(P.surface2))

@Composable
fun Chevron() = Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = P.muted.copy(alpha = .6f))

@Composable
private fun Value(text: String) = Text(text, fontSize = 15.sp, color = P.muted)

@Composable
private fun Row_(
    icon: ImageVector, tint: Color, title: String, subtitle: String?,
    titleColor: Color = P.text, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(tint.copy(alpha = .15f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = titleColor)
            if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = P.muted)
        }
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}

@Composable
private fun ToggleRow(icon: ImageVector, tint: Color, title: String, subtitle: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row_(icon, tint, title, subtitle, onClick = if (enabled) ({ onChange(!checked) }) else null) {
        Switch(
            checked, onChange, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = P.accent, checkedThumbColor = Color.White, uncheckedTrackColor = P.surface2, uncheckedBorderColor = Color.Transparent),
        )
    }
}

// ---------------------------------------------------------------- budget

@Composable
fun BudgetDialog(onClose: () -> Unit) {
    var text by remember { mutableStateOf(if (Store.budget > 0) minorToInput(Store.budget) else "") }
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = P.surface,
        title = { Text("Monthly budget", fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                Text("We'll show how much is left on Home.", color = P.muted, fontSize = 14.sp)
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(16.dp)).background(P.surface2).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(symbol(), fontSize = 22.sp, color = P.muted)
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(
                        text, { v -> text = v.filter { it.isDigit() || it == '.' }.fold("") { s, c -> keypad(s, c.toString()) } },
                        singleLine = true, textStyle = num(22.sp, FontWeight.SemiBold).copy(color = P.text), cursorBrush = SolidColor(P.accent),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { Store.changeBudget(inputToMinor(text)); onClose() }) { Text("Save", fontWeight = FontWeight.SemiBold) } },
        dismissButton = {
            TextButton(onClick = { if (Store.budget > 0) Store.changeBudget(0); onClose() }) {
                Text(if (Store.budget > 0) "Remove" else "Cancel", color = P.muted)
            }
        },
    )
}

// ---------------------------------------------------------------- categories

/** Back arrow + title for screens pushed over the tabs (Nav.screen). */
@Composable
fun TopBar(title: String, action: @Composable () -> Unit = {}) {
    Row(Modifier.statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { Nav.screen = null }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = P.text) }
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = P.text, modifier = Modifier.weight(1f))
        action()
    }
}

@Composable
fun CategoriesScreen() {
    var editing by remember { mutableStateOf<Category?>(null) }
    var creating by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(P.bg)) {
        TopBar("Categories") { IconButton(onClick = { creating = true }) { Icon(Icons.Rounded.Add, "Add category", tint = P.accent) } }
        LazyColumn(contentPadding = PaddingValues(bottom = 40.dp)) {
            listOf(false to "Expenses", true to "Income").forEach { (inc, label) ->
                item {
                    Group(label) {
                        Store.categories.filter { it.income == inc }.forEachIndexed { i, c ->
                            if (i > 0) Box(Modifier.padding(start = 68.dp).fillMaxWidth().height(1.dp).background(P.surface2))
                            Row(
                                Modifier.fillMaxWidth().clickable { editing = c }.padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Badge(c, 40.dp)
                                Spacer(Modifier.width(12.dp))
                                Text(c.name, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = P.text, modifier = Modifier.weight(1f))
                                if (!c.builtIn) Text("Custom", fontSize = 12.sp, color = P.muted, modifier = Modifier.padding(end = 8.dp))
                                Chevron()
                            }
                        }
                    }
                }
            }
        }
    }
    if (creating) CategoryEditor(null) { creating = false }
    editing?.let { CategoryEditor(it) { editing = null } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryEditor(existing: Category?, onClose: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var emoji by remember { mutableStateOf(existing?.emoji ?: "🏷️") }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var color by remember { mutableStateOf(existing?.color ?: PALETTE[5]) }
    var income by remember { mutableStateOf(existing?.income ?: false) }
    val close: () -> Unit = { scope.launch { sheet.hide() }.invokeOnCompletion { onClose() } }
    val preview = Category(existing?.id ?: "", name.ifBlank { "Name" }, emoji.ifBlank { "🏷️" }, color, income)

    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheet, containerColor = P.surface) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(if (existing == null) "New category" else "Edit category", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = P.text)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Badge(preview, 64.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(emoji, "Emoji", Modifier.width(96.dp)) { emoji = it.take(8) }
                    Field(name, "Name", Modifier.fillMaxWidth()) { name = it.take(20) }
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(PALETTE) { hex ->
                    val c = Category("", "", "", hex, false).tint
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).background(c).then(if (hex == color) Modifier.border(3.dp, P.text, CircleShape) else Modifier)
                            .clickable { color = hex },
                    )
                }
            }
            if (existing == null) Segmented(listOf("Expense", "Income"), if (income) 1 else 0, Modifier.fillMaxWidth()) { income = it == 1 }
            PrimaryButton("Save", enabled = name.isNotBlank() && emoji.isNotBlank()) {
                Store.upsertCategory(Category(existing?.id ?: UUID.randomUUID().toString().take(8), name.trim(), emoji.trim(), color, income))
                close()
            }
            if (existing != null && !existing.builtIn) {
                TextButton(onClick = { Store.deleteCategory(existing); close() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Delete category", color = P.danger)
                }
            }
        }
    }
}

@Composable
fun Field(
    value: String, placeholder: String, modifier: Modifier,
    keyboard: KeyboardOptions = KeyboardOptions.Default, onDone: (() -> Unit)? = null, onChange: (String) -> Unit,
) {
    BasicTextField(
        value, onChange, singleLine = true, textStyle = TextStyle(fontSize = 16.sp, color = P.text), cursorBrush = SolidColor(P.accent),
        modifier = modifier, keyboardOptions = keyboard, keyboardActions = KeyboardActions(onDone = if (onDone != null) ({ onDone() }) else null),
        decorationBox = { inner ->
            Box(Modifier.height(44.dp).clip(RoundedCornerShape(14.dp)).background(P.surface2).padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(placeholder, color = P.muted, fontSize = 16.sp)
                inner()
            }
        },
    )
}

// ---------------------------------------------------------------- AI setup guide (DESIGN §4.13)

private const val AICORE = "com.google.android.aicore"
private const val PCS = "com.google.android.as.oss"

@Composable
fun AiSetupScreen(activity: MainActivity) {
    val ctx = LocalContext.current
    var checking by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { Ai.refresh() }
    val status = Ai.status
    val error = Ai.error
    val (emoji, line) = when (status) {
        Ai.CHECKING -> "⏳" to "Checking Gemini Nano…"
        FeatureStatus.AVAILABLE -> "✅" to "Gemini Nano is ready."
        FeatureStatus.DOWNLOADABLE -> "⬇️" to "Gemini Nano can be downloaded."
        FeatureStatus.DOWNLOADING -> "⏳" to "Gemini Nano is downloading."
        else -> "⚠️" to when (error) {
            ErrorCode.AICORE_INCOMPATIBLE, ErrorCode.NEEDS_SYSTEM_UPDATE -> "Gemini Nano needs an update first."
            ErrorCode.NOT_ENOUGH_DISK_SPACE -> "Gemini Nano needs more free storage."
            else -> "Gemini Nano isn't available on this phone yet."
        }
    }
    fun store(pkg: String) {
        activity.expectingReturn = true
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))) }
            .recoverCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))) }
            .onFailure { activity.expectingReturn = false; Nav.snack = Snack("Couldn't open the Play Store") }
    }

    val storage: @Composable (Int) -> Unit = { Step(it, "Free up storage", "Gemini Nano needs a few GB free on this phone.") }
    val phone: @Composable (Int) -> Unit = {
        Step(it, "Check your phone", null) {
            Text("This phone: ${Build.MANUFACTURER.replaceFirstChar(Char::uppercaseChar)} ${Build.MODEL}", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = P.text)
            StepText(
                "Gemini Nano for apps runs on Pixel 9 and newer, Galaxy S26 series, Galaxy Z Fold7 / Fold8 / Flip8 / TriFold, and recent " +
                    "flagships from OnePlus, Xiaomi, OPPO, vivo, Honor, Motorola and others. Older phones, including Galaxy S25 and earlier " +
                    "and A-series, don't have it yet.",
            )
        }
    }
    val apps: @Composable (Int) -> Unit = {
        Step(it, "Update Android AICore and Private Compute Services", "AICore runs Gemini Nano. Private Compute Services downloads it.") {
            listOf("Android AICore" to AICORE, "Private Compute Services" to PCS).forEach { (name, pkg) ->
                // Throws when the package is missing (visible to us through <queries> in the manifest).
                val installed = runCatching { ctx.packageManager.getApplicationEnabledSetting(pkg) }.isSuccess
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = P.text)
                        Text(if (installed) "Installed" else "Not installed", fontSize = 13.sp, color = if (installed) P.income else P.danger)
                    }
                    Text(
                        if (installed) "Update" else "Install", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = P.accent,
                        modifier = Modifier.clip(CircleShape).background(P.accentSoft).clickable { store(pkg) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
    val wifi: @Composable (Int) -> Unit = {
        Step(it, "Stay on Wi‑Fi and restart the phone", "After an update AICore fetches its settings in the background. This can take a few minutes to a few hours; a restart speeds it up.")
    }
    val system: @Composable (Int) -> Unit = { Step(it, "Install system updates", "Settings → Software update.") }
    val root: @Composable (Int) -> Unit = { Step(it, "Use an unmodified phone", "Phones with an unlocked bootloader or root aren't supported.") }
    // The step that matches the error comes first.
    val steps = when {
        status == FeatureStatus.DOWNLOADABLE -> listOfNotNull(storage.takeIf { error == ErrorCode.NOT_ENOUGH_DISK_SPACE })
        status == FeatureStatus.AVAILABLE || status == FeatureStatus.DOWNLOADING || status == Ai.CHECKING -> emptyList()
        error == ErrorCode.AICORE_INCOMPATIBLE -> listOf(apps, phone, wifi, system, root)
        error == ErrorCode.NEEDS_SYSTEM_UPDATE -> listOf(system, phone, apps, wifi, root)
        error == ErrorCode.NOT_ENOUGH_DISK_SPACE -> listOf(storage, phone, apps, wifi, system, root)
        else -> listOf(phone, apps, wifi, system, root)
    }

    Column(Modifier.fillMaxSize().background(P.bg)) {
        TopBar("On-device AI")
        LazyColumn(contentPadding = PaddingValues(bottom = 40.dp)) {
            item {
                Card(Modifier.padding(horizontal = 20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        EmojiBadge(emoji, 52.dp)
                        Spacer(Modifier.width(14.dp))
                        Text(line, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = P.text, lineHeight = 22.sp)
                    }
                    if (status == FeatureStatus.DOWNLOADABLE || status == FeatureStatus.DOWNLOADING) {
                        Spacer(Modifier.height(16.dp))
                        if (status == FeatureStatus.DOWNLOADABLE) PrimaryButton("Download") { activity.lifecycleScope.launch { Ai.download() } }
                        else if (Ai.total > 0) {
                            Progress(Ai.downloaded.toFloat() / Ai.total, P.accent, P.surface2, 8.dp)
                            Spacer(Modifier.height(8.dp))
                            Text("${gb(Ai.downloaded)} of ${gb(Ai.total)} GB", style = num(14.sp, FontWeight.Medium), color = P.muted)
                        } else StepText("Downloading in the background. Keep the phone on Wi‑Fi.")
                        Spacer(Modifier.height(10.dp))
                        StepText("AICore downloads it, not TxnBuddy. Use Wi‑Fi.")
                    }
                }
            }
            if (steps.isNotEmpty()) item {
                Group("Try these steps") {
                    steps.forEachIndexed { i, step ->
                        if (i > 0) Box(Modifier.padding(start = 56.dp).fillMaxWidth().height(1.dp).background(P.surface2))
                        step(i + 1)
                    }
                }
            }
            item {
                Box(
                    Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp).fillMaxWidth().height(52.dp).clip(CircleShape).background(P.accentSoft)
                        .clickable(enabled = !checking) { checking = true; activity.lifecycleScope.launch { Ai.refresh(); checking = false } },
                    contentAlignment = Alignment.Center,
                ) {
                    if (checking) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = P.accent)
                    else Text("Check again", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = P.accent)
                }
                Text(
                    "TxnBuddy works fully without AI: the built-in rules read your SMS, and it learns from the categories you pick.",
                    fontSize = 13.sp, color = P.muted, textAlign = TextAlign.Center, lineHeight = 18.sp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 16.dp),
                )
            }
        }
    }
}

private fun gb(bytes: Long) = String.format(java.util.Locale.getDefault(), "%.1f", bytes / 1e9)

@Composable
private fun StepText(text: String) = Text(text, fontSize = 14.sp, color = P.muted, lineHeight = 20.sp)

@Composable
private fun Step(n: Int, title: String, body: String?, content: @Composable ColumnScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(16.dp)) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(P.accentSoft), contentAlignment = Alignment.Center) {
            Text("$n", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = P.accent)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(top = 3.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = P.text)
            if (body != null) StepText(body)
            content()
        }
    }
}

// ---------------------------------------------------------------- demo data (debug builds only; R8 drops it from release)

fun demoData(): List<Txn> {
    val rnd = Random(42)
    val today = LocalDate.now()
    val out = mutableListOf<Txn>()
    fun at(d: LocalDate, hour: Int = rnd.nextInt(8, 22)) = d.atTime(LocalTime.of(hour, rnd.nextInt(60))).atZone(zone).toInstant().toEpochMilli()
    fun add(d: LocalDate, cat: String, note: String, lo: Int, hi: Int = lo, income: Boolean = false, sms: Boolean = rnd.nextBoolean()) {
        val rupees = if (hi > lo) rnd.nextInt(lo, hi) else lo
        val paise = if (hi > lo && rnd.nextInt(4) == 0) rnd.nextInt(1, 99) else 0
        out += Txn(
            amount = rupees * 100L + paise, income = income, categoryId = cat, note = note, date = at(d),
            account = if (sms) listOf("4521", "4433", "7788", "PAYTMB").random(rnd) else null,
            source = if (sms) "sms" else "manual", smsKey = if (sms) SmsParser.fnv(UUID.randomUUID().toString()) else null,
        )
    }
    val daily = listOf(
        Triple("food", "Swiggy", 180 to 650), Triple("food", "Zomato", 220 to 780), Triple("food", "Chai Point", 60 to 160),
        Triple("food", "Starbucks", 280 to 480), Triple("groceries", "Blinkit", 150 to 1200), Triple("groceries", "BigBasket", 800 to 2600),
        Triple("transport", "Uber", 140 to 620), Triple("transport", "Rapido", 50 to 210), Triple("transport", "Namma Metro", 40 to 90),
        Triple("shopping", "Amazon", 300 to 3800), Triple("shopping", "Myntra", 900 to 2900), Triple("entertainment", "BookMyShow", 400 to 1200),
        Triple("health", "Apollo Pharmacy", 180 to 1400), Triple("other", "", 100 to 900),
    )
    for (back in 0..92) {
        val d = today.minusDays(back.toLong())
        repeat(if (back == 0) 2 else rnd.nextInt(0, 3)) {
            val (cat, note, range) = daily[rnd.nextInt(daily.size)]
            add(d, cat, note, range.first, range.second)
        }
        when (d.dayOfMonth) {
            1 -> add(d, "salary", "Acme Technologies", 85000, income = true, sms = true)
            3 -> add(d, "home", "Rent", 25000, sms = true)
            5 -> add(d, "bills", "Airtel", 599)
            8 -> add(d, "bills", "BESCOM", 900, 2100, sms = true)
            12 -> add(d, "entertainment", "Netflix", 649, sms = true)
            15 -> add(d, "transport", "Shell fuel", 1800, 3200)
            18 -> add(d, "transfer", "ATM", 2000, 5000, sms = true)
            22 -> add(d, "otherIncome", "Rahul Sharma", 500, 2500, income = true)
            26 -> add(d, "refund", "Myntra refund", 400, 1800, income = true, sms = true)
        }
    }
    return out.filter { it.date <= System.currentTimeMillis() }
}

/** Fixed ids so loading twice updates instead of duplicating. ••7788 stays unlinked to show the link flow. */
fun demoAccounts() = listOf(
    Account("demo-hdfc", "HDFC Savings", "bank", listOf("4521")),
    Account("demo-icici", "Amazon ICICI Card", "card", listOf("4433")),
    Account("demo-paytm", "Paytm Wallet", "wallet", listOf("paytmb")),
)
