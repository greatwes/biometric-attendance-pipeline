package com.example.attendance.fingerprint

import android.util.Log
import cn.pda.serialport.SerialPort

/**
 * The fingerprint module is physically powered off until a GPIO is pulled low.
 *
 * This is the step that is invisible from the API surface and impossible to
 * infer: with the module unpowered, nothing appears on the USB bus at all
 * (`/sys/bus/usb/devices` shows only the root hub), and
 * `NIDFPSensor.open()` fails with the unhelpful "Open NIDFP sensor failed".
 *
 * Both the GPIO number and the settle delay are taken from the vendor demo's
 * `OpenDeviceAndRequestDevice`, disassembled - see ../../../../../../SDK-CONTRACT.md.
 */
object SensorPower {

    /** GPIO line feeding the fingerprint module. Active LOW. */
    private const val GPIO_FINGERPRINT = 141

    /**
     * How long to wait after powering up before the module has enumerated on
     * USB. The demo sleeps exactly this long; shortening it means the sensor is
     * sometimes not there yet and open() fails intermittently, which is far
     * worse than failing every time.
     */
    const val ENUMERATION_DELAY_MS = 3200L

    @Volatile
    private var port: SerialPort? = null

    val isPowered: Boolean get() = port != null

    /** Blocking - call from a background dispatcher. */
    fun on() {
        if (port != null) return
        val p = SerialPort()
        p.setGPIOlow(GPIO_FINGERPRINT)
        port = p
        Log.i(TAG, "fingerprint module powered on (GPIO $GPIO_FINGERPRINT low)")
    }

    fun off() {
        val p = port ?: return
        runCatching { p.setGPIOhigh(GPIO_FINGERPRINT) }
            .onFailure { Log.w(TAG, "failed to power the module down", it) }
        port = null
    }

    private const val TAG = "SensorPower"
}
