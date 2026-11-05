package com.example.attendance

import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.attendance.databinding.ActivityLocationBinding

/**
 * Which site this terminal is recording for, and the list of sites to choose
 * from.
 *
 * The same APK runs at several locations, so this is the setting that decides
 * what every punch on this device is attributed to. It is stamped onto punches
 * as they happen - changing it here affects future punches only, and never
 * rewrites history.
 */
class LocationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLocationBinding
    private lateinit var config: Config

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLocationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        config = Config.get(this)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.location_setting)

        binding.addLocationButton.setOnClickListener {
            val name = binding.newLocation.text.toString().trim()
            if (name.isEmpty()) return@setOnClickListener
            config.addLocation(name)
            // First location added becomes the device's location: with only one
            // to choose from, making them pick again is pure friction.
            if (config.location.isBlank()) config.location = name
            binding.newLocation.text?.clear()
            render()
        }

        render()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun render() {
        val current = config.location
        binding.currentLocation.text = if (current.isBlank()) {
            getString(R.string.location_unset)
        } else {
            getString(R.string.location_is, current)
        }

        binding.locationList.removeAllViews()
        val locations = config.knownLocations
        if (locations.isEmpty()) {
            binding.locationList.addView(TextView(this).apply {
                text = getString(R.string.location_none_yet)
                setTextColor(getColor(R.color.text_muted))
            })
            return
        }

        for (name in locations) {
            binding.locationList.addView(rowFor(name, name.equals(current, ignoreCase = true)))
        }
    }

    private fun rowFor(name: String, isCurrent: Boolean): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 8)

            addView(TextView(this@LocationActivity).apply {
                text = if (isCurrent) getString(R.string.location_is, name) else name
                setTextColor(getColor(R.color.text_primary))
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })

            if (!isCurrent) {
                addView(Button(this@LocationActivity).apply {
                    text = getString(R.string.location_set_default)
                    setOnClickListener {
                        config.location = name
                        Toast.makeText(
                            this@LocationActivity,
                            getString(R.string.location_saved, name),
                            Toast.LENGTH_SHORT
                        ).show()
                        render()
                    }
                })
            }

            addView(Button(this@LocationActivity).apply {
                text = getString(R.string.delete)
                setOnClickListener { confirmRemove(name, isCurrent) }
            })
        }

    /**
     * Removing the site the device is currently set to would leave punches
     * stamped with a location no longer in the list, so it is called out rather
     * than silently allowed.
     */
    private fun confirmRemove(name: String, isCurrent: Boolean) {
        val message = if (isCurrent) {
            getString(R.string.location_remove_current_warning, name)
        } else {
            getString(R.string.location_remove_warning, name)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.confirm_delete_title, name))
            .setMessage(message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                config.removeLocation(name)
                if (isCurrent) config.location = ""
                Toast.makeText(
                    this,
                    getString(R.string.location_removed, name),
                    Toast.LENGTH_SHORT
                ).show()
                render()
            }
            .show()
    }
}
