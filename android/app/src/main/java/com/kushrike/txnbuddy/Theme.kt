package com.kushrike.txnbuddy

import android.icu.text.CompactDecimalFormat
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Locale

// ---------------------------------------------------------------- tokens (DESIGN §3)

@Immutable
data class Pal(
    val bg: Color, val surface: Color, val surface2: Color, val text: Color, val muted: Color,
    val accent: Color, val accentSoft: Color, val income: Color, val danger: Color, val dark: Boolean,
)

private val LightPal = Pal(
    bg = Color(0xFFF5F5F8), surface = Color.White, surface2 = Color(0xFFEDEDF2), text = Color(0xFF111116),
    muted = Color(0xFF6B6B78), accent = Color(0xFF5B4CF0), accentSoft = Color(0xFF5B4CF0).copy(alpha = .12f),
    income = Color(0xFF12A26B), danger = Color(0xFFE5484D), dark = false,
)
private val DarkPal = Pal(
    bg = Color(0xFF0B0B0F), surface = Color(0xFF17171D), surface2 = Color(0xFF23232B), text = Color(0xFFF2F2F7),
    muted = Color(0xFF9A9AA8), accent = Color(0xFF8A7DFF), accentSoft = Color(0xFF8A7DFF).copy(alpha = .18f),
    income = Color(0xFF34D399), danger = Color(0xFFFF6B6B), dark = true,
)
val HeroBrush = Brush.linearGradient(listOf(Color(0xFF5B4CF0), Color(0xFF8B5CF6)))

private val LocalPal = staticCompositionLocalOf { LightPal }
val P: Pal @Composable get() = LocalPal.current

@Composable
fun TxnTheme(content: @Composable () -> Unit) {
    val p = if (isSystemInDarkTheme()) DarkPal else LightPal
    val scheme = (if (p.dark) darkColorScheme() else lightColorScheme()).copy(
        primary = p.accent, onPrimary = Color.White, background = p.bg, onBackground = p.text,
        surface = p.surface, onSurface = p.text, surfaceVariant = p.surface2, onSurfaceVariant = p.muted,
        surfaceContainer = p.surface, surfaceContainerHigh = p.surface, surfaceContainerLow = p.surface,
        surfaceContainerHighest = p.surface2, secondaryContainer = p.accentSoft, onSecondaryContainer = p.text,
        primaryContainer = p.accentSoft, onPrimaryContainer = p.accent, error = p.danger, outline = p.muted.copy(alpha = .4f),
        outlineVariant = p.surface2,
    )
    MaterialTheme(colorScheme = scheme) { CompositionLocalProvider(LocalPal provides p, content = content) }
}

/** Tabular figures so amounts don't jiggle. */
fun num(size: TextUnit, weight: FontWeight = FontWeight.Bold) =
    TextStyle(fontSize = size, fontWeight = weight, fontFeatureSettings = "tnum", letterSpacing = if (size >= 30.sp) (-0.5).sp else 0.sp)

// ---------------------------------------------------------------- money & dates

private val formats = HashMap<String, NumberFormat>()

fun money(minor: Long, code: String = Store.currency): String {
    val frac = minor % 100 != 0L
    val f = formats.getOrPut("$code$frac") {
        NumberFormat.getCurrencyInstance(Locale.getDefault()).apply {
            currency = Currency.getInstance(code)
            minimumFractionDigits = if (frac) 2 else 0
            maximumFractionDigits = if (frac) 2 else 0
        }
    }
    return f.format(BigDecimal.valueOf(minor, 2))
}

fun symbol(code: String = Store.currency): String = Currency.getInstance(code).getSymbol(Locale.getDefault())

/** Formats or masks an amount depending on "Hide amounts". */
fun amt(minor: Long, hide: Boolean = Store.hideAmounts) = if (hide) symbol() + "••••" else money(minor)

fun signed(t: Txn, hide: Boolean = Store.hideAmounts) = (if (t.income) "+" else "−") + amt(t.amount, hide)

fun compact(minor: Long): String =
    symbol() + CompactDecimalFormat.getInstance(Locale.getDefault(), CompactDecimalFormat.CompactStyle.SHORT).format(minor / 100.0)

val zone: ZoneId get() = ZoneId.systemDefault()
fun Long.toLocal(): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(this), zone)
fun LocalDate.millis(): Long = atStartOfDay(zone).toInstant().toEpochMilli()

private val timeFmt = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
private val dayFmt = DateTimeFormatter.ofPattern("EEE, d MMM")
fun timeLabel(ms: Long): String = ms.toLocal().format(timeFmt)

fun dayLabel(d: LocalDate): String {
    val today = LocalDate.now()
    return when (d) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> d.format(dayFmt)
    }
}

// ---------------------------------------------------------------- components

@Composable
fun AppIcon(size: Dp) {
    // Launcher layers are 108dp with the visible icon in the centre 72dp, hence 1.5×. Corner = icon.svg rx 30/128.
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.234f)), contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.requiredSize(size * 1.5f))
        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.requiredSize(size * 1.5f))
    }
}

@Composable
fun Badge(c: Category, size: Dp = 40.dp, ring: Boolean = false) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(c.tint.copy(alpha = if (P.dark) .24f else .16f))
            .then(if (ring) Modifier.border(2.dp, c.tint, CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) { Text(c.emoji, fontSize = (size.value * 0.46f).sp, textAlign = TextAlign.Center) }
}

@Composable
fun EmojiBadge(emoji: String, size: Dp = 40.dp, bg: Color = P.accentSoft) {
    Box(Modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Text(emoji, fontSize = (size.value * 0.46f).sp)
    }
}

@Composable
fun Card(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(20.dp),
    color: Color = P.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(color).padding(padding),
        content = content,
    )
}

@Composable
fun SectionTitle(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 24.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = P.text, modifier = Modifier.weight(1f))
        if (action != null) {
            Text(
                "$action ›", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = P.accent,
                modifier = Modifier.clip(CircleShape).clickable(onClick = onAction).padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/** Capsule segmented control (accent fill on the selected item). */
@Composable
fun Segmented(options: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    Row(modifier.clip(CircleShape).background(P.surface2).padding(4.dp)) {
        options.forEachIndexed { i, label ->
            val sel = i == selected
            val bg by animateColorAsState(if (sel) P.accent else Color.Transparent, label = "seg")
            Box(
                Modifier.weight(1f).clip(CircleShape).background(bg).clickable { onSelect(i) }.padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (sel) Color.White else P.muted, maxLines = 1)
            }
        }
    }
}

@Composable
fun Chip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(36.dp)
            .clip(CircleShape)
            .background(if (selected) P.accent else P.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (selected) Color.White else P.text, maxLines = 1)
    }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(CircleShape)
            .background(if (enabled) P.accent else P.accent.copy(alpha = .4f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun Progress(fraction: Float, color: Color, track: Color, height: Dp = 6.dp, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(height).clip(CircleShape).background(track)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(height).clip(CircleShape).background(color))
    }
}

/** Account name if linked, else the SMS identifier (`••4521`). */
fun accountLabel(t: Txn): String? = Store.account(t.accountId)?.name ?: t.account?.let(::idLabel)

/** The one transaction row used everywhere (DESIGN §4.2). */
@Composable
fun TxnRow(t: Txn, modifier: Modifier = Modifier, onClick: () -> Unit = { Nav.editor = Editor(t) }) {
    val c = Store.category(t.categoryId)
    val title = t.note.ifBlank { c.name }
    val sub = listOfNotNull(
        c.name.takeIf { t.note.isNotBlank() }, timeLabel(t.date), "SMS".takeIf { t.source == "sms" },
    ).joinToString(" · ")
    Row(
        modifier.fillMaxWidth().background(P.surface).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Badge(c, 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = P.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, fontSize = 13.sp, color = P.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        AmountAndAccount(t)
    }
}

/** Trailing column of a row: amount, with the account under it so neither gets truncated. */
@Composable
fun AmountAndAccount(t: Txn) {
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(signed(t), style = num(16.sp, FontWeight.SemiBold), color = if (t.income) P.income else P.text)
        accountLabel(t)?.let { a ->
            Text(if (a.length > 18) a.take(17) + "…" else a, fontSize = 13.sp, color = P.muted, maxLines = 1)
        }
    }
}

/** Row with swipe-left-to-delete (Undo via snackbar). */
@Composable
fun SwipeRow(t: Txn) {
    val state = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        onDismiss = { if (it == SwipeToDismissBoxValue.EndToStart) deleteWithUndo(t) },
        backgroundContent = {
            Box(Modifier.fillMaxWidth().height(62.dp).background(P.danger).padding(end = 24.dp), contentAlignment = Alignment.CenterEnd) {
                Icon(Icons.Rounded.Delete, "Delete", tint = Color.White)
            }
        },
    ) { TxnRow(t) }
}

@Composable
fun DayHeader(day: LocalDate, net: Long) {
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(dayLabel(day), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = P.muted, modifier = Modifier.weight(1f))
        if (net != 0L) {
            Text(
                (if (net > 0) "+" else "−") + amt(kotlin.math.abs(net)), style = num(13.sp, FontWeight.SemiBold),
                color = if (net > 0) P.income else P.muted,
            )
        }
    }
}

/** Groups newest-first transactions by local day: (day, net, items). */
fun byDay(list: List<Txn>): List<Triple<LocalDate, Long, List<Txn>>> =
    list.groupBy { it.date.toLocal().toLocalDate() }.map { (d, items) ->
        Triple(d, items.sumOf { if (it.income) it.amount else -it.amount }, items)
    }

@Composable
fun EmptyState(emoji: String, title: String, body: String, content: @Composable ColumnScope.() -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        EmojiBadge(emoji, 88.dp)
        Spacer(Modifier.height(16.dp))
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = P.text)
        Spacer(Modifier.height(6.dp))
        Text(body, fontSize = 14.sp, color = P.muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        content()
    }
}
