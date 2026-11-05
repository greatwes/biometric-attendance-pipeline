package com.example.attendance.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface EmployeeDao {

    /**
     * @Upsert, never @Insert(REPLACE). This is load-bearing.
     *
     * In SQLite, REPLACE is DELETE-then-INSERT. Since v4, `fingerprints` has a
     * foreign key to this table with ON DELETE CASCADE - so replacing an
     * employee row silently destroys every fingerprint that person has.
     *
     * That is not theoretical. Enrolment re-upserts the employee once per
     * finger, so with REPLACE each finger wiped the ones before it and a
     * four-finger enrolment ended with exactly one template. It looked like a
     * successful enrolment, synced cleanly, and then failed to recognise any
     * finger except the last one captured. The roster pull did the same thing
     * on every apply.
     *
     * @Upsert compiles to INSERT ... ON CONFLICT DO UPDATE: it modifies the row
     * in place, so no delete fires and nothing cascades.
     */
    @Upsert
    suspend fun upsert(employee: Employee)

    /**
     * Everyone currently on the roster.
     *
     * Tombstoned rows are excluded everywhere except the sync path. They still
     * exist in the table - that is the whole point of a tombstone - but as far
     * as the screens, the matcher and the head-count are concerned the person
     * is gone the moment an admin deletes them, not when the server finally
     * confirms it.
     */
    @Query("SELECT * FROM employees WHERE deletedAt IS NULL ORDER BY firstName, lastName")
    suspend fun all(): List<Employee>

    @Query("SELECT * FROM employees WHERE deletedAt IS NULL ORDER BY firstName, lastName")
    fun observeAll(): Flow<List<Employee>>

    /**
     * Staff list search. Matches SF ID, either name, or the two names together
     * so that typing "ibrahim alkali" finds somebody whose parts are stored
     * separately. LIKE is case-insensitive for ASCII in SQLite, which is what
     * an admin typing into a search box expects.
     */
    @Query(
        """
        SELECT * FROM employees
        WHERE deletedAt IS NULL
          AND (:query = ''
               OR pin LIKE '%' || :query || '%'
               OR firstName LIKE '%' || :query || '%'
               OR lastName LIKE '%' || :query || '%'
               OR (firstName || ' ' || lastName) LIKE '%' || :query || '%')
        ORDER BY firstName, lastName
        """
    )
    fun observeSearch(query: String): Flow<List<Employee>>

    @Query("SELECT * FROM employees WHERE pin = :pin")
    suspend fun byPin(pin: String): Employee?

    @Query("SELECT * FROM employees WHERE pin = :pin")
    fun observeByPin(pin: String): Flow<Employee?>

    /**
     * Does this SF ID already exist, tombstone included?
     *
     * Checks deleted rows too. Re-using the SF ID of somebody who has left
     * would attach their attendance history to a different person, because
     * every punch ever recorded is keyed on this value.
     */
    @Query("SELECT COUNT(*) FROM employees WHERE pin = :pin")
    suspend fun exists(pin: String): Int

    /**
     * Rows waiting to go up, tombstones included - a deletion is a push like
     * any other, and excluding it here is exactly how deletion silently stops
     * working across devices.
     */
    @Query("SELECT * FROM employees WHERE pendingUpload = 1 ORDER BY updatedAt")
    suspend fun pendingUpload(): List<Employee>

    /** Clears the error as well: a row that has landed is no longer failed. */
    @Query("UPDATE employees SET pendingUpload = 0, lastError = NULL WHERE pin = :pin")
    suspend fun markUploaded(pin: String)

    /**
     * Record a permanent rejection without clearing pendingUpload.
     *
     * The row stays queued on purpose. Something is misconfigured and a human
     * has to fix it; dropping the enrolment because the server said no would
     * lose it silently, which is the failure mode this whole status column
     * exists to make visible.
     */
    @Query("UPDATE employees SET lastError = :error WHERE pin = :pin")
    suspend fun markFailed(pin: String, error: String?)

    /**
     * Delete as a write, not an absence.
     *
     * updatedAt moves too, because incremental pull selects on
     * `UPDATED_AT > :since` and a tombstone that does not move it is never sent
     * to anyone. pendingUpload goes back to 1 so the deletion is pushed.
     */
    @Query(
        """
        UPDATE employees
        SET deletedAt = :at, updatedAt = :at, pendingUpload = 1, lastError = NULL
        WHERE pin = :pin
        """
    )
    suspend fun softDelete(pin: String, at: Long)

    /** Undo a tombstone, for somebody deleted by mistake or returning. */
    @Query(
        """
        UPDATE employees
        SET deletedAt = NULL, updatedAt = :at, pendingUpload = 1, lastError = NULL
        WHERE pin = :pin
        """
    )
    suspend fun restore(pin: String, at: Long)

    /**
     * Drop tombstones the server has confirmed. Cascades to their templates.
     *
     * Only ever touches rows where pendingUpload is already clear, so a
     * deletion that has not been delivered cannot be forgotten before anyone
     * else hears about it.
     */
    @Query("DELETE FROM employees WHERE deletedAt IS NOT NULL AND pendingUpload = 0")
    suspend fun purgeConfirmedDeletions(): Int

    @Query("DELETE FROM employees WHERE pin = :pin")
    suspend fun delete(pin: String)

    @Query("SELECT COUNT(*) FROM employees WHERE deletedAt IS NULL")
    suspend fun count(): Int

    /**
     * Counts per sync state, for the header on the staff list.
     *
     * COALESCE is not decoration. An aggregate over an empty table still
     * returns one row, and SUM of nothing is NULL - which Room cannot map onto
     * the non-null Ints in [SyncCounts], so the very first launch on a fresh
     * device would crash the staff list before anyone could enrol anybody.
     */
    @Query(
        """
        SELECT
            COALESCE(SUM(CASE WHEN pendingUpload = 0 THEN 1 ELSE 0 END), 0) AS synced,
            COALESCE(SUM(CASE WHEN pendingUpload = 1 AND lastError IS NULL THEN 1 ELSE 0 END), 0) AS pending,
            COALESCE(SUM(CASE WHEN pendingUpload = 1 AND lastError IS NOT NULL THEN 1 ELSE 0 END), 0) AS failed
        FROM employees
        WHERE deletedAt IS NULL
        """
    )
    fun observeSyncCounts(): Flow<SyncCounts>

    /**
     * Queue every employee for upload again, returning how many were marked.
     *
     * The repair for a server that acknowledged rows it did not keep. That is
     * not hypothetical: on 4 Sep 2026 n8n returned 200 and queued the roster,
     * the Dataiku drain rolled the transaction back, and the queue rows were
     * acked and deleted. The device had already cleared pendingUpload on the
     * 200, so it believed the roster was delivered and would never have sent
     * it again - the enrolments were reachable only by re-enrolling in person.
     *
     * Safe to run at any time: every statement the server builds is a MERGE on
     * a business key, so a re-upload updates rather than duplicates.
     */
    @Query("UPDATE employees SET pendingUpload = 1, lastError = NULL")
    suspend fun markAllPending(): Int

    @Query("UPDATE employees SET pendingUpload = 1, lastError = NULL WHERE pin = :pin")
    suspend fun markPending(pin: String): Int

    /** Edit without touching enrolledAt, so the original enrolment date survives. */
    @Query(
        """
        UPDATE employees
        SET firstName = :firstName, lastName = :lastName,
            department = :department, gender = :gender,
            updatedAt = :updatedAt, pendingUpload = 1, lastError = NULL
        WHERE pin = :pin
        """
    )
    suspend fun updateDetails(
        pin: String,
        firstName: String,
        lastName: String,
        department: String,
        gender: String,
        updatedAt: Long
    )
}

/** Header projection for the staff list. */
data class SyncCounts(val synced: Int, val pending: Int, val failed: Int)

@Dao
interface FingerprintDao {

    /**
     * Re-enrolling finger N overwrites it rather than failing.
     *
     * @Upsert rather than @Insert(REPLACE) for consistency with [EmployeeDao] -
     * this table has no children so nothing would cascade today, but an
     * in-place update is the correct operation and leaves no trap for whoever
     * adds a table referencing this one.
     */
    @Upsert
    suspend fun upsert(fingerprint: Fingerprint)

    @Query("SELECT * FROM fingerprints ORDER BY employeePin, fingerIndex")
    suspend fun all(): List<Fingerprint>

    /**
     * What the matcher should hold: templates belonging to people still on the
     * roster.
     *
     * Tombstoned staff keep their rows until the server confirms the deletion,
     * so loading [all] into the engine would let somebody who was deleted this
     * morning carry on clocking in until the next sync - and then stop, which
     * looks like a broken sensor rather than a deletion.
     */
    @Query(
        """
        SELECT f.* FROM fingerprints f
        JOIN employees e ON e.pin = f.employeePin
        WHERE e.deletedAt IS NULL
        ORDER BY f.employeePin, f.fingerIndex
        """
    )
    suspend fun allForMatcher(): List<Fingerprint>

    @Query("SELECT * FROM fingerprints WHERE employeePin = :pin ORDER BY fingerIndex")
    suspend fun forEmployee(pin: String): List<Fingerprint>

    @Query("SELECT * FROM fingerprints WHERE employeePin = :pin ORDER BY fingerIndex")
    fun observeForEmployee(pin: String): Flow<List<Fingerprint>>

    @Query("SELECT COUNT(*) FROM fingerprints WHERE employeePin = :pin")
    suspend fun countForEmployee(pin: String): Int

    /** Lowest unused 1-based slot, so a partial enrolment resumes where it stopped. */
    @Query("SELECT COALESCE(MAX(fingerIndex), 0) FROM fingerprints WHERE employeePin = :pin")
    suspend fun highestIndexFor(pin: String): Int

    /**
     * Templates waiting to go up, for people still on the roster.
     *
     * Excludes tombstoned staff, unlike the employee equivalent: the deletion
     * itself is carried by the EMPLOYEES tombstone, and pushing a template for
     * somebody we have just asked the server to remove is at best wasted work
     * and at worst re-creates them.
     */
    @Query(
        """
        SELECT f.* FROM fingerprints f
        JOIN employees e ON e.pin = f.employeePin
        WHERE f.pendingUpload = 1 AND e.deletedAt IS NULL
        ORDER BY f.employeePin, f.fingerIndex
        """
    )
    suspend fun pendingUpload(): List<Fingerprint>

    @Query(
        """
        UPDATE fingerprints SET pendingUpload = 0, lastError = NULL
        WHERE employeePin = :pin AND fingerIndex = :index
        """
    )
    suspend fun markUploaded(pin: String, index: Int)

    /** See [EmployeeDao.markFailed] - the row stays queued deliberately. */
    @Query("UPDATE fingerprints SET lastError = :error WHERE employeePin = :pin AND fingerIndex = :index")
    suspend fun markFailed(pin: String, index: Int, error: String?)

    @Query("UPDATE fingerprints SET pendingUpload = 1, lastError = NULL WHERE employeePin = :pin")
    suspend fun markPendingForEmployee(pin: String): Int

    @Query("DELETE FROM fingerprints WHERE employeePin = :pin")
    suspend fun deleteForEmployee(pin: String)

    @Query("SELECT COUNT(*) FROM fingerprints")
    suspend fun count(): Int

    /**
     * Queue every template for upload again, returning how many were marked.
     *
     * This is the half that mattered. [EmployeeDao.updateDetails] re-flags an
     * employee row as a side effect of editing, so a name could always be
     * pushed again by opening the edit dialog and saving. Nothing anywhere set
     * fingerprints.pendingUpload back to 1, so a template that the server lost
     * after acknowledging it was unrecoverable without physically re-enrolling
     * the person's fingers.
     */
    @Query("UPDATE fingerprints SET pendingUpload = 1, lastError = NULL")
    suspend fun markAllPending(): Int

    @Query("SELECT employeePin, COUNT(*) AS fingers FROM fingerprints GROUP BY employeePin")
    fun observeCounts(): Flow<List<FingerCount>>
}

/** Roster projection: how many fingers each person has enrolled. */
data class FingerCount(val employeePin: String, val fingers: Int)

@Dao
interface DepartmentDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(department: Department): Long

    /** In-place, for rows arriving from the server rather than an admin. */
    @Upsert
    suspend fun upsertFromServer(department: Department)

    @Query("DELETE FROM departments WHERE name = :name")
    suspend fun deleteByName(name: String)

    @Query("SELECT * FROM departments WHERE name = :name")
    suspend fun byName(name: String): Department?

    /**
     * The list the enrolment dropdown offers. Tombstones excluded, so a
     * department an admin removed stops being offered immediately rather than
     * when the server catches up.
     */
    @Query("SELECT * FROM departments WHERE deletedAt IS NULL ORDER BY name")
    fun observeActive(): Flow<List<Department>>

    @Query("SELECT name FROM departments WHERE deletedAt IS NULL ORDER BY name")
    suspend fun activeNames(): List<String>

    @Query("SELECT * FROM departments WHERE pendingUpload = 1 ORDER BY updatedAt")
    suspend fun pendingUpload(): List<Department>

    @Query("UPDATE departments SET pendingUpload = 0, lastError = NULL WHERE name = :name")
    suspend fun markUploaded(name: String)

    @Query("UPDATE departments SET lastError = :error WHERE name = :name")
    suspend fun markFailed(name: String, error: String?)

    /** Tombstone, for the same reasons as [EmployeeDao.softDelete]. */
    @Query(
        """
        UPDATE departments
        SET deletedAt = :at, updatedAt = :at, pendingUpload = 1, lastError = NULL
        WHERE name = :name
        """
    )
    suspend fun softDelete(name: String, at: Long)

    @Query(
        """
        UPDATE departments
        SET deletedAt = NULL, updatedAt = :at, pendingUpload = 1, lastError = NULL
        WHERE name = :name
        """
    )
    suspend fun restore(name: String, at: Long)

    /**
     * How many people are filed under a department.
     *
     * Asked before removing one: a department with staff in it is almost
     * certainly a misclick, and deleting it would leave those rows pointing at
     * a value no longer in the list.
     */
    @Query("SELECT COUNT(*) FROM employees WHERE deletedAt IS NULL AND department = :name")
    suspend fun staffCount(name: String): Int

    @Query("UPDATE departments SET pendingUpload = 1, lastError = NULL")
    suspend fun markAllPending(): Int
}

@Dao
interface PunchDao {

    /**
     * IGNORE, not REPLACE. logId is unique, so a duplicate insert is a
     * duplicate punch - dropping it silently is exactly right, and replacing
     * would reset uploadedAt and cause the row to be sent twice.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(punch: Punch): Long

    // No Kotlin default arguments on DAO methods - Room generates the
    // implementation and defaults interact badly with that. Callers pass
    // everything explicitly.
    @Query("SELECT * FROM punches WHERE uploadedAt IS NULL ORDER BY punchedAt LIMIT :limit")
    suspend fun queued(limit: Int): List<Punch>

    @Query("UPDATE punches SET uploadedAt = :at WHERE id IN (:ids)")
    suspend fun markUploaded(ids: List<Long>, at: Long)

    @Query("UPDATE punches SET attempts = attempts + 1, lastError = :error WHERE id IN (:ids)")
    suspend fun markFailed(ids: List<Long>, error: String?)

    @Query("SELECT COUNT(*) FROM punches WHERE uploadedAt IS NULL")
    fun observeQueueDepth(): Flow<Int>

    @Query("SELECT COUNT(*) FROM punches WHERE uploadedAt IS NULL")
    suspend fun queueDepth(): Int

    /**
     * Most recent punch of one type for one person, used for debouncing.
     *
     * Scoped to the type on purpose: a finger resting on the sensor repeats the
     * same type and is still suppressed, but somebody who taps Clock In by
     * mistake can immediately correct it with Clock Out instead of being told
     * "already recorded" for the next minute.
     */
    @Query("SELECT MAX(punchedAt) FROM punches WHERE employeePin = :pin AND punchType = :type")
    suspend fun lastPunchAt(pin: String, type: Int): Long?

    @Query("SELECT * FROM punches ORDER BY punchedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<Punch>>

    /**
     * Records screen: a date window plus an optional delivery-state filter.
     *
     * [state] is 0 for all, 1 sent, 2 pending, 3 failed - matching
     * [DeliveryState] but expressed against the underlying columns, because the
     * status is derived and there is no column to filter on. A single date is
     * just a range of one day, so one query serves both filters.
     */
    @Query(
        """
        SELECT * FROM punches
        WHERE punchedAt BETWEEN :from AND :to
          AND (:state = 0
               OR (:state = 1 AND uploadedAt IS NOT NULL)
               OR (:state = 2 AND uploadedAt IS NULL AND lastError IS NULL)
               OR (:state = 3 AND uploadedAt IS NULL AND lastError IS NOT NULL))
        ORDER BY punchedAt DESC
        LIMIT :limit
        """
    )
    fun observeFiltered(from: Long, to: Long, state: Int, limit: Int): Flow<List<Punch>>

    /**
     * Put failed rows back in the queue.
     *
     * Only clears the error and the attempt count - it never touches
     * uploadedAt, so this cannot resurrect an already-delivered punch and
     * double-count attendance.
     */
    @Query("UPDATE punches SET lastError = NULL, attempts = 0 WHERE id IN (:ids) AND uploadedAt IS NULL")
    suspend fun requeue(ids: List<Long>): Int

    @Query("UPDATE punches SET lastError = NULL, attempts = 0 WHERE uploadedAt IS NULL AND lastError IS NOT NULL")
    suspend fun requeueAllFailed(): Int

    /**
     * Housekeeping. Only ever removes rows the server has confirmed, so this
     * cannot lose attendance data.
     */
    @Query("DELETE FROM punches WHERE uploadedAt IS NOT NULL AND uploadedAt < :before")
    suspend fun purgeUploadedBefore(before: Long): Int
}
