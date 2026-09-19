package com.pickle.patcher.data

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.io.DEFAULT_BUFFER_SIZE

/**
 * Minimal GitHub Releases client. Now API-quota-free: it resolves the latest
 * tag through the github.com redirect and downloads release assets straight
 * from the releases/download CDN path. No api.github.com calls remain, so the
 * per-IP 60 req/hour rate limit is never hit by the app's polling loop.
 */
object ReleaseRepository {

    /**
     * Raised on GitHub HTTP 403 (per-IP API quota or CDN download throttling).
     * Carries the server's Retry-After hint so callers can back off politely.
     */
    class GitHubRateLimited(
        val retryAfterSeconds: Long?,
        message: String,
    ) : IOException(message)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val webClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Resolves the latest release tag via the github.com redirect
     * (…/releases/latest -> …/releases/tag/vX). Costs no API quota,
     * unlike /releases/latest on api.github.com (60 req/hour shared).
     * Returns null on any failure (caller backs off).
     */
    suspend fun latestTagRedirect(repo: String): String? {
        return try {
            val req = Request.Builder()
                .url("https://github.com/$repo/releases/latest")
                .header("User-Agent", "cs16-amxx-patcher")
                .head()
                .build()
            webClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val finalUrl = resp.request.url.toString()
                finalUrl.substringAfterLast("/releases/tag/", "").ifBlank { null }
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Builds a direct release-asset download URL. Unlike
     * `api.github.com/.../browser_download_url`, this never touches the API
     * quota, so it is safe to construct on every poll.
     */
    fun assetUrl(repo: String, tag: String, name: String): String =
        "https://github.com/$repo/releases/download/$tag/$name"

    /**
     * HEAD-probes a release asset. Returns its Content-Length (0 = no length)
     * or null when the asset does not exist / the request failed. Zero API cost.
     */
    suspend fun probeSize(url: String): Long? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "cs16-amxx-patcher")
                .head()
                .build()
            webClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null
                else resp.body?.contentLength()?.coerceAtLeast(0L) ?: 0L
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * GETs a small release text asset (e.g. version.txt from the Continuous
     * release). Returns the trimmed body or null on any failure.
     */
    suspend fun fetchText(url: String): String? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "cs16-amxx-patcher")
                .get()
                .build()
            webClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null
                else resp.body?.string()?.trim().orEmpty().ifBlank { null }
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** Seconds from a Retry-After header (integer or HTTP-date). */
    private fun retryAfterSeconds(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        raw.trim().toLongOrNull()?.let { return it }
        return try {
            val fmt = java.text.SimpleDateFormat(
                "EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US
            )
            val whenTo = fmt.parse(raw.trim()) ?: return null
            ((whenTo.time - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
        } catch (_: Throwable) {
            null
        }
    }

    suspend fun downloadUrl(
        url: String,
        dest: File,
        knownSize: Long = 0,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): File {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "cs16-amxx-patcher")
            .header("Accept", "application/octet-stream")
            .build()
        client.newCall(req).execute().use { resp ->
            if (resp.code == 403) {
                val wait = retryAfterSeconds(resp.header("Retry-After"))
                val hint = wait?.let {
                    " (>1 min)" + if (it >= 60) " — retry in ${it / 60} min" else ""
                } ?: ""
                throw GitHubRateLimited(
                    wait,
                    "GitHub is rate-limiting this network right now (HTTP 403$hint). " +
                        "Wait a bit and try again.",
                )
            }
            if (!resp.isSuccessful) throw IOException("Download ${resp.code}: ${resp.message}")
            dest.parentFile?.mkdirs()
            val body = resp.body
                ?: throw IOException("Empty response body")
            val total = knownSize.takeIf { it > 0 }
                ?: body.contentLength().takeIf { it > 0 }
                ?: 0L
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var read = 0L
            body.byteStream().use { input ->
                dest.outputStream().use { output ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        read += n
                        onProgress(read, total)
                    }
                }
            }
        }
        return dest
    }
}