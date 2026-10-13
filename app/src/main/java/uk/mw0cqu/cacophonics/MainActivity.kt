// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Rob (MW0CQU)

package uk.mw0cqu.cacophonics

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.PopupMenu
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils

class MainActivity : AppCompatActivity() {

    private class Panel(val root: View) {
        val num: TextView = root.findViewById(R.id.num)
        val name: TextView = root.findViewById(R.id.name)
        val tag: TagView = root.findViewById(R.id.tag)
        val status: TextView = root.findViewById(R.id.status)
        val mute: ImageButton = root.findViewById(R.id.mute)
        val lock: ImageButton = root.findViewById(R.id.lock)
        val vol: VolumeBar = root.findViewById(R.id.vol)
        val sqlRow: View = root.findViewById(R.id.sqlRow)
        val sql: VolumeBar = root.findViewById(R.id.sql)
        val sqlLabel: TextView = root.findViewById(R.id.sqlLabel)

        /** Tag icon: 0 nothing to lock, 1 solid green, 2 flashing green (some tags locked), 3 flashing red (this tag locked). */
        var lockState = 0

        /** Whether the squelch row is showing (OpenWebRX+ analog sources only). */
        var sqlShown = false

        /** Which look is currently applied (0 normal, 1 live, 2 muted), so unchanged panels aren't redrawn. */
        var themeKey = -1
    }

    private val panels = ArrayList<Panel>()
    private var service: StreamService? = null
    private var autostartAllowed = false
    private lateinit var btnStart: SignView

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional */ }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as StreamService.LocalBinder).service()
            service = s
            s.onChange = { render() }
            s.applyConfig()
            if (autostartAllowed &&
                Prefs.autostart(this@MainActivity) &&
                !s.running &&
                Prefs.load(this@MainActivity).any { it.enabled }
            ) {
                startStreams()
            }
            autostartAllowed = false
            render()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        keepInsideSystemBars()

        // Only auto-start on a fresh launch, not on rotation.
        autostartAllowed = savedInstanceState == null

        btnStart = findViewById(R.id.btnStart)
        btnStart.setOnClickListener {
            if (service?.running == true) stopStreams() else startStreams()
        }
        // Press and hold the sign (running or stopped) to delete all stream data, after a warning.
        btnStart.setOnLongClickListener {
            confirmWipe()
            true
        }
        findViewById<View>(R.id.btnMenu).setOnClickListener { showMenu(it) }

        buildPanels()

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        render()
    }

    private val handler = Handler(Looper.getMainLooper())
    private var blinkOn = true
    private val blinker = object : Runnable {
        override fun run() {
            blinkOn = !blinkOn
            for (p in panels) paintLock(p)
            handler.postDelayed(this, 500)
        }
    }

    /** Applies the flash (or steady) look of a tag icon. */
    private fun paintLock(p: Panel) {
        p.lock.alpha = when (p.lockState) {
            0 -> 0.35f
            1 -> 1f
            else -> if (blinkOn) 1f else 0.2f
        }
    }

    override fun onStart() {
        super.onStart()
        handler.removeCallbacks(blinker)
        handler.post(blinker)
        bindService(Intent(this, StreamService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        handler.removeCallbacks(blinker)
        service?.onChange = null
        service = null
        unbindService(connection)
        super.onStop()
    }

    /** Portrait: 1 column. Landscape: 2 columns (see integersp.xml / integersl.xml). The screen scrolls if needed. */
    private fun buildPanels() {
        val grid = findViewById<GridLayout>(R.id.grid)
        val columns = resources.getInteger(R.integer.grid_columns)
        val rows = Prefs.SLOTS / columns
        grid.columnCount = columns
        grid.rowCount = rows

        val margin = (6 * resources.displayMetrics.density).toInt()
        for (i in 0 until Prefs.SLOTS) {
            val view = LayoutInflater.from(this).inflate(R.layout.stream_panel, grid, false)
            // Rows are not stretched: each panel gets an explicit height, set in fitPanelHeights().
            val lp = GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED, 1, GridLayout.TOP),
                GridLayout.spec(GridLayout.UNDEFINED, 1f)
            )
            lp.width = 0
            lp.height = 0
            lp.setMargins(margin, margin, margin, margin)
            grid.addView(view, lp)

            val panel = Panel(view)
            panel.num.text = (i + 1).toString()
            panel.tag.setTextSizePx(resources.getDimension(R.dimen.tag_text_size))
            panel.mute.setOnClickListener { service?.toggleMute(i) }
            panel.lock.setOnClickListener { service?.toggleLock(i) }
            panel.lock.setOnLongClickListener {
                if ((service?.states?.get(i)?.lockCount ?: 0) > 0) {
                    AlertDialog.Builder(this)
                        .setTitle("Unmute all unwanted tags?")
                        .setMessage("Stream ${i + 1} will play every tag again.")
                        .setPositiveButton("Yes") { _, _ -> service?.clearLocks(i) }
                        .setNegativeButton("No", null)
                        .show()
                }
                true
            }

            // Volume slider: changes the sound live while dragging, and is remembered when released.
            panel.vol.progress = Prefs.volume(this, i)
            panel.vol.contentDescription = "Volume stream ${i + 1}"
            panel.vol.onChange = { value -> service?.setVolume(i, value) }
            panel.vol.onRelease = { value -> Prefs.setVolume(this, i, value) }

            // Squelch slider (OpenWebRX+ analog sources): live on the receiver, remembered when released.
            panel.sql.progress = Prefs.squelch(this, i)
            panel.sql.labeler = { value -> OwrxClient.squelchLabel(value) }
            panel.sql.contentDescription = "Squelch stream ${i + 1}"
            panel.sql.onChange = { value -> service?.setSquelch(i, value) }
            panel.sql.onRelease = { value -> Prefs.setSquelch(this, i, value) }
            panels += panel
        }
        fitPanelHeights(findViewById(R.id.scroll), rows, margin)
    }

    private var baseHeight = 0

    /**
     * Each panel takes an equal share of the screen height, but never more than
     * R.dimen.panel_max_height and never less than R.dimen.panel_min_height. With six streams the
     * panels don't all fit, so the screen scrolls. Panels showing a squelch slider are taller.
     */
    private fun fitPanelHeights(scroll: View, rows: Int, margin: Int) {
        val cap = resources.getDimensionPixelSize(R.dimen.panel_max_height)
        val min = resources.getDimensionPixelSize(R.dimen.panel_min_height)
        scroll.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val available = bottom - top
            if (available <= 0) return@addOnLayoutChangeListener
            val each = (available / rows - 2 * margin).coerceIn(min, maxOf(min, cap))
            if (each != baseHeight) {
                baseHeight = each
                applyHeights()
            }
        }
    }

    private fun applyHeights() {
        if (baseHeight <= 0) return
        val extra = resources.getDimensionPixelSize(R.dimen.sql_row_height)
        for (p in panels) {
            val h = baseHeight + if (p.sqlShown) extra else 0
            val lp = p.root.layoutParams as GridLayout.LayoutParams
            if (lp.height != h) {
                lp.height = h
                p.root.layoutParams = lp
            }
        }
    }

    private fun showSql(p: Panel, show: Boolean) {
        if (p.sqlShown == show) return
        p.sqlShown = show
        p.sqlRow.visibility = if (show) View.VISIBLE else View.GONE
        applyHeights()
    }

    private fun render() {
        val cfgs = Prefs.load(this)
        val s = service

        for (i in panels.indices) {
            val p = panels[i]
            val cfg = cfgs[i]
            val st = s?.states?.get(i)
            p.name.text = cfg.label(i)
            // Receivers don't scan, so they have no tags to lock out.
            p.lock.visibility = if (cfg.isOwrx) View.GONE else View.VISIBLE

            // Keep the slider in step with the saved volume (but never fight a finger that is dragging it).
            p.vol.isEnabled = cfg.enabled
            val volume = st?.volume ?: Prefs.volume(this, i)
            if (!p.vol.dragging) p.vol.progress = volume
            p.sql.isEnabled = cfg.enabled
            if (!p.sql.dragging) p.sql.progress = st?.squelch ?: Prefs.squelch(this, i)
            showSql(p, cfg.enabled && cfg.isOwrx && cfg.mode !in OwrxClient.DIGITAL)

            if (!cfg.enabled) {
                applyTheme(p, live = false)
                p.tag.text = "Not configured"
                p.status.text = "Open Settings to add a stream URL"
                p.mute.isEnabled = false
                p.lockState = 0
                p.lock.isEnabled = false
                p.lock.imageTintList = ColorStateList.valueOf(color(R.color.text_secondary))
                paintLock(p)
                p.mute.setImageResource(R.drawable.ic_speaker_off)
                p.mute.imageTintList = ColorStateList.valueOf(color(R.color.text_secondary))
                p.root.alpha = 0.5f
                continue
            }

            p.tag.text = when {
                st == null -> "Stopped"
                st.tag.isNotBlank() -> st.tag
                st.active && st.status == "Playing" -> "(no tag received)"
                else -> st.status
            }
            p.status.text = st?.status ?: "Stopped"

            val locked = st?.locked == true
            val muted = st?.muted == true || locked
            val usable = st?.active == true
            // "Live" = the stream is carrying audible sound right now AND is not muted. The panel
            // turns light grey with dark text, and goes back to dark grey when the audio stops.
            // A muted stream never highlights; all its text turns red instead.
            val live = st != null && st.active && st.audio && !muted
            val lockCount = st?.lockCount ?: 0
            applyTheme(p, live, muted && usable)

            val connected = st != null && st.active && (st.status == "Playing" || st.status == "Buffering…")

            p.mute.isEnabled = usable
            // Green speaker = audio on, red slashed speaker = muted, grey = not connected.
            p.mute.setImageResource(if (muted) R.drawable.ic_speaker_off else R.drawable.ic_speaker_on)
            p.mute.imageTintList = ColorStateList.valueOf(
                color(
                    when {
                        !connected -> R.color.text_secondary
                        muted -> R.color.icon_off
                        else -> if (live) R.color.icon_on_live else R.color.icon_on
                    }
                )
            )
            p.mute.contentDescription = if (muted) "Unmute stream ${i + 1}" else "Mute stream ${i + 1}"
            p.lockState = when {
                !usable -> 0
                locked -> 3
                lockCount > 0 -> 2
                st?.tag.isNullOrBlank() -> 0
                else -> 1
            }
            p.lock.isEnabled = p.lockState != 0
            p.lock.imageTintList = ColorStateList.valueOf(
                color(
                    when (p.lockState) {
                        0 -> R.color.text_secondary
                        3 -> R.color.icon_off
                        else -> if (live) R.color.icon_on_live else R.color.icon_on
                    }
                )
            )
            p.lock.contentDescription = if (locked) "Unlock this tag, stream ${i + 1}" else "Lock out this tag, stream ${i + 1}"
            paintLock(p)
            p.root.alpha = 1f // muted text must stay fully visible, so no dimming
        }

        btnStart.running = s?.running == true
    }

    private fun showMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, "Configure")
        menu.menu.add(0, 2, 1, "Help")
        menu.menu.add(0, 3, 2, "About")
        menu.menu.add(0, 5, 3, "Quit")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> startActivity(Intent(this, SettingsActivity::class.java))
                2 -> startActivity(
                    Intent(this, InfoActivity::class.java).putExtra(InfoActivity.EXTRA_PAGE, InfoActivity.PAGE_HELP)
                )
                3 -> startActivity(
                    Intent(this, InfoActivity::class.java).putExtra(InfoActivity.EXTRA_PAGE, InfoActivity.PAGE_ABOUT)
                )
                5 -> quit()
            }
            true
        }
        menu.show()
    }

    /** Warning box: the data is only deleted by pressing and HOLDING the confirm button. */
    private fun confirmWipe() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("CAUTION")
            .setMessage("Delete all stream data")
            .setPositiveButton("TAP AND HOLD to confirm", null)
            .setNegativeButton("Go back", null)
            .create()
        dialog.setOnShowListener {
            val confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            confirm.setTextColor(android.graphics.Color.rgb(200, 16, 46))
            // A plain tap only reminds; it does not close the box or delete anything.
            confirm.setOnClickListener {
                Toast.makeText(this, "Keep holding the button to confirm", Toast.LENGTH_SHORT).show()
            }
            confirm.setOnLongClickListener {
                dialog.dismiss()
                wipeAllData()
                true
            }
        }
        dialog.show()
    }

    /** Stops everything and forgets all six streams, with their volume and squelch settings. */
    private fun wipeAllData() {
        service?.resetAll()
        stopService(Intent(this, StreamService::class.java))
        Prefs.clearAll(this)
        for (p in panels) {
            p.vol.progress = 100
            p.sql.progress = Prefs.DEFAULT_SQUELCH
        }
        render()
        Toast.makeText(this, "All stream data deleted", Toast.LENGTH_LONG).show()
    }

    private fun color(id: Int) = ContextCompat.getColor(this, id)

    /**
     * Dark panel with light text normally; light grey panel with dark text while live;
     * dark panel with all text red while muted.
     */
    private fun applyTheme(p: Panel, live: Boolean, muted: Boolean = false) {
        val key = if (muted) 2 else if (live) 1 else 0
        if (key == p.themeKey) return
        p.themeKey = key

        if (muted) {
            val red = color(R.color.icon_off)
            p.root.setBackgroundResource(R.drawable.panel_bg)
            p.num.setTextColor(red)
            p.name.setTextColor(red)
            p.tag.setTextColor(red)
            p.status.setTextColor(red)
            // muted: red slider
            p.vol.setColors(red, ColorUtils.setAlphaComponent(red, 120), color(R.color.panel))
            p.sql.setColors(red, ColorUtils.setAlphaComponent(red, 120), color(R.color.panel))
            p.sqlLabel.setTextColor(red)
            return
        }
        p.root.setBackgroundResource(if (live) R.drawable.panel_bg_live else R.drawable.panel_bg)
        p.num.setTextColor(color(if (live) R.color.live_accent else R.color.accent))
        p.name.setTextColor(color(if (live) R.color.live_text_secondary else R.color.text_secondary))
        p.tag.setTextColor(color(if (live) R.color.live_text else R.color.text_primary))
        p.status.setTextColor(color(if (live) R.color.live_text_secondary else R.color.text_secondary))
        p.sqlLabel.setTextColor(color(if (live) R.color.live_text_secondary else R.color.text_secondary))
        if (live) {
            // light grey panel: deep blue fill, dark track, so the slider stands out clearly
            val fill = color(R.color.live_accent)
            val track = ColorUtils.setAlphaComponent(color(R.color.live_text), 150)
            p.vol.setColors(fill, track, color(R.color.live_bg))
            p.sql.setColors(fill, track, color(R.color.live_bg))
        } else {
            val track = ColorUtils.setAlphaComponent(color(R.color.text_secondary), 130)
            p.vol.setColors(color(R.color.accent), track, color(R.color.panel))
            p.sql.setColors(color(R.color.accent), track, color(R.color.panel))
        }
    }

    private fun startStreams() {
        if (Prefs.load(this).none { it.enabled }) {
            Toast.makeText(this, "Add a stream URL in Settings first", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, StreamService::class.java))
    }

    /** Stops every stream, shuts the background service down and closes the app. */
    private fun quit() {
        stopStreams()
        finishAndRemoveTask()
    }

    private fun stopStreams() {
        service?.stopAll()
        stopService(Intent(this, StreamService::class.java))
        render()
    }
}
