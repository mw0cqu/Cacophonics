// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Rob (MW0CQU)

package uk.mw0cqu.cacophonics

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.AudioAttributesCompat
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.metadata.icy.IcyInfo
import java.util.concurrent.atomic.AtomicLong

/**
 * Plays up to four Icecast streams at once (one ExoPlayer each), keeps them alive in the
 * background, and exposes a single MediaSession so Bluetooth / car stereos / Android Auto
 * show "N: tag" for whatever you are listening to.
 *
 * Everything here runs on the main thread.
 */
@OptIn(UnstableApi::class)
class StreamService : MediaBrowserServiceCompat() {

    companion object {
        const val ACTION_STOP = "uk.mw0cqu.cacophonics.STOP"
        const val ACTION_DISMISS = "uk.mw0cqu.cacophonics.DISMISS"
        private const val CHANNEL = "streams_lock" // 1.0.10: new id, so it can be shown on the lock screen
        private const val ALERT_CHANNEL = "alerts"
        private const val NOTIF_ID = 1
        private const val ALERT_ID = 2
        private const val LOST_TEXT = "Bluetooth connection lost"
        private const val ROOT = "root"
        private const val RETRY_MS = 5000L

        /** Peak level (0..1) above which a stream counts as "carrying audio". About -36 dBFS. */
        private const val AUDIO_THRESHOLD = 0.015f

        /** Keeps the panel lit through short gaps between words / syllables. */
        private const val AUDIO_HOLD_MS = 450L
        private const val TICK_MS = 60L

        /** The Bluetooth / notification / car-list updates are slow, so they are batched. */
        private const val SYSTEM_PUSH_MS = 300L
    }

    inner class LocalBinder : Binder() {
        fun service(): StreamService = this@StreamService
    }

    private val binder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())

    /** Observable by the UI. */
    val states = Array(Prefs.SLOTS) { StreamState() }
    var onChange: (() -> Unit)? = null
    var running = false
        private set

    private val players = arrayOfNulls<ExoPlayer>(Prefs.SLOTS)
    private val live = arrayOfNulls<StreamConfig>(Prefs.SLOTS)
    private val retries = arrayOfNulls<Runnable>(Prefs.SLOTS)

    /** Tags the user has locked out, per stream. Kept in memory only: gone when the streams stop. */
    private val locks = Array(Prefs.SLOTS) { LinkedHashSet<String>() }

    /** OpenWebRX+ sources (instead of a player) and a counter that invalidates stale callbacks. */
    private val owrx = arrayOfNulls<OwrxClient>(Prefs.SLOTS)
    private val owrxGen = java.util.concurrent.atomic.AtomicIntegerArray(Prefs.SLOTS)

    /** Last time (elapsedRealtime) each stream carried sound. Written on the audio thread. */
    private val lastAudio = Array(Prefs.SLOTS) { AtomicLong(0L) }

    private lateinit var session: MediaSessionCompat

    /** The stream whose tag is shown on Bluetooth / car displays. */
    private var focus = 0
    private var duck = 1f
    private var paused = false
    private var hasFocus = false

    // ---------------------------------------------------------------- audio focus

    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pauseAll()

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                duck = 0.25f
                applyVolumes()
            }

            AudioManager.AUDIOFOCUS_GAIN -> {
                duck = 1f
                applyVolumes()
                if (paused) resumeAll()
            }
        }
    }

    private val focusRequest by lazy {
        AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributesCompat.Builder()
                    .setUsage(AudioAttributesCompat.USAGE_MEDIA)
                    .setContentType(AudioAttributesCompat.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusListener)
            .build()
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate() {
        super.onCreate()
        for (i in 0 until Prefs.SLOTS) {
            states[i].volume = Prefs.volume(this, i)
            states[i].squelch = Prefs.squelch(this, i)
        }
        createChannel()
        session = MediaSessionCompat(this, "Cacophonics").apply {
            setCallback(sessionCallback)
            isActive = true
        }
        sessionToken = session.sessionToken
        updateSession()
    }

    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == SERVICE_INTERFACE) super.onBind(intent) else binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISMISS) {
            NotificationManagerCompat.from(this).cancel(ALERT_ID)
            if (!running) stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            stopAll()
            stopSelf()
            return START_NOT_STICKY
        }
        enterForeground()
        startStreams()
        return START_STICKY
    }

    override fun onDestroy() {
        stopAll()
        main.removeCallbacks(systemPush)
        session.release()
        super.onDestroy()
    }

    private fun enterForeground() {
        try {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                buildNotification(),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
            )
        } catch (_: Exception) {
            // Android may refuse a foreground start from the background; streams still run
            // while the app or Android Auto keeps the service bound.
        }
    }

    // ---------------------------------------------------------------- public API (UI)

    fun toggleMute(i: Int) {
        // On a locked-out tag the speaker button simply lifts the lock ("I want to hear this").
        if (states[i].locked) {
            locks[i].remove(states[i].tag)
            refresh()
            return
        }
        setMuted(i, !states[i].muted)
    }

    /** The tag icon: lock out the tag showing now, or lift the lock if it is already locked. */
    fun toggleLock(i: Int) {
        val tag = states[i].tag
        if (tag.isEmpty()) return
        if (!locks[i].remove(tag)) locks[i].add(tag)
        refresh()
    }

    /** Forgets every locked-out tag on one stream. */
    fun clearLocks(i: Int) {
        locks[i].clear()
        refresh()
    }

    fun setMuted(i: Int, muted: Boolean) {
        states[i].muted = muted
        applyVolumes()
        if (!muted) focus = i
        pickFocus()
        refresh()
    }

    /** Re-reads settings and restarts only the streams whose settings changed. */
    fun applyConfig() {
        if (!running) return
        owrxChecks.clear()
        val cfgs = Prefs.load(this)
        for (i in cfgs.indices) {
            if (cfgs[i] != live[i]) {
                releaseSlot(i)
                live[i] = cfgs[i]
                if (cfgs[i].enabled) createPlayer(i, cfgs[i]) else states[i].status = "Not configured"
            }
        }
        pickFocus()
        refresh()
    }

    /** Stops everything and puts the live state of every stream back to its starting values. */
    fun resetAll() {
        stopAll()
        for (i in 0 until Prefs.SLOTS) {
            locks[i].clear()
            val s = states[i]
            s.muted = false
            s.locked = false
            s.lockCount = 0
            s.volume = 100
            s.squelch = Prefs.DEFAULT_SQUELCH
            s.tag = ""
            s.audio = false
            s.status = "Stopped"
        }
        onChange?.invoke()
    }

    fun stopAll() {
        owrxChecks.clear()
        running = false
        paused = false
        unregisterNoisy()
        main.removeCallbacks(ticker)
        for (i in 0 until Prefs.SLOTS) {
            releaseSlot(i)
            live[i] = null
            states[i].status = "Stopped"
        }
        if (hasFocus) {
            AudioManagerCompat.abandonAudioFocusRequest(audioManager, focusRequest)
            hasFocus = false
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        main.removeCallbacks(systemPush)
        systemPushScheduled = false
        onChange?.invoke()
        updateSession() // tell Bluetooth / the car straight away that playback has stopped
    }

    // ---------------------------------------------------------------- streams

    // ---------------------------------------------------------------- Bluetooth lost

    private var noisyRegistered = false

    /** Android sends this when Bluetooth audio (or wired headphones) disconnects and sound is about to jump to the phone speaker. */
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) audioRouteLost()
        }
    }

    private fun registerNoisy() {
        if (noisyRegistered) return
        ContextCompat.registerReceiver(
            this, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        noisyRegistered = true
    }

    private fun unregisterNoisy() {
        if (!noisyRegistered) return
        try {
            unregisterReceiver(noisyReceiver)
        } catch (_: Exception) {
        }
        noisyRegistered = false
    }

    /** Stops every stream straight away, so nothing comes out of the phone speaker, and says why. */
    private fun audioRouteLost() {
        if (!running) return
        stopAll()
        val cfgs = Prefs.load(this)
        for (i in cfgs.indices) if (cfgs[i].enabled) states[i].status = LOST_TEXT
        onChange?.invoke()
        postLostNotification()
        stopSelf()
    }

    private fun postLostNotification() {
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val dismiss = PendingIntent.getService(
            this, 3, Intent(this, StreamService::class.java).setAction(ACTION_DISMISS),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(LOST_TEXT)
            .setContentText("Streams stopped. Press Start in the app when it is back.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "Dismiss", dismiss)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(ALERT_ID, n)
        } catch (_: SecurityException) {
            // Notification permission not granted; the panels still show the message.
        }
    }

    private fun startStreams() {
        if (running) return
        running = true
        paused = false
        NotificationManagerCompat.from(this).cancel(ALERT_ID)
        registerNoisy()
        hasFocus = AudioManagerCompat.requestAudioFocus(audioManager, focusRequest) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED

        owrxChecks.clear()
        val cfgs = Prefs.load(this)
        for (i in cfgs.indices) {
            live[i] = cfgs[i]
        }
        for (i in cfgs.indices) {
            if (cfgs[i].enabled) createPlayer(i, cfgs[i]) else states[i].status = "Not configured"
        }
        main.removeCallbacks(ticker)
        main.post(ticker)
        pickFocus()
        refresh()
    }

    /**
     * Decides which streams are carrying sound right now. When a stream starts carrying sound
     * (and isn't muted) it also becomes the one shown on the car display.
     */
    private fun evaluateAudio() {
        if (!running) return
        val now = SystemClock.elapsedRealtime()
        var changed = false
        var started = false
        for (i in 0 until Prefs.SLOTS) {
            val st = states[i]
            val present = st.active && st.status == "Playing" &&
                now - lastAudio[i].get() < AUDIO_HOLD_MS
            if (present != st.audio) {
                st.audio = present
                changed = true
                if (present && !st.silent) {
                    focus = i
                    started = true
                }
            }
        }
        if (started) refresh() else if (changed) onChange?.invoke()
    }

    /** Runs every 60 ms so a stream going quiet is noticed quickly. */
    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            evaluateAudio()
            main.postDelayed(this, TICK_MS)
        }
    }

    /** Called from the audio thread the moment sound is heard, so the panel lights straight away. */
    private val kicked = Array(Prefs.SLOTS) { java.util.concurrent.atomic.AtomicBoolean(false) }

    private fun kick(i: Int) {
        if (kicked[i].compareAndSet(false, true)) {
            main.post {
                kicked[i].set(false)
                evaluateAudio()
            }
        }
    }

    private fun createPlayer(i: Int, cfg: StreamConfig) {
        if (cfg.isOwrx) {
            states[i].active = true
            states[i].tag = ""
            precheck(i)
            return
        }
        val headers = hashMapOf("Icy-MetaData" to "1")
        if (cfg.pass.isNotEmpty()) {
            val credentials = "${cfg.user.ifBlank { "listener" }}:${cfg.pass}"
            headers["Authorization"] =
                "Basic " + Base64.encodeToString(credentials.toByteArray(), Base64.NO_WRAP)
        }
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("Cacophonics/1.0")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)
            .setDefaultRequestProperties(headers)

        // Same as the default audio setup, plus a level meter on the decoded audio.
        val renderers = object : DefaultRenderersFactory(this@StreamService) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink? = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(
                    arrayOf<AudioProcessor>(
                        LevelProcessor { peak ->
                            if (peak >= AUDIO_THRESHOLD) {
                                lastAudio[i].set(SystemClock.elapsedRealtime())
                                if (!states[i].audio) kick(i)
                            }
                        }
                    )
                )
                .build()
        }

        val player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(http))
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        // We manage audio focus ourselves, once, so the four players don't fight each other.
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            false
        )
        player.volume = volumeFor(i)
        player.addListener(playerListener(i))

        players[i] = player
        states[i].active = true
        states[i].tag = ""
        connect(i)
    }

    // ---------------------------------------------------------------- receiver check at start

    /** One check per receiver address, shared by every stream that uses it. */
    private class OwrxCheck {
        var result: OwrxProbe.Result? = null
        var error: String? = null
        val waiting = ArrayList<Pair<Int, Int>>() // (slot, generation)
    }

    private val owrxChecks = HashMap<String, OwrxCheck>()

    /**
     * Before an OpenWebRX+ stream starts, ask its receiver whether the saved profile still exists
     * (and is still called the same, since the name carries the range), and that no other stream
     * on the same receiver needs a different profile. A stream that fails does not start.
     */
    private fun precheck(i: Int) {
        val cfg = live[i] ?: return
        if (cfg.profile.isBlank()) {
            connect(i)
            return
        }
        val key = OwrxClient.wsUrl(cfg.url)
        if (key == null) {
            failStart(i, "Receiver address not understood")
            return
        }
        val gen = owrxGen.get(i)
        states[i].status = "Checking receiver…"
        refresh()
        var c = owrxChecks[key]
        if (c == null) {
            val fresh = OwrxCheck()
            c = fresh
            owrxChecks[key] = fresh
            OwrxProbe(cfg.url, null, object : OwrxProbe.Callback {
                override fun onResult(r: OwrxProbe.Result) {
                    main.post {
                        if (owrxChecks[key] !== fresh) return@post
                        fresh.result = r
                        finishChecks(key, fresh)
                    }
                }

                override fun onError(message: String) {
                    main.post {
                        if (owrxChecks[key] !== fresh) return@post
                        fresh.error = message
                        finishChecks(key, fresh)
                    }
                }
            }).start()
        }
        c.waiting += i to gen
        if (c.result != null || c.error != null) finishChecks(key, c)
    }

    private fun finishChecks(key: String, c: OwrxCheck) {
        val todo = ArrayList(c.waiting)
        c.waiting.clear()
        val error = c.error
        if (error != null) owrxChecks.remove(key) // so the retry asks again
        for ((i, gen) in todo) {
            if (!running || owrxGen.get(i) != gen || live[i] == null) continue
            if (error != null) {
                // Receiver not reachable: not started, but keep trying in case it is only a poor connection.
                states[i].tag = ""
                states[i].status = "$error – retrying"
                refresh()
                retries[i]?.let { main.removeCallbacks(it) }
                val r = Runnable { if (running && !paused) precheck(i) }
                retries[i] = r
                main.postDelayed(r, RETRY_MS)
            } else {
                c.result?.let { judge(i, it) }
            }
        }
    }

    private fun judge(i: Int, r: OwrxProbe.Result) {
        val cfg = live[i] ?: return
        val want = cfg.profile.trim()
        val key = OwrxClient.wsUrl(cfg.url)
        val found = r.profiles.firstOrNull { it.first == want }
        // A profile belongs to one SDR dongle: two streams clash only if they use the SAME dongle
        // (the part of the profile id before "|") with different profiles. Different dongles on the
        // same receiver are independent. The lowest-numbered stream on a dongle decides its profile.
        val sdr = want.substringBefore('|')
        val first = (0 until Prefs.SLOTS).firstOrNull { j ->
            val o = live[j]
            o != null && o.isOwrx && o.profile.isNotBlank() && OwrxClient.wsUrl(o.url) == key &&
                o.profile.trim().substringBefore('|') == sdr
        }
        when {
            r.profiles.isNotEmpty() && found == null ->
                failStart(i, "Profile no longer on the receiver: ${cfg.profileName.ifBlank { want }}")

            found != null && cfg.profileName.isNotBlank() &&
                !found.second.trim().equals(cfg.profileName.trim(), true) ->
                failStart(i, "Profile has changed on the receiver: now \"${found.second}\" (was \"${cfg.profileName}\") – check it in Settings")

            first != null && first != i && live[first]!!.profile.trim() != want ->
                failStart(i, "Needs a different profile from stream ${first + 1}, which uses the same SDR")

            else -> connect(i)
        }
    }

    private fun failStart(i: Int, message: String) {
        states[i].tag = ""
        states[i].audio = false
        states[i].status = message
        refresh()
    }

    private fun connectOwrx(i: Int) {
        val cfg = live[i] ?: return
        owrx[i]?.stop()
        states[i].status = "Connecting…"
        val gen = owrxGen.incrementAndGet(i)
        val client = OwrxClient(cfg, object : OwrxClient.Callback {
            override fun onStarted(tag: String) {
                main.post {
                    if (gen != owrxGen.get(i)) return@post
                    states[i].status = "Playing"
                    setTag(i, tag)
                    refresh()
                }
            }

            override fun onTag(tag: String) {
                main.post {
                    if (gen != owrxGen.get(i)) return@post
                    setTag(i, tag)
                }
            }

            override fun onLevel(peak: Float) {
                if (gen != owrxGen.get(i)) return
                if (peak >= AUDIO_THRESHOLD) {
                    lastAudio[i].set(SystemClock.elapsedRealtime())
                    if (!states[i].audio) kick(i)
                }
            }

            override fun onFailed(message: String, retry: Boolean) {
                main.post {
                    if (gen != owrxGen.get(i)) return@post
                    states[i].tag = ""
                    states[i].audio = false
                    states[i].status = message
                    refresh()
                    if (retry) scheduleRetry(i)
                }
            }
        })
        client.setVolume(volumeFor(i))
        client.setSquelch(OwrxClient.squelchDb(states[i].squelch))
        owrx[i] = client
        refresh()
        client.start()
    }

    private fun connect(i: Int) {
        if (live[i]?.isOwrx == true) {
            connectOwrx(i)
            return
        }
        val player = players[i] ?: return
        val cfg = live[i] ?: return
        states[i].status = "Connecting…"
        player.setMediaItem(MediaItem.fromUri(cfg.url))
        player.prepare()
        player.playWhenReady = true
        refresh()
    }

    private fun releaseSlot(i: Int) {
        retries[i]?.let { main.removeCallbacks(it) }
        retries[i] = null
        owrx[i]?.stop()
        owrx[i] = null
        owrxGen.incrementAndGet(i)
        players[i]?.release()
        players[i] = null
        lastAudio[i].set(0L)
        states[i].active = false
        states[i].audio = false
        states[i].tag = ""
        locks[i].clear()
        states[i].locked = false
        states[i].lockCount = 0
        states[i].status = if (running) "Not configured" else "Stopped"
    }

    private fun scheduleRetry(i: Int) {
        retries[i]?.let { main.removeCallbacks(it) }
        val r = Runnable { if (running && !paused) connect(i) }
        retries[i] = r
        main.postDelayed(r, RETRY_MS)
    }

    private fun playerListener(i: Int) = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> states[i].status = "Buffering…"
                Player.STATE_READY -> states[i].status = "Playing"
                Player.STATE_ENDED -> {
                    states[i].tag = "" // so the status shows in the tag line
                    states[i].status = "Stream ended – retrying"
                    scheduleRetry(i)
                }
                else -> Unit
            }
            refresh()
        }

        override fun onMetadata(metadata: Metadata) {
            for (k in 0 until metadata.length()) {
                val entry = metadata.get(k)
                if (entry is IcyInfo) setTag(i, entry.title)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            states[i].tag = "" // drop the stale tag so the problem shows in the tag line
            states[i].status = "Can't connect – retrying"
            refresh()
            scheduleRetry(i)
        }
    }

    private fun setTag(i: Int, raw: String?) {
        val tag = raw?.trim().orEmpty()
        if (tag.isEmpty()) {
            // A blank tag ends a locked-out tag's turn, so the sound comes back.
            if (states[i].locked) {
                states[i].tag = ""
                refresh()
            }
            return
        }
        if (tag == states[i].tag) return
        states[i].tag = tag
        if (!states[i].silent) focus = i // the stream that just changed gets the car display
        refresh()
    }

    /**
     * Final volume for a stream: 0 when muted, otherwise the slider (squared, so the slider feels
     * even to the ear instead of everything happening in the bottom few percent) times any ducking.
     */
    private fun volumeFor(i: Int): Float {
        if (states[i].silent) return 0f
        val slider = states[i].volume / 100f
        return duck * slider * slider
    }

    private fun applyVolumes() {
        for (i in 0 until Prefs.SLOTS) {
            players[i]?.volume = volumeFor(i)
            owrx[i]?.setVolume(volumeFor(i))
        }
    }

    /** Called by the squelch sliders while they are dragged. Saved separately by the screen. */
    fun setSquelch(i: Int, value: Int) {
        states[i].squelch = value.coerceIn(0, 100)
        owrx[i]?.setSquelch(OwrxClient.squelchDb(states[i].squelch))
    }

    /** Called by the volume sliders while they are dragged. Saved separately by the screen. */
    fun setVolume(i: Int, value: Int) {
        states[i].volume = value.coerceIn(0, 100)
        players[i]?.volume = volumeFor(i)
        owrx[i]?.setVolume(volumeFor(i))
    }

    /** Works out which streams are showing a locked-out tag right now, and silences / restores them. */
    private fun syncLocks() {
        var changed = false
        for (i in 0 until Prefs.SLOTS) {
            val st = states[i]
            val should = st.tag.isNotEmpty() && st.tag in locks[i]
            if (should != st.locked) {
                st.locked = should
                changed = true
            }
            st.lockCount = locks[i].size
        }
        if (changed) {
            applyVolumes()
            pickFocus()
        }
    }

    private fun pauseAll() {
        paused = true
        for (i in 0 until Prefs.SLOTS) {
            retries[i]?.let { main.removeCallbacks(it) }
            players[i]?.stop()
            owrx[i]?.stop()
            owrx[i] = null
            owrxGen.incrementAndGet(i)
            states[i].audio = false
            if (states[i].active) states[i].status = "Paused"
        }
        refresh()
    }

    /** Live streams go stale when paused, so reconnect to get back to "now". */
    private fun resumeAll() {
        paused = false
        for (i in 0 until Prefs.SLOTS) {
            if (players[i] != null || (live[i]?.isOwrx == true && states[i].active)) connect(i)
        }
    }

    private fun pickFocus() {
        if (states[focus].active && !states[focus].silent) return
        val next = (0 until Prefs.SLOTS).firstOrNull { states[it].active && !states[it].silent }
        if (next != null) focus = next
    }

    private fun cycleFocus(direction: Int) {
        val n = Prefs.SLOTS
        for (k in 1..n) {
            val j = ((focus + direction * k) % n + n) % n
            if (states[j].active && !states[j].silent) {
                focus = j
                break
            }
        }
        refresh()
    }

    // ---------------------------------------------------------------- Bluetooth / Android Auto

    private val sessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() {
            if (running) setMuted(focus, false)
        }

        override fun onPause() {
            if (running) setMuted(focus, true)
        }

        override fun onSkipToNext() = cycleFocus(+1)
        override fun onSkipToPrevious() = cycleFocus(-1)

        override fun onStop() {
            stopAll()
            stopSelf()
        }

        /** Android Auto: tapping a stream in the list toggles its mute. */
        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            val i = mediaId?.toIntOrNull() ?: return
            if (i !in 0 until Prefs.SLOTS) return
            if (!running) {
                ContextCompat.startForegroundService(
                    this@StreamService,
                    Intent(this@StreamService, StreamService::class.java)
                )
            } else {
                toggleMute(i)
            }
        }
    }

    private fun titleText(): String {
        val s = states[focus]
        return "${focus + 1}: ${s.tag.ifBlank { s.status }}"
    }

    private fun othersText(): String =
        states.indices
            .filter { it != focus && states[it].active && !states[it].silent }
            .joinToString(" | ") { "${it + 1}: ${states[it].tag.ifBlank { "-" }}" }

    private fun updateSession() {
        val title = if (running) titleText() else getString(R.string.app_name)
        // No app name here: the car display has little room and it is shown anyway by some stereos.
        val artist = if (running) othersText().ifEmpty { "No other streams" } else ""
        val album = if (running) Prefs.load(this).getOrNull(focus)?.label(focus).orEmpty() else ""

        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
                .build()
        )

        val state = when {
            !running -> PlaybackStateCompat.STATE_STOPPED
            paused -> PlaybackStateCompat.STATE_PAUSED
            else -> PlaybackStateCompat.STATE_PLAYING
        }
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
                        PlaybackStateCompat.ACTION_STOP
                )
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot = BrowserRoot(ROOT, null)

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        val items = mutableListOf<MediaBrowserCompat.MediaItem>()
        if (parentId == ROOT) {
            val cfgs = Prefs.load(this)
            for (i in cfgs.indices) {
                if (!cfgs[i].enabled) continue
                val muted = if (states[i].silent) " (muted)" else ""
                val description = MediaDescriptionCompat.Builder()
                    .setMediaId(i.toString())
                    .setTitle("${i + 1}: ${cfgs[i].label(i)}$muted")
                    .setSubtitle(states[i].tag.ifBlank { states[i].status })
                    .build()
                items += MediaBrowserCompat.MediaItem(
                    description,
                    MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                )
            }
        }
        result.sendResult(items)
    }

    // ---------------------------------------------------------------- notification

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL, "Streams", NotificationManager.IMPORTANCE_LOW)
            channel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            channel.setShowBadge(false)
            getSystemService(NotificationManager::class.java).deleteNotificationChannel("streams")
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(ALERT_CHANNEL, "Alerts", NotificationManager.IMPORTANCE_DEFAULT)
            )
            // 1.0.8 had a separate channel for six lock-screen lines; one notification replaces them.
            getSystemService(NotificationManager::class.java).deleteNotificationChannel("lines")
        }
    }

    /**
     * The one notification. Collapsed it shows the stream you are on and the others; expanded
     * (the arrow on the lock screen) it lists all of them. It has no buttons, so nothing can be
     * muted or stopped by accident in a pocket; a tap asks for PIN / fingerprint and opens the app.
     * It is deliberately not a media-style notification, which stops Android drawing a second
     * media card for it. Bluetooth and the car still get their text from the media session.
     */
    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val title = if (running) titleText() else getString(R.string.app_name)
        val text = if (running) othersText().ifEmpty { "No other streams" } else "Starting…"
        val box = NotificationCompat.InboxStyle().setBigContentTitle(title)
        if (running) {
            val cfgs = Prefs.load(this)
            for (i in 0 until Prefs.SLOTS) {
                if (!cfgs[i].enabled) continue
                val st = states[i]
                box.addLine("${i + 1}: ${st.tag.ifBlank { st.status }}${if (st.silent) " (muted)" else ""}")
            }
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setStyle(box)
            .build()
    }

    private fun updateNotification() {
        if (!running) return
        try {
            NotificationManagerCompat.from(this).notify(NOTIF_ID, buildNotification())
        } catch (_: SecurityException) {
            // Notification permission not granted; streaming is unaffected.
        }
    }

    // ---------------------------------------------------------------- fan-out of changes

    private var systemPushScheduled = false

    private val systemPush = Runnable {
        systemPushScheduled = false
        pushSystemState()
    }

    /** The slow, system-facing updates: Bluetooth / car display, notification, Android Auto list. */
    private fun pushSystemState() {
        updateSession()
        updateNotification()
        notifyChildrenChanged(ROOT)
    }

    /**
     * The screen is redrawn straight away. The slow updates are batched into one every 300 ms,
     * so a scanner hopping quickly can't pile up work on the thread that draws the screen.
     */
    private fun refresh() {
        syncLocks()
        onChange?.invoke()
        if (!systemPushScheduled) {
            systemPushScheduled = true
            main.postDelayed(systemPush, SYSTEM_PUSH_MS)
        }
    }
}
