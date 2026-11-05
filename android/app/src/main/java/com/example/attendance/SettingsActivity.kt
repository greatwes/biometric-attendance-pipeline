package com.example.attendance

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.attendance.databinding.ActivitySettingsBinding
import com.example.attendance.sync.SyncWorker

/** Endpoint, credentials and matching behaviour. Moved off the enrolment screen. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var config: Config

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        config = Config.get(this)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.settings)

        binding.serverUrl.setText(config.baseUrl)
        binding.sharedSecret.setText(config.sharedSecret)
        binding.deviceSn.setText(config.deviceSn)
        binding.threshold.setText(config.matchThreshold.toString())
        binding.debounce.setText(config.debounceSeconds.toString())
        // Left blank rather than pre-filled: showing the current PIN to anyone
        // who already guessed it is not a trade worth making.
        binding.adminPin.setText("")

        binding.saveSettingsButton.setOnClickListener { save() }
        renderWarning()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun save() {
        val url = binding.serverUrl.text.toString().trim()
        if (!url.startsWith("https://")) {
            // Plain HTTP would put fingerprint templates and staff names on the
            // wire in clear text over a public hotspot.
            binding.serverUrl.error = getString(R.string.https_required)
            return
        }
        config.baseUrl = url
        config.sharedSecret = binding.sharedSecret.text.toString().trim()
        config.deviceSn = binding.deviceSn.text.toString().trim()
        binding.threshold.text.toString().toIntOrNull()?.let { config.matchThreshold = it }
        binding.debounce.text.toString().toIntOrNull()?.let { config.debounceSeconds = it }

        val newPin = binding.adminPin.text.toString().trim()
        if (newPin.isNotEmpty()) {
            config.adminPin = newPin
            binding.adminPin.setText("")
        }

        binding.settingsWarning.text = getString(R.string.settings_saved)
        SyncWorker.syncNow(this)
        renderWarning()
    }

    private fun renderWarning() {
        if (config.adminPin == Config.DEFAULT_ADMIN_PIN) {
            binding.settingsWarning.text = getString(R.string.default_pin_warning)
        }
    }
}
