package com.example.attendance

import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
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
import com.example.attendance.data.Department
import com.example.attendance.data.SyncState
import com.example.attendance.databinding.ActivityDepartmentBinding
import com.example.attendance.sync.SyncWorker

/**
 * The department list the enrolment dropdown offers.
 *
 * Looks like [LocationActivity] and is not the same thing underneath. Location
 * is a device setting - this terminal is at Lapai, that one is at Minna - and
 * lives in preferences. Departments are shared: the whole point of a closed
 * list is that "Field Ops" means the same thing on every terminal and in
 * Snowflake, which only works if the list itself is one list. So these are rows
 * in the database, pushed to the server like any other record.
 *
 * Until the roster pull lands (SYNC-DESIGN.md), that sharing is one-way: what
 * an admin creates here reaches Snowflake, but a department created on another
 * terminal will not appear here on its own.
 */
class DepartmentActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDepartmentBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDepartmentBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.department_setting)

        binding.addDepartmentButton.setOnClickListener { add() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppDatabase.get(this@DepartmentActivity).departments().observeActive()
                    .collectLatest { render(it) }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun add() = lifecycleScope.launch {
        val name = binding.newDepartment.text.toString().trim()
        if (name.isEmpty()) return@launch

        val db = AppDatabase.get(this@DepartmentActivity)
        val now = System.currentTimeMillis()

        val outcome = withContext(Dispatchers.IO) {
            // insert IGNOREs a clash, which covers the case that matters: a
            // department deleted earlier is still in the table as a tombstone,
            // so a plain insert would silently do nothing and the admin would
            // watch their department fail to appear. Restoring the tombstone is
            // what they actually meant.
            val inserted = db.departments().insert(
                Department(name = name, createdAt = now, updatedAt = now, pendingUpload = true)
            )
            if (inserted != -1L) {
                Outcome.ADDED
            } else {
                val live = db.departments().activeNames().any { it.equals(name, true) }
                if (live) {
                    Outcome.ALREADY_THERE
                } else {
                    db.departments().restore(name, now)
                    Outcome.ADDED
                }
            }
        }

        binding.newDepartment.text?.clear()
        when (outcome) {
            Outcome.ADDED -> {
                SyncWorker.syncNow(this@DepartmentActivity)
                toast(getString(R.string.department_added, name))
            }
            Outcome.ALREADY_THERE -> toast(getString(R.string.department_exists, name))
        }
    }

    private fun render(departments: List<Department>) {
        binding.departmentList.removeAllViews()
        if (departments.isEmpty()) {
            binding.departmentList.addView(TextView(this).apply {
                text = getString(R.string.department_none_yet)
                setTextColor(getColor(R.color.text_muted))
            })
            return
        }
        for (department in departments) {
            binding.departmentList.addView(rowFor(department))
        }
    }

    private fun rowFor(department: Department): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 8)

            addView(LinearLayout(this@DepartmentActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams =
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

                addView(TextView(this@DepartmentActivity).apply {
                    text = department.name
                    setTextColor(getColor(R.color.text_primary))
                    textSize = 16f
                })
                // The sync state is shown here for the same reason it is on the
                // staff list: a department that never reached the server is a
                // department the next terminal will not offer, and there is
                // otherwise nothing on screen to say so.
                addView(TextView(this@DepartmentActivity).apply {
                    text = getString(labelFor(department.syncState))
                    setTextColor(getColor(colorFor(department.syncState)))
                    textSize = 13f
                })
            })

            addView(Button(this@DepartmentActivity).apply {
                text = getString(R.string.delete)
                setOnClickListener { confirmRemove(department) }
            })
        }

    /**
     * Removing a department in use is allowed but called out.
     *
     * Staff keep whatever they were filed under - rewriting their records to
     * some placeholder would be a silent edit of everybody's data to fix one
     * admin's misclick. The department simply stops being offered.
     */
    private fun confirmRemove(department: Department) = lifecycleScope.launch {
        val db = AppDatabase.get(this@DepartmentActivity)
        val inUse = withContext(Dispatchers.IO) { db.departments().staffCount(department.name) }

        val message = if (inUse > 0) {
            getString(R.string.department_remove_in_use, department.name, inUse)
        } else {
            getString(R.string.department_remove_warning, department.name)
        }

        AlertDialog.Builder(this@DepartmentActivity)
            .setTitle(getString(R.string.confirm_delete_title, department.name))
            .setMessage(message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        // Tombstone, not DELETE. An absent row cannot be told
                        // apart from one another device has not pulled yet, so a
                        // hard delete here would be undone by the next sync.
                        db.departments().softDelete(department.name, System.currentTimeMillis())
                    }
                    SyncWorker.syncNow(this@DepartmentActivity)
                    toast(getString(R.string.department_removed, department.name))
                }
            }
            .show()
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

    /** What [add] did, so the caller can pick the right message. */
    private enum class Outcome { ADDED, ALREADY_THERE }
}
