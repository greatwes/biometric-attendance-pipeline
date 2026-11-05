package com.example.attendance

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.attendance.data.AppDatabase
import com.example.attendance.data.Employee
import com.example.attendance.data.SyncState
import com.example.attendance.databinding.ActivityStaffListBinding
import com.example.attendance.databinding.ItemStaffBinding
import com.example.attendance.sync.SyncWorker

/**
 * The enrolled roster: searchable, with a sync state per person.
 *
 * Deliberately does not open the fingerprint sensor. Editing a name or removing
 * somebody must work whether or not the sensor came up, and only the enrolment
 * screen should ever own the engine.
 *
 * Rows are recycled and the list is driven by a database query rather than
 * filtered in memory, because this is expected to hold several hundred people
 * and re-inflating that on every keystroke would make the search unusable on
 * this hardware.
 */
class StaffListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStaffListBinding
    private lateinit var adapter: StaffAdapter

    /** Current search text, as a flow so the query re-runs when it changes. */
    private val search = MutableStateFlow("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStaffListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.menu_staff)

        adapter = StaffAdapter { openDetail(it) }
        binding.staffList.layoutManager = LinearLayoutManager(this)
        binding.staffList.adapter = adapter

        binding.staffSearch.doAfterTextChanged { search.value = it?.toString()?.trim().orEmpty() }
        binding.pushPending.setOnClickListener { pushPending() }
        binding.resyncAll.setOnClickListener { confirmResync() }

        observe()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun observe() {
        val db = AppDatabase.get(this)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // The finger counts are a separate query joined in memory
                // rather than a SQL join: one row per person plus one small
                // grouped count is cheaper than a join that has to touch the
                // template blobs, and the counts change far less often.
                search
                    .flatMapLatest { q ->
                        combine(
                            db.employees().observeSearch(q),
                            db.fingerprints().observeCounts()
                        ) { staff, counts ->
                            val byPin = counts.associate { it.employeePin to it.fingers }
                            staff.map { StaffRow(it, byPin[it.pin] ?: 0) }
                        }
                    }
                    .collectLatest { render(it) }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                db.employees().observeSyncCounts().collectLatest {
                    binding.syncCounts.text =
                        getString(R.string.sync_counts, it.synced, it.pending, it.failed)
                }
            }
        }
    }

    private fun render(rows: List<StaffRow>) {
        adapter.submitList(rows)

        binding.staffCount.text =
            resources.getQuantityString(R.plurals.enrolled_count, rows.size, rows.size)

        val query = search.value
        binding.emptyMessage.text = when {
            rows.isNotEmpty() -> ""
            query.isNotEmpty() -> getString(R.string.staff_no_matches, query)
            else -> getString(R.string.nobody_enrolled)
        }
        binding.emptyMessage.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openDetail(row: StaffRow) {
        startActivity(
            Intent(this, StaffDetailActivity::class.java)
                .putExtra(StaffDetailActivity.EXTRA_PIN, row.employee.pin)
        )
    }

    // ------------------------------------------------------------- syncing

    /**
     * Kick the queue: nothing is re-flagged, the worker just runs now.
     *
     * The everyday action, and the one to reach for when a few rows say
     * Pending. It cannot lose anything, so there is no confirmation.
     */
    private fun pushPending() = lifecycleScope.launch {
        val db = AppDatabase.get(this@StaffListActivity)
        val queued = withContext(Dispatchers.IO) {
            db.employees().pendingUpload().size +
                db.fingerprints().pendingUpload().size +
                db.departments().pendingUpload().size
        }
        SyncWorker.syncNow(this@StaffListActivity)
        toast(getString(R.string.push_all_pending_done, queued))
    }

    /**
     * Put the whole roster back in the upload queue.
     *
     * Needed because delivery is acknowledged, not verified. The device clears
     * pendingUpload the moment the server answers 200, so anything the server
     * accepted and then lost is invisible from here: the roster looks fully
     * delivered and no retry will ever fire. Without this the only recovery for
     * a lost template is re-enrolling the person's fingers in person.
     *
     * Deliberately re-flags rather than uploading directly - SyncWorker already
     * owns ordering (employees before templates), retry policy and the network
     * constraint, and duplicating any of that here would let the two drift.
     */
    private fun confirmResync() = lifecycleScope.launch {
        val db = AppDatabase.get(this@StaffListActivity)
        val staff = withContext(Dispatchers.IO) { db.employees().count() }
        val templates = withContext(Dispatchers.IO) { db.fingerprints().count() }

        if (staff == 0 && templates == 0) {
            toast(getString(R.string.resync_nothing))
            return@launch
        }

        AlertDialog.Builder(this@StaffListActivity)
            .setTitle(R.string.resync_title)
            .setMessage(getString(R.string.resync_body, staff, templates))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.resync_confirm) { _, _ -> resync() }
            .show()
    }

    private fun resync() = lifecycleScope.launch {
        val db = AppDatabase.get(this@StaffListActivity)
        val (staff, templates) = withContext(Dispatchers.IO) {
            // Employees first so that if the process dies between the two
            // statements, the server never receives a template for a PIN it has
            // no name for.
            db.departments().markAllPending()
            db.employees().markAllPending() to db.fingerprints().markAllPending()
        }

        SyncWorker.syncNow(this@StaffListActivity)
        toast(getString(R.string.resync_queued, staff, templates))
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    // -------------------------------------------------------------- adapter

    /** One person plus the count that needs a second query to know. */
    data class StaffRow(val employee: Employee, val fingers: Int)

    private class StaffAdapter(
        private val onClick: (StaffRow) -> Unit
    ) : ListAdapter<StaffRow, StaffAdapter.Holder>(DIFF) {

        class Holder(val binding: ItemStaffBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            ItemStaffBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = getItem(position)
            val context = holder.itemView.context
            val person = row.employee

            holder.binding.staffName.text = "${person.pin}  ${person.displayName}"
            holder.binding.staffMeta.text = context.getString(
                R.string.staff_summary,
                person.department.ifBlank { "—" },
                person.gender.ifBlank { "—" },
                row.fingers
            )

            val state = person.syncState
            holder.binding.staffState.setText(labelFor(state))
            holder.binding.staffState.setTextColor(context.getColor(colorFor(state)))

            holder.itemView.setOnClickListener { onClick(row) }
        }

        private companion object {
            fun labelFor(state: SyncState) = when (state) {
                SyncState.SYNCED -> R.string.sync_synced
                SyncState.PENDING -> R.string.sync_pending
                SyncState.FAILED -> R.string.sync_failed
            }

            fun colorFor(state: SyncState) = when (state) {
                SyncState.SYNCED -> R.color.state_sent
                SyncState.PENDING -> R.color.state_pending
                SyncState.FAILED -> R.color.state_failed
            }

            val DIFF = object : DiffUtil.ItemCallback<StaffRow>() {
                // The SF ID is the identity; everything else can change.
                override fun areItemsTheSame(a: StaffRow, b: StaffRow) =
                    a.employee.pin == b.employee.pin

                override fun areContentsTheSame(a: StaffRow, b: StaffRow) =
                    a == b
            }
        }
    }
}
