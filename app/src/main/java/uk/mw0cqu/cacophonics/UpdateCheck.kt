// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Rob (MW0CQU)

package uk.mw0cqu.cacophonics

import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * "Check for updates": asks GitHub which releases of this app are published, picks the highest
 * version (pre-releases included) and compares it with the installed version.
 * Nothing is sent except the request itself, and only when the user asks for the check.
 *
 * The callback arrives on a background thread.
 */
class UpdateCheck(
    /** The installed version name, for example "1.0.5". */
    private val current: String,
    private val cb: Callback
) {
    class Result(
        /** The newest published version, without a leading "V". */
        val latest: String,
        val newer: Boolean,
        /** The APK download address if the release has one, otherwise the release page. */
        val url: String
    )

    interface Callback {
        fun onResult(r: Result)
        fun onError(message: String)
    }

    companion object {
        private const val API = "https://api.github.com/repos/mw0cqu/Cacophonics/releases?per_page=30"

        private val client by lazy {
            OwrxClient.http.newBuilder()
                .readTimeout(8, TimeUnit.SECONDS)
                .callTimeout(12, TimeUnit.SECONDS)
                .pingInterval(0, TimeUnit.SECONDS)
                .build()
        }

        /** "V1.0.5" -> [1, 0, 5]. Anything that is not a number counts as 0. */
        fun parts(version: String): List<Int> =
            version.trim().trimStart('v', 'V').split('.').map { seg ->
                seg.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
            }

        /** Negative if a is older than b, 0 if the same, positive if a is newer. */
        fun compare(a: String, b: String): Int {
            val x = parts(a)
            val y = parts(b)
            for (i in 0 until maxOf(x.size, y.size)) {
                val d = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
                if (d != 0) return d
            }
            return 0
        }
    }

    fun start() {
        Thread { run() }.also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun run() {
        try {
            val request = Request.Builder()
                .url(API)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Cacophonics/$current")
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    cb.onError(
                        when (resp.code) {
                            403, 429 -> "GitHub says there have been too many checks. Try again later."
                            404 -> "The release list was not found (is the repository public?)."
                            else -> "GitHub replied with HTTP ${resp.code}."
                        }
                    )
                    return
                }
                val list = JSONArray(resp.body?.string().orEmpty())
                var bestTag = ""
                var bestUrl = ""
                for (k in 0 until list.length()) {
                    val o = list.optJSONObject(k) ?: continue
                    if (o.optBoolean("draft", false)) continue
                    val tag = o.optString("tag_name").trim()
                    if (tag.isEmpty()) continue
                    if (bestTag.isEmpty() || compare(tag, bestTag) > 0) {
                        bestTag = tag
                        bestUrl = o.optString("html_url")
                        val assets = o.optJSONArray("assets")
                        if (assets != null) {
                            for (j in 0 until assets.length()) {
                                val a = assets.optJSONObject(j) ?: continue
                                if (a.optString("name").endsWith(".apk", true)) {
                                    val dl = a.optString("browser_download_url")
                                    if (dl.isNotEmpty()) bestUrl = dl
                                    break
                                }
                            }
                        }
                    }
                }
                if (bestTag.isEmpty()) {
                    cb.onError("No releases were found on GitHub.")
                    return
                }
                cb.onResult(Result(bestTag.trimStart('v', 'V'), compare(bestTag, current) > 0, bestUrl))
            }
        } catch (_: java.net.UnknownHostException) {
            cb.onError("Could not reach GitHub. Check your internet connection.")
        } catch (_: java.net.SocketTimeoutException) {
            cb.onError("GitHub took too long to answer. Try again.")
        } catch (e: Exception) {
            cb.onError("Could not check for updates (${e.javaClass.simpleName}).")
        }
    }
}
