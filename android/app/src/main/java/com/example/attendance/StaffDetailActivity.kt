package com.example.attendance

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.attendance.data.AppDatabase
import com.example.attendance.data.Employee
import com.example.attendance.data.SyncState
import com.example.attendance.databinding.ActivityStaffDetailBinding
import com.example.attendance.fingerprint.Biometrics
import com.example.attendance.sync.SyncWorker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One member of staff: their details, their sync state, and the three things an
 * admin can do to them.
 *
 * A page rather than buttons on the list row. The list is scanned quickly and
 * often, and Delete sitting next to a name in a scrolling list is how the wrong
 * person gets removed - a deliberate tap through to a page with their name at
 * the top makes that mistake much harder.
 */
class StaffDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStaffDetailBinding
    private lateinit var pin: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStaffDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        pin = intent.getStringExtra(EXTRA_PIN).orEmpty()
        if (pin.isBlank()) { finish(); return }

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.staff_detail)

        binding.detailSync.setOnClickListener { syncNow() }
        binding.detailEdit.setOnClickListener { promptEdit() }
        binding.detailDelete.setOnClickListener { confirmDelete() }

        // Observed rather than loaded once, so the state badge updates itself
        // when the sync worker finishes while this screen is open - otherwise
        // an admin taps Sync now, sees "Pending", and taps it again.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppDatabase.get(this@StaffDetailActivity).employees().observeByPin(pin)
                    .collectLatest { person ->
                        // Null once a deletion has been confirmed and purged.
                        if (person == null) finish() else render(person)
                    }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun render(person: Employee) = lifecycleScope.launch {
        val db = AppDatabase.get(this@StaffDetailActivity)
        val fingers = withContext(Dispatchers.IO) { db.fingerprints().countForEmployee(pin) }

        binding.detailName.text = person.displayName
        binding.detailSfId.text = "${getString(R.string.sf_id)} ${person.pin}"
        binding.detailMeta.text = getString(
            R.string.staff_summary,
            person.department.ifBlank { "—" },
            person.gender.ifBlank { "—" },
            fingers
        )
        binding.detailEnrolled.text =
            getString(R.string.staff_enrolled_on, DATE.format(Date(person.enrolledAt)))

        val state = person.syncState
        binding.detailState.setText(labelFor(state))
        binding.detailState.setTextColor(getColor(colorFor(state)))

        binding.detailError.visibility = if (person.lastError != null) View.VISIBLE else View.GONE
        person.lastError?.let {
            binding.detailError.text = getString(R.string.staff_last_error, it)
        }

        // A tombstone still on the device is worth saying out loud: the person
        // is gone from every screen but the row is deliberately still here
        // until the server confirms, and without a note that looks like a bug.
        if (person.deletedAt != null) {
            binding.detailError.visibility = View.VISIBLE
            binding.detailError.text = getString(R.string.staff_pending_delete)
        }
    }

    /**
     * Re-queue this one person and their templates, then run the worker.
     *
     * Re-flags rather than uploading directly, for the same reason the roster
     * re-sync does: SyncWorker owns ordering, retries and the network
     * constraint, and a second upload path here would drift from it.
     */
    private fun syncNow() = lifecycleScope.launch {
        val db = AppDatabase.get(this@StaffDetailActivity)
        val name = withContext(Dispatchers.IO) {
            db.employees().markPending(pin)
            db.fingerprints().markPendingForEmployee(pin)
            db.employees().byPin(pin)?.displayName ?: pin
        }
        SyncWorker.syncNow(this@StaffDetailActivity)
        toast(getString(R.string.staff_sync_queued, name))
    }

    /** Edit details only. Re-enrolling fingers is the enrolment screen's job. */
    private fun promptEdit() = lifecycleScope.launch {
        val db = AppDatabase.get(this@StaffDetailActivity)
        val person = withContext(Dispatchers.IO) { db.employees().byPin(pin) } ?: return@launch
        val departments = withContext(Dispatchers.IO) { db.departments().activeNames() }

        val firstName = EditText(this@StaffDetailActivity).apply {
            setText(person.firstName)
            hint = getString(R.string.first_name)
            inputType = InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        val lastName = EditText(this@StaffDetailActivity).apply {
            setText(person.lastName)
            hint = getString(R.string.last_name)
            inputType = InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }

        // The person's current department is included even if it has since been
        // removed from the list. Otherwise editing somebody's name would
        // silently move them to whichever department happens to sort first.
        val departmentOptions =
            (departments + person.department).filter { it.isNotBlank() }.distinct()
        val department = Spinner(this@StaffDetailActivity).apply {
            adapter = adapterOf(departmentOptions)
            setSelection(departmentOptions.indexOf(person.department).coerceAtLeast(0))
        }

        val genders = listOf(getString(R.string.gender_male), getString(R.string.gender_female))
        val gender = Spinner(this@StaffDetailActivity).apply {
            adapter = adapterOf(genders)
            setSelection(genders.indexOfFirst { it.equals(person.gender, true) }.coerceAtLeast(0))
        }

        val fields = LinearLayout(this@StaffDetailActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(firstName)
            addView(lastName)
            addView(department)
            addView(gender)
        }

        AlertDialog.Builder(this@StaffDetailActivity)
            .setTitle(person.pin)
            .setView(fields)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save_changes) { _, _ ->
                val first = firstName.text.toString().trim()
                val last = lastName.text.toString().trim()
                val dept = department.selectedItem?.toString().orEmpty()
                val sex = gender.selectedItem?.toString().orEmpty()
                if (first.isEmpty() || last.isEmpty() || dept.isEmpty() || sex.isEmpty()) {
                    toast(getString(R.string.enrol_need_all_fields))
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        // updatedAt drives last-write-wins once devices sync;
                        // pendingUpload is set so the edit reaches the server.
                        db.employees().updateDetails(
                            pin = person.pin,
                            firstName = first,
                            lastName = last,
                            department = dept,
                            gender = sex,
                            updatedAt = System.currentTimeMillis()
                        )
                    }
                    SyncWorker.syncNow(this@StaffDetailActivity)
                    toast(getString(R.string.staff_updated, "$first $last"))
                }
            }
            .show()
    }

    private fun confirmDelete() = lifecycleScope.launch {
        val db = AppDatabase.get(this@StaffDetailActivity)
        val person = withContext(Dispatchers.IO) { db.employees().byPin(pin) } ?: return@launch
        val fingers = withContext(Dispatchers.IO) { db.fingerprints().countForEmployee(pin) }

        AlertDialog.Builder(this@StaffDetailActivity)
            .setTitle(getString(R.string.confirm_delete_title, person.displayName))
            .setMessage(getString(R.string.confirm_delete_body, fingers))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> delete(person) }
            .show()
    }

    /**
     * Remove the person from the matcher and tombstone their row.
     *
     * The matcher part is the one that matters: templates live in the SDK's
     * memory, so tombstoning only the database row leaves the person able to
     * clock in until the next restart - and then they silently stop working,
     * which looks like a sensor fault rather than a deletion.
     *
     * The row itself is NOT deleted. With several terminals, a missing row
     * cannot be told apart from one that has not been pulled yet, so a hard
     * delete here would be undone by the next sync. The tombstone is pushed,
     * and SyncWorker purges it once the server has confirmed - which is also
     * what finally removes the templates, via the foreign key.
     *
     * Attendance already recorded is kept on purpose. Somebody leaving must not
     * erase the month they worked, and those punches may not have reached
     * Snowflake yet.
     */
    private fun delete(person: Employee) = lifecycleScope.launch {
        val db = AppDatabase.get(this@StaffDetailActivity)
        withContext(Dispatchers.IO) {
            for (index in 1..FINGER_SLOTS) {
                runCatching { Biometrics.engine.unregister(person.pin, index) }
            }
            db.employees().softDelete(person.pin, System.currentTimeMillis())
        }
        // Rebuild from what is left, so the matcher and the database cannot
        // drift apart if any single unregister above was ignored.
        Biometrics.reloadTemplates(this@StaffDetailActivity)
        SyncWorker.syncNow(this@StaffDetailActivity)

        toast(getString(R.string.staff_deleted, person.displayName))
        finish()
    }

    private fun adapterOf(items: List<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun labelFor(state: SyncState) = when (state) {
        SyncState.SYNCED -> R.string.sync_synced
        SyncState.PENDING -> R.string.sync_pending
        SyncState.FAILED -> R.string.sync_failed
    }

    private fun colorFor(state: SyncState) = when (state) {
        SyncState.SYNCED -> R.color.state_sent
        SyncState.PENDING -> R.color.state_pending
        SyncState.FAILED -> R.color.state_failed
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_PIN = "pin"

        /** Covers every slot enrolment can use; unregistering a spare is harmless. */
        private const val FINGER_SLOTS = 10

        private val DATE = SimpleDateFormat("d MMMM yyyy", Locale.getDefault())
    }
}
