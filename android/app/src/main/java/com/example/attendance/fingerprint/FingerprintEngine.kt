package com.example.attendance.fingerprint

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.zkteco.android.biometric.core.device.TransportType
import com.zkteco.android.biometric.core.utils.LogHelper
import com.zkteco.android.biometric.module.fingerprintreader.ZKFingerService
import com.zkteco.android.biometric.nidfpsensor.NIDFPFactory
import com.zkteco.android.biometric.nidfpsensor.NIDFPSensor
import com.zkteco.android.biometric.nidfpsensor.exception.NIDFPException

/**
 * The only class that touches the ZKTeco SDK.
 *
 * Every constant and call order here was read off the device's own demo app
 * (`com.zkteco.android.zkfinger10sample`) by disassembling its dex - see
 * ../../../../../../SDK-CONTRACT.md for the provenance of each one. Nothing in
 * this file is inferred from ZKTeco's public documentation or from the GitHub
 * samples, which target a different (USB/OTG) product and would not work here.
 */
class FingerprintEngine {

    /** Result of one capture attempt. */
    sealed interface Capture {
        /** No finger on the sensor, or the image was unusable. Normal; keep polling. */
        data object None : Capture
        data class Ok(val template: ByteArray) : Capture
        data class Failed(val code: Int) : Capture
    }

    /** [employeePin] is always the bare payroll number, never the composite id. */
    data class Match(val employeePin: String, val fingerIndex: Int, val score: Int)

    private var sensor: NIDFPSensor? = null
    private var imageBuf = ByteArray(0)
    private var width = 0
    private var height = 0

    /** Copy of the last frame that yielded a template. See [lastImageBitmap]. */
    @Volatile
    private var lastImage: ByteArray? = null

    @Volatile
    var isOpen: Boolean = false
        private set

    /**
     * Bring up the sensor and the matching engine.
     *
     * Four steps, and the first two are the ones no documentation mentions:
     *  1. power the module on over GPIO - it is physically off at boot
     *  2. wait for it to enumerate, then get USB permission from the user
     *  3. open the sensor
     *  4. init the matching engine, which must happen *after* step 3
     *
     * @throws FingerprintUnavailable if the hardware or algorithm will not start.
     */
    suspend fun open(context: Context) {
        if (isOpen) return

        // 1 + 2. Without these, createNIDFPSensor succeeds and open() throws
        // "Open NIDFP sensor failed", because there is no device on the bus.
        withContext(Dispatchers.IO) { SensorPower.on() }
        delay(SensorPower.ENUMERATION_DELAY_MS)

        // Each failure gets its own message. They used to share one, which cost
        // a debugging session: "permission not granted" was displayed when the
        // module had in fact never appeared on the bus at all.
        val device = when (val r = UsbPermission.ensure(context, VID, PID_RANGE)) {
            is UsbPermission.Outcome.Ok -> r.device
            is UsbPermission.Outcome.Denied ->
                throw FingerprintUnavailable("USB permission was denied")
            is UsbPermission.Outcome.NoUsbManager ->
                throw FingerprintUnavailable("no UsbManager on this device")
            is UsbPermission.Outcome.NotFound -> throw FingerprintUnavailable(
                // The bus contents go in the *message* because this terminal is
                // meant to run with no laptop attached: if adb is not available
                // this line on screen is the only diagnostic anyone gets.
                "fingerprint module not on the USB bus. Seen: " +
                    r.seen.joinToString().ifBlank { "nothing" }
            )
        }

        LogHelper.setLevel(LOG_LEVEL)
        LogHelper.setNDKLogLevel(LOG_LEVEL)

        // HashMap<String, Any>, not <String, Int>. The vendor jar declares the
        // parameter as a raw Map, which Kotlin sees as Map<String!, Any!> - a
        // Map<String, Int> is rejected at compile time.
        //
        // The pid is whatever we just found, not a constant: the demo stores
        // the discovered pid and passes that (`getstatic PID`).
        val params = HashMap<String, Any>().apply {
            put(PARAM_KEY_VID, VID)
            put(PARAM_KEY_PID, device.productId)
        }

        val s = try {
            NIDFPFactory.createNIDFPSensor(context, TransportType.USBSCSI, params)
        } catch (t: Throwable) {
            throw FingerprintUnavailable("could not create the sensor", t)
        } ?: throw FingerprintUnavailable("createNIDFPSensor returned null")

        try {
            // false, not true. The demo passes iconst_0 here; we had this
            // inverted, which asks the module for an ID-fingerprint mode this
            // hardware does not advertise.
            s.setIDFPSupport(false)
            s.open(DEVICE_INDEX)
        } catch (t: Throwable) {
            throw FingerprintUnavailable("sensor.open failed", t)
        }

        val init = ZKFingerService.init()
        if (init != SUCCESS) {
            runCatching { s.close(DEVICE_INDEX) }
            throw FingerprintUnavailable("ZKFingerService.init() returned $init")
        }

        // The demo calls this immediately after a successful init and before
        // any capture. The middle argument is 5 - read off the bytecode
        // (iconst_0, iconst_5, iconst_0), not guessed.
        runCatching {
            s.setParameter(PARAM_DEVICE_INDEX, PARAM_CODE_CAPTURE_MODE, CAPTURE_MODE_DEFAULT)
        }.onFailure { Log.w(TAG, "setParameter capture-mode failed", it) }

        width = s.fpImgWidth
        height = s.fpImgHeight
        if (width <= 0 || height <= 0) {
            runCatching { s.close(DEVICE_INDEX) }
            runCatching { ZKFingerService.free() }
            throw FingerprintUnavailable("sensor reported a ${width}x$height image")
        }

        imageBuf = ByteArray(width * height)
        sensor = s
        isOpen = true

        Log.i(TAG, "sensor open: ${width}x$height fw=${runCatching { s.firmwareVersion }.getOrNull()} " +
                "licenseType=${runCatching { ZKFingerService.getLicenseType() }.getOrNull()} " +
                "fpLimit=${runCatching { ZKFingerService.getFPLimitCount() }.getOrNull()}")
    }

    fun close() {
        val s = sensor ?: return
        isOpen = false
        runCatching { ZKFingerService.free() }
        runCatching { s.close(DEVICE_INDEX) }
        runCatching { s.destroy() }
        sensor = null
        imageBuf = ByteArray(0)
        // Drop the module's power too, mirroring the demo's CloseDevice.
        SensorPower.off()
    }

    /**
     * One poll of the sensor. Cheap, and returns [Capture.None] most of the
     * time - callers are expected to invoke this in a loop.
     */
    fun capture(): Capture {
        val s = sensor ?: return Capture.Failed(ERR_NOT_OPEN)
        val template = ByteArray(TEMPLATE_SIZE)
        val size = intArrayOf(TEMPLATE_SIZE)

        return try {
            s.GetFPRawData(DEVICE_INDEX, imageBuf)
            // extract() returns the TEMPLATE LENGTH, so >0 is success and <=0 is
            // "nothing usable on the sensor right now" - the overwhelmingly
            // common case, which must not be logged or surfaced as an error.
            //
            // This was inverted (0 read as success, >0 as a failure code), which
            // meant every successful scan was thrown away as an error: the
            // sensor worked, the finger extracted fine, and the terminal sat
            // there doing nothing. The demo's WorkThread settles it - `ifgt`
            // straight to the success path.
            val ret = ZKFingerService.extract(imageBuf, width, height, template, size)
            if (ret > 0) {
                // Hand back the whole 2048-byte buffer, NOT a copy trimmed to
                // the extracted length. The demo allocates new byte[2048] and
                // passes that same full buffer to identify/verify/merge without
                // ever truncating it; trimming made verify() reject two samples
                // of the same finger as "different finger".
                Log.i(TAG, "extract ok: ret=$ret size[0]=${size[0]}")
                // Snapshot the frame that produced this template so the
                // enrolment screen can show the operator what was actually
                // read. Only on success, so the copy is rare.
                lastImage = imageBuf.copyOf()
                Capture.Ok(template)
            } else {
                Capture.None
            }
        } catch (e: NIDFPException) {
            // GetFPRawData throws rather than returning a code when there is
            // no finger on the platen, so this fires on almost every poll of an
            // idle terminal - about once a second, forever. Logging it produces
            // a 12-line stack trace per poll and buries real faults; treating it
            // as an error also triggers the caller's back-off for no reason.
            Capture.None
        } catch (t: Throwable) {
            Log.w(TAG, "capture threw", t)
            Capture.Failed(ERR_EXCEPTION)
        }
    }

    /**
     * 1:N search against the templates currently loaded into the engine.
     *
     * The SDK writes `"<id>\t<score>"` into the output buffer - not a bare id.
     * Parsing this wrongly yields employee pins with a score glued on the end,
     * which then fail to match any row in the database.
     */
    fun identify(template: ByteArray, threshold: Int = DEFAULT_THRESHOLD): Match? {
        val out = ByteArray(IDENTIFY_BUF_SIZE)
        val ret = ZKFingerService.identify(template, out, threshold, 1)
        if (ret <= 0) return null

        val raw = String(out).trim { it <= ' ' || it == ' ' }
        val parts = raw.split('\t')
        val id = parts.getOrNull(0)?.trim().orEmpty()
        if (id.isEmpty()) return null
        val score = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0

        // The engine reports whichever finger matched, as "<pin>#<index>".
        // Everything downstream - the punch, the debounce, the Snowflake MERGE
        // key - must see one person, so the suffix is stripped here rather than
        // at each call site. An id written before multi-finger enrolment has no
        // suffix and still parses correctly.
        val pin = id.substringBefore(ID_SEPARATOR)
        val fingerIndex = id.substringAfter(ID_SEPARATOR, "").toIntOrNull() ?: 1
        return Match(pin, fingerIndex, score)
    }

    /** The id the matching engine knows a given finger by. */
    fun sdkId(employeePin: String, fingerIndex: Int): String =
        "$employeePin$ID_SEPARATOR$fingerIndex"

    /**
     * The last successfully captured frame, as a bitmap for display.
     *
     * The sensor delivers 8-bit greyscale, one byte per pixel, so this expands
     * it to ARGB. Built here rather than with the SDK's `raw2TransparentPng`,
     * whose six int arguments are not documented anywhere we can verify.
     */
    fun lastImageBitmap(): Bitmap? {
        val raw = lastImage ?: return null
        val w = width
        val h = height
        if (w <= 0 || h <= 0 || raw.size < w * h) return null

        val pixels = IntArray(w * h)
        for (i in pixels.indices) {
            val v = raw[i].toInt() and 0xFF
            pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    /** 1:1 compare of two templates. Used during enrolment. */
    fun verify(a: ByteArray, b: ByteArray): Boolean = ZKFingerService.verify(a, b) > 0

    /**
     * Combine three samples of the same finger into one registered template.
     * Returns null if the samples were too inconsistent to merge.
     */
    fun merge(s1: ByteArray, s2: ByteArray, s3: ByteArray): ByteArray? {
        val reg = ByteArray(TEMPLATE_SIZE)
        // Same convention as extract(): the return is a length, so >0 is
        // success. Testing `!= 0` here rejected every successful merge.
        val ret = ZKFingerService.merge(s1, s2, s3, reg)
        if (ret <= 0) {
            Log.w(TAG, "merge failed: $ret")
            return null
        }
        // Untruncated, for the same reason as capture(): the demo passes the
        // full merge buffer straight to save().
        Log.i(TAG, "merge ok: ret=$ret len=${runCatching { ZKFingerService.getTemplateLength(reg) }.getOrNull()}")
        return reg
    }

    /**
     * Load one template into the in-memory matcher.
     *
     * This is why [com.example.attendance.data.EmployeeDao] exists. The
     * SDK's own store does NOT survive process death - the demo app's private
     * data directory holds no database at all - so every template has to be
     * replayed into the engine at startup. Skip this and the app matches
     * nobody after the first reboot, without any error.
     */
    fun register(employeePin: String, fingerIndex: Int, template: ByteArray): Boolean {
        // The demo discards save()'s return value, so its success convention is
        // the one thing the bytecode does not tell us - and the rest of this API
        // is inconsistent (init: 0 == ok; extract/merge/identify/verify: >0 ==
        // ok). Accept anything non-negative and log the value, because
        // [registeredCount] is the honest check: it reports what the matcher
        // actually holds regardless of what save() claims.
        val id = sdkId(employeePin, fingerIndex)
        val ret = ZKFingerService.save(template, id)
        if (ret != SUCCESS) Log.i(TAG, "save($id) returned $ret")
        return ret >= SUCCESS
    }

    fun unregister(employeePin: String, fingerIndex: Int) {
        ZKFingerService.del(sdkId(employeePin, fingerIndex))
    }

    /** How many templates the matcher currently holds. Useful as a health check. */
    fun registeredCount(): Int = runCatching { ZKFingerService.count() }.getOrDefault(-1)

    fun clearAll() {
        runCatching { ZKFingerService.clear() }
    }

    class FingerprintUnavailable(message: String, cause: Throwable? = null) :
        Exception(message, cause)

    companion object {
        private const val TAG = "FingerprintEngine"

        // --- verified against the device, see SDK-CONTRACT.md ---------------
        /** ZKTeco vendor id, 0x1B55. */
        private const val VID = 6997

        /**
         * The integrated sensor enumerates somewhere in 0x0300-0x03FF, and the
         * exact value varies between units - so this is a range and the pid we
         * actually use is whatever we discover inside it.
         *
         * 770 (0x0302) was the value on the unit whose demo APK we dexdumped,
         * and hard-coding it was a real bug: on a unit that enumerates as any
         * other pid the lookup silently found nothing, no permission dialog was
         * ever shown, and the app reported a *permission* failure for what was
         * really a discovery failure.
         *
         * Public ZKFinger samples use 289 with [TransportType.USB]; those are
         * for external OTG scanners. Using them here produces an app that
         * builds and installs and then never sees a finger.
         */
        private val PID_RANGE = 768..1023

        private const val PARAM_KEY_VID = "param.key.vid"
        private const val PARAM_KEY_PID = "param.key.pid"

        private const val DEVICE_INDEX = 0
        private const val SUCCESS = 0

        /**
         * Separates payroll number from finger index in the matcher's id.
         * Safe because payroll numbers are digits; if that ever stops being
         * true, [identify] would return a truncated pin rather than fail loudly.
         */
        private const val ID_SEPARATOR = '#'

        /** Fingers enrolled per person, so a cut or plaster is not a blocker. */
        const val FINGERS_PER_EMPLOYEE = 4

        /** Samples merged into one registered template. The SDK requires 3. */
        const val SAMPLES_PER_FINGER = 3

        /**
         * `setParameter(deviceIndex, code, value)` - the demo calls
         * `setParameter(0, 5, 0)` post-init, and 5 is
         * `NIDFPSensor.PARAM_CODE_CAPTURE_MODE`, so this is "capture mode 0".
         */
        private const val PARAM_DEVICE_INDEX = 0
        private const val PARAM_CODE_CAPTURE_MODE = 5
        private const val CAPTURE_MODE_DEFAULT = 0

        /** Buffer sizes the demo uses. */
        private const val TEMPLATE_SIZE = 2048
        private const val IDENTIFY_BUF_SIZE = 256

        /** The demo's matching threshold. Higher = stricter. */
        const val DEFAULT_THRESHOLD = 70

        /** Vendor log verbosity. 7 is what the demo sets. */
        private const val LOG_LEVEL = 7

        private const val ERR_NOT_OPEN = -1
        private const val ERR_EXCEPTION = -2

        /**
         * Sensor poll interval. The demo polls in a tight loop with a short
         * sleep; the exact vendor value is not recoverable from the bytecode,
         * so this is ours. 80 ms is responsive to a human finger press while
         * leaving the CPU mostly idle. Raise it if the device runs hot.
         */
        const val POLL_INTERVAL_MS = 80L
    }
}
