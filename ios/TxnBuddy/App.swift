import SwiftUI
import LocalAuthentication
import AppIntents

@main
struct TxnBuddyApp: App {
    var body: some Scene {
        WindowGroup { RootView() }
    }
}

struct RootView: View {
    @AppStorage("onboarded") private var onboarded = false
    @AppStorage("appLock") private var appLock = false
    @Environment(\.scenePhase) private var phase
    @State private var locked = UserDefaults.standard.bool(forKey: "appLock")
    @State private var prompted = false

    var body: some View {
        ZStack {
            if onboarded {
                MainTabs()
            } else {
                WelcomeView { withAnimation(.smooth) { onboarded = true } }
                    .transition(.opacity)
            }
            if appLock && phase != .active && !locked { PrivacyCover() }
            if locked { LockView(unlock: unlock).transition(.opacity) }
        }
        .tint(.brand)
        .onChange(of: phase) { _, p in
            if p == .background && appLock { locked = true; prompted = false }
            if p == .active && locked && !prompted { prompted = true; unlock() }
        }
        .task { if locked && !prompted { prompted = true; unlock() } }
        .task(id: phase) { if phase == .active { await Updater.shared.checkIfDue() } }
    }

    private func unlock() {
        Task { @MainActor in
            let ctx = LAContext()
            guard ctx.canEvaluatePolicy(.deviceOwnerAuthentication, error: nil) else {
                withAnimation { locked = false }   // passcode was removed from the device: nothing to unlock with
                return
            }
            if (try? await ctx.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: "Unlock TxnBuddy")) == true {
                withAnimation(.smooth) { locked = false }
            }
        }
    }
}

struct MainTabs: View {
    @Bindable private var router = Router.shared

    var body: some View {
        TabView(selection: $router.tab) {
            HomeView()
                .tabItem { Label("Home", systemImage: "house.fill") }
                .tag(Router.Tab.home)
            ActivityView()
                .tabItem { Label("Activity", systemImage: "list.bullet.rectangle.fill") }
                .tag(Router.Tab.activity)
            InsightsView()
                .tabItem { Label("Insights", systemImage: "chart.pie.fill") }
                .tag(Router.Tab.insights)
            SettingsView()
                .tabItem { Label("Settings", systemImage: "gearshape.fill") }
                .tag(Router.Tab.settings)
        }
        .overlay(alignment: .bottom) { ToastView() }
        .sheet(item: $router.editor) { AddSheet(editor: $0) }
        .sheet(isPresented: $router.budgetEditor) { BudgetSheet() }
        .sheet(isPresented: $router.shortcutsGuide) { NavigationStack { ShortcutsGuideView(showsDone: true) } }
        .sheet(isPresented: $router.pasteSheet) { PasteSheet() }
        .sheet(isPresented: $router.accountsSheet) {
            NavigationStack { AccountsView(showsDone: true) }
                .overlay(alignment: .bottom) { ToastView(bottom: 24) }   // the tab-level toast sits under this sheet
        }
    }
}

struct ToastView: View {
    var bottom: CGFloat = 72          // clears the tab bar
    @Bindable private var router = Router.shared

    var body: some View {
        ZStack {
            if let t = router.toast {
                HStack(spacing: 12) {
                    Text(t.text).lineLimit(2)
                    Spacer(minLength: 0)
                    if let undo = t.undo {
                        Button(t.action) { undo(); withAnimation { router.toast = nil } }
                            .font(size: 15, .bold)
                            .foregroundStyle(Color.brand)
                            .fixedSize()
                    }
                }
                .font(size: 15, .medium)
                .foregroundStyle(Color.bg)
                .padding(.horizontal, 20)
                .frame(minHeight: 50)
                .background(Color.ink, in: Capsule())
                .padding(.horizontal, 20)
                .padding(.bottom, bottom)
                .transition(.move(edge: .bottom).combined(with: .opacity))
                .task(id: t.id) {
                    try? await Task.sleep(for: .seconds(4))
                    if router.toast?.id == t.id { withAnimation { router.toast = nil } }
                }
            }
        }
        .animation(.spring(duration: 0.35), value: router.toast?.id)
    }
}

struct PrivacyCover: View {
    var body: some View {
        ZStack {
            Color.bg.ignoresSafeArea()
            LogoMark(size: 72)
        }
    }
}

struct LockView: View {
    let unlock: () -> Void

    var body: some View {
        VStack(spacing: 14) {
            Spacer()
            EmojiBubble(emoji: "🔒")
            Text("TxnBuddy is locked")
                .font(size: 22, .bold, relativeTo: .title2)
                .foregroundStyle(.ink)
                .padding(.top, 6)
            Text("Unlock with \(Biometry.name) to see your money.")
                .font(size: 15)
                .foregroundStyle(.muted)
                .multilineTextAlignment(.center)
            Spacer()
            Button(action: unlock) { Label("Unlock", systemImage: Biometry.symbol) }
                .buttonStyle(PrimaryButtonStyle())
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.bg.ignoresSafeArea())
    }
}

struct WelcomeView: View {
    let done: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            Spacer(minLength: 24)
            LogoMark(size: 96)
                .shadow(color: Color(hex: "#5B4CF0").opacity(0.3), radius: 24, y: 12)
            Text("TxnBuddy")
                .font(size: 34, .bold, rounded: true, relativeTo: .largeTitle)
                .foregroundStyle(.ink)
                .padding(.top, 24)
            Text("Track spending without giving it away.")
                .font(size: 17)
                .foregroundStyle(.muted)
                .multilineTextAlignment(.center)
                .padding(.top, 6)
            VStack(alignment: .leading, spacing: 22) {
                feature("🔒", "Stays on your phone", "No account, no cloud, no trackers.")
                feature("⚡️", "Logs bank SMS via Shortcuts", "Parsed on-device, never uploaded.")
                feature("📊", "Insights that make sense", "Daily, weekly, monthly, yearly.")
            }
            .padding(.top, 44)
            Spacer(minLength: 24)
            Button("Get started", action: done)
                .buttonStyle(PrimaryButtonStyle())
            Text("No sign-up. Everything stays on this iPhone.")
                .font(size: 13, relativeTo: .footnote)
                .foregroundStyle(.muted)
                .padding(.top, 12)
        }
        .padding(24)
        .frame(maxWidth: 520)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.bg.ignoresSafeArea())
    }

    private func feature(_ emoji: String, _ title: String, _ caption: String) -> some View {
        HStack(spacing: 16) {
            EmojiBubble(emoji: emoji, size: 48)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(size: 16, .semibold).foregroundStyle(.ink)
                Text(caption).font(size: 14).foregroundStyle(.muted)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

// MARK: - Shortcuts: iOS gives no app access to the SMS inbox, so a Message automation hands each bank SMS to this intent.

struct LogBankSMSIntent: AppIntent {
    static let title: LocalizedStringResource = "Log Bank SMS"
    static let description = IntentDescription("Reads a bank message on this iPhone and logs the debit or credit in TxnBuddy. Nothing leaves your device.")
    static let openAppWhenRun = false

    @Parameter(title: "Message", description: "The text of the bank SMS")
    var text: String

    static var parameterSummary: some ParameterSummary { Summary("Log \(\.$text) in TxnBuddy") }

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        let line = await Store.shared.logFromShortcut(text)
        return .result(dialog: IntentDialog(stringLiteral: line))
    }
}

struct TxnBuddyShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: LogBankSMSIntent(),
                    phrases: ["Log a bank SMS in \(.applicationName)"],
                    shortTitle: "Log Bank SMS",
                    systemImageName: "message.badge.filled.fill")
    }
}
