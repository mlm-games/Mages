package org.mlm.mages.push

import android.app.NotificationManager
import android.content.Context
import android.os.Build

object BubbleEligibilityEvaluator {

    fun canBubble(context: Context, roomId: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val nm = context.getSystemService(NotificationManager::class.java)

        val allowed = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                nm.bubblePreference != NotificationManager.BUBBLE_PREFERENCE_NONE
            else ->
                @Suppress("DEPRECATION") nm.areBubblesAllowed()
        }

        return allowed && roomId.isNotBlank()
    }
}