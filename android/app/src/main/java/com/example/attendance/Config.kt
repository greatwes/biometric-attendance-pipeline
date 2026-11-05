package com.example.attendance

import android.content.Context
import android.os.Build

/**
 * Runtime configuration, editable from the admin screen and persisted.
 *
 * Deliberately not hard-coded: the endpoint and the shared secret change
 * between test and production, and a rebuild to move a device between them
 * would guarantee that somebody eventually ships a debug build to the field.
 */
class Config private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("zk_attendance", Context.MODE_PRIVATE)

    /**
     * Base URL that the punch is POSTed under. The app appends
     * `/iclock/cdata?...`, which is why this points at n8n's webhook prefix.
     *
     * Note what this buys us: because we control the full URL, we do NOT need
     * the reverse-proxy rewrite that the real ADMS device needed (the device
     * could only be given a host and a port, never a path). Phase 4 of the
     * original plan disappears.
     */
    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL)!!
        set(v) = prefs.edit().putString(KEY_BASE_URL, v.trimEnd('/')).apply()

    /**
     * Sent as the X-ZK-Secret header on every request.
     *
     * The n8n webhook is reachable from the public internet with no login, so
     * without this anyone who guesses the URL can inject attendance records or
     * post fingerprint templates. The n8n workflow MUST reject requests whose
     * header does not match; the header alone does nothing.
     */
    var sharedSecret: String
        // Trimmed on both read and write. A secret is pasted or typed on a
        // touchscreen keyboard that likes to add a trailing space, and the
        // stored value goes straight into an HTTP header - so " abc" and "abc"
        // are different secrets while looking identical in the settings field.
        // The resulting 403 says "shared secret wrong?", which sends you to
        // check a value that reads as correct.
        //
        // Trimming on read as well as write is deliberate: it repairs a value
        // that was already saved with whitespace, without anyone having to
        // retype it.
        get() = prefs.getString(KEY_SECRET, "")!!.trim()
        set(v) = prefs.edit().putString(KEY_SECRET, v.trim()).apply()

    /**
     * Identifies this terminal in ATTENDANCE_LOGS.DEVICE_SN. Defaults to the
     * hardware serial so two terminals never collide.
     */
    var deviceSn: String
        get() = prefs.getString(KEY_SN, null) ?: defaultSerial()
        set(v) = prefs.edit().putString(KEY_SN, v).apply()

    /**
     * Watermark for the roster pull: the highest version this device has
     * applied.
     *
     * A server-assigned value, never a local clock. It is Snowflake's
     * UPDATED_AT in epoch millis, and the device only ever echoes back what it
     * was given. Terminal clocks are set by whoever is holding the terminal, and
     * one running a day fast would otherwise skip every change made in between
     * and never notice.
     *
     * Advanced only after a batch has been applied successfully. A crash
     * halfway through has to re-fetch, not skip - every apply is idempotent, so
     * re-applying costs nothing and missing a row costs a person their
     * enrolment.
     */
    var rosterSince: Long
        get() = prefs.getLong(KEY_ROSTER_SINCE, 0L)
        set(v) = prefs.edit().putLong(KEY_ROSTER_SINCE, v).apply()

    /** Matching strictness. Raising it reduces false accepts and raises rejects. */
    var matchThreshold: Int
        get() = prefs.getInt(KEY_THRESHOLD, DEFAULT_THRESHOLD)
        set(v) = prefs.edit().putInt(KEY_THRESHOLD, v.coerceIn(30, 100)).apply()

    /**
     * How long the same person is ignored after a successful punch.
     *
     * Without this a finger resting on the sensor produces a punch every poll.
     * It is a debounce, not a business rule - the user asked for no late/absent
     * logic and this does not add any.
     */
    var debounceSeconds: Int
        get() = prefs.getInt(KEY_DEBOUNCE, DEFAULT_DEBOUNCE_SECONDS)
        set(v) = prefs.edit().putInt(KEY_DEBOUNCE, v.coerceIn(0, 3600)).apply()

    /** PIN that unlocks the admin/enrolment screen. */
    var adminPin: String
        get() = prefs.getString(KEY_ADMIN_PIN, DEFAULT_ADMIN_PIN)!!
        set(v) = prefs.edit().putString(KEY_ADMIN_PIN, v).apply()

    /**
     * The site this terminal records for. Stamped onto every punch as it is
     * taken and shown permanently on the home screen.
     *
     * Blank until an admin sets it, and deliberately not defaulted to anything
     * plausible: a wrong-but-confident location silently mis-attributes every
     * punch, whereas a blank one is visible on the home screen immediately.
     */
    var location: String
        get() = prefs.getString(KEY_LOCATION, "")!!
        set(v) = prefs.edit().putString(KEY_LOCATION, v.trim()).apply()

    /**
     * Locations an admin has defined on this device, newest last.
     *
     * Held as a set in prefs rather than a table because it is a short pick
     * list, not data - the authoritative list lives with whoever runs the sites.
     */
    var knownLocations: List<String>
        get() = prefs.getStringSet(KEY_KNOWN_LOCATIONS, emptySet())!!.sorted()
        set(v) = prefs.edit()
            .putStringSet(KEY_KNOWN_LOCATIONS, v.map { it.trim() }.filter { it.isNotEmpty() }.toSet())
            .apply()

    fun addLocation(name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        knownLocations = knownLocations + clean
    }

    fun removeLocation(name: String) {
        knownLocations = knownLocations.filterNot { it.equals(name.trim(), ignoreCase = true) }
    }

    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && sharedSecret.isNotBlank()

    @Suppress("DEPRECATION")
    private fun defaultSerial(): String =
        runCatching { Build.getSerial() }.getOrNull()
            ?.takeIf { it.isNotBlank() && it != Build.UNKNOWN }
            ?: Build.SERIAL.takeIf { it.isNotBlank() && it != Build.UNKNOWN }
            ?: "C6-UNKNOWN"

    companion object {
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_SECRET = "shared_secret"
        private const val KEY_SN = "device_sn"
        private const val KEY_THRESHOLD = "threshold"
        private const val KEY_DEBOUNCE = "debounce_s"
        private const val KEY_ADMIN_PIN = "admin_pin"
        private const val KEY_LOCATION = "location"
        private const val KEY_KNOWN_LOCATIONS = "known_locations"
        private const val KEY_ROSTER_SINCE = "roster_since"

        const val DEFAULT_BASE_URL = "https://automations.example.org/webhook"
        private const val DEFAULT_THRESHOLD = 70
        private const val DEFAULT_DEBOUNCE_SECONDS = 60

        /** Changed on first run; the admin screen nags until it is. */
        const val DEFAULT_ADMIN_PIN = "0000"

        @Volatile
        private var instance: Config? = null

        fun get(context: Context): Config =
            instance ?: synchronized(this) {
                instance ?: Config(context).also { instance = it }
            }
    }
}
