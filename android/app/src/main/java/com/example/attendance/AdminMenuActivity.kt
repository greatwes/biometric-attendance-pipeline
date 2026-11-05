package com.example.attendance

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.attendance.databinding.ActivityAdminMenuBinding

/**
 * What the admin sees after entering the PIN: five destinations, nothing else.
 *
 * This exists so the enrolment screen stops being the place every other admin
 * task is bolted onto. It holds no state of its own - each tile owns its own
 * screen - which keeps the sensor ownership rules simple: only the enrolment
 * screen ever touches the fingerprint engine.
 */
class AdminMenuActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminMenuBinding
    private lateinit var config: Config

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminMenuBinding.inflate(layoutInflater)
        setContentView(binding.root)
        config = Config.get(this)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.admin)

        binding.tileEnrol.setOnClickListener { open(AdminActivity::class.java) }
        binding.tileStaff.setOnClickListener { open(StaffListActivity::class.java) }
        binding.tileRecords.setOnClickListener { open(RecordsActivity::class.java) }
        binding.tileLocation.setOnClickListener { open(LocationActivity::class.java) }
        binding.tileDepartments.setOnClickListener { open(DepartmentActivity::class.java) }
        binding.tileSettings.setOnClickListener { open(SettingsActivity::class.java) }
    }

    override fun onResume() {
        super.onResume()
        // Re-read on every return: the location and PIN can change in the
        // screens this menu launches.
        val where = config.location
        binding.menuLocation.text = if (where.isBlank()) {
            getString(R.string.location_unset)
        } else {
            getString(R.string.location_is, where)
        }

        binding.menuWarning.text = when {
            config.adminPin == Config.DEFAULT_ADMIN_PIN ->
                getString(R.string.default_pin_warning)
            !config.isConfigured -> getString(R.string.not_configured_warning)
            else -> ""
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun open(target: Class<*>) = startActivity(Intent(this, target))
}
