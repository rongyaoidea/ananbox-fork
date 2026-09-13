package com.github.ananbox

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.github.ananbox.anna.AnnaCore
import com.github.ananbox.anna.AnnaService

class SettingsActivity : AppCompatActivity() {

    class SettingsFragment: PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            val shutdown = preferenceScreen.findPreference<Preference>(getString(R.string.settings_shutdown_key))

            shutdown?.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                activity?.finishAffinity()
                Anbox.stopRuntime()
                Anbox.stopContainer()
                true
            }

            val gateway = preferenceScreen.findPreference<SwitchPreferenceCompat>("anna_gateway_enabled")
            gateway?.setOnPreferenceChangeListener { _, newValue ->
                if (newValue as? Boolean == true) {
                    AnnaService.start(requireContext())
                } else {
                    AnnaService.stop(requireContext())
                }
                true
            }

            var execConfirmed = false
            val exec = preferenceScreen.findPreference<SwitchPreferenceCompat>(AnnaCore.PREF_EXEC_ENABLED)
            exec?.setOnPreferenceChangeListener { preference, newValue ->
                if (newValue as? Boolean == true && !execConfirmed) {
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.anna_exec_confirm_title)
                        .setMessage(R.string.anna_exec_confirm_message)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            execConfirmed = true
                            (preference as SwitchPreferenceCompat).isChecked = true
                            execConfirmed = false
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                    false
                } else {
                    true
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.title_settings)

        supportFragmentManager
            .beginTransaction()
            .replace(R.id.settings, SettingsFragment())
            .commit()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when(item.itemId) {
            android.R.id.home -> {
                finish()
            }
        }
        return true
    }
}