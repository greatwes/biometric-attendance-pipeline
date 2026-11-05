package com.example.attendance.sync

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.example.attendance.data.AppDatabase
import com.example.attendance.data.Department
import com.example.attendance.data.Employee
import com.example.attendance.data.Fingerprint
import com.example.attendance.fingerprint.Biometrics

/**
 * Applies a roster page pulled from the server.
 *
 * The fourth phase of a sync, and always the last one: a device with unsent
 * local enrolments that pulled first would apply server state over its own
 * pending writes. Push, then pull.
 */
object RosterSync {

    /**
     * Returns true if anything changed, meaning the matcher needs rebuilding.
     *
     * The whole page is applied in one transaction. A crash halfway through
     * would otherwise leave the watermark advanced past rows that were never
     * written, and those people would be invisible on this terminal until
     * somebody noticed and forced a full re-sync.
     */
    suspend fun apply(context: Context, snapshot: RosterSnapshot): Boolean {
        if (snapshot.isEmpty) return false
        val db = AppDatabase.get(context)

        var touchedMatcher = false

        db.withTransaction {
            for (remote in snapshot.departments) {
                val local = db.departments().byName(remote.name)

                // Never clobber something this device has not managed to push
                // yet. See the note on employees below - same rule, same reason.
                if (local != null && local.pendingUpload) continue

                if (remote.deleted) {
                    // Hard delete rather than a local tombstone. A tombstone
                    // exists to tell the server about a deletion it does not
                    // know about; this deletion came FROM the server, so there
                    // is nobody left to tell.
                    db.departments().deleteByName(remote.name)
                } else {
                    db.departments().upsertFromServer(
                        Department(
                            name = remote.name,
                            createdAt = local?.createdAt ?: System.currentTimeMillis(),
                            updatedAt = remote.version,
                            deletedAt = null,
                            // Already on the server by definition - marking it
                            // pending would push it straight back and loop.
                            pendingUpload = false,
                            lastError = null
                        )
                    )
                }
            }

            for (remote in snapshot.employees) {
                val local = db.employees().byPin(remote.pin)

                // The conflict rule, and it differs from SYNC-DESIGN.md on
                // purpose.
                //
                // That document said to skip when the local updatedAt is newer
                // AND pendingUpload is set. But local updatedAt is a device
                // clock and the remote version is Snowflake's - comparing them
                // is meaningless, and on a terminal whose clock is a day fast
                // it would silently discard every server change.
                //
                // So: skip anything with unpushed local changes, full stop. Our
                // edit reaches the server on the next push, and the pull after
                // that will agree with us. The cost is that a row stuck in
                // Failed never accepts server updates - which is visible on the
                // staff list, and far better than overwriting an enrolment
                // nobody has sent yet.
                if (local != null && local.pendingUpload) continue

                if (remote.deleted) {
                    if (local != null) {
                        unregisterAll(remote.pin)
                        // Cascades to fingerprints via the foreign key.
                        db.employees().delete(remote.pin)
                        touchedMatcher = true
                    }
                    continue
                }

                val (firstName, lastName) = remote.resolvedNames()
                db.employees().upsert(
                    Employee(
                        pin = remote.pin,
                        firstName = firstName,
                        lastName = lastName,
                        department = remote.department,
                        gender = remote.gender,
                        // Preserved so a person's original enrolment date is not
                        // reset to "whenever this terminal first heard of them".
                        enrolledAt = local?.enrolledAt ?: System.currentTimeMillis(),
                        updatedAt = remote.version,
                        deletedAt = null,
                        pendingUpload = false,
                        lastError = null
                    )
                )

                // Templates only arrive when the publisher is sending them, and
                // it will not until the portability test in SYNC-DESIGN.md
                // section 5 has passed on two units. Until then this loop is a
                // no-op and staff enrol their fingers on each terminal they use.
                for (finger in remote.fingers) {
                    db.fingerprints().upsert(
                        Fingerprint(
                            employeePin = remote.pin,
                            fingerIndex = finger.fingerIndex,
                            template = finger.template,
                            enrolledAt = local?.enrolledAt ?: System.currentTimeMillis(),
                            pendingUpload = false,
                            lastError = null
                        )
                    )
                    touchedMatcher = true
                }
            }
        }

        return touchedMatcher
    }

    /**
     * Drop every slot this person could hold from the live matcher.
     *
     * Templates live in the SDK's memory, so removing only the database rows
     * leaves somebody able to clock in until the next restart - and then they
     * stop, which looks like a sensor fault rather than a deletion.
     */
    private fun unregisterAll(pin: String) {
        for (index in 1..FINGER_SLOTS) {
            runCatching { Biometrics.engine.unregister(pin, index) }
        }
    }

    suspend fun reloadMatcherIfNeeded(context: Context, changed: Boolean) {
        if (!changed) return
        // Once, at the end, not per person: rebuilding the matcher is the
        // expensive part and doing it inside the loop would make a hundred-row
        // page take minutes.
        Biometrics.reloadTemplates(context)
        Log.i(TAG, "matcher rebuilt after roster changes")
    }

    private const val TAG = "RosterSync"
    private const val FINGER_SLOTS = 10
}
