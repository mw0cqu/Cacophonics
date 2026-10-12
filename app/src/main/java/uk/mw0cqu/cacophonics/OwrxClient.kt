// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Rob (MW0CQU)

package uk.mw0cqu.cacophonics

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

/**
 * A small, independent client for an OpenWebRX / OpenWebRX+ receiver. It opens the receiver's
 * WebSocket, tunes one demodulator to a fixed frequency, and plays the audio that comes back.
 *
 * The protocol is not formally documented or versioned, so every step is logged under the
 * Logcat tag "OWRX" to make it easy to see what a particular receiver does.
 *
 * Callbacks arrive on background threads; the caller must hop to its own thread if it needs to.
 */
class OwrxClient(
    private val cfg: StreamConfig,
    private val cb: Callback
) {
    interface Callback {
        /** Tuned and receiving: [tag] is e.g. "145.500 MHz NFM". */
        fun onStarted(tag: String)

        /** Called for every audio frame with its peak level 0..1 (before the volume control). */
        fun onLevel(peak: Float)

        /** The text for the panel changed, e.g. a new talker on a digital voice channel. */
        fun onTag(tag: String)

        /** The connection failed or was refused. [retry] says whether trying again could help. */
        fun onFailed(message: String, retry: Boolean)
    }

    companion object {
        private const val TAG = "OWRX"
        private const val RATE = 12000
        private const val MAX_QUEUED_FRAMES = 30
        private const val WATCHDOG_MS = 15_000L

        internal val http: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS) // a WebSocket is a long-lived read
                .pingInterval(20, TimeUnit.SECONDS)
                .build()
        }

        private val SYNC = byteArrayOf(0x53, 0x59, 0x4E, 0x43) // "SYNC"

        private val STEP = intArrayOf(
            7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55,
            60, 66, 73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
            337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876, 963, 1060, 1166, 1282, 1411,
            1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358,
            5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899, 15289, 16818, 18500,
            20350, 22385, 24623, 27086, 29794, 32767
        )
        private val INDEX = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8)

        /** (mode name the receiver understands, label shown in Settings). */
        val MODES = listOf(
            "nfm" to "NFM", "am" to "AM", "lsb" to "LSB", "usb" to "USB", "cw" to "CW", "wfm" to "WFM",
            "dmr" to "DMR", "ysf" to "YSF (Fusion)", "dstar" to "D-STAR", "nxdn" to "NXDN",
            "m17" to "M17", "freedv" to "FreeDV", "tetra" to "TETRA"
        )

        /** Modes the receiver decodes itself; squelch is not used for these. */
        val DIGITAL = setOf("dmr", "ysf", "dstar", "nxdn", "m17", "freedv", "tetra")

        /** Passband (low cut, high cut in Hz) for each mode. */
        fun cutsFor(mode: String): Pair<Int, Int> = when (mode) {
            "dmr", "ysf" -> -6250 to 6250
            "dstar", "nxdn" -> -3250 to 3250
            "m17" -> -4500 to 4500
            "freedv" -> 300 to 3000
            "tetra" -> -12500 to 12500
            "am" -> -4000 to 4000
            "lsb" -> -2700 to -300
            "usb" -> 300 to 2700
            "cw" -> 300 to 800
            "wfm" -> -80000 to 80000
            else -> -4000 to 4000 // nfm
        }

        /** "192.168.1.5:8073", "http://host:8073" or "https://host/sdr" -> the receiver's WebSocket address. */
        fun wsUrl(raw: String): String? {
            var u = raw.trim()
            if (u.isEmpty()) return null
            if (!u.contains("://")) u = "http://$u"
            val uri = try {
                URI(u)
            } catch (_: Exception) {
                return null
            }
            val host = uri.host ?: return null
            val secure = uri.scheme.equals("https", true) || uri.scheme.equals("wss", true)
            val port = if (uri.port > 0) ":${uri.port}" else ""
            var path = (uri.path ?: "").trim('/')
            if (path == "ws" || path.endsWith("/ws")) path = path.removeSuffix("ws").trim('/')
            return (if (secure) "wss://" else "ws://") + host + port +
                (if (path.isEmpty()) "" else "/$path") + "/ws/"
        }

        /**
         * Turns a digital-voice metadata message into short text, e.g. "TG 91 · ID 2345678 · G4ABC".
         * The receiver's message layout is not documented, so this looks for the usual field names.
         */
        fun describeMeta(v: JSONObject): String {
            val add = v.optJSONObject("additional")
            fun str(o: JSONObject?, k: String): String {
                if (o == null || !o.has(k) || o.isNull(k)) return ""
                return o.opt(k)?.toString()?.trim().orEmpty()
            }

            fun first(vararg keys: String): String {
                for (k in keys) {
                    val a = str(v, k).ifEmpty { str(add, k) }
                    if (a.isNotEmpty()) return a
                }
                return ""
            }

            val parts = ArrayList<String>()
            val kind = str(v, "type")
            val target = first("target", "yourcall")
            if (target.isNotEmpty()) {
                parts += if (kind == "group" && target.all { it.isDigit() }) "TG $target" else target
            }
            val source = first("source", "ourcall")
            if (source.isNotEmpty()) parts += if (source.all { it.isDigit() }) "ID $source" else source
            for (k in listOf("callsign", "fname", "lname", "name", "talkeralias", "alias", "message", "text")) {
                val x = first(k)
                if (x.isNotEmpty() && x !in parts) parts += x
            }
            return parts.joinToString(" · ")
        }

        /** Squelch slider 0..100 -> receiver dB. 0 = off (-150), then 1.5 dB per step up to 0 dB. */
        fun squelchDb(p: Int): Double = if (p <= 0) -150.0 else -150.0 + 1.5 * p.coerceAtMost(100)

        fun squelchLabel(p: Int): String = if (p <= 0) "off" else "${Math.round(squelchDb(p))} dB"

        fun fmtMhz(mhz: Double): String {
            var s = String.format(Locale.US, "%.6f", mhz).trimEnd('0')
            val dot = s.indexOf('.')
            while (s.length - dot - 1 < 3) s += "0"
            return s
        }
    }

    @Volatile
    private var closed = false

    @Volatile
    private var started = false

    @Volatile
    private var volume = 1f

    @Volatile
    private var squelchDb = -150.0

    private var ws: WebSocket? = null
    private var playThread: Thread? = null
    private var watchdog: Thread? = null
    private val queue = LinkedBlockingQueue<ShortArray>()

    // What the receiver has told us (config messages are incremental, so remember them).
    private var compression = "adpcm"
    @Volatile
    private var center = Double.NaN

    @Volatile
    private var span = Double.NaN
    private var tuneScheduled = false
    private var sdrId: String? = null
    private var profileId: String? = null
    private var profileRequested = false
    private var awaitingSwitch = false

    /** True once the receiver has been seen changing profile (by us or by another stream of ours). */
    private var switchedLive = false
    private var mismatchTimer = false

    // Wanted tuning
    private val mode = cfg.mode.lowercase(Locale.US).ifBlank { "nfm" }
    private val freqHz: Long = run {
        val mhz = cfg.freq.trim().replace(',', '.').toDoubleOrNull() ?: Double.NaN
        if (mhz.isNaN() || mhz <= 0.0) -1L else Math.round(mhz * 1_000_000.0)
    }
    private val modeLabel = MODES.firstOrNull { it.first == mode }?.second ?: mode.uppercase(Locale.US)
    private val baseTag = if (freqHz > 0) "${fmtMhz(freqHz / 1_000_000.0)} MHz $modeLabel" else ""

    /** Frequency and mode, plus the receiver's bookmark name for this frequency if it has one. */
    @Volatile
    private var tagText = baseTag
    private var lastParams: JSONObject? = null
    private var firstAudioLogged = false
    private val seenTypes = HashSet<Int>()

    // ADPCM decoder state
    private var adIndex = 0
    private var adPred = 0
    private var syncMatched = 0
    private var inHeader = false
    private val header = IntArray(4)
    private var headerN = 0
    private var syncLogs = 0

    // ------------------------------------------------------------------ public

    fun start() {
        if (freqHz <= 0) {
            fail("Set a frequency (MHz) in Settings", false)
            return
        }
        val url = wsUrl()
        if (url == null) {
            fail("Receiver address not understood", false)
            return
        }
        Log.i(TAG, "connecting to $url, wanted ${freqHz} Hz $mode")

        watchdog = Thread {
            try {
                Thread.sleep(WATCHDOG_MS)
                if (!closed && !started) fail("Receiver not responding – retrying", true)
            } catch (_: InterruptedException) {
            }
        }.also {
            it.isDaemon = true
            it.start()
        }

        ws = http.newWebSocket(Request.Builder().url(url).build(), listener)
    }

    fun stop() {
        if (closed) return
        closed = true
        teardown()
    }

    /** Changes the squelch on the receiver, live. Digital modes ignore squelch. */
    fun setSquelch(db: Double) {
        squelchDb = db
        if (started && mode !in DIGITAL) {
            try {
                lastParams?.put("squelch_level", db)
                ws?.send(
                    JSONObject().put("type", "dspcontrol")
                        .put("params", JSONObject().put("squelch_level", db)).toString()
                )
            } catch (_: Exception) {
            }
        }
    }

    fun setVolume(v: Float) {
        volume = v
        try {
            track?.setVolume(v)
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------ internals

    @Volatile
    private var track: AudioTrack? = null

    private fun teardown() {
        try {
            ws?.cancel()
        } catch (_: Exception) {
        }
        watchdog?.interrupt()
        playThread?.interrupt()
    }

    private fun fail(message: String, retry: Boolean) {
        if (closed) return
        closed = true
        Log.w(TAG, "failed: $message (retry=$retry)")
        teardown()
        cb.onFailed(message, retry)
    }

    private fun wsUrl(): String? = wsUrl(cfg.url)

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "socket open")
            webSocket.send("SERVER DE CLIENT client=MultiIcecast type=receiver")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (closed) return
            handleText(webSocket, text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (closed) return
            handleBinary(webSocket, bytes.toByteArray())
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "closing $code $reason")
            webSocket.close(1000, null)
            fail("Receiver closed the connection – retrying", true)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "socket failure: $t ${response?.code}")
            fail("Can't connect – retrying", true)
        }
    }

    private fun handleText(webSocket: WebSocket, text: String) {
        if (text.startsWith("CLIENT DE SERVER")) {
            Log.i(TAG, "server hello: ${text.take(200)}")
            webSocket.send(
                JSONObject().put("type", "connectionproperties")
                    .put("params", JSONObject().put("output_rate", RATE))
                    .toString()
            )
            return
        }
        val j = try {
            JSONObject(text)
        } catch (_: Exception) {
            Log.i(TAG, "text: ${text.take(200)}")
            return
        }
        when (val type = j.optString("type")) {
            "config" -> {
                Log.i(TAG, "config: ${text.take(400)}")
                handleConfig(webSocket, j.optJSONObject("value"))
            }

            "smeter" -> Unit
            "bookmarks" -> {
                Log.i(TAG, "bookmarks: ${text.take(600)}")
                handleBookmarks(j.opt("value"))
            }

            "metadata" -> {
                Log.i(TAG, "metadata: ${text.take(500)}")
                if (started) {
                    val extra = describeMeta(j.optJSONObject("value") ?: j)
                    cb.onTag(if (extra.isEmpty()) tagText else "$tagText · $extra")
                }
            }

            "backoff" -> fail("Receiver busy: ${j.optString("reason")}", true)
            else -> Log.i(TAG, "$type: ${text.take(300)}")
        }
    }

    /**
     * The receiver lists its bookmarks (name, frequency, modulation). If one sits on the frequency
     * this stream is tuned to, its name is shown after the frequency and mode.
     */
    private fun handleBookmarks(value: Any?) {
        val arr: JSONArray = when (value) {
            is JSONArray -> value
            is JSONObject -> value.optJSONArray("bookmarks") ?: return
            else -> return
        }
        if (freqHz <= 0) return
        var bestName = ""
        var bestDiff = Long.MAX_VALUE
        for (k in 0 until arr.length()) {
            val o = arr.optJSONObject(k) ?: continue
            val f = o.optDouble("frequency", Double.NaN)
            if (f.isNaN()) continue
            val name = o.optString("name").trim().ifEmpty { o.optString("description").trim() }
            if (name.isEmpty()) continue
            var diff = Math.abs(Math.round(f) - freqHz)
            // on a tie, prefer the bookmark with the same mode
            if (o.optString("modulation").equals(mode, true)) diff -= 1
            if (diff <= 1000 && diff < bestDiff) {
                bestDiff = diff
                bestName = name
            }
        }
        if (bestName.isEmpty()) return
        val tag = "$baseTag · $bestName"
        if (tag == tagText) return
        tagText = tag
        Log.i(TAG, "bookmark for this frequency: $bestName")
        if (started) cb.onTag(tag)
    }

    private fun handleConfig(webSocket: WebSocket, v: JSONObject?) {
        if (v == null || started) return
        if (v.has("audio_compression")) compression = v.optString("audio_compression", "adpcm")
        val oldCenter = center
        val oldSpan = span
        val oldSdr = sdrId
        val oldProfile = profileId
        if (v.has("center_freq")) center = v.optDouble("center_freq")
        if (v.has("samp_rate")) span = v.optDouble("samp_rate")
        if (v.has("sdr_id")) sdrId = v.optString("sdr_id")
        if (v.has("profile_id")) profileId = v.optString("profile_id")
        // The receiver's profile is shared by every stream. If another of our streams switches it
        // while we are connecting, the description of the new profile arrives in pieces.
        if ((oldSdr != null && sdrId != oldSdr) || (oldProfile != null && profileId != oldProfile) ||
            (!oldCenter.isNaN() && center != oldCenter) || (!oldSpan.isNaN() && span != oldSpan)
        ) switchedLive = true
        if (awaitingSwitch) {
            // after asking for another profile, wait for the config that describes it
            if (v.has("profile_id") || v.has("sdr_id") || v.has("center_freq") || v.has("samp_rate")) {
                awaitingSwitch = false
            } else {
                return
            }
        }
        if (tuneScheduled) return
        if (center.isNaN() || span.isNaN()) return

        val want = cfg.profile.trim()
        if (want.isNotEmpty()) {
            val current = if (sdrId != null && profileId != null) "$sdrId|$profileId" else null
            if (current != want) {
                if (!profileRequested) {
                    profileRequested = true
                    Log.i(TAG, "selecting profile $want (current $current)")
                    awaitingSwitch = true
                    webSocket.send(
                        JSONObject().put("type", "selectprofile")
                            .put("params", JSONObject().put("profile", want))
                            .toString()
                    )
                    return
                } else if (current != null) {
                    // The new profile's details arrive in pieces, so a mismatch right after the
                    // request is normal. Only give up if the wanted profile never turns up.
                    if (!mismatchTimer) {
                        mismatchTimer = true
                        Thread {
                            try {
                                Thread.sleep(5000)
                            } catch (_: InterruptedException) {
                                return@Thread
                            }
                            if (!closed && !started && !tuneScheduled) {
                                fail("Profile not found on receiver: ${cfg.profileName.ifBlank { want }}", false)
                            }
                        }.also {
                            it.isDaemon = true
                            it.start()
                        }
                    }
                    return
                }
            }
        }
        if (profileRequested || switchedLive) {
            // After a profile switch the receiver describes the new profile in several messages;
            // let them all arrive before working out the tuning offset.
            tuneScheduled = true
            Thread {
                try {
                    Thread.sleep(700)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (!closed && !started) tune(webSocket)
            }.also {
                it.isDaemon = true
                it.start()
            }
            return
        }
        tune(webSocket)
    }

    private fun tune(webSocket: WebSocket) {
        val offset = freqHz - Math.round(center)
        if (abs(offset) > span / 2) {
            fail(
                "Outside receiver range (centre ${fmtMhz(center / 1e6)} MHz, " +
                    "span ${fmtMhz(span / 1e6)} MHz) – choose a profile",
                false
            )
            return
        }
        val (lo, hi) = cutsFor(mode)
        val sql = if (mode in DIGITAL) -150.0 else squelchDb
        val params = JSONObject()
            .put("low_cut", lo)
            .put("high_cut", hi)
            .put("offset_freq", offset)
            .put("mod", mode)
            .put("squelch_level", sql)
        if (mode == "dmr") params.put("dmr_filter", cfg.slot.toIntOrNull() ?: 3)
        lastParams = params
        Log.i(TAG, "tuning: offset $offset Hz, $mode, squelch $sql, compression $compression")
        webSocket.send(
            JSONObject().put("type", "dspcontrol").put("action", "start").put("params", params).toString()
        )
        startPlayback()
        started = true
        watchdog?.interrupt()
        cb.onStarted(tagText)
    }

    // ------------------------------------------------------------------ audio

    private fun handleBinary(webSocket: WebSocket, data: ByteArray) {
        if (data.isEmpty()) return
        val type = data[0].toInt() and 0xFF
        if (seenTypes.add(type)) Log.i(TAG, "first binary frame of type $type, ${data.size} bytes")
        if (type != 2 || !started) return // 2 = audio at the requested rate; everything else is ignored

        if (!firstAudioLogged) {
            firstAudioLogged = true
            Log.i(TAG, "first audio frame: ${data.size - 1} bytes, compression=$compression")
            // Belt and braces: send the parameters again now the demodulator is certainly running.
            lastParams?.let {
                webSocket.send(JSONObject().put("type", "dspcontrol").put("params", it).toString())
            }
        }

        val pcm = if (compression == "none") decodePcm(data) else decodeAdpcm(data)
        if (pcm.isEmpty()) return

        var peak = 0
        for (s in pcm) {
            val a = if (s < 0) -s.toInt() else s.toInt()
            if (a > peak) peak = a
        }
        cb.onLevel(peak / 32768f)

        while (queue.size > MAX_QUEUED_FRAMES) queue.poll() // keep latency bounded
        queue.offer(pcm)
    }

    private fun decodePcm(data: ByteArray): ShortArray {
        val n = (data.size - 1) / 2
        val out = ShortArray(n)
        for (k in 0 until n) {
            val lo = data[1 + 2 * k].toInt() and 0xFF
            val hi = data[2 + 2 * k].toInt()
            out[k] = ((hi shl 8) or lo).toShort()
        }
        return out
    }

    private fun nibble(n: Int): Short {
        val step = STEP[adIndex]
        var diff = step shr 3
        if (n and 4 != 0) diff += step
        if (n and 2 != 0) diff += step shr 1
        if (n and 1 != 0) diff += step shr 2
        adPred += if (n and 8 != 0) -diff else diff
        if (adPred > 32767) adPred = 32767
        if (adPred < -32768) adPred = -32768
        adIndex += INDEX[n and 7]
        if (adIndex < 0) adIndex = 0
        if (adIndex > 88) adIndex = 88
        return adPred.toShort()
    }

    /**
     * IMA ADPCM, low nibble first. The receiver inserts "SYNC" + 4 bytes (step index, predictor)
     * every so often so a client that missed some data can pick the decoder state up again.
     */
    private fun decodeAdpcm(data: ByteArray): ShortArray {
        val out = ShortArray((data.size) * 2 + 8)
        var o = 0
        for (k in 1 until data.size) {
            val b = data[k].toInt() and 0xFF
            if (inHeader) {
                header[headerN++] = b
                if (headerN == 4) {
                    inHeader = false
                    applySync()
                }
                continue
            }
            if (b == (SYNC[syncMatched].toInt() and 0xFF)) {
                syncMatched++
                if (syncMatched == 4) {
                    syncMatched = 0
                    inHeader = true
                    headerN = 0
                }
                continue
            }
            if (syncMatched > 0) {
                // Those bytes only looked like the start of a sync marker: they were audio after all.
                for (m in 0 until syncMatched) {
                    val s = SYNC[m].toInt() and 0xFF
                    out[o++] = nibble(s and 0x0F)
                    out[o++] = nibble(s shr 4)
                }
                syncMatched = 0
                if (b == (SYNC[0].toInt() and 0xFF)) {
                    syncMatched = 1
                    continue
                }
            }
            out[o++] = nibble(b and 0x0F)
            out[o++] = nibble(b shr 4)
        }
        return out.copyOf(o)
    }

    private fun applySync() {
        val a = header[0] or (header[1] shl 8)
        val b = (header[2] or (header[3] shl 8)).toShort().toInt()
        val c = header[2] or (header[3] shl 8)
        val d = (header[0] or (header[1] shl 8)).toShort().toInt()
        if (syncLogs < 3) {
            syncLogs++
            Log.i(TAG, "sync block: ${header.joinToString(",")}")
        }
        if (a in 0..88) {
            adIndex = a
            adPred = b
        } else if (c in 0..88) {
            adIndex = c
            adPred = d
        }
    }

    private fun startPlayback() {
        val min = AudioTrack.getMinBufferSize(
            RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val t = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            max(min, RATE), // about half a second
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        t.setVolume(volume)
        t.play()
        track = t

        playThread = Thread {
            try {
                while (!closed) {
                    val chunk = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    t.write(chunk, 0, chunk.size)
                }
            } catch (_: InterruptedException) {
            } finally {
                try {
                    t.stop()
                } catch (_: Exception) {
                }
                t.release()
                track = null
            }
        }.also {
            it.isDaemon = true
            it.start()
        }
    }
}
