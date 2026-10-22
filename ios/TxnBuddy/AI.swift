import Foundation
#if canImport(FoundationModels)
import FoundationModels
#endif

/// Apple's built-in on-device model (Foundation Models, iOS 26+, Apple Intelligence devices).
/// Strictly optional: the deterministic SMSParser is the primary path. Nothing here touches the network.
enum OnDeviceAI {
    enum Status {
        case available, downloading, turnedOff, unsupported, needsNewerOS

        var label: String {
            switch self {
            case .available: "Available"
            case .downloading: "Downloading"
            case .turnedOff: "Turned off"
            case .unsupported: "Not supported on this phone"
            case .needsNewerOS: "Needs an update"
            }
        }
    }

    static var status: Status {
        #if canImport(FoundationModels)
        if #available(iOS 26.0, macOS 26.0, *) {
            switch SystemLanguageModel.default.availability {
            case .available: return .available
            case .unavailable(.appleIntelligenceNotEnabled): return .turnedOff
            case .unavailable(.modelNotReady): return .downloading
            case .unavailable: return .unsupported
            }
        }
        #endif
        return .needsNewerOS
    }

    /// Picks one of `choices` for a transaction the keyword table couldn't place. Returns a category id.
    static func categorize(merchant: String?, message: String, choices: [Category]) async -> String? {
        #if canImport(FoundationModels)
        guard #available(iOS 26.0, macOS 26.0, *), status == .available, !choices.isEmpty else { return nil }
        let names = choices.map(\.name)
        let prompt = """
        Pick the single best spending category for this bank transaction. \
        Merchant: \(merchant ?? "unknown"). Message: \(message.prefix(200)). \
        Answer with exactly one of: \(names.joined(separator: ", ")).
        """
        // Guided generation: the model can only emit one of the category names.
        guard let schema = try? GenerationSchema(root: DynamicGenerationSchema(name: "Category", anyOf: names), dependencies: []) else { return nil }
        let answer: String? = await withTimeout(6) {
            let session = LanguageModelSession(instructions: "You sort bank transactions into spending categories.")
            let r = try await session.respond(to: prompt, schema: schema, options: GenerationOptions(temperature: 0))
            return try r.content.value(String.self)
        }
        guard let a = answer?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() else { return nil }
        return choices.first { $0.name.lowercased() == a }?.id
        #else
        return nil
        #endif
    }

    struct Rescue { var amountMinor: Int64; var income: Bool; var merchant: String? }

    /// Extracts a transaction from an SMS the regex parser gave up on.
    static func rescue(message: String) async -> Rescue? {
        #if canImport(FoundationModels)
        guard #available(iOS 26.0, macOS 26.0, *), status == .available else { return nil }
        let root = DynamicGenerationSchema(name: "BankSMS", properties: [
            .init(name: "isTransaction", description: "true only if money actually moved; false for OTPs, offers, reminders or failed payments",
                  schema: DynamicGenerationSchema(type: Bool.self)),
            .init(name: "amount", description: "the amount of money that moved, as a plain number", schema: DynamicGenerationSchema(type: Double.self)),
            .init(name: "isCredit", description: "true if money came into the account, false if it went out", schema: DynamicGenerationSchema(type: Bool.self)),
            .init(name: "merchant", description: "who was paid or who paid; empty if unknown", schema: DynamicGenerationSchema(type: String.self)),
        ])
        guard let schema = try? GenerationSchema(root: root, dependencies: []) else { return nil }
        let text = String(message.prefix(400))
        let content: GeneratedContent? = await withTimeout(6) {
            let session = LanguageModelSession(instructions: "You read bank SMS messages and extract the transaction. Be literal.")
            return try await session.respond(to: "SMS: \(text)", schema: schema, options: GenerationOptions(temperature: 0)).content
        }
        guard let content,
              (try? content.value(Bool.self, forProperty: "isTransaction")) == true,
              let amount = try? content.value(Double.self, forProperty: "amount"), amount > 0, amount < 1e10,
              let credit = try? content.value(Bool.self, forProperty: "isCredit")
        else { return nil }
        let merchant = (try? content.value(String.self, forProperty: "merchant"))?.trimmingCharacters(in: .whitespaces)
        return Rescue(amountMinor: Int64((amount * 100).rounded()), income: credit,
                      merchant: merchant?.isEmpty == false ? String(merchant!.prefix(32)) : nil)
        #else
        return nil
        #endif
    }

    private static func withTimeout<T: Sendable>(_ seconds: Double, _ op: @escaping @Sendable () async throws -> T) async -> T? {
        await withTaskGroup(of: T?.self) { group in
            group.addTask { try? await op() }
            group.addTask { try? await Task.sleep(for: .seconds(seconds)); return nil }
            let first = await group.next() ?? nil
            group.cancelAll()
            return first
        }
    }
}
