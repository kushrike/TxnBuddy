import Foundation
import Observation

// MARK: - Models (DESIGN §2 — the same JSON is the backup format on iOS and Android)

struct Category: Codable, Identifiable, Hashable {
    var id: String
    var name: String
    var emoji: String
    var color: String
    var income: Bool

    var isBuiltIn: Bool { Category.defaults.contains { $0.id == id } }

    static let defaults: [Category] = [
        .init(id: "food", name: "Food", emoji: "🍔", color: "#FF8A3D", income: false),
        .init(id: "groceries", name: "Groceries", emoji: "🛒", color: "#22C55E", income: false),
        .init(id: "transport", name: "Transport", emoji: "🚕", color: "#3B82F6", income: false),
        .init(id: "shopping", name: "Shopping", emoji: "🛍️", color: "#EC4899", income: false),
        .init(id: "bills", name: "Bills", emoji: "💡", color: "#F59E0B", income: false),
        .init(id: "entertainment", name: "Fun", emoji: "🎬", color: "#8B5CF6", income: false),
        .init(id: "health", name: "Health", emoji: "💊", color: "#EF4444", income: false),
        .init(id: "home", name: "Home", emoji: "🏠", color: "#14B8A6", income: false),
        .init(id: "travel", name: "Travel", emoji: "✈️", color: "#06B6D4", income: false),
        .init(id: "education", name: "Education", emoji: "📚", color: "#6366F1", income: false),
        .init(id: "transfer", name: "Transfers", emoji: "💸", color: "#64748B", income: false),
        .init(id: "other", name: "Other", emoji: "📦", color: "#9CA3AF", income: false),
        .init(id: "salary", name: "Salary", emoji: "💼", color: "#10B981", income: true),
        .init(id: "refund", name: "Refunds", emoji: "🔁", color: "#84CC16", income: true),
        .init(id: "otherIncome", name: "Other income", emoji: "💰", color: "#0EA5E9", income: true),
    ]
    /// Palette offered for custom categories.
    static let palette = ["#FF8A3D", "#22C55E", "#3B82F6", "#EC4899", "#F59E0B", "#8B5CF6", "#EF4444",
                          "#14B8A6", "#06B6D4", "#6366F1", "#64748B", "#9CA3AF", "#10B981", "#84CC16", "#0EA5E9"]
}

struct Txn: Codable, Identifiable, Hashable {
    var id = UUID().uuidString
    var amount: Int64            // minor units, always > 0
    var income: Bool
    var categoryId: String
    var note: String?
    var date: Date
    var account: String?         // source identifier seen in the SMS (iOS: the last 4 digits); nil for manual entries
    var accountId: String?       // the user's Account this belongs to
    var source = "manual"        // "manual" | "sms"
    var smsKey: String?

    var isSMS: Bool { source == "sms" }

    init(amount: Int64, income: Bool, categoryId: String, note: String?, date: Date,
         account: String? = nil, accountId: String? = nil, source: String = "manual", smsKey: String? = nil) {
        (self.amount, self.income, self.categoryId, self.note, self.date) = (amount, income, categoryId, note, date)
        (self.account, self.accountId, self.source, self.smsKey) = (account, accountId, source, smsKey)
    }

    // Tolerant decode + explicit nulls on encode, so backups round-trip with the Android app.
    enum CodingKeys: String, CodingKey { case id, amount, income, categoryId, note, date, account, accountId, source, smsKey }
    init(from d: Decoder) throws {
        let c = try d.container(keyedBy: CodingKeys.self)
        id = try c.decodeIfPresent(String.self, forKey: .id) ?? UUID().uuidString
        amount = try c.decode(Int64.self, forKey: .amount)
        income = try c.decodeIfPresent(Bool.self, forKey: .income) ?? false
        categoryId = try c.decodeIfPresent(String.self, forKey: .categoryId) ?? (income ? "otherIncome" : "other")
        note = try c.decodeIfPresent(String.self, forKey: .note)
        date = try c.decode(Date.self, forKey: .date)
        account = try c.decodeIfPresent(String.self, forKey: .account)
        accountId = try c.decodeIfPresent(String.self, forKey: .accountId)
        source = try c.decodeIfPresent(String.self, forKey: .source) ?? "manual"
        smsKey = try c.decodeIfPresent(String.self, forKey: .smsKey)
    }
    func encode(to e: Encoder) throws {
        var c = e.container(keyedBy: CodingKeys.self)
        try c.encode(id, forKey: .id); try c.encode(amount, forKey: .amount); try c.encode(income, forKey: .income)
        try c.encode(categoryId, forKey: .categoryId); try c.encode(note, forKey: .note); try c.encode(date, forKey: .date)
        try c.encode(account, forKey: .account); try c.encode(accountId, forKey: .accountId); try c.encode(source, forKey: .source); try c.encode(smsKey, forKey: .smsKey)
    }
}

struct Backup: Codable {
    struct Settings: Codable { var currency: String; var monthlyBudget: Int64 }
    var version = 1
    var settings: Settings
    var categories: [Category]
    var transactions: [Txn]
    var accounts: [Account]?     // nil in backups made before accounts existed
    var learned: [String: String]?   // merchant key → category the user picked (DESIGN §5); nil in older backups
}

enum JSON {
    static let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .iso8601
        e.outputFormatting = [.sortedKeys]
        return e
    }()
    static let decoder: JSONDecoder = {
        let d = JSONDecoder()
        let plain = ISO8601DateFormatter()
        let frac = ISO8601DateFormatter()
        frac.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        d.dateDecodingStrategy = .custom { dec in
            let s = try dec.singleValueContainer().decode(String.self)
            if let date = plain.date(from: s) ?? frac.date(from: s) { return date }
            throw DecodingError.dataCorrupted(.init(codingPath: dec.codingPath, debugDescription: "Bad date \(s)"))
        }
        return d
    }()
}

// MARK: - Store

/// All app data, in memory, persisted as one JSON file in Application Support (excluded from iCloud backup).
/// ponytail: whole-file JSON rewrite on every change; move to SQLite past ~50k transactions.
@MainActor @Observable final class Store {
    static let shared = Store()
    static let currencies = ["INR", "USD", "EUR", "GBP", "AED", "SGD", "AUD", "CAD", "JPY"]

    private(set) var txns: [Txn] = []                 // newest first
    private(set) var categories: [Category] = Category.defaults
    private(set) var currency = Store.deviceCurrency
    private(set) var monthlyBudget: Int64 = 0
    private(set) var accounts: [Account] = []
    private(set) var learned: [String: String] = [:]   // merchant key → category the user picked (DESIGN §5)
    @ObservationIgnored private let url: URL

    private static var deviceCurrency: String { Locale.current.currency?.identifier ?? "INR" }

    init() {
        let fm = FileManager.default
        var dir = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("TxnBuddy", isDirectory: true)
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        var rv = URLResourceValues()
        rv.isExcludedFromBackup = true            // privacy: never in iCloud/iTunes backups
        try? dir.setResourceValues(rv)
        url = dir.appendingPathComponent("data.json")
        guard let data = try? Data(contentsOf: url) else { return }
        if let b = try? JSON.decoder.decode(Backup.self, from: data) {
            apply(b)
        } else {
            // Never silently overwrite a file we couldn't read: keep it aside for recovery.
            try? fm.moveItem(at: url, to: dir.appendingPathComponent("data-unreadable-\(Int(Date().timeIntervalSince1970)).json"))
        }
    }

    var backup: Backup {
        Backup(settings: .init(currency: currency, monthlyBudget: monthlyBudget), categories: categories, transactions: txns, accounts: accounts, learned: learned)
    }

    private func apply(_ b: Backup) {
        currency = b.settings.currency
        monthlyBudget = max(0, b.settings.monthlyBudget)
        categories = b.categories + Category.defaults.filter { d in !b.categories.contains { $0.id == d.id } }
        txns = b.transactions.filter { $0.amount > 0 }.sorted { $0.date > $1.date }
        accounts = b.accounts ?? []
        learned = (b.learned ?? [:]).filter { e in categories.contains { $0.id == e.value } }
    }

    private func save() {
        do {
            try JSON.encoder.encode(backup).write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        } catch {
            print("TxnBuddy: save failed: \(error)")
        }
    }

    // MARK: Transactions

    func upsert(_ t: Txn) {
        if let i = txns.firstIndex(where: { $0.id == t.id }) { txns[i] = t } else { txns.append(t) }
        txns.sort { $0.date > $1.date }
        save()
    }

    func add(_ ts: [Txn]) {
        guard !ts.isEmpty else { return }
        txns.append(contentsOf: ts)
        txns.sort { $0.date > $1.date }
        save()
    }

    func delete(_ t: Txn) {
        txns.removeAll { $0.id == t.id }
        save()
    }

    func delete(ids: Set<String>) {
        txns.removeAll { ids.contains($0.id) }
        save()
    }

    // MARK: Settings & categories

    func setCurrency(_ code: String) { currency = code; save() }
    func setBudget(_ minor: Int64) { monthlyBudget = max(0, minor); save() }

    func category(_ id: String) -> Category {
        categories.first { $0.id == id } ?? categories.first { $0.id == "other" } ?? Category.defaults[11]
    }
    func categories(income: Bool) -> [Category] { categories.filter { $0.income == income } }

    func upsertCategory(_ c: Category) {
        if let i = categories.firstIndex(where: { $0.id == c.id }) { categories[i] = c } else { categories.append(c) }
        save()
    }

    /// Custom categories only; their transactions move to Other / Other income.
    func deleteCategory(_ c: Category) {
        guard !c.isBuiltIn else { return }
        let fallback = c.income ? "otherIncome" : "other"
        for i in txns.indices where txns[i].categoryId == c.id { txns[i].categoryId = fallback }
        categories.removeAll { $0.id == c.id }
        learned = learned.filter { $0.value != c.id }
        save()
    }

    // MARK: Learning from you (DESIGN §5)

    /// Remembers the category the user picked for this merchant / note.
    func learn(_ note: String?, _ categoryId: String) {
        guard let k = note.flatMap(Learn.mkey), learned[k] != categoryId else { return }
        learned[k] = categoryId
        save()
    }

    func forgetLearned() { learned = [:]; save() }

    func recategorize(_ ids: Set<String>, to categoryId: String) {
        for i in txns.indices where ids.contains(txns[i].id) { txns[i].categoryId = categoryId }
        save()
    }

    /// Trained on the user's transactions of one kind that have a note and a real category.
    func bayes(income: Bool) -> Learn.Bayes {
        Learn.Bayes(txns.compactMap { t in
            guard t.income == income, let n = t.note, !n.isEmpty, t.categoryId != "other", t.categoryId != "otherIncome" else { return nil }
            return (n, t.categoryId)
        })
    }

    /// Memory → keyword category → classifier; nil = nothing learned (the LLM or Other decides).
    func pick(note: String?, income: Bool, keyword: String, bayes: Learn.Bayes) -> String? {
        if let id = Learn.recall(note, in: learned), categories.contains(where: { $0.id == id && $0.income == income }) { return id }
        if keyword != "other" && keyword != "otherIncome" { return keyword }
        return note.flatMap(bayes.predict)
    }

    // MARK: Accounts (DESIGN §2.1)

    struct Source: Identifiable { let id: String; var count = 0; var spent: Int64 = 0; var received: Int64 = 0 }

    func account(_ id: String?) -> Account? { id.flatMap { id in accounts.first { $0.id == id } } }

    /// Row text for where the money moved: the account's name, else the SMS identifier (••4521).
    func accountLabel(_ t: Txn) -> String? { account(t.accountId)?.name ?? t.account.map(Account.display) }

    /// Identifiers seen in SMS whose transactions have no account yet, most frequent first.
    var unlinkedSources: [Source] {
        var by: [String: Source] = [:]
        for t in txns where t.accountId == nil {
            guard let id = t.account else { continue }
            var s = by[id] ?? Source(id: id)
            s.count += 1
            if t.income { s.received += t.amount } else { s.spent += t.amount }
            by[id] = s
        }
        return by.values.sorted { $0.count != $1.count ? $0.count > $1.count : $0.id < $1.id }
    }

    /// Upsert. An identifier belongs to one account, and transactions waiting on these identifiers get linked.
    func saveAccount(_ a: Account) {
        for i in accounts.indices { accounts[i].ids.removeAll { a.ids.contains($0) } }
        if let i = accounts.firstIndex(where: { $0.id == a.id }) { accounts[i] = a } else { accounts.append(a) }
        let ids = Set(a.ids.map { $0.lowercased() })
        for i in txns.indices where txns[i].accountId == nil && ids.contains(txns[i].account?.lowercased() ?? "") {
            txns[i].accountId = a.id
        }
        save()
    }

    func link(_ identifier: String, to accountId: String) {
        guard var a = account(accountId) else { return }
        if !a.ids.contains(identifier.lowercased()) { a.ids.append(identifier.lowercased()) }
        saveAccount(a)
    }

    /// Its transactions keep their identifier, so they show up as unlinked again.
    func deleteAccount(_ a: Account) {
        accounts.removeAll { $0.id == a.id }
        for i in txns.indices where txns[i].accountId == a.id { txns[i].accountId = nil }
        save()
    }

    // MARK: Data

    func replaceAll(with b: Backup) { apply(b); save() }

    func eraseAll() {
        txns = []; categories = Category.defaults; accounts = []; learned = [:]; monthlyBudget = 0; currency = Store.deviceCurrency
        save()
    }

    func csv() -> String {
        let iso = ISO8601DateFormatter()
        func esc(_ s: String) -> String { s.contains(where: { ",\"\n".contains($0) }) ? "\"" + s.replacingOccurrences(of: "\"", with: "\"\"") + "\"" : s }
        var out = "date,type,amount,currency,category,note,account,source\n"
        for t in txns {
            let amount = String(format: "%lld.%02lld", t.amount / 100, t.amount % 100)
            out += [iso.string(from: t.date), t.income ? "income" : "expense", amount, currency,
                    esc(category(t.categoryId).name), esc(t.note ?? ""), esc(account(t.accountId)?.name ?? t.account ?? ""), t.source].joined(separator: ",") + "\n"
        }
        return out
    }

    // MARK: Analytics

    func txns(in interval: DateInterval) -> [Txn] {
        txns.filter { $0.date >= interval.start && $0.date < interval.end }
    }
    func spent(in interval: DateInterval) -> Int64 { txns(in: interval).filter { !$0.income }.reduce(0) { $0 + $1.amount } }
    func received(in interval: DateInterval) -> Int64 { txns(in: interval).filter(\.income).reduce(0) { $0 + $1.amount } }

    // MARK: SMS (parsed on-device; raw bodies are never stored)

    /// Turns messages into new, not-yet-saved transactions (duplicates dropped by smsKey).
    /// `useBodyDate`: paste flow takes the date printed in the SMS; Shortcuts (real time) uses now.
    func parseMessages(_ texts: [String], useBodyDate: Bool) async -> [Txn] {
        let aiOn = UserDefaults.standard.object(forKey: "smartAI") as? Bool ?? true
        let useAI = aiOn && OnDeviceAI.status == .available && texts.count <= 200
        var keys = Set(txns.compactMap(\.smsKey))
        let nb = (expense: bayes(income: false), income: bayes(income: true))
        var out: [Txn] = []
        let now = Date(), cal = Calendar.current
        for text in texts {
            let key = SMSParser.key(for: text)
            guard !keys.contains(key) else { continue }
            var parsed = SMSParser.parse(text, now: now)
            if parsed == nil, useAI, SMSParser.isRescueCandidate(text), let r = await OnDeviceAI.rescue(message: text) {
                let lower = SMSParser.normalize(text).lowercased()
                parsed = .init(amountMinor: r.amountMinor, income: r.income, account: nil, merchant: r.merchant,
                               categoryId: SMSParser.category(lower: lower, merchant: r.merchant, income: r.income, viaVPA: false),
                               date: nil, smsKey: key, viaVPA: false)
            }
            guard var p = parsed else { continue }
            // Learning from you first (every phone), then the on-device LLM for what's still Other.
            if let id = pick(note: p.merchant, income: p.income, keyword: p.categoryId, bayes: p.income ? nb.income : nb.expense) { p.categoryId = id }
            if useAI, p.categoryId == "other",
               let id = await OnDeviceAI.categorize(merchant: p.merchant, message: text, choices: categories(income: false)) {
                p.categoryId = id
            }
            var date = now
            if useBodyDate, let d = p.date, !cal.isDate(d, inSameDayAs: now) {
                date = cal.date(bySettingHour: 12, minute: 0, second: 0, of: d) ?? d
            }
            keys.insert(key)
            out.append(Txn(amount: p.amountMinor, income: p.income, categoryId: p.categoryId, note: p.merchant,
                           date: date, account: p.account, accountId: Account.accountFor(p.account, text: text, in: accounts)?.id,
                           source: "sms", smsKey: key))
        }
        return out
    }

    /// Used by the "Log Bank SMS" App Intent. Returns the line Shortcuts shows.
    func logFromShortcut(_ text: String) async -> String {
        guard let t = await parseMessages([text], useBodyDate: false).first else {
            return txns.contains { $0.smsKey == SMSParser.key(for: text) } ? "Already logged." : "No transaction found in that message."
        }
        add([t])
        let hidden = UserDefaults.standard.bool(forKey: "hideAmounts")
        let amount = hidden ? (t.income ? "income" : "expense") : Money.string(t.amount, code: currency, signed: t.income)
        return "Logged \(amount) · \(t.note ?? category(t.categoryId).name)"
    }
}

// MARK: - UI routing (sheets/tabs/toasts shared across screens)

@MainActor @Observable final class Router {
    static let shared = Router()
    enum Tab: Hashable { case home, activity, insights, settings }
    struct Editor: Identifiable { let id = UUID(); var txn: Txn?; var income = false }
    struct Toast: Identifiable { let id = UUID(); let text: String; var action = "Undo"; let undo: (() -> Void)? }

    var tab: Tab = .home
    var editor: Editor?
    var budgetEditor = false
    var shortcutsGuide = false
    var pasteSheet = false
    var accountsSheet = false
    var toast: Toast?

    func newTxn(income: Bool = false) { editor = Editor(txn: nil, income: income) }
    func edit(_ t: Txn) { editor = Editor(txn: t, income: t.income) }
    func show(_ text: String, action: String = "Undo", undo: (() -> Void)? = nil) { toast = Toast(text: text, action: action, undo: undo) }

    func delete(_ t: Txn) {
        Store.shared.delete(t)
        show("Deleted \(t.note ?? Store.shared.category(t.categoryId).name)") { Store.shared.upsert(t) }
    }
}

// MARK: - Money formatting

enum Money {
    @MainActor private static var symbols: [String: String] = [:]

    /// ₹12,340 (fraction hidden when .00) or ₹12,340.50. `signed`: true → "+", false → "−", nil → none.
    @MainActor static func string(_ minor: Int64, code: String, hidden: Bool = false, signed: Bool? = nil) -> String {
        let prefix = signed.map { $0 ? "+" : "−" } ?? ""
        if hidden { return prefix + symbol(code) + "••••" }
        let frac = minor % 100 == 0 ? 0 : 2
        return prefix + (Decimal(minor) / 100).formatted(.currency(code: code).precision(.fractionLength(frac)))
    }

    /// Axis labels: ₹1.2K
    @MainActor static func compact(_ minor: Int64, code: String) -> String {
        symbol(code) + (Double(minor) / 100).formatted(.number.notation(.compactName).precision(.fractionLength(0...1)))
    }

    @MainActor static func symbol(_ code: String) -> String {
        if let s = symbols[code] { return s }
        let f = NumberFormatter()
        f.numberStyle = .currency
        f.currencyCode = code
        symbols[code] = f.currencySymbol
        return f.currencySymbol
    }
}

// MARK: - Demo data (DEBUG builds only)

#if DEBUG
extension Store {
    func loadDemoData() {
        var seed: UInt64 = 42
        func rand(_ r: ClosedRange<Int>) -> Int {
            seed = seed &* 6364136223846793005 &+ 1442695040888963407
            return r.lowerBound + Int((seed >> 33) % UInt64(r.count))
        }
        let pool: [(String, [String], ClosedRange<Int>, Int)] = [   // category, merchants, rupees, weight
            ("food", ["Swiggy", "Zomato", "Starbucks", "Chai Point", "Domino's", "Third Wave Coffee"], 90...950, 9),
            ("groceries", ["Blinkit", "BigBasket", "DMart", "Zepto"], 250...2400, 5),
            ("transport", ["Uber", "Rapido", "Namma Metro", "HP Petrol"], 45...1600, 6),
            ("shopping", ["Amazon", "Myntra", "Flipkart", "Decathlon"], 399...4800, 3),
            ("bills", ["Airtel", "BESCOM", "Jio Fiber"], 299...2300, 2),
            ("entertainment", ["Netflix", "BookMyShow", "Spotify"], 119...950, 2),
            ("health", ["Apollo Pharmacy", "Cult.fit"], 180...1900, 1),
            ("travel", ["IRCTC", "IndiGo", "MakeMyTrip"], 850...6900, 1),
            ("education", ["Udemy", "Sapna Books"], 449...1299, 1),
        ]
        let weighted = pool.flatMap { Array(repeating: $0, count: $0.3) }
        let hdfc = Account(name: "HDFC Savings", kind: "bank", ids: ["4521"])
        let card = Account(name: "Amazon ICICI Card", kind: "card", ids: ["7788"])
        let cash = Account(name: "Cash", kind: "cash")
        let cal = Calendar.current, now = Date()
        var out: [Txn] = []
        for back in 0..<90 {
            guard let day = cal.date(byAdding: .day, value: -back, to: now) else { continue }
            for _ in 0..<rand(0...2) {
                let (cat, names, rupees, _) = weighted[rand(0...(weighted.count - 1))]
                var date = cal.date(bySettingHour: rand(8...22), minute: rand(0...59), second: 0, of: day) ?? day
                if date > now { date = now.addingTimeInterval(-Double(rand(60...3000))) }
                let sms = rand(0...4) == 0
                out.append(Txn(amount: Int64(rand(rupees)) * 100 + (rand(0...3) == 0 ? 50 : 0), income: false, categoryId: cat,
                               note: names[rand(0...(names.count - 1))], date: date, account: sms ? (rand(0...2) == 0 ? "7788" : "4521") : nil,
                               accountId: !sms && rand(0...3) == 0 ? cash.id : nil,
                               source: sms ? "sms" : "manual", smsKey: sms ? UUID().uuidString : nil))
            }
        }
        for m in 0..<3 {
            guard let month = cal.date(byAdding: .month, value: -m, to: now),
                  let first = cal.dateInterval(of: .month, for: month)?.start else { continue }
            let salaryDay = cal.date(bySettingHour: 9, minute: 30, second: 0, of: first)!
            let rentDay = cal.date(byAdding: .day, value: 2, to: salaryDay)!
            if salaryDay <= now {
                out.append(Txn(amount: 8_500_000, income: true, categoryId: "salary", note: "Acme Technologies", date: salaryDay,
                               account: "1234", source: "sms", smsKey: UUID().uuidString))
            }
            if rentDay <= now {
                out.append(Txn(amount: 2_500_000, income: false, categoryId: "home", note: "Rent", date: rentDay))
            }
        }
        out.append(Txn(amount: 49_900, income: true, categoryId: "refund", note: "Myntra refund",
                       date: now.addingTimeInterval(-86_400 * 6), account: "5566", source: "sms", smsKey: UUID().uuidString))
        setBudget(6_000_000)
        add(out)
        // Salary (••1234) and the refund (••5566) stay unlinked, so the "new accounts found" flow shows.
        [hdfc, card, cash].forEach(saveAccount)
    }
}
#endif
