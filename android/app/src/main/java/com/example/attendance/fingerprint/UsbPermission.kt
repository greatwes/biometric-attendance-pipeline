package com.example.attendance.fingerprint

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Obtains USB permission for the fingerprint module.
 *
 * Android will not let an ordinary app claim a USB device without the user
 * granting permission. The vendor demo does this at runtime with
 * `UsbManager.requestPermission` - note it declares no `<uses-permission>` and
 * no device filter in its manifest, so copying the manifest alone gets you
 * nothing.
 *
 * The module must already be powered on ([SensorPower.on]) or it will not be
 * in the device list to ask about.
 */
object UsbPermission {

    /** Why [ensure] could not hand back a usable device. */
    sealed interface Outcome {
        data class Ok(val device: UsbDevice) : Outcome
        /** Nothing on the bus matched. [seen] is every device Android could see. */
        data class NotFound(val seen: List<String>) : Outcome
        data object Denied : Outcome
        data object NoUsbManager : Outcome
    }

    /**
     * Find the fingerprint module and make sure we may talk to it.
     *
     * [productIds] is a *range*, not a single value, because that is what the
     * vendor demo does: it accepts any pid in 0x0300-0x03FF and then uses
     * whichever one it actually found (`getProductId` -> `putstatic PID`).
     * Pinning this to the one pid that happened to be observed on the
     * dexdumped unit makes the app fail on any other unit with no dialog and
     * no obvious reason - see SDK-CONTRACT.md.
     */
    suspend fun ensure(context: Context, vendorId: Int, productIds: IntRange): Outcome {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: return Outcome.NoUsbManager.also { Log.e(TAG, "no UsbManager") }

        val device = findDevice(manager, vendorId, productIds)
        if (device == null) {
            val seen = manager.deviceList.values.map { "${it.vendorId}/${it.productId}" }
            Log.e(
                TAG,
                "no USB device with vid=$vendorId pid in $productIds. " +
                    "Is the module powered on? Devices seen: " +
                    seen.joinToString().ifBlank { "none" }
            )
            return Outcome.NotFound(seen)
        }

        if (manager.hasPermission(device)) return Outcome.Ok(device)

        return suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action != ACTION_USB_PERMISSION) return
                    runCatching { context.unregisterReceiver(this) }
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    Log.i(TAG, "USB permission ${if (granted) "granted" else "denied"}")
                    if (cont.isActive) {
                        cont.resume(if (granted) Outcome.Ok(device) else Outcome.Denied)
                    }
                }
            }

            val filter = IntentFilter(ACTION_USB_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }

            cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }

            // MUTABLE, not IMMUTABLE: the system writes the device and the
            // grant result into this intent's extras. An immutable one is
            // delivered with no extras and every request reads as denied.
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
            val pending = PendingIntent.getBroadcast(
                context,
                0,
                Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
                flags
            )
            manager.requestPermission(device, pending)
        }
    }

    private fun findDevice(manager: UsbManager, vid: Int, pids: IntRange): UsbDevice? =
        manager.deviceList.values.firstOrNull { it.vendorId == vid && it.productId in pids }

    /** Same action string the vendor demo uses. */
    private const val ACTION_USB_PERMISSION = "com.zkteco.android.biometric.USB_PERMISSION"
    private const val TAG = "UsbPermission"
}
