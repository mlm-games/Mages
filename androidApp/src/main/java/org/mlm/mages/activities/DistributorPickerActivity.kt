package org.mlm.mages.activities

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.mlm.mages.push.PREF_INSTANCE
import org.unifiedpush.android.connector.UnifiedPush

class DistributorPickerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val distributors = UnifiedPush.getDistributors(this)
        val saved = UnifiedPush.getSavedDistributor(this)

        Log.i("UP-Mages", "Distributors: $distributors, saved: $saved")

        when {
            distributors.isEmpty() -> {
                AlertDialog.Builder(this)
                    .setTitle("No push distributor")
                    .setMessage("Install a UnifiedPush distributor app like ntfy, Sunup, or gCompat to receive push notifications.")
                    .setPositiveButton("OK") { _, _ -> finish() }
                    .setOnCancelListener { finish() }
                    .show()
            }

            distributors.size == 1 && saved == distributors.first() -> {
                AlertDialog.Builder(this)
                    .setTitle("Push service")
                    .setMessage("Currently using: ${distributors.first()}\n\nInstall another distributor to switch.")
                    .setPositiveButton("OK") { _, _ -> finish() }
                    .setOnCancelListener { finish() }
                    .show()
            }

            else -> {
                AlertDialog.Builder(this)
                    .setTitle("Select push service")
                    .setMessage("Choose which app will deliver your push notifications.")
                    .setPositiveButton("Continue") { _, _ -> launchPicker() }
                    .setNegativeButton("Cancel") { _, _ -> finish() }
                    .setOnCancelListener { finish() }
                    .show()
            }
        }
    }

    private fun launchPicker() {
        UnifiedPush.tryPickDistributor(this) { success ->
            Log.i("UP-Mages", "tryPickDistributor success=$success")
            if (success) {
                UnifiedPush.register(this, PREF_INSTANCE)
            }
            finish()
        }
    }
}
