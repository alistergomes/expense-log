# Releasing, updates and rollback

## Making a release

1. Change the code.
2. In `app/build.gradle.kts`, raise the defaults in `versionCode = ... ?: N` and `versionName = ... ?: "x.y"`.
   The version code must be higher than every version ever published (including rollbacks, which use
   `published + 1`). Check `D:\AndroidDev\releases\watch.json` for the latest.
3. Plug the phone in (USB debugging on, screen unlocked) and run:

   ```powershell
   .\build.ps1 -Notes "what changed"
   ```

`build.ps1` does, in order:

| Step | Fails the release when |
|---|---|
| Unit tests (`testDebugUnitTest`) | any test fails |
| Release build, signed with `D:\AndroidDev\keys\release.keystore` | the APK isn't signed with `CN=Expense Log` |
| Install on the phone over USB | the install is refused |
| Launch test: start the app, wait 6 s, check logcat | the app crashes or isn't running |
| `publish.ps1`: upload to OneDrive `ExpenseLog/` | — |

Without a phone it refuses to publish. `-PublishUntested` overrides that; only use it for trivial changes,
because the phone installs whatever is published **by itself**. `-NoPublish` builds and launch-tests only.

## How the phone updates itself

- `publish.ps1` uploads `ExpenseLog.apk`, then `version.json` (in that order), to OneDrive `ExpenseLog/`.
  It also keeps a copy of the APK and the source in `D:\AndroidDev\releases\<versionCode>\`.
- The app's **separate `:updater` process** checks `version.json`:
  - every 3 hours (JobScheduler, survives restarts);
  - when any notification arrives (at most hourly);
  - when the app is opened (at most every 6 hours);
  - on **Settings > App updates > Check for updates**.
- If newer, it downloads and installs through Android's PackageInstaller **without a prompt** (allowed
  because the app installed itself last time). If Android insists on a tap, it posts *Update ready*.
- It waits while you're using the app (touched in the last 90 s or a half-typed entry), retrying each
  minute. If the app was on screen, it reopens after the update (needs *Display over other apps*).
- A failed install is retried for the same version only after 12 hours.

Running the update check by hand (phone on USB, app closed):

```bash
adb shell cmd jobscheduler run -f com.expenselog.app 1
```

## Health reports and automatic rollback

The phone writes `ExpenseLog/health.json`: `installed` (from the updater process), `ok` (main app ran for
15 s), or `crashed` (crash catcher, with the error). The laptop task **ExpenseLog Watchdog** runs
`watchdog.ps1` every 15 minutes:

| Phone reports for the watched version | Watchdog does |
|---|---|
| `crashed` | rolls back |
| `installed`, no `ok` after 30 min | rolls back |
| `ok` for 2 hours | marks it known-good (`releases\good.json`) |

Rolling back = rebuilding the known-good source snapshot as version `watched + 1` (named like `2.9-r17`),
release-signed, and publishing it. The phone then installs it like any update. Every failure is logged
to `UPDATE-FAILURES.md` in the project and announced with a Windows notification; the phone shows its
own notification. Rollback needs the laptop on. If a rollback isn't possible (no known-good version, or the
rollback itself failed) it alerts instead of retrying.

Testing the rollback deliberately (builds a version that crashes on start):

```powershell
. .\release-env.ps1
& $Gradle -q assembleRelease "-PvcOverride=<N>" "-PvnOverride=<N>-crashtest" "-PcrashTest=true"
.\publish.ps1 -Apk $ReleaseApkRel -Notes "crash test"
```

## If something goes wrong

| Symptom | Fix |
|---|---|
| App won't open after an update | Wait for the watchdog (laptop on), or install `ExpenseLog/ExpenseLog.apk` from the OneDrive app on the phone |
| *Signatures don't match* on install | The APK wasn't signed with the release key. Never use another key; check `D:\AndroidDev\keys` |
| Phone not seen by `adb devices`, or "offline" | Unlock, replug, accept the USB debugging prompt, choose File transfer |
| Laptop says signed out of OneDrive | `.\publish.ps1 -SignIn` |
| App says sign in again | Settings > Sign in |
| Updates stopped | Settings > App updates (switch on? message?), battery set to *Don't optimise* |
| "Scan this app" prompt on each update | Play Protect > ⚙ > *Improve harmful app detection* off |
