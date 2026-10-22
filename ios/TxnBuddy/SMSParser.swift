import Foundation

/// Deterministic bank-SMS parser. Implements DESIGN.md §5 exactly (shared test vectors in shared/sms-samples.tsv).
/// Foundation-only so it can be checked with plain `swiftc` (see Tests/ParserCheck.swift).
enum SMSParser {
    struct Result: Equatable {
        var amountMinor: Int64
        var income: Bool
        var account: String?
        var merchant: String?
        var categoryId: String
        var date: Date?          // start of the local calendar day found in the body, if any
        var smsKey: String
        var viaVPA: Bool
    }

    // MARK: Public API

    static func parse(_ body: String, sender: String? = nil, now: Date = Date(), calendar: Calendar = .current) -> Result? {
        let text = normalize(body)
        let lower = text.lowercased()
        if !senderAllowed(sender) || matches(reject, lower) { return nil }

        let k = lower.replacingOccurrences(of: "credit card", with: "card").replacingOccurrences(of: "debit card", with: "card")
        let income: Bool
        if let m = first(tier1, k) {
            income = tier1Income.contains(group(m, 1, in: k) ?? "")
        } else if let m = first(tier2, k) {
            income = group(m, 1, in: k) == "cr"
        } else { return nil }

        guard let amount = amount(in: text) else { return nil }

        var account: String?
        if let m = first(acct, text), let digits = group(m, 1, in: text) { account = String(digits.suffix(4)) }

        let (merchant, viaVPA) = merchant(in: text, income: income)
        let cat = category(lower: lower, merchant: merchant, income: income, viaVPA: viaVPA)
        let date = date(in: text, now: now, calendar: calendar)
        return Result(amountMinor: amount, income: income, account: account, merchant: merchant,
                      categoryId: cat, date: date, smsKey: fnv1a(lower), viaVPA: viaVPA)
    }

    /// True when the parser gave up at direction/amount but the message still looks financial (AI rescue gate).
    static func isRescueCandidate(_ body: String, sender: String? = nil) -> Bool {
        let text = normalize(body)
        guard senderAllowed(sender), !matches(reject, text.lowercased()) else { return false }
        return first(amountPatterns[0], text) != nil || first(amountPatterns[1], text) != nil
    }

    /// Keyword category (DESIGN §5.7). Exposed so AI-rescued messages get the same treatment.
    static func category(lower: String, merchant: String?, income: Bool, viaVPA: Bool) -> String {
        if income {
            if matches(salaryRx, lower) { return "salary" }
            if matches(refundRx, lower) { return "refund" }
            return "otherIncome"
        }
        let hay = lower + " " + (merchant ?? "").lowercased()
        for (cat, words) in keywordMatchers {
            for (w, wordRx) in words where wordRx.map({ matches($0, hay) }) ?? hay.contains(w) { return cat }
        }
        return viaVPA ? "transfer" : "other"
    }

    static func normalize(_ body: String) -> String {
        let ns = body as NSString
        return ws.stringByReplacingMatches(in: body, range: NSRange(location: 0, length: ns.length), withTemplate: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Dedupe key: FNV-1a 64 over the normalized, lowercased body.
    static func key(for body: String) -> String { fnv1a(normalize(body).lowercased()) }

    static func fnv1a(_ s: String) -> String {
        var h: UInt64 = 0xcbf29ce484222325
        for b in s.utf8 { h ^= UInt64(b); h = h &* 0x100000001b3 }
        return String(format: "%016llx", h)
    }

    // MARK: Steps

    private static func senderAllowed(_ sender: String?) -> Bool {
        guard let sender else { return true }
        return sender.filter(\.isNumber).count < 10
    }

    private static func amount(in text: String) -> Int64? {
        let ns = text as NSString
        for p in amountPatterns {
            for m in p.matches(in: text, range: NSRange(location: 0, length: ns.length)) {
                guard let raw = group(m, 1, in: text),
                      let dec = Decimal(string: raw.replacingOccurrences(of: ",", with: ""), locale: Locale(identifier: "en_US_POSIX"))
                else { continue }
                var scaled = dec * 100, rounded = Decimal()
                NSDecimalRound(&rounded, &scaled, 0, .plain)
                let minor = NSDecimalNumber(decimal: rounded).int64Value
                let start = m.range.location
                let pre = ns.substring(with: NSRange(location: max(0, start - 25), length: min(25, start))).lowercased()
                if minor > 0 && !balanceWords.contains(where: pre.contains) { return minor }
            }
        }
        return nil
    }

    private static func merchant(in text: String, income: Bool) -> (String?, Bool) {
        let ns = text as NSString
        for m in vpa.matches(in: text, range: NSRange(location: 0, length: ns.length)) {
            guard let localPart = group(m, 1, in: text) else { continue }
            let loc = collapse(localPart.lowercased().filter { !$0.isNumber }
                .map { ".-_".contains($0) ? " " : String($0) }.joined())
            if loc.filter(\.isLetter).count >= 3 && !aggregators.contains(where: loc.hasPrefix) {
                return (loc.split(separator: " ").map { $0.prefix(1).uppercased() + $0.dropFirst() }.joined(separator: " "), true)
            }
        }
        let rules = income ? [r3Income] : [r2, r3Expense, r4]
        for r in rules {
            for m in r.matches(in: text, range: NSRange(location: 0, length: ns.length)) {
                guard let cand = group(m, 1, in: text) else { continue }
                let firstWord = cand.split(separator: " ").first.map { $0.lowercased() } ?? ""
                if deny.contains(firstWord) { continue }
                return (clean(cand), false)
            }
        }
        return (nil, false)
    }

    private static func clean(_ c: String) -> String {
        var s = collapse(c)
        while let last = s.last, "-/#*_& ".contains(last) { s.removeLast() }
        if !s.contains(where: { ("a"..."z").contains($0) }) {
            s = s.split(separator: " ", omittingEmptySubsequences: false).map { w -> String in
                guard !w.isEmpty, w.allSatisfy(\.isLetter) else { return String(w) }
                return w.prefix(1).uppercased() + w.dropFirst().lowercased()
            }.joined(separator: " ")
        }
        return String(s.prefix(32))
    }

    private static func date(in text: String, now: Date, calendar cal: Calendar) -> Date? {
        let today = cal.startOfDay(for: now)
        guard let lo = cal.date(byAdding: .day, value: -400, to: today),
              let hi = cal.date(byAdding: .day, value: 1, to: today) else { return nil }
        let year = cal.component(.year, from: now)
        func make(_ y: Int, _ m: Int, _ d: Int) -> Date? {
            guard (1...12).contains(m), (1...31).contains(d),
                  let date = cal.date(from: DateComponents(year: y, month: m, day: d)),
                  cal.component(.month, from: date) == m, cal.component(.day, from: date) == d else { return nil }
            return date
        }
        func ok(_ d: Date?) -> Date? { d.flatMap { $0 >= lo && $0 <= hi ? $0 : nil } }
        func yy(_ s: String) -> Int { s.count == 2 ? 2000 + Int(s)! : Int(s)! }
        func mon(_ s: String) -> Int { months.firstIndex(of: String(s.lowercased().prefix(3)))! + 1 }
        let ns = text as NSString
        func each(_ r: NSRegularExpression, _ f: ([String?]) -> Date?) -> Date? {
            for m in r.matches(in: text, range: NSRange(location: 0, length: ns.length)) {
                let g = (0..<m.numberOfRanges).map { group(m, $0, in: text) }
                if let d = ok(f(g)) { return d }
            }
            return nil
        }
        if let d = each(p1, { make(Int($0[1]!)!, Int($0[2]!)!, Int($0[3]!)!) }) { return d }
        if let d = each(p2, { g in
            let mo = mon(g[2]!), day = Int(g[1]!)!
            if let y = g[3] { return make(yy(y), mo, day) }
            if let d = make(year, mo, day), d > hi { return make(year - 1, mo, day) }
            return make(year, mo, day)
        }) { return d }
        if let d = each(p3, { make(Int($0[3]!)!, mon($0[1]!), Int($0[2]!)!) }) { return d }
        return each(p4, { g in
            var d = Int(g[1]!)!, m = Int(g[2]!)!
            let y = yy(g[3]!)
            if m > 12 { swap(&d, &m) }
            if let r = ok(make(y, m, d)) { return r }
            return ok(make(y, d, m))
        })
    }

    // MARK: Regex helpers

    private static func rx(_ p: String) -> NSRegularExpression { try! NSRegularExpression(pattern: p, options: [.caseInsensitive]) }
    private static func matches(_ r: NSRegularExpression, _ s: String) -> Bool { first(r, s) != nil }
    private static func first(_ r: NSRegularExpression, _ s: String) -> NSTextCheckingResult? {
        r.firstMatch(in: s, range: NSRange(location: 0, length: (s as NSString).length))
    }
    private static func group(_ m: NSTextCheckingResult, _ i: Int, in s: String) -> String? {
        let r = m.range(at: i)
        return r.location == NSNotFound ? nil : (s as NSString).substring(with: r)
    }
    private static func collapse(_ s: String) -> String {
        s.split(separator: " ", omittingEmptySubsequences: true).joined(separator: " ")
    }

    // MARK: Patterns (DESIGN §5)

    private static let ws = rx(#"\s+"#)
    private static let reject = rx(#"\b(otp|one[- ]?time password|verification code|will be (?:debited|credited|deducted|charged)|is due|are due|due (?:on|by|date)|requested|request money|collect request|pre-?approved|failed|declined|unsuccessful|insufficient|reminder)\b"#)
    private static let tier1 = rx(#"\b(debited|debit of|spent|paid|sent|withdrawn|purchased?|charged|deducted|transferred|credited|received|deposited|refund(?:ed)?|cashback|reversed)\b"#)
    private static let tier1Income: Set<String> = ["credited", "received", "deposited", "refund", "refunded", "cashback", "reversed"]
    private static let tier2 = rx(#"\b(txn|transaction|dr|cr)\b"#)
    private static let num = #"([0-9]+(?:,[0-9]+)*(?:\.[0-9]{1,2})?)"#
    private static let amountPatterns = [
        rx(#"(?:\brs\.?|\binr|₹|\busd|\$|\beur|€|\bgbp|£|\baed|\bsgd)\s*"# + num),
        rx(#"\b"# + num + #"\s*(?:rs|inr|usd|eur|gbp|aed)\b"#),
        rx(#"\b(?:debited|credited|spent|paid|sent|received|withdrawn|deposited|transferred)\s+(?:by|with|for|of)?\s*(?:rs\.?|inr|₹)?\s*"# + num + #"\b"#),
    ]
    private static let balanceWords = ["bal", "avl", "available", "limit", "lmt", "outstanding", "due"]
    private static let acct = rx(#"\b(?:account|acct|a/c|card|ac)\s*(?:no\.?|number|num|ending(?:\s+(?:with|in))?)?\s*[:\-#]?\s*[x*•]*(\d{3,})"#)
    private static let cand = #"([a-z][a-z0-9\&' \-/#*_]{0,39}?)"#
    private static let stop = #"(?=\s+(?:on|ref\w*|via|upi|using|for|avl|bal|is|was|txn|dated|at|from|to|and|thru|by|with|in|of|has|not|card|a/c|rs|inr)\b|\s*[.,;:()!?]|\s*$)"#
    private static let vpa = rx(#"\b([a-z0-9][a-z0-9.\-_]+)@([a-z][a-z0-9]+)\b"#)
    private static let aggregators = ["paytmqr", "bharatpe", "phonepe", "gpay", "razorpay", "rzp", "cashfree", "upi"]
    private static let r2 = rx(#"[;.]\s*"# + cand + #"\s+credited\b"#)
    private static let r3Expense = rx(#"\b(?:at|to|towards|with)\s+"# + cand + stop)
    private static let r3Income = rx(#"\bfrom\s+"# + cand + stop)
    private static let r4 = rx(#"\bon\s+"# + cand + stop)
    private static let deny: Set<String> = Set("your you a/c ac acct account card my rs rs. inr usd bank vpa upi date self beneficiary mobile number jan feb mar apr may jun jul aug sep oct nov dec".split(separator: " ").map(String.init))
    private static let months = ["jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"]
    private static let monR = "(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)"
    private static let p1 = rx(#"\b(\d{4})-(\d{2})-(\d{2})"#)
    private static let p2 = rx(#"\b(\d{1,2})[-/ ]?"# + monR + #"[a-z]*[-/ ,]*(\d{2,4})?\b"#)
    private static let p3 = rx(#"\b"# + monR + #"[a-z]*\.?\s+(\d{1,2}),?\s+(\d{4})\b"#)
    private static let p4 = rx(#"\b(\d{1,2})[-/.](\d{1,2})[-/.](\d{2,4})\b"#)

    private static let salaryRx = rx("salary|payroll|stipend")
    private static let refundRx = rx("refund|cashback|reversal|reversed")
    /// Keywords of ≤ 4 chars match as whole words, longer ones as substrings.
    private static let keywordMatchers = keywords.map { cat, words in
        (cat, words.map { w in (w, w.count <= 4 ? rx("\\b" + NSRegularExpression.escapedPattern(for: w) + "\\b") : nil) })
    }
    private static let keywords: [(String, [String])] = [
        ("groceries", ["instamart", "blinkit", "zepto", "bigbasket", "grofers", "dmart", "jiomart", "grocery", "supermarket", "reliance fresh", "natures basket", "walmart", "costco", "whole foods", "kroger", "safeway", "trader joe"]),
        ("food", ["uber eats", "swiggy", "zomato", "restaurant", "cafe", "coffee", "chai", "starbucks", "domino", "pizza", "mcdonald", "kfc", "burger", "subway", "eatsure", "bakery", "food", "dining", "haldiram", "barbeque", "doordash"]),
        ("transport", ["uber", "ola", "rapido", "metro", "fuel", "petrol", "diesel", "hpcl", "bpcl", "iocl", "indian oil", "shell", "fastag", "parking", "toll", "namma yatri", "lyft"]),
        ("travel", ["irctc", "makemytrip", "goibibo", "cleartrip", "yatra", "ixigo", "redbus", "indigo", "air india", "vistara", "akasa", "spicejet", "airlines", "airways", "hotel", "oyo", "airbnb", "booking.com", "expedia", "agoda"]),
        ("shopping", ["amazon", "flipkart", "myntra", "ajio", "meesho", "nykaa", "tata cliq", "croma", "reliance digital", "decathlon", "ikea", "lifestyle", "westside", "zara", "h&m", "uniqlo"]),
        ("bills", ["electricity", "bescom", "tneb", "msedcl", "tata power", "airtel", "jio", "vodafone", "bsnl", "broadband", "recharge", "bill", "dth", "tata play", "gas", "water", "insurance", "lic", "premium", "emi", "loan"]),
        ("entertainment", ["netflix", "spotify", "prime video", "hotstar", "jiocinema", "bookmyshow", "pvr", "inox", "youtube", "apple music", "gaana", "steam", "playstation", "xbox", "disney", "sonyliv", "zee5", "movie", "cinema"]),
        ("health", ["pharmacy", "apollo", "medplus", "1mg", "pharmeasy", "netmeds", "hospital", "clinic", "diagnostic", "practo", "cultfit", "cult.fit", "gym", "doctor", "lab", "medical", "chemist"]),
        ("home", ["rent", "landlord", "nobroker", "housing", "maintenance", "society", "urban company", "furniture", "pepperfry"]),
        ("education", ["school", "college", "university", "tuition", "udemy", "coursera", "byju", "unacademy", "books", "course", "fees"]),
        ("transfer", ["neft", "imps", "rtgs", "transfer", "trf", "atm", "cash"]),
    ]
}

// MARK: - Accounts (DESIGN §2.1). Kept here, Foundation-only, so Tests/ParserCheck.swift checks normId/accountFor too.

struct Account: Codable, Identifiable, Hashable {
    var id = UUID().uuidString
    var name: String
    var kind = "bank"            // bank | card | wallet | cash
    var ids: [String] = []       // last 4 digits, or lowercased text IDs (wallet, UPI ID, SMS header)

    /// User input → stored identifier: "XXXX XXXX 4521" → "4521", " Paytm  Wallet" → "paytm wallet", too short → nil.
    static func normId(_ s: String) -> String? {
        let t = s.trimmingCharacters(in: .whitespacesAndNewlines)
        let digits = t.filter { $0.isASCII && $0.isNumber }
        if digits.count >= 4 && !t.contains(where: { $0.isLetter && $0 != "x" && $0 != "X" }) { return String(digits.suffix(4)) }
        let text = t.lowercased().split(whereSeparator: \.isWhitespace).joined(separator: " ")
        return text.filter { $0 != " " }.count >= 3 ? text : nil
    }

    /// Matching at import. A 4-digit identifier matches digits only (an HDFC debit "to paytmqr…" must not land in
    /// a "paytm" wallet); otherwise the first account with a text ID inside `text` (iOS: the SMS body).
    static func accountFor(_ identifier: String?, text: String, in accounts: [Account]) -> Account? {
        if let identifier, identifier.count == 4, isDigits(identifier) { return accounts.first { $0.ids.contains(identifier) } }
        let lower = text.lowercased()
        return accounts.first { $0.ids.contains { !(isDigits($0) && $0.count == 4) && lower.contains($0) } }
    }

    /// "4521" → "••4521"; text IDs as they are.
    static func display(_ id: String) -> String { isDigits(id) ? "••" + id : id }
    static func isDigits(_ s: String) -> Bool { !s.isEmpty && s.allSatisfy { $0.isASCII && $0.isNumber } }
}

// MARK: - Learning from you (DESIGN §5). Foundation-only so Tests/ParserCheck.swift checks it too.

enum Learn {
    private static let stop: Set<String> = ["the", "and", "for", "with", "from", "upi", "pvt", "ltd", "limited", "private", "india",
                                            "payment", "payments", "txn", "ref", "to", "of", "at", "by", "in", "on"]

    /// "Starbucks #1234" → "starbucks"; nil when no letters are left.
    static func mkey(_ s: String) -> String? {
        let k = s.lowercased().split { !$0.isLetter }.joined(separator: " ")
        return k.isEmpty ? nil : k
    }

    /// "starbucks" covers "Starbucks" and "Starbucks Coffee": the same key, or the start of it word by word.
    static func covers(_ key: String, _ note: String?) -> Bool {
        guard let k = note.flatMap(mkey) else { return false }
        return k == key || k.hasPrefix(key + " ")
    }

    /// The category learned for this note: its own key, else the longest learned key it starts with.
    static func recall(_ note: String?, in learned: [String: String]) -> String? {
        guard let k = note.flatMap(mkey) else { return nil }
        return learned[k] ?? learned.filter { k.hasPrefix($0.key + " ") }.max { $0.key.count < $1.key.count }?.value
    }

    static func tokens(_ s: String) -> [String] {
        s.lowercased().split { !($0.isLetter || $0.isNumber) }.map(String.init)
            .filter { $0.count >= 2 && !$0.allSatisfy(\.isNumber) && !stop.contains($0) }
    }

    /// Multinomial naive Bayes over (note, categoryId) pairs of one kind, add-0.1 smoothing.
    struct Bayes {
        private var docsPer: [String: Int] = [:], totals: [String: Int] = [:]
        private var counts: [String: [String: Int]] = [:]       // category → token → count
        private var vocab: Set<String> = []
        private var n = 0

        init(_ docs: [(String, String)]) {
            for (note, cat) in docs {
                let ts = Learn.tokens(note)
                guard !ts.isEmpty else { continue }
                n += 1
                docsPer[cat, default: 0] += 1
                totals[cat, default: 0] += ts.count
                for t in ts { counts[cat, default: [:]][t, default: 0] += 1; vocab.insert(t) }
            }
        }

        /// Best category if its posterior is ≥ 0.6; nil when no input token was ever seen.
        func predict(_ s: String) -> String? {
            let ts = Learn.tokens(s).filter(vocab.contains)
            guard !ts.isEmpty else { return nil }
            let v = Double(vocab.count)
            let scores = docsPer.map { c, d in
                let m = counts[c] ?? [:], tot = Double(totals[c] ?? 0)
                return (c, log(Double(d) / Double(n)) + ts.reduce(0) { $0 + log((Double(m[$1] ?? 0) + 0.1) / (tot + 0.1 * v)) })
            }
            guard let best = scores.max(by: { $0.1 < $1.1 }) else { return nil }
            return 1 / scores.reduce(0) { $0 + exp($1.1 - best.1) } >= 0.6 ? best.0 : nil
        }
    }
}
