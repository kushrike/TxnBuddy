import SwiftUI

/// Update check against the public GitHub releases. This is the only file allowed to touch the network (CI fails the
/// build if another one does). It only downloads the latest release info: ephemeral session, no cookies, a fixed
/// User-Agent, and nothing from the app is sent. iOS doesn't let an app install itself, so this only links to the release.
@MainActor @Observable final class Updater {
    static let shared = Updater()
    struct Release: Decodable {
        let tag_name: String   // v1.0.<build> (see .github/workflows/build.yml)
        let html_url: URL
        var version: String { String(tag_name.dropFirst()) }
        var build: Int { Int(tag_name.split(separator: ".").last ?? "") ?? 0 }
    }

    private(set) var latest: Release?   // only set when newer than this build
    private(set) var busy = false
    private(set) var status: String?

    func check() async {
        busy = true; status = nil
        defer { busy = false; UserDefaults.standard.set(Date.now, forKey: "lastUpdateCheck") }
        var req = URLRequest(url: URL(string: "https://api.github.com/repos/kushrike/TxnBuddy/releases/latest")!)
        req.setValue("TxnBuddy", forHTTPHeaderField: "User-Agent")
        do {
            let (data, _) = try await URLSession(configuration: .ephemeral).data(for: req)
            let r = try JSONDecoder().decode(Release.self, from: data)
            let build = Int(Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "") ?? 0
            latest = r.build > build ? r : nil
            if latest == nil { status = "You're on the latest version" }
        } catch {
            status = "Couldn't reach GitHub"
        }
    }

    /// Once a day when the app opens, if the user turned it on.
    func checkIfDue() async {
        let d = UserDefaults.standard
        guard d.bool(forKey: "autoUpdate"), Date.now.timeIntervalSince(d.object(forKey: "lastUpdateCheck") as? Date ?? .distantPast) > 86_400 else { return }
        await check()
        if let r = latest { Router.shared.show("TxnBuddy \(r.version) is available", action: "View") { UIApplication.shared.open(r.html_url) } }
    }
}
