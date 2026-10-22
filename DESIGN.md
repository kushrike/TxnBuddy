# TxnBuddy — shared design & behaviour spec

One spec, two native apps (`ios/` SwiftUI, `android/` Jetpack Compose). Both must look and behave the same.
Main goals, in order: **privacy**, **UI that is quick to use**, **lightweight**.

---

## 1. Privacy rules (non‑negotiable)

| Rule | iOS | Android |
|---|---|---|
| Network only for updates | `Updater.swift` is the only file with networking code (CI greps for it). It GETs the latest GitHub release info with an ephemeral session and a fixed User-Agent; nothing from the app is sent | `INTERNET` is used only by `Updater.kt` (CI greps for it, and checks the APK's permissions against an exact list). It GETs the release info and the APK, nothing is sent. ML Kit's telemetry upload backend is removed from the manifest, and `ACCESS_NETWORK_STATE` stays removed |
| No cloud backup | Store file `isExcludedFromBackup = true` | `allowBackup="false"`, `dataExtractionRules`/`fullBackupContent` exclude everything |
| Data at rest | App sandbox + Data Protection (default class) | App-private storage (device FBE) |
| SMS | Raw SMS body is **never stored**; only extracted fields + an FNV hash for dedupe | same |
| AI | Built-in on-device model only: Apple **Foundation Models** (iOS 26+, Apple Intelligence devices) | **Gemini Nano** via ML Kit GenAI Prompt API (`com.google.mlkit:genai-prompt`) through AICore |
| App switcher / screenshots | Privacy cover when scene is not `.active` (if App Lock on) | `FLAG_SECURE` when App Lock on |
| Third-party deps | **Zero** | Only AndroidX (compose, activity) + ML Kit genai-prompt |

AI is an *enhancement*. The deterministic parser (§5) is the primary path, and the app is fully functional with AI unavailable.

---

## 2. Data model (also the backup file format, so a backup moves between iOS and Android)

```json
{
  "version": 1,
  "settings": { "currency": "INR", "monthlyBudget": 3000000 },
  "categories": [
    { "id": "food", "name": "Food", "emoji": "🍔", "color": "#FF8A3D", "income": false }
  ],
  "transactions": [
    { "id": "6F1C…uuid", "amount": 25000, "income": false, "categoryId": "food",
      "note": "Swiggy", "date": "2026-10-12T13:45:00Z", "account": "4521", "accountId": "9A2E…uuid",
      "source": "sms", "smsKey": "a1b2c3d4e5f60718" }
  ],
  "accounts": [
    { "id": "9A2E…uuid", "name": "HDFC Savings", "kind": "bank", "ids": ["4521", "hdfcbk"] }
  ]
}
```
* `amount`: **integer minor units** (paise/cents), always > 0. Direction comes from `income`.
* `date`: ISO‑8601 UTC, seconds precision. `source`: `manual` | `sms`.
* `account`: the **source identifier** seen in the SMS (see §2.1), or `null` (always `null` for manual entries). `accountId`: the user's account this transaction belongs to, or `null`. Both optional when reading (older backups have neither, and no `accounts` array); always written.
* `monthlyBudget`: minor units, `0` = not set.
* Storage = one JSON file in app-private storage, loaded fully into memory, rewritten on change. (`ponytail:` whole-file JSON; move to SQLite past ~50k txns.)
* UI prefs (app lock, hide amounts, AI toggle, last SMS scan time, onboarding done) live in UserDefaults / SharedPreferences, **not** in the backup.

### Default categories (order = display order)
| id | name | emoji | color | income |
|---|---|---|---|---|
| food | Food | 🍔 | #FF8A3D | no |
| groceries | Groceries | 🛒 | #22C55E | no |
| transport | Transport | 🚕 | #3B82F6 | no |
| shopping | Shopping | 🛍️ | #EC4899 | no |
| bills | Bills | 💡 | #F59E0B | no |
| entertainment | Fun | 🎬 | #8B5CF6 | no |
| health | Health | 💊 | #EF4444 | no |
| home | Home | 🏠 | #14B8A6 | no |
| travel | Travel | ✈️ | #06B6D4 | no |
| education | Education | 📚 | #6366F1 | no |
| transfer | Transfers | 💸 | #64748B | no |
| other | Other | 📦 | #9CA3AF | no |
| salary | Salary | 💼 | #10B981 | yes |
| refund | Refunds | 🔁 | #84CC16 | yes |
| otherIncome | Other income | 💰 | #0EA5E9 | yes |

Users can add custom categories (emoji + name + colour from the palette above + kind). Built-ins can be renamed but not deleted. Deleting a custom category moves its transactions to `other` / `otherIncome`.

### 2.1 Accounts & source matching
An **account** is where money moves in or out: `{ id, name, kind, ids }`.
* `kind`: `bank` 🏦 · `card` 💳 · `wallet` 👛 · `cash` 💵 (badge = kind emoji on accentSoft).
* `ids`: identifiers that tie SMS to this account. Each is either **4 digits** (last 4 of a card or account number; nothing more is ever stored) or a **text ID** (wallet name/ID, UPI ID, bank SMS header), stored lowercased.
* An identifier belongs to **one** account: saving an account removes its ids from every other account.

**Normalising user input** `normId(s)`: trim. If it has ≥ 4 digits and no letters other than `x`/`X` (spaces, `-`, `*`, `•`, `x` allowed, e.g. `XXXX XXXX 4521`) → its last 4 digits. Else, if it has ≥ 3 non-space chars → lowercased, spaces collapsed. Else invalid. Caption under the field: "Only the last 4 digits are stored."

**Source identifier** (`txn.account`), set at SMS import:
1. The parser's `account` (last 4 digits), if any.
2. Else, **Android only** (iOS has no sender): the sender header's brand part: uppercase the sender, split on `-`, take the first part that is only letters A–Z and 4–8 long (`VM-PAYTMB-S` → `PAYTMB`, `AX-HDFCBK` → `HDFCBK`). Numeric senders → none.
3. Else `null`.
Display: 4 digits → `••4521`; otherwise the identifier as is (`PAYTMB`).

**Matching at import** (`accountFor(identifier, text)`, `text` = lowercase of sender + " " + body; iOS: body):
* identifier is 4 digits → the account whose `ids` contains it. **Only** digit matching in this case (an HDFC debit paid "to paytmqr…" must not land in a "paytm" wallet).
* otherwise → the first account (list order) with a text id contained in `text`.
* Result → `txn.accountId` (else `null`).

**Unlinked sources** = distinct non-null `txn.account` values among transactions with `accountId == null`, with count and total, sorted by count desc.

**Store operations**
* `saveAccount(a)`: upsert; strip `a.ids` from other accounts; then every transaction with `accountId == null` whose `account` equals one of `a.ids` (case-insensitive) gets `accountId = a.id`.
* `link(identifier, accountId)`: add the identifier to that account's `ids`, then `saveAccount`.
* `deleteAccount(a)`: remove it; its transactions get `accountId = null` (their identifier stays, so they show up as unlinked again).
* Saving a transaction in the Add/Edit sheet with an `accountId` whose `account` identifier is in **no** account's ids → `link(identifier, accountId)` and toast "Linked ••4521 to HDFC Savings".

Accounts are part of the backup. Device-only prefs: `lastAccountId` (default account in the Add sheet), `dismissedSources` (set of identifiers hidden from the Home card).

---

## 3. Design tokens

**Feel:** calm, soft, roomy. Big friendly numbers, rounded type, emoji category badges in tinted circles, one vivid brand gradient. No heavy shadows; surfaces separate by tone.

### Colour
| token | light | dark |
|---|---|---|
| bg | #F5F5F8 | #0B0B0F |
| surface | #FFFFFF | #17171D |
| surface2 (chips, keys, fields) | #EDEDF2 | #23232B |
| text | #111116 | #F2F2F7 |
| textMuted | #6B6B78 | #9A9AA8 |
| accent | #5B4CF0 | #8A7DFF |
| accentSoft | accent @ 12% | accent @ 18% |
| income (positive) | #12A26B | #34D399 |
| danger (over budget, spend up) | #E5484D | #FF6B6B |
| heroGradient | #5B4CF0 → #8B5CF6 (top-leading → bottom-trailing) | same |

Category badge: circle, fill = category colour @ 16% (dark: 24%), emoji centred.

### Type
* iOS: SF Pro; **all amounts** use `.rounded` design + `monospacedDigit()`. Android: default sans; amounts `FontWeight.Bold` + `fontFeatureSettings = "tnum"`.
* Scale: hero amount 44/bold · sheet amount 56/bold · screen title 28/bold · section title 17/semibold · body 16 · caption 13 · micro 11.

### Shape & spacing
* Radius: hero/cards 24 · rows/fields 16 · keypad keys 18 · chips & buttons = capsule.
* Spacing 4/8/12/16/20/24, screen horizontal padding 20. Min touch target 44pt / 48dp.
* Badge sizes: 40 (rows), 52 (category grid), 32 (compact).

### Money formatting
* Locale-aware, currency from settings (default = device-locale currency, fallback INR).
* Hide the fraction when it is `.00` (₹12,340), else show 2 decimals (₹12,340.50).
* Expense rows show `−₹320` in `text`; income rows `+₹50,000` in `income`.
* Chart axes use compact notation (₹1.2K, ₹3.4L/₹3.4M per locale).
* **Hide amounts** mode: every amount renders as `₹••••` (the add sheet is the only exception).

### Motion & feedback
Numeric content transitions on amount changes; a light haptic on keypad taps, category select, and save; spring sheet presentation; charts animate in. Respect Reduce Motion.

---

## 4. Screens

Navigation: bottom tab bar with **Home · Activity · Insights · Settings** and a floating **+** button (60pt accent circle, bottom-trailing, above the tab bar) on Home and Activity.

### 4.1 Welcome (first launch only)
App icon (96pt) · "TxnBuddy" · "Track spending without giving it away."
Three rows (emoji badge + title + caption):
* 🔒 **Stays on your phone**: No account, no cloud, no trackers.
* ⚡️ **Reads bank SMS for you**: Parsed on-device, never uploaded. (iOS: "Log bank SMS via Shortcuts")
* 📊 **Insights that make sense**: Daily, weekly, monthly, yearly.
Primary button "Get started". Android then shows an SMS permission explainer step ("Allow SMS access" / "Not now").

### 4.2 Home
1. Header: caption "Good morning/afternoon/evening" above title "October" (current month name); trailing **eye** button toggles hide-amounts.
2. **Hero card** (heroGradient, radius 28, padding 20, white text):
   * caption "Spent this month" (white 80%) → amount 44 bold.
   * Budget: capsule progress (track white 25%, fill white; fill becomes #FFB4B4 when >100%) + caption "₹17,660 left · 19 days to go" / "₹1,200 over budget" / "Set a monthly budget ›" (tap → budget editor).
   * Hairline (white 15%), then 3 mini stats: **Today · This week · Income** (label 12 white 70%, value 15 semibold).
3. **SMS banner** (only when there are new parsed SMS awaiting review): surface card, 💬 badge, "3 new transactions found in SMS", "Review" capsule → SMS Review sheet.
3b. **New sources card** (when unlinked sources minus `dismissedSources` is non-empty): surface card, 💳 badge, "2 new accounts found in SMS" (1 → "New account found in SMS"), caption "Link ••4521, ••7788 to your accounts" (first 3), "Link" capsule → Accounts screen; small × → adds the shown identifiers to `dismissedSources`.
4. **Top categories** (this month): section title + "Insights ›". Horizontal scroll of 128-wide cards: badge 40, name, amount, thin progress bar in category colour (= share of month spend).
5. **Recent**: section title + "See all ›" (→ Activity). Up to 8 rows grouped by day.
6. Empty state (no transactions at all): 🧾 in 88pt accentSoft circle, "No transactions yet", "Tap + to add one, or import from your bank SMS." + buttons "Add expense" and "Import SMS".

**Transaction row** (used everywhere): badge 40 · title (note, else category name) 16 medium, one line · subtitle 13 muted "Food · 7:45 PM · SMS" (omit missing parts) · trailing column: amount (16 semibold), and under it (13 muted, max 18 chars then "…") the account name if `accountId` resolves, else the displayed identifier `••4521`. Tap → Edit sheet. Swipe/long-press → Delete (with Undo snackbar/toast).
**Day header**: "Today" / "Yesterday" / "Mon, 10 Oct" (13 semibold muted) with the day's net spend right-aligned.

### 4.3 Add / Edit sheet (the most-used screen: two taps to log)
Top to bottom:
1. Top row: "Cancel" · segmented **Expense | Income** pill (accent fill on selected) · trash icon (edit mode only).
2. **Amount**: currency symbol (28, muted) + digits (56 bold rounded), centred, numeric transition. Shows "0" in muted when empty. Saving with 0 → shake + haptic.
2b. **Account chip**, centred under the amount (capsule, height 32, 14 medium): none → "＋ Account" in muted on surface2; set → kind emoji + name (1 line, max ~160) + ▾ on accentSoft in accent; SMS transaction with an unlinked identifier → "••4521 · Link ▾". Tap → menu: "No account", each account (emoji + name, ✓ on current), divider, "New account…" (opens the Account editor; when saved it becomes the selection). New manual entries default to `lastAccountId` (if it still exists); picking an account updates `lastAccountId`. Editing an existing transaction keeps its own account.
3. **Category grid**: 4 columns of badge 52 + name (12, 1 line); shows categories of the selected kind; selected = 2pt ring in category colour + name in `text` semibold; at most 2 rows visible, then scrolls horizontally *or* vertically (platform choice). Default = last used category of that kind.
4. Row: note field (surface2, radius 16, placeholder "Add a note") and date chips **Today · Yesterday · 📅** (📅 opens a date picker; selected chip accent).
5. **Keypad** 3×4: `1 2 3 / 4 5 6 / 7 8 9 / . 0 ⌫`. Keys 56 high, surface2, radius 18, 26 medium rounded. Rules: max 2 decimals, max 9 integer digits, single ".", leading "0" replaced, ⌫ long-press clears.
6. **Save** button: full-width 56 capsule, accent, "Save expense" / "Save income" / "Update".

### 4.4 Activity
Title "Activity" · search (matches note, category name, account name, identifier) · filter chips: **All · Expenses · Income · From SMS** + "Category ▾" menu chip + "Account ▾" menu chip (All accounts · each account · "Not linked"; only shown when at least one account exists). List grouped by day with day totals. Footer: "42 transactions · ₹12,340 spent · ₹50,000 received". Empty filter result: "Nothing matches".

### 4.5 Insights
1. Segmented **Day · Week · Month · Year** (custom capsule control, accent selected).
2. Period navigator: ‹ title › where title = "Today"/"12 Oct", "This week"/"6–12 Oct", "October 2026", "2026". › disabled at current period.
3. **Summary card**: "Spent" + amount (36 bold); delta chip "▲ 12% vs last week" (danger when up, income colour when down, hidden when previous = 0); row of 3: Income · Net · Avg/day.
4. **Spending chart card**: bar per bucket — Day → 24 hours, Week → 7 days (locale first weekday), Month → each day, Year → 12 months. Bars accent @ 35%, current bucket (now) solid accent, rounded tops. Tap/drag on a bar shows its value. Sparse axis labels (Day: 0 6 12 18 · Week: M T W… · Month: 1 8 15 22 29 · Year: J F M…).
5. **By category card**: donut (outer 180, thickness 28, 2° gaps), centre = total + "N categories"; below, rows: badge 32 · name · thin bar in category colour · % · amount, sorted desc.
6. **Top expenses**: the 3 largest transactions in the period (rows).
7. Empty period: "No spending in this period" in the card area.

Expenses only for chart/donut. Income shown in the summary.

### 4.6 Settings
1. **Privacy card** (accentSoft bg): 🛡️ badge · "Private by design" · "Your money data never leaves this device." · checks: ✓ Stored only on this device ✓ On-device AI ✓ Internet only for app updates (Android) / Internet only for update checks (iOS) ✓ Not in cloud backups.
2. **Security**: App Lock (Face ID / Touch ID / passcode, or fingerprint / screen lock) toggle · Hide amounts toggle.
3. **SMS**:
   * Android: "Read bank SMS" (permission state, tap to grant) · "Scan inbox now" · "Auto-add new SMS on open" toggle (default on) · caption "Last scanned 5 min ago".
   * iOS: "Auto-log with Shortcuts" (→ step-by-step guide sheet) · "Paste messages" (→ paste sheet → SMS Review).
4. **On-device AI**: status row (Available · Downloading · Ready to download · Not supported on this phone · Turned off · Needs an update), tappable → **AI setup** (§4.13); when not Available the trailing text is "Set up ›" in accent. + "Smart categorization" toggle (disabled when unavailable) + caption "Runs on Apple Intelligence / Gemini Nano on this device. Nothing is sent anywhere."
5. **Budget & currency**: Monthly budget (opens amount entry) · Currency picker (INR USD EUR GBP AED SGD AUD CAD JPY).
6. **Accounts** → Accounts screen (§4.10); trailing = number of accounts, plus an accent dot when unlinked sources exist. **Categories** → manager (list; add/edit sheet with emoji field, name, colour swatches, kind; built-ins non-deletable).
7. **Data**: Export CSV · Back up (JSON file) · Restore from backup (confirm: replaces everything) · Erase all data (destructive confirm).
8. **Updates** (§4.14): "Check for updates" row (subtitle = version or the last result) · "Check automatically" toggle (default **off**: "Once a day, when you open the app") · caption naming github.com/kushrike/TxnBuddy and "none of your data is sent".
9. Footer: version · "No analytics. No ads. No account."
10. DEBUG builds only: "Load demo data" (≈120 realistic transactions across 3 months).

### 4.7 SMS Review sheet
Title "Found 12 transactions" · caption "Parsed on this device. Nothing was uploaded." · list rows with a checkbox (default checked) + transaction row; tapping the badge cycles/opens a category menu · bottom button "Add 12 transactions" (count follows selection). Also used for the iOS paste flow.

### 4.8 Lock screen
Full screen bg · 🔒 in 88pt accentSoft circle · "TxnBuddy is locked" · "Unlock" capsule button. Auto-prompts biometrics on appear and on return from background.

### 4.9 iOS Shortcuts guide sheet
Numbered steps with SF Symbols:
1. Open **Shortcuts** → **Automation** → **+** → **Message**.
2. "Message contains" → `debited` (repeat the automation for `credited` and `spent`), choose **Run Immediately**.
3. Add action **Log Bank SMS** (TxnBuddy) → set *Message* to the **Shortcut Input / Message** variable.
4. Done. New bank SMS are logged instantly and parsed on this iPhone.
Button "Open Shortcuts" (`shortcuts://`), plus a note: "iOS doesn't let any app read your SMS inbox, so this automation hands each bank message to TxnBuddy. It still never leaves your phone."

### 4.10 Accounts screen
Title "Accounts".
1. **Found in SMS** section (only when unlinked sources exist). Caption: "Link each one to an account and future messages are sorted automatically." Rows: 🔎 badge · identifier `••4521` (16 semibold) · "12 transactions · ₹23,400" (13 muted) · trailing "Link" capsule → Link sheet (§4.12).
2. **Your accounts**: rows: kind badge · name · subtitle "Card · ••4521, ••7788" (kind name + identifiers; "No identifiers" if empty) · trailing "N transactions" muted. Tap → Account editor. Swipe/long-press delete (confirm "Its transactions stay, just unlinked.").
3. "Add account" button (accentSoft capsule, full width).
Empty (no accounts, no unlinked): 💳 bubble, "No accounts yet", "Add your bank accounts, cards and wallets. TxnBuddy matches bank SMS to them by the last 4 digits."

### 4.11 Account editor (sheet; add + edit)
* Top: Cancel · "New account" / "Edit account" · Save (disabled until name non-empty).
* Kind: 4 capsule chips 🏦 Bank · 💳 Card · 👛 Wallet · 💵 Cash.
* Name field, placeholder by kind: "HDFC Savings" · "Amazon ICICI Card" · "Paytm Wallet" · "Cash".
* **Identifiers** (hidden for Cash): existing ids as chips with ×; a field "Last 4 digits or wallet ID" + "Add" (also on return); input goes through `normId`, invalid → field shakes. Caption: "Only the last 4 digits are stored. Used to match bank SMS on this device."
* Edit mode: "Delete account" destructive text button at the bottom (same confirm as §4.10).
* Prefill when created from a Link sheet: `ids = [identifier]`, kind = card if the identifier is 4 digits else wallet, name empty and focused.

### 4.12 Link sheet (medium detent / bottom sheet)
Title = displayed identifier (`••4521`), caption "Seen in 12 SMS · ₹23,400 spent". Primary row "＋ Create new account" → Account editor (prefilled). Then "Or add it to" + the account list (tap → `link`, dismiss, toast "Linked ••4521 to HDFC Savings").

### 4.13 AI setup guide (screen/sheet, from the Settings AI row)
Header: AI status badge + one-line status. Then a numbered checklist that depends on status; always end with a "Check again" button (re-reads status) and the note "TxnBuddy works fully without AI: the built-in rules still read your SMS and pick categories."
* **iOS**
  * Available → "Apple Intelligence is ready." (no steps)
  * Turned off (`appleIntelligenceNotEnabled`) → 1. Open **Settings → Apple Intelligence & Siri**. 2. Turn on **Apple Intelligence**. 3. Wait for the download to finish (Wi‑Fi, charging, about 7 GB free). Button "Open Settings" (`UIApplication.openSettingsURLString`; no public deep link to that page).
  * Downloading (`modelNotReady`) → "Apple Intelligence is still downloading." 1. Keep the iPhone on Wi‑Fi and charging. 2. Make sure about 7 GB is free. 3. Check progress in Settings → Apple Intelligence & Siri.
  * Not supported (`deviceNotEligible`) → "This iPhone can't run Apple Intelligence. It needs iPhone 15 Pro or newer." Show model name if available.
  * Needs newer iOS → "Update to iOS 26 or later in Settings → General → Software Update."
  * Also: device + Siri language must be one Apple Intelligence supports (mention in Turned off).
* **Android** (status from `checkStatus()`; a thrown `GenAiException` maps by `errorCode`)
  * AVAILABLE → "Gemini Nano is ready."
  * DOWNLOADABLE → "Gemini Nano can be downloaded." Button **Download** → `download()` with progress (`DownloadStarted.bytesToDownload`, `DownloadProgress.totalBytesDownloaded`, shown as "1.2 of 3.1 GB"). Note: "AICore downloads it, not TxnBuddy (this app has no internet access). Use Wi‑Fi."
  * DOWNLOADING → progress if known, else "Downloading in the background. Keep the phone on Wi‑Fi."
  * `AICORE_INCOMPATIBLE` (−101) → "Update Android AICore" step first.
  * `NEEDS_SYSTEM_UPDATE` (604) → "Install the latest system update (Settings → Software update)" first.
  * `NOT_ENOUGH_DISK_SPACE` (501) → "Free up storage (a few GB)" first.
  * UNAVAILABLE / anything else → full checklist:
    1. **Check your phone.** "This phone: {Build.MANUFACTURER} {Build.MODEL}". "Gemini Nano for apps runs on Pixel 9 and newer, Galaxy S26 series, Galaxy Z Fold7 / Fold8 / Flip8 / TriFold, and recent flagships from OnePlus, Xiaomi, OPPO, vivo, Honor, Motorola and others. Older phones, including Galaxy S25 and earlier and A-series, don't have it yet."
    2. **Update Android AICore** and **Private Compute Services**: buttons opening `market://details?id=com.google.android.aicore` and `…id=com.google.android.as.oss` (fall back to `https://play.google.com/store/apps/details?id=…`). Show "Installed" / "Not installed" for each (PackageManager; declare both in `<queries>`).
    3. **Stay on Wi‑Fi and restart the phone.** "After an update AICore fetches its settings in the background. This can take a few minutes to a few hours; a restart speeds it up."
    4. **Install system updates** (Settings → Software update).
    5. Phones with an unlocked bootloader or root aren't supported.

### 4.14 Updates
Source: the public GitHub release `latest` (`api.github.com/repos/kushrike/TxnBuddy/releases/latest`). Tag `v1.0.<N>` where N is the CI run number = Android `versionCode` = iOS `CFBundleVersion`; a release is newer when its N is higher.
* **Android** installs in-app. Row states: "Check for updates" → "You're on the latest version" / "Couldn't reach GitHub", or "Update to 1.0.N" with trailing **Install** → "Downloading… 42%" → Android's own "Update this app?" dialog (PackageInstaller session). First time only, Install opens the system "Install unknown apps" page for TxnBuddy and continues by itself when the user comes back. Android rejects an APK not signed with the app's key, so the download needs no extra verification. Errors: signature mismatch → "This copy is signed with a different key. Back up, uninstall, and install the release once."
* **iOS** can't install itself: when newer, the row becomes "TxnBuddy 1.0.N is out · Open the release page" (link).
* Auto check (toggle on): once per 24 h on app open; if newer, toast "TxnBuddy 1.0.N is available" with **Update** (Android: starts the install, toast "Downloading TxnBuddy 1.0.N…" when not on Settings) / **View** (iOS: opens the release page).

### App icon
Master: `shared/icon.svg` (128-unit artboard). After changing it:
* **iOS:** `swift ios/scripts/render-icon.swift` renders `AppIcon-1024.png` (square, opaque) and copies the SVG into `Logo.imageset` for the in-app logo.
* **Android:** hand-port the shapes into `res/drawable/ic_launcher_{background,foreground,monochrome}.xml` (or Android Studio → New → Vector Asset). Foreground/monochrome use a 192 viewport with `translate(32,32)` so the 128 artboard lands on the visible 72dp centre of the 108dp layer. The in-app `AppIcon()` reuses these drawables.

---

## 5. SMS parser (deterministic, identical on both platforms)

`parse(body, sender: String?, now: Date) -> Parsed?` where
`Parsed = { amountMinor: Long, income: Bool, account: String?, merchant: String?, categoryId: String, date: Date? (local calendar day), smsKey: String }`.

All regexes are **case-insensitive** and run on `text` = body with every whitespace run collapsed to one space, trimmed. `lower` = lowercase of `text`.

1. **Sender filter**: if `sender` != nil and it contains ≥ 10 digits → `nil` (personal phone number).
2. **Reject** if `lower` matches
   `\b(otp|one[- ]?time password|verification code|will be (?:debited|credited|deducted|charged)|is due|are due|due (?:on|by|date)|requested|request money|collect request|pre-?approved|failed|declined|unsuccessful|insufficient|reminder)\b`
3. **Direction**: let `k` = `lower` with `credit card` and `debit card` replaced by `card`.
   * Tier 1, first match in `k` of `\b(debited|debit of|spent|paid|sent|withdrawn|purchased?|charged|deducted|transferred|credited|received|deposited|refund(?:ed)?|cashback|reversed)\b`. `credited received deposited refund refunded cashback reversed` → income, the rest → expense.
   * Tier 2 (only if tier 1 has no match): first match of `\b(txn|transaction|dr|cr)\b`; `cr` → income, others → expense.
   * Neither → `nil`.
4. **Amount**: try patterns in order and take the first match (left to right) that is (a) > 0 and (b) not a balance: the 25 chars before the match start (lowercased) contain none of `bal avl available limit lmt outstanding due`.
   * A (currency before): `(?:\brs\.?|\binr|₹|\busd|\$|\beur|€|\bgbp|£|\baed|\bsgd)\s*([0-9]+(?:,[0-9]+)*(?:\.[0-9]{1,2})?)`
   * B (currency after): `\b([0-9]+(?:,[0-9]+)*(?:\.[0-9]{1,2})?)\s*(?:rs|inr|usd|eur|gbp|aed)\b`
   * C (verb + number, no currency): `\b(?:debited|credited|spent|paid|sent|received|withdrawn|deposited|transferred)\s+(?:by|with|for|of)?\s*(?:rs\.?|inr|₹)?\s*([0-9]+(?:,[0-9]+)*(?:\.[0-9]{1,2})?)\b`
   * No amount → `nil`. Minor units = round(value × 100) after removing commas.
5. **Account**: first match of `\b(?:account|acct|a/c|card|ac)\s*(?:no\.?|number|num|ending(?:\s+(?:with|in))?)?\s*[:\-#]?\s*[x*•]*(\d{3,})` → last 4 chars of the digit group.
6. **Merchant**: first rule that yields a valid candidate.
   * `CAND` = `([a-z][a-z0-9&' \-/#*_]{0,39}?)`
   * `STOP` = `(?=\s+(?:on|ref\w*|via|upi|using|for|avl|bal|is|was|txn|dated|at|from|to|and|thru|by|with|in|of|has|not|card|a/c|rs|inr)\b|\s*[.,;:()!?]|\s*$)`
   * R1 **VPA**: first match of `\b([a-z0-9][a-z0-9.\-_]+)@([a-z][a-z0-9]+)\b` whose local part, after removing digits and turning `. - _` into spaces (collapse, trim), has ≥ 3 letters and does not start with `paytmqr bharatpe phonepe gpay razorpay rzp cashfree upi`. Result is titlecased (every word).
   * R2 (expense only): `[;.]\s*CAND\s+credited\b` (ICICI style "; AMAZON PAY credited").
   * R3: expense → `\b(?:at|to|towards|with)\s+CAND STOP`; income → `\bfrom\s+CAND STOP`. Iterate matches left to right.
   * R4 (expense only): `\bon\s+CAND STOP`, iterating left to right.
   * A candidate from R2–R4 is **invalid** if its first word (lowercased) is one of `your you a/c ac acct account card my rs rs. inr usd bank vpa upi date self beneficiary mobile number jan feb mar apr may jun jul aug sep oct nov dec`.
   * Clean-up: trim, collapse spaces, strip trailing `- / # * _ &` and spaces. If it has **no lowercase letters**, titlecase each word made only of letters (`UBER INDIA` → `Uber India`, `STARBUCKS #1234` → `Starbucks #1234`). Cap at 32 chars.
7. **Category**:
   * Income: `salary|payroll|stipend` → `salary`; `refund|cashback|reversal|reversed` → `refund`; else `otherIncome`.
   * Expense: search `lower + " " + merchant.lower` with the keyword table below in **row order**. First hit wins. Keywords of **≤ 4 chars match as whole words** (`\bkw\b`); longer ones match as substrings. No hit → `transfer` if R1 (VPA) produced the merchant, else `other`.

| category | keywords |
|---|---|
| groceries | instamart, blinkit, zepto, bigbasket, grofers, dmart, jiomart, grocery, supermarket, reliance fresh, natures basket, walmart, costco, whole foods, kroger, safeway, trader joe |
| food | uber eats, swiggy, zomato, restaurant, cafe, coffee, chai, starbucks, domino, pizza, mcdonald, kfc, burger, subway, eatsure, bakery, food, dining, haldiram, barbeque, doordash |
| transport | uber, ola, rapido, metro, fuel, petrol, diesel, hpcl, bpcl, iocl, indian oil, shell, fastag, parking, toll, namma yatri, lyft |
| travel | irctc, makemytrip, goibibo, cleartrip, yatra, ixigo, redbus, indigo, air india, vistara, akasa, spicejet, airlines, airways, hotel, oyo, airbnb, booking.com, expedia, agoda |
| shopping | amazon, flipkart, myntra, ajio, meesho, nykaa, tata cliq, croma, reliance digital, decathlon, ikea, lifestyle, westside, zara, h&m, uniqlo |
| bills | electricity, bescom, tneb, msedcl, tata power, airtel, jio, vodafone, bsnl, broadband, recharge, bill, dth, tata play, gas, water, insurance, lic, premium, emi, loan |
| entertainment | netflix, spotify, prime video, hotstar, jiocinema, bookmyshow, pvr, inox, youtube, apple music, gaana, steam, playstation, xbox, disney, sonyliv, zee5, movie, cinema |
| health | pharmacy, apollo, medplus, 1mg, pharmeasy, netmeds, hospital, clinic, diagnostic, practo, cultfit, cult.fit, gym, doctor, lab, medical, chemist |
| home | rent, landlord, nobroker, housing, maintenance, society, urban company, furniture, pepperfry |
| education | school, college, university, tuition, udemy, coursera, byju, unacademy, books, course, fees |
| transfer | neft, imps, rtgs, transfer, trf, atm, cash |

8. **Date** (only used when the platform has no message timestamp, i.e. iOS paste): try P1 → P4; within a pattern take the first match (left to right) that gives a valid calendar date in **[now − 400 days, now + 1 day]**. 2‑digit year → 2000+yy. Month = first 3 letters.
   * P1 `\b(\d{4})-(\d{2})-(\d{2})` → y-m-d
   * P2 `\b(\d{1,2})[-/ ]?(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*[-/ ,]*(\d{2,4})?\b` → d Mon [y]; no year → current year, and if that is after now+1d use the previous year.
   * P3 `\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.?\s+(\d{1,2}),?\s+(\d{4})\b` → Mon d, y
   * P4 `\b(\d{1,2})[-/.](\d{1,2})[-/.](\d{2,4})\b` → d/m/y (day first). If m > 12 swap. If the result is out of range (or invalid) and swapping gives an in-range date, use the swapped one.
9. **smsKey** = FNV‑1a 64‑bit over the UTF‑8 bytes of `lower`, as 16 lowercase hex chars. Checks: `""`→`cbf29ce484222325`, `"a"`→`af63dc4c8601ec8c`, `"foobar"`→`85944171f73967e8`.

**Test vectors:** `shared/sms-samples.tsv` with columns `sender type amount account merchant category date body`, where `-` = nil, `now` = **2026-10-12 12:00 local**, and `type none` means `parse` returns nil. Each platform has one runnable check that loads this file and asserts every field.

### On-device AI (after the parser)
* **Categorize**: when the parser gives `other` (expense) and Smart categorization is on and the model is available, ask the model: *"Pick the single best spending category for this bank transaction. Merchant: {merchant}. Message: {first 200 chars}. Answer with exactly one of: {category names of that kind}."* Map the answer back by case-insensitive name match; unknown → keep `other`.
* **Rescue**: when the parser returns nil at step 3/4 but the text has an amount pattern A/B, is not rejected (step 2) and the sender filter passed, ask the model to extract `{isTransaction, amount, isCredit, merchant}` (iOS: `@Generable` struct; Android: JSON in text, tolerant parse). Accept only if `isTransaction` and amount > 0.
* Timeout around 6 s per message, run sequentially, never block the UI. Skip AI entirely for imports of more than 200 messages (keyword categories only), or do it in the background after import.

### Learning from you (every phone, no model, no download)
Category of an imported transaction, first hit wins:
1. **Memory**: `recall(merchant)`, if that category still exists and is of the same kind.
2. The parser's keyword category (§5.7), unless it is `other` / `otherIncome`.
3. **Classifier** prediction (below).
4. On-device LLM `categorize` (when available and the toggle is on).
5. `other` / `otherIncome`.

* `mkey(s)`: lowercase, every run of non-letters → one space, trim; empty → none. `Starbucks #1234` → `starbucks`, `UBER INDIA` → `uber india`.
* **Memory** = `learned`, a map `mkey → categoryId` stored in the data file (so it is in backups: `"learned": {"swiggy": "groceries"}`, optional when reading). Set whenever the user saves from the Add/Edit sheet with a non-empty note, and when they change a category in the SMS Review sheet (every row there covered by that key follows). Entries pointing at a deleted category are dropped; Erase clears it.
* A key **covers** a note when `mkey(note)` equals it or starts with it plus a space (`starbucks` covers `Starbucks Coffee`, not `Starbuck`). `recall(note)` = `learned[mkey(note)]`, else the value of the longest learned key that covers the note.
* **Classifier**: multinomial naive Bayes over note tokens, rebuilt on the fly (once per import batch / per sheet) from every transaction of the same kind whose note is non-empty and whose category is not `other` / `otherIncome`.
  * `tokens(s)`: lowercase, split on non-alphanumerics, keep tokens with length ≥ 2 that are not all digits and not in `the and for with from upi pvt ltd limited private india payment payments txn ref to of at by in on`.
  * One transaction with at least one token = one document. Score(c) = log(docs_c / N) + Σ over input tokens **that are in the vocabulary** of log((count(t,c) + 0.1) / (tokens_c + 0.1·V)), V = vocabulary size.
  * No input token in the vocabulary → no prediction. Otherwise take the best category and accept it only if its posterior (softmax of the scores) is ≥ 0.6.
  * Check (both platforms): train `Swiggy order`→food, `Zomato`→food, `Uber ride`→transport, `Ola cabs`→transport, `Lenskart`→health; then `lenskart store` → health, `uber auto` → transport, `random xyz` → none.
* **Add sheet**: on a new entry, until the user taps a category in this sheet, typing a note selects (for the current kind) memory → keyword table on the note (if not `other`/`otherIncome`) → classifier; nothing found → keep the current selection.
* **Edit sheet**: when the category was changed and the note is non-empty, toast "Swiggy → Groceries from now on"; if other transactions of the same kind that the key covers have a different category, add the action "Apply to N more" (sets them all). If the account auto-link toast also fires, show only that one.
* Settings → On-device AI: row **Learned from you** · trailing "14 merchants" → confirm "Forget what TxnBuddy learned? Your transactions stay as they are." → clears `learned`. Section caption: "Learns the categories you pick, on this device. On supported phones Gemini Nano / Apple Intelligence helps with new merchants. Nothing is sent anywhere."
* AI setup guide closing note: "TxnBuddy works fully without AI: the built-in rules read your SMS, and it learns from the categories you pick."

### SMS flows
* **Android**: `READ_SMS` runtime permission. Scan `content://sms/inbox` (`_id, address, body, date`) for messages newer than the last scan (first scan: last **180 days**). Parse each, drop ones whose `smsKey` already exists. First scan → SMS Review sheet. Later scans on app open → auto-add (if the toggle is on) and show a snackbar "Added 3 from SMS · Undo". Date = SMS `date` column.
* **iOS**: App Intent **"Log Bank SMS"** (`text` parameter, no UI, does not open the app) → parse (+AI) → add if new → returns a dialog like "Logged −₹250 · Swiggy" or "No transaction found". Paste sheet: multiline text; split messages on blank lines (or treat as one if there are none) → SMS Review sheet. Date = parsed date, else now.
