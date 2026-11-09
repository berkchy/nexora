package com.pickle.patcher.data
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.BufferedSink
import okio.Source
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit



/**
 * Minimal client for the Nexora Store API. Deliberately thin: every call
 * returns a Result so the UI can show the error without guessing which
 * failure mode hit.
 */
class StoreApi(private val baseUrl: String, private val context: android.content.Context? = null) {

    data class LibUpload(val name: String, val url: String, val size: Long)
    data class CloudinaryUpload(val url: String, val bytes: Long)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = "application/json; charset=utf-8".toMediaType()

    private var _token: String? = context?.let { com.pickle.patcher.data.StoreSession.token(it) }
    var token: String?
        get() = context?.let { com.pickle.patcher.data.StoreSession.token(it) } ?: _token
        set(value) { _token = value }

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

    suspend fun verifyEmail(email: String, code: String): Result<JSONObject> =
        post("/api/auth/verify-email", JSONObject().apply {
            put("email", email); put("code", code)
        }.toString())

    suspend fun resendVerification(email: String): Result<JSONObject> =
        post("/api/auth/resend-verification", JSONObject().apply {
            put("email", email)
        }.toString())

    suspend fun me(): Result<JSONObject> = get("/api/auth/me", null)

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

    suspend fun signUpload(filename: String = "file.bin", exclude: Set<String> = emptySet(), provider: String? = null): Result<JSONObject> {
        val providerQuery = if (provider == null) "" else "&provider=${URLEncoder.encode(provider, "UTF-8")}"
        val excludeQuery = if (exclude.isEmpty()) "" else "&exclude=${URLEncoder.encode(exclude.joinToString(","), "UTF-8")}"
        return get("/api/upload/sign?filename=${URLEncoder.encode(filename, "UTF-8")}$providerQuery$excludeQuery", token)
    }

    private suspend fun uploadWithSign(
        file: File,
        sign: JSONObject,
        onProgress: (Long, Long) -> Unit,
    ): Result<CloudinaryUpload> = withContext(Dispatchers.IO) {
        val provider = sign.optString("provider", "cloudinary")
        if (provider == "vps") {
            val uploadUrl = sign.optString("uploadUrl")
            val publicBaseUrl = sign.optString("publicBaseUrl")
            val vpsToken = sign.optString("token")
            if (uploadUrl.isBlank() || publicBaseUrl.isBlank() || vpsToken.isBlank()) return@withContext Result.failure(Exception("Invalid VPS sign"))
            val req = Request.Builder()
                .url(uploadUrl)
                .post(ProgressRequestBody(file, "application/octet-stream", onProgress))
                .header("Content-Type", "application/octet-stream")
                .header("Authorization", "Bearer $vpsToken")
                .build()
            try {
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: ""
                    val json = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
                    if (!resp.isSuccessful || !json.optBoolean("ok", false)) {
                        return@withContext Result.failure(Exception(json.optString("error", "VPS upload failed: HTTP ${resp.code}")))
                    }
                    val path = json.optString("path")
                    if (!path.startsWith("/files/")) return@withContext Result.failure(Exception("No path from VPS storage"))
                    Result.success(CloudinaryUpload(publicBaseUrl.trimEnd('/') + path, json.optLong("size", file.length())))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        } else if (provider == "supabase") {
            val uploadUrl = sign.optString("uploadUrl")
            val publicUrl = sign.optString("publicUrl")
            val apiKey = sign.optString("apiKey")
            if (uploadUrl.isBlank() || publicUrl.isBlank() || apiKey.isBlank()) return@withContext Result.failure(Exception("Invalid supabase sign"))
            val req = Request.Builder()
                .url(uploadUrl)
                .put(ProgressRequestBody(file, "application/octet-stream", onProgress))
                .header("Content-Type", "application/octet-stream")
                .header("Authorization", "Bearer $apiKey")
                .header("apikey", apiKey)
                .build()
            try {
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: ""
                    if (!resp.isSuccessful) return@withContext Result.failure(Exception("Supabase upload failed: ${resp.code} $body"))
                    Result.success(CloudinaryUpload(publicUrl, file.length()))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        } else {
            val cloudName = sign.optString("cloudName")
            val apiKey = sign.optString("apiKey")
            val timestamp = sign.optLong("timestamp")
            val signature = sign.optString("signature")
            if (cloudName.isBlank() || apiKey.isBlank() || signature.isBlank()) {
                return@withContext Result.failure(Exception("Invalid sign response"))
            }
            val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", file.name, ProgressRequestBody(file, "application/octet-stream", onProgress))
                .addFormDataPart("api_key", apiKey)
                .addFormDataPart("timestamp", timestamp.toString())
                .addFormDataPart("signature", signature)
                .build()
            val req = Request.Builder()
                .url("https://api.cloudinary.com/v1_1/$cloudName/raw/upload")
                .post(form)
                .build()
            try {
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: ""
                    val json = try { JSONObject(body) } catch (_: Exception) { JSONObject().put("raw", body) }
                    if (!resp.isSuccessful) return@withContext Result.failure(Exception(json.optString("error", "Cloudinary HTTP ${resp.code}")))
                    val url = json.optString("secure_url")
                    if (url.isBlank()) return@withContext Result.failure(Exception("No url from cloudinary"))
                    Result.success(CloudinaryUpload(url, json.optLong("bytes")))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun uploadFile(file: File, onProgress: (Long, Long) -> Unit = { _, _ -> }): Result<CloudinaryUpload> = withContext(Dispatchers.IO) {
        val providerOrder = listOf("vps", "supabase", "cloudinary")
        var lastError: Exception? = null
        for (provider in providerOrder) {
            val signResult = signUpload(file.name, provider = provider)
            if (signResult.isFailure) {
                lastError = Exception(signResult.exceptionOrNull()?.message ?: "$provider sign failed")
                continue
            }
            val sign = signResult.getOrThrow()
            val result = uploadWithSign(file, sign, onProgress)
            if (result.isSuccess) return@withContext result
            lastError = result.exceptionOrNull() as? Exception ?: Exception("$provider upload failed")
        }

        Result.failure(lastError ?: Exception("All upload providers failed"))
    }

    suspend fun createItem(
        token: String,
        title: String,
        description: String,
        version: String,
        logoUrl: String?,
        libs: List<LibUpload>,
        links: List<Pair<String?, String>> = emptyList(),
    ): Result<JSONObject> {
        val body = JSONObject().apply {
            put("title", title)
            put("description", description)
            put("version", version)
            if (logoUrl != null) put("logoUrl", logoUrl)
            val arr = org.json.JSONArray()
            libs.forEach { l ->
                arr.put(org.json.JSONObject().apply {
                    put("name", l.name); put("url", l.url); put("size", l.size)
                })
            }
            put("libs", arr)
            val larr = org.json.JSONArray()
            links.forEach { (label, url) ->
                larr.put(org.json.JSONObject().apply {
                    if (!label.isNullOrBlank()) put("label", label)
                    put("url", url)
                })
            }
            put("links", larr)
        }.toString()
        val req = Request.Builder()
            .url("$baseUrl/api/items")
            .header("Authorization", "Bearer ${token ?: this@StoreApi.token}")
            .post(body.toRequestBody(json))
            .build()
        return execute(req)
    }

    suspend fun updateItem(
        token: String,
        id: String,
        title: String? = null,
        description: String? = null,
        version: String? = null,
        logoUrl: String? = null,
        hasLogo: Boolean = false,
        libs: List<LibUpload>? = null,
        links: List<Pair<String?, String>>? = null,
    ): Result<JSONObject> =
        patch("/api/items/$id", JSONObject().apply {
            if (title != null) put("title", title)
            if (description != null) put("description", description)
            if (version != null) put("version", version)
            if (hasLogo) put("logoUrl", logoUrl)
            if (libs != null) {
                val arr = org.json.JSONArray()
                libs.forEach { l ->
                    arr.put(org.json.JSONObject().apply {
                        put("name", l.name); put("url", l.url); put("size", l.size)
                    })
                }
                put("libs", arr)
            }
            if (links != null) {
                val arr = org.json.JSONArray()
                links.forEach { (label, url) ->
                    arr.put(org.json.JSONObject().apply {
                        if (!label.isNullOrBlank()) put("label", label)
                        put("url", url)
                    })
                }
                put("links", arr)
            }
        }.toString(), token)

    private fun storagePathFromUrl(value: String): String? = runCatching {
        val path = if (value.startsWith("/files/")) value else java.net.URL(value).path
        if (!path.startsWith("/files/") || path.contains("..") || path.split('/').size != 5) return null
        path
    }.getOrNull()

    suspend fun deleteStorageFile(token: String, pathOrUrl: String): Result<JSONObject> {
        val path = storagePathFromUrl(pathOrUrl)
            ?: return Result.failure(Exception("Not a VPS storage path"))
        return authPost("/api/storage/delete", JSONObject().put("path", path).toString(), token)
    }

    suspend fun deleteItem(token: String, id: String): Result<JSONObject> =
        delete("/api/items/$id", token)

    suspend fun incrementLibDownload(itemId: String, libId: String): Result<Int> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$baseUrl/api/items/$itemId/libs/$libId/download")
            .post("{}".toRequestBody(json))
            .build()
        execute(req).map { it.optInt("downloads") }
    }

    suspend fun getComments(itemId: String): Result<JSONArray> = withContext(Dispatchers.IO) {
        get("/api/items/$itemId/comments", null).map { it.optJSONArray("data") ?: org.json.JSONArray() }
    }

    suspend fun postComment(token: String, itemId: String, content: String): Result<JSONObject> = withContext(Dispatchers.IO) {
        authPost("/api/items/$itemId/comments", JSONObject().put("content", content).toString(), token)
    }

    suspend fun toggleLike(token: String, itemId: String): Result<Pair<Boolean, Int>> = withContext(Dispatchers.IO) {
        authPost("/api/items/$itemId/like", "{}", token).map { Pair(it.optBoolean("liked"), it.optInt("likesCount")) }
    }

    suspend fun getUserProfile(username: String): Result<JSONObject> =
        get("/api/users/$username", null)

    // ---- admin ----

    suspend fun adminListItems(token: String, status: String? = null): Result<JSONObject> {
        val path = if (status != null) "/api/admin/items?status=$status" else "/api/admin/items"
        return get(path, token)
    }

    suspend fun adminPatchItem(token: String, id: String, status: String): Result<JSONObject> =
        patch("/api/admin/items/$id", JSONObject().put("status", status).toString(), token)

    suspend fun adminDeleteItem(token: String, id: String): Result<JSONObject> =
        delete("/api/admin/items/$id", token)

    suspend fun adminListUsers(token: String): Result<JSONObject> =
        get("/api/admin/users", token)

    suspend fun adminPatchUser(token: String, id: String, role: String? = null, displayName: String? = null, permissions: List<String>? = null): Result<JSONObject> =
        patch("/api/admin/users/$id", JSONObject().apply {
            if (role != null) put("role", role)
            if (displayName != null) put("displayName", displayName)
            if (permissions != null) {
                val arr = org.json.JSONArray()
                permissions.forEach { arr.put(it) }
                put("permissions", arr)
            }
        }.toString(), token)

    suspend fun adminDeleteUser(token: String, id: String): Result<JSONObject> =
        delete("/api/admin/users/$id", token)

    // ---- helpers ----

    private suspend fun get(path: String, token: String?): Result<JSONObject> = withContext(Dispatchers.IO) {
        val b = Request.Builder().url("$baseUrl$path")
        val t = token ?: this@StoreApi.token
        if (t != null) b.header("Authorization", "Bearer $t")
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
        val t = token ?: this@StoreApi.token
        if (t != null) b.header("Authorization", "Bearer $t")
        execute(b.build())
    }

    private suspend fun authPost(path: String, body: String, token: String?): Result<JSONObject> = withContext(Dispatchers.IO) {
        val b = Request.Builder().url("$baseUrl$path")
            .post(body.toRequestBody(json))
        val t = token ?: this@StoreApi.token
        if (t != null) b.header("Authorization", "Bearer $t")
        execute(b.build())
    }

    private suspend fun delete(path: String, token: String?): Result<JSONObject> = withContext(Dispatchers.IO) {
        val b = Request.Builder().url("$baseUrl$path").delete()
        val t = token ?: this@StoreApi.token
        if (t != null) b.header("Authorization", "Bearer $t")
        execute(b.build())
    }

    private suspend fun multipart(path: String, field: String, file: File, token: String?): Result<JSONObject> = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart(field, file.name, file.asRequestBody())
            .build()
        val b = Request.Builder().url("$baseUrl$path").post(body)
        val t = token ?: this@StoreApi.token
        if (t != null) b.header("Authorization", "Bearer $t")
        execute(b.build())
    }

    private suspend fun execute(req: Request): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                val json = try { JSONObject(body) } catch (_: Exception) { JSONObject().put("raw", body) }
                if (resp.isSuccessful) Result.success(json)
                else {
                    val serverMsg = json.optString("error", "").takeIf { it.isNotBlank() }
                    val friendly = when (resp.code) {
                        413 -> "File too large (max ~4.5MB per request)"
                        429 -> serverMsg ?: "Too many requests, try later"
                        401 -> "Not authorized, please log in"
                        404 -> "Not found"
                        else -> serverMsg ?: "HTTP ${resp.code}"
                    }
                    Result.failure(Exception(friendly))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private class ProgressRequestBody(
        private val file: File,
        private val contentType: String,
        private val onProgress: (Long, Long) -> Unit,
    ) : RequestBody() {
        override fun contentType() = contentType.toMediaType()
        override fun contentLength() = file.length()
        override fun writeTo(sink: okio.BufferedSink) {
            val source = file.inputStream().source()
            val total = file.length()
            var written = 0L
            val buf = okio.Buffer()
            val chunk = 8192L
            while (true) {
                val read = source.read(buf, chunk)
                if (read == -1L) break
                sink.write(buf, read)
                written += read
                onProgress(written, total)
            }
            source.close()
        }
    }

    private fun File.asRequestBody() = okhttp3.RequestBody.create("application/octet-stream".toMediaType(), this)
}