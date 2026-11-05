package com.example.attendance

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.attendance.data.AppDatabase
import com.example.attendance.data.Employee
import com.example.attendance.data.FingerSlot
import com.example.attendance.data.Fingerprint
import com.example.attendance.databinding.ActivityAdminBinding
import com.example.attendance.fingerprint.Biometrics
import com.example.attendance.fingerprint.FingerprintEngine
import com.example.attendance.sync.SyncWorker

/**
 * Enrolment and settings. Reached only behind the admin PIN.
 *
 * Enrolment mirrors the vendor demo's algorithm exactly: three samples of the
 * same finger, each verified against the previous, merged into one registered
 * template. The duplicate check before the first sample is the important part
 * - it refuses a finger already registered to a different payroll number,
 * which is the cheapest anti-buddy-punching control available.
 */
class AdminActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminBinding
    private lateinit var config: Config
    private var enrolJob: Job? = null

    /** Mirrors the department spinner, so validation can check it is non-empty. */
    private var departments: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminBinding.inflate(layoutInflater)
        setContentView(binding.root)
        config = Config.get(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.menu_enrol)

        binding.enrolGender.adapter = adapterOf(
            getString(R.string.select_gender),
            listOf(getString(R.string.gender_male), getString(R.string.gender_female))
        )

        // The department list is observed rather than read once: an admin can
        // add a department on the other screen and come straight back here, and
        // a stale dropdown would send them round in circles.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppDatabase.get(this@AdminActivity).departments().observeActive()
                    .collectLatest { list ->
                        departments = list.map { it.name }
                        binding.enrolDepartment.adapter =
                            adapterOf(getString(R.string.select_department), departments)
                    }
            }
        }

        binding.enrolButton.setOnClickListener { startEnrolment() }
    }

    /**
     * A spinner whose first row is a "Select ..." prompt rather than a real
     * value.
     *
     * Android has no first-class placeholder for a dropdown spinner - the
     * android:prompt attribute only shows in dialog mode - so the prompt has to
     * be a list item. That makes it selectable, which is the trap: without the
     * matching check in [chosen] the form would happily enrol somebody whose
     * department is the literal string "Select department", and it would look
     * completely normal until it reached a dashboard.
     */
    private fun adapterOf(placeholder: String, items: List<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, listOf(placeholder) + items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    /** The selected value, or empty if the prompt row is still showing. */
    private fun Spinner.chosen(placeholder: String): String {
        val value = selectedItem?.toString().orEmpty()
        return if (value == placeholder) "" else value
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onPause() {
        super.onPause()
        enrolJob?.cancel()
        enrolJob = null
    }

    // ----------------------------------------------------------- enrolment

    private fun startEnrolment() {
        val pin = binding.enrolPin.text.toString().trim()
        val firstName = binding.enrolFirstName.text.toString().trim()
        val lastName = binding.enrolLastName.text.toString().trim()
        val department = binding.enrolDepartment.chosen(getString(R.string.select_department))
        val gender = binding.enrolGender.chosen(getString(R.string.select_gender))

        // Every field required, by request. The trade-off is deliberate and
        // worth naming: an incomplete record can no longer be created, which
        // also means an admin cannot enrol somebody in a hurry and fill in the
        // rest later. Departments must therefore exist before anyone can be
        // enrolled at all - hence the explicit message rather than an empty
        // dropdown that looks broken.
        if (departments.isEmpty()) {
            binding.enrolStatus.text = getString(R.string.enrol_need_departments)
            return
        }
        if (pin.isEmpty() || firstName.isEmpty() || lastName.isEmpty() ||
            department.isEmpty() || gender.isEmpty()
        ) {
            binding.enrolStatus.text = getString(R.string.enrol_need_all_fields)
            return
        }

        enrolJob?.cancel()
        enrolJob = lifecycleScope.launch {
            // One SF ID, one person. Checked against tombstoned rows too: an SF
            // ID belonging to somebody who has left must not be handed to a new
            // starter, because every punch ever recorded is keyed on it and the
            // two people's attendance would merge into one history.
            //
            // This only covers THIS device. Fleet-wide uniqueness needs the
            // roster pull - see SYNC-DESIGN.md - so until that lands, two
            // admins enrolling at the same moment on different terminals can
            // still collide.
            val taken = withContext(Dispatchers.IO) {
                AppDatabase.get(this@AdminActivity).employees().exists(pin) > 0
            }
            if (taken) {
                binding.enrolStatus.text = getString(R.string.enrol_duplicate_sf_id, pin)
                return@launch
            }
            if (!Biometrics.ensureReady(this@AdminActivity)) {
                binding.enrolStatus.text =
                    Biometrics.lastError ?: getString(R.string.sensor_unavailable)
                return@launch
            }

            val db = AppDatabase.get(this@AdminActivity)

            // Each finger is committed as soon as it is merged. Twelve presses
            // is a long way to get to the end and lose everything to one bad
            // sample, so a person who manages two fingers keeps two fingers.
            for (slot in FingerSlot.entries) {
                val template = enrolOneFinger(pin, slot) ?: return@launch

                withContext(Dispatchers.IO) {
                    val now = System.currentTimeMillis()
                    db.employees().upsert(
                        Employee(
                            pin = pin,
                            firstName = firstName,
                            lastName = lastName,
                            department = department,
                            gender = gender,
                            enrolledAt = now,
                            updatedAt = now,
                            pendingUpload = true
                        )
                    )
                    db.fingerprints().upsert(
                        Fingerprint(
                            employeePin = pin,
                            fingerIndex = slot.index,
                            template = template,
                            enrolledAt = now,
                            pendingUpload = true
                        )
                    )
                }

                // Reload before the next finger so the duplicate check below can
                // see the one just enrolled - that is what stops somebody
                // registering the same finger four times and calling it four.
                Biometrics.reloadTemplates(this@AdminActivity)

                binding.enrolStatus.text = getString(
                    R.string.enrol_finger_done,
                    getString(labelFor(slot)),
                    slot.index,
                    FingerSlot.entries.size
                )
                delay(FEEDBACK_MS)
            }

            SyncWorker.syncNow(this@AdminActivity)

            binding.enrolStatus.text = getString(
                R.string.enrol_done, "$firstName $lastName", FingerSlot.entries.size
            )
            binding.enrolPin.text?.clear()
            binding.enrolFirstName.text?.clear()
            binding.enrolLastName.text?.clear()
            binding.enrolDepartment.setSelection(0)
            binding.enrolGender.setSelection(0)
            binding.fingerprintPreview.visibility = View.GONE
        }
    }

    /**
     * Which finger the operator is being asked for.
     *
     * A name, not "finger 3 of 4". The number tells the person holding the
     * device nothing about which hand to offer, and four unlabelled prompts in
     * a row is how you end up with the same finger enrolled twice and one
     * genuinely useful finger missing.
     */
    private fun labelFor(slot: FingerSlot): Int = when (slot) {
        FingerSlot.RIGHT_INDEX -> R.string.finger_right_index
        FingerSlot.RIGHT_THUMB -> R.string.finger_right_thumb
        FingerSlot.LEFT_INDEX -> R.string.finger_left_index
        FingerSlot.LEFT_THUMB -> R.string.finger_left_thumb
    }

    /**
     * Collect three samples of one finger and merge them.
     *
     * Returns null if the operator should stop - the finger belongs to somebody
     * else, or the samples would not merge. A wrong finger mid-sequence is not
     * fatal; it just re-asks.
     */
    private suspend fun enrolOneFinger(pin: String, slot: FingerSlot): ByteArray? {
        val samples = ArrayList<ByteArray>(FingerprintEngine.SAMPLES_PER_FINGER)
        val fingerName = getString(labelFor(slot))

        while (currentCoroutineContext().isActive &&
            samples.size < FingerprintEngine.SAMPLES_PER_FINGER
        ) {
            binding.enrolStatus.text = getString(
                R.string.enrol_press,
                fingerName,
                samples.size + 1,
                FingerprintEngine.SAMPLES_PER_FINGER
            )

            val template = awaitCapture() ?: return null
            showPreview()

            if (samples.isEmpty()) {
                val existing = withContext(Dispatchers.IO) {
                    Biometrics.engine.identify(template, config.matchThreshold)
                }
                if (existing != null && existing.employeePin != pin) {
                    val who = withContext(Dispatchers.IO) {
                        AppDatabase.get(this@AdminActivity).employees()
                            .byPin(existing.employeePin)?.displayName
                    } ?: existing.employeePin
                    binding.enrolStatus.text = getString(R.string.enrol_already, who)
                    return null
                }
                // Their own, but a finger they already gave us: ask for a new one
                // rather than storing the same finger under two indices.
                if (existing != null && existing.employeePin == pin) {
                    val already = FingerSlot.fromIndex(existing.fingerIndex)
                    binding.enrolStatus.text = getString(
                        R.string.enrol_already_self,
                        already?.let { getString(labelFor(it)) } ?: fingerName
                    )
                    delay(FEEDBACK_MS)
                    continue
                }
            } else {
                val same = withContext(Dispatchers.IO) {
                    Biometrics.engine.verify(samples.last(), template)
                }
                if (!same) {
                    binding.enrolStatus.text = getString(R.string.enrol_same_finger)
                    delay(FEEDBACK_MS)
                    continue
                }
            }

            samples.add(template)
            binding.enrolStatus.text = getString(
                R.string.enrol_captured, fingerName, samples.size, FingerprintEngine.SAMPLES_PER_FINGER
            )
            // Force a lift between samples, otherwise one press is read three times.
            delay(LIFT_FINGER_MS)
        }

        if (samples.size < FingerprintEngine.SAMPLES_PER_FINGER) return null

        val merged = withContext(Dispatchers.IO) {
            Biometrics.engine.merge(samples[0], samples[1], samples[2])
        }
        if (merged == null) {
            binding.enrolStatus.text = getString(R.string.enrol_merge_failed)
        }
        return merged
    }

    /** Put the frame that produced the last template on screen. */
    private suspend fun showPreview() {
        val bitmap = withContext(Dispatchers.IO) { Biometrics.engine.lastImageBitmap() } ?: return
        binding.fingerprintPreview.setImageBitmap(bitmap)
        binding.fingerprintPreview.visibility = View.VISIBLE
    }

    /** Polls until a usable template appears, or the job is cancelled. */
    private suspend fun awaitCapture(): ByteArray? {
        while (true) {
            when (val c = withContext(Dispatchers.IO) { Biometrics.engine.capture() }) {
                is FingerprintEngine.Capture.Ok -> return c.template
                is FingerprintEngine.Capture.Failed -> delay(FingerprintEngine.POLL_INTERVAL_MS * 4)
                FingerprintEngine.Capture.None -> delay(FingerprintEngine.POLL_INTERVAL_MS)
            }
        }
    }

    companion object {
        private const val LIFT_FINGER_MS = 900L
        private const val FEEDBACK_MS = 1200L
    }
}
