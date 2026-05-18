package org.mlm.mages.activities

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import co.touchlab.kermit.Logger
import androidx.appcompat.app.AppCompatActivity
import org.mlm.mages.push.PREF_INSTANCE
import org.unifiedpush.android.connector.UnifiedPush

class DistributorPickerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val distributors = UnifiedPush.getDistributors(this)
        val saved = UnifiedPush.getSavedDistributor(this)

        Logger.i("DistributorPicker: distributors=$distributors, saved=$saved")

        when {
            distributors.isEmpty() -> {
                AlertDialog.Builder(this)
                    .setTitle("No push distributor")
                    .setMessage("No push distributor available.")
                    .setPositiveButton("OK") { _, _ -> finish() }
                    .show()
            }
            distributors.size == 1 -> {
                val dist = distributors.first()
                UnifiedPush.saveDistributor(this, dist)
                UnifiedPush.register(this, PREF_INSTANCE)

                val name = if (dist.contains(packageName)) "Built-in FCM (embedded)" else dist
                AlertDialog.Builder(this)
                    .setTitle("Push service")
                    .setMessage("Using: $name\n\nYou can install ntfy or another distributor later to switch.")
                    .setPositiveButton("OK") { _, _ -> finish() }
                    .show()
            }
            else -> {
                AlertDialog.Builder(this)
                    .setTitle("Select push service")
                    .setMessage("Choose which app will deliver your push notifications.")
                    .setPositiveButton("Continue") { _, _ -> launchPicker() }
                    .setNegativeButton("Cancel") { _, _ -> finish() }
                    .show()
            }
        }
    }

    private fun launchPicker() {
        UnifiedPush.tryPickDistributor(this) { success ->
            if (success) UnifiedPush.register(this, PREF_INSTANCE)
            finish()
        }
    }
}
