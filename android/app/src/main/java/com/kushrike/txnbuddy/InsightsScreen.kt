package com.kushrike.txnbuddy

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JTextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

enum class Period(val label: String, val prevLabel: String) {
    Day("Day", "yesterday"), Week("Week", "last week"), Month("Month", "last month"), Year("Year", "last year");

    fun vs(offset: Int) = if (offset == 0) prevLabel else "previous ${name.lowercase()}"
}

data class Span(val start: LocalDate, val end: LocalDate) { // end exclusive
    val days get() = ChronoUnit.DAYS.between(start, end).toInt()
    operator fun contains(d: LocalDate) = !d.isBefore(start) && d.isBefore(end)
}

fun span(p: Period, offset: Int, today: LocalDate = LocalDate.now()): Span = when (p) {
    Period.Day -> today.plusDays(offset.toLong()).let { Span(it, it.plusDays(1)) }
    Period.Week -> weekStart(today).plusWeeks(offset.toLong()).let { Span(it, it.plusWeeks(1)) }
    Period.Month -> today.withDayOfMonth(1).plusMonths(offset.toLong()).let { Span(it, it.plusMonths(1)) }
    Period.Year -> today.withDayOfYear(1).plusYears(offset.toLong()).let { Span(it, it.plusYears(1)) }
}

private fun bucketOf(p: Period, s: Span, t: LocalDateTime): Int = when (p) {
    Period.Day -> t.hour
    Period.Week -> ChronoUnit.DAYS.between(s.start, t.toLocalDate()).toInt()
    Period.Month -> t.dayOfMonth - 1
    Period.Year -> t.monthValue - 1
}

private fun bucketCount(p: Period, s: Span) = when (p) {
    Period.Day -> 24; Period.Week -> 7; Period.Month -> s.start.lengthOfMonth(); Period.Year -> 12
}

private val shortDay = DateTimeFormatter.ofPattern("d MMM")

private fun title(p: Period, o: Int, s: Span): Pair<String, String?> {
    val last = s.end.minusDays(1)
    val range = if (s.start.month == last.month) "${s.start.dayOfMonth} – ${last.format(shortDay)}" else "${s.start.format(shortDay)} – ${last.format(shortDay)}"
    return when (p) {
        Period.Day -> (if (o == 0) "Today" else if (o == -1) "Yesterday" else s.start.format(DateTimeFormatter.ofPattern("EEE, d MMM"))) to
            s.start.format(DateTimeFormatter.ofPattern("d MMMM yyyy"))
        Period.Week -> (if (o == 0) "This week" else if (o == -1) "Last week" else range) to (if (o >= -1) range else null)
        Period.Month -> s.start.format(DateTimeFormatter.ofPattern("MMMM yyyy")) to null
        Period.Year -> s.start.year.toString() to null
    }
}

@Composable
fun InsightsScreen() {
    var period by rememberSaveable { mutableIntStateOf(1) }
    var offset by rememberSaveable { mutableIntStateOf(0) }
    val p = Period.entries[period]
    val txns = Store.txns
    val today = LocalDate.now()
    val s = span(p, offset, today)
    val inSpan = remember(txns, p, offset) { txns.filter { it.date.toLocal().toLocalDate() in s } }
    val prevSpan = span(p, offset - 1, today)
    val prevSpent = remember(txns, p, offset) { spent(txns, prevSpan.start, prevSpan.end) }
    val expenses = inSpan.filter { !it.income }
    val spentTotal = expenses.sumOf { it.amount }
    val income = inSpan.sumOf { if (it.income) it.amount else 0 }
    val elapsed = if (today in s) ChronoUnit.DAYS.between(s.start, today).toInt() + 1 else s.days
    val hide = Store.hideAmounts

    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.statusBarsPadding().padding(top = 12.dp)) {
                Text("Insights", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = P.text)
                Spacer(Modifier.height(14.dp))
                Segmented(Period.entries.map { it.label }, period, Modifier.fillMaxWidth()) { period = it; offset = 0 }
                Spacer(Modifier.height(6.dp))
                val (t, sub) = title(p, offset, s)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { offset-- }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous", tint = P.text) }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(t, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = P.text)
                        if (sub != null) Text(sub, fontSize = 12.sp, color = P.muted)
                    }
                    IconButton(onClick = { offset++ }, enabled = offset < 0) {
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next", tint = if (offset < 0) P.text else P.muted.copy(alpha = .35f))
                    }
                }
            }
        }

        item {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Spent", fontSize = 14.sp, color = P.muted, modifier = Modifier.weight(1f))
                    if (prevSpent > 0 && spentTotal != prevSpent) {
                        val pct = abs(spentTotal - prevSpent) * 100 / prevSpent
                        val up = spentTotal > prevSpent
                        val c = if (up) P.danger else P.income
                        Text(
                            "${if (up) "▲" else "▼"} $pct% vs ${p.vs(offset)}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c,
                            modifier = Modifier.clip(CircleShape).background(c.copy(alpha = .12f)).padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
                Text(amt(spentTotal, hide), style = num(36.sp), color = P.text, maxLines = 1)
                Spacer(Modifier.height(16.dp))
                Row {
                    Stat("Income", amt(income, hide), P.income, Modifier.weight(1f))
                    Stat("Net", (if (income >= spentTotal) "+" else "−") + amt(abs(income - spentTotal), hide), P.text, Modifier.weight(1f))
                    Stat("Avg/day", amt(spentTotal / elapsed.coerceAtLeast(1), hide), P.text, Modifier.weight(1f))
                }
            }
        }

        if (spentTotal == 0L) {
            item { Card { EmptyState("🌱", "No spending in this period", "Nice. Or add something with +.") } }
        } else {
            item {
                val n = bucketCount(p, s)
                val values = remember(inSpan, p) {
                    LongArray(n).also { a -> expenses.forEach { t -> a[bucketOf(p, s, t.date.toLocal()).coerceIn(0, n - 1)] += t.amount } }
                }
                val current = if (today in s) bucketOf(p, s, LocalDateTime.now()) else -1
                val labels = when (p) {
                    Period.Day -> listOf(0 to "0", 6 to "6", 12 to "12", 18 to "18")
                    Period.Week -> (0 until 7).map { it to s.start.plusDays(it.toLong()).dayOfWeek.getDisplayName(JTextStyle.NARROW, Locale.getDefault()) }
                    Period.Month -> listOf(0, 7, 14, 21, 28).filter { it < n }.map { it to "${it + 1}" }
                    Period.Year -> (0 until 12).map { it to s.start.plusMonths(it.toLong()).month.getDisplayName(JTextStyle.NARROW, Locale.getDefault()) }
                }
                fun bucketName(i: Int) = when (p) {
                    Period.Day -> "%02d:00".format(i)
                    Period.Week -> s.start.plusDays(i.toLong()).format(DateTimeFormatter.ofPattern("EEE d MMM"))
                    Period.Month -> s.start.plusDays(i.toLong()).format(shortDay)
                    Period.Year -> s.start.plusMonths(i.toLong()).format(DateTimeFormatter.ofPattern("MMMM"))
                }
                Card {
                    Text("Spending", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = P.text)
                    Spacer(Modifier.height(4.dp))
                    BarChart(values, current, labels) { i -> "${bucketName(i)} · ${amt(values[i], hide)}" }
                }
            }
            item {
                val byCat = remember(inSpan) {
                    expenses.groupBy { it.categoryId }.map { (id, l) -> Store.category(id) to l.sumOf { it.amount } }.sortedByDescending { it.second }
                }
                Card {
                    Text("By category", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = P.text)
                    Spacer(Modifier.height(16.dp))
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Donut(byCat.map { it.first.tint to it.second }, amt(spentTotal, hide), "${byCat.size} ${if (byCat.size == 1) "category" else "categories"}")
                    }
                    Spacer(Modifier.height(16.dp))
                    byCat.forEach { (c, v) ->
                        val share = v.toFloat() / spentTotal
                        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Badge(c, 32.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(c.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = P.text, modifier = Modifier.weight(1f))
                                    Text("${(share * 100).toInt()}%", fontSize = 13.sp, color = P.muted)
                                    Spacer(Modifier.width(12.dp))
                                    Text(amt(v, hide), style = num(15.sp, FontWeight.SemiBold), color = P.text)
                                }
                                Spacer(Modifier.height(6.dp))
                                Progress(share, c.tint, c.tint.copy(alpha = .14f), 4.dp)
                            }
                        }
                    }
                }
            }
            item {
                Card(padding = PaddingValues(top = 20.dp, bottom = 8.dp)) {
                    Text("Top expenses", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = P.text, modifier = Modifier.padding(horizontal = 20.dp))
                    Spacer(Modifier.height(6.dp))
                    expenses.sortedByDescending { it.amount }.take(3).forEach { TxnRow(it) }
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier) {
        Text(label, fontSize = 12.sp, color = P.muted)
        Text(value, style = num(15.sp, FontWeight.SemiBold), color = color, maxLines = 1)
    }
}

/** Bars per bucket; current bucket solid, others 35%. Tap or drag to inspect a bar. */
@Composable
fun BarChart(values: LongArray, current: Int, labels: List<Pair<Int, String>>, describe: (Int) -> String) {
    val n = values.size
    var selected by remember(values) { mutableIntStateOf(-1) }
    val grow = remember(values) { Animatable(0f) }
    LaunchedEffect(values) { grow.animateTo(1f, tween(450)) }
    val max = values.max().coerceAtLeast(1)
    val accent = P.accent
    val faint = P.accent.copy(alpha = .35f)
    val empty = P.surface2
    val grid = P.muted.copy(alpha = .25f)
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 11.sp, color = P.muted)
    val hide = Store.hideAmounts

    Text(
        if (selected >= 0) describe(selected) else "Tap a bar for details",
        fontSize = 13.sp, color = if (selected >= 0) P.text else P.muted, fontWeight = if (selected >= 0) FontWeight.Medium else FontWeight.Normal,
    )
    Spacer(Modifier.height(12.dp))
    Canvas(
        Modifier.fillMaxWidth().height(150.dp)
            .pointerInput(values) { detectTapGestures { o -> selected = (o.x / (size.width / n)).toInt().coerceIn(0, n - 1) } }
            .pointerInput(values) {
                detectHorizontalDragGestures { change, _ -> selected = (change.position.x / (size.width / n)).toInt().coerceIn(0, n - 1) }
            },
    ) {
        val top = 18.dp.toPx()
        val h = size.height - top
        // Max gridline with a compact label.
        val y = top
        drawLine(grid, Offset(0f, y), Offset(size.width, y), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        if (!hide) drawText(measurer, compact(max), Offset(0f, 0f), labelStyle)
        val slot = size.width / n
        val bw = slot * if (n > 20) 0.62f else 0.5f
        val r = minOf(bw / 2, 6.dp.toPx())
        values.forEachIndexed { i, v ->
            val x = i * slot + (slot - bw) / 2
            if (v == 0L) {
                drawRoundRect(empty, Offset(x, size.height - 3.dp.toPx()), Size(bw, 3.dp.toPx()), CornerRadius(r, r))
            } else {
                val bh = maxOf(h * v / max * grow.value, 4.dp.toPx())
                val color = if (i == selected || (selected < 0 && i == current)) accent else faint
                val path = Path().apply {
                    addRoundRect(RoundRect(x, size.height - bh, x + bw, size.height, topLeftCornerRadius = CornerRadius(r, r), topRightCornerRadius = CornerRadius(r, r)))
                }
                drawPath(path, color)
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth()) {
        val map = labels.toMap()
        for (i in 0 until n) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                map[i]?.let { Text(it, fontSize = 11.sp, color = if (i == current) P.accent else P.muted, softWrap = false, modifier = Modifier.wrapContentWidth(unbounded = true)) }
            }
        }
    }
}

/** Donut with 2° gaps; centre shows the total. */
@Composable
fun Donut(slices: List<Pair<Color, Long>>, centerTop: String, centerBottom: String) {
    val sweep = remember(slices) { Animatable(0f) }
    LaunchedEffect(slices) { sweep.animateTo(1f, tween(600)) }
    val total = slices.sumOf { it.second }.coerceAtLeast(1).toFloat()
    Box(Modifier.size(180.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 28.dp.toPx()
            val gap = if (slices.size > 1) 2f else 0f
            var start = -90f
            slices.forEach { (c, v) ->
                val a = 360f * v / total * sweep.value
                if (a > gap) {
                    drawArc(
                        c, start + gap / 2, a - gap, useCenter = false,
                        topLeft = Offset(stroke / 2, stroke / 2), size = Size(size.width - stroke, size.height - stroke), style = Stroke(stroke),
                    )
                }
                start += a
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(centerTop, style = num(20.sp), color = P.text, maxLines = 1)
            Text(centerBottom, fontSize = 12.sp, color = P.muted)
        }
    }
}
