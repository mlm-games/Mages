package org.mlm.mages.settings

import io.github.mlmgames.settings.core.annotations.*
import kotlinx.serialization.Serializable

// Define categories
@CategoryDefinition(order = 0)
object General

@CategoryDefinition(order = 1)
object Appearance

@CategoryDefinition(order = 2)
object Notifications

@CategoryDefinition(order = 3)
object Privacy

@CategoryDefinition(order = 4)
object Advanced

// Settings data class
@Serializable
data class AppSettings(
    // General
    @Setting(
        title = "Homeserver",
        description = "Your Matrix homeserver URL",
        category = General::class,
        type = io.github.mlmgames.settings.core.types.TextInput::class
    )
    val homeserver: String = "",

    // Appearance
    @Setting(
        title = "Dark Mode",
        description = "Enable dark theme",
        category = Appearance::class,
        type = io.github.mlmgames.settings.core.types.Toggle::class
    )
    val darkMode: Boolean = false,

    @Setting(
        title = "Font Size",
        description = "Message font size",
        category = Appearance::class,
        type = io.github.mlmgames.settings.core.types.Slider::class,
        min = 12f,
        max = 24f,
        step = 1f
    )
    val fontSize: Float = 16f,

    // Notifications
    @Setting(
        title = "Enable Notifications",
        description = "Receive push notifications",
        category = Notifications::class,
        type = io.github.mlmgames.settings.core.types.Toggle::class
    )
    val notificationsEnabled: Boolean = true,

    @Setting(
        title = "Notification Sound",
        description = "Play sound for notifications",
        category = Notifications::class,
        type = io.github.mlmgames.settings.core.types.Toggle::class,
        dependsOn = "notificationsEnabled"
    )
    val notificationSound: Boolean = true,

    // Privacy
    @Setting(
        title = "Read Receipts",
        description = "Send read receipts to others",
        category = Privacy::class,
        type = io.github.mlmgames.settings.core.types.Toggle::class
    )
    val sendReadReceipts: Boolean = true,

    @Setting(
        title = "Typing Indicators",
        description = "Show when you're typing",
        category = Privacy::class,
        type = io.github.mlmgames.settings.core.types.Toggle::class
    )
    val sendTypingIndicators: Boolean = true,

    // Persisted-only (not shown in UI)
    @Persisted
    val lastSyncToken: String = "",

    @Persisted
    val roomNotificationOverrides: Map<String, Int> = emptyMap(),

    @Persisted
    val lastOpenedRoomId: String? = null,
)