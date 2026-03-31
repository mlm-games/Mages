package org.mlm.mages.notifications

import org.mlm.mages.matrix.MatrixPort
import org.mlm.mages.matrix.PushRuleKind
import org.mlm.mages.matrix.RoomNotificationMode

class NotificationSettingsRepository(
    private val port: MatrixPort,
) {
    suspend fun isToggleEnabled(toggle: PushRuleToggle): Boolean {
        if (toggle.id == NotificationToggles.reactions.id) {
            return port.isReactionNotificationsEnabled()
                .getOrElse { toggle.defaultUiValue }
        }

        val binding = toggle.rules.firstOrNull() ?: return toggle.defaultUiValue
        val ruleEnabled = port.isPushRuleEnabled(binding.kind, binding.ruleId)
            .getOrElse { return toggle.defaultUiValue }

        return if (toggle.invertedSemantics) !ruleEnabled else ruleEnabled
    }

    suspend fun setToggleEnabled(toggle: PushRuleToggle, uiEnabled: Boolean): Result<Unit> {
        if (toggle.id == NotificationToggles.reactions.id) {
            return port.setReactionNotificationsEnabled(uiEnabled)
        }

        val ruleEnabled = if (toggle.invertedSemantics) !uiEnabled else uiEnabled
        var lastResult: Result<Unit> = Result.success(Unit)
        for (binding in toggle.rules) {
            lastResult = port.setPushRuleEnabled(binding.kind, binding.ruleId, ruleEnabled)
            if (lastResult.isFailure) {
                return lastResult
            }
        }
        return lastResult
    }

    suspend fun readAll(): Map<String, Boolean> {
        return NotificationToggles.all.associate { toggle ->
            toggle.id to isToggleEnabled(toggle)
        }
    }

    suspend fun getDefaultRoomNotificationMode(
        isEncrypted: Boolean,
        isOneToOne: Boolean,
    ): Result<RoomNotificationMode> {
        return port.getDefaultRoomNotificationMode(isEncrypted, isOneToOne)
    }

    suspend fun setDefaultRoomNotificationMode(
        isEncrypted: Boolean,
        isOneToOne: Boolean,
        mode: RoomNotificationMode,
    ): Result<Unit> {
        return port.setDefaultRoomNotificationMode(isEncrypted, isOneToOne, mode)
    }
}
