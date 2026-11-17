package com.expenselog.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Backs up the app's settings, everything it has learned (card wording, merchant names, notes, categories,
 * statement days) and the Detected and Recently-ignored lists to ExpenseLog/backup.json in your OneDrive,
 * and restores them. Sign-in tokens are never included: after a reinstall you sign in again, then restore.
 */
object Backup {
    private const val URL = "/me/drive/root:/ExpenseLog/backup.json:/content"

    /** Settings that belong to this install only and must not be copied. */
    private val skip = setOf(
        "refreshToken", "accessToken", "accessExpiresAt", "rowsCache", "lastAdd",
        "updateCode", "updateName", "updateNotes", "lastUpdateCheck", "updateMessage",
        "autoTriedCode", "autoTriedAt", "updatingFrom"
    )

    /** Blocking. Returns a one-line summary. */
    fun save(c: Context): String {
        val store = Store(c)
        val prefs = JSONObject()
        c.getSharedPreferences("expenselog", Context.MODE_PRIVATE).all.forEach { (k, v) ->
            if (k in skip) return@forEach
            when (v) {
                is String -> prefs.put(k, JSONObject().put("t", "s").put("v", v))
                is Boolean -> prefs.put(k, JSONObject().put("t", "b").put("v", v))
                is Int -> prefs.put(k, JSONObject().put("t", "i").put("v", v))
                is Long -> prefs.put(k, JSONObject().put("t", "l").put("v", v))
                is Set<*> -> prefs.put(k, JSONObject().put("t", "set").put("v", JSONArray(v.map { it.toString() })))
            }
        }
        val pending = JSONArray().apply { PendingStore.all(c).forEach { put(it.toJson()) } }
        val ignored = JSONArray().apply {
            IgnoredStore.all(c).forEach { (at, p) -> put(JSONObject().put("at", at).put("p", p.toJson())) }
        }
        val body = JSONObject()
            .put("app", "Expense Log").put("version", Updater.installedName(c)).put("savedAt", System.currentTimeMillis())
            .put("settings", prefs).put("detected", pending).put("ignored", ignored)
        Graph.putJson(store, URL, body)
        return "Backed up ${prefs.length()} settings, ${pending.length()} detected and ${ignored.length()} ignored payments to OneDrive (ExpenseLog/backup.json)."
    }

    /** Blocking. Restores the last backup over what is on the phone. Returns a one-line summary. */
    fun restore(c: Context): String {
        val store = Store(c)
        val b = Graph.getJsonOrNull(store, URL) ?: return "No backup found in OneDrive yet."
        val prefs = b.getJSONObject("settings")
        val edit = c.getSharedPreferences("expenselog", Context.MODE_PRIVATE).edit()
        prefs.keys().forEach { k ->
            if (k in skip) return@forEach
            val o = prefs.getJSONObject(k)
            when (o.getString("t")) {
                "s" -> edit.putString(k, o.getString("v"))
                "b" -> edit.putBoolean(k, o.getBoolean("v"))
                "i" -> edit.putInt(k, o.getInt("v"))
                "l" -> edit.putLong(k, o.getLong("v"))
                "set" -> {
                    val a = o.getJSONArray("v")
                    edit.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet())
                }
            }
        }
        edit.commit()
        val detected = b.optJSONArray("detected") ?: JSONArray()
        for (i in 0 until detected.length()) PendingStore.add(c, Pending.fromJson(detected.getJSONObject(i)))
        val ignored = b.optJSONArray("ignored") ?: JSONArray()
        for (i in 0 until ignored.length()) IgnoredStore.add(c, Pending.fromJson(ignored.getJSONObject(i).getJSONObject("p")))
        Prompts.update(c, alert = false)
        val from = b.optString("version")
        return "Restored ${prefs.length()} settings, ${detected.length()} detected and ${ignored.length()} ignored payments (backup from version $from)."
    }
}
