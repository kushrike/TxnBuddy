// Runnable parser check (no Xcode needed):
//   swiftc -O ios/TxnBuddy/SMSParser.swift ios/Tests/ParserCheck.swift -o /tmp/pc && /tmp/pc shared/sms-samples.tsv
import Foundation

@main struct ParserCheck {
    static func main() throws {
        precondition(SMSParser.fnv1a("") == "cbf29ce484222325")
        precondition(SMSParser.fnv1a("a") == "af63dc4c8601ec8c")
        precondition(SMSParser.fnv1a("foobar") == "85944171f73967e8")

        // Accounts (DESIGN §2.1)
        precondition(Account.normId("XXXX XXXX 4521") == "4521")
        precondition(Account.normId(" **** 1234-5678 ") == "5678")
        precondition(Account.normId(" Paytm  Wallet ") == "paytm wallet")
        precondition(Account.normId("Card 4521") == "card 4521")
        precondition(Account.normId("x1") == nil)
        let hdfc = Account(name: "HDFC", ids: ["4521"]), paytm = Account(name: "Paytm", kind: "wallet", ids: ["paytm"])
        precondition(Account.accountFor("4521", text: "", in: [paytm, hdfc])?.id == hdfc.id)
        precondition(Account.accountFor("9999", text: "Sent to paytmqr281@ptys", in: [paytm, hdfc]) == nil)   // digits only
        precondition(Account.accountFor(nil, text: "Paid Rs 99 from your Paytm Wallet", in: [hdfc, paytm])?.id == paytm.id)

        // Learning from you (DESIGN §5)
        precondition(Learn.mkey("Starbucks #1234") == "starbucks")
        precondition(Learn.mkey("UBER INDIA") == "uber india")
        precondition(Learn.mkey("#1234") == nil)
        precondition(Learn.recall("Starbucks Coffee", in: ["starbucks": "groceries", "starbucks coffee x": "food"]) == "groceries")
        precondition(Learn.recall("Starbuck", in: ["starbucks": "groceries"]) == nil)
        let nb = Learn.Bayes([("Swiggy order", "food"), ("Zomato", "food"), ("Uber ride", "transport"), ("Ola cabs", "transport"), ("Lenskart", "health")])
        precondition(nb.predict("lenskart store") == "health")
        precondition(nb.predict("uber auto") == "transport")
        precondition(nb.predict("random xyz") == nil)

        let path = CommandLine.arguments.dropFirst().first ?? "shared/sms-samples.tsv"
        let lines = try String(contentsOfFile: path, encoding: .utf8).split(separator: "\n").dropFirst()
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = .current
        let now = cal.date(from: DateComponents(year: 2026, month: 10, day: 12, hour: 12))!
        let dayFmt = DateFormatter()
        dayFmt.calendar = cal; dayFmt.timeZone = cal.timeZone; dayFmt.dateFormat = "yyyy-MM-dd"

        var fails = 0
        for (i, line) in lines.enumerated() {
            let f = line.split(separator: "\t", omittingEmptySubsequences: false).map(String.init)
            let (sender, type, amount, account, merchant, category, date, body) = (f[0], f[1], f[2], f[3], f[4], f[5], f[6], f[7])
            let r = SMSParser.parse(body, sender: sender, now: now, calendar: cal)
            let expected: String, got: String
            if type == "none" {
                expected = "none"; got = r == nil ? "none" : "\(r!)"
            } else if let r {
                let minor = Int64((Double(amount)! * 100).rounded())
                expected = [type, "\(minor)", account, merchant, category, date].joined(separator: "|")
                got = [r.income ? "credit" : "debit", "\(r.amountMinor)", r.account ?? "-", r.merchant ?? "-",
                       r.categoryId, r.date.map(dayFmt.string) ?? "-"].joined(separator: "|")
            } else {
                expected = type; got = "nil"
            }
            if expected != got { fails += 1; print("row \(i + 2) FAIL\n  expected \(expected)\n  got      \(got)") }
        }
        print("\(lines.count) rows, \(fails) failures")
        exit(fails == 0 ? 0 : 1)
    }
}
