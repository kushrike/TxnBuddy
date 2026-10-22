import SwiftUI

extension Account {
    typealias Kind = (id: String, emoji: String, name: String, placeholder: String)
    static let kinds: [Kind] = [
        ("bank", "🏦", "Bank", "HDFC Savings"), ("card", "💳", "Card", "Amazon ICICI Card"),
        ("wallet", "👛", "Wallet", "Paytm Wallet"), ("cash", "💵", "Cash", "Cash"),
    ]
    var kindInfo: Kind { Account.kinds.first { $0.id == kind } ?? Account.kinds[0] }
    var emoji: String { kindInfo.emoji }

    /// "Card · ••4521, ••7788". Cash has no identifiers to show.
    var subtitle: String {
        kind == "cash" ? kindInfo.name
            : kindInfo.name + " · " + (ids.isEmpty ? "No identifiers" : ids.map(Account.display).joined(separator: ", "))
    }
}

private func count(_ n: Int, _ noun: String) -> String { "\(n) \(noun)\(n == 1 ? "" : "s")" }

// MARK: - Accounts screen (DESIGN §4.10)

struct AccountsView: View {
    var showsDone = false
    @State private var editing: Account?
    @State private var linking: Store.Source?
    @State private var deleting: Account?
    @AppStorage("hideAmounts") private var hide = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let store = Store.shared
        let sources = store.unlinkedSources
        let counts = Dictionary(grouping: store.txns.compactMap(\.accountId), by: { $0 }).mapValues(\.count)
        List {
            if store.accounts.isEmpty && sources.isEmpty {
                Section { empty }.listRowBackground(Color.clear)
            }
            if !sources.isEmpty {
                Section {
                    ForEach(sources) { s in
                        Button { linking = s } label: { sourceRow(s) }
                            .listRowBackground(Color.surface)
                    }
                } header: {
                    header("Found in SMS", caption: "Link each one to an account and future messages are sorted automatically.")
                }
            }
            if !store.accounts.isEmpty {
                Section {
                    ForEach(store.accounts) { a in
                        Button { editing = a } label: { AccountRow(account: a, trailing: count(counts[a.id] ?? 0, "transaction")) }
                            .listRowBackground(Color.surface)
                            .swipeActions { Button { deleting = a } label: { Label("Delete", systemImage: "trash") }.tint(.red) }
                            .contextMenu { Button(role: .destructive) { deleting = a } label: { Label("Delete", systemImage: "trash") } }
                            .confirmationDialog("Delete \(a.name)?", isPresented: Binding(get: { deleting?.id == a.id }, set: { if !$0 { deleting = nil } }),
                                                titleVisibility: .visible) {
                                Button("Delete account", role: .destructive) { store.deleteAccount(a) }
                            } message: {
                                Text("Its transactions stay, just unlinked.")
                            }
                    }
                } header: {
                    header("Your accounts")
                }
            }
            Section {
                Button { editing = Account(name: "") } label: {
                    Label("Add account", systemImage: "plus")
                        .font(size: 16, .semibold)
                        .foregroundStyle(.brand)
                        .frame(maxWidth: .infinity, minHeight: 52)
                        .background(Color.brand.opacity(0.12), in: Capsule())
                }
                .buttonStyle(PressStyle())
            }
            .listRowBackground(Color.clear)
            .listRowInsets(EdgeInsets())
        }
        .scrollContentBackground(.hidden)
        .background(Color.bg)
        .navigationTitle("Accounts")
        .toolbar {
            if showsDone { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
        .sheet(item: $editing) { AccountEditor(account: $0) }
        .sheet(item: $linking) { LinkSheet(source: $0) }
    }

    private func sourceRow(_ s: Store.Source) -> some View {
        let incomeOnly = s.spent == 0 && s.received > 0
        let amount = Money.string(incomeOnly ? s.received : s.spent, code: Store.shared.currency, hidden: hide, signed: incomeOnly ? true : nil)
        return HStack(spacing: 12) {
            EmojiBubble(emoji: "🔎", size: 40)
            VStack(alignment: .leading, spacing: 2) {
                Text(Account.display(s.id)).font(size: 16, .semibold, rounded: true).foregroundStyle(.ink)
                Text("\(count(s.count, "transaction")) · \(amount)").font(size: 13, relativeTo: .footnote).foregroundStyle(.muted).lineLimit(1)
            }
            Spacer(minLength: 8)
            Text("Link")
                .font(size: 14, .semibold)
                .foregroundStyle(.white)
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
                .background(Color.brand, in: Capsule())
        }
        .padding(.vertical, 4)
    }

    private func header(_ title: String, caption: String? = nil) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(size: 17, .semibold, relativeTo: .headline).foregroundStyle(.ink)
            if let caption { Text(caption).font(size: 13, relativeTo: .footnote).foregroundStyle(.muted) }
        }
        .textCase(nil)
        .padding(.bottom, 4)
    }

    private var empty: some View {
        VStack(spacing: 12) {
            EmojiBubble(emoji: "💳")
            Text("No accounts yet")
                .font(size: 20, .bold, relativeTo: .title3)
                .foregroundStyle(.ink)
                .padding(.top, 4)
            Text("Add your bank accounts, cards and wallets. TxnBuddy matches bank SMS to them by the last 4 digits.")
                .font(size: 15)
                .foregroundStyle(.muted)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 28)
    }
}

private struct AccountRow: View {
    let account: Account
    var trailing: String? = nil

    var body: some View {
        HStack(spacing: 12) {
            EmojiBubble(emoji: account.emoji, size: 40)
            VStack(alignment: .leading, spacing: 2) {
                Text(account.name).font(size: 16, .medium).foregroundStyle(.ink).lineLimit(1)
                Text(account.subtitle).font(size: 13, relativeTo: .footnote).foregroundStyle(.muted).lineLimit(1)
            }
            Spacer(minLength: 8)
            if let trailing { Text(trailing).font(size: 13, relativeTo: .footnote).foregroundStyle(.muted) }
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }
}

// MARK: - Link sheet (DESIGN §4.12)

private struct LinkSheet: View {
    let source: Store.Source
    @State private var creating: Account?
    @AppStorage("hideAmounts") private var hide = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let store = Store.shared
        let shown = Account.display(source.id)
        let incomeOnly = source.spent == 0 && source.received > 0
        let amount = Money.string(incomeOnly ? source.received : source.spent, code: store.currency, hidden: hide)
        let targets = store.accounts.filter { $0.kind != "cash" }      // cash has no identifiers
        List {
            Section {
                Button {
                    creating = Account(name: "", kind: Account.isDigits(source.id) ? "card" : "wallet", ids: [source.id.lowercased()])
                } label: {
                    HStack(spacing: 12) {
                        Image(systemName: "plus")
                            .font(.system(size: 17, weight: .semibold))
                            .foregroundStyle(.white)
                            .frame(width: 40, height: 40)
                            .background(Color.brand, in: Circle())
                        Text("Create new account").font(size: 16, .semibold).foregroundStyle(.brand)
                    }
                    .padding(.vertical, 4)
                }
                .listRowBackground(Color.surface)
            } header: {
                VStack(alignment: .leading, spacing: 4) {
                    Text(shown).font(size: 28, .bold, rounded: true, relativeTo: .largeTitle).foregroundStyle(.ink)
                    Text("Seen in \(source.count) SMS · \(amount) \(incomeOnly ? "received" : "spent")")
                        .font(size: 15).foregroundStyle(.muted)
                }
                .textCase(nil)
                .padding(.bottom, 8)
            }
            if !targets.isEmpty {
                Section {
                    ForEach(targets) { a in
                        Button {
                            store.link(source.id, to: a.id)
                            Router.shared.show("Linked \(shown) to \(a.name)")
                            dismiss()
                        } label: { AccountRow(account: a) }
                        .listRowBackground(Color.surface)
                    }
                } header: {
                    Text("Or add it to").font(size: 13, .semibold, relativeTo: .footnote).foregroundStyle(.muted).textCase(nil)
                }
            }
        }
        .scrollContentBackground(.hidden)
        .contentMargins(.top, 12, for: .scrollContent)
        .background(Color.bg)
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .presentationBackground(Color.bg)
        .sheet(item: $creating) {
            AccountEditor(account: $0) { a in
                if a.ids.contains(source.id.lowercased()) { Router.shared.show("Linked \(shown) to \(a.name)") }
                dismiss()
            }
        }
    }
}

// MARK: - Account editor (DESIGN §4.11): add + edit

struct AccountEditor: View {
    @State private var account: Account
    @State private var isNew: Bool          // fixed at open, so the title doesn't flip while the sheet closes
    var onSave: (Account) -> Void
    @State private var newId = ""
    @State private var shakes = 0
    @State private var confirmDelete = false
    @FocusState private var nameFocused: Bool
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    init(account: Account, onSave: @escaping (Account) -> Void = { _ in }) {
        _account = State(initialValue: account)
        _isNew = State(initialValue: !Store.shared.accounts.contains { $0.id == account.id })
        self.onSave = onSave
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    VStack(spacing: 18) {
                        EmojiBubble(emoji: account.emoji, size: 72)
                        HStack(spacing: 6) {
                            ForEach(Account.kinds, id: \.id) { k in
                                let on = account.kind == k.id
                                Button { withAnimation(.snappy(duration: 0.2)) { account.kind = k.id } } label: {
                                    Text("\(k.emoji) \(k.name)")
                                        .font(size: 14, .medium)
                                        .lineLimit(1)
                                        .foregroundStyle(on ? Color.white : Color.ink)
                                        .frame(maxWidth: .infinity, minHeight: 36)
                                        .background(on ? Color.brand : Color.surface2, in: Capsule())
                                }
                                .buttonStyle(PressStyle())
                                .accessibilityAddTraits(on ? .isSelected : [])
                            }
                        }
                    }
                    .frame(maxWidth: .infinity)
                }
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets())
                .sensoryFeedback(.selection, trigger: account.kind)

                Section {
                    TextField(account.kindInfo.placeholder, text: $account.name)
                        .focused($nameFocused)
                        .submitLabel(.done)
                }
                .listRowBackground(Color.surface)

                if account.kind != "cash" {
                    Section {
                        if !account.ids.isEmpty {
                            ScrollView(.horizontal) {
                                HStack(spacing: 8) { ForEach(account.ids, id: \.self, content: idChip) }
                            }
                            .scrollIndicators(.hidden)
                        }
                        HStack {
                            TextField("Last 4 digits or wallet ID", text: $newId)
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled()
                                .submitLabel(.done)
                                .onSubmit { addId() }
                            Button("Add") { addId() }
                                .font(size: 15, .semibold)
                                .disabled(newId.trimmingCharacters(in: .whitespaces).isEmpty)
                        }
                        .modifier(Shake(animatableData: CGFloat(shakes)))
                    } header: {
                        Text("Identifiers")
                    } footer: {
                        Text("Only the last 4 digits are stored. Used to match bank SMS on this device.")
                    }
                    .listRowBackground(Color.surface)
                    .buttonStyle(.borderless)
                }

                if !isNew {
                    Section {
                        Button("Delete account", role: .destructive) { confirmDelete = true }
                            .frame(maxWidth: .infinity)
                    }
                    .listRowBackground(Color.surface)
                }
            }
            .scrollContentBackground(.hidden)
            .background(Color.bg)
            .navigationTitle(isNew ? "New account" : "Edit account")
            .toolbarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save", action: save).disabled(account.name.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            .confirmationDialog("Delete \(account.name)?", isPresented: $confirmDelete, titleVisibility: .visible) {
                Button("Delete account", role: .destructive) { Store.shared.deleteAccount(account); dismiss() }
            } message: {
                Text("Its transactions stay, just unlinked.")
            }
            .sensoryFeedback(.error, trigger: shakes)
            .onAppear { if isNew && account.name.isEmpty { nameFocused = true } }
        }
    }

    private func idChip(_ id: String) -> some View {
        HStack(spacing: 6) {
            Text(Account.display(id)).font(size: 14, .medium, rounded: true).foregroundStyle(.ink)
            Button { withAnimation(.snappy) { account.ids.removeAll { $0 == id } } } label: {
                Image(systemName: "xmark.circle.fill").foregroundStyle(.muted)
            }
            .accessibilityLabel("Remove \(Account.display(id))")
        }
        .padding(.leading, 12)
        .padding(.trailing, 8)
        .padding(.vertical, 7)
        .background(Color.surface2, in: Capsule())
    }

    /// Runs the field through normId; invalid input shakes the field.
    @discardableResult private func addId() -> Bool {
        guard let id = Account.normId(newId) else {
            withAnimation(reduceMotion ? nil : .linear(duration: 0.4)) { shakes += 1 }
            return false
        }
        if !account.ids.contains(id) { withAnimation(.snappy) { account.ids.append(id) } }
        newId = ""
        return true
    }

    private func save() {
        // An ID typed but not yet added still counts.
        if account.kind != "cash" && !newId.trimmingCharacters(in: .whitespaces).isEmpty && !addId() { return }
        account.name = account.name.trimmingCharacters(in: .whitespaces)
        if account.kind == "cash" { account.ids = [] }
        Store.shared.saveAccount(account)
        onSave(account)
        dismiss()
    }
}
