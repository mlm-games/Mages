package org.mlm.mages.settings

import io.github.mlmgames.settings.core.annotations.*
import io.github.mlmgames.settings.core.types.*
import kotlinx.serialization.Serializable

@CategoryDefinition(order = 0) object Account
@CategoryDefinition(order = 1) object Appearance
@CategoryDefinition(order = 2) object Notifications
@CategoryDefinition(order = 3) object Privacy
@CategoryDefinition(order = 4) object Calls
@CategoryDefinition(order = 5) object Storage
@CategoryDefinition(order = 6) object Advanced

@Serializable
enum class ThemeMode { System, Light, Dark }

@Serializable
enum class PresenceMode { Online, Offline,  Unavailable }

@Serializable
enum class LocalRoomNotifMode {
    Default,      // follow server rules / Matrix notif mode
    MentionsOnly,
    Mute          // local-only filter
}

@Serializable
data class AppSettings(
    @Persisted
    val homeserver: String = "https://matrix.org",

    @Persisted
    val accountsJson: String = "",

    @Persisted
    val activeAccountId: String? = null,

    @Setting(
        title = "Theme",
        description = "System / Light / Dark",
        category = Appearance::class,
        type = Dropdown::class,
        options = ["System", "Light", "Dark"]
    )
    val themeMode: Int = ThemeMode.Dark.ordinal,

    @Setting(
        title = "Font size",
        description = "Message font size",
        category = Appearance::class,
        type = Slider::class,
        min = 12f,
        max = 24f,
        step = 1f
    )
    val fontSize: Float = 16f,

    @Setting(
        title = "Enable notifications",
        description = "Show notifications (desktop polling + Android push)",
        category = Notifications::class,
        type = Toggle::class
    )
    val notificationsEnabled: Boolean = true,

    @Setting(
        title = "Mentions only (local)",
        description = "Only notify when you're mentioned (local filter)",
        category = Notifications::class,
        type = Toggle::class,
        dependsOn = "notificationsEnabled"
    )
    val mentionsOnly: Boolean = false,

    @Setting(
        title = "Notification sound",
        description = "Play sound for notifications (platform support varies)",
        category = Notifications::class,
        type = Toggle::class,
        dependsOn = "notificationsEnabled"
    )
    val notificationSound: Boolean = true,

    @Persisted
    val desktopNotifBaselineMs: Long = 0L,

    @Persisted
    val androidNotifBaselineMs: Long = 0L,

    @Setting(
        title = "Send read receipts",
        description = "When disabled, Mages will not send read receipts / fully-read markers",
        category = Privacy::class,
        type = Toggle::class
    )
    val sendReadReceipts: Boolean = true,

    @Setting(
        title = "Send typing indicators",
        description = "When disabled, Mages will not send typing notifications",
        category = Privacy::class,
        type = Toggle::class
    )
    val sendTypingIndicators: Boolean = true,

    @Setting(
        title = "Presence",
        description = "Set a global presence status",
        category = Privacy::class,
        type = Dropdown::class,
        options = ["Online", "Offline", "Unavailable"]
    )
    val presence: Int = PresenceMode.Online.ordinal,

//    @Setting(
//        type = TextInput::class,
//        title = "Status Message",
//        description = "What's on your mind?"
//    )
//    val statusMessage: String = ""

//    @Setting(
//        title = "Element Call URL",
//        description = "Override Element Call instance (default: call.element.io)",
//        category = Calls::class,
//        type = TextInput::class
//    )
    val elementCallUrl: String = "",

    @Setting(
        title = "Media cache max size (MB)",
        description = "Limit media cache size; 0 = SDK default",
        category = Storage::class,
        type = Slider::class,
        min = 0f,
        max = 4096f,
        step = 64f
    )
    val mediaCacheMaxMb: Float = 0f,

    @Setting(
        title = "Media max file size (MB)",
        description = "Maximum media file size to keep; 0 = SDK default",
        category = Storage::class,
        type = Slider::class,
        min = 0f,
        max = 2048f,
        step = 32f
    )
    val mediaMaxFileMb: Float = 0f,

    @Setting(
        title = "Start in tray",
        description = "Minimize to tray on launch",
        category = Advanced::class,
        type = Toggle::class,
        platforms = [SettingPlatform.DESKTOP]
    )
    val startInTray: Boolean = false,

    @Persisted
    val lastOpenedRoomId: String? = null,
)