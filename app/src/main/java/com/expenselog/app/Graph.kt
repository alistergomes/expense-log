package com.expenselog.app

import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

class ApiException(message: String, val code: String = "") : Exception(message)

/** Microsoft sign-in (device-code flow) and the Excel calls. All functions block; call off the UI thread. */
object Graph {
    private const val AUTH = "https://login.microsoftonline.com/consumers/oauth2/v2.0"
    private const val API = "https://graph.microsoft.com/v1.0"
    private const val SCOPE = "Files.ReadWrite offline_access"
    private val JSON = "application/json".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // ---------- sign-in ----------

    /** Starts sign-in. Returns user_code, verification_uri, device_code, interval, expires_in. */
    fun startDeviceCode(clientId: String): JSONObject =
        postForm("$AUTH/devicecode", mapOf("client_id" to clientId, "scope" to SCOPE))

    /** One poll. Returns true when signed in, false while the user hasn't finished yet. */
    fun pollDeviceCode(store: Store, deviceCode: String): Boolean {
        try {
            val r = postForm(
                "$AUTH/token", mapOf(
                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                    "client_id" to store.clientId,
                    "device_code" to deviceCode
                )
            )
            saveTokens(store, r)
            return true
        } catch (e: ApiException) {
            if (e.code == "authorization_pending" || e.code == "slow_down") return false
            throw e
        }
    }

    private fun accessToken(store: Store): String {
        if (store.accessToken.isNotEmpty() && System.currentTimeMillis() < store.accessExpiresAt) {
            return store.accessToken
        }
        if (!store.isSignedIn) throw ApiException("Not signed in. Open Settings and sign in.")
        val r = try {
            postForm(
                "$AUTH/token", mapOf(
                    "grant_type" to "refresh_token",
                    "client_id" to store.clientId,
                    "refresh_token" to store.refreshToken,
                    "scope" to SCOPE
                )
            )
        } catch (e: ApiException) {
            if (e.code == "invalid_grant") {
                store.signOut()
                throw ApiException("Sign-in expired. Open Settings and sign in again.")
            }
            throw e
        }
        saveTokens(store, r)
        return store.accessToken
    }

    private fun saveTokens(store: Store, r: JSONObject) {
        store.accessToken = r.getString("access_token")
        store.accessExpiresAt = System.currentTimeMillis() + (r.optLong("expires_in", 3600) - 120) * 1000
        r.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { store.refreshToken = it }
    }

    // ---------- Excel ----------

    private fun workbook(store: Store) = "$API/me/drive/items/${enc(store.itemId)}/workbook"
    private fun sheetUrl(store: Store) = "${workbook(store)}/worksheets('${enc(store.sheet.replace("'", "''"))}')"

    /** Tab names in the configured workbook. */
    fun listSheets(store: Store): List<String> {
        val arr = call(store, "GET", "${workbook(store)}/worksheets?\$select=name").getJSONArray("value")
        return (0 until arr.length()).map { arr.getJSONObject(it).getString("name") }
    }

    /** Finds an .xlsx by name in OneDrive. Returns (id, name) or null. */
    fun findFile(store: Store, name: String): Pair<String, String>? {
        val q = enc(name.replace("'", "''"))
        val arr = call(store, "GET", "$API/me/drive/root/search(q='$q')?\$select=id,name").getJSONArray("value")
        val items = (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { it.optString("name").endsWith(".xlsx", ignoreCase = true) }
        val best = items.firstOrNull { it.getString("name").removeSuffix(".xlsx").equals(name.removeSuffix(".xlsx"), true) }
            ?: items.firstOrNull()
        return best?.let { it.getString("id") to it.getString("name") }
    }

    /** Writes one row below the last used row of the sheet. Returns the row number written. */
    /** One sheet row: A date+time, B amount, C method, D purpose, E notes, F category. */
    data class Row(
        val number: Int,
        val at: LocalDateTime?,
        val amount: Double?,
        val method: String,
        val purpose: String,
        val notes: String,
        val category: String
    )

    /** Excel stores date-times as days since 1899-12-30 with the time as a fraction of a day. */
    fun toSerial(t: LocalDateTime): Double = t.toLocalDate().toEpochDay() + 25569 + t.toLocalTime().toSecondOfDay() / 86400.0

    fun fromSerial(v: Double): LocalDateTime {
        val day = Math.floor(v).toLong()
        val secs = Math.round((v - day) * 86400).coerceIn(0, 86399)
        return LocalDate.ofEpochDay(day - 25569).atTime(LocalTime.ofSecondOfDay(secs))
    }

    /** All data rows of the sheet (the header row is skipped). */
    fun readRows(store: Store): List<Row> {
        val used = call(store, "GET", "${sheetUrl(store)}/usedRange(valuesOnly=true)?\$select=address,values")
        val start = Regex("!?[A-Z]+(\\d+)").find(used.getString("address").substringAfter('!'))
            ?.groupValues?.get(1)?.toInt() ?: 1
        val values = used.getJSONArray("values")
        return (0 until values.length()).mapNotNull<Int, Row> { i ->
            val r = values.getJSONArray(i)
            val n = start + i
            if (n == 1) return@mapNotNull null
            fun s(k: Int) = if (k < r.length()) r.opt(k)?.toString().orEmpty() else ""
            Row(
                n,
                (r.opt(0) as? Number)?.toDouble()?.let { fromSerial(it) },
                (r.opt(1) as? Number)?.toDouble(),
                s(2), s(3), s(4), s(5)
            )
        }.also { Insights.cache(store, it) } // keeps a local copy for the Sheet/Totals screens and unusual-charge flags
    }

    /** Writes one row below the last used row. Returns the row number written. */
    fun appendRow(
        store: Store, at: LocalDateTime, amount: Double, method: String, purpose: String, notes: String, category: String
    ): Int {
        val used = call(store, "GET", "${sheetUrl(store)}/usedRange(valuesOnly=true)?\$select=address")
        val lastRow = Regex("(\\d+)$").find(used.getString("address"))?.value?.toInt()
            ?: throw ApiException("Could not read the sheet's used range.")
        val row = lastRow + 1
        writeRow(store, row, at, amount, method, purpose, notes, category)
        return row
    }

    /** Overwrites row [row] (A–F) with the given values. */
    fun writeRow(
        store: Store, row: Int, at: LocalDateTime, amount: Double, method: String, purpose: String, notes: String, category: String
    ) {
        val values = JSONArray().put(
            JSONArray().put(toSerial(at)).put(amount).put(text(method)).put(text(purpose)).put(text(notes)).put(text(category))
        )
        // Date and time in A, amount as a number, the rest as text ("@").
        val formats = JSONArray().put(
            JSONArray().put("m/d/yyyy h:mm AM/PM").put("0.00").put("@").put("@").put("@").put("@")
        )
        val body = JSONObject().put("values", values).put("numberFormat", formats)
        call(store, "PATCH", "${sheetUrl(store)}/range(address='A$row:F$row')", body)
    }

    /** Deletes row [row] (A–F), moving the rows below it up. */
    fun deleteRow(store: Store, row: Int) {
        call(store, "POST", "${sheetUrl(store)}/range(address='A$row:F$row')/delete", JSONObject().put("shift", "Up"))
    }

    // ---------- files (used by the self-updater) ----------

    /** GETs a small JSON file from OneDrive by API path; null if it does not exist. */
    fun getJsonOrNull(store: Store, path: String): JSONObject? {
        val req = Request.Builder().url(API + path).header("Authorization", "Bearer ${accessToken(store)}").build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 404) return null
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ApiException("Could not read the update information (HTTP ${resp.code}).")
            return runCatching { JSONObject(body) }.getOrNull()
        }
    }

    /** Writes a small JSON file to OneDrive by API path (creating or replacing it). */
    fun putJson(store: Store, path: String, body: JSONObject) {
        val req = Request.Builder().url(API + path)
            .header("Authorization", "Bearer ${accessToken(store)}")
            .put(body.toString().toRequestBody(JSON))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw ApiException("Could not write to OneDrive (HTTP ${resp.code}).")
        }
    }

    /** Downloads a OneDrive file by API path to [out], reporting progress as 0..100. */
    fun download(store: Store, path: String, out: java.io.File, onProgress: (Int) -> Unit) {
        val req = Request.Builder().url(API + path).header("Authorization", "Bearer ${accessToken(store)}").build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 404) throw ApiException("No update file has been published.")
            if (!resp.isSuccessful) throw ApiException("Download failed (HTTP ${resp.code}).")
            val body = resp.body ?: throw ApiException("Download failed (empty response).")
            val total = body.contentLength()
            body.byteStream().use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done * 100 / total).toInt())
                    }
                }
            }
        }
    }

    /** Stops Excel treating typed text as a formula. */
    private fun text(s: String): String {
        val t = s.trim()
        return if (t.isNotEmpty() && t[0] in "=+-@") "'$t" else t
    }

    // ---------- plumbing ----------

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun call(store: Store, method: String, url: String, body: JSONObject? = null): JSONObject {
        val req = Request.Builder().url(url)
            .header("Authorization", "Bearer ${accessToken(store)}")
            .method(method, body?.toString()?.toRequestBody(JSON))
            .build()
        http.newCall(req).execute().use { resp ->
            val textBody = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val err = runCatching { JSONObject(textBody).getJSONObject("error") }.getOrNull()
                val code = err?.optString("code").orEmpty()
                val msg = err?.optString("message") ?: "HTTP ${resp.code}"
                throw ApiException(
                    when {
                        resp.code == 404 || code == "itemNotFound" ->
                            "File or sheet not found. Check the File ID and sheet name in Settings. ($msg)"
                        else -> "Excel error: $msg"
                    }, code
                )
            }
            return if (textBody.isBlank()) JSONObject() else JSONObject(textBody)
        }
    }

    private fun postForm(url: String, fields: Map<String, String>): JSONObject {
        val form = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        http.newCall(Request.Builder().url(url).post(form).build()).execute().use { resp ->
            val textBody = resp.body?.string().orEmpty()
            val json = runCatching { JSONObject(textBody) }.getOrElse { JSONObject() }
            if (!resp.isSuccessful) {
                val code = json.optString("error")
                val desc = json.optString("error_description").lineSequence().firstOrNull().orEmpty()
                throw ApiException("Sign-in error: ${desc.ifEmpty { "HTTP ${resp.code}" }}", code)
            }
            return json
        }
    }
}
