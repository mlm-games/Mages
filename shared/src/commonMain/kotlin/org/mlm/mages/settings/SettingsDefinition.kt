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
enum class HideInRoomsMode { Never, PublicRooms, NonDMs, Always }

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
    )
    val themeMode: ThemeMode = ThemeMode.Dark,

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
    )
    val language: AppLanguage = AppLanguage.System,

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
    )
    val compactPublicRoomMembershipEvents: HideInRoomsMode = HideInRoomsMode.PublicRooms,

    @Setting(
        title = "Hide profile changes",
        description = "Display name/avatar changes in rooms",
        category = Timeline::class,
        type = Dropdown::class,
    )
    val compactPublicRoomProfileChangeEvents: HideInRoomsMode = HideInRoomsMode.PublicRooms,

    @Setting(
        title = "Hide topic changes",
        category = Timeline::class,
        type = Dropdown::class,
    )
    val compactPublicRoomTopicEvents: HideInRoomsMode = HideInRoomsMode.PublicRooms,

    @Setting(
        title = "Hide redacted events",
        description = "Events whose content was removed by redaction",
        category = Timeline::class,
        type = Dropdown::class,
    )
    val compactPublicRoomRedactedEvents: HideInRoomsMode = HideInRoomsMode.Never,

    @Setting(
        title = "Hide room name changes",
        category = Timeline::class,
        type = Dropdown::class,
    )
    val compactPublicRoomRoomNameEvents: HideInRoomsMode = HideInRoomsMode.Never,

    @Setting(
        title = "Hide room avatar changes",
        category = Timeline::class,
        type = Dropdown::class,
    )
    val compactPublicRoomRoomAvatarEvents: HideInRoomsMode = HideInRoomsMode.Never,

    @Setting(
        title = "Hide encryption changes",
        category = Timeline::class,
        type = Dropdown::class,
    )
    val compactPublicRoomRoomEncryptionEvents: HideInRoomsMode = HideInRoomsMode.Never,

    @Setting(
        title = "Hide pinned-event updates",
        category = Timeline::class,
        type = Dropdown::class,
    )
    val compactPublicRoomRoomPinnedEvents: HideInRoomsMode = HideInRoomsMode.Never,

    @Setting(
        title = "Hide power-level changes",
        description = "Moderator/admin permission changes",
        category = Timeline::class,
        type = Dropdown::class,
    )
    val compactPublicRoomRoomPowerLevelsEvents: HideInRoomsMode = HideInRoomsMode.Never,

    @Setting(
        title = "Hide canonical alias changes",
        description = "Primary alias changes",
        category = Timeline::class,
        type = Dropdown::class,
    )
    val compactPublicRoomRoomCanonicalAliasEvents: HideInRoomsMode = HideInRoomsMode.Never,

    @Setting(
        title = "Hide join-rule changes",
        description = "Who can join the room",
        category = Timeline::class,
        type = Dropdown::class,
    )
    val compactPublicRoomJoinRulesEvents: HideInRoomsMode = HideInRoomsMode.Never,

    @Setting(
        title = "Hide history-visibility changes",
        description = "Who can read room history",
        category = Timeline::class,
        type = Dropdown::class,
    )
    val compactPublicRoomHistoryVisibilityEvents: HideInRoomsMode = HideInRoomsMode.Never,

    @Setting(
        title = "Hide guest-access changes",
        category = Timeline::class,
        type = Dropdown::class,
    )
    val compactPublicRoomGuestAccessEvents: HideInRoomsMode = HideInRoomsMode.Never,

    @Setting(
        title = "Hide server ACL changes",
        category = Timeline::class,
        type = Dropdown::class,
    )
    val compactPublicRoomServerAclEvents: HideInRoomsMode = HideInRoomsMode.Never,

    @Setting(
        title = "Hide tombstone events",
        description = "Room replacement / upgrade notices",
        category = Timeline::class,
        type = Dropdown::class,
    )
    val compactPublicRoomTombstoneEvents: HideInRoomsMode = HideInRoomsMode.Never,

    @Setting(
        title = "Hide space-child events",
        category = Timeline::class,
        type = Dropdown::class,
    )
    val compactPublicRoomSpaceChildEvents: HideInRoomsMode = HideInRoomsMode.Never,

    @Setting(
        title = "Hide other state events",
        description = "Unknown or uncategorized room state updates",
        category = Timeline::class,
        type = Dropdown::class,
    )
    val compactPublicRoomOtherStateEvents: HideInRoomsMode = HideInRoomsMode.Never,

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
        title = "Notification rules",
        description = "Manage messages, mentions, reactions, invites, and other server-backed notification rules",
        category = Notifications::class,
        type = Button::class,
        dependsOn = "notificationsEnabled",
    )
    @ActionHandler(OpenNotificationRulesAction::class)
    val openNotificationRules: Unit = Unit,

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
        platforms = [SettingPlatform.WEB],
    )
    @ActionHandler(TestNotificationAction::class)
    val testNotification: Unit = Unit,

    @Deprecated("Replaced by server-backed push rule toggles in NotificationSettingsRepository")
    @Setting(
        title = "Mentions only (legacy)",
        description = "Deprecated — use notification toggles instead",
        category = Notifications::class,
        type = Toggle::class,
        dependsOn = "notificationsEnabled"
    )
    val mentionsOnly: Boolean = false,

    @Setting(
        title = "Show message preview",
        description = "Show message content in notifications",
        category = Notifications::class,
        type = Toggle::class,
        dependsOn = "notificationsEnabled"
    )
    val notificationShowPreview: Boolean = true,

    @Setting(
        title = "Vibrate",
        description = "Vibrate on notification",
        category = Notifications::class,
        type = Toggle::class,
        dependsOn = "notificationsEnabled",
        platforms = [SettingPlatform.ANDROID]
    )
    val notificationVibrate: Boolean = true,

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
        title = "Quiet hours",
        description = "Suppress audible notifications during quiet hours",
        category = Notifications::class,
        type = Toggle::class,
        dependsOn = "notificationsEnabled"
    )
    val quietHoursEnabled: Boolean = false,

    @Setting(
        title = "Quiet hours start time", category = Notifications::class,
        type = TimePickerType::class,
        dependsOn = "quietHoursEnabled"
    )
    val quietHoursStartMinutes: Int = 1380,

    @Setting(
        title = "Quiet hours end time",
        category = Notifications::class,
        type = TimePickerType::class,
        dependsOn = "quietHoursEnabled"
    )
    val quietHoursEndMinutes: Int = 420,

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
    )
    val presence: PresenceMode = PresenceMode.Online,

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

//    @Setting(
//        title = "Auto-register UnifiedPush",
//        description = "Automatically register with a UnifiedPush distributor when available",
//        category = Notifications::class,
//        type = Toggle::class,
//        platforms = [SettingPlatform.ANDROID],
//    )
    @Persisted
    val autoRegisterPushDistributor: Boolean = true,

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
        title = "Block media previews",
        description = "Don't auto-download thumbnails/previews",
        category = Storage::class,
        type = Toggle::class
    )
    val blockMediaPreviews: Boolean = false,

    @Setting(
        title = "Media Cache",
        description = "View and manage cached media",
        category = Storage::class,
        type = Button::class,
        platforms = [SettingPlatform.ANDROID, SettingPlatform.JVM]
    )
    @ActionHandler(OpenMediaCacheAction::class)
    val openMediaCache: Unit = Unit,

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
object OpenNotificationRulesAction : SettingAction
object OpenMediaCacheAction : SettingAction
object RequestNotificationPermissionAction : SettingAction
object TestNotificationAction : SettingAction

