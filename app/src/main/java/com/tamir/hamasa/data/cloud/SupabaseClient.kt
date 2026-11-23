package com.tamir.hamasa.data.cloud

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Minimal PostgREST client over HttpURLConnection + org.json. Never throws: upsert → false, select → empty array (selectOrNull → null). */
object SupabaseClient {
    private const val TAG = "SupabaseClient"

    /** POST rows to {url}/rest/v1/{table} with upsert semantics (merge on primary key). */
    suspend fun upsert(table: String, rows: JSONArray): Boolean = withContext(Dispatchers.IO) {
        if (rows.length() == 0) return@withContext true
        var conn: HttpURLConnection? = null
        try {
            conn = open("${CloudConfig.SUPABASE_URL}/rest/v1/$table", "POST")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Prefer", "resolution=merge-duplicates,return=minimal")
            conn.doOutput = true
            val body = rows.toString().toByteArray(Charsets.UTF_8)
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code in 200..299) {
                true
            } else {
                Log.w(TAG, "upsert $table failed: HTTP $code ${readAll(conn.errorStream)}")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "upsert $table error", e)
            false
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * GET {url}/rest/v1/{table}?{query} — `query` is a raw PostgREST query string (select=* is added when missing).
     * [profileCode] overrides the x-profile-code header (restore probes a code before switching to it).
     */
    suspend fun select(table: String, query: String, profileCode: String? = null): JSONArray = withContext(Dispatchers.IO) {
        selectOrNull(table, query, profileCode) ?: JSONArray()
    }

    /** Like [select] but null on any failure (HTTP error, no network), so callers can tell "empty" from "failed". */
    suspend fun selectOrNull(table: String, query: String, profileCode: String? = null): JSONArray? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val q = if (query.contains("select=")) query else (if (query.isBlank()) "select=*" else "$query&select=*")
            conn = open("${CloudConfig.SUPABASE_URL}/rest/v1/$table?$q", "GET", profileCode)
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code in 200..299) {
                val text = readAll(conn.inputStream)
                if (text.isBlank()) JSONArray() else JSONArray(text)
            } else {
                Log.w(TAG, "select $table failed: HTTP $code ${readAll(conn.errorStream)}")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "select $table error", e)
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * HTTP status of a GET (no body read), or -1 when there was no answer at all. Used to probe whether a column
     * exists (200) or not (400) without caring about rows.
     */
    suspend fun probe(table: String, query: String): Int = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = open("${CloudConfig.SUPABASE_URL}/rest/v1/$table?$query", "GET", null)
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code !in 200..299) Log.i(TAG, "probe $table: HTTP $code ${readAll(conn.errorStream).take(200)}")
            code
        } catch (e: Exception) {
            Log.w(TAG, "probe $table error", e)
            -1
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Every Supabase request carries the anon key AND the user's recovery code (header x-profile-code, see
     * [Account] and docs/multiuser.sql). Before that SQL runs the header is simply ignored by the old policies.
     */
    private fun open(url: String, method: String, profileCode: String? = null): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = CloudConfig.TIMEOUT_MS
        conn.readTimeout = CloudConfig.TIMEOUT_MS
        conn.setRequestProperty("apikey", CloudConfig.SUPABASE_ANON_KEY)
        conn.setRequestProperty("Authorization", "Bearer ${CloudConfig.SUPABASE_ANON_KEY}")
        val code = profileCode ?: Account.profileCodeOrNull()
        if (!code.isNullOrBlank()) conn.setRequestProperty(CloudConfig.PROFILE_HEADER, code)
        return conn
    }

    private fun readAll(stream: InputStream?): String {
        if (stream == null) return ""
        return try {
            stream.bufferedReader(Charsets.UTF_8).use(BufferedReader::readText)
        } catch (e: Exception) {
            ""
        }
    }
}
