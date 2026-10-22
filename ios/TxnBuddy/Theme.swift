import SwiftUI
import LocalAuthentication

// MARK: - Tokens (DESIGN §3). Light/dark values live in Assets.xcassets colour sets.

extension Color {
    static let bg = Color("Bg"), surface = Color("Surface"), surface2 = Color("Surface2")
    static let ink = Color("Ink"), muted = Color("Muted"), brand = Color("AccentColor")
    static let income = Color("Income"), danger = Color("Danger")

    init(hex: String) {
        let v = UInt64(hex.trimmingCharacters(in: CharacterSet(charactersIn: "#")), radix: 16) ?? 0x9CA3AF
        self.init(red: Double((v >> 16) & 0xFF) / 255, green: Double((v >> 8) & 0xFF) / 255, blue: Double(v & 0xFF) / 255)
    }
}

extension ShapeStyle where Self == Color {
    static var bg: Color { .bg }
    static var surface: Color { .surface }
    static var surface2: Color { .surface2 }
    static var ink: Color { .ink }
    static var muted: Color { .muted }
    static var brand: Color { .brand }
    static var income: Color { .income }
    static var danger: Color { .danger }
}

let heroGradient = LinearGradient(colors: [Color(hex: "#5B4CF0"), Color(hex: "#8B5CF6")], startPoint: .topLeading, endPoint: .bottomTrailing)

/// Fixed design sizes that still follow Dynamic Type.
private struct ScaledFont: ViewModifier {
    @ScaledMetric var size: CGFloat
    let weight: Font.Weight
    let rounded: Bool
    init(_ size: CGFloat, _ weight: Font.Weight, _ rounded: Bool, _ style: Font.TextStyle) {
        _size = ScaledMetric(wrappedValue: size, relativeTo: style)
        self.weight = weight
        self.rounded = rounded
    }
    func body(content: Content) -> some View {
        content.font(.system(size: size, weight: weight, design: rounded ? .rounded : .default))
    }
}

extension View {
    func font(size: CGFloat, _ weight: Font.Weight = .regular, rounded: Bool = false, relativeTo style: Font.TextStyle = .body) -> some View {
        modifier(ScaledFont(size, weight, rounded, style))
    }

    func card(padding: CGFloat = 16) -> some View {
        self.padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.surface, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
    }
}

// MARK: - Components

/// Every amount on screen goes through this, so "Hide amounts" is respected everywhere.
struct MoneyText: View {
    let minor: Int64
    var signed: Bool? = nil
    var size: CGFloat = 16
    var weight: Font.Weight = .semibold
    var color: Color? = nil
    @AppStorage("hideAmounts") private var hide = false

    var body: some View {
        let s = Money.string(minor, code: Store.shared.currency, hidden: hide, signed: signed)
        Text(s)
            .font(size: size, weight, rounded: true, relativeTo: size > 30 ? .largeTitle : .body)
            .monospacedDigit()
            .foregroundStyle(color ?? (signed == true ? .income : .ink))
            .contentTransition(.numericText())
            .animation(.snappy, value: minor)
            .accessibilityLabel(hide ? "Amount hidden" : s)
    }
}

struct Badge: View {
    let category: Category
    var size: CGFloat = 40
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        Text(category.emoji)
            .font(.system(size: size * 0.46))
            .frame(width: size, height: size)
            .background(Color(hex: category.color).opacity(scheme == .dark ? 0.24 : 0.16), in: Circle())
            .accessibilityHidden(true)
    }
}

struct TxnRow: View {
    let txn: Txn
    var showDate = false

    var body: some View {
        let cat = Store.shared.category(txn.categoryId)
        let note = txn.note?.trimmingCharacters(in: .whitespaces) ?? ""
        let title = note.isEmpty ? cat.name : note
        let time = showDate ? txn.date.formatted(.dateTime.day().month(.abbreviated)) : txn.date.formatted(date: .omitted, time: .shortened)
        let subtitle = [note.isEmpty ? nil : cat.name, time, txn.isSMS ? "SMS" : nil].compactMap { $0 }.joined(separator: " · ")
        HStack(spacing: 12) {
            Badge(category: cat)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(size: 16, .medium).foregroundStyle(.ink).lineLimit(1)
                Text(subtitle).font(size: 13, relativeTo: .footnote).foregroundStyle(.muted).lineLimit(1)
            }
            Spacer(minLength: 8)
            AmountAndAccount(txn: txn)
        }
        .padding(.vertical, 10)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}

/// Trailing column of a transaction row: amount, with the account under it so neither gets truncated.
struct AmountAndAccount: View {
    let txn: Txn

    var body: some View {
        VStack(alignment: .trailing, spacing: 2) {
            MoneyText(minor: txn.amount, signed: txn.income)
            if let a = Store.shared.accountLabel(txn) {
                Text(a.count > 18 ? String(a.prefix(17)) + "…" : a)
                    .font(size: 13, relativeTo: .footnote)
                    .foregroundStyle(.muted)
                    .lineLimit(1)
            }
        }
        .layoutPriority(1)
    }
}

struct DayGroup: Identifiable {
    let day: Date
    let txns: [Txn]
    var id: Date { day }
}

/// Groups an already date-sorted list by calendar day, keeping order.
func groupByDay(_ txns: [Txn]) -> [DayGroup] {
    let cal = Calendar.current
    var out: [DayGroup] = []
    var current: [Txn] = []
    var day: Date?
    for t in txns {
        let d = cal.startOfDay(for: t.date)
        if d != day, let day { out.append(DayGroup(day: day, txns: current)); current = [] }
        day = d
        current.append(t)
    }
    if let day { out.append(DayGroup(day: day, txns: current)) }
    return out
}

struct DayHeader: View {
    let group: DayGroup

    var body: some View {
        let cal = Calendar.current
        let title = cal.isDateInToday(group.day) ? "Today"
            : cal.isDateInYesterday(group.day) ? "Yesterday"
            : group.day.formatted(.dateTime.weekday(.abbreviated).day().month(.abbreviated))
        let net = group.txns.reduce(Int64(0)) { $0 + ($1.income ? $1.amount : -$1.amount) }
        HStack {
            Text(title)
            Spacer()
            MoneyText(minor: abs(net), signed: net >= 0, size: 13, weight: .semibold, color: .muted)
        }
        .font(size: 13, .semibold, relativeTo: .footnote)
        .foregroundStyle(.muted)
        .textCase(nil)
    }
}

struct SectionTitle: View {
    let title: String
    var action: (String, () -> Void)? = nil

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(title).font(size: 17, .semibold, relativeTo: .headline).foregroundStyle(.ink)
            Spacer()
            if let action {
                Button(action: action.1) {
                    HStack(spacing: 2) { Text(action.0); Image(systemName: "chevron.right").imageScale(.small) }
                        .font(size: 14, .medium)
                }
                .foregroundStyle(.brand)
            }
        }
    }
}

struct ProgressBar: View {
    let value: Double
    var color: Color = .brand
    var track: Color = .surface2
    var height: CGFloat = 6

    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .leading) {
                Capsule().fill(track)
                Capsule().fill(color).frame(width: max(height, g.size.width * min(1, max(0, value))))
            }
        }
        .frame(height: height)
        .accessibilityHidden(true)
    }
}

/// Capsule segmented control with a sliding accent pill.
struct PillPicker<T: Hashable>: View {
    let options: [(T, String)]
    @Binding var selection: T
    @Namespace private var ns

    var body: some View {
        HStack(spacing: 4) {
            ForEach(options, id: \.0) { value, label in
                let on = selection == value
                Button { withAnimation(.snappy(duration: 0.25)) { selection = value } } label: {
                    Text(label)
                        .font(size: 14, .semibold)
                        .lineLimit(1)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 9)
                        .foregroundStyle(on ? Color.white : Color.muted)
                        .background {
                            if on { Capsule().fill(Color.brand).matchedGeometryEffect(id: "pill", in: ns) }
                        }
                        .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(on ? .isSelected : [])
            }
        }
        .padding(4)
        .background(Color.surface2, in: Capsule())
        .sensoryFeedback(.selection, trigger: selection)
    }
}

struct Chip: View {
    let title: String
    var selected = false
    var trailingSymbol: String? = nil

    var body: some View {
        HStack(spacing: 4) {
            Text(title)
            if let trailingSymbol { Image(systemName: trailingSymbol).imageScale(.small) }
        }
        .font(size: 14, .medium)
        .lineLimit(1)
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .foregroundStyle(selected ? Color.white : Color.ink)
        .background(selected ? Color.brand : Color.surface2, in: Capsule())
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

struct PrimaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(size: 17, .semibold)
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity, minHeight: 56)
            .background(Color.brand.opacity(enabled ? 1 : 0.4), in: Capsule())
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(.snappy(duration: 0.15), value: configuration.isPressed)
    }
}

struct PressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.94 : 1)
            .opacity(configuration.isPressed ? 0.85 : 1)
            .animation(.snappy(duration: 0.12), value: configuration.isPressed)
    }
}

struct AddButton: View {
    var body: some View {
        Button { Router.shared.newTxn() } label: {
            Image(systemName: "plus")
                .font(.system(size: 26, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 60, height: 60)
                .background(heroGradient, in: Circle())
                .shadow(color: Color(hex: "#5B4CF0").opacity(0.3), radius: 12, y: 6)
        }
        .buttonStyle(PressStyle())
        .padding(20)
        .accessibilityLabel("Add transaction")
    }
}

/// Emoji in a soft accent circle — empty states, lock screen, welcome rows.
struct EmojiBubble: View {
    let emoji: String
    var size: CGFloat = 88

    var body: some View {
        Text(emoji)
            .font(.system(size: size * 0.45))
            .frame(width: size, height: size)
            .background(Color.brand.opacity(0.12), in: Circle())
            .accessibilityHidden(true)
    }
}

/// The app icon: shared/icon.svg, copied into Logo.imageset by ios/scripts/render-icon.swift.
struct LogoMark: View {
    var size: CGFloat = 96

    var body: some View {
        Image("Logo")
            .resizable()
            .frame(width: size, height: size)
            .accessibilityHidden(true)
    }
}

struct Shake: GeometryEffect {
    var animatableData: CGFloat
    func effectValue(size: CGSize) -> ProjectionTransform {
        ProjectionTransform(CGAffineTransform(translationX: 8 * sin(animatableData * .pi * 4), y: 0))
    }
}

enum Biometry {
    static var name: String {
        let ctx = LAContext()
        _ = ctx.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil)
        switch ctx.biometryType {
        case .faceID: return "Face ID"
        case .touchID: return "Touch ID"
        case .opticID: return "Optic ID"
        default: return "Passcode"
        }
    }
    static var symbol: String {
        switch name {
        case "Face ID": return "faceid"
        case "Touch ID": return "touchid"
        case "Optic ID": return "opticid"
        default: return "lock.fill"
        }
    }
}

func greeting(_ now: Date = Date()) -> String {
    switch Calendar.current.component(.hour, from: now) {
    case 5..<12: return "Good morning"
    case 12..<17: return "Good afternoon"
    default: return "Good evening"
    }
}
