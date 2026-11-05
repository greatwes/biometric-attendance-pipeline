package com.example.attendance.sync

import android.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.example.attendance.Config
import com.example.attendance.data.Department
import com.example.attendance.data.Employee
import com.example.attendance.data.Fingerprint
import com.example.attendance.data.Punch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Speaks the ADMS push wire format, over HTTPS, to the existing n8n webhook.
 *
 * This is a deliberate choice rather than inventing a JSON API. n8n already
 * has a parser for this format with 28 passing tests, and it already produces
 * the Snowflake MERGE statements for EMPLOYEES, BIOMETRIC_TEMPLATES and
 * ATTENDANCE_LOGS. Emitting the same bytes means the entire server side stays
 * untouched and stays tested; a new JSON endpoint would mean re-testing all of
 * it to gain nothing.
 *
 * The one thing we do NOT reproduce is the handshake. The device had to be told
 * what to push; we already know.
 */
class AttendanceApi(private val config: Config) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    sealed interface Result {
        data object Ok : Result
        /** Worth trying again: timeout, DNS, 5xx. */
        data class Retryable(val reason: String) : Result
        /** Will never succeed as-is: 4xx, bad config. Do not spin on it. */
        data class Permanent(val reason: String) : Result
    }

    fun uploadPunches(punches: List<Punch>): Result {
        if (punches.isEmpty()) return Result.Ok
        // ATTLOG is positional, so the location goes in the work-code slot -
        // field 5, previously always empty. That keeps the row shape identical
        // to what a real ZKTeco terminal sends, so the existing parser and
        // MERGE keep working; the parser already carries this field through as
        // WORK_CODE and only needs renaming server-side if you want it labelled
        // as location in Snowflake.
        val body = punches.joinToString("\n") { p ->
            listOf(
                p.employeePin,
                TS.format(Date(p.punchedAt)),
                p.punchType.toString(),
                p.verifyMode.toString(),
                p.location
            ).joinToString("\t")
        }
        return post(TABLE_ATTLOG, body)
    }

    /**
     * Pushes the roster row for one person.
     *
     * Separate from the templates because they are separate tables server-side
     * and a partial failure should not leave a template with no employee row.
     * The parser does create a stub EMPLOYEES row from a BIODATA push, but
     * relying on that would mean staff appearing with no name. Callers send
     * this first - see [com.example.attendance.sync.SyncWorker].
     */
    fun uploadEmployee(employee: Employee): Result {
        // Dept/Gender/FirstName/LastName are extra KEY=value pairs. USERINFO is
        // key-value, not positional, so adding fields cannot break a parser that
        // ignores them. Name= is still sent for exactly that reason: anything
        // reading FULL_NAME keeps working, and the parser falls back to
        // splitting it when a stock terminal sends only that.
        val user = listOfNotNull(
            "PIN=${employee.pin}",
            "Name=${employee.displayName}",
            employee.firstName.takeIf { it.isNotBlank() }?.let { "FirstName=$it" },
            employee.lastName.takeIf { it.isNotBlank() }?.let { "LastName=$it" },
            employee.department.takeIf { it.isNotBlank() }?.let { "Dept=$it" },
            employee.gender.takeIf { it.isNotBlank() }?.let { "Gender=$it" },
            // Tri-state on the wire, matching the parser. Sent on every push
            // rather than only on deletion: "Deleted=0" is what restores
            // somebody, and omitting it entirely means "this push says nothing
            // about deletion", which must leave a tombstone set on another
            // device alone. Sending nothing here would make a restore
            // impossible; sending 0 unconditionally would make deletion
            // impossible. Both have to be explicit.
            if (employee.deletedAt != null) "Deleted=1" else "Deleted=0"
        ).joinToString("\t")

        return post(TABLE_USERINFO, user)
    }

    /**
     * Pushes one department.
     *
     * Same tri-state deletion rule as [uploadEmployee]. Departments are a
     * shared list rather than a per-device setting, so this is what stops two
     * terminals inventing "Field Ops" and "Field Operations" independently.
     */
    fun uploadDepartment(department: Department): Result {
        val row = listOf(
            "Name=${department.name}",
            if (department.deletedAt != null) "Deleted=1" else "Deleted=0"
        ).joinToString("\t")

        return post(TABLE_DEPARTMENT, row)
    }

    /**
     * Pushes one enrolled finger.
     *
     * `FID` is ADMS's finger id, which is exactly what a real ZKTeco terminal
     * sends when a person has several fingers registered - so multi-finger
     * enrolment needs no server-side change at all. It is 0-based on the wire,
     * while our [com.example.attendance.data.Fingerprint.fingerIndex] is
     * 1-based for display, hence the shift.
     */
    fun uploadFingerprint(fingerprint: Fingerprint): Result {
        val template = Base64.encodeToString(fingerprint.template, Base64.NO_WRAP)
        val bio = listOf(
            "PIN=${fingerprint.employeePin}",
            "FID=${fingerprint.fingerIndex - 1}",
            "Valid=1",
            "TMP=$template",
            "Size=${fingerprint.template.size}",
            "Type=$TEMPLATE_TYPE"
        ).joinToString("\t")

        return post(TABLE_BIODATA, bio)
    }

    /**
     * What the server knows that this device may not.
     *
     * Note the direction: the device asks n8n, and n8n answers from a Data
     * table that Dataiku filled. Nothing here reaches Snowflake, because
     * nothing in n8n can - see SYNC-DESIGN.md section 3.2. The roster is
     * therefore as fresh as the last Dataiku run, not live.
     */
    sealed interface RosterResult {
        data class Ok(val snapshot: RosterSnapshot) : RosterResult
        data class Retryable(val reason: String) : RosterResult
        data class Permanent(val reason: String) : RosterResult
    }

    fun fetchRoster(since: Long): RosterResult {
        val base = config.baseUrl
        if (base.isBlank()) return RosterResult.Permanent("no server URL configured")
        if (config.sharedSecret.isBlank()) return RosterResult.Permanent("no shared secret configured")

        val url = "$base$PATH_ROSTER".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("since", since.toString())
            ?.addQueryParameter("sn", config.deviceSn)
            ?.build()
            ?: return RosterResult.Permanent("malformed server URL: $base")

        val request = Request.Builder()
            .url(url)
            .header("X-ZK-Secret", config.sharedSecret)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> {
                        val text = response.body?.string().orEmpty()
                        runCatching { RosterSnapshot.parse(text) }
                            .fold(
                                onSuccess = { RosterResult.Ok(it) },
                                // Malformed JSON is permanent, not retryable:
                                // hammering the endpoint will not make it parse,
                                // and the watermark stays put so nothing is lost.
                                onFailure = {
                                    RosterResult.Permanent(
                                        "unparseable roster: " + (it.message ?: "no detail")
                                    )
                                }
                            )
                    }
                    response.code == 401 || response.code == 403 ->
                        RosterResult.Permanent("rejected (${response.code}) - shared secret wrong?")
                    response.code == 404 ->
                        RosterResult.Permanent("404 - is the roster workflow active?")
                    response.code in 500..599 ->
                        RosterResult.Retryable("server error ${response.code}")
                    else -> RosterResult.Permanent("HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            RosterResult.Retryable(e.javaClass.simpleName + ": " + (e.message ?: "no detail"))
        }
    }

    private fun post(table: String, body: String): Result {
        val base = config.baseUrl
        if (base.isBlank()) return Result.Permanent("no server URL configured")
        if (config.sharedSecret.isBlank()) return Result.Permanent("no shared secret configured")

        val url = "$base$PATH".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("SN", config.deviceSn)
            ?.addQueryParameter("table", table)
            ?.addQueryParameter("Stamp", "9999")
            ?.build()
            ?: return Result.Permanent("malformed server URL: $base")

        val request = Request.Builder()
            .url(url)
            // The n8n webhook is publicly reachable with no login. This header
            // is the only thing standing between the internet and the ability
            // to inject attendance records - the workflow must verify it.
            .header("X-ZK-Secret", config.sharedSecret)
            .header("User-Agent", USER_AGENT)
            .post(body.toRequestBody(TEXT_PLAIN))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> Result.Ok
                    response.code == 401 || response.code == 403 ->
                        Result.Permanent("rejected (${response.code}) - shared secret wrong?")
                    response.code == 404 ->
                        Result.Permanent("404 - is the n8n workflow active?")
                    response.code in 500..599 ->
                        Result.Retryable("server error ${response.code}")
                    else ->
                        Result.Permanent("HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            // Offline, DNS failure, TLS handshake, timeout. All transient by
            // assumption - the queue exists precisely for this.
            Result.Retryable(e.javaClass.simpleName + ": " + (e.message ?: "no detail"))
        }
    }

    companion object {
        private const val PATH = "/iclock/cdata"
        private const val PATH_ROSTER = "/zk/roster"
        private const val TABLE_ATTLOG = "ATTLOG"
        private const val TABLE_USERINFO = "USERINFO"
        private const val TABLE_BIODATA = "BIODATA"
        private const val TABLE_DEPARTMENT = "DEPARTMENT"

        /** ZKFinger10 template, base64-encoded. Recorded for the server's benefit. */
        private const val TEMPLATE_TYPE = "ZKFinger10"

        private const val USER_AGENT = "ZKAttendance-C6/1.0"
        private val TEXT_PLAIN = "text/plain; charset=utf-8".toMediaType()

        /** Exactly what the parser's normaliseTs() accepts. */
        private val TS = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    }
}
