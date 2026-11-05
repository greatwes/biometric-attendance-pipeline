package com.example.attendance.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * How far a locally-owned row has got towards the server.
 *
 * Derived from the columns rather than stored, for the same reason
 * [Punch.state] is: a status column and the columns it summarises can disagree
 * after a crash mid-upload, and then the screen confidently shows the wrong
 * thing. This is the roster equivalent of [DeliveryState]; kept separate
 * because "sent" and "synced" mean different things to an admin looking at a
 * person versus a punch.
 */
enum class SyncState { SYNCED, PENDING, FAILED }

/**
 * An enrolled member of staff.
 *
 * This table is the reason the app works after a reboot. The ZKTeco matching
 * engine keeps templates in memory only - its own store is empty on disk (see
 * android/SDK-CONTRACT.md) - so we hold the authoritative copy and replay every
 * row into the engine at startup.
 */
@Entity(
    tableName = "employees",
    indices = [Index(value = ["pendingUpload"]), Index(value = ["lastName"])]
)
data class Employee(
    /**
     * The staff member's SF ID, and simultaneously the ADMS PIN.
     *
     * Deliberately one field and not two. The ZK matcher keys on this, the
     * ATTLOG protocol carries it, and Snowflake calls it DEVICE_PIN - inventing
     * a second identifier alongside it would mean a join for every query and a
     * reconciliation job for when the two disagree. The column keeps the
     * protocol name; every label a human reads says "SF ID".
     *
     * Unique per person by construction here, because it is the primary key.
     * Fleet-wide uniqueness needs the roster pull - see SYNC-DESIGN.md.
     */
    @PrimaryKey val pin: String,

    // Split rather than one `name`, because the punch messages greet people by
    // first name and there is no reliable way to recover that from a single
    // field: "Ada Grace Obi" and "Ada Obi" both start with the given name, but
    // "Mwangi Asha" - written surname-first, as it often is here - does not.
    val firstName: String,
    val lastName: String,

    // defaultValue is declared so the ALTER TABLE in the migrations matches what
    // Room expects; Room validates defaults, not just types.
    /** Chosen from the admin-managed department list, not free text. */
    @ColumnInfo(defaultValue = "''") val department: String = "",
    /** Male or Female, chosen from a fixed list; self-reported. */
    @ColumnInfo(defaultValue = "''") val gender: String = "",

    val enrolledAt: Long,
    /** Bumped on every local edit; the newest write wins when devices sync. */
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,

    /**
     * Local tombstone. Null means the person is current.
     *
     * A deletion has to be a write, not an absence. With several terminals,
     * "row missing here but present there" cannot be told apart from "not
     * pulled yet", so a device that hard-deleted somebody would have them
     * restored on its next pull and deletion would silently stop working. The
     * row therefore stays, marked, until the server has acknowledged it.
     *
     * Mirrors EMPLOYEES.DELETED_AT in Snowflake - see sql/01_snowflake_schema.sql.
     */
    val deletedAt: Long? = null,

    /** False once the roster row has reached the server. */
    val pendingUpload: Boolean = true,

    /**
     * Why the last push attempt failed permanently, or null.
     *
     * Needed for the failed state on the staff list. Without it a row rejected
     * by the server is indistinguishable from one that has simply not been
     * tried yet, and an admin has no way to know anything is wrong.
     */
    val lastError: String? = null
) {
    /** What the terminal greets them with. */
    val displayName: String get() = "$firstName $lastName".trim().ifBlank { pin }

    val syncState: SyncState
        get() = when {
            !pendingUpload -> SyncState.SYNCED
            lastError != null -> SyncState.FAILED
            else -> SyncState.PENDING
        }
}

/**
 * A department, created by an admin.
 *
 * A table rather than a preference - unlike Location, which is genuinely
 * per-device, this list has to be identical everywhere or the same team ends up
 * in Snowflake as both "Field Ops" and "Field Operations" and no dashboard can
 * group them. Rows are pushed to the server and, once the roster pull exists,
 * pulled back down so every terminal converges on one list.
 */
@Entity(tableName = "departments")
data class Department(
    @PrimaryKey val name: String,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
    /** Tombstone, for the same reason as [Employee.deletedAt]. */
    val deletedAt: Long? = null,
    val pendingUpload: Boolean = true,
    val lastError: String? = null
) {
    val syncState: SyncState
        get() = when {
            !pendingUpload -> SyncState.SYNCED
            lastError != null -> SyncState.FAILED
            else -> SyncState.PENDING
        }
}

/**
 * Which finger a template belongs to.
 *
 * The index is what reaches the SDK, the device database and Snowflake's
 * FINGER_INDEX, so these numbers are permanent: renumbering them would silently
 * re-label every template already enrolled. Four specific fingers rather than
 * "any four" so that a re-enrolment captures the same ones, and so an operator
 * can be told which finger to ask for instead of guessing.
 */
enum class FingerSlot(val index: Int) {
    RIGHT_INDEX(1),
    RIGHT_THUMB(2),
    LEFT_INDEX(3),
    LEFT_THUMB(4);

    companion object {
        fun fromIndex(index: Int): FingerSlot? = entries.firstOrNull { it.index == index }
    }
}

/**
 * One enrolled finger. A member of staff has several, so that a cut, a plaster
 * or a wet hand does not stop them clocking in.
 *
 * Templates live here rather than on [Employee] because the matching engine is
 * keyed by a single opaque id, so each finger has to be its own entry. The id
 * handed to the SDK is `<pin>#<fingerIndex>` - see
 * [com.example.attendance.fingerprint.FingerprintEngine.sdkId] - and is
 * split back to the SF ID on the way out, so attendance still records one
 * person no matter which finger they used.
 */
@Entity(
    tableName = "fingerprints",
    primaryKeys = ["employeePin", "fingerIndex"],
    indices = [Index(value = ["employeePin"]), Index(value = ["pendingUpload"])],
    // A real foreign key here, unlike on punches: a template has no meaning
    // without the person, and the two are created and destroyed together.
    foreignKeys = [
        ForeignKey(
            entity = Employee::class,
            parentColumns = ["pin"],
            childColumns = ["employeePin"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class Fingerprint(
    val employeePin: String,
    /** 1-based; see [FingerSlot] for what each index means. */
    val fingerIndex: Int,
    /** Registered template produced by merging three enrolment samples. */
    val template: ByteArray,
    val enrolledAt: Long,
    /** False once the template has reached the server. */
    val pendingUpload: Boolean = true,
    /** Why the last push failed permanently, or null. See [Employee.lastError]. */
    val lastError: String? = null
) {
    val slot: FingerSlot? get() = FingerSlot.fromIndex(fingerIndex)

    val syncState: SyncState
        get() = when {
            !pendingUpload -> SyncState.SYNCED
            lastError != null -> SyncState.FAILED
            else -> SyncState.PENDING
        }

    // ByteArray needs these; the generated equals/hashCode would compare by
    // reference and quietly break any set or distinct-by operation.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Fingerprint) return false
        return employeePin == other.employeePin &&
            fingerIndex == other.fingerIndex &&
            template.contentEquals(other.template) &&
            enrolledAt == other.enrolledAt &&
            pendingUpload == other.pendingUpload &&
            lastError == other.lastError
    }

    override fun hashCode(): Int {
        var result = employeePin.hashCode()
        result = 31 * result + fingerIndex
        result = 31 * result + template.contentHashCode()
        result = 31 * result + enrolledAt.hashCode()
        result = 31 * result + pendingUpload.hashCode()
        result = 31 * result + (lastError?.hashCode() ?: 0)
        return result
    }
}

/**
 * What a punch means. The numbers are ADMS attendance-state codes, which is
 * what a real ZKTeco terminal puts in field 3 of an ATTLOG row, so they reach
 * `ATTENDANCE_LOGS.PUNCH_TYPE` unchanged and match any other ZK device.
 *
 * Note 2 and 3 are not in label order: ADMS defines 2 as Break-Out (leaving
 * temporarily) and 3 as Break-In (coming back). Renumbering them to read
 * nicely would silently disagree with every other ZKTeco device. The names
 * staff see - "Stepping Out" and "Back on Seat" - are labels over these codes,
 * held in strings.xml and in Snowflake's PUNCH_TYPES, never in the numbers.
 */
enum class PunchType(val code: Int) {
    CLOCK_IN(0),
    CLOCK_OUT(1),
    /** Temporary exit. ADMS Break-Out. Shown as "Stepping Out". */
    CHECK_OUT(2),
    /** Temporary entry. ADMS Break-In. Shown as "Back on Seat". */
    CHECK_IN(3);

    companion object {
        fun fromCode(code: Int): PunchType? = entries.firstOrNull { it.code == code }
    }
}

/**
 * One clock-in event.
 *
 * Rows are never deleted on failure and never updated in place except to mark
 * them sent. The queue is the whole point: the terminal is on a phone hotspot
 * in a field office, and "the network was down for an hour" must not mean
 * "nobody clocked in for an hour".
 *
 * No foreign key to [Employee], deliberately, even though Snowflake declares
 * one. Attendance has to outlive the roster row: somebody leaving must not
 * erase the month they worked, and a punch may still be queued when their
 * record is finally purged. An enforced constraint here would turn that into a
 * crash on insert or a cascading delete of real attendance.
 */
@Entity(
    tableName = "punches",
    indices = [
        Index(value = ["logId"], unique = true),
        Index(value = ["uploadedAt"]),
        Index(value = ["employeePin"])
    ]
)
data class Punch(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /**
     * Idempotency key, identical in construction to the one the n8n parser
     * builds for a real device: "<deviceSn>|<pin>|<timestamp>". Snowflake does
     * not enforce uniqueness, so the MERGE relies on this being stable and
     * deterministic - a retry must produce the same key, not a new one.
     */
    val logId: String,

    val employeePin: String,
    /** Epoch millis, device local clock. */
    val punchedAt: Long,

    /**
     * Which site recorded this punch, captured at punch time rather than read
     * from settings at upload time. If an admin moves the device or renames the
     * location, punches already taken must keep the site they were actually
     * taken at.
     */
    @ColumnInfo(defaultValue = "''") val location: String = "",

    /** ADMS punch type. 0 = clock in; we do not infer direction on the device. */
    val punchType: Int = 0,
    /** ADMS verify mode. 1 = fingerprint. */
    val verifyMode: Int = 1,
    val matchScore: Int = 0,

    /** Null until the server has acknowledged it. */
    val uploadedAt: Long? = null,
    val attempts: Int = 0,
    val lastError: String? = null
) {
    /**
     * Delivery state, derived rather than stored - the three fields above
     * already say everything, and a duplicated status column is one more thing
     * that can disagree with reality after a crash mid-upload.
     */
    val state: DeliveryState
        get() = when {
            uploadedAt != null -> DeliveryState.SENT
            lastError != null -> DeliveryState.FAILED
            else -> DeliveryState.PENDING
        }
}

enum class DeliveryState { SENT, PENDING, FAILED }
