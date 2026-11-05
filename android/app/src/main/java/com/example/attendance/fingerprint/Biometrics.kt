package com.example.attendance.fingerprint

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.attendance.data.AppDatabase

/**
 * Process-wide owner of the single [FingerprintEngine].
 *
 * There is one physical sensor, so there is one engine. Two activities each
 * opening their own would fight over the device and fail in ways that look
 * like hardware faults.
 */
object Biometrics {

    val engine = FingerprintEngine()

    @Volatile
    private var templatesLoaded = false

    @Volatile
    var lastError: String? = null
        private set

    /**
     * Opens the sensor and replays every stored template into the matcher.
     *
     * The replay is the part that is easy to forget and impossible to notice:
     * the SDK's template store lives in process memory only (verified - the
     * vendor demo's data directory contains no database), so without this the
     * app runs perfectly, matches nobody, and reports no error at all.
     *
     * Safe to call repeatedly.
     */
    suspend fun ensureReady(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!engine.isOpen) engine.open(context)
        } catch (e: FingerprintEngine.FingerprintUnavailable) {
            lastError = e.message
            Log.e(TAG, "sensor unavailable", e)
            return@withContext false
        }

        if (!templatesLoaded) {
            // allForMatcher, not all: a tombstoned person keeps their rows until
            // the server confirms the deletion, and loading those would let
            // somebody deleted this morning keep clocking in until the next sync.
            val fingers = AppDatabase.get(context).fingerprints().allForMatcher()
            var ok = 0
            for (f in fingers) {
                if (engine.register(f.employeePin, f.fingerIndex, f.template)) ok++
                else Log.w(TAG, "finger ${f.fingerIndex} for ${f.employeePin} rejected by the matcher")
            }
            templatesLoaded = true
            Log.i(TAG, "loaded $ok/${fingers.size} templates; matcher holds ${engine.registeredCount()}")

            if (ok < fingers.size) {
                lastError = "only $ok of ${fingers.size} fingerprints could be loaded"
            }
        }

        lastError = null
        true
    }

    /** Call after enrolling or deleting someone so the matcher is rebuilt. */
    suspend fun reloadTemplates(context: Context) = withContext(Dispatchers.IO) {
        templatesLoaded = false
        engine.clearAll()
        ensureReady(context)
    }

    fun shutdown() {
        engine.close()
        templatesLoaded = false
    }

    private const val TAG = "Biometrics"
}
