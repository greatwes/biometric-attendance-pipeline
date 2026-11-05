package com.example.attendance

import android.app.DatePickerDialog
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.attendance.data.AppDatabase
import com.example.attendance.data.DeliveryState
import com.example.attendance.data.Punch
import com.example.attendance.data.PunchType
import com.example.attendance.databinding.ActivityRecordsBinding
import com.example.attendance.sync.SyncWorker
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Attendance history with its delivery state, filters, and a resend.
 *
 * The state shown here is derived from the punch rows themselves rather than
 * stored, so it cannot drift from what the sync worker will actually do.
 */
class RecordsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecordsBinding

    private var from: Calendar = startOfToday()
    private var to: Calendar = startOfToday()
    private var stateFilter: Int = FILTER_ALL
    private var observeJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecordsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.records)

        binding.fromButton.setOnClickListener { pickDate(from) { from = it; restart() } }
        binding.toButton.setOnClickListener { pickDate(to) { to = it; restart() } }

        binding.filterAll.setOnClickListener { stateFilter = FILTER_ALL; restart() }
        binding.filterSent.setOnClickListener { stateFilter = FILTER_SENT; restart() }
        binding.filterPending.setOnClickListener { stateFilter = FILTER_PENDING; restart() }
        binding.filterFailed.setOnClickListener { stateFilter = FILTER_FAILED; restart() }

        binding.resendButton.setOnClickListener { resendFailed() }

        restart()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun restart() {
        binding.fromButton.text = getString(R.string.filter_from) + ": " + DAY.format(from.time)
        binding.toButton.text = getString(R.string.filter_to) + ": " + DAY.format(to.time)

        observeJob?.cancel()
        observeJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppDatabase.get(this@RecordsActivity).punches()
                    .observeFiltered(from.timeInMillis, endOfDay(to), stateFilter, ROW_LIMIT)
                    .collectLatest { render(it) }
            }
        }
    }

    private suspend fun render(rows: List<Punch>) {
        val names = withContext(Dispatchers.IO) {
            val dao = AppDatabase.get(this@RecordsActivity).employees()
            rows.map { it.employeePin }.distinct().associateWith { dao.byPin(it)?.displayName }
        }

        binding.recordsSummary.text = resources.getQuantityString(
            R.plurals.record_count, rows.size, rows.size
        )

        binding.recordsList.removeAllViews()
        if (rows.isEmpty()) {
            binding.recordsList.addView(TextView(this).apply {
                text = getString(R.string.no_records)
                setTextColor(getColor(R.color.text_muted))
            })
            return
        }

        for (row in rows) {
            binding.recordsList.addView(TextView(this).apply {
                val who = names[row.employeePin] ?: row.employeePin
                val what = row.punchType.let { code ->
                    PunchType.fromCode(code)?.let { label(it) } ?: code.toString()
                }
                val where = row.location.ifBlank { "—" }
                text = "${STAMP.format(Date(row.punchedAt))}   $who\n$what · $where · ${stateLabel(row.state)}"
                setTextColor(getColor(colourFor(row.state)))
                textSize = 15f
                setPadding(0, 10, 0, 10)
            })
        }
    }

    /**
     * Re-queue everything that failed. Never touches delivered rows, so this
     * cannot double-count attendance no matter how often it is pressed.
     */
    private fun resendFailed() = lifecycleScope.launch {
        val requeued = withContext(Dispatchers.IO) {
            AppDatabase.get(this@RecordsActivity).punches().requeueAllFailed()
        }
        SyncWorker.syncNow(this@RecordsActivity)
        Toast.makeText(
            this@RecordsActivity,
            getString(R.string.resend_done, requeued),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun label(type: PunchType): String = getString(
        when (type) {
            PunchType.CLOCK_IN -> R.string.clock_in
            PunchType.CLOCK_OUT -> R.string.clock_out
            PunchType.CHECK_IN -> R.string.check_in
            PunchType.CHECK_OUT -> R.string.check_out
        }
    )

    private fun stateLabel(state: DeliveryState): String = getString(
        when (state) {
            DeliveryState.SENT -> R.string.state_sent
            DeliveryState.PENDING -> R.string.state_pending
            DeliveryState.FAILED -> R.string.state_failed
        }
    )

    private fun colourFor(state: DeliveryState): Int = when (state) {
        DeliveryState.SENT -> R.color.state_sent
        DeliveryState.PENDING -> R.color.state_pending
        DeliveryState.FAILED -> R.color.state_failed
    }

    private fun pickDate(initial: Calendar, onPicked: (Calendar) -> Unit) {
        DatePickerDialog(
            this,
            { _, year, month, day ->
                onPicked(
                    Calendar.getInstance().apply {
                        set(year, month, day, 0, 0, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                )
            },
            initial.get(Calendar.YEAR),
            initial.get(Calendar.MONTH),
            initial.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private companion object {
        const val FILTER_ALL = 0
        const val FILTER_SENT = 1
        const val FILTER_PENDING = 2
        const val FILTER_FAILED = 3

        /** Plain views, not a RecyclerView, so the list is capped deliberately. */
        const val ROW_LIMIT = 500

        val DAY = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
        val STAMP = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault())

        fun startOfToday(): Calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        /** Inclusive end of the chosen day, so "today to today" means all of today. */
        fun endOfDay(day: Calendar): Long = (day.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis
    }
}
