import SwiftUI
import LocalAuthentication
import UniformTypeIdentifiers

struct SettingsView: View {
    @AppStorage("appLock") private var appLock = false
    @AppStorage("hideAmounts") private var hide = false
    @AppStorage("smartAI") private var smartAI = true
    @AppStorage("autoUpdate") private var autoUpdate = false
    @State private var importing = false
    @State private var pendingRestore: Backup?
    @State private var confirmErase = false
    @State private var confirmForget = false
    @State private var message: String?

    var body: some View {
        let store = Store.shared
        let ai = OnDeviceAI.status
        NavigationStack {
            Form {
                Section { PrivacyCard() }
                    .listRowBackground(Color.brand.opacity(0.12))

                Section("Security") {
                    Toggle(isOn: Binding(get: { appLock }, set: setLock)) {
                        RowLabel(title: "App Lock", subtitle: Biometry.name, symbol: Biometry.symbol, color: .green)
                    }
                    Toggle(isOn: $hide.animation()) {
                        RowLabel(title: "Hide amounts", symbol: "eye.slash.fill", color: .gray)
                    }
                }

                Section {
                    NavigationLink { ShortcutsGuideView(showsDone: false) } label: {
                        RowLabel(title: "Auto-log with Shortcuts", symbol: "bolt.fill", color: .orange)
                    }
                    Button { Router.shared.pasteSheet = true } label: {
                        RowLabel(title: "Paste messages", symbol: "doc.on.clipboard.fill", color: .blue)
                    }
                } header: {
                    Text("SMS")
                } footer: {
                    Text("iOS doesn't let apps read your inbox. A Shortcuts automation hands each bank SMS to TxnBuddy, and it's parsed right here on this iPhone.")
                }

                Section {
                    NavigationLink { AISetupView() } label: {
                        LabeledContent {
                            if ai == .available { AIStatusText(status: ai) } else { Text("Set up").foregroundStyle(.brand) }
                        } label: {
                            RowLabel(title: "Apple Intelligence", caption: ai == .available ? nil : ai.label, symbol: "sparkles", color: .purple)
                        }
                    }
                    Toggle("Smart categorization", isOn: $smartAI)
                        .disabled(ai != .available)
                    let n = store.learned.count
                    Button { confirmForget = true } label: {
                        LabeledContent {
                            Text(n == 0 ? "Nothing yet" : n == 1 ? "1 merchant" : "\(n) merchants").foregroundStyle(.muted)
                        } label: {
                            RowLabel(title: "Learned from you", symbol: "brain.head.profile", color: .green)
                        }
                    }
                    .disabled(n == 0)
                } header: {
                    Text("On-device AI")
                } footer: {
                    Text("Learns the categories you pick, on this device. On supported iPhones Apple Intelligence helps with new merchants. Nothing is sent anywhere.")
                }

                Section("Budget & currency") {
                    Button { Router.shared.budgetEditor = true } label: {
                        LabeledContent {
                            Text(store.monthlyBudget > 0 ? Money.string(store.monthlyBudget, code: store.currency, hidden: hide) : "Not set")
                                .foregroundStyle(.muted)
                        } label: {
                            RowLabel(title: "Monthly budget", symbol: "target", color: .pink)
                        }
                    }
                    Picker(selection: Binding(get: { store.currency }, set: store.setCurrency)) {
                        ForEach(Store.currencies.contains(store.currency) ? Store.currencies : Store.currencies + [store.currency], id: \.self) { code in
                            Text("\(code)  \(Money.symbol(code))").tag(code)
                        }
                    } label: {
                        RowLabel(title: "Currency", symbol: "coloncurrencysign.circle.fill", color: .teal)
                    }
                }

                Section {
                    NavigationLink { AccountsView() } label: {
                        RowLabel(title: "Accounts", subtitle: "\(store.accounts.count)", dot: !store.unlinkedSources.isEmpty,
                                 symbol: "creditcard.fill", color: .mint)
                    }
                    NavigationLink { CategoriesView() } label: {
                        RowLabel(title: "Categories", subtitle: "\(store.categories.count)", symbol: "square.grid.2x2.fill", color: .indigo)
                    }
                }

                Section {
                    ShareLink(item: CSVExport(), preview: SharePreview("TxnBuddy.csv", image: Image(systemName: "tablecells"))) {
                        RowLabel(title: "Export CSV", symbol: "tablecells.fill", color: .green)
                    }
                    ShareLink(item: BackupExport(), preview: SharePreview("TxnBuddy backup", image: Image(systemName: "externaldrive"))) {
                        RowLabel(title: "Back up", symbol: "arrow.up.doc.fill", color: .blue)
                    }
                    Button { importing = true } label: {
                        RowLabel(title: "Restore from backup", symbol: "arrow.down.doc.fill", color: .cyan)
                    }
                    Button { confirmErase = true } label: {
                        RowLabel(title: "Erase all data", symbol: "trash.fill", color: .red, destructive: true)
                    }
                } header: {
                    Text("Data")
                } footer: {
                    Text("Backups are a file you keep. They work with TxnBuddy on Android too.")
                }

                Section {
                    let u = Updater.shared
                    if let r = u.latest {
                        Link(destination: r.html_url) {
                            RowLabel(title: "TxnBuddy \(r.version) is out", caption: "Open the release page", symbol: "arrow.down.circle.fill", color: .blue)
                        }
                    } else {
                        Button { Task { await u.check() } } label: {
                            LabeledContent {
                                if u.busy { ProgressView() }
                            } label: {
                                RowLabel(title: "Check for updates", caption: u.status, symbol: "arrow.triangle.2.circlepath", color: .blue)
                            }
                        }
                        .disabled(u.busy)
                    }
                    Toggle(isOn: $autoUpdate) {
                        RowLabel(title: "Check automatically", caption: "Once a day, when you open the app", symbol: "clock.arrow.circlepath", color: .purple)
                    }
                } header: {
                    Text("Updates")
                } footer: {
                    Text("Asks github.com/kushrike/TxnBuddy for the latest version; none of your data is sent. iOS doesn't let apps install themselves, so install the new version with Xcode or a sideloading tool.")
                }

                #if DEBUG
                Section("Developer") {
                    Button("Load demo data") { store.loadDemoData() }
                }
                #endif

                Section {
                    VStack(spacing: 6) {
                        LogoMark(size: 40)
                        Text("TxnBuddy \(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "")")
                            .font(size: 13, .semibold, relativeTo: .footnote)
                        Text("No analytics. No ads. No account.").font(size: 12, relativeTo: .caption)
                    }
                    .foregroundStyle(.muted)
                    .frame(maxWidth: .infinity)
                }
                .listRowBackground(Color.clear)
            }
            .scrollContentBackground(.hidden)
            .background(Color.bg)
            .navigationTitle("Settings")
            .fileImporter(isPresented: $importing, allowedContentTypes: [.json]) { result in
                guard case .success(let url) = result else { return }
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                if let data = try? Data(contentsOf: url), let b = try? JSON.decoder.decode(Backup.self, from: data) {
                    pendingRestore = b
                } else {
                    message = "That file isn't a TxnBuddy backup."
                }
            }
            .alert("Replace all data?", isPresented: Binding(get: { pendingRestore != nil }, set: { if !$0 { pendingRestore = nil } })) {
                Button("Replace", role: .destructive) {
                    if let b = pendingRestore { store.replaceAll(with: b); Router.shared.show("Restored \(b.transactions.count) transactions") }
                }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("Everything on this device will be replaced by the backup (\(pendingRestore?.transactions.count ?? 0) transactions).")
            }
            .confirmationDialog("Forget what TxnBuddy learned?", isPresented: $confirmForget, titleVisibility: .visible) {
                Button("Forget", role: .destructive) { store.forgetLearned(); Router.shared.show("Forgot learned categories") }
            } message: {
                Text("Your transactions stay as they are.")
            }
            .confirmationDialog("Erase all data?", isPresented: $confirmErase, titleVisibility: .visible) {
                Button("Erase everything", role: .destructive) { store.eraseAll(); Router.shared.show("All data erased") }
            } message: {
                Text("This permanently deletes every transaction, account, budget and custom category on this device.")
            }
            .alert(message ?? "", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) {
                Button("OK", role: .cancel) {}
            }
        }
    }

    private func setLock(_ on: Bool) {
        guard on else { appLock = false; return }
        let ctx = LAContext()
        guard ctx.canEvaluatePolicy(.deviceOwnerAuthentication, error: nil) else {
            message = "Set a passcode on this iPhone first, then turn on App Lock."
            return
        }
        Task { @MainActor in
            if (try? await ctx.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: "Turn on App Lock")) == true {
                appLock = true
            }
        }
    }
}

/// Settings-style row: white glyph on a coloured rounded square.
private struct RowLabel: View {
    let title: String
    var caption: String? = nil       // second line under the title
    var subtitle: String? = nil      // trailing value
    var dot = false                  // accent dot before the trailing value
    let symbol: String
    let color: Color
    var destructive = false

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: symbol)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 29, height: 29)
                .background(color.gradient, in: RoundedRectangle(cornerRadius: 7, style: .continuous))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 1) {
                Text(title).foregroundStyle(destructive ? Color.danger : Color.ink)
                if let caption { Text(caption).font(size: 13, relativeTo: .footnote).foregroundStyle(.muted) }
            }
            if let subtitle {
                Spacer()
                if dot { Circle().fill(Color.brand).frame(width: 8, height: 8).accessibilityLabel("New accounts found") }
                Text(subtitle).foregroundStyle(.muted)
            }
        }
    }
}

private struct PrivacyCard: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 12) {
                EmojiBubble(emoji: "🛡️", size: 48)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Private by design").font(size: 17, .bold, relativeTo: .headline).foregroundStyle(.ink)
                    Text("Your money data never leaves this device.").font(size: 14).foregroundStyle(.muted)
                }
            }
            VStack(alignment: .leading, spacing: 8) {
                check("Stored only on this iPhone")
                check("On-device AI")
                check("Internet only for update checks")
                check("Not in iCloud backups")
            }
        }
        .padding(.vertical, 8)
        .accessibilityElement(children: .combine)
    }

    private func check(_ text: String) -> some View {
        HStack(spacing: 8) {
            Image(systemName: "checkmark.circle.fill").foregroundStyle(.income)
            Text(text).font(size: 14).foregroundStyle(.ink)
        }
    }
}

// MARK: - Export (files are generated on demand and handed to the share sheet; nothing is uploaded by the app)

struct CSVExport: Transferable {
    static var transferRepresentation: some TransferRepresentation {
        DataRepresentation(exportedContentType: .commaSeparatedText) { _ in
            await MainActor.run { Data(Store.shared.csv().utf8) }
        }
        .suggestedFileName("TxnBuddy.csv")
    }
}

struct BackupExport: Transferable {
    static var transferRepresentation: some TransferRepresentation {
        DataRepresentation(exportedContentType: .json) { _ in
            try await MainActor.run { try JSON.encoder.encode(Store.shared.backup) }
        }
        .suggestedFileName("TxnBuddy-backup.json")
    }
}

// MARK: - Categories

struct CategoriesView: View {
    @State private var editing: Category?

    var body: some View {
        let store = Store.shared
        List {
            ForEach([false, true], id: \.self) { income in
                Section(income ? "Income" : "Expenses") {
                    ForEach(store.categories(income: income)) { c in
                        Button { editing = c } label: {
                            HStack(spacing: 12) {
                                Badge(category: c, size: 36)
                                Text(c.name).foregroundStyle(.ink)
                                Spacer()
                                if !c.isBuiltIn { Text("Custom").font(size: 12).foregroundStyle(.muted) }
                            }
                        }
                        .swipeActions {
                            if !c.isBuiltIn {
                                Button(role: .destructive) { store.deleteCategory(c) } label: { Label("Delete", systemImage: "trash") }
                            }
                        }
                    }
                }
            }
        }
        .scrollContentBackground(.hidden)
        .background(Color.bg)
        .navigationTitle("Categories")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    editing = Category(id: "c-" + UUID().uuidString.prefix(8).lowercased(), name: "", emoji: "⭐️", color: Category.palette[2], income: false)
                } label: { Image(systemName: "plus") }
                .accessibilityLabel("New category")
            }
        }
        .sheet(item: $editing) { c in
            CategoryEditor(category: c, isNew: !store.categories.contains { $0.id == c.id })
        }
    }
}

private struct CategoryEditor: View {
    @State var category: Category
    let isNew: Bool
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Badge(category: category, size: 80).frame(maxWidth: .infinity)
                }
                .listRowBackground(Color.clear)
                Section {
                    TextField("Emoji", text: Binding(get: { category.emoji }, set: { category.emoji = $0.last.map(String.init) ?? "" }))
                    TextField("Name", text: $category.name)
                }
                Section("Colour") {
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 5), spacing: 14) {
                        ForEach(Category.palette, id: \.self) { hex in
                            Button { category.color = hex } label: {
                                Circle().fill(Color(hex: hex)).frame(width: 34, height: 34)
                                    .overlay(Circle().strokeBorder(Color.ink, lineWidth: category.color == hex ? 3 : 0).padding(-4))
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel("Colour \(hex)")
                            .accessibilityAddTraits(category.color == hex ? .isSelected : [])
                        }
                    }
                    .padding(.vertical, 6)
                }
                if isNew {
                    Section("Type") {
                        Picker("Type", selection: $category.income) {
                            Text("Expense").tag(false)
                            Text("Income").tag(true)
                        }
                        .pickerStyle(.segmented)
                    }
                } else if !category.isBuiltIn {
                    Section {
                        Button("Delete category", role: .destructive) { Store.shared.deleteCategory(category); dismiss() }
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(Color.bg)
            .navigationTitle(isNew ? "New category" : "Edit category")
            .toolbarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        category.name = category.name.trimmingCharacters(in: .whitespaces)
                        Store.shared.upsertCategory(category)
                        dismiss()
                    }
                    .disabled(category.name.trimmingCharacters(in: .whitespaces).isEmpty || category.emoji.isEmpty)
                }
            }
        }
    }
}

// MARK: - Shortcuts guide

struct ShortcutsGuideView: View {
    let showsDone: Bool
    @Environment(\.openURL) private var openURL
    @Environment(\.dismiss) private var dismiss

    private let steps: [(String, String, String)] = [
        ("plus.circle.fill", "In **Shortcuts**, open **Automation**", "Tap **+** and choose **Message** as the trigger."),
        ("text.bubble.fill", "Message contains **debited**", "Pick **Run Immediately**. Repeat for **credited** and **spent**."),
        ("square.stack.3d.up.fill", "Add the action **Log Bank SMS**", "Find it under TxnBuddy, then set *Message* to **Shortcut Input**."),
        ("checkmark.seal.fill", "Done", "New bank SMS are logged instantly and parsed on this iPhone."),
    ]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                VStack(alignment: .leading, spacing: 8) {
                    EmojiBubble(emoji: "⚡️", size: 64)
                    Text("Auto-log bank SMS").font(size: 28, .bold, relativeTo: .largeTitle).foregroundStyle(.ink).padding(.top, 8)
                    Text("Set it up once. Every bank SMS is read on this iPhone the moment it arrives.")
                        .font(size: 16).foregroundStyle(.muted)
                }
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(Array(steps.enumerated()), id: \.offset) { i, step in
                        HStack(alignment: .top, spacing: 14) {
                            ZStack {
                                Circle().fill(Color.brand).frame(width: 32, height: 32)
                                Text("\(i + 1)").font(size: 15, .bold, rounded: true).foregroundStyle(.white)
                            }
                            VStack(alignment: .leading, spacing: 4) {
                                HStack(spacing: 6) {
                                    Image(systemName: step.0).foregroundStyle(.brand)
                                    Text(LocalizedStringKey(step.1)).font(size: 16).foregroundStyle(.ink)
                                }
                                Text(LocalizedStringKey(step.2)).font(size: 14).foregroundStyle(.muted)
                            }
                            .padding(.bottom, 20)
                        }
                    }
                }
                .card()
                Button { openURL(URL(string: "shortcuts://")!) } label: {
                    Label("Open Shortcuts", systemImage: "arrow.up.forward.app")
                }
                .buttonStyle(PrimaryButtonStyle())
                HStack(alignment: .top, spacing: 12) {
                    Image(systemName: "lock.shield.fill").font(.title3).foregroundStyle(.brand)
                    Text("iOS doesn't let any app read your SMS inbox, so this automation hands each bank message to TxnBuddy. It still never leaves your phone.")
                        .font(size: 14).foregroundStyle(.muted)
                }
                .card()
            }
            .padding(20)
        }
        .background(Color.bg)
        .navigationTitle("Shortcuts")
        .toolbarTitleDisplayMode(.inline)
        .toolbar {
            if showsDone { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }
}

// MARK: - AI setup guide (DESIGN §4.13)

private struct AIStatusText: View {
    let status: OnDeviceAI.Status

    var body: some View {
        HStack(spacing: 6) {
            Circle().fill(status == .available ? Color.income : status == .downloading ? .orange : Color.muted).frame(width: 8, height: 8)
            Text(status.label).foregroundStyle(.muted)
        }
    }
}

struct AISetupView: View {
    @State private var status = OnDeviceAI.status
    @State private var checks = 0
    @Environment(\.openURL) private var openURL
    @Environment(\.scenePhase) private var phase

    /// One-line status + the steps that fix it.
    private var guide: (String, [String]) {
        switch status {
        case .available:
            ("Apple Intelligence is ready.", [])
        case .turnedOff:
            ("Apple Intelligence is turned off.", [
                "Open **Settings → Apple Intelligence & Siri**.",
                "Turn on **Apple Intelligence**. iPhone and Siri need to use a language it supports.",
                "Wait for the download to finish (Wi‑Fi, charging, about 7 GB free).",
            ])
        case .downloading:
            ("Apple Intelligence is still downloading.", [
                "Keep the iPhone on Wi‑Fi and charging.",
                "Make sure about 7 GB is free.",
                "Check progress in **Settings → Apple Intelligence & Siri**.",
            ])
        case .unsupported:
            ("This iPhone can't run Apple Intelligence. It needs iPhone 15 Pro or newer.", [])
        case .needsNewerOS:
            ("Update to iOS 26 or later in **Settings → General → Software Update**.", [])
        }
    }

    var body: some View {
        let (headline, steps) = guide
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                VStack(alignment: .leading, spacing: 8) {
                    EmojiBubble(emoji: "✨", size: 64)
                    Text("Apple Intelligence").font(size: 28, .bold, relativeTo: .largeTitle).foregroundStyle(.ink).padding(.top, 8)
                    AIStatusText(status: status)
                        .font(size: 14, .semibold)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .background(Color.surface, in: Capsule())
                    Text(LocalizedStringKey(headline)).font(size: 16).foregroundStyle(.muted).padding(.top, 2)
                }
                if !steps.isEmpty {
                    VStack(alignment: .leading, spacing: 16) {
                        ForEach(Array(steps.enumerated()), id: \.offset) { i, step in
                            HStack(alignment: .top, spacing: 14) {
                                Text("\(i + 1)")
                                    .font(size: 15, .bold, rounded: true)
                                    .foregroundStyle(.white)
                                    .frame(width: 28, height: 28)
                                    .background(Color.brand, in: Circle())
                                Text(LocalizedStringKey(step)).font(size: 16).foregroundStyle(.ink).padding(.top, 4)
                            }
                        }
                    }
                    .card()
                }
                VStack(spacing: 12) {
                    if status == .turnedOff || status == .downloading {
                        Button { openURL(URL(string: UIApplication.openSettingsURLString)!) } label: {
                            Label("Open Settings", systemImage: "gear")
                        }
                        .buttonStyle(PrimaryButtonStyle())
                    }
                    Button { status = OnDeviceAI.status; checks += 1 } label: {
                        Label("Check again", systemImage: "arrow.clockwise")
                            .font(size: 17, .semibold)
                            .foregroundStyle(.brand)
                            .frame(maxWidth: .infinity, minHeight: 56)
                            .background(Color.brand.opacity(0.12), in: Capsule())
                    }
                    .buttonStyle(PressStyle())
                }
                HStack(alignment: .top, spacing: 12) {
                    Image(systemName: "checkmark.shield.fill").font(.title3).foregroundStyle(.brand)
                    Text("TxnBuddy works fully without AI: the built-in rules read your SMS, and it learns from the categories you pick.")
                        .font(size: 14).foregroundStyle(.muted)
                }
                .card()
            }
            .padding(20)
        }
        .background(Color.bg)
        .navigationTitle("On-device AI")
        .toolbarTitleDisplayMode(.inline)
        .sensoryFeedback(.success, trigger: checks)
        .onChange(of: phase) { _, p in if p == .active { status = OnDeviceAI.status } }
    }
}

// MARK: - Paste & review

struct PasteSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""
    @State private var results: [Txn]?
    @State private var working = false

    var body: some View {
        NavigationStack {
            Group {
                if let results {
                    SMSReviewView(candidates: results) { dismiss() }
                } else {
                    form
                }
            }
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
        }
        .presentationDragIndicator(.visible)
    }

    private var messages: [String] {
        text.components(separatedBy: "\n")
            .split { $0.trimmingCharacters(in: .whitespaces).isEmpty }
            .map { $0.joined(separator: " ") }
    }

    private var form: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Copy bank SMS from Messages and paste them here. Leave a blank line between messages.")
                .font(size: 15).foregroundStyle(.muted)
            TextEditor(text: $text)
                .font(size: 15)
                .scrollContentBackground(.hidden)
                .padding(12)
                .frame(minHeight: 180)
                .background(Color.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                .overlay(alignment: .topLeading) {
                    if text.isEmpty {
                        Text("Paste messages here").font(size: 15).foregroundStyle(.muted).padding(18).allowsHitTesting(false)
                    }
                }
            HStack {
                PasteButton(payloadType: String.self) { strings in
                    Task { @MainActor in text = strings.joined(separator: "\n\n") }
                }
                .buttonBorderShape(.capsule)
                .tint(.brand)
                Spacer()
                if !messages.isEmpty {
                    Text("\(messages.count) \(messages.count == 1 ? "message" : "messages")").font(size: 13).foregroundStyle(.muted)
                }
            }
            NavigationLink { ShortcutsGuideView(showsDone: false) } label: {
                Label("Make it automatic with Shortcuts", systemImage: "bolt.fill").font(size: 15, .medium)
            }
            Spacer()
            Button {
                working = true
                Task { @MainActor in
                    let found = await Store.shared.parseMessages(messages, useBodyDate: true)
                    withAnimation { results = found; working = false }
                }
            } label: {
                if working { ProgressView().tint(.white) } else { Text("Find transactions") }
            }
            .buttonStyle(PrimaryButtonStyle())
            .disabled(messages.isEmpty || working)
        }
        .padding(20)
        .background(Color.bg)
        .navigationTitle("Paste messages")
        .toolbarTitleDisplayMode(.inline)
    }
}

/// Confirm parsed SMS before saving (DESIGN §4.7).
struct SMSReviewView: View {
    @State private var candidates: [Txn]
    @State private var selected: Set<String>
    let done: () -> Void

    /// Learned (DESIGN §5): every row from the same merchant follows, and so will future SMS.
    private func recategorize(_ t: Txn, _ id: String) {
        let k = t.note.flatMap(Learn.mkey)
        for i in candidates.indices where candidates[i].income == t.income
            && (candidates[i].id == t.id || (k.map { Learn.covers($0, candidates[i].note) } ?? false)) {
            candidates[i].categoryId = id
        }
        Store.shared.learn(t.note, id)
    }

    init(candidates: [Txn], done: @escaping () -> Void) {
        _candidates = State(initialValue: candidates)
        _selected = State(initialValue: Set(candidates.map(\.id)))
        self.done = done
    }

    var body: some View {
        let store = Store.shared
        if candidates.isEmpty {
            ContentUnavailableView {
                Label("No transactions found", systemImage: "text.magnifyingglass")
            } description: {
                Text("Those messages had no debits or credits, or they're already logged.")
            } actions: {
                Button("Done", action: done).buttonStyle(.borderedProminent).buttonBorderShape(.capsule)
            }
            .background(Color.bg)
        } else {
            List {
                Section {
                    ForEach($candidates) { $t in
                        let on = selected.contains(t.id)
                        HStack(spacing: 12) {
                            Button { if on { selected.remove(t.id) } else { selected.insert(t.id) } } label: {
                                Image(systemName: on ? "checkmark.circle.fill" : "circle")
                                    .font(.system(size: 22))
                                    .foregroundStyle(on ? Color.brand : Color.muted)
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(on ? "Selected" : "Not selected")
                            Menu {
                                ForEach(store.categories(income: t.income)) { c in
                                    Button("\(c.emoji)  \(c.name)") { recategorize(t, c.id) }
                                }
                            } label: {
                                Badge(category: store.category(t.categoryId))
                            }
                            .accessibilityLabel("Category \(store.category(t.categoryId).name)")
                            VStack(alignment: .leading, spacing: 2) {
                                Text(t.note ?? store.category(t.categoryId).name).font(size: 16, .medium).foregroundStyle(.ink).lineLimit(1)
                                Text(store.category(t.categoryId).name + " · " + t.date.formatted(.dateTime.day().month(.abbreviated)))
                                    .font(size: 13, relativeTo: .footnote).foregroundStyle(.muted).lineLimit(1)
                            }
                            Spacer(minLength: 6)
                            AmountAndAccount(txn: t)
                        }
                        .opacity(on ? 1 : 0.5)
                        .listRowBackground(Color.surface)
                    }
                } header: {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Found \(candidates.count) \(candidates.count == 1 ? "transaction" : "transactions")")
                            .font(size: 22, .bold, relativeTo: .title2).foregroundStyle(.ink)
                        Text("Parsed on this device. Nothing was uploaded.").font(size: 13).foregroundStyle(.muted)
                    }
                    .textCase(nil)
                    .padding(.bottom, 8)
                }
            }
            .scrollContentBackground(.hidden)
            .background(Color.bg)
            .safeAreaInset(edge: .bottom) {
                Button("Add \(selected.count) \(selected.count == 1 ? "transaction" : "transactions")") {
                    let picked = candidates.filter { selected.contains($0.id) }
                    store.add(picked)
                    Router.shared.show("Added \(picked.count) from SMS")
                    done()
                }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(selected.isEmpty)
                .padding(.horizontal, 20)
                .padding(.vertical, 10)
                .background(Color.bg)
            }
            .navigationTitle("Review")
            .toolbarTitleDisplayMode(.inline)
        }
    }
}
