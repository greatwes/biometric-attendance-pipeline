package com.example.attendance

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.attendance.data.AppDatabase
import com.example.attendance.data.Punch
import com.example.attendance.data.PunchType
import com.example.attendance.databinding.ActivityMainBinding
import com.example.attendance.fingerprint.Biometrics
import com.example.attendance.fingerprint.FingerprintEngine
import com.example.attendance.sync.SyncWorker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.widget.EditText

/**
 * The clock-in screen. This is what staff see all day.
 *
 * It does one thing: poll the sensor, identify the finger, record a punch.
 * There is no late/absent logic anywhere in this app - that was an explicit
 * requirement, and keeping the rule out of the terminal means changing your
 * mind later is a query change, not a fleet-wide app update.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var config: Config
    private var captureJob: Job? = null

    /**
     * What the person at the terminal is punching for. Null means nobody has
     * chosen yet, and a finger is ignored until they do.
     *
     * There is deliberately no default and no sticky value: whoever is standing
     * there selects their own action every time. A mode left over from the last
     * person would silently record everyone behind them in the wrong direction,
     * and nothing on screen or in the data would look wrong until somebody
     * reviewed the month.
     */
    private var selectedMode: PunchType? = null
    private var modeTimeoutJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        config = Config.get(this)

        // No action bar on the kiosk screen. The Heritage theme gives every
        // screen a forest-green header, which is right for the admin section
        // and wrong here: it rendered the app name immediately above the app
        // name, and cost 56dp of the vertical space the buttons were just
        // widened to fill. The brand row inside the layout - wordmark left,
        // leaf right - is this screen's header. Admin screens keep theirs.
        supportActionBar?.hide()

        // A terminal that sleeps is a terminal nobody can clock in on.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding.adminButton.setOnClickListener { promptForAdminPin() }

        binding.clockInButton.setOnClickListener { selectMode(PunchType.CLOCK_IN) }
        binding.clockOutButton.setOnClickListener { selectMode(PunchType.CLOCK_OUT) }
        binding.checkInButton.setOnClickListener { selectMode(PunchType.CHECK_IN) }
        binding.checkOutButton.setOnClickListener { selectMode(PunchType.CHECK_OUT) }
        binding.cancelButton.setOnClickListener { showOptions() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppDatabase.get(this@MainActivity).punches().observeQueueDepth()
                    .collectLatest { depth -> renderQueueDepth(depth) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        renderLocation()
        startCaptureLoop()
    }

    private fun renderLocation() {
        val where = config.location
        binding.locationLabel.text = if (where.isBlank()) {
            getString(R.string.location_unset)
        } else {
            getString(R.string.location_is, where)
        }
    }

    override fun onPause() {
        super.onPause()
        // Release the sensor so the enrolment screen can use it.
        captureJob?.cancel()
        captureJob = null
        // Never come back from the admin screen or a lock with an action still
        // armed from before.
        showOptions()
    }

    private fun startCaptureLoop() {
        if (captureJob?.isActive == true) return

        captureJob = lifecycleScope.launch {
            if (!Biometrics.ensureReady(this@MainActivity)) {
                // Shown on the capture page rather than the options page, which
                // has no status card. Without this the failure would be
                // completely silent: every button would still work and every
                // finger would be ignored, which reads as "my finger isn't
                // working" rather than "this terminal is broken". Cancel gets
                // back out.
                binding.captureMode.text = getString(R.string.sensor_unavailable)
                binding.optionsPage.visibility = View.GONE
                binding.capturePage.visibility = View.VISIBLE
                showStatus(
                    getString(R.string.sensor_unavailable),
                    Biometrics.lastError ?: getString(R.string.sensor_unavailable_hint),
                    State.ERROR
                )
                return@launch
            }

            while (isActive) {
                when (val capture = withContext(Dispatchers.IO) { Biometrics.engine.capture() }) {
                    is FingerprintEngine.Capture.Ok -> {
                        // A finger with no action chosen is now simply ignored.
                        // The options page has no status card to complain on,
                        // and on this hardware a resting hand or a smudge
                        // produces captures constantly - flashing a message for
                        // each one would make the idle screen unreadable.
                        selectedMode?.let { handleTemplate(capture.template, it) }
                    }
                    is FingerprintEngine.Capture.Failed -> {
                        // Transient sensor complaint. Do not spam the user with
                        // it; a genuine failure shows up as a run of them and
                        // the next identify simply will not happen.
                        delay(FingerprintEngine.POLL_INTERVAL_MS * 4)
                    }
                    FingerprintEngine.Capture.None -> Unit
                }
                delay(FingerprintEngine.POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun handleTemplate(template: ByteArray, mode: PunchType) {
        val match = withContext(Dispatchers.IO) {
            Biometrics.engine.identify(template, config.matchThreshold)
        }

        if (match == null) {
            showStatus(getString(R.string.not_recognised), getString(R.string.try_again), State.REJECT)
            delay(RESULT_DWELL_MS)
            showIdle()
            return
        }

        val db = AppDatabase.get(this)
        val employee = withContext(Dispatchers.IO) { db.employees().byPin(match.employeePin) }
        val display = employee?.displayName ?: match.employeePin
        // The greeting uses the first name alone, which is the whole reason the
        // name is stored split. Falling back to the full name and then the SF ID
        // means somebody always gets greeted by something, even a record pushed
        // by a stock terminal that only ever sent one name field.
        val greeting = employee?.firstName?.takeIf { it.isNotBlank() } ?: display

        // Debounce. A finger left on the sensor would otherwise generate a
        // punch every poll - dozens per second, all of them real as far as
        // Snowflake is concerned.
        val now = System.currentTimeMillis()
        val last = withContext(Dispatchers.IO) {
            db.punches().lastPunchAt(match.employeePin, mode.code)
        }
        if (last != null && now - last < config.debounceSeconds * 1000L) {
            showStatus(
                getString(R.string.already_recorded, display),
                getString(R.string.already_recorded_action, label(mode), display),
                State.NEUTRAL
            )
            delay(RESULT_DWELL_MS)
            showIdle()
            return
        }

        val ts = TS.format(Date(now))
        val punch = Punch(
            // Same construction the n8n parser uses for a real device, so the
            // Snowflake MERGE de-duplicates our retries exactly as it would
            // a device re-sending a batch.
            logId = "${config.deviceSn}|${match.employeePin}|$ts",
            employeePin = match.employeePin,
            punchedAt = now,
            // Stamped now, not at upload time: moving the device later must not
            // rewrite where past punches happened.
            location = config.location,
            punchType = mode.code,
            matchScore = match.score
        )
        withContext(Dispatchers.IO) { db.punches().insert(punch) }
        SyncWorker.syncNow(this)

        showStatus(
            getString(titleFor(mode), greeting),
            getString(detailFor(mode)),
            State.ACCEPT,
            TIME_ONLY.format(Date(now))
        )
        delay(RESULT_DWELL_MS)
        // Back to the options page, selection cleared, so the next person
        // chooses their own action rather than inheriting this one.
        showOptions()
    }

    /**
     * The four messages, one per punch type.
     *
     * Split into a title and a detail line because the status card has a large
     * heading and a smaller body; together they read as the single message the
     * person is meant to see. Keeping them keyed off [PunchType] rather than
     * assembled from fragments means each one can be worded naturally - "for
     * Contributing to the team today" belongs to Clock Out and nowhere else.
     */
    private fun titleFor(mode: PunchType): Int = when (mode) {
        PunchType.CLOCK_IN -> R.string.msg_clock_in_title
        PunchType.CLOCK_OUT -> R.string.msg_clock_out_title
        PunchType.CHECK_OUT -> R.string.msg_stepping_out_title
        PunchType.CHECK_IN -> R.string.msg_back_on_seat_title
    }

    private fun detailFor(mode: PunchType): Int = when (mode) {
        PunchType.CLOCK_IN -> R.string.msg_clock_in_detail
        PunchType.CLOCK_OUT -> R.string.msg_clock_out_detail
        PunchType.CHECK_OUT -> R.string.msg_stepping_out_detail
        PunchType.CHECK_IN -> R.string.msg_back_on_seat_detail
    }

    // -------------------------------------------------------------- mode

    private fun label(mode: PunchType): String = getString(
        when (mode) {
            PunchType.CLOCK_IN -> R.string.clock_in
            PunchType.CLOCK_OUT -> R.string.clock_out
            PunchType.CHECK_IN -> R.string.check_in
            PunchType.CHECK_OUT -> R.string.check_out
        }
    )

    /** Options page → capture page, with the chosen action armed. */
    private fun selectMode(mode: PunchType) {
        selectedMode = mode
        binding.captureMode.text = getString(R.string.place_finger_for, label(mode))
        binding.optionsPage.visibility = View.GONE
        binding.capturePage.visibility = View.VISIBLE
        showStatus(
            getString(R.string.place_finger),
            getString(R.string.place_finger_hint),
            State.IDLE
        )

        // If they walk away without punching, go back rather than leave an
        // action armed for whoever walks up next. This is the reason the two
        // pages exist at all - a mode left over from the last person would
        // record everyone behind them in the wrong direction, and nothing on
        // screen or in the data would look wrong until somebody reviewed the
        // month.
        modeTimeoutJob?.cancel()
        modeTimeoutJob = lifecycleScope.launch {
            delay(MODE_TIMEOUT_MS)
            showOptions()
        }
    }

    /** Capture page → options page, selection dropped. */
    private fun showOptions() {
        modeTimeoutJob?.cancel()
        modeTimeoutJob = null
        selectedMode = null
        binding.capturePage.visibility = View.GONE
        binding.optionsPage.visibility = View.VISIBLE
    }

    // ------------------------------------------------------------------ UI

    private enum class State { IDLE, ACCEPT, REJECT, NEUTRAL, ERROR }

    /**
     * Back to "place your finger" on the capture page.
     *
     * Only ever reached while a mode is armed - a rejected or debounced finger
     * returns here so the person can simply try again, rather than being sent
     * back to the options page to re-choose something they already chose.
     */
    private fun showIdle() = showStatus(
        getString(R.string.place_finger),
        getString(R.string.place_finger_hint),
        State.IDLE
    )

    /**
     * @param time the punch time, or null for messages that have none.
     */
    private fun showStatus(title: String, detail: String, state: State, time: String? = null) {
        binding.statusTitle.text = title
        binding.statusDetail.text = detail

        binding.statusTime.text = time.orEmpty()
        binding.statusTime.visibility = if (time.isNullOrBlank()) View.GONE else View.VISIBLE

        binding.statusCard.setBackgroundResource(
            when (state) {
                State.IDLE -> R.drawable.bg_status_idle
                State.ACCEPT -> R.drawable.bg_status_accept
                State.REJECT -> R.drawable.bg_status_reject
                State.NEUTRAL -> R.drawable.bg_status_neutral
                State.ERROR -> R.drawable.bg_status_error
            }
        )

        // The outcome states flood the card with colour, so their text has to be
        // white; the waiting states are a white card and need ink. These two
        // decisions are made in one place on purpose - split them and the day
        // somebody adds a state, the message renders white on white and is
        // invisible rather than merely wrong.
        val onColour = when (state) {
            State.ACCEPT, State.REJECT, State.ERROR -> true
            State.IDLE, State.NEUTRAL -> false
        }
        val ink = getColor(if (onColour) android.R.color.white else R.color.text_primary)
        binding.statusTitle.setTextColor(ink)
        binding.statusDetail.setTextColor(ink)
        binding.statusTime.setTextColor(ink)
    }

    private fun renderQueueDepth(depth: Int) {
        binding.queueStatus.text = when (depth) {
            0 -> getString(R.string.queue_clear)
            1 -> getString(R.string.queue_one)
            else -> getString(R.string.queue_many, depth)
        }
    }

    /**
     * Gate on the enrolment screen. Not security theatre for its own sake:
     * anyone who reaches enrolment can register their own finger against
     * somebody else's payroll number.
     */
    private fun promptForAdminPin() {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = getString(R.string.admin_pin_hint)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.admin_access)
            .setView(input)
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            ok.isEnabled = false
            input.doAfterTextChanged { ok.isEnabled = !it.isNullOrBlank() }
            ok.setOnClickListener {
                if (input.text.toString() == config.adminPin) {
                    dialog.dismiss()
                    startActivity(Intent(this, AdminMenuActivity::class.java))
                } else {
                    input.error = getString(R.string.wrong_pin)
                }
            }
        }
        dialog.show()
    }

    companion object {
        /** How long a result stays on screen before returning to idle. */
        private const val RESULT_DWELL_MS = 3500L

        /**
         * How long a chosen action stays armed with nobody punching. Long
         * enough to select and present a finger unhurried, short enough that a
         * selection abandoned mid-queue does not catch the next person.
         */
        private const val MODE_TIMEOUT_MS = 20_000L
        /**
         * The stored timestamp. Stays 24-hour and unchanged - it is the
         * idempotency key the server de-duplicates on, and reformatting it
         * would silently create a second copy of every punch.
         */
        private val TS = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        /**
         * What the person reads. 12-hour with AM/PM, display only.
         *
         * Locale.US is deliberate, not an oversight: it pins "AM/PM" so the
         * label cannot change with the device's locale setting on a terminal
         * whose language nobody manages.
         */
        private val TIME_ONLY = SimpleDateFormat("h:mm:ss a", Locale.US)
    }
}
