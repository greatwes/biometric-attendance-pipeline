package com.example.attendance

import android.app.Application
import com.example.attendance.sync.SyncWorker

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Registered here rather than in an Activity so that a device which
        // reboots overnight still drains its queue even if nobody opens the
        // app until morning.
        SyncWorker.schedulePeriodic(this)
    }
}
