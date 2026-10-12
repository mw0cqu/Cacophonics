package uk.mw0cqu.cacophonics

import okhttp3.Credentials
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * "Connect" for an Icecast server: finds the mountpoints it offers.
 * It reads the public status page (/status-json.xsl); if that lists nothing and a password was
 * given, it asks the admin page (/admin/listmounts), which also shows hidden mounts.
 * When something goes wrong the message says what, including the HTTP code where there is one.
 *
 * The callback arrives on a background thread.
 */
class IcecastProbe(
    /** e.g. "http://192.168.1.50:8000", from [baseFrom]. */
    private val base: String,
    private val user: String,
    private val pass: String,
    private val cb: Callback
) {
    class Mount(val path: String, val label: String)

    interface Callback {
        fun onResult(mounts: List<Mount>)
        fun onError(message: String)
    }

    private sealed class Reply {
        class Ok(val code: Int, val body: String) : Reply()
        class Err(val message: String) : Reply()
    }

    companion object {
        /**
         * Builds "http://host:port" from what is typed in the server and port boxes. A port typed
         * in the server box wins; if there is none anywhere it is 8000 (443 for https).
         */
        fun baseFrom(server: String, port: String): String? {
            var u = server.trim()
            if (u.isEmpty()) return null
            if (!u.contains("://")) u = "http://$u"
            val uri = try {
                URI(u)
            } catch (_: Exception) {
                return null
            }
            val host = uri.host ?: return null
            val https = uri.scheme.equals("https", true)
            val p = if (uri.port > 0) uri.port else port.trim().toIntOrNull() ?: if (https) 443 else 8000
            return "${if (https) "https" else "http"}://$host:$p"
        }

        /** The mountpoint part of a URL, e.g. "/radio1.mp3" (empty if there is none). */
        fun pathOf(raw: String): String {
            var u = raw.trim()
            if (u.isEmpty()) return ""
            if (!u.contains("://")) u = "http://$u"
            return try {
                val p = URI(u).path.orEmpty()
                if (p.length > 1) p else ""
            } catch (_: Exception) {
                ""
            }
        }

        /** The full stream URL to save: server + port + mount. */
        fun build(server: String, port: String, mount: String): String {
            val b = baseFrom(server, port) ?: return ""
            val m = mount.ifBlank { pathOf(server) }
            return when {
                m.isBlank() -> b
                m.startsWith("/") -> b + m
                else -> "$b/$m"
            }
        }

        /** A saved stream URL -> (server box text, port box text, mount). */
        fun split(url: String): Triple<String, String, String> {
            var u = url.trim()
            if (u.isEmpty()) return Triple("", "8000", "")
            if (!u.contains("://")) u = "http://$u"
            return try {
                val uri = URI(u)
                val https = uri.scheme.equals("https", true)
                val port = if (uri.port > 0) uri.port.toString() else if (https) "443" else "8000"
                Triple((if (https) "https://" else "") + uri.host, port, pathOf(url))
            } catch (_: Exception) {
                Triple(url.trim(), "8000", "")
            }
        }

        private fun reason(code: Int): String = when (code) {
            401 -> "login required or wrong"
            403 -> "access refused"
            404 -> "page not found (not an Icecast server, or its status page is switched off)"
            in 500..599 -> "the server reported an error"
            else -> "unexpected reply"
        }

        private val client by lazy {
            OwrxClient.http.newBuilder()
                .readTimeout(8, TimeUnit.SECONDS)
                .callTimeout(12, TimeUnit.SECONDS)
                .pingInterval(0, TimeUnit.SECONDS)
                .build()
        }
    }

    fun start() {
        Thread { run() }.also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun get(url: String, auth: Boolean): Reply = try {
        val rb = Request.Builder().url(url)
        if (auth) rb.header("Authorization", Credentials.basic(user.ifBlank { "admin" }, pass))
        client.newCall(rb.build()).execute().use { r -> Reply.Ok(r.code, r.body?.string().orEmpty()) }
    } catch (_: UnknownHostException) {
        Reply.Err("Server name not found")
    } catch (_: ConnectException) {
        Reply.Err("Connection refused – check the address and port")
    } catch (_: NoRouteToHostException) {
        Reply.Err("No route to the server – is it on this network?")
    } catch (_: InterruptedIOException) {
        Reply.Err("Timed out – no reply from the server")
    } catch (e: SSLException) {
        Reply.Err("Secure connection failed (${e.message})")
    } catch (e: IOException) {
        Reply.Err("Network error: ${e.message}")
    } catch (e: Exception) {
        Reply.Err("Error: ${e.message}")
    }

    private fun run() {
        val status = get("$base/status-json.xsl", false)
        if (status is Reply.Err) {
            cb.onError(status.message)
            return
        }
        status as Reply.Ok
        var mounts: List<Mount> =
            if (status.code in 200..299) parseStatus(status.body) else emptyList()

        if (mounts.isEmpty() && pass.isNotBlank()) {
            val admin = get("$base/admin/listmounts", true)
            if (admin is Reply.Err) {
                cb.onError(admin.message)
                return
            }
            admin as Reply.Ok
            if (admin.code in 200..299) {
                mounts = parseAdmin(admin.body)
            } else if (mounts.isEmpty()) {
                cb.onError("HTTP ${admin.code} – ${reason(admin.code)} (admin login)")
                return
            }
        }

        if (mounts.isNotEmpty()) {
            cb.onResult(mounts)
        } else if (status.code in 200..299) {
            cb.onError(
                if (pass.isBlank()) "Connected, but no mountpoints are listed – enter the admin login to list hidden ones"
                else "Connected, but no mountpoints found"
            )
        } else {
            cb.onError(
                "HTTP ${status.code} – ${reason(status.code)}" +
                    if (pass.isBlank()) ". Try the admin login." else ""
            )
        }
    }

    private fun parseStatus(body: String): List<Mount> {
        val out = LinkedHashMap<String, String>()
        try {
            val stats = JSONObject(body).optJSONObject("icestats")
            val src = stats?.opt("source")
            val list = when (src) {
                is JSONArray -> (0 until src.length()).mapNotNull { src.optJSONObject(it) }
                is JSONObject -> listOf(src)
                else -> emptyList()
            }
            for (o in list) {
                val path = pathOf(o.optString("listenurl"))
                if (path.isNotEmpty() && path !in out) {
                    val name = o.optString("server_name").trim()
                    out[path] = if (name.isNotEmpty() && name != "Unspecified name") "$path – $name" else path
                }
            }
        } catch (_: Exception) {
            // Some Icecast versions produce slightly invalid JSON; fall back to picking out the URLs.
            for (m in Regex("\"listenurl\"\\s*:\\s*\"([^\"]+)\"").findAll(body)) {
                val path = pathOf(m.groupValues[1])
                if (path.isNotEmpty()) out.putIfAbsent(path, path)
            }
        }
        return out.map { Mount(it.key, it.value) }
    }

    private fun parseAdmin(body: String): List<Mount> =
        Regex("<source mount=\"([^\"]+)\"").findAll(body)
            .map { it.groupValues[1] }
            .distinct()
            .map { Mount(it, it) }
            .toList()
}
