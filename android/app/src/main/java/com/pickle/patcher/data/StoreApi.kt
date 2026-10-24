package com.pickle.patcher.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Minimal client for the Nexora Store API. Deliberately thin: every call
 * returns a Result so the UI can show the error without guessing which
 * failure mode hit.
 */
class StoreApi(private val baseUrl: String) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = "application/json; charset=utf-8".toMediaType()

    // ---- auth ----

    suspend fun register(email: String, password: String, username: String): Result<JSONObject> =
        post("/api/auth/register", JSONObject().apply {
            put("email", email); put("password", password); put("username", username)
        }.toString())

    suspend fun login(email: String, password: String): Result<JSONObject> =
        post("/api/auth/login", JSONObject().apply {
            put("email", email); put("password", password)
        }.toString())

    suspend fun logout(): Result<JSONObject> = post("/api/auth/logout", "{}")

    suspend fun me(token: String?): Result<JSONObject> =
        get("/api/auth/me", token)

    suspend fun updateProfile(token: String, displayName: String?, username: String?): Result<JSONObject> =
        patch("/api/auth/profile", JSONObject().apply {
            if (displayName != null) put("displayName", displayName)
            if (username != null) put("username", username)
        }.toString(), token)

    suspend fun uploadAvatar(token: String, file: File): Result<JSONObject> =
        multipart("/api/auth/avatar", "avatar", file, token)

    // ---- items ----

    suspend fun listItems(page: Int = 1, userId: String? = null): Result<JSONObject> {
        val url = buildString {
            append("/api/items?page=$page&limit=20")
            if (userId != null) append("&userId=$userId")
        }
        return get(url, null)
    }

    suspend fun getItem(id: String): Result<JSONObject> = get("/api/items/$id", null)

    suspend fun createItem(
        token: String,
        title: String,
        description: String,
        version: String,
        logo: File?,
        libs: List<File>,
    ): Result<JSONObject> {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
        builder.addFormDataPart("title", title)
        builder.addFormDataPart("description", description)
        builder.addFormDataPart("version", version)
        if (logo != null) builder.addFormDataPart("logo", logo.name, logo.asRequestBody())
        libs.forEachIndexed { i, f -> builder.addFormDataPart("lib_$i", f.name, f.asRequestBody()) }
        val body = builder.build()
        val req = Request.Builder()
            .url("$baseUrl/api/items")
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()
        return execute(req)
    }

    suspend fun updateItem(token: String, id: String, title: String?, description: String?, version: String?): Result<JSONObject> =
        patch("/api/items/$id", JSONObject().apply {
            if (title != null) put("title", title)
            if (description != null) put("description", description)
            if (version != null) put("version", version)
        }.toString(), token)

    suspend fun deleteItem(token: String, id: String): Result<JSONObject> =
        delete("/api/items/$id", token)

    suspend fun getUserProfile(username: String): Result<JSONObject> =
        get("/api/users/$username", null)

    // ---- helpers ----

    private suspend fun get(path: String, token: String?): Result<JSONObject> = withContext(Dispatchers.IO) {
        val b = Request.Builder().url("$baseUrl$path")
        if (token != null) b.header("Authorization", "Bearer $token")
        execute(b.get().build())
    }

    private suspend fun post(path: String, body: String): Result<JSONObject> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$baseUrl$path")
            .post(body.toRequestBody(json))
            .build()
        execute(req)
    }

    private suspend fun patch(path: String, body: String, token: String?): Result<JSONObject> = withContext(Dispatchers.IO) {
        val b = Request.Builder().url("$baseUrl$path")
            .patch(body.toRequestBody(json))
        if (token != null) b.header("Authorization", "Bearer $token")
        execute(b.build())
    }

    private suspend fun delete(path: String, token: String?): Result<JSONObject> = withContext(Dispatchers.IO) {
        val b = Request.Builder().url("$baseUrl$path").delete()
        if (token != null) b.header("Authorization", "Bearer $token")
        execute(b.build())
    }

    private suspend fun multipart(path: String, field: String, file: File, token: String?): Result<JSONObject> = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart(field, file.name, file.asRequestBody())
            .build()
        val b = Request.Builder().url("$baseUrl$path").post(body)
        if (token != null) b.header("Authorization", "Bearer $token")
        execute(b.build())
    }

    private fun execute(req: Request): Result<JSONObject> {
        return try {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                val json = try { JSONObject(body) } catch (_: Exception) { JSONObject().put("raw", body) }
                if (resp.isSuccessful) Result.success(json)
                else Result.failure(Exception(json.optString("error", "HTTP ${resp.code}")))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun File.asRequestBody() = okhttp3.RequestBody.create("application/octet-stream".toMediaType(), this)
}
