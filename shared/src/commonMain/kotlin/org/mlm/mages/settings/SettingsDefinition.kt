package org.mlm.mages.settings

import io.github.mlmgames.settings.core.annotations.*
import io.github.mlmgames.settings.core.types.*
import kotlinx.serialization.Serializable

@CategoryDefinition(order = 0) object Account
@CategoryDefinition(order = 1) object Appearance
@CategoryDefinition(order = 2) object Timeline
@CategoryDefinition(order = 3) object Notifications
@CategoryDefinition(order = 4) object Privacy
@CategoryDefinition(order = 5) object Calls
@CategoryDefinition(order = 6) object Storage
@CategoryDefinition(order = 7) object Advanced

@Serializable
enum class ThemeMode { System, Light, Dark }

@Serializable
enum class PresenceMode { Online, Offline, Unavailable }

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
        title = "Dynamic Colors",
        description = "Use Material You colors (Android 12+)",
        category = Appearance::class,
        type = Toggle::class,
        platforms = [SettingPlatform.ANDROID]
    )
    val dynamicColors: Boolean = false,

    @Setting(
        title = "Language",
        description = "System / English / Spanish",
        category = Appearance::class,
        type = Dropdown::class,
        options = ["System", "English", "Spanish"],
    )
    val language: Int = AppLanguage.System.ordinal,

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
        title = "Show message avatars",
        description = "Display user avatars next to messages",
        category = Appearance::class,
        type = Toggle::class
    )
    val showMessageAvatars: Boolean = true,

    @Setting(
        title = "Show username in DMs",
        category = Appearance::class,
        type = Toggle::class
    )
    val showUsernameInDms: Boolean = false,

    @Setting(
        title = "Message bubble animations",
        description = "Animate messages as they appear",
        category = Appearance::class,
        type = Toggle::class,
        platforms = [SettingPlatform.ANDROID]
    )
    val bubbleAnimations: Boolean = true,

    @Setting(
        title = "Hide membership events",
        description = "Join/leave/invite events in rooms",
        category = Timeline::class,
        type = Dropdown::class,
        options = ["Never", "Public Rooms", "Non-DMs", "Always"]
    )
    val compactPublicRoomMembershipEvents: Int = 1,

    @Setting(
        title = "Hide profile changes",
        description = "Display name/avatar changes in rooms",
        category = Timeline::class,
        type = Dropdown::class,
        options = ["Never", "Public Rooms", "Non-DMs", "Always"]
    )
    val compactPublicRoomProfileChangeEvents: Int = 1,

    @Setting(
        title = "Hide topic changes",
        description = "Room topic updates",
        category = Timeline::class,
        type = Dropdown::class,
        options = ["Never", "Public Rooms", "Non-DMs", "Always"]
    )
    val compactPublicRoomTopicEvents: Int = 1,

    @Setting(
        title = "Hide redacted events",
        description = "Events whose content was removed by redaction",
        category = Timeline::class,
        type = Dropdown::class,
        options = ["Never", "Public Rooms", "Non-DMs", "Always"]
    )
    val compactPublicRoomRedactedEvents: Int = 0,

    @Setting(
        title = "Hide room name changes",
        description = "Room name updates",
        category = Timeline::class,
        type = Dropdown::class,
        options = ["Never", "Public Rooms", "Non-DMs", "Always"]
    )
    val compactPublicRoomRoomNameEvents: Int = 0,

    @Setting(
        title = "Hide room avatar changes",
        description = "Room avatar updates",
        category = Timeline::class,
        type = Dropdown::class,
        options = ["Never", "Public Rooms", "Non-DMs", "Always"]
    )
    val compactPublicRoomRoomAvatarEvents: Int = 0,

    @Setting(
        title = "Hide encryption changes",
        description = "Encryption state updates",
        category = Timeline::class,
        type = Dropdown::class,
        options = ["Never", "Public Rooms", "Non-DMs", "Always"]
    )
    val compactPublicRoomRoomEncryptionEvents: Int = 0,

    @Setting(
        title = "Hide pinned-event updates",
        description = "Pinned event list changes",
        category = Timeline::class,
        type = Dropdown::class,
        options = ["Never", "Public Rooms", "Non-DMs", "Always"]
    )
    val compactPublicRoomRoomPinnedEvents: Int = 0,

    @Setting(
        title = "Hide power-level changes",
        description = "Moderator/admin permission changes",
        category = Timeline::class,
        type = Dropdown::class,
        options = ["Never", "Public Rooms", "Non-DMs", "Always"]
    )
    val compactPublicRoomRoomPowerLevelsEvents: Int = 0,

    @Setting(
        title = "Hide canonical alias changes",
        description = "Primary alias changes",
        category = Timeline::class,
        type = Dropdown::class,
        options = ["Never", "Public Rooms", "Non-DMs", "Always"]
    )
    val compactPublicRoomRoomCanonicalAliasEvents: Int = 0,

    @Setting(
        title = "Hide join-rule changes",
        description = "Who can join the room",
        category = Timeline::class,
        type = Dropdown::class,
        options = ["Never", "Public Rooms", "Non-DMs", "Always"]
    )
    val compactPublicRoomJoinRulesEvents: Int = 0,

    @Setting(
        title = "Hide history-visibility changes",
        description = "Who can read room history",
        category = Timeline::class,
        type = Dropdown::class,
        options = ["Never", "Public Rooms", "Non-DMs", "Always"]
    )
    val compactPublicRoomHistoryVisibilityEvents: Int = 0,

    @Setting(
        title = "Hide guest-access changes",
        description = "Guest access updates",
        category = Timeline::class,
        type = Dropdown::class,
        options = ["Never", "Public Rooms", "Non-DMs", "Always"]
    )
    val compactPublicRoomGuestAccessEvents: Int = 0,

    @Setting(
        title = "Hide server ACL changes",
        description = "Server allow/deny list updates",
        category = Timeline::class,
        type = Dropdown::class,
        options = ["Never", "Public Rooms", "Non-DMs", "Always"]
    )
    val compactPublicRoomServerAclEvents: Int = 0,

    @Setting(
        title = "Hide tombstone events",
        description = "Room replacement / upgrade notices",
        category = Timeline::class,
        type = Dropdown::class,
        options = ["Never", "Public Rooms", "Non-DMs", "Always"]
    )
    val compactPublicRoomTombstoneEvents: Int = 0,

    @Setting(
        title = "Hide space-child events",
        description = "Space hierarchy updates",
        category = Timeline::class,
        type = Dropdown::class,
        options = ["Never", "Public Rooms", "Non-DMs", "Always"]
    )
    val compactPublicRoomSpaceChildEvents: Int = 0,

    @Setting(
        title = "Hide other state events",
        description = "Unknown or uncategorized room state updates",
        category = Timeline::class,
        type = Dropdown::class,
        options = ["Never", "Public Rooms", "Non-DMs", "Always"]
    )
    val compactPublicRoomOtherStateEvents: Int = 0,

    @Setting(
        title = "Chat bubbles",
        description = "Open system settings to enable (or disable) conversation bubbles",
        category = Notifications::class,
        type = Button::class,
        platforms = [SettingPlatform.ANDROID],
    )
    @ActionHandler(OpenBubbleSettingsAction::class)
    val openBubbleSettings: Unit = Unit,

    @Setting(
        title = "Enable notifications",
        description = "Show notifications (desktop & web polling + Android push)",
        category = Notifications::class,
        type = Toggle::class
    )
    val notificationsEnabled: Boolean = true,

    @Setting(
        title = "Request notification permission",
        description = "Click to enable browser notifications",
        category = Notifications::class,
        type = Button::class,
        platforms = [SettingPlatform.WEB],
    )
    @ActionHandler(RequestNotificationPermissionAction::class)
    val requestNotificationPermission: Unit = Unit,

    @Setting(
        title = "Test browser notification",
        description = "Click to test if notifications work",
        category = Notifications::class,
        type = Button::class,
        platforms = [SettingPlatform.WEB], // TODO: Doesn't seem to work?
    )
    @ActionHandler(TestNotificationAction::class)
    val testNotification: Unit = Unit,

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

    @Setting(
        title = "Sound once per room",
        description = "Only play sound for first message until you check the room",
        category = Notifications::class,
        type = Toggle::class,
        dependsOn = "notificationSound"
    )
    val notifySoundOncePerRoom: Boolean = false,

    @Setting(
        title = "Auto-join room invites",
        description = "Automatically join rooms when invited",
        category = Notifications::class,
        type = Toggle::class,
        dependsOn = "notificationsEnabled"
    )
    val autoJoinInvites: Boolean = false,

    @Persisted
    val notifiedRoomsJson: String = "",

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
//        description = "Mostly only shown by discord-based clients"
//    )
//    val statusMessage: String = ""


    val elementCallUrl: String = "",

    @Setting(
        category = Calls::class,
        type = Toggle::class,
        title = "Show call screen",
        description = "Show full-screen incoming call UI",
        dependsOn = "callNotificationsEnabled",
        platforms = [SettingPlatform.ANDROID]
    )
    val showIncomingCallScreen: Boolean = false,

    @Setting(
        category = Calls::class,
        type = Toggle::class,
        title = "Call notifications",
        description = "Incoming call notifications",
        platforms = [SettingPlatform.ANDROID]
    )
    val callNotificationsEnabled: Boolean = true,

//    @Setting(
//        category = Notifications::class,
//        type = Button::class,
//        title = "System notification settings",
//        description = "Open Android notification settings for Mages",
//        platforms = [SettingPlatform.ANDROID],
//    )
//    @ActionHandler(OpenSystemNotificationSettingsAction::class)
//    val openSystemNotificationSettings: Unit = Unit,

    @Setting(
        category = Notifications::class,
        type = Button::class,
        title = "Select UnifiedPush distributor",
        description = "Choose the app that delivers pushes (gcompat/sunup/ntfy/etc.)",
        platforms = [SettingPlatform.ANDROID],
    )
    @ActionHandler(SelectUnifiedPushDistributorAction::class)
    val selectUnifiedPushDistributor: Unit = Unit,

    @Setting(
        category = Notifications::class,
        type = Button::class,
        title = "Re-register UnifiedPush",
        description = "Fix push issues after update/reboot or distributor change",
        platforms = [SettingPlatform.ANDROID],
    )
    @ActionHandler(ReRegisterUnifiedPushAction::class)
    val reRegisterUnifiedPush: Unit = Unit,

    @Setting(
        category = Notifications::class,
        type = Button::class,
        title = "Copy UnifiedPush endpoint",
        description = "For debugging; shows what endpoint is registered",
        platforms = [SettingPlatform.ANDROID],
    )
    @ActionHandler(CopyUnifiedPushEndpointAction::class)
    val copyUnifiedPushEndpoint: Unit = Unit,

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
        title = "Block media previews",
        description = "Don't auto-download thumbnails/previews",
        category = Storage::class,
        type = Toggle::class
    )
    val blockMediaPreviews: Boolean = false,

    @Setting(
        title = "Start in tray",
        description = "Minimize to tray on launch",
        category = Advanced::class,
        type = Toggle::class,
        platforms = [SettingPlatform.JVM]
    )
    val startInTray: Boolean = false,

    @Persisted
    val lastOpenedRoomId: String? = null,

    @Persisted
    val roomDraftsJson: String = "",

    @Setting(
        title = "Enter sends message",
        description = "When enabled, pressing Enter will send the message and Shift+Enter will insert a new line",
        category = Advanced::class,
        type = Toggle::class
    )
    val enterSendsMessage: Boolean = false,
)

object OpenSystemNotificationSettingsAction : SettingAction
object SelectUnifiedPushDistributorAction : SettingAction
object ReRegisterUnifiedPushAction : SettingAction
object CopyUnifiedPushEndpointAction : SettingAction

object OpenBubbleSettingsAction : SettingAction
object RequestNotificationPermissionAction : SettingAction
object TestNotificationAction : SettingAction

