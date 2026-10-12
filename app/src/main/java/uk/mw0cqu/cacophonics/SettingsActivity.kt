package uk.mw0cqu.cacophonics

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Rect
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.GridLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged

class SettingsActivity : AppCompatActivity() {

    private class Section(val root: View) {
        val header: TextView = root.findViewById(R.id.header)
        val name: EditText = root.findViewById(R.id.name)
        val url: EditText = root.findViewById(R.id.url)
        val port: EditText = root.findViewById(R.id.port)
        val user: EditText = root.findViewById(R.id.user)
        val pass: EditText = root.findViewById(R.id.pass)
        val apply: Button = root.findViewById(R.id.apply)
        val type: Spinner = root.findViewById(R.id.type)
        val icecastGroup: View = root.findViewById(R.id.icecastGroup)
        val owrxGroup: View = root.findViewById(R.id.owrxGroup)
        val freq: EditText = root.findViewById(R.id.freq)
        val mode: Spinner = root.findViewById(R.id.mode)
        val slot: Spinner = root.findViewById(R.id.slot)
        val icTest: Button = root.findViewById(R.id.icTest)
        val icStatus: TextView = root.findViewById(R.id.icStatus)
        val mountSpinner: Spinner = root.findViewById(R.id.mountSpinner)
        var mountPaths: List<String> = emptyList()

        /** The mountpoint chosen from the list, e.g. "/radio1". */
        var mountPath = ""

        /** Whether the full lists have been fetched from the server yet. */
        var profilesLoaded = false
        var mountsLoaded = false
        val profile: EditText = root.findViewById(R.id.profile)
        val testConn: Button = root.findViewById(R.id.testConn)
        val testStatus: TextView = root.findViewById(R.id.testStatus)
        val profileSpinner: Spinner = root.findViewById(R.id.profileSpinner)

        /** Ids matching the profile spinner entries (entry 0 is the "choose" prompt). */
        var profileIds: List<String> = emptyList()

        /** Display name of the chosen profile, saved with the stream. */
        var profileName = ""

        fun toConfig() = StreamConfig(
            name = name.text.toString().trim(),
            url = if (type.selectedItemPosition == 1) url.text.toString().trim()
            else IcecastProbe.build(url.text.toString(), port.text.toString(), mountPath),
            user = user.text.toString().trim(),
            pass = pass.text.toString(),
            type = if (type.selectedItemPosition == 1) Prefs.TYPE_OWRX else Prefs.TYPE_ICECAST,
            freq = freq.text.toString().trim(),
            mode = MODES[mode.selectedItemPosition.coerceIn(0, MODES.size - 1)],
            profile = profile.text.toString().trim(),
            profileName = profileName,
            slot = SLOT_VALUES[slot.selectedItemPosition.coerceIn(0, SLOT_VALUES.size - 1)]
        )

        /** DMR needs a timeslot choice. */
        fun showMode() {
            val m = MODES[mode.selectedItemPosition.coerceIn(0, MODES.size - 1)]
            slot.visibility = if (m == "dmr") View.VISIBLE else View.GONE
        }

        fun showType() {
            val owrx = type.selectedItemPosition == 1
            icecastGroup.visibility = if (owrx) View.GONE else View.VISIBLE
            owrxGroup.visibility = if (owrx) View.VISIBLE else View.GONE
            port.visibility = if (owrx) View.GONE else View.VISIBLE
            url.hint = if (owrx) "Receiver address, e.g. http://192.168.1.50:8073"
            else "Server name or IP, e.g. 192.168.1.50"
        }
    }

    private companion object {
        val MODES = OwrxClient.MODES.map { it.first }
        val MODE_LABELS = OwrxClient.MODES.map { it.second }
        val SLOT_LABELS = listOf("Both timeslots", "Timeslot 1", "Timeslot 2")
        val SLOT_VALUES = listOf("3", "1", "2")
        val TYPES = listOf("Icecast stream", "OpenWebRX+ receiver")
    }

    private val sections = ArrayList<Section>()
    private lateinit var autostart: CheckBox
    private var service: StreamService? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as StreamService.LocalBinder).service()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        keepInsideSystemBars()

        autostart = findViewById(R.id.autostart)
        autostart.isChecked = Prefs.autostart(this)

        val grid = findViewById<GridLayout>(R.id.sections)
        val columns = resources.getInteger(R.integer.grid_columns)
        grid.columnCount = columns

        val cfgs = Prefs.load(this)
        val margin = (6 * resources.displayMetrics.density).toInt()
        for (i in 0 until Prefs.SLOTS) {
            val view = LayoutInflater.from(this).inflate(R.layout.settings_stream, grid, false)
            val lp = GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED),
                GridLayout.spec(GridLayout.UNDEFINED, 1f)
            )
            lp.width = 0
            lp.setMargins(margin, margin, margin, margin)
            grid.addView(view, lp)

            val s = Section(view)
            s.header.text = "Stream ${i + 1}"
            s.name.setText(cfgs[i].name)
            if (cfgs[i].isOwrx) {
                s.url.setText(cfgs[i].url)
                s.port.setText("8000")
            } else {
                val (server, port, mount) = IcecastProbe.split(cfgs[i].url)
                s.url.setText(server)
                s.port.setText(port)
                s.mountPath = mount
            }
            // If the address changes, any list fetched from the old one is out of date.
            s.url.doAfterTextChanged {
                s.profilesLoaded = false
                s.mountsLoaded = false
            }
            s.port.doAfterTextChanged { s.mountsLoaded = false }
            s.user.setText(cfgs[i].user)
            s.pass.setText(cfgs[i].pass)
            s.type.adapter = ArrayAdapter(this, R.layout.spinner_item, TYPES).also {
                it.setDropDownViewResource(R.layout.spinner_dropdown_item)
            }
            s.mode.adapter = ArrayAdapter(this, R.layout.spinner_item, MODE_LABELS).also {
                it.setDropDownViewResource(R.layout.spinner_dropdown_item)
            }
            s.type.setSelection(if (cfgs[i].isOwrx) 1 else 0)
            s.mode.setSelection(MODES.indexOf(cfgs[i].mode).coerceAtLeast(0))
            s.slot.adapter = ArrayAdapter(this, R.layout.spinner_item, SLOT_LABELS).also {
                it.setDropDownViewResource(R.layout.spinner_dropdown_item)
            }
            s.slot.setSelection(SLOT_VALUES.indexOf(cfgs[i].slot).coerceAtLeast(0))
            s.showMode()
            s.mode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = s.showMode()
                override fun onNothingSelected(p: AdapterView<*>?) = Unit
            }
            s.freq.setText(cfgs[i].freq)
            s.profile.setText(cfgs[i].profile)
            s.profileName = cfgs[i].profileName
            if (cfgs[i].profile.isNotBlank()) showSavedProfile(s)
            if (!cfgs[i].isOwrx) showSavedMount(s)
            s.icTest.setOnClickListener { testIcecast(s) }
            // Tapping a list that only shows the saved choice fetches the full list, then opens it.
            s.profileSpinner.setOnTouchListener { _, e ->
                if (s.profilesLoaded) {
                    false
                } else {
                    if (e.action == MotionEvent.ACTION_UP && s.testConn.isEnabled) testConnection(s, true)
                    true
                }
            }
            s.mountSpinner.setOnTouchListener { _, e ->
                if (s.mountsLoaded) {
                    false
                } else {
                    if (e.action == MotionEvent.ACTION_UP && s.icTest.isEnabled) testIcecast(s)
                    true
                }
            }
            s.showType()
            s.type.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = s.showType()
                override fun onNothingSelected(p: AdapterView<*>?) = Unit
            }
            s.testConn.setOnClickListener { testConnection(s) }
            s.apply.text = "Apply stream ${i + 1}"
            s.apply.setOnClickListener { applyOne(i) }

            // When a field gets focus, scroll so this whole stream sits just above the keyboard.
            val showAboveKeyboard = View.OnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    v.postDelayed({
                        s.root.requestRectangleOnScreen(Rect(0, 0, s.root.width, s.root.height))
                    }, 350)
                }
            }
            listOf(s.name, s.url, s.user, s.pass, s.freq, s.port, s.profile).forEach { it.onFocusChangeListener = showAboveKeyboard }

            sections += s
        }

        findViewById<Button>(R.id.btnDone).setOnClickListener { finish() }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, StreamService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        service = null
        unbindService(connection)
        super.onStop()
    }

    /** Before the list has been fetched, the spinner just shows the profile saved with this stream. */
    private fun showSavedProfile(s: Section) {
        val shown = s.profileName.ifBlank { s.profile.text.toString() }
        s.profileSpinner.adapter = ArrayAdapter(this, R.layout.spinner_item, listOf(shown)).also {
            it.setDropDownViewResource(R.layout.spinner_dropdown_item)
        }
        s.profileSpinner.visibility = View.VISIBLE
    }

    /** Before Connect, the mount list just shows the mountpoint saved with this stream. */
    private fun showSavedMount(s: Section) {
        if (s.mountPath.isEmpty()) return
        s.mountSpinner.adapter = ArrayAdapter(this, R.layout.spinner_item, listOf(s.mountPath)).also {
            it.setDropDownViewResource(R.layout.spinner_dropdown_item)
        }
        s.mountSpinner.visibility = View.VISIBLE
    }

    /** Asks the Icecast server which mountpoints it has, then opens the list so one can be picked. */
    private fun testIcecast(s: Section) {
        val base = IcecastProbe.baseFrom(s.url.text.toString(), s.port.text.toString())
        if (base == null) {
            s.icStatus.text = "Enter the server name or IP address"
            return
        }
        s.icStatus.text = "Connecting…"
        s.icTest.isEnabled = false
        IcecastProbe(
            base, s.user.text.toString().trim(), s.pass.text.toString(),
            object : IcecastProbe.Callback {
                override fun onResult(mounts: List<IcecastProbe.Mount>) = runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    s.icTest.isEnabled = true
                    s.icStatus.text = "Connected – ${mounts.size} mount(s)"
                    fillMounts(s, mounts)
                    s.mountsLoaded = true
                    s.mountSpinner.post { s.mountSpinner.performClick() }
                }

                override fun onError(message: String) = runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    s.icTest.isEnabled = true
                    s.icStatus.text = message
                }
            }
        ).start()
    }

    private fun fillMounts(s: Section, mounts: List<IcecastProbe.Mount>) {
        s.mountPaths = mounts.map { it.path }
        val items = listOf("Choose a mountpoint…") + mounts.map { it.label }
        s.mountSpinner.onItemSelectedListener = null
        s.mountSpinner.adapter = ArrayAdapter(this, R.layout.spinner_item, items).also {
            it.setDropDownViewResource(R.layout.spinner_dropdown_item)
        }
        s.mountSpinner.setSelection(s.mountPaths.indexOf(s.mountPath) + 1, false)
        s.mountSpinner.visibility = View.VISIBLE
        s.mountSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos > 0) s.mountPath = s.mountPaths[pos - 1]
            }

            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
    }

    /** Connects to the receiver, fetches its profiles and fills in the list. */
    private fun testConnection(s: Section, openAfter: Boolean = false) {
        val address = s.url.text.toString().trim()
        s.testStatus.text = "Connecting…"
        s.testConn.isEnabled = false
        OwrxProbe(address, null, object : OwrxProbe.Callback {
            override fun onResult(r: OwrxProbe.Result) = runOnUiThread {
                if (isFinishing) return@runOnUiThread
                s.testConn.isEnabled = true
                s.testStatus.text = "Connected"
                fillProfiles(s, r)
                s.profilesLoaded = true
                if (openAfter) s.profileSpinner.post { s.profileSpinner.performClick() }
            }

            override fun onError(message: String) = runOnUiThread {
                if (isFinishing) return@runOnUiThread
                s.testConn.isEnabled = true
                s.testStatus.text = message
            }
        }).start()
    }

    private fun fillProfiles(s: Section, r: OwrxProbe.Result) {
        s.profileIds = r.profiles.map { it.first }
        val names = listOf("Choose a profile…") + r.profiles.map { it.second }
        s.profileSpinner.onItemSelectedListener = null
        s.profileSpinner.adapter = ArrayAdapter(this, R.layout.spinner_item, names).also {
            it.setDropDownViewResource(R.layout.spinner_dropdown_item)
        }
        val saved = s.profile.text.toString().trim()
        val at = s.profileIds.indexOf(saved)
        if (at >= 0) s.profileName = names[at + 1] // keep the saved name in step with the receiver
        s.profileSpinner.setSelection(at + 1, false)
        s.profileSpinner.visibility = View.VISIBLE
        s.profileSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos <= 0) return
                val chosen = s.profileIds[pos - 1]
                s.profile.setText(chosen)
                s.profileName = names[pos]
            }

            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
    }

    /** Saves just this stream and restarts only this stream (if the streams are running). */
    private fun applyOne(i: Int) {
        val all = Prefs.load(this).toMutableList()
        all[i] = sections[i].toConfig()
        Prefs.save(this, all)

        val s = service
        if (s != null && s.running) {
            s.applyConfig()
            Toast.makeText(this, "Stream ${i + 1} applied", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(
                this,
                "Stream ${i + 1} saved. It will connect when you press Start.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** Saving on pause means nothing is lost however the screen is left (back, home, rotate). */
    override fun onPause() {
        Prefs.setAutostart(this, autostart.isChecked)
        Prefs.save(this, sections.map { it.toConfig() })
        super.onPause()
    }
}
