package org.mlm.mages.calls

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import co.touchlab.kermit.Logger
import kotlinx.coroutines.delay
import org.mlm.mages.MatrixService
import org.mlm.mages.activities.CallActivity
import org.mlm.mages.matrix.CallIntent
import org.mlm.mages.platform.SessionBootstrapper
import org.mlm.mages.platform.platformEmbeddedElementCallParentUrlOrNull
import org.mlm.mages.platform.platformEmbeddedElementCallUrlOrNull
import org.mlm.mages.settings.AppSettings
import org.mlm.mages.settings.ThemeMode
import org.mlm.mages.settings.appLanguageTagOrDefault

object CallEntryPoint {
    private const val JOIN_ATTEMPTS = 12
    private const val JOIN_RETRY_BASE_MS = 350L

    suspend fun start(
        context: Context,
        service: MatrixService,
        callManager: CallManager,
        settings: AppSettings,
        roomId: String,
        roomName: String?,
        isVoiceOnly: Boolean,
        incoming: Boolean,
    ): Boolean {
        if (service.portOrNull == null) {
            SessionBootstrapper.ensureReadyAndSyncing(service)
        }

        val resolvedName = roomName?.takeIf { it.isNotBlank() }
            ?: runCatching { service.portOrNull?.roomProfile(roomId)?.name }.getOrNull()
            ?: roomId
        val elementCallUrl = settings.elementCallUrl.trim().ifBlank {
            runCatching { platformEmbeddedElementCallUrlOrNull() }.getOrNull()
        }
        val parentCallUrl = runCatching { platformEmbeddedElementCallParentUrlOrNull() }.getOrNull()
        val dark = when (settings.themeMode) {
            ThemeMode.System ->
                (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
            ThemeMode.Dark -> true
            ThemeMode.Light -> false
        }
        val languageTag = appLanguageTagOrDefault(
            settings.language,
            context.resources.configuration.locales[0].toLanguageTag()
        )

        repeat(JOIN_ATTEMPTS) { attempt ->
            val joined = runCatching {
                callManager.startOrJoinCall(
                    roomId = roomId,
                    roomName = resolvedName,
                    intent = if (isVoiceOnly) CallIntent.JoinExistingVoiceDm else CallIntent.JoinExisting,
                    elementCallUrl = elementCallUrl,
                    parentUrl = parentCallUrl,
                    languageTag = languageTag,
                    theme = if (dark) "dark" else "light",
                    incoming = incoming,
                )
            }.getOrDefault(false)

            if (joined) {
                val opened = runCatching {
                    context.startActivity(
                        Intent(context, CallActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                    )
                }.isSuccess
                if (opened) {
                    return true
                }
                Logger.w { "CallEntryPoint: joined room $roomId but could not open the call screen" }
            }
            delay(JOIN_RETRY_BASE_MS + attempt * 100L)
        }

        Logger.w { "CallEntryPoint: could not join room $roomId" }
        return false
    }
}
