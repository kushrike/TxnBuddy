import SwiftUI

/// The most-used screen: amount → category → save. Two taps when the default category is right.
struct AddSheet: View {
    let editor: Router.Editor
    @Environment(\.dismiss) private var dismiss
    @AppStorage("lastExpenseCat") private var lastExpense = "food"
    @AppStorage("lastIncomeCat") private var lastIncome = "salary"
    @AppStorage("lastAccountId") private var lastAccountId = ""
    @State private var income: Bool
    @State private var digits: String
    @State private var categoryId: String?
    @State private var note: String
    @State private var date: Date
    @State private var accountId: String?
    @State private var newAccount: Account?
    @State private var showDatePicker = false
    @State private var shakes = 0
    @State private var taps = 0
    @State private var saves = 0
    @State private var tapped: Bool                   // a category was tapped in this sheet: stop suggesting
    @State private var models: [Bool: Learn.Bayes] = [:]
    @FocusState private var noteFocused: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    init(editor: Router.Editor) {
        self.editor = editor
        let t = editor.txn
        _income = State(initialValue: t?.income ?? editor.income)
        _digits = State(initialValue: t.map { Keypad.digits(fromMinor: $0.amount) } ?? "")
        _categoryId = State(initialValue: t?.categoryId)
        _tapped = State(initialValue: t != nil)
        _note = State(initialValue: t?.note ?? "")
        _date = State(initialValue: t?.date ?? Date())
        // Editing keeps the transaction's own account; new entries start on the last one picked.
        _accountId = State(initialValue: t == nil ? UserDefaults.standard.string(forKey: "lastAccountId") : t?.accountId)
    }

    private var store: Store { .shared }
    private var isEditing: Bool { editor.txn != nil }

    /// Explicit choice → else last used of this kind → else first of this kind.
    private var selectedId: String {
        let cats = store.categories(income: income)
        if let categoryId, cats.contains(where: { $0.id == categoryId }) { return categoryId }
        let last = income ? lastIncome : lastExpense
        return cats.contains { $0.id == last } ? last : (cats.first?.id ?? "other")
    }

    var body: some View {
        VStack(spacing: 0) {
            topBar
                .padding(.horizontal, 20)
                .padding(.top, 12)
            ScrollView {
                VStack(spacing: 14) {
                    VStack(spacing: 6) {
                        AmountDisplay(digits: digits, color: income ? .income : .ink)
                            .modifier(Shake(animatableData: CGFloat(shakes)))
                        accountMenu
                    }
                    CategoryPager(categories: store.categories(income: income), selected: selectedId) { categoryId = $0; tapped = true }
                    HStack(spacing: 8) {
                        noteField
                        dateMenu
                    }
                }
                .padding(.horizontal, 20)
                .padding(.bottom, 8)
            }
            .scrollIndicators(.hidden)
            .scrollBounceBehavior(.basedOnSize)
            .scrollDismissesKeyboard(.interactively)
            VStack(spacing: 12) {
                if !noteFocused {
                    Keypad(digits: $digits, taps: $taps)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }
                Button(isEditing ? "Update" : income ? "Save income" : "Save expense", action: save)
                    .buttonStyle(PrimaryButtonStyle())
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 10)
            .animation(.snappy, value: noteFocused)
        }
        .presentationBackground(Color.bg)
        .presentationDragIndicator(.visible)
        .presentationCornerRadius(32)
        .sensoryFeedback(.impact(weight: .light), trigger: taps)
        .sensoryFeedback(.selection, trigger: selectedId)
        .sensoryFeedback(.error, trigger: shakes)
        .sensoryFeedback(.success, trigger: saves)
        .sheet(isPresented: $showDatePicker) { DatePickerSheet(date: $date) }
        .sheet(item: $newAccount) {
            AccountEditor(account: $0) { a in accountId = a.id; lastAccountId = a.id }
        }
    }

    private var topBar: some View {
        HStack {
            Button("Cancel") { dismiss() }
                .font(size: 16, .medium)
                .foregroundStyle(.muted)
                .frame(width: 70, alignment: .leading)
            Spacer()
            PillPicker(options: [(false, "Expense"), (true, "Income")], selection: $income)
                .frame(maxWidth: 220)
                .onChange(of: income) { categoryId = nil }
            Spacer()
            Group {
                if let t = editor.txn {
                    Button { dismiss(); Router.shared.delete(t) } label: {
                        Image(systemName: "trash").font(.system(size: 17, weight: .medium)).foregroundStyle(.danger)
                            .frame(width: 44, height: 44)
                    }
                    .accessibilityLabel("Delete transaction")
                } else {
                    Color.clear.frame(width: 44, height: 44)
                }
            }
            .frame(width: 70, alignment: .trailing)
        }
    }

    private var noteField: some View {
        HStack(spacing: 10) {
            Image(systemName: "text.cursor").foregroundStyle(.muted).accessibilityHidden(true)
            TextField("Add a note", text: $note)
                .font(size: 16)
                .focused($noteFocused)
                .submitLabel(.done)
                .onSubmit { noteFocused = false }
                .onChange(of: note) { if !tapped { suggest() } }
            if noteFocused {
                Button("Done") { noteFocused = false }.font(size: 15, .semibold).foregroundStyle(.brand)
            }
        }
        .padding(.horizontal, 14)
        .frame(minHeight: 48)
        .background(Color.surface2, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    /// Account chip under the amount (DESIGN §4.3 2b). An SMS identifier no account knows yet reads "••4521 · Link".
    private var accountMenu: some View {
        let picked = store.account(accountId)
        let source = editor.txn?.account
        let unlinked = picked == nil ? source.flatMap { s in store.accounts.contains { $0.ids.contains(s.lowercased()) } ? nil : s } : nil
        return Menu {
            Picker("Account", selection: Binding(get: { picked?.id }, set: { accountId = $0; lastAccountId = $0 ?? "" })) {
                Text("No account").tag(String?.none)
                ForEach(store.accounts) { a in Text("\(a.emoji)  \(a.name)").tag(String?.some(a.id)) }
            }
            .pickerStyle(.inline)
            Divider()
            Button("New account…", systemImage: "plus") {
                newAccount = unlinked.map { Account(name: "", kind: Account.isDigits($0) ? "card" : "wallet", ids: [$0.lowercased()]) }
                    ?? Account(name: "")
            }
        } label: {
            HStack(spacing: 4) {
                if let picked {
                    Text(picked.emoji)
                    Text(picked.name.count > 22 ? String(picked.name.prefix(21)) + "…" : picked.name)   // ~160pt max
                } else if let unlinked {
                    Text("\(Account.display(unlinked)) · ").foregroundStyle(.muted)
                    Text("Link")
                } else {
                    Image(systemName: "plus").imageScale(.small)
                    Text("Account")
                }
                if picked != nil || unlinked != nil { Image(systemName: "chevron.down").imageScale(.small) }
            }
            .font(size: 14, .medium)
            .lineLimit(1)
            .foregroundStyle(picked == nil && unlinked == nil ? Color.muted : Color.brand)
            .padding(.horizontal, 14)
            .frame(height: 32)
            .background(picked == nil ? Color.surface2 : Color.brand.opacity(0.12), in: Capsule())
        }
        .accessibilityLabel("Account, \(picked?.name ?? unlinked.map(Account.display) ?? "none")")
    }

    /// One compact chip instead of a chip row, so note + date share a line and the keypad never covers them.
    private var dateMenu: some View {
        let cal = Calendar.current
        let title = cal.isDateInToday(date) ? "Today"
            : cal.isDateInYesterday(date) ? "Yesterday"
            : date.formatted(.dateTime.day().month(.abbreviated))
        return Menu {
            Button("Today", systemImage: "sun.max") { date = Date() }
            Button("Yesterday", systemImage: "moon") { date = cal.date(byAdding: .day, value: -1, to: Date()) ?? Date() }
            Button("Pick date…", systemImage: "calendar") { showDatePicker = true }
        } label: {
            HStack(spacing: 6) {
                Image(systemName: "calendar").font(.system(size: 15, weight: .semibold))
                Text(title).font(size: 15, .semibold).lineLimit(1)
            }
            .foregroundStyle(.brand)
            .padding(.horizontal, 14)
            .frame(minHeight: 48)
            .background(Color.brand.opacity(0.12), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
        .fixedSize()
        .accessibilityLabel("Date, \(title)")
    }

    /// New entries: until a category is tapped, the note picks one from what TxnBuddy learned (DESIGN §5).
    private func suggest() {
        let model = models[income] ?? store.bayes(income: income)
        models[income] = model
        let keyword = SMSParser.category(lower: note.lowercased(), merchant: nil, income: income, viaVPA: false)
        if let id = store.pick(note: note, income: income, keyword: keyword, bayes: model) { categoryId = id }
    }

    /// "Swiggy → Groceries from now on", offering to move the other transactions from the same merchant too.
    private func showLearned(_ note: String, _ id: String) {
        guard let k = Learn.mkey(note) else { return }
        let others = Set(store.txns.filter { $0.income == income && $0.categoryId != id && Learn.covers(k, $0.note) }.map(\.id))
        let text = "\(note.prefix(24)) → \(store.category(id).name) from now on"
        if others.isEmpty { Router.shared.show(text) } else {
            Router.shared.show(text, action: "Apply to \(others.count) more") { Store.shared.recategorize(others, to: id) }
        }
    }

    private func save() {
        guard let minor = Keypad.minor(digits), minor > 0 else {
            withAnimation(reduceMotion ? nil : .linear(duration: 0.4)) { shakes += 1 }
            return
        }
        let id = selectedId
        let trimmed = note.trimmingCharacters(in: .whitespacesAndNewlines)
        var t = editor.txn ?? Txn(amount: minor, income: income, categoryId: id, note: nil, date: date)
        t.amount = minor
        t.income = income
        t.categoryId = id
        t.note = trimmed.isEmpty ? nil : trimmed
        t.date = date
        t.accountId = store.account(accountId)?.id
        store.upsert(t)
        store.learn(t.note, id)
        // An SMS identifier no account has yet gets linked to the picked account, so its other messages follow.
        if let a = store.account(t.accountId), a.kind != "cash", let src = t.account,
           !store.accounts.contains(where: { $0.ids.contains(src.lowercased()) }) {
            store.link(src, to: a.id)
            Router.shared.show("Linked \(Account.display(src)) to \(a.name)")
        } else if let old = editor.txn, old.categoryId != id, let n = t.note {
            showLearned(n, id)
        }
        if income { lastIncome = id } else { lastExpense = id }
        saves += 1
        dismiss()
    }
}

/// Big centred amount: muted currency symbol + digits, grouped per locale.
struct AmountDisplay: View {
    let digits: String
    var color: Color = .ink

    var body: some View {
        let text = Keypad.display(digits)
        HStack(alignment: .firstTextBaseline, spacing: 4) {
            Text(Money.symbol(Store.shared.currency))
                .font(size: 28, .semibold, rounded: true)
                .foregroundStyle(.muted)
            Text(text)
                .font(size: 56, .bold, rounded: true, relativeTo: .largeTitle)
                .monospacedDigit()
                .foregroundStyle(digits.isEmpty ? .muted : color)
                .contentTransition(.numericText())
                .animation(.snappy(duration: 0.2), value: digits)
        }
        .lineLimit(1)
        .minimumScaleFactor(0.4)
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Amount \(text)")
    }
}

/// Category grid: pages of 4 × 2, swiped horizontally, reading order left-to-right.
private struct CategoryPager: View {
    let categories: [Category]
    let selected: String
    let pick: (String) -> Void
    @State private var page: Int? = 0

    var body: some View {
        let pages = stride(from: 0, to: categories.count, by: 8).map { Array(categories[$0..<min($0 + 8, categories.count)]) }
        VStack(spacing: 10) {
            ScrollView(.horizontal) {
                LazyHStack(spacing: 0) {
                    ForEach(pages.indices, id: \.self) { i in
                        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 4), alignment: .center, spacing: 14) {
                            ForEach(pages[i]) { c in cell(c) }
                        }
                        .frame(maxHeight: .infinity, alignment: .top)
                        .containerRelativeFrame(.horizontal)
                        .id(i)
                    }
                }
                .scrollTargetLayout()
            }
            .scrollTargetBehavior(.paging)
            .scrollPosition(id: $page)
            .scrollIndicators(.hidden)
            .frame(height: categories.count > 4 ? 186 : 90)
            if pages.count > 1 {
                HStack(spacing: 6) {
                    ForEach(pages.indices, id: \.self) { i in
                        Capsule().fill(i == (page ?? 0) ? Color.brand : Color.muted.opacity(0.3))
                            .frame(width: i == (page ?? 0) ? 16 : 6, height: 6)
                    }
                }
                .animation(.snappy, value: page)
                .accessibilityHidden(true)
            }
        }
        .onChange(of: categories.map(\.id)) { page = 0 }
        .onChange(of: selected, initial: true) { showSelected() }
    }

    /// Keeps a suggested (or edited) category on screen when it lives on another page.
    private func showSelected() {
        guard let i = categories.firstIndex(where: { $0.id == selected }), i / 8 != (page ?? 0) else { return }
        withAnimation(.snappy) { page = i / 8 }
    }

    private func cell(_ c: Category) -> some View {
        let on = c.id == selected
        return Button { withAnimation(.snappy(duration: 0.2)) { pick(c.id) } } label: {
            VStack(spacing: 6) {
                Badge(category: c, size: 52)
                    .overlay(Circle().strokeBorder(Color(hex: c.color), lineWidth: on ? 2.5 : 0).padding(-5))
                    .scaleEffect(on ? 1.04 : 1)
                    .padding(5)
                Text(c.name)
                    .font(size: 12, on ? .semibold : .regular, relativeTo: .caption)
                    .foregroundStyle(on ? .ink : .muted)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(c.name)
        .accessibilityAddTraits(on ? .isSelected : [])
    }
}

struct Keypad: View {
    @Binding var digits: String
    @Binding var taps: Int
    private static let keys = ["1", "2", "3", "4", "5", "6", "7", "8", "9", ".", "0", "⌫"]
    private static var decimalSeparator: String { Locale.current.decimalSeparator ?? "." }

    var body: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 10), count: 3), spacing: 8) {
            ForEach(Self.keys, id: \.self) { k in
                Button { digits = Self.apply(k, to: digits); taps += 1 } label: {
                    Group {
                        if k == "⌫" {
                            Image(systemName: "delete.left").font(.system(size: 22, weight: .medium))
                        } else {
                            Text(k == "." ? Self.decimalSeparator : k).font(size: 26, .medium, rounded: true)
                        }
                    }
                    .foregroundStyle(.ink)
                    .frame(maxWidth: .infinity, minHeight: 52)
                    .background(Color.surface2, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                }
                .buttonStyle(PressStyle())
                .simultaneousGesture(LongPressGesture(minimumDuration: 0.45).onEnded { _ in
                    if k == "⌫" && !digits.isEmpty { digits = ""; taps += 1 }
                })
                .accessibilityLabel(k == "⌫" ? "Delete" : k == "." ? "Decimal point" : k)
                .accessibilityHint(k == "⌫" ? "Touch and hold to clear" : "")
            }
        }
    }

    /// Keypad rules: max 2 decimals, max 9 integer digits, single ".", leading "0" replaced.
    static func apply(_ key: String, to s: String) -> String {
        switch key {
        case "⌫": return String(s.dropLast())
        case ".": return s.contains(".") ? s : (s.isEmpty ? "0." : s + ".")
        default:
            if let dot = s.firstIndex(of: ".") { return s[s.index(after: dot)...].count >= 2 ? s : s + key }
            if s == "0" { return key }
            return s.count >= 9 ? s : s + key
        }
    }

    static func minor(_ s: String) -> Int64? {
        guard !s.isEmpty, let d = Decimal(string: s, locale: Locale(identifier: "en_US_POSIX")) else { return nil }
        var scaled = d * 100, rounded = Decimal()
        NSDecimalRound(&rounded, &scaled, 0, .plain)
        return NSDecimalNumber(decimal: rounded).int64Value
    }

    static func digits(fromMinor m: Int64) -> String {
        m % 100 == 0 ? "\(m / 100)" : String(format: "%lld.%02lld", m / 100, m % 100)
    }

    static func display(_ s: String) -> String {
        guard !s.isEmpty else { return "0" }
        let parts = s.split(separator: ".", omittingEmptySubsequences: false)
        let whole = (Int(parts[0]) ?? 0).formatted(.number.grouping(.automatic))
        return parts.count > 1 ? whole + decimalSeparator + parts[1] : whole
    }
}

private struct DatePickerSheet: View {
    @Binding var date: Date
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            DatePicker("Date", selection: $date, in: ...Date(), displayedComponents: .date)
                .datePickerStyle(.graphical)
                .padding(.horizontal, 16)
                .navigationTitle("Pick a date")
                .toolbarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
        .presentationDetents([.medium, .large])
    }
}

struct BudgetSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var digits: String
    @State private var taps = 0

    init() {
        let b = Store.shared.monthlyBudget
        _digits = State(initialValue: b > 0 ? Keypad.digits(fromMinor: b) : "")
    }

    var body: some View {
        VStack(spacing: 12) {
            Text("Monthly budget").font(size: 22, .bold, relativeTo: .title2).foregroundStyle(.ink)
            Text("Home shows how much is left each month.").font(size: 15).foregroundStyle(.muted)
            Spacer(minLength: 12)
            AmountDisplay(digits: digits)
            Spacer(minLength: 12)
            Keypad(digits: $digits, taps: $taps)
            Button("Save budget") {
                Store.shared.setBudget(Keypad.minor(digits) ?? 0)
                dismiss()
            }
            .buttonStyle(PrimaryButtonStyle())
            .padding(.top, 4)
            if Store.shared.monthlyBudget > 0 {
                Button("Remove budget", role: .destructive) {
                    Store.shared.setBudget(0)
                    dismiss()
                }
                .font(size: 15, .medium)
                .foregroundStyle(.danger)
            }
        }
        .padding(20)
        .padding(.top, 16)
        .presentationBackground(Color.bg)
        .presentationDragIndicator(.visible)
        .presentationCornerRadius(32)
        .sensoryFeedback(.impact(weight: .light), trigger: taps)
    }
}
