import SwiftUI

struct HomeView: View {
    @AppStorage("hideAmounts") private var hide = false
    @AppStorage("shortcutTipDismissed") private var tipDismissed = false
    @AppStorage("dismissedSources") private var dismissedSources = ""   // identifiers hidden from the new-sources card, comma-separated

    var body: some View {
        let store = Store.shared
        let now = Date()
        let month = Calendar.current.dateInterval(of: .month, for: now)!
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                header(now)
                HeroCard(month: month, now: now)
                // iOS stand-in for the Android "new SMS" banner: invites the user to set up the Shortcuts automation.
                if !tipDismissed && !store.txns.contains(where: \.isSMS) {
                    TipCard(emoji: "💬", title: "Log bank SMS automatically", caption: "A one-time Shortcuts setup", button: "Set up",
                            action: { Router.shared.shortcutsGuide = true }) { withAnimation(.snappy) { tipDismissed = true } }
                }
                newSources(store)
                if store.txns.isEmpty {
                    emptyState
                } else {
                    TopCategories(month: month)
                    recent(store.txns)
                }
            }
            .padding(.horizontal, 20)
            .padding(.top, 8)
            .padding(.bottom, 110)
        }
        .scrollIndicators(.hidden)
        .background(Color.bg)
        // The empty state has its own "Add expense" button; the FAB would overlap it.
        .overlay(alignment: .bottomTrailing) { if !store.txns.isEmpty { AddButton() } }
    }

    private func header(_ now: Date) -> some View {
        HStack(alignment: .bottom) {
            VStack(alignment: .leading, spacing: 2) {
                Text(greeting(now)).font(size: 13, .medium, relativeTo: .footnote).foregroundStyle(.muted)
                Text(now.formatted(.dateTime.month(.wide)))
                    .font(size: 28, .bold, relativeTo: .largeTitle)
                    .foregroundStyle(.ink)
            }
            Spacer()
            Button { withAnimation(.snappy) { hide.toggle() } } label: {
                Image(systemName: hide ? "eye.slash.fill" : "eye.fill")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(.ink)
                    .frame(width: 44, height: 44)
                    .background(Color.surface, in: Circle())
                    .contentTransition(.symbolEffect(.replace))
            }
            .buttonStyle(PressStyle())
            .accessibilityLabel(hide ? "Show amounts" : "Hide amounts")
        }
    }

    /// SMS identifiers no account claims yet (DESIGN §4.2 3b).
    @ViewBuilder
    private func newSources(_ store: Store) -> some View {
        let dismissed = Set(dismissedSources.split(separator: ",").map(String.init))
        let ids = store.unlinkedSources.map(\.id).filter { !dismissed.contains($0) }
        if !ids.isEmpty {
            TipCard(emoji: "💳", title: ids.count == 1 ? "New account found in SMS" : "\(ids.count) new accounts found in SMS",
                    caption: "Link \(ids.prefix(3).map(Account.display).joined(separator: ", ")) to your accounts", button: "Link",
                    action: { Router.shared.accountsSheet = true }) {
                withAnimation(.snappy) { dismissedSources = (dismissed.sorted() + ids).joined(separator: ",") }
            }
        }
    }

    private func recent(_ txns: [Txn]) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionTitle(title: "Recent", action: ("See all", { Router.shared.tab = .activity }))
            ForEach(groupByDay(Array(txns.prefix(8)))) { g in
                VStack(alignment: .leading, spacing: 8) {
                    DayHeader(group: g).padding(.horizontal, 4)
                    VStack(spacing: 0) {
                        ForEach(Array(g.txns.enumerated()), id: \.element.id) { i, t in
                            if i > 0 { Divider().padding(.leading, 52) }
                            Button { Router.shared.edit(t) } label: { TxnRow(txn: t) }
                                .buttonStyle(.plain)
                                .contextMenu {
                                    Button { Router.shared.edit(t) } label: { Label("Edit", systemImage: "pencil") }
                                    Button(role: .destructive) { Router.shared.delete(t) } label: { Label("Delete", systemImage: "trash") }
                                }
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 4)
                    .background(Color.surface, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
                }
            }
        }
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            EmojiBubble(emoji: "🧾")
            Text("No transactions yet")
                .font(size: 20, .bold, relativeTo: .title3)
                .foregroundStyle(.ink)
                .padding(.top, 4)
            Text("Tap + to add one, or import from your bank SMS.")
                .font(size: 15)
                .foregroundStyle(.muted)
                .multilineTextAlignment(.center)
            HStack(spacing: 10) {
                Button { Router.shared.newTxn() } label: {
                    Text("Add expense").padding(.horizontal, 18).padding(.vertical, 11)
                        .foregroundStyle(.white).background(Color.brand, in: Capsule())
                }
                Button { Router.shared.pasteSheet = true } label: {
                    Text("Import SMS").padding(.horizontal, 18).padding(.vertical, 11)
                        .foregroundStyle(.brand).background(Color.brand.opacity(0.12), in: Capsule())
                }
            }
            .font(size: 15, .semibold)
            .buttonStyle(PressStyle())
            .padding(.top, 8)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 28)
    }
}

private struct HeroCard: View {
    let month: DateInterval
    let now: Date
    @AppStorage("hideAmounts") private var hide = false

    var body: some View {
        let store = Store.shared, cal = Calendar.current
        let spent = store.spent(in: month)
        VStack(alignment: .leading, spacing: 0) {
            Text("Spent this month")
                .font(size: 14, .medium)
                .foregroundStyle(.white.opacity(0.8))
            MoneyText(minor: spent, size: 44, weight: .bold, color: .white)
                .lineLimit(1)
                .minimumScaleFactor(0.5)
                .padding(.top, 4)
            budget(spent: spent, budget: store.monthlyBudget)
                .padding(.top, 14)
            Rectangle().fill(.white.opacity(0.15)).frame(height: 1).padding(.vertical, 16)
            HStack(alignment: .top) {
                stat("Today", store.spent(in: cal.dateInterval(of: .day, for: now)!))
                stat("This week", store.spent(in: cal.dateInterval(of: .weekOfYear, for: now)!))
                stat("Income", store.received(in: month))
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background {
            ZStack {
                heroGradient
                Circle().fill(.white.opacity(0.09)).frame(width: 240, height: 240).offset(x: 150, y: -110)
                Circle().fill(.white.opacity(0.06)).frame(width: 170, height: 170).offset(x: -150, y: 120)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
        .shadow(color: Color(hex: "#5B4CF0").opacity(0.18), radius: 16, y: 8)
    }

    @ViewBuilder
    private func budget(spent: Int64, budget: Int64) -> some View {
        let code = Store.shared.currency
        if budget > 0 {
            let ratio = Double(spent) / Double(budget)
            let cal = Calendar.current
            let daysLeft = (cal.range(of: .day, in: .month, for: now)?.count ?? 30) - cal.component(.day, from: now)
            let line = spent > budget
                ? "\(Money.string(spent - budget, code: code, hidden: hide)) over budget"
                : "\(Money.string(budget - spent, code: code, hidden: hide)) left · " + (daysLeft == 0 ? "last day" : daysLeft == 1 ? "1 day to go" : "\(daysLeft) days to go")
            Button { Router.shared.budgetEditor = true } label: {
                VStack(alignment: .leading, spacing: 8) {
                    ProgressBar(value: ratio, color: ratio > 1 ? Color(hex: "#FFB4B4") : .white, track: .white.opacity(0.25), height: 8)
                    Text(line).font(size: 13, .medium).foregroundStyle(.white.opacity(0.85))
                }
            }
            .buttonStyle(.plain)
            .accessibilityLabel(line)
            .accessibilityHint("Edit monthly budget")
        } else {
            Button { Router.shared.budgetEditor = true } label: {
                HStack(spacing: 4) {
                    Text("Set a monthly budget")
                    Image(systemName: "chevron.right").imageScale(.small)
                }
                .font(size: 13, .semibold)
                .foregroundStyle(.white)
                .padding(.horizontal, 12)
                .padding(.vertical, 7)
                .background(.white.opacity(0.18), in: Capsule())
            }
            .buttonStyle(PressStyle())
        }
    }

    private func stat(_ label: String, _ minor: Int64) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(label).font(size: 12, .medium, relativeTo: .caption).foregroundStyle(.white.opacity(0.7))
            MoneyText(minor: minor, size: 15, weight: .semibold, color: .white)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }
}

/// Dismissible prompt card on Home: Shortcuts setup, new SMS sources.
private struct TipCard: View {
    let emoji: String
    let title: String
    let caption: String
    let button: String
    let action: () -> Void
    let dismiss: () -> Void

    // Title + × on one line, caption + button on the next, so neither line has to wrap on a phone.
    var body: some View {
        HStack(spacing: 12) {
            EmojiBubble(emoji: emoji, size: 44)
            VStack(alignment: .leading, spacing: 4) {
                HStack(alignment: .top, spacing: 8) {
                    Text(title).font(size: 15, .semibold).foregroundStyle(.ink)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Button(action: dismiss) {
                        Image(systemName: "xmark")
                            .font(.system(size: 10, weight: .bold))
                            .foregroundStyle(.muted)
                            .frame(width: 22, height: 22)
                            .background(Color.surface2, in: Circle())
                            .frame(width: 40, height: 40)   // touch target, without growing the row
                            .contentShape(Rectangle())
                            .padding(-9)
                    }
                    .accessibilityLabel("Dismiss")
                }
                HStack(spacing: 8) {
                    Text(caption).font(size: 13, relativeTo: .footnote).foregroundStyle(.muted)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Button(action: action) {
                        Text(button)
                            .font(size: 14, .semibold)
                            .foregroundStyle(.white)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 7)
                            .background(Color.brand, in: Capsule())
                    }
                    .buttonStyle(PressStyle())
                }
            }
        }
        .card(padding: 14)
    }
}

private struct TopCategories: View {
    let month: DateInterval

    var body: some View {
        let store = Store.shared
        let spend = store.txns(in: month).filter { !$0.income }
        let total = max(1, spend.reduce(0) { $0 + $1.amount })
        let byCat = Dictionary(grouping: spend, by: \.categoryId)
            .map { (id: $0.key, amount: $0.value.reduce(Int64(0)) { $0 + $1.amount }) }
            .sorted { $0.amount > $1.amount }
        if !byCat.isEmpty {
            VStack(alignment: .leading, spacing: 12) {
                SectionTitle(title: "Top categories", action: ("Insights", { Router.shared.tab = .insights }))
                ScrollView(.horizontal) {
                    HStack(spacing: 12) {
                        ForEach(byCat.prefix(8), id: \.id) { item in
                            let c = store.category(item.id)
                            Button { Router.shared.tab = .insights } label: {
                                VStack(alignment: .leading, spacing: 10) {
                                    Badge(category: c)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(c.name).font(size: 13, .medium, relativeTo: .footnote).foregroundStyle(.muted).lineLimit(1)
                                        MoneyText(minor: item.amount).lineLimit(1).minimumScaleFactor(0.7)
                                    }
                                    ProgressBar(value: Double(item.amount) / Double(total), color: Color(hex: c.color), height: 4)
                                }
                                .padding(14)
                                .frame(width: 128, alignment: .leading)
                                .background(Color.surface, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                            }
                            .buttonStyle(PressStyle())
                        }
                    }
                    .padding(.horizontal, 20)
                }
                .scrollIndicators(.hidden)
                .padding(.horizontal, -20)
            }
        }
    }
}
