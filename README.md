# Expense Log

A personal Android app that logs card payments into an Excel workbook on OneDrive. It reads payment
alerts from your bank texts and apps, lets you approve each one with a tap (or a swipe), and writes a
tidy row to the sheet: date and time, amount, payment method, purpose, notes and category.

It has a dark "liquid glass" look tuned for AMOLED screens, updates itself from your own OneDrive (no
app store, no cable), and rolls itself back automatically if a new version ever fails on the phone.

Built for one person's OnePlus 8T (Android 14) and a personal Microsoft account. It uses only free
services: the Microsoft Graph API with a free Entra app registration. Nothing runs in Azure.

## What it does

**Getting payments in**
- Watches notifications from the apps you choose (your SMS app by default) and picks out payment alerts:
  amount, merchant, card, date and time. Bill payments, money received and one-time codes are ignored;
  refunds become negative amounts; repeated texts are logged once.
- Detected payments wait in a **Detected** list (and a home screen widget): ✓ or swipe right adds to
  Excel, ✕ or swipe left ignores (kept 7 days in *Recently ignored*), long-press to select several,
  **Add all** adds them in date order.
- **Scan messages** finds payment texts in your inbox that never made it into the sheet.
- Manual entry form, **voice entry** ("twelve fifty at Starbucks on Chase"), **receipt photos** (read on
  the phone, offline), and **Share to Expense Log** from any app.

**Keeping the sheet right**
- Column A gets a real date and time, B a number, C–F text (method, purpose, notes, category).
- **Duplicate guard** checks the sheet before adding; an **offline queue** adds entries when you're back online;
  **Undo** removes the last row added and puts the payment back in Detected.
- Learns per card (payment-method wording) and per merchant (nickname, notes, category); all editable
  in *Cards & merchants*.
- **Sheet** screen to search, edit and delete rows; **Totals** per card by statement cycle; unusual-charge flags.

**Around the app**
- Quick Settings tiles, app-icon shortcuts, home screen widget.
- Sign-in tokens and saved messages encrypted with the phone's hardware keystore.
- Backup and restore of settings and learned data to OneDrive.
- Automatic updates from OneDrive, with a laptop-side watchdog that rolls back a release that crashes.

## Repository layout

| Path | What it is |
|---|---|
| `app/` | The Android app (Kotlin, no Jetpack Compose, minSdk 26, targets Android 14) |
| `app/src/test/` | Unit tests: message parsing, receipts, voice entry, insights |
| `build.ps1` | Build, test on the phone over USB, and publish (the only way to release) |
| `publish.ps1` | Uploads a build to OneDrive and records it for the watchdog |
| `watchdog.ps1` | Scheduled every 15 min: rolls back a release that fails on the phone |
| `onedrive.ps1`, `release-env.ps1` | Shared helpers: OneDrive sign-in, toolchain and signing key |
| `make-release-key.ps1` | One-time creation of the release signing key |
| `docs/` | Setup, architecture, releasing and troubleshooting |
| `CLAUDE.md` | Working rules for continuing this project with Claude |

## Getting started

1. [docs/SETUP.md](docs/SETUP.md): register the free Microsoft app, set up the build tools, configure
   `local.properties`, install on the phone.
2. [docs/RELEASING.md](docs/RELEASING.md): how updates, automatic installs and rollbacks work.
3. [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): how the code is organised.

## Privacy

- Personal settings (Microsoft app ID, Excel file ID) live in `local.properties`, which is not committed.
- Bank messages and tokens stay on the phone, encrypted. The app talks only to Microsoft
  (sign-in and Graph). Backups go to your own OneDrive.
- The signing key and the laptop's OneDrive token live outside the repository in `D:\AndroidDev\keys`.

## License

Personal project; no license granted. Ask before reusing.
