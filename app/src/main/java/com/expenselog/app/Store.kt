package com.expenselog.app

import android.content.Context

/** App-private settings and sign-in tokens. */
class Store(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("expenselog", Context.MODE_PRIVATE)

    var clientId: String
        get() = p.getString("clientId", DEFAULT_CLIENT_ID)?.ifEmpty { DEFAULT_CLIENT_ID } ?: DEFAULT_CLIENT_ID
        set(v) = p.edit().putString("clientId", v.trim()).apply()

    var itemId: String
        get() = p.getString("itemId", DEFAULT_ITEM_ID) ?: DEFAULT_ITEM_ID
        set(v) = p.edit().putString("itemId", v.trim()).apply()

    var fileName: String
        get() = p.getString("fileName", DEFAULT_FILE_NAME) ?: DEFAULT_FILE_NAME
        set(v) = p.edit().putString("fileName", v.trim()).apply()

    var sheet: String
        get() = p.getString("sheet", DEFAULT_SHEET) ?: DEFAULT_SHEET
        set(v) = p.edit().putString("sheet", v.trim()).apply()

    var refreshToken: String
        get() = Crypto.open(p.getString("refreshToken", ""))
        set(v) = p.edit().putString("refreshToken", Crypto.seal(v)).apply()

    var accessToken: String
        get() = Crypto.open(p.getString("accessToken", ""))
        set(v) = p.edit().putString("accessToken", Crypto.seal(v)).apply()

    /** Epoch millis after which the access token must be refreshed. */
    var accessExpiresAt: Long
        get() = p.getLong("accessExpiresAt", 0)
        set(v) = p.edit().putLong("accessExpiresAt", v).apply()

    var lastMethod: String
        get() = p.getString("lastMethod", "") ?: ""
        set(v) = p.edit().putString("lastMethod", v).apply()

    /** Payment methods typed before, most recent first. */
    var methods: List<String>
        get() = (p.getString("methods", "") ?: "").split('\n').filter { it.isNotBlank() }
        set(v) = p.edit().putString("methods", v.joinToString("\n")).apply()

    /** Package names whose notifications are checked for payments. */
    var watchedApps: Set<String>
        get() = HashSet(p.getStringSet("watchedApps", DEFAULT_WATCHED) ?: DEFAULT_WATCHED)
        set(v) = p.edit().putStringSet("watchedApps", HashSet(v)).apply()

    // ----- self-update: what the last check of OneDrive found -----
    var updateCode: Long
        get() = p.getLong("updateCode", 0)
        set(v) = p.edit().putLong("updateCode", v).apply()

    var updateName: String
        get() = p.getString("updateName", "") ?: ""
        set(v) = p.edit().putString("updateName", v).apply()

    var updateNotes: String
        get() = p.getString("updateNotes", "") ?: ""
        set(v) = p.edit().putString("updateNotes", v).apply()

    var lastUpdateCheck: Long
        get() = p.getLong("lastUpdateCheck", 0)
        set(v) = p.edit().putLong("lastUpdateCheck", v).apply()

    /** Install new versions unattended (default). Off: only the manual button updates the app. */
    var autoUpdate: Boolean
        get() = p.getBoolean("autoUpdate", true)
        set(v) = p.edit().putBoolean("autoUpdate", v).apply()

    /** The version an unattended install was last attempted for, and when, so a failing one is not retried in a loop. */
    var autoTriedCode: Long
        get() = p.getLong("autoTriedCode", 0)
        set(v) = p.edit().putLong("autoTriedCode", v).apply()

    var autoTriedAt: Long
        get() = p.getLong("autoTriedAt", 0)
        set(v) = p.edit().putLong("autoTriedAt", v).apply()

    /** Version name that was installed when an update started; "" when none is in progress. */
    var updatingFrom: String
        get() = p.getString("updatingFrom", "") ?: ""
        set(v) = p.edit().putString("updatingFrom", v).commit().let { }

    /** Why the last install attempt failed; "" when there is nothing to report. */
    var updateMessage: String
        get() = p.getString("updateMessage", "") ?: ""
        set(v) = p.edit().putString("updateMessage", v).commit().let { }

    /** Master switch for payment detection. Off: notifications from other apps are not looked at. */
    var monitorEnabled: Boolean
        get() = p.getBoolean("monitorEnabled", true)
        set(v) = p.edit().putBoolean("monitorEnabled", v).apply()

    /** Whether a detected payment raises a notification. Off: it is only added to the Detected list. */
    var alertsEnabled: Boolean
        get() = p.getBoolean("alertsEnabled", true)
        set(v) = p.edit().putBoolean("alertsEnabled", v).apply()

    // ----- learned per merchant (keyed by the merchant text as it appears in the bank message) -----
    private fun key(merchant: String) = merchant.trim().lowercase()

    /** The user's own name for a merchant, e.g. GRUBHUB*INDIANDELIGH -> "Grubhub – Indian Delight". */
    fun nickname(merchant: String): String = if (merchant.isBlank()) "" else p.getString("nick_" + key(merchant), "") ?: ""
    fun setNickname(merchant: String, name: String) {
        if (merchant.isBlank()) return
        val v = name.trim()
        p.edit().apply { if (v.isEmpty() || v == merchant.trim()) remove("nick_" + key(merchant)) else putString("nick_" + key(merchant), v) }.apply()
    }

    fun merchantNotes(merchant: String): String = if (merchant.isBlank()) "" else p.getString("notes_" + key(merchant), "") ?: ""
    fun setMerchantNotes(merchant: String, notes: String) {
        if (merchant.isBlank()) return
        p.edit().putString("notes_" + key(merchant), notes.trim()).apply()
    }

    fun merchantCategory(merchant: String): String = if (merchant.isBlank()) "" else p.getString("cat_" + key(merchant), "") ?: ""
    fun setMerchantCategory(merchant: String, category: String) {
        if (merchant.isBlank() || category.isBlank()) return
        p.edit().putString("cat_" + key(merchant), category.trim()).apply()
    }

    /** Encrypts values saved before encryption existed (#48). Runs at startup; does nothing once done. */
    fun encryptExisting() {
        fun plain(k: String) = p.getString(k, "").let { !it.isNullOrEmpty() && !it.startsWith("enc1:") }
        if (plain("refreshToken")) refreshToken = refreshToken
        if (plain("accessToken")) accessToken = accessToken
        if (plain("lastAdd")) lastAdd = lastAdd
        if (plain("rowsCache")) rowsCache = rowsCache
    }

    /** A local copy of the sheet's rows (JSON), refreshed on every read. */
    var rowsCache: String
        get() = Crypto.open(p.getString("rowsCache", "")).ifEmpty { "[]" }
        set(v) = p.edit().putString("rowsCache", Crypto.seal(v)).apply()

    /** Day of the month a card's statement closes (1–31); 1 until the user sets it. */
    fun statementDay(method: String): Int = p.getInt("cycle_" + key(method), 1)
    fun setStatementDay(method: String, day: Int) = p.edit().putInt("cycle_" + key(method), day.coerceIn(1, 31)).apply()

    /** Everything learned under [prefix] ("card_", "nick_", "notes_", "cat_"): stored key -> value. */
    fun learned(prefix: String): Map<String, String> =
        p.all.filterKeys { it.startsWith(prefix) }
            .mapNotNull { (k, v) -> (v as? String)?.takeIf { it.isNotBlank() }?.let { k.removePrefix(prefix) to it } }
            .toMap().toSortedMap()

    fun setLearned(prefix: String, keyName: String, value: String) {
        p.edit().apply { if (value.isBlank()) remove(prefix + keyName) else putString(prefix + keyName, value.trim()) }.apply()
    }

    /** The last row the app added (JSON), for Undo; "" when there is nothing to undo. */
    var lastAdd: String
        get() = Crypto.open(p.getString("lastAdd", ""))
        set(v) = p.edit().putString("lastAdd", Crypto.seal(v)).apply()

    /** Appearance: dark glass unless the user switches this on. */
    var lightMode: Boolean
        get() = p.getBoolean("lightMode", false)
        set(v) = p.edit().putBoolean("lightMode", v).apply()

    /** Text used by "Send test notification"; the default until the user types their own. */
    var testText: String
        get() = p.getString("testText", DEFAULT_TEST_TEXT)?.ifBlank { DEFAULT_TEST_TEXT } ?: DEFAULT_TEST_TEXT
        set(v) = p.edit().putString("testText", v).apply()

    /** The payment-method text the user last saved for a card, by its last 4 digits; "" if none. */
    fun cardLabel(last4: String): String =
        if (last4.isEmpty()) "" else p.getString("card_$last4", "") ?: ""

    fun setCardLabel(last4: String, method: String) {
        if (last4.isNotEmpty() && method.isNotBlank()) p.edit().putString("card_$last4", method.trim()).apply()
    }

    /** Method to write for a detected payment: what the user saved for that card, else what the message said, else the last one used. */
    fun methodFor(pending: Pending): String =
        cardLabel(pending.last4).ifEmpty { pending.method }.ifEmpty { lastMethod }

    /** Summary of the last notification seen from a watched app (no message content). */
    var lastSeen: String
        get() = p.getString("lastSeen", "") ?: ""
        set(v) = p.edit().putString("lastSeen", v).apply()

    val isSignedIn: Boolean get() = refreshToken.isNotEmpty()
    val isConfigured: Boolean get() = clientId.isNotEmpty() && isSignedIn && itemId.isNotEmpty() && sheet.isNotEmpty()

    fun signOut() {
        p.edit().remove("refreshToken").remove("accessToken").remove("accessExpiresAt").apply()
    }

    companion object {
        /** Your "Expense Log" Microsoft app registration, from local.properties at build time (see docs/SETUP.md). */
        val DEFAULT_CLIENT_ID: String = BuildConfig.CLIENT_ID
        /** SMS apps, so bank text alerts are covered out of the box. */
        val DEFAULT_WATCHED = setOf("com.google.android.apps.messaging", "com.oneplus.mms", "com.android.mms")
        const val DEFAULT_TEST_TEXT =
            "Your Test Bank Rewards Visa credit card ending in 0000 was charged \$12.34 at TEST STORE."
        // Default Excel file and sheet, also from local.properties; all can be changed in the app's Settings.
        val DEFAULT_ITEM_ID: String = BuildConfig.ITEM_ID
        val DEFAULT_FILE_NAME: String = BuildConfig.FILE_NAME.ifEmpty { "Payments" }
        val DEFAULT_SHEET: String = BuildConfig.SHEET.ifEmpty { "Expenditures" }
    }
}
