# Architecture

Plain Android views and Kotlin, no Compose, no AndroidX UI libraries: activities, `ListView`s,
`SharedPreferences`, `JobScheduler`. Network calls use OkHttp against Microsoft Graph. Package
`com.expenselog.app`; all source is in `app/src/main/java/com/expenselog/app/`.

## Processes

| Process | Runs | Why |
|---|---|---|
| main | screens, notification listener, widget, offline queue | normal app |
| `:updater` | `UpdateJob`, `InstallReceiver` | a startup crash in the main app can't stop updates or health reports |

`App.onCreate` runs in both. In `:updater` it only installs the crash catcher and returns. Anything that
runs at startup must never throw (wrapped in try/catch), or the app won't open.

## Files

| File | Responsibility |
|---|---|
| `Graph.kt` | Microsoft sign-in (device-code flow) and Graph calls: read rows, append/write/delete row, small OneDrive files |
| `Store.kt` | Settings and learned data (SharedPreferences `expenselog`); tokens encrypted |
| `Crypto.kt` | AES-GCM with an Android Keystore key; `seal` / `open` |
| `PaymentParser.kt` | Bank text → amount, merchant, card type/name/last 4, date, time; refunds; what to ignore |
| `PaymentListener.kt` | `NotificationListenerService`: filters watched apps, parses, adds to Detected |
| `Pending.kt` | `Pending` entry, `PendingStore` (Detected list, encrypted), `IgnoredStore` (7 days), `Prompts` notification |
| `Ledger.kt` | Adding to the sheet: learned values, duplicate guard, offline queue (`QueueJob`), Undo; `Categories` |
| `Capture.kt` | Detected entries from other sources; `ReceiptParser`, `VoiceParser`, `InboxScanner`, repeated-text filter |
| `Ocr.kt` | ML Kit text recognition (bundled, offline) for receipts |
| `Insights.kt` | Local copy of the sheet, unusual-charge rule, statement cycles |
| `Backup.kt` | Settings + Detected + ignored ↔ OneDrive `ExpenseLog/backup.json` (no tokens) |
| `Updater.kt` | Self-update, `App` (startup), `UpdateJob`, `InstallReceiver`, `TouchSpy` |
| `Health.kt` | `health.json` reports, crash catcher, on-screen / in-use / reopen markers (files shared across processes) |
| `Glass.kt` | Liquid-glass material (`GlassDrawable`), refraction shader (`Lens`, Android 13+), `Backdrop`, `BlurBar`, `GlassCard`, tilt `Light` |
| `Ui.kt` | Theme, edge-to-edge framing, `AuroraView` background, `Fx` animations, `Battery` |
| `Widget.kt`, `Tiles.kt` | Home screen widget, Quick Settings tiles |
| `MainActivity`, `PendingActivity`, `SettingsActivity`, `SheetActivity`, `TotalsActivity`, `ManageActivity`, `ShareActivity` | Screens |

## Data flow for a detected payment

```
Bank SMS → notification → PaymentListener → PaymentParser.parse
        → PendingStore (encrypted) → Prompts notification / widget / Detected screen
        → ✓ → Ledger.add → (duplicate check: Graph.readRows) → Graph.appendRow → Undo record
                         ↘ no internet → queued → QueueJob when online
```

## Sheet format

Row 1 is the header. `Graph.writeRow` writes A–F with number formats: A `m/d/yyyy h:mm AM/PM` (Excel serial
date-time), B `0.00`, C–F text (`@`). New rows go below the last used row.

## Glass rendering

`GlassDrawable` draws body, sheen, rim (moving with `Light`), press glow and ripple. `Backdrop` records the
`AuroraView` (and, for bars, the scrolling content) into a `RenderNode` with blur + saturation +
the `Lens` runtime shader, clipped to the rounded shape. Redraws happen only when the background moves
(`Backdrop.redrawAll` from `AuroraView`) or something scrolls, never every frame, so the battery saver
(`AuroraView` pausing after 30 s idle or on low battery) actually stops rendering.

## Tests

`app/src/test/`: `PaymentParserTest` (real bank message formats, anonymised), `CaptureTest` (receipts,
voice), `InsightsTest` (unusual charges, statement cycles). Run with `gradle testDebugUnitTest`
(`build.ps1` always does).
