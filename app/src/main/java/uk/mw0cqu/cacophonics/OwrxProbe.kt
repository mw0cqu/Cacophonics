package uk.mw0cqu.cacophonics

import android.util.Log
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * One-shot "test connection": connects to a receiver, reads its list of profiles and the range
 * of the profile in use, then hangs up. If [selectProfile] is given it asks the receiver to
 * switch to that profile first (which changes it for everybody listening to that receiver).
 *
 * Callbacks arrive on a background thread.
 */
class OwrxProbe(
    private val address: String,
    private val selectProfile: String?,
    private val cb: Callback
) {
    class Result(
        /** (id, display name) pairs, in the order the receiver lists them. */
        val profiles: List<Pair<String, String>>,
        val currentId: String?,
        /** Hz */
        val center: Double,
        /** Hz */
        val span: Double
    )

    interface Callback {
        fun onResult(r: Result)
        fun onError(message: String)
    }

    private companion object {
        const val TAG = "OWRX"
        const val TIMEOUT_MS = 10_000L
    }

    @Volatile
    private var done = false
    private var ws: WebSocket? = null
    private var profiles: List<Pair<String, String>>? = null
    private var sdrId: String? = null
    private var profileId: String? = null
    @Volatile
    private var center = Double.NaN

    @Volatile
    private var span = Double.NaN

    @Volatile
    private var settling = false
    private var requested = false
    private var awaitingSwitch = false

    fun start() {
        val url = OwrxClient.wsUrl(address)
        if (url == null) {
            finishError("Receiver address not understood")
            return
        }
        Thread {
            try {
                Thread.sleep(TIMEOUT_MS)
                if (!done) finishError("Receiver not responding")
            } catch (_: InterruptedException) {
            }
        }.also {
            it.isDaemon = true
            it.start()
        }
        ws = OwrxClient.http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("SERVER DE CLIENT client=MultiIcecast type=receiver")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!done) handle(webSocket, text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "probe failure: $t")
                finishError("Can't connect to the receiver")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                finishError("Receiver closed the connection")
            }
        })
    }

    private fun handle(webSocket: WebSocket, text: String) {
        if (text.startsWith("CLIENT DE SERVER")) {
            webSocket.send(
                JSONObject().put("type", "connectionproperties")
                    .put("params", JSONObject().put("output_rate", 12000)).toString()
            )
            return
        }
        val j = try {
            JSONObject(text)
        } catch (_: Exception) {
            return
        }
        when (j.optString("type")) {
            "profiles" -> {
                Log.i(TAG, "probe profiles: ${text.take(2000)}")
                val arr = j.optJSONArray("value") ?: return
                val list = ArrayList<Pair<String, String>>()
                for (k in 0 until arr.length()) {
                    val o = arr.optJSONObject(k) ?: continue
                    val id = o.optString("id")
                    if (id.isNotEmpty()) list += id to o.optString("name", id).ifBlank { id }
                }
                profiles = list
            }

            "config" -> {
                val v = j.optJSONObject("value") ?: return
                Log.i(TAG, "probe config: ${text.take(400)}")
                if (v.has("center_freq")) center = v.optDouble("center_freq")
                if (v.has("samp_rate")) span = v.optDouble("samp_rate")
                if (v.has("sdr_id")) sdrId = v.optString("sdr_id")
                if (v.has("profile_id")) profileId = v.optString("profile_id")
                if (awaitingSwitch) {
                    if (v.has("profile_id") || v.has("sdr_id") || v.has("center_freq") || v.has("samp_rate")) {
                        awaitingSwitch = false
                    } else {
                        return
                    }
                }
            }

            "backoff" -> {
                finishError("Receiver busy: ${j.optString("reason")}")
                return
            }

            else -> return
        }
        check(webSocket)
    }

    private fun check(webSocket: WebSocket) {
        val list = profiles ?: return
        if (center.isNaN() || span.isNaN() || awaitingSwitch) return
        val current = if (sdrId != null && profileId != null) "$sdrId|$profileId" else null
        val want = selectProfile
        if (want != null && current != want) {
            if (!requested) {
                requested = true
                awaitingSwitch = true
                webSocket.send(
                    JSONObject().put("type", "selectprofile")
                        .put("params", JSONObject().put("profile", want)).toString()
                )
                return
            } else if (current != null) {
                finishError("Profile not found on receiver")
                return
            }
        }
        if (done) return
        if (requested) {
            // The receiver describes a new profile in several messages; let them all arrive
            // before reading the range.
            if (settling) return
            settling = true
            Thread {
                try {
                    Thread.sleep(700)
                } catch (_: InterruptedException) {
                }
                if (!done) {
                    done = true
                    ws?.cancel()
                    val cur = if (sdrId != null && profileId != null) "$sdrId|$profileId" else null
                    Log.i(TAG, "probe range: centre $center span $span profile $cur")
                    cb.onResult(Result(list, cur, center, span))
                }
            }.also {
                it.isDaemon = true
                it.start()
            }
            return
        }
        done = true
        ws?.cancel()
        cb.onResult(Result(list, current, center, span))
    }

    private fun finishError(message: String) {
        if (done) return
        done = true
        ws?.cancel()
        cb.onError(message)
    }
}
