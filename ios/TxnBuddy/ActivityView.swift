import SwiftUI

struct ActivityView: View {
    enum Filter: Hashable { case all, expenses, income, sms }
    @State private var query = ""
    @State private var filter = Filter.all
    @State private var categoryId: String?
    @State private var accountId: String?      // nil = all accounts, "" = not linked
    @AppStorage("hideAmounts") private var hide = false

    var body: some View {
        let store = Store.shared
        let items = filtered(store)
        NavigationStack {
            List {
                Section { filterBar(store) }
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
                if items.isEmpty {
                    Section { empty(store) }.listRowBackground(Color.clear)
                } else {
                    ForEach(groupByDay(items)) { g in
                        Section {
                            ForEach(g.txns) { t in
                                Button { Router.shared.edit(t) } label: { TxnRow(txn: t) }
                                    .buttonStyle(.plain)
                                    .listRowBackground(Color.surface)
                                    .swipeActions(edge: .trailing) {
                                        Button(role: .destructive) { Router.shared.delete(t) } label: { Label("Delete", systemImage: "trash") }
                                    }
                            }
                        } header: {
                            DayHeader(group: g)
                        }
                    }
                    Section { summary(items, store) }.listRowBackground(Color.clear)
                }
            }
            .scrollContentBackground(.hidden)
            .background(Color.bg)
            .contentMargins(.bottom, 90, for: .scrollContent)
            .navigationTitle("Activity")
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Search notes, categories, accounts")
            .overlay(alignment: .bottomTrailing) { AddButton() }
        }
    }

    private func filtered(_ store: Store) -> [Txn] {
        let q = query.trimmingCharacters(in: CharacterSet(charactersIn: " •")).lowercased()
        let account = store.accounts.isEmpty ? nil : accountId == "" ? "" : store.account(accountId)?.id
        return store.txns.filter { t in
            switch filter {
            case .all: break
            case .expenses: if t.income { return false }
            case .income: if !t.income { return false }
            case .sms: if !t.isSMS { return false }
            }
            if let categoryId, t.categoryId != categoryId { return false }
            if let account, (t.accountId ?? "") != account { return false }
            guard !q.isEmpty else { return true }
            return (t.note ?? "").lowercased().contains(q)
                || store.category(t.categoryId).name.lowercased().contains(q)
                || (store.account(t.accountId)?.name ?? "").lowercased().contains(q)
                || (t.account ?? "").lowercased().contains(q)
        }
    }

    private func filterBar(_ store: Store) -> some View {
        let options: [(Filter, String)] = [(.all, "All"), (.expenses, "Expenses"), (.income, "Income"), (.sms, "From SMS")]
        return ScrollView(.horizontal) {
            HStack(spacing: 8) {
                ForEach(options, id: \.0) { f, title in
                    Button { withAnimation(.snappy) { filter = f } } label: { Chip(title: title, selected: filter == f) }
                }
                Menu {
                    Button("All categories") { categoryId = nil }
                    Divider()
                    ForEach(store.categories) { c in
                        Button("\(c.emoji)  \(c.name)") { categoryId = c.id }
                    }
                } label: {
                    let c = categoryId.map(store.category)
                    Chip(title: c.map { "\($0.emoji) \($0.name)" } ?? "Category", selected: c != nil, trailingSymbol: "chevron.down")
                }
                if !store.accounts.isEmpty {
                    Menu {
                        Button("All accounts") { accountId = nil }
                        Divider()
                        ForEach(store.accounts) { a in
                            Button("\(a.emoji)  \(a.name)") { accountId = a.id }
                        }
                        Divider()
                        Button("Not linked") { accountId = "" }
                    } label: {
                        let title = accountId == "" ? "Not linked" : store.account(accountId).map { "\($0.emoji) \($0.name)" }
                        Chip(title: title ?? "Account", selected: title != nil, trailingSymbol: "chevron.down")
                    }
                }
            }
            .buttonStyle(PressStyle())
            .padding(.horizontal, 20)
            .padding(.vertical, 6)
        }
        .scrollIndicators(.hidden)
        .sensoryFeedback(.selection, trigger: filter)
    }

    @ViewBuilder
    private func empty(_ store: Store) -> some View {
        if store.txns.isEmpty {
            ContentUnavailableView {
                Label("No transactions yet", systemImage: "tray")
            } description: {
                Text("Tap + to add one, or import your bank SMS from Settings.")
            }
        } else {
            ContentUnavailableView {
                Label("Nothing matches", systemImage: "magnifyingglass")
            } description: {
                Text("Try a different search or filter.")
            }
        }
    }

    private func summary(_ items: [Txn], _ store: Store) -> some View {
        let spent = items.filter { !$0.income }.reduce(Int64(0)) { $0 + $1.amount }
        let received = items.filter(\.income).reduce(Int64(0)) { $0 + $1.amount }
        let code = store.currency
        let n = items.count
        return Text("\(n) \(n == 1 ? "transaction" : "transactions") · \(Money.string(spent, code: code, hidden: hide)) spent · \(Money.string(received, code: code, hidden: hide)) received")
            .font(size: 13, relativeTo: .footnote)
            .foregroundStyle(.muted)
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity)
    }
}
