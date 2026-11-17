# Working on Expense Log (instructions for Claude)

Personal Android app that logs card payments to an Excel file on the owner's OneDrive. Read
[README.md](README.md) first, then [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and
[docs/RELEASING.md](docs/RELEASING.md). The owner is not a developer: explain in plain words, ask before
anything hard to undo, and report results honestly (what was tested on the phone, what wasn't).

## At the start of a session

1. If `UPDATE-FAILURES.md` exists, read it. For each `OPEN` entry: find the cause, fix it, release through
   `build.ps1`, then mark the entry `FIXED`. "Check the failed update" means exactly this.
2. Check `D:\AndroidDev\releases\watch.json` for the latest published version code.

## Hard rules

- **No cost.** Never create Azure resources, change the Azure subscription, or use any paid Microsoft
  service. The app needs only the free Entra app registration and Microsoft Graph with a personal account.
- **Release only through `build.ps1`** with the phone on USB. The phone installs whatever is published by
  itself, so a build that crashes on start breaks the app. `-PublishUntested` only for trivial changes, and
  say so. Raise `versionCode` above everything ever published (rollbacks use `published + 1`).
- **Never re-key.** Always sign with `D:\AndroidDev\keys\release.keystore` (alias `expenselog`, password via
  `release-env.ps1`). Never create a new key and never sign with the debug key; a different signature
  forces an uninstall that wipes the app's data and permissions. Run Settings > Backup first if a
  reinstall is ever unavoidable.
- **Startup must not throw.** `App.onCreate` runs in the main and `:updater` processes; wrap anything
  new there.
- **Don't change the phone's system or security settings** (airplane mode, Wi-Fi, permissions, font size,
  Play Protect). Ask the owner to do it.
- **The owner's real data:** Detected entries and the sheet are real payments. When testing on the phone,
  use clearly fake entries (e.g. `GRUBHUB*TESTPHASE1`, `$3.21`), act only on cards you identified by title,
  and remove test rows afterwards (Undo, or delete after confirming the row's contents). Never tick or add
  a real payment yourself.
- **Keep personal data out of git:** client ID, Excel file ID and real card digits live in
  `local.properties` (git-ignored). Tests use anonymised messages. Don't commit APKs, screenshots or logs.

## Building and testing

- Toolchain under `D:\AndroidDev` (JDK 17, Gradle 8.9, SDK 34); `. .\release-env.ps1` sets it up.
- `.\build.ps1 -NoPublish` = tests + release build + install + launch test, without publishing.
- `adb` is `D:\AndroidDev\sdk\platform-tools\adb.exe`. In Git Bash set `MSYS_NO_PATHCONV=1`.
- Release builds aren't debuggable: no `run-as`, and stored data is encrypted. Diagnose with
  `adb logcat`, `dumpsys` (package, notification, jobscheduler, activity), the UI tree
  (`uiautomator dump`), and OneDrive `ExpenseLog/health.json` / `backup.json`.
- Only `MainActivity` is exported: reach other screens by tapping through the UI.
- Check the phone is unlocked (`dumpsys window | grep isKeyguardShowing`) before UI tests or screenshots,
  and that the notification shade isn't open.
- For visual changes, take screenshots (`adb exec-out screencap -p`) and look at them before publishing.

## Conventions

- Plain Android views + Kotlin, no new heavy libraries without reason. Glass styling is applied by
  `Glass.apply()` from `android:tag` values (`card`, `field`, `pill`, `primary`, `yes`, `no`); cards use
  `com.expenselog.app.GlassCard`.
- New screens: copy `activity_list.xml` + `row_card.xml` (see `TotalsActivity`), call `Ui.applyTheme`
  before `setContentView` and `Ui.frame` after.
- Parser changes: add a test with the real message format (anonymise names and card digits).
- Keep comments short and in plain language; number user-requested features as the owner did (#1–#77)
  when it helps trace them.
