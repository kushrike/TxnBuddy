import SwiftUI
import Charts

enum Period: String, CaseIterable, Hashable {
    case day = "Day", week = "Week", month = "Month", year = "Year"

    var unit: Calendar.Component {
        switch self { case .day: .day; case .week: .weekOfYear; case .month: .month; case .year: .year }
    }
    var bucket: Calendar.Component {
        switch self { case .day: .hour; case .week, .month: .day; case .year: .month }
    }
}

/// Everything the Insights screen shows for one period, computed once per render.
struct PeriodStats {
    struct Bucket: Identifiable { let id: String; let axis: String; let title: String; let amount: Double; let isNow: Bool; let labeled: Bool }
    struct Slice: Identifiable { let category: Category; let amount: Int64; var id: String { category.id } }

    let interval: DateInterval
    let spent: Int64, previous: Int64, received: Int64, count: Int, days: Int
    let buckets: [Bucket]
    let slices: [Slice]
    let top: [Txn]

    @MainActor init(period: Period, offset: Int, now: Date = Date(), cal: Calendar = .current) {
        let store = Store.shared
        let anchor = cal.date(byAdding: period.unit, value: offset, to: now) ?? now
        interval = cal.dateInterval(of: period.unit, for: anchor) ?? DateInterval(start: now, duration: 86_400)
        let prevAnchor = cal.date(byAdding: period.unit, value: offset - 1, to: now) ?? now
        previous = cal.dateInterval(of: period.unit, for: prevAnchor).map(store.spent) ?? 0

        let txns = store.txns(in: interval)
        let spend = txns.filter { !$0.income }
        spent = spend.reduce(0) { $0 + $1.amount }
        received = txns.filter(\.income).reduce(0) { $0 + $1.amount }
        count = txns.count
        let end = min(now, interval.end)
        days = max(1, (cal.dateComponents([.day], from: cal.startOfDay(for: interval.start), to: end).day ?? 0) + (end == interval.end ? 0 : 1))

        var out: [Bucket] = []
        var cursor = interval.start
        let weekdays = cal.shortWeekdaySymbols, months = cal.shortMonthSymbols
        while cursor < interval.end, let next = cal.date(byAdding: period.bucket, value: 1, to: cursor) {
            let amount = spend.filter { $0.date >= cursor && $0.date < next }.reduce(0) { $0 + $1.amount }
            let id: String, axis: String, title: String, labeled: Bool
            switch period {
            case .day:
                let h = cal.component(.hour, from: cursor)
                id = "\(h)"; axis = id; labeled = h % 6 == 0
                title = cursor.formatted(date: .omitted, time: .shortened)
            case .week:
                id = weekdays[cal.component(.weekday, from: cursor) - 1]; axis = String(id.prefix(1)); labeled = true
                title = cursor.formatted(.dateTime.weekday(.wide).day().month(.abbreviated))
            case .month:
                let d = cal.component(.day, from: cursor)
                id = "\(d)"; axis = id; labeled = [1, 8, 15, 22, 29].contains(d)
                title = cursor.formatted(.dateTime.day().month(.abbreviated))
            case .year:
                id = months[cal.component(.month, from: cursor) - 1]; axis = String(id.prefix(1)); labeled = true
                title = cursor.formatted(.dateTime.month(.wide))
            }
            if !out.contains(where: { $0.id == id }) {   // DST days repeat an hour; keep the first
                out.append(Bucket(id: id, axis: axis, title: title, amount: Double(amount),
                                  isNow: now >= cursor && now < next, labeled: labeled))
            }
            cursor = next
        }
        buckets = out

        slices = Dictionary(grouping: spend, by: \.categoryId)
            .map { Slice(category: store.category($0.key), amount: $0.value.reduce(0) { $0 + $1.amount }) }
            .sorted { $0.amount > $1.amount }
        top = Array(spend.sorted { $0.amount > $1.amount }.prefix(3))
    }
}

struct InsightsView: View {
    @State private var period = Period.month
    @State private var offset = 0

    var body: some View {
        let stats = PeriodStats(period: period, offset: offset)
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    PillPicker(options: Period.allCases.map { ($0, $0.rawValue) }, selection: $period)
                    navigator(stats)
                    SummaryCard(stats: stats, period: period, offset: offset)
                    SpendingChart(stats: stats, period: period)
                    if stats.slices.isEmpty {
                        VStack(spacing: 10) {
                            EmojiBubble(emoji: "📭", size: 64)
                            Text("No spending in this period").font(size: 15, .medium).foregroundStyle(.muted)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 20)
                        .card()
                    } else {
                        CategoryDonut(stats: stats)
                        topExpenses(stats)
                    }
                }
                .padding(.horizontal, 20)
                .padding(.bottom, 28)
                .animation(.snappy, value: period)
                .animation(.snappy, value: offset)
            }
            .scrollIndicators(.hidden)
            .background(Color.bg)
            .navigationTitle("Insights")
            .onChange(of: period) { offset = 0 }
        }
    }

    private func navigator(_ stats: PeriodStats) -> some View {
        let (title, subtitle) = titles(stats.interval)
        return HStack {
            navButton("chevron.left", label: "Previous \(period.rawValue.lowercased())") { offset -= 1 }
            Spacer()
            VStack(spacing: 2) {
                Text(title).font(size: 17, .semibold, relativeTo: .headline).foregroundStyle(.ink)
                if let subtitle { Text(subtitle).font(size: 13, relativeTo: .footnote).foregroundStyle(.muted) }
            }
            .accessibilityElement(children: .combine)
            Spacer()
            navButton("chevron.right", label: "Next \(period.rawValue.lowercased())") { offset += 1 }
                .disabled(offset >= 0)
                .opacity(offset >= 0 ? 0.3 : 1)
        }
        .sensoryFeedback(.selection, trigger: offset)
    }

    private func navButton(_ symbol: String, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(.ink)
                .frame(width: 44, height: 44)
                .background(Color.surface, in: Circle())
        }
        .buttonStyle(PressStyle())
        .accessibilityLabel(label)
    }

    private func titles(_ i: DateInterval) -> (String, String?) {
        let lastDay = i.end.addingTimeInterval(-1)
        let range = (i.start..<lastDay).formatted(.interval.day().month(.abbreviated))
        switch period {
        case .day:
            let d = i.start.formatted(.dateTime.day().month(.abbreviated))
            return offset == 0 ? ("Today", d) : offset == -1 ? ("Yesterday", d)
                : (i.start.formatted(.dateTime.weekday(.wide)), i.start.formatted(.dateTime.day().month(.abbreviated).year()))
        case .week:
            return offset == 0 ? ("This week", range) : offset == -1 ? ("Last week", range) : (range, i.start.formatted(.dateTime.year()))
        case .month:
            return (i.start.formatted(.dateTime.month(.wide).year()), offset == 0 ? "This month" : nil)
        case .year:
            return (i.start.formatted(.dateTime.year()), offset == 0 ? "This year" : nil)
        }
    }

    private func topExpenses(_ stats: PeriodStats) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Top expenses").font(size: 17, .semibold, relativeTo: .headline).foregroundStyle(.ink).padding(.bottom, 4)
            ForEach(Array(stats.top.enumerated()), id: \.element.id) { i, t in
                if i > 0 { Divider().padding(.leading, 52) }
                Button { Router.shared.edit(t) } label: { TxnRow(txn: t, showDate: true) }.buttonStyle(.plain)
            }
        }
        .card()
    }
}

private struct SummaryCard: View {
    let stats: PeriodStats
    let period: Period
    let offset: Int

    var body: some View {
        let net = stats.received - stats.spent
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .center) {
                Text("Spent").font(size: 15, .medium).foregroundStyle(.muted)
                Spacer()
                delta
            }
            MoneyText(minor: stats.spent, size: 36, weight: .bold)
                .lineLimit(1)
                .minimumScaleFactor(0.5)
            HStack(alignment: .top) {
                mini("Income") { MoneyText(minor: stats.received, size: 15, color: .income) }
                mini("Net") { MoneyText(minor: abs(net), signed: net >= 0, size: 15, color: net >= 0 ? .income : .ink) }
                if period == .day {
                    mini("Transactions") { Text("\(stats.count)").font(size: 15, .semibold, rounded: true).foregroundStyle(.ink) }
                } else {
                    mini("Avg/day") { MoneyText(minor: stats.spent / Int64(stats.days), size: 15) }
                }
            }
        }
        .card()
    }

    @ViewBuilder
    private var delta: some View {
        if stats.previous > 0 {
            let pct = Int(((Double(stats.spent) - Double(stats.previous)) / Double(stats.previous) * 100).rounded())
            let up = pct > 0
            let unit = period.rawValue.lowercased()
            let vs = offset == 0 ? (period == .day ? "yesterday" : "last \(unit)") : "previous \(unit)"
            Text("\(up ? "▲" : pct < 0 ? "▼" : "•") \(abs(pct))% vs \(vs)")
                .font(size: 12, .semibold, relativeTo: .caption)
                .foregroundStyle(up ? Color.danger : Color.income)
                .padding(.horizontal, 10)
                .padding(.vertical, 5)
                .background((up ? Color.danger : Color.income).opacity(0.12), in: Capsule())
        }
    }

    private func mini(_ label: String, @ViewBuilder _ value: () -> some View) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(label).font(size: 12, .medium, relativeTo: .caption).foregroundStyle(.muted)
            value().lineLimit(1).minimumScaleFactor(0.7)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }
}

private struct SpendingChart: View {
    let stats: PeriodStats
    let period: Period
    @State private var selected: String?
    @AppStorage("hideAmounts") private var hide = false

    var body: some View {
        let code = Store.shared.currency
        let pick = stats.buckets.first { $0.id == selected }
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .firstTextBaseline) {
                Text("Spending").font(size: 17, .semibold, relativeTo: .headline).foregroundStyle(.ink)
                Spacer()
                if let pick {
                    Text("\(pick.title) · \(Money.string(Int64(pick.amount), code: code, hidden: hide))")
                        .font(size: 13, .semibold, relativeTo: .footnote)
                        .foregroundStyle(.brand)
                        .transition(.opacity)
                }
            }
            Chart(stats.buckets) { b in
                BarMark(x: .value("Time", b.id), y: .value("Spent", b.amount), width: .ratio(0.62))
                    .foregroundStyle((selected == nil ? b.isNow : b.id == selected) ? Color.brand : Color.brand.opacity(0.35))
                    .cornerRadius(period == .month || period == .day ? 3 : 6)
            }
            .chartXSelection(value: $selected)
            .chartXAxis {
                // Every bucket gets a mark (categorical axes ignore `values:` filtering); unlabeled ones render empty.
                AxisMarks { v in
                    AxisValueLabel {
                        let b = stats.buckets.first { $0.id == v.as(String.self) }
                        Text(b?.labeled == true ? b!.axis : "")
                            .font(size: 11, relativeTo: .caption2)
                            .foregroundStyle(.muted)
                    }
                }
            }
            .chartYAxis {
                AxisMarks(position: .trailing, values: .automatic(desiredCount: 3)) { v in
                    AxisGridLine(stroke: StrokeStyle(lineWidth: 0.5, dash: [3, 3])).foregroundStyle(Color.muted.opacity(0.35))
                    AxisValueLabel {
                        if !hide, let d = v.as(Double.self) {
                            Text(Money.compact(Int64(d), code: code)).font(size: 11, relativeTo: .caption2).foregroundStyle(.muted)
                        }
                    }
                }
            }
            .frame(height: 180)
            .animation(.snappy, value: selected)
            .accessibilityLabel("Spending chart")
        }
        .card()
        .sensoryFeedback(.selection, trigger: selected)
        .onChange(of: stats.interval) { selected = nil }
    }
}

private struct CategoryDonut: View {
    let stats: PeriodStats
    @State private var angle: Double?

    var body: some View {
        let total = max(1, stats.slices.reduce(Int64(0)) { $0 + $1.amount })
        let picked = slice(at: angle)
        VStack(alignment: .leading, spacing: 18) {
            Text("By category").font(size: 17, .semibold, relativeTo: .headline).foregroundStyle(.ink)
            Chart(stats.slices) { s in
                SectorMark(angle: .value("Amount", Double(s.amount)), innerRadius: .ratio(0.69), angularInset: 1.5)
                    .cornerRadius(5)
                    .foregroundStyle(Color(hex: s.category.color))
                    .opacity(picked == nil || picked?.id == s.id ? 1 : 0.3)
            }
            .chartAngleSelection(value: $angle)
            .chartLegend(.hidden)
            .chartBackground { proxy in
                GeometryReader { geo in
                    if let anchor = proxy.plotFrame {
                        let frame = geo[anchor]
                        VStack(spacing: 2) {
                            if let picked {
                                Text("\(picked.category.emoji) \(picked.category.name)").font(size: 13, .medium).foregroundStyle(.muted)
                                MoneyText(minor: picked.amount, size: 20, weight: .bold)
                                Text("\(Int((Double(picked.amount) / Double(total) * 100).rounded()))%").font(size: 12).foregroundStyle(.muted)
                            } else {
                                MoneyText(minor: stats.spent, size: 20, weight: .bold)
                                Text("\(stats.slices.count) \(stats.slices.count == 1 ? "category" : "categories")")
                                    .font(size: 12).foregroundStyle(.muted)
                            }
                        }
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                        .frame(width: frame.width * 0.6)
                        .position(x: frame.midX, y: frame.midY)
                    }
                }
            }
            .frame(width: 180, height: 180)
            .frame(maxWidth: .infinity)
            .animation(.snappy, value: picked?.id)
            .sensoryFeedback(.selection, trigger: picked?.id)

            VStack(spacing: 14) {
                ForEach(stats.slices) { s in
                    let share = Double(s.amount) / Double(total)
                    HStack(spacing: 12) {
                        Badge(category: s.category, size: 32)
                        VStack(alignment: .leading, spacing: 6) {
                            HStack(spacing: 8) {
                                Text(s.category.name).font(size: 15, .medium).foregroundStyle(.ink).lineLimit(1)
                                Spacer(minLength: 4)
                                Text("\(Int((share * 100).rounded()))%").font(size: 13, relativeTo: .footnote).foregroundStyle(.muted).monospacedDigit()
                                MoneyText(minor: s.amount, size: 15)
                            }
                            ProgressBar(value: share, color: Color(hex: s.category.color), height: 5)
                        }
                    }
                    .accessibilityElement(children: .combine)
                }
            }
        }
        .card()
        .onChange(of: stats.interval) { angle = nil }
    }

    private func slice(at angle: Double?) -> PeriodStats.Slice? {
        guard let angle else { return nil }
        var acc = 0.0
        for s in stats.slices {
            acc += Double(s.amount)
            if angle <= acc { return s }
        }
        return stats.slices.last
    }
}
