package org.mlm.mages.settings

import io.github.mlmgames.settings.core.annotations.*
import io.github.mlmgames.settings.core.types.*
import kotlinx.serialization.Serializable

@CategoryDefinition(order = 0, titleKey = MagesSettingsKeys.CATEGORY_ACCOUNT)
object Account

@CategoryDefinition(order = 1, titleKey = MagesSettingsKeys.CATEGORY_APPEARANCE)
object Appearance

@CategoryDefinition(order = 2, titleKey = MagesSettingsKeys.CATEGORY_TIMELINE)
object Timeline

@CategoryDefinition(order = 3, titleKey = MagesSettingsKeys.CATEGORY_NOTIFICATIONS)
object Notifications

@CategoryDefinition(order = 4, titleKey = MagesSettingsKeys.CATEGORY_PRIVACY)
object Privacy

@CategoryDefinition(order = 5, titleKey = MagesSettingsKeys.CATEGORY_CALLS)
object Calls

@CategoryDefinition(order = 6, titleKey = MagesSettingsKeys.CATEGORY_STORAGE)
object Storage

@CategoryDefinition(order = 7, titleKey = MagesSettingsKeys.CATEGORY_ADVANCED)
object Advanced

@Serializable
enum class ThemeMode { System, Light, Dark }

@Serializable
enum class PresenceMode { Online, Offline, Unavailable }

@Serializable
enum class AppLockTimeout {
    Immediate,
    OneMinute,
    FiveMinutes,
    ThirtyMinutes,
    OneHour,
    Never
}

fun AppLockTimeout.toSeconds(): Long = when (this) {
    AppLockTimeout.Immediate -> 0L
    AppLockTimeout.OneMinute -> 60L
    AppLockTimeout.FiveMinutes -> 300L
    AppLockTimeout.ThirtyMinutes -> 1800L
    AppLockTimeout.OneHour -> 3600L
    AppLockTimeout.Never -> -1L
}

@Serializable
enum class HideInRoomsMode { Never, PublicRooms, NonDMs, Always }

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
        titleKey = MagesSettingsKeys.THEME_MODE,
        description = "System / Light / Dark",
        descriptionKey = MagesSettingsKeys.THEME_MODE_DESCRIPTION,
        category = Appearance::class,
        type = Dropdown::class,
        options = ["System", "Light", "Dark"],
        optionsKey = MagesSettingsKeys.THEME_OPTIONS,
    )
    val themeMode: ThemeMode = ThemeMode.System,

    @Setting(
        title = "Dynamic Colors",
        titleKey = MagesSettingsKeys.DYNAMIC_COLORS,
        description = "Use Material You colors (Android 12+)",
        descriptionKey = MagesSettingsKeys.DYNAMIC_COLORS_DESCRIPTION,
        category = Appearance::class,
        type = Toggle::class,
        platforms = [SettingPlatform.ANDROID]
    )
    val dynamicColors: Boolean = false,

    @Setting(
        title = "Pure black (OLED)",
        titleKey = MagesSettingsKeys.OLED_BLACK,
        description = "Use a true black background in dark mode for OLED displays",
        descriptionKey = MagesSettingsKeys.OLED_BLACK_DESCRIPTION,
        category = Appearance::class,
        type = Toggle::class,
        key = "oled_black"
    )
    val oledBlack: Boolean = false,

    @Setting(
        title = "Language",
        titleKey = MagesSettingsKeys.LANGUAGE,
        description = "Application language",
        descriptionKey = MagesSettingsKeys.LANGUAGE_DESCRIPTION,
        category = Appearance::class,
        type = Dropdown::class,
        options = [
            "System", "English", "Spanish", "Arabic", "Czech", "German", "Greek", "Persian",
            "Finnish", "French", "Croatian", "Hungarian", "Indonesian", "Italian", "Hebrew",
            "Japanese", "Korean", "Dutch", "Polish", "Portuguese", "Russian", "Swedish", "Turkish",
            "Ukrainian", "Vietnamese", "Simplified Chinese", "Traditional Chinese"
        ],
        optionsKey = MagesSettingsKeys.LANGUAGE_OPTIONS,
    )
    val language: AppLanguage = AppLanguage.System,

    @Setting(
        title = "Font size",
        titleKey = MagesSettingsKeys.FONT_SIZE,
        description = "Message font size",
        descriptionKey = MagesSettingsKeys.FONT_SIZE_DESCRIPTION,
        category = Appearance::class,
        type = Slider::class,
        min = 12f,
        max = 24f,
        step = 1f
    )
    val fontSize: Float = 16f,

    @Setting(
        title = "Show message avatars",
        titleKey = MagesSettingsKeys.SHOW_MESSAGE_AVATARS,
        description = "Display user avatars next to messages",
        descriptionKey = MagesSettingsKeys.SHOW_MESSAGE_AVATARS_DESCRIPTION,
        category = Appearance::class,
        type = Toggle::class
    )
    val showMessageAvatars: Boolean = true,

    @Setting(
        title = "Show reaction avatars",
        titleKey = MagesSettingsKeys.SHOW_REACTION_AVATARS,
        description = "Display user avatars on reaction chips",
        descriptionKey = MagesSettingsKeys.SHOW_REACTION_AVATARS_DESCRIPTION,
        category = Appearance::class,
        type = Toggle::class
    )
    val showReactionAvatars: Boolean = false,

    @Setting(
        title = "Show username in DMs",
        titleKey = MagesSettingsKeys.SHOW_USERNAME_IN_DMS,
        description = "Show usernames in direct messages",
        descriptionKey = MagesSettingsKeys.SHOW_USERNAME_IN_DMS_DESCRIPTION,
        category = Appearance::class,
        type = Toggle::class
    )
    val showUsernameInDms: Boolean = false,

    @Setting(
        title = "Message bubble animations",
        titleKey = MagesSettingsKeys.BUBBLE_ANIMATIONS,
        description = "Animate messages as they appear",
        descriptionKey = MagesSettingsKeys.BUBBLE_ANIMATIONS_DESCRIPTION,
        category = Appearance::class,
        type = Toggle::class,
    )
    val bubbleAnimations: Boolean = false,

    @Setting(
        title = "Hide membership events",
        titleKey = MagesSettingsKeys.HIDE_MEMBERSHIP_EVENTS,
        description = "Join/leave/invite events in rooms",
        descriptionKey = MagesSettingsKeys.HIDE_MEMBERSHIP_EVENTS_DESCRIPTION,
        category = Timeline::class,
        type = Dropdown::class,
        options = ["Never", "Public Rooms", "Non-DMs", "Always"],
        optionsKey = MagesSettingsKeys.HIDE_IN_ROOMS_OPTIONS,
    )
    val compactPublicRoomMembershipEvents: HideInRoomsMode = HideInRoomsMode.PublicRooms,

    @Setting(
        title = "Hide profile changes",
        titleKey = MagesSettingsKeys.HIDE_PROFILE_CHANGES,
        description = "Display name/avatar changes in rooms",
        descriptionKey = MagesSettingsKeys.HIDE_PROFILE_CHANGES_DESCRIPTION,
        category = Timeline::class,
        type = Dropdown::class,
        options = ["Never", "Public Rooms", "Non-DMs", "Always"],
        optionsKey = MagesSettingsKeys.HIDE_IN_ROOMS_OPTIONS,
    )
    val compactPublicRoomProfileChangeEvents: HideInRoomsMode = HideInRoomsMode.PublicRooms,

    @Setting(
        title = "Hide topic changes",
        titleKey = MagesSettingsKeys.HIDE_TOPIC_CHANGES,
        description = "Topic changes in rooms",
        descriptionKey = MagesSettingsKeys.HIDE_TOPIC_CHANGES_DESCRIPTION,
        category = Timeline::class,
        type = Dropdown::class,
        options = ["Never", "Public Rooms", "Non-DMs", "Always"],
        optionsKey = MagesSettingsKeys.HIDE_IN_ROOMS_OPTIONS,
    )
    val compactPublicRoomTopicEvents: HideInRoomsMode = HideInRoomsMode.PublicRooms,

    @Setting(
        title = "Hide redacted events",
        titleKey = MagesSettingsKeys.HIDE_REDACTED_EVENTS,
        description = "Events whose content was removed by redaction",
        descriptionKey = MagesSettingsKeys.HIDE_REDACTED_EVENTS_DESCRIPTION,
        category = Timeline::class,
        type = Dropdown::class,
        options = ["Never", "Public Rooms", "Non-DMs", "Always"],
        optionsKey = MagesSettingsKeys.HIDE_IN_ROOMS_OPTIONS,
    )
    val compactPublicRoomRedactedEvents: HideInRoomsMode = HideInRoomsMode.Never,

    @Setting(
        title = "Hide room name changes",
        titleKey = MagesSettingsKeys.HIDE_ROOM_NAME_CHANGES,
        description = "Room name changes",
        descriptionKey = MagesSettingsKeys.HIDE_ROOM_NAME_CHANGES_DESCRIPTION,
        category = Timeline::class,
        type = Dropdown::class,
        options = ["Never", "Public Rooms", "Non-DMs", "Always"],
        optionsKey = MagesSettingsKeys.HIDE_IN_ROOMS_OPTIONS,
    )
    val compactPublicRoomRoomNameEvents: HideInRoomsMode = HideInRoomsMode.Never,

    @Setting(
        title = "Hide room avatar changes",
        titleKey = MagesSettingsKeys.HIDE_ROOM_AVATAR_CHANGES,
        description = "Room avatar changes",
        descriptionKey = MagesSettingsKeys.HIDE_ROOM_AVATAR_CHANGES_DESCRIPTION,
        category = Timeline::class,
        type = Dropdown::class,
        options = ["Never", "Public Rooms", "Non-DMs", "Always"],
        optionsKey = MagesSettingsKeys.HIDE_IN_ROOMS_OPTIONS,
    )
    val compactPublicRoomRoomAvatarEvents: HideInRoomsMode = HideInRoomsMode.Never,

    @Setting(
        title = "Hide encryption changes",
        titleKey = MagesSettingsKeys.HIDE_ENCRYPTION_CHANGES,
        description = "Encryption state changes",
        descriptionKey = MagesSettingsKeys.HIDE_ENCRYPTION_CHANGES_DESCRIPTION,
        category = Timeline::class,
        type = Dropdown::class,
        options = ["Never", "Public Rooms", "Non-DMs", "Always"],
        optionsKey = MagesSettingsKeys.HIDE_IN_ROOMS_OPTIONS,
    )
    val compactPublicRoomRoomEncryptionEvents: HideInRoomsMode = HideInRoomsMode.Never,

    @Setting(
        title = "Hide pinned-event updates",
        titleKey = MagesSettingsKeys.HIDE_PINNED_EVENT_UPDATES,
        description = "Pinned event updates",
        descriptionKey = MagesSettingsKeys.HIDE_PINNED_EVENT_UPDATES_DESCRIPTION,
        category = Timeline::class,
        type = Dropdown::class,
        options = ["Never", "Public Rooms", "Non-DMs", "Always"],
        optionsKey = MagesSettingsKeys.HIDE_IN_ROOMS_OPTIONS,
    )
    val compactPublicRoomRoomPinnedEvents: HideInRoomsMode = HideInRoomsMode.Never,

    @Setting(
        title = "Hide power-level changes",
        titleKey = MagesSettingsKeys.HIDE_POWER_LEVEL_CHANGES,
        description = "Moderator/admin permission changes",
        descriptionKey = MagesSettingsKeys.HIDE_POWER_LEVEL_CHANGES_DESCRIPTION,
        category = Timeline::class,
        type = Dropdown::class,
        options = ["Never", "Public Rooms", "Non-DMs", "Always"],
        optionsKey = MagesSettingsKeys.HIDE_IN_ROOMS_OPTIONS,
    )
    val compactPublicRoomRoomPowerLevelsEvents: HideInRoomsMode = HideInRoomsMode.Never,

    @Setting(
        title = "Hide canonical alias changes",
        titleKey = MagesSettingsKeys.HIDE_CANONICAL_ALIAS_CHANGES,
        description = "Primary alias changes",
        descriptionKey = MagesSettingsKeys.HIDE_CANONICAL_ALIAS_CHANGES_DESCRIPTION,
        category = Timeline::class,
        type = Dropdown::class,
        options = ["Never", "Public Rooms", "Non-DMs", "Always"],
        optionsKey = MagesSettingsKeys.HIDE_IN_ROOMS_OPTIONS,
    )
    val compactPublicRoomRoomCanonicalAliasEvents: HideInRoomsMode = HideInRoomsMode.Never,

    @Setting(
        title = "Hide join-rule changes",
        titleKey = MagesSettingsKeys.HIDE_JOIN_RULE_CHANGES,
        description = "Who can join the room",
        descriptionKey = MagesSettingsKeys.HIDE_JOIN_RULE_CHANGES_DESCRIPTION,
        category = Timeline::class,
        type = Dropdown::class,
        options = ["Never", "Public Rooms", "Non-DMs", "Always"],
        optionsKey = MagesSettingsKeys.HIDE_IN_ROOMS_OPTIONS,
    )
    val compactPublicRoomJoinRulesEvents: HideInRoomsMode = HideInRoomsMode.Never,

    @Setting(
        title = "Hide history-visibility changes",
        titleKey = MagesSettingsKeys.HIDE_HISTORY_VISIBILITY_CHANGES,
        description = "Who can read room history",
        descriptionKey = MagesSettingsKeys.HIDE_HISTORY_VISIBILITY_CHANGES_DESCRIPTION,
        category = Timeline::class,
        type = Dropdown::class,
        options = ["Never", "Public Rooms", "Non-DMs", "Always"],
        optionsKey = MagesSettingsKeys.HIDE_IN_ROOMS_OPTIONS,
    )
    val compactPublicRoomHistoryVisibilityEvents: HideInRoomsMode = HideInRoomsMode.Never,

    @Setting(
        title = "Hide guest-access changes",
        titleKey = MagesSettingsKeys.HIDE_GUEST_ACCESS_CHANGES,
        description = "Guest access changes",
        descriptionKey = MagesSettingsKeys.HIDE_GUEST_ACCESS_CHANGES_DESCRIPTION,
        category = Timeline::class,
        type = Dropdown::class,
        options = ["Never", "Public Rooms", "Non-DMs", "Always"],
        optionsKey = MagesSettingsKeys.HIDE_IN_ROOMS_OPTIONS,
    )
    val compactPublicRoomGuestAccessEvents: HideInRoomsMode = HideInRoomsMode.Never,

    @Setting(
        title = "Hide server ACL changes",
        titleKey = MagesSettingsKeys.HIDE_SERVER_ACL_CHANGES,
        description = "Server access control list changes",
        descriptionKey = MagesSettingsKeys.HIDE_SERVER_ACL_CHANGES_DESCRIPTION,
        category = Timeline::class,
        type = Dropdown::class,
        options = ["Never", "Public Rooms", "Non-DMs", "Always"],
        optionsKey = MagesSettingsKeys.HIDE_IN_ROOMS_OPTIONS,
    )
    val compactPublicRoomServerAclEvents: HideInRoomsMode = HideInRoomsMode.Never,

    @Setting(
        title = "Hide tombstone events",
        titleKey = MagesSettingsKeys.HIDE_TOMBSTONE_EVENTS,
        description = "Room replacement / upgrade notices",
        descriptionKey = MagesSettingsKeys.HIDE_TOMBSTONE_EVENTS_DESCRIPTION,
        category = Timeline::class,
        type = Dropdown::class,
        options = ["Never", "Public Rooms", "Non-DMs", "Always"],
        optionsKey = MagesSettingsKeys.HIDE_IN_ROOMS_OPTIONS,
    )
    val compactPublicRoomTombstoneEvents: HideInRoomsMode = HideInRoomsMode.Never,

    @Setting(
        title = "Hide space-child events",
        titleKey = MagesSettingsKeys.HIDE_SPACE_CHILD_EVENTS,
        description = "Space child changes",
        descriptionKey = MagesSettingsKeys.HIDE_SPACE_CHILD_EVENTS_DESCRIPTION,
        category = Timeline::class,
        type = Dropdown::class,
        options = ["Never", "Public Rooms", "Non-DMs", "Always"],
        optionsKey = MagesSettingsKeys.HIDE_IN_ROOMS_OPTIONS,
    )
    val compactPublicRoomSpaceChildEvents: HideInRoomsMode = HideInRoomsMode.Never,

    @Setting(
        title = "Hide other state events",
        titleKey = MagesSettingsKeys.HIDE_OTHER_STATE_EVENTS,
        description = "Unknown or uncategorized room state updates",
        descriptionKey = MagesSettingsKeys.HIDE_OTHER_STATE_EVENTS_DESCRIPTION,
        category = Timeline::class,
        type = Dropdown::class,
        options = ["Never", "Public Rooms", "Non-DMs", "Always"],
        optionsKey = MagesSettingsKeys.HIDE_IN_ROOMS_OPTIONS,
    )
    val compactPublicRoomOtherStateEvents: HideInRoomsMode = HideInRoomsMode.Never,

    @Setting(
        title = "Auto-paginate older messages",
        titleKey = MagesSettingsKeys.AUTO_PAGINATE_OLDER_MESSAGES,

        description = "Automatically load older messages when scrolling to top",
        descriptionKey = MagesSettingsKeys.AUTO_PAGINATE_OLDER_MESSAGES_DESCRIPTION,
        category = Timeline::class,
        type = Toggle::class
    )
    val autoBackPagination: Boolean = false,

    @Setting(
        title = "Include silent unread in filter",
        titleKey = MagesSettingsKeys.INCLUDE_SILENT_UNREAD_IN_FILTER,
        description = "When on, the unread filter shows rooms with any unread messages (notifications first). When off, only rooms with notifications are shown.",
        descriptionKey = MagesSettingsKeys.INCLUDE_SILENT_UNREAD_IN_FILTER_DESCRIPTION,
        category = Timeline::class,
        type = Toggle::class,
    )
    val includeSilentUnreadInFilter: Boolean = true,

    @Setting(
        title = "Chat bubbles",
        titleKey = MagesSettingsKeys.CHAT_BUBBLES,
        description = "Open system settings to enable (or disable) conversation bubbles",
        descriptionKey = MagesSettingsKeys.CHAT_BUBBLES_DESCRIPTION,
        category = Notifications::class,
        type = Button::class,
        platforms = [SettingPlatform.ANDROID],
    )
    @ActionHandler(OpenBubbleSettingsAction::class)
    val openBubbleSettings: Unit = Unit,

    @Setting(
        title = "Notification rules",
        titleKey = MagesSettingsKeys.NOTIFICATION_RULES,
        description = "Manage messages, mentions, reactions, invites, and other server-backed notification rules",
        descriptionKey = MagesSettingsKeys.NOTIFICATION_RULES_DESCRIPTION,
        category = Notifications::class,
        type = Button::class,
        dependsOn = "notificationsEnabled",
    )
    @ActionHandler(OpenNotificationRulesAction::class)
    val openNotificationRules: Unit = Unit,

    @Setting(
        title = "Enable notifications",
        titleKey = MagesSettingsKeys.ENABLE_NOTIFICATIONS,
        description = "Show notifications (desktop & web polling + Android push)",
        descriptionKey = MagesSettingsKeys.ENABLE_NOTIFICATIONS_DESCRIPTION,
        category = Notifications::class,
        type = Toggle::class
    )
    val notificationsEnabled: Boolean = true,

    @Setting(
        title = "Request notification permission",
        titleKey = MagesSettingsKeys.REQUEST_NOTIFICATION_PERMISSION,
        description = "Click to enable browser notifications",
        descriptionKey = MagesSettingsKeys.REQUEST_NOTIFICATION_PERMISSION_DESCRIPTION,
        category = Notifications::class,
        type = Button::class,
        platforms = [SettingPlatform.WEB],
    )
    @ActionHandler(RequestNotificationPermissionAction::class)
    val requestNotificationPermission: Unit = Unit,

    @Setting(
        title = "Test browser notification",
        titleKey = MagesSettingsKeys.TEST_BROWSER_NOTIFICATION,
        description = "Click to test if notifications work",
        descriptionKey = MagesSettingsKeys.TEST_BROWSER_NOTIFICATION_DESCRIPTION,
        category = Notifications::class,
        type = Button::class,
        platforms = [SettingPlatform.WEB],
    )
    @ActionHandler(TestNotificationAction::class)
    val testNotification: Unit = Unit,

    @Setting(
        title = "Show message preview",
        titleKey = MagesSettingsKeys.SHOW_MESSAGE_PREVIEW,
        description = "Show message content in notifications",
        descriptionKey = MagesSettingsKeys.SHOW_MESSAGE_PREVIEW_DESCRIPTION,
        category = Notifications::class,
        type = Toggle::class,
        dependsOn = "notificationsEnabled"
    )
    val notificationShowPreview: Boolean = true,

    @Setting(
        title = "Vibrate",
        titleKey = MagesSettingsKeys.VIBRATE,
        description = "Vibrate on notification",
        descriptionKey = MagesSettingsKeys.VIBRATE_DESCRIPTION,
        category = Notifications::class,
        type = Toggle::class,
        dependsOn = "notificationsEnabled",
        platforms = [SettingPlatform.ANDROID]
    )
    val notificationVibrate: Boolean = true,

    @Setting(
        title = "Notification sound",
        titleKey = MagesSettingsKeys.NOTIFICATION_SOUND,
        description = "Play sound for notifications (platform support varies)",
        descriptionKey = MagesSettingsKeys.NOTIFICATION_SOUND_DESCRIPTION,
        category = Notifications::class,
        type = Toggle::class,
        dependsOn = "notificationsEnabled"
    )
    val notificationSound: Boolean = true,

    @Setting(
        title = "Sound once per room",
        titleKey = MagesSettingsKeys.SOUND_ONCE_PER_ROOM,
        description = "Only play sound for first message until you check the room",
        descriptionKey = MagesSettingsKeys.SOUND_ONCE_PER_ROOM_DESCRIPTION,
        category = Notifications::class,
        type = Toggle::class,
        dependsOn = "notificationSound"
    )
    val notifySoundOncePerRoom: Boolean = false,

    @Setting(
        title = "Quiet hours",
        titleKey = MagesSettingsKeys.QUIET_HOURS,
        description = "Suppress audible notifications during quiet hours",
        descriptionKey = MagesSettingsKeys.QUIET_HOURS_DESCRIPTION,
        category = Notifications::class,
        type = Toggle::class,
        dependsOn = "notificationsEnabled"
    )
    val quietHoursEnabled: Boolean = false,

    @Setting(
        title = "Quiet hours start time", category = Notifications::class,
        titleKey = MagesSettingsKeys.QUIET_HOURS_START_TIME,
        description = "Start time for quiet hours",
        descriptionKey = MagesSettingsKeys.QUIET_HOURS_START_TIME_DESCRIPTION,
        type = TimePickerType::class,
        dependsOn = "quietHoursEnabled"
    )
    val quietHoursStartMinutes: Int = 1380,

    @Setting(
        title = "Quiet hours end time",
        titleKey = MagesSettingsKeys.QUIET_HOURS_END_TIME,
        description = "End time for quiet hours",
        descriptionKey = MagesSettingsKeys.QUIET_HOURS_END_TIME_DESCRIPTION,
        category = Notifications::class,
        type = TimePickerType::class,
        dependsOn = "quietHoursEnabled"
    )
    val quietHoursEndMinutes: Int = 420,

    @Setting(
        title = "Auto-join room invites",
        titleKey = MagesSettingsKeys.AUTO_JOIN_ROOM_INVITES,
        description = "Automatically join rooms when invited",
        descriptionKey = MagesSettingsKeys.AUTO_JOIN_ROOM_INVITES_DESCRIPTION,
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
        title = "Send public read receipts",
        titleKey = MagesSettingsKeys.SEND_PUBLIC_READ_RECEIPTS,
        description = "When enabled, other users can see when you've read their messages",
        descriptionKey = MagesSettingsKeys.SEND_PUBLIC_READ_RECEIPTS_DESCRIPTION,
        category = Privacy::class,
        type = Toggle::class
    )
    val sendReadReceipts: Boolean = true,

    @Setting(
        title = "Send typing indicators",
        titleKey = MagesSettingsKeys.SEND_TYPING_INDICATORS,
        description = "When disabled, Mages will not send typing notifications",
        descriptionKey = MagesSettingsKeys.SEND_TYPING_INDICATORS_DESCRIPTION,
        category = Privacy::class,
        type = Toggle::class
    )
    val sendTypingIndicators: Boolean = true,

    @Setting(
        title = "Presence",
        titleKey = MagesSettingsKeys.PRESENCE,
        description = "Set a global presence status",
        descriptionKey = MagesSettingsKeys.PRESENCE_DESCRIPTION,
        category = Privacy::class,
        type = Dropdown::class,
        options = ["Online", "Offline", "Unavailable"],
        optionsKey = MagesSettingsKeys.PRESENCE_OPTIONS,
    )
    val presence: PresenceMode = PresenceMode.Online,

    @Setting(
        title = "App lock",
        titleKey = MagesSettingsKeys.APP_LOCK,
        description = "Require biometrics or device PIN to open",
        descriptionKey = MagesSettingsKeys.APP_LOCK_DESCRIPTION,
        category = Privacy::class,
        type = Toggle::class,
        platforms = [SettingPlatform.ANDROID],
    )
    val appLockEnabled: Boolean = false,

    @Setting(
        title = "App lock timeout",
        titleKey = MagesSettingsKeys.APP_LOCK_TIMEOUT,
        description = "Lock after this much time in the background",
        descriptionKey = MagesSettingsKeys.APP_LOCK_TIMEOUT_DESCRIPTION,
        category = Privacy::class,
        type = Dropdown::class,
        options = ["Immediate", "One Minute", "Five Minutes", "Thirty Minutes", "One Hour", "Never"],
        optionsKey = MagesSettingsKeys.APP_LOCK_TIMEOUT_OPTIONS,
        dependsOn = "appLockEnabled",
        platforms = [SettingPlatform.ANDROID],
    )
    val appLockTimeout: AppLockTimeout = AppLockTimeout.OneMinute,

    @Setting(
        title = "Screen security",
        titleKey = MagesSettingsKeys.SCREEN_SECURITY,
        description = "Block screenshots and hide content in the app switcher",
        descriptionKey = MagesSettingsKeys.SCREEN_SECURITY_DESCRIPTION,
        category = Privacy::class,
        type = Toggle::class,
        platforms = [SettingPlatform.ANDROID],
    )
    val screenSecurityEnabled: Boolean = false,

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
        titleKey = MagesSettingsKeys.SHOW_CALL_SCREEN,
        description = "Show full-screen incoming call UI",
        descriptionKey = MagesSettingsKeys.SHOW_CALL_SCREEN_DESCRIPTION,
        dependsOn = "callNotificationsEnabled",
        platforms = [SettingPlatform.ANDROID]
    )
    val showIncomingCallScreen: Boolean = true,

    @Setting(
        category = Calls::class,
        type = Toggle::class,
        title = "Call notifications",
        titleKey = MagesSettingsKeys.CALL_NOTIFICATIONS,
        description = "Incoming call notifications",
        descriptionKey = MagesSettingsKeys.CALL_NOTIFICATIONS_DESCRIPTION,
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
        titleKey = MagesSettingsKeys.SELECT_UNIFIED_PUSH_DISTRIBUTOR,
        description = "Choose the app that delivers pushes (gcompat/sunup/ntfy/etc.)",
        descriptionKey = MagesSettingsKeys.SELECT_UNIFIED_PUSH_DISTRIBUTOR_DESCRIPTION,
        platforms = [SettingPlatform.ANDROID],
    )
    @ActionHandler(SelectUnifiedPushDistributorAction::class)
    val selectUnifiedPushDistributor: Unit = Unit,

    @Setting(
        category = Notifications::class,
        type = Button::class,
        title = "Re-register UnifiedPush",
        titleKey = MagesSettingsKeys.RE_REGISTER_UNIFIED_PUSH,
        description = "Fix push issues after update/reboot or distributor change",
        descriptionKey = MagesSettingsKeys.RE_REGISTER_UNIFIED_PUSH_DESCRIPTION,
        platforms = [SettingPlatform.ANDROID],
    )
    @ActionHandler(ReRegisterUnifiedPushAction::class)
    val reRegisterUnifiedPush: Unit = Unit,

    @Setting(
        category = Notifications::class,
        type = Button::class,
        title = "Copy UnifiedPush endpoint",
        titleKey = MagesSettingsKeys.COPY_UNIFIED_PUSH_ENDPOINT,
        description = "For debugging; shows what endpoint is registered",
        descriptionKey = MagesSettingsKeys.COPY_UNIFIED_PUSH_ENDPOINT_DESCRIPTION,
        platforms = [SettingPlatform.ANDROID],
    )
    @ActionHandler(CopyUnifiedPushEndpointAction::class)
    val copyUnifiedPushEndpoint: Unit = Unit,

    @Setting(
        title = "Block media previews",
        titleKey = MagesSettingsKeys.BLOCK_MEDIA_PREVIEWS,
        description = "Don't auto-download thumbnails/previews",
        descriptionKey = MagesSettingsKeys.BLOCK_MEDIA_PREVIEWS_DESCRIPTION,
        category = Storage::class,
        type = Toggle::class
    )
    val blockMediaPreviews: Boolean = false,

    @Setting(
        title = "Media Cache",
        titleKey = MagesSettingsKeys.MEDIA_CACHE,
        description = "View and manage cached media",
        descriptionKey = MagesSettingsKeys.MEDIA_CACHE_DESCRIPTION,
        category = Storage::class,
        type = Button::class,
        platforms = [SettingPlatform.ANDROID, SettingPlatform.JVM]
    )
    @ActionHandler(OpenMediaCacheAction::class)
    val openMediaCache: Unit = Unit,

    @Setting(
        title = "Start in tray",
        titleKey = MagesSettingsKeys.START_IN_TRAY,
        description = "Minimize to tray on launch",
        descriptionKey = MagesSettingsKeys.START_IN_TRAY_DESCRIPTION,
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
        titleKey = MagesSettingsKeys.ENTER_SENDS_MESSAGE,
        description = "When enabled, pressing Enter will send the message and Shift, Ctrl or Cmd + Enter will insert a new line",
        descriptionKey = MagesSettingsKeys.ENTER_SENDS_MESSAGE_DESCRIPTION,
        category = Advanced::class,
        type = Toggle::class
    )
    val enterSendsMessage: Boolean = false,

    @Setting(
        title = "Use proxy",
        titleKey = MagesSettingsKeys.USE_PROXY,
        description = "Route all traffic through a SOCKS5 or HTTP proxy",
        descriptionKey = MagesSettingsKeys.USE_PROXY_DESCRIPTION,
        category = Advanced::class,
        type = Toggle::class,
        platforms = [SettingPlatform.ANDROID, SettingPlatform.JVM],
    )
    val proxyEnabled: Boolean = false,

    @Setting(
        title = "Proxy URL",
        titleKey = MagesSettingsKeys.PROXY_URL,
        description = "SOCKS5 or HTTP proxy address",
        descriptionKey = MagesSettingsKeys.PROXY_URL_DESCRIPTION,
        category = Advanced::class,
        type = TextInput::class,
        dependsOn = "proxyEnabled",
        platforms = [SettingPlatform.ANDROID, SettingPlatform.JVM],
    )
    val proxyUrl: String = "",

    @Setting(
        title = "Live location update interval",
        titleKey = MagesSettingsKeys.LIVE_LOCATION_UPDATE_INTERVAL,
        description = "Minimum distance (in meters) traveled before sending a location update",
        descriptionKey = MagesSettingsKeys.LIVE_LOCATION_UPDATE_INTERVAL_DESCRIPTION,
        category = Advanced::class,
        type = Slider::class,
        min = 0f,
        max = 100f,
        step = 5f,
        platforms = [SettingPlatform.ANDROID],
    )
    val liveLocationMinDistanceMeters: Float = 10f,
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
