# Setup

Everything here is free. Nothing creates billable Azure resources.

## 1. Register the app with Microsoft (once)

Microsoft only lets an app reach your OneDrive through an app registration (a "client ID").

1. Open <https://entra.microsoft.com> and sign in with the Microsoft account that owns the Excel file.
   - If it says your account has no directory/tenant, create a free Azure account at
     <https://azure.microsoft.com/free> first (it asks for a card for identity checks; this app never uses
     anything billable). Leave the subscription empty: create no resources in it.
2. **Identity > Applications > App registrations > New registration**.
3. Name `Expense Log`; supported account types **Personal Microsoft accounts only**; no redirect URI; **Register**.
4. Copy the **Application (client) ID**.
5. **Authentication > Settings > Allow public client flows = Yes**, **Save**.

## 2. Build tools on the laptop (Windows)

The scripts expect everything under `D:\AndroidDev` and add nothing to the system PATH:

| Tool | Where |
|---|---|
| JDK 17 (Microsoft OpenJDK) | `D:\AndroidDev\jdk\<version>` |
| Gradle 8.9 | `D:\AndroidDev\gradle\gradle-8.9` |
| Android SDK: platform 34, build-tools 34.0.0, platform-tools | `D:\AndroidDev\sdk` |

Installing the SDK packages means accepting Google's Android SDK license.

## 3. Personal configuration

Copy `local.properties.example` to `local.properties` and fill in:

```properties
sdk.dir=D\:\\AndroidDev\\sdk
expenselog.clientId=<the Application (client) ID from step 1>
expenselog.itemId=<the resid= value from the Excel file's OneDrive web link>
expenselog.fileName=<Excel file name, e.g. Payments>
expenselog.sheet=<sheet (tab) name>
```

`local.properties` is git-ignored. The file and sheet can also be changed later in the app's Settings.

The sheet's columns are: **A** Date (date + time), **B** Amount ($), **C** Payment Method, **D** Purpose,
**E** Notes/Remarks, **F** Category. Row 1 is the header.

## 4. Signing key (once)

```powershell
.\make-release-key.ps1
```

Creates `D:\AndroidDev\keys\release.keystore` and its password (encrypted for your Windows user) in
`release-pass.dat`. **Back up `D:\AndroidDev\keys` somewhere off the laptop.** Every version must be
signed with this key; losing it means uninstalling the app to install a newer one.

## 5. Laptop sign-in for publishing (once)

```powershell
.\publish.ps1 -SignIn
```

Open the link shown, enter the code, sign in with the same Microsoft account.

## 6. First install on the phone

1. On the phone: **Settings > About device > Version**, tap **Build number** 7 times, then
   **Developer options > USB debugging** on. Allow the laptop when asked.
2. Plug in and run `.\build.ps1 -Notes "first install"`.
3. In the app's **Settings**: **Sign in**, then **Test connection**.
4. **Payment detection**: (a) allow notification access (if greyed out: *Open app info* > ⋮ > *Allow
   restricted settings*), (b) choose apps to watch, (c) battery: don't optimise.
5. **App updates > Reopen after updates**: allow *Display over other apps* (optional).
6. The first automatic update asks once to allow Expense Log to install apps.
7. Optional: Play Store > profile > Play Protect > ⚙ > turn off **Improve harmful app detection**
   (keeps scanning on) to stop the "scan this app" prompt on each update.

After this, the phone doesn't need the cable to receive updates; see [RELEASING.md](RELEASING.md).

## Moving to a new phone or reinstalling

1. Old phone: **Settings > Backup > Back up**.
2. Install on the new phone (step 6), sign in, then **Settings > Backup > Restore**.
3. Re-allow the permissions in step 6.4–6.5.
