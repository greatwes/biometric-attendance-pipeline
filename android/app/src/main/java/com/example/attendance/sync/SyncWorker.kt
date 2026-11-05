package com.example.attendance.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.attendance.Config
import com.example.attendance.data.AppDatabase
import java.util.concurrent.TimeUnit

/**
 * Drains the offline queue.
 *
 * Two triggers: one-shot immediately after a punch (so a healthy network gives
 * near-real-time delivery), and a periodic sweep that catches anything the
 * one-shot missed because the device was offline, asleep, or rebooted.
 *
 * The periodic sweep is the one that matters. WorkManager survives reboot and
 * process death; a background thread does not, and Android will kill a plain
 * service on a device like this without warning.
 */
class SyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val config = Config.get(applicationContext)
        if (!config.isConfigured) {
            Log.w(TAG, "not configured; nothing to do")
            return Result.success()
        }

        val db = AppDatabase.get(applicationContext)
        val api = AttendanceApi(config)

        // Counted rather than returned early on, so one bad row cannot hold up
        // everything queued behind it. Reported at the end as a warning.
        var permanentFailures = 0

        // Departments first: they are the smallest payload and the enrolment
        // dropdown on every other terminal depends on them.
        for (department in db.departments().pendingUpload()) {
            when (val r = api.uploadDepartment(department)) {
                is AttendanceApi.Result.Ok -> db.departments().markUploaded(department.name)
                is AttendanceApi.Result.Retryable -> {
                    Log.w(TAG, "department ${department.name} retryable: ${r.reason}")
                    return Result.retry()
                }
                is AttendanceApi.Result.Permanent -> {
                    Log.e(TAG, "department ${department.name} permanent: ${r.reason}")
                    db.departments().markFailed(department.name, r.reason)
                    permanentFailures++
                }
            }
        }

        // Roster and templates next. A punch for a PIN the server has never
        // heard of still lands (the parser creates a stub employee), but it
        // lands without a name, and that is what people notice on a dashboard.
        //
        // A permanent rejection now records the reason on the row and moves on,
        // where it used to abandon the whole run. One malformed record used to
        // block every person queued behind it indefinitely, and because nothing
        // was written down, the staff list could not say which one - or that
        // anything was wrong at all. A retryable error still stops the run,
        // because that means the network or the server is down and everything
        // behind it would fail the same way.
        for (employee in db.employees().pendingUpload()) {
            when (val r = api.uploadEmployee(employee)) {
                is AttendanceApi.Result.Ok -> db.employees().markUploaded(employee.pin)
                is AttendanceApi.Result.Retryable -> {
                    Log.w(TAG, "employee ${employee.pin} retryable: ${r.reason}")
                    return Result.retry()
                }
                is AttendanceApi.Result.Permanent -> {
                    // pendingUpload deliberately stays set. Something is
                    // misconfigured and a human has to fix it; clearing the flag
                    // would drop the enrolment silently. The error text is what
                    // turns this into a visible "Failed" on the staff list.
                    Log.e(TAG, "employee ${employee.pin} permanent: ${r.reason}")
                    db.employees().markFailed(employee.pin, r.reason)
                    permanentFailures++
                }
            }
        }

        // Templates after the roster rows, so a BIODATA push never arrives for
        // a PIN the server has no name for. One request per finger, mirroring
        // what a real terminal sends.
        for (finger in db.fingerprints().pendingUpload()) {
            val label = "${finger.employeePin} finger ${finger.fingerIndex}"
            when (val r = api.uploadFingerprint(finger)) {
                is AttendanceApi.Result.Ok ->
                    db.fingerprints().markUploaded(finger.employeePin, finger.fingerIndex)
                is AttendanceApi.Result.Retryable -> {
                    Log.w(TAG, "$label retryable: ${r.reason}")
                    return Result.retry()
                }
                is AttendanceApi.Result.Permanent -> {
                    Log.e(TAG, "$label permanent: ${r.reason}")
                    db.fingerprints().markFailed(finger.employeePin, finger.fingerIndex, r.reason)
                    permanentFailures++
                }
            }
        }

        var sent = 0
        while (true) {
            val batch = db.punches().queued(BATCH_SIZE)
            if (batch.isEmpty()) break

            when (val r = api.uploadPunches(batch)) {
                is AttendanceApi.Result.Ok -> {
                    db.punches().markUploaded(batch.map { it.id }, System.currentTimeMillis())
                    sent += batch.size
                }
                is AttendanceApi.Result.Retryable -> {
                    db.punches().markFailed(batch.map { it.id }, r.reason)
                    Log.w(TAG, "punch batch retryable: ${r.reason}")
                    return Result.retry()
                }
                is AttendanceApi.Result.Permanent -> {
                    db.punches().markFailed(batch.map { it.id }, r.reason)
                    Log.e(TAG, "punch batch permanent: ${r.reason}")
                    return Result.failure()
                }
            }
            // A short queue is the normal case; this loop only iterates after
            // an outage.
            if (batch.size < BATCH_SIZE) break
        }

        if (sent > 0) Log.i(TAG, "uploaded $sent punches")

        // Only ever removes rows Snowflake has already acknowledged.
        runCatching {
            db.punches().purgeUploadedBefore(
                System.currentTimeMillis() - TimeUnit.DAYS.toMillis(RETAIN_DAYS)
            )
        }

        // Tombstones whose deletion the server has confirmed. Cascades to the
        // person's templates. Only touches rows where pendingUpload is already
        // clear, so a deletion nobody else has heard about is never forgotten.
        runCatching { db.employees().purgeConfirmedDeletions() }
            .onSuccess { if (it > 0) Log.i(TAG, "purged $it confirmed deletion(s)") }

        // ------------------------------------------------------------ pull
        // Last, always. A device with unsent local enrolments that pulled first
        // would apply server state over its own pending writes - which is why
        // every phase above runs before this one, not because of any ordering
        // between them.
        when (val r = api.fetchRoster(config.rosterSince)) {
            is AttendanceApi.RosterResult.Ok -> {
                val snapshot = r.snapshot
                val changed = runCatching { RosterSync.apply(applicationContext, snapshot) }
                    .onFailure { Log.e(TAG, "roster apply failed; watermark not advanced", it) }
                    .getOrNull()

                if (changed != null) {
                    RosterSync.reloadMatcherIfNeeded(applicationContext, changed)
                    // Advanced only after the batch actually landed. If apply
                    // threw, this is skipped and the same page is re-fetched
                    // next run - every apply is idempotent, so replaying costs
                    // nothing while skipping a row costs somebody their
                    // enrolment.
                    if (snapshot.version > config.rosterSince) {
                        config.rosterSince = snapshot.version
                    }
                    if (!snapshot.isEmpty) {
                        Log.i(
                            TAG,
                            "roster: ${snapshot.employees.size} employee(s), " +
                                "${snapshot.departments.size} department(s) applied"
                        )
                    }
                }
            }
            is AttendanceApi.RosterResult.Retryable ->
                // Not a retry() - the push phases already succeeded, and
                // returning retry here would replay them on the next attempt.
                // The pull simply happens again on the next scheduled run.
                Log.w(TAG, "roster pull retryable: ${r.reason}")
            is AttendanceApi.RosterResult.Permanent ->
                Log.e(TAG, "roster pull permanent: ${r.reason}")
        }

        if (permanentFailures > 0) {
            // Deliberately still a success. The rows are marked and visible on
            // the staff list as Failed; returning failure here would make
            // WorkManager back off the whole schedule, delaying the punches and
            // roster rows that are perfectly fine.
            Log.w(TAG, "$permanentFailures row(s) rejected permanently - see the staff list")
        }

        return Result.success()
    }

    companion object {
        private const val TAG = "SyncWorker"
        private const val BATCH_SIZE = 200

        /**
         * How long delivered punches stay on the device. They are in Snowflake
         * by then; this is a local audit trail, not the record of truth.
         */
        private const val RETAIN_DAYS = 30L

        private const val PERIODIC_NAME = "zk-sync-periodic"
        private const val ONESHOT_NAME = "zk-sync-now"

        private val network = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /** Call once at startup. Idempotent. */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(network)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_NAME,
                // KEEP, so an app restart does not reset the schedule and
                // stampede every terminal at once after an update.
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        /** Call right after a punch is recorded. */
        fun syncNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(network)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                ONESHOT_NAME,
                // APPEND_OR_REPLACE would queue one job per punch. The worker
                // drains the whole queue anyway, so replacing is correct.
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
