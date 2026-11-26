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

object ReactionEmojiPicker : SettingTypeMarker

@Serializable
enum class ThemeMode { System, Light, Dark }

@Serializable
enum class AppFontFamily { System, GoogleSansFlex }

@Serializable
enum class PresenceMode { Online, Offline, Unavailable }

@Serializable
enum class MediaPreviewsMode { On, Private, Off }

@Serializable
enum class LinkPreviewsMode { On, UnencryptedOnly, Off }

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
enum class DeletedMessageRetention { Never, OneMinute, FiveMinutes, FifteenMinutes, OneHour }

fun DeletedMessageRetention.toRetentionMs(): Long? = when (this) {
    DeletedMessageRetention.Never -> null
    DeletedMessageRetention.OneMinute -> 60_000L
    DeletedMessageRetention.FiveMinutes -> 300_000L
    DeletedMessageRetention.FifteenMinutes -> 900_000L
    DeletedMessageRetention.OneHour -> 3_600_000L
}

@Serializable
enum class RoomSwipeAction { MarkRead, MarkUnread, Nothing, ToggleUnread }

@Serializable
enum class MessageSwipeAction { Reply, React, Pin, Copy, Nothing }

@Serializable
enum class MessageDoubleTapAction { React, Reply, Pin, Copy, Nothing }

@Serializable
enum class MessageRightClickAction { Menu, Copy, Reply, Pin, Nothing }

@Serializable
enum class AutoMarkReadTrigger { OnSend, WhileOpen, OnRoomClose, Never }

@Serializable
enum class ThreadUnreadIndicator { Off, Dot, Count }

@Serializable
data class AppSettings(
    @Persisted
    val homeserver: String = "https://matrix.org",

    @Persisted
    val accountsJson: String = "",

    @Persisted
    val activeAccountId: String? = null,

    @Setting(
        titleKey = MagesSettingsKeys.SHOW_SWITCH_ACCOUNT_BUTTON,
        descriptionKey = MagesSettingsKeys.SHOW_SWITCH_ACCOUNT_BUTTON_DESCRIPTION,
        category = Appearance::class,
        type = Toggle::class,
    )
    val showSwitchAccountButton: Boolean = false,

    @Setting(
        titleKey = MagesSettingsKeys.THEME_MODE,
        descriptionKey = MagesSettingsKeys.THEME_MODE_DESCRIPTION,
        category = Appearance::class,
        type = Dropdown::class,
        optionsKey = MagesSettingsKeys.THEME_OPTIONS,
    )
    val themeMode: ThemeMode = ThemeMode.System,

    @Setting(
        titleKey = MagesSettingsKeys.DYNAMIC_COLORS,
        descriptionKey = MagesSettingsKeys.DYNAMIC_COLORS_DESCRIPTION,
        category = Appearance::class,
        type = Toggle::class,
        platforms = [SettingPlatform.ANDROID]
    )
    val dynamicColors: Boolean = false,

    @Setting(
        titleKey = MagesSettingsKeys.OLED_BLACK,
        descriptionKey = MagesSettingsKeys.OLED_BLACK_DESCRIPTION,
        category = Appearance::class,
        type = Toggle::class,
        key = "oled_black"
    )
    val oledBlack: Boolean = false,

    @Setting(
        titleKey = MagesSettingsKeys.USE_24_HOUR_TIME,
        descriptionKey = MagesSettingsKeys.USE_24_HOUR_TIME_DESCRIPTION,
        category = Appearance::class,
        type = Toggle::class,
    )
    val use24HourTime: Boolean = true,

    @Setting(
        titleKey = MagesSettingsKeys.CHAT_WALLPAPER,
        descriptionKey = MagesSettingsKeys.CHAT_WALLPAPER_DESCRIPTION,
        category = Appearance::class,
        type = Button::class,
    )
    @ActionHandler(OpenWallpaperAction::class)
    val openWallpaper: Unit = Unit,

    @Setting(
        titleKey = MagesSettingsKeys.LANGUAGE,
        descriptionKey = MagesSettingsKeys.LANGUAGE_DESCRIPTION,
        category = Appearance::class,
        type = Dropdown::class,
        optionsKey = MagesSettingsKeys.LANGUAGE_OPTIONS,
    )
    val language: AppLanguage = AppLanguage.System,

    @Setting(
        titleKey = MagesSettingsKeys.APP_FONT,
        descriptionKey = MagesSettingsKeys.APP_FONT_DESCRIPTION,
        category = Appearance::class,
        type = Dropdown::class,
        optionsKey = MagesSettingsKeys.APP_FONT_OPTIONS,
    )
    val appFont: AppFontFamily = AppFontFamily.System,

    @Setting(
        titleKey = MagesSettingsKeys.APP_FONT_WIDTH,
        descriptionKey = MagesSettingsKeys.APP_FONT_WIDTH_DESCRIPTION,
        category = Appearance::class,
        type = Slider::class,
        min = 85f,
        max = 115f,
        step = 5f,
        dependsOn = "appFont",
        dependsOnValue = "GoogleSansFlex",
    )
    val appFontWidth: Float = 100f,

    @Setting(
        titleKey = MagesSettingsKeys.APP_FONT_ROUNDNESS,
        descriptionKey = MagesSettingsKeys.APP_FONT_ROUNDNESS_DESCRIPTION,
        category = Appearance::class,
        type = Slider::class,
        min = 0f,
        max = 100f,
        step = 25f,
        dependsOn = "appFont",
        dependsOnValue = "GoogleSansFlex",
    )
    val appFontRoundness: Float = 0f,

    @Setting(
        titleKey = MagesSettingsKeys.FONT_SIZE,
        descriptionKey = MagesSettingsKeys.FONT_SIZE_DESCRIPTION,
        category = Appearance::class,
        type = Slider::class,
        min = 12f,
        max = 24f,
        step = 1f
    )
    val fontSize: Float = 16f,

    @Setting(
        titleKey = MagesSettingsKeys.UI_SCALE,
        descriptionKey = MagesSettingsKeys.UI_SCALE_DESCRIPTION,
        category = Appearance::class,
        type = Dropdown::class,
        options = ["1", "2", "3", "4"],
        platforms = [SettingPlatform.JVM],
    )
    val uiScaleIndex: Int = 0,

    @Setting(
        titleKey = MagesSettingsKeys.DRAG_TO_SCROLL,
        descriptionKey = MagesSettingsKeys.DRAG_TO_SCROLL_DESCRIPTION,
        category = Appearance::class,
        type = Toggle::class,
        platforms = [SettingPlatform.JVM],
    )
    val dragToScroll: Boolean = false,

    @Setting(
        titleKey = MagesSettingsKeys.SHOW_MESSAGE_AVATARS,
        descriptionKey = MagesSettingsKeys.SHOW_MESSAGE_AVATARS_DESCRIPTION,
        category = Appearance::class,
        type = Toggle::class
    )
    val showMessageAvatars: Boolean = true,

    @Setting(
        titleKey = MagesSettingsKeys.SHOW_SENDER_ROLE,
        descriptionKey = MagesSettingsKeys.SHOW_SENDER_ROLE_DESCRIPTION,
        category = Appearance::class,
        type = Toggle::class
    )
    val showSenderRole: Boolean = false,

    @Setting(
        titleKey = MagesSettingsKeys.SHOW_REACTION_AVATARS,
        descriptionKey = MagesSettingsKeys.SHOW_REACTION_AVATARS_DESCRIPTION,
        category = Appearance::class,
        type = Toggle::class
    )
    val showReactionAvatars: Boolean = false,

    @Setting(
        titleKey = MagesSettingsKeys.SHOW_THREAD_REPLY_COUNT,
        category = Appearance::class,
        type = Toggle::class
    )
    val showThreadReplyCount: Boolean = false,

    @Setting(
        titleKey = MagesSettingsKeys.THREAD_UNREAD_INDICATOR,
        category = Appearance::class,
        type = Dropdown::class,
        optionsKey = MagesSettingsKeys.THREAD_UNREAD_INDICATOR_OPTIONS,
    )
    val threadUnreadIndicator: ThreadUnreadIndicator = ThreadUnreadIndicator.Off,

    @Setting(
        titleKey = MagesSettingsKeys.SHOW_READ_RECEIPT_AVATARS,
        descriptionKey = MagesSettingsKeys.SHOW_READ_RECEIPT_AVATARS_DESCRIPTION,
        category = Appearance::class,
        type = Toggle::class
    )
    val showReadReceiptAvatars: Boolean = true,

    @Setting(
        titleKey = MagesSettingsKeys.SHOW_USERNAME_IN_DMS,
        descriptionKey = MagesSettingsKeys.SHOW_USERNAME_IN_DMS_DESCRIPTION,
        category = Appearance::class,
        type = Toggle::class
    )
    val showUsernameInDms: Boolean = false,

    @Setting(
        titleKey = MagesSettingsKeys.BUBBLE_ANIMATIONS,
        descriptionKey = MagesSettingsKeys.BUBBLE_ANIMATIONS_DESCRIPTION,
        category = Appearance::class,
        type = Toggle::class,
    )
    val bubbleAnimations: Boolean = false,

    @Setting(
        titleKey = MagesSettingsKeys.REDUCE_MOTION,
        descriptionKey = MagesSettingsKeys.REDUCE_MOTION_DESCRIPTION,
        category = Appearance::class,
        type = Toggle::class,
    )
    val reduceMotion: Boolean = false,

    @Setting(
        titleKey = MagesSettingsKeys.HIDE_MEMBERSHIP_EVENTS,
        descriptionKey = MagesSettingsKeys.HIDE_MEMBERSHIP_EVENTS_DESCRIPTION,
        category = Timeline::class,
        type = Dropdown::class,
        optionsKey = MagesSettingsKeys.HIDE_IN_ROOMS_OPTIONS,
    )
    val compactPublicRoomMembershipEvents: HideInRoomsMode = HideInRoomsMode.PublicRooms,

    @Setting(
        titleKey = MagesSettingsKeys.HIDE_PROFILE_CHANGES,
        descriptionKey = MagesSettingsKeys.HIDE_PROFILE_CHANGES_DESCRIPTION,
        category = Timeline::class,
        type = Dropdown::class,
        optionsKey = MagesSettingsKeys.HIDE_IN_ROOMS_OPTIONS,
    )
    val compactPublicRoomProfileChangeEvents: HideInRoomsMode = HideInRoomsMode.PublicRooms,

    @Setting(
        titleKey = MagesSettingsKeys.HIDE_TOPIC_CHANGES,
        descriptionKey = MagesSettingsKeys.HIDE_TOPIC_CHANGES_DESCRIPTION,
        category = Timeline::class,
        type = Dropdown::class,
        optionsKey = MagesSettingsKeys.HIDE_IN_ROOMS_OPTIONS,
    )
    val compactPublicRoomTopicEvents: HideInRoomsMode = HideInRoomsMode.PublicRooms,

    @Setting(
        titleKey = MagesSettingsKeys.HIDE_REDACTED_EVENTS,
        descriptionKey = MagesSettingsKeys.HIDE_REDACTED_EVENTS_DESCRIPTION,
        category = Timeline::class,
        type = Dropdown::class,
        optionsKey = MagesSettingsKeys.HIDE_IN_ROOMS_OPTIONS,
    )
    val compactPublicRoomRedactedEvents: HideInRoomsMode = HideInRoomsMode.Never,

    @Setting(
        titleKey = MagesSettingsKeys.HIDE_DELETED_MESSAGES_AFTER,
        descriptionKey = MagesSettingsKeys.HIDE_DELETED_MESSAGES_AFTER_DESCRIPTION,
        category = Timeline::class,
        type = Dropdown::class,
        optionsKey = MagesSettingsKeys.HIDE_DELETED_AFTER_OPTIONS,
    )
    val hideDeletedMessagesAfter: DeletedMessageRetention = DeletedMessageRetention.Never,

    @Setting(
        titleKey = MagesSettingsKeys.HIDE_ROOM_NAME_CHANGES,
        descriptionKey = MagesSettingsKeys.HIDE_ROOM_NAME_CHANGES_DESCRIPTION,
        category = Timeline::class,
        type = Dropdown::class,
        optionsKey = MagesSettingsKeys.HIDE_IN_ROOMS_OPTIONS,
    )
    val compactPublicRoomRoomNameEvents: HideInRoomsMode = HideInRoomsMode.Never,

    @Setting(
        titleKey = MagesSettingsKeys.HIDE_ROOM_AVATAR_CHANGES,
        descriptionKey = MagesSettingsKeys.HIDE_ROOM_AVATAR_CHANGES_DESCRIPTION,
        category = Timeline::class,
        type = Dropdown::class,
        optionsKey = MagesSettingsKeys.HIDE_IN_ROOMS_OPTIONS,
    )
    val compactPublicRoomRoomAvatarEvents: HideInRoomsMode = HideInRoomsMode.Never,

    @Setting(
        titleKey = MagesSettingsKeys.HIDE_ENCRYPTION_CHANGES,
        descriptionKey = MagesSettingsKeys.HIDE_ENCRYPTION_CHANGES_DESCRIPTION,
        category = Timeline::class,
        type = Dropdown::class,
        optionsKey = MagesSettingsKeys.HIDE_IN_ROOMS_OPTIONS,
    )
    val compactPublicRoomRoomEncryptionEvents: HideInRoomsMode = HideInRoomsMode.Never,

    @Setting(
        titleKey = MagesSettingsKeys.HIDE_PINNED_EVENT_UPDATES,
        descriptionKey = MagesSettingsKeys.HIDE_PINNED_EVENT_UPDATES_DESCRIPTION,
        category = Timeline::class,
        type = Dropdown::class,
        optionsKey = MagesSettingsKeys.HIDE_IN_ROOMS_OPTIONS,
    )
    val compactPublicRoomRoomPinnedEvents: HideInRoomsMode = HideInRoomsMode.Never,

    @Setting(
        titleKey = MagesSettingsKeys.HIDE_POWER_LEVEL_CHANGES,
        descriptionKey = MagesSettingsKeys.HIDE_POWER_LEVEL_CHANGES_DESCRIPTION,
        category = Timeline::class,
        type = Dropdown::class,
        optionsKey = MagesSettingsKeys.HIDE_IN_ROOMS_OPTIONS,
    )
    val compactPublicRoomRoomPowerLevelsEvents: HideInRoomsMode = HideInRoomsMode.Never,

    @Setting(
        titleKey = MagesSettingsKeys.HIDE_CANONICAL_ALIAS_CHANGES,
        descriptionKey = MagesSettingsKeys.HIDE_CANONICAL_ALIAS_CHANGES_DESCRIPTION,
        category = Timeline::class,
        type = Dropdown::class,
        optionsKey = MagesSettingsKeys.HIDE_IN_ROOMS_OPTIONS,
    )
    val compactPublicRoomRoomCanonicalAliasEvents: HideInRoomsMode = HideInRoomsMode.Never,

    @Setting(
        titleKey = MagesSettingsKeys.HIDE_JOIN_RULE_CHANGES,
        descriptionKey = MagesSettingsKeys.HIDE_JOIN_RULE_CHANGES_DESCRIPTION,
        category = Timeline::class,
        type = Dropdown::class,
        optionsKey = MagesSettingsKeys.HIDE_IN_ROOMS_OPTIONS,
    )
    val compactPublicRoomJoinRulesEvents: HideInRoomsMode = HideInRoomsMode.Never,

    @Setting(
        titleKey = MagesSettingsKeys.HIDE_HISTORY_VISIBILITY_CHANGES,
        descriptionKey = MagesSettingsKeys.HIDE_HISTORY_VISIBILITY_CHANGES_DESCRIPTION,
        category = Timeline::class,
        type = Dropdown::class,
        optionsKey = MagesSettingsKeys.HIDE_IN_ROOMS_OPTIONS,
    )
    val compactPublicRoomHistoryVisibilityEvents: HideInRoomsMode = HideInRoomsMode.Never,

    @Setting(
        titleKey = MagesSettingsKeys.HIDE_GUEST_ACCESS_CHANGES,
        descriptionKey = MagesSettingsKeys.HIDE_GUEST_ACCESS_CHANGES_DESCRIPTION,
        category = Timeline::class,
        type = Dropdown::class,
        optionsKey = MagesSettingsKeys.HIDE_IN_ROOMS_OPTIONS,
    )
    val compactPublicRoomGuestAccessEvents: HideInRoomsMode = HideInRoomsMode.Never,

    @Setting(
        titleKey = MagesSettingsKeys.HIDE_SERVER_ACL_CHANGES,
        descriptionKey = MagesSettingsKeys.HIDE_SERVER_ACL_CHANGES_DESCRIPTION,
        category = Timeline::class,
        type = Dropdown::class,
        optionsKey = MagesSettingsKeys.HIDE_IN_ROOMS_OPTIONS,
    )
    val compactPublicRoomServerAclEvents: HideInRoomsMode = HideInRoomsMode.Never,

    @Setting(
        titleKey = MagesSettingsKeys.HIDE_TOMBSTONE_EVENTS,
        descriptionKey = MagesSettingsKeys.HIDE_TOMBSTONE_EVENTS_DESCRIPTION,
        category = Timeline::class,
        type = Dropdown::class,
        optionsKey = MagesSettingsKeys.HIDE_IN_ROOMS_OPTIONS,
    )
    val compactPublicRoomTombstoneEvents: HideInRoomsMode = HideInRoomsMode.Never,

    @Setting(
        titleKey = MagesSettingsKeys.HIDE_SPACE_CHILD_EVENTS,
        descriptionKey = MagesSettingsKeys.HIDE_SPACE_CHILD_EVENTS_DESCRIPTION,
        category = Timeline::class,
        type = Dropdown::class,
        optionsKey = MagesSettingsKeys.HIDE_IN_ROOMS_OPTIONS,
    )
    val compactPublicRoomSpaceChildEvents: HideInRoomsMode = HideInRoomsMode.Never,

    @Setting(
        titleKey = MagesSettingsKeys.HIDE_OTHER_STATE_EVENTS,
        descriptionKey = MagesSettingsKeys.HIDE_OTHER_STATE_EVENTS_DESCRIPTION,
        category = Timeline::class,
        type = Dropdown::class,
        optionsKey = MagesSettingsKeys.HIDE_IN_ROOMS_OPTIONS,
    )
    val compactPublicRoomOtherStateEvents: HideInRoomsMode = HideInRoomsMode.Never,

    @Setting(
        titleKey = MagesSettingsKeys.AUTO_PAGINATE_OLDER_MESSAGES,
        descriptionKey = MagesSettingsKeys.AUTO_PAGINATE_OLDER_MESSAGES_DESCRIPTION,
        category = Timeline::class,
        type = Toggle::class
    )
    val autoBackPagination: Boolean = false,

    @Setting(
        titleKey = MagesSettingsKeys.INCLUDE_SILENT_UNREAD_IN_FILTER,
        descriptionKey = MagesSettingsKeys.INCLUDE_SILENT_UNREAD_IN_FILTER_DESCRIPTION,
        category = Timeline::class,
        type = Toggle::class,
    )
    val includeSilentUnreadInFilter: Boolean = false,

    @Setting(
        titleKey = MagesSettingsKeys.SHOW_SPACE_BADGE_IN_ROOM_LIST,
        descriptionKey = MagesSettingsKeys.SHOW_SPACE_BADGE_IN_ROOM_LIST_DESCRIPTION,
        category = Timeline::class,
        type = Toggle::class,
    )
    val showSpaceBadgeInRoomList: Boolean = false,

    @Setting(
        titleKey = MagesSettingsKeys.HIDE_SPACE_ROOMS_IN_ROOM_LIST,
        category = Timeline::class,
        type = Toggle::class,
    )
    val hideSpaceRoomsInRoomList: Boolean = false,

    @Setting(
        titleKey = MagesSettingsKeys.ROOM_LIST_SWIPE_RIGHT,
        descriptionKey = MagesSettingsKeys.ROOM_LIST_SWIPE_RIGHT_DESCRIPTION,
        category = Timeline::class,
        type = Dropdown::class,
        optionsKey = MagesSettingsKeys.ROOM_LIST_SWIPE_OPTIONS,
    )
    val swipeRightAction: RoomSwipeAction = RoomSwipeAction.Nothing,

    @Setting(
        titleKey = MagesSettingsKeys.ROOM_LIST_SWIPE_LEFT,
        descriptionKey = MagesSettingsKeys.ROOM_LIST_SWIPE_LEFT_DESCRIPTION,
        category = Timeline::class,
        type = Dropdown::class,
        optionsKey = MagesSettingsKeys.ROOM_LIST_SWIPE_OPTIONS,
    )
    val swipeLeftAction: RoomSwipeAction = RoomSwipeAction.Nothing,

    @Setting(
        titleKey = MagesSettingsKeys.MESSAGE_SWIPE_RIGHT,
        descriptionKey = MagesSettingsKeys.MESSAGE_SWIPE_RIGHT_DESCRIPTION,
        category = Timeline::class,
        type = Dropdown::class,
        optionsKey = MagesSettingsKeys.MESSAGE_SWIPE_OPTIONS,
    )
    val messageSwipeRightAction: MessageSwipeAction = MessageSwipeAction.Reply,

    @Setting(
        titleKey = MagesSettingsKeys.MESSAGE_SWIPE_LEFT,
        descriptionKey = MagesSettingsKeys.MESSAGE_SWIPE_LEFT_DESCRIPTION,
        category = Timeline::class,
        type = Dropdown::class,
        optionsKey = MagesSettingsKeys.MESSAGE_SWIPE_OPTIONS,
    )
    val messageSwipeLeftAction: MessageSwipeAction = MessageSwipeAction.Nothing,

    @Setting(
        titleKey = MagesSettingsKeys.MESSAGE_DOUBLE_TAP,
        descriptionKey = MagesSettingsKeys.MESSAGE_DOUBLE_TAP_DESCRIPTION,
        category = Timeline::class,
        type = Dropdown::class,
        optionsKey = MagesSettingsKeys.MESSAGE_DOUBLE_TAP_OPTIONS,
    )
    val messageDoubleTapAction: MessageDoubleTapAction = MessageDoubleTapAction.React,

    @Setting(
        titleKey = MagesSettingsKeys.MESSAGE_DOUBLE_TAP_REACTION,
        descriptionKey = MagesSettingsKeys.MESSAGE_DOUBLE_TAP_REACTION_DESCRIPTION,
        category = Timeline::class,
        type = ReactionEmojiPicker::class,
        dependsOn = "messageDoubleTapAction",
        dependsOnValue = "React",
    )
    val messageDoubleTapReaction: String = "👍",

    @Setting(
        titleKey = MagesSettingsKeys.MESSAGE_RIGHT_CLICK,
        descriptionKey = MagesSettingsKeys.MESSAGE_RIGHT_CLICK_DESCRIPTION,
        category = Timeline::class,
        type = Dropdown::class,
        optionsKey = MagesSettingsKeys.MESSAGE_RIGHT_CLICK_OPTIONS,
        platforms = [SettingPlatform.JVM, SettingPlatform.WEB],
    )
    val messageRightClickAction: MessageRightClickAction = MessageRightClickAction.Menu,

    @Setting(
        titleKey = MagesSettingsKeys.MESSAGE_MENU_ORDER,
        descriptionKey = MagesSettingsKeys.MESSAGE_MENU_ORDER_DESCRIPTION,
        category = Timeline::class,
        type = Button::class,
    )
    @ActionHandler(OpenMessageMenuSettingsAction::class)
    val openMessageMenuSettings: Unit = Unit,

    @Persisted
    val messageMenuOrder: String = "",

    @Setting(
        titleKey = MagesSettingsKeys.QUICK_REACTIONS,
        descriptionKey = MagesSettingsKeys.QUICK_REACTIONS_DESCRIPTION,
        category = Timeline::class,
        type = Button::class,
    )
    @ActionHandler(OpenQuickReactionsAction::class)
    val openQuickReactionsSettings: Unit = Unit,

    @Persisted
    val quickReactions: String = "",

    @Setting(
        titleKey = MagesSettingsKeys.SPLIT_PANES,
        descriptionKey = MagesSettingsKeys.SPLIT_PANES_DESCRIPTION,
        category = Appearance::class,
        type = Toggle::class,
    )
    val splitPanes: Boolean = true,

    @Setting(
        titleKey = MagesSettingsKeys.SPLIT_ROOM_LIST_PERCENT,
        descriptionKey = MagesSettingsKeys.SPLIT_ROOM_LIST_PERCENT_DESCRIPTION,
        category = Appearance::class,
        type = Slider::class,
        min = 15f,
        max = 50f,
        step = 5f,
        dependsOn = "splitPanes",
    )
    val splitRoomListPercent: Int = 40,

    @Setting(
        titleKey = MagesSettingsKeys.SORT_BY_UNREAD,
        descriptionKey = MagesSettingsKeys.SORT_BY_UNREAD_DESCRIPTION,
        category = Timeline::class,
        type = Toggle::class,
    )
    val sortByUnread: Boolean = false,

    @Setting(
        titleKey = MagesSettingsKeys.SHOW_SILENT_UNREAD_COUNT,
        descriptionKey = MagesSettingsKeys.SHOW_SILENT_UNREAD_COUNT_DESCRIPTION,
        category = Timeline::class,
        type = Toggle::class,
    )
    val showSilentUnreadCount: Boolean = true,

    @Setting(
        titleKey = MagesSettingsKeys.EXCLUDE_LOW_PRIORITY_FROM_UNREAD_BADGE,
        descriptionKey = MagesSettingsKeys.EXCLUDE_LOW_PRIORITY_FROM_UNREAD_BADGE_DESCRIPTION,
        category = Timeline::class,
        type = Toggle::class,
    )
    val excludeLowPriorityFromUnreadBadge: Boolean = true,

    @Setting(
        titleKey = MagesSettingsKeys.OPEN_AT_UNREAD,
        descriptionKey = MagesSettingsKeys.OPEN_AT_UNREAD_DESCRIPTION,
        category = Timeline::class,
        type = Toggle::class,
    )
    val openAtUnread: Boolean = true,

    @Setting(
        titleKey = MagesSettingsKeys.FLOATING_DATE,
        descriptionKey = MagesSettingsKeys.FLOATING_DATE_DESCRIPTION,
        category = Timeline::class,
        type = Toggle::class,
    )
    val floatingDate: Boolean = true,

    @Setting(
        titleKey = MagesSettingsKeys.AUTO_MARK_READ_TRIGGER,
        descriptionKey = MagesSettingsKeys.AUTO_MARK_READ_TRIGGER_DESCRIPTION,
        category = Timeline::class,
        type = Dropdown::class,
        optionsKey = MagesSettingsKeys.AUTO_MARK_READ_TRIGGER_OPTIONS,
    )
    val autoMarkReadTrigger: AutoMarkReadTrigger = AutoMarkReadTrigger.WhileOpen,

    @Setting(
        titleKey = MagesSettingsKeys.COMPOSER_MAX_LINES,
        descriptionKey = MagesSettingsKeys.COMPOSER_MAX_LINES_DESCRIPTION,
        category = Appearance::class,
        type = Slider::class,
        min = 1f,
        max = 20f,
        step = 1f,
    )
    val composerMaxLines: Int = 6,

    @Setting(
        titleKey = MagesSettingsKeys.COMPOSER_MAX_LINES_UNFOCUSED,
        descriptionKey = MagesSettingsKeys.COMPOSER_MAX_LINES_UNFOCUSED_DESCRIPTION,
        category = Appearance::class,
        type = Slider::class,
        min = 1f,
        max = 20f,
        step = 1f,
    )
    val composerMaxLinesUnfocused: Int = 2,

    @Setting(
        titleKey = MagesSettingsKeys.SKIKO_VSYNC,
        descriptionKey = MagesSettingsKeys.SKIKO_VSYNC_DESCRIPTION,
        category = Advanced::class,
        type = Toggle::class,
        platforms = [SettingPlatform.JVM],
    )
    val skikoVsync: Boolean = true,

    @Setting(
        titleKey = MagesSettingsKeys.HIDE_WINDOW_DECORATION,
        descriptionKey = MagesSettingsKeys.HIDE_WINDOW_DECORATION_DESCRIPTION,
        category = Appearance::class,
        type = Toggle::class,
        platforms = [SettingPlatform.JVM],
    )
    val hideWindowDecoration: Boolean = false,

    @Setting(
        titleKey = MagesSettingsKeys.WINDOW_BACKGROUND_ALPHA,
        descriptionKey = MagesSettingsKeys.WINDOW_BACKGROUND_ALPHA_DESCRIPTION,
        category = Appearance::class,
        type = Slider::class,
        min = 0.3f,
        max = 1f,
        step = 0.05f,
        platforms = [SettingPlatform.JVM],
    )
    val windowBackgroundAlpha: Float = 1f,

    @Setting(
        titleKey = MagesSettingsKeys.CHAT_BUBBLES,
        descriptionKey = MagesSettingsKeys.CHAT_BUBBLES_DESCRIPTION,
        category = Notifications::class,
        type = Button::class,
        platforms = [SettingPlatform.ANDROID],
    )
    @ActionHandler(OpenBubbleSettingsAction::class)
    val openBubbleSettings: Unit = Unit,

    @Setting(
        titleKey = MagesSettingsKeys.NOTIFICATION_RULES,
        descriptionKey = MagesSettingsKeys.NOTIFICATION_RULES_DESCRIPTION,
        category = Notifications::class,
        type = Button::class,
        dependsOn = "notificationsEnabled",
    )
    @ActionHandler(OpenNotificationRulesAction::class)
    val openNotificationRules: Unit = Unit,

    @Setting(
        titleKey = MagesSettingsKeys.ENABLE_NOTIFICATIONS,
        descriptionKey = MagesSettingsKeys.ENABLE_NOTIFICATIONS_DESCRIPTION,
        category = Notifications::class,
        type = Toggle::class
    )
    val notificationsEnabled: Boolean = true,

    @Setting(
        titleKey = MagesSettingsKeys.REQUEST_NOTIFICATION_PERMISSION,
        descriptionKey = MagesSettingsKeys.REQUEST_NOTIFICATION_PERMISSION_DESCRIPTION,
        category = Notifications::class,
        type = Button::class,
        platforms = [SettingPlatform.WEB],
    )
    @ActionHandler(RequestNotificationPermissionAction::class)
    val requestNotificationPermission: Unit = Unit,

    @Setting(
        titleKey = MagesSettingsKeys.TEST_BROWSER_NOTIFICATION,
        descriptionKey = MagesSettingsKeys.TEST_BROWSER_NOTIFICATION_DESCRIPTION,
        category = Notifications::class,
        type = Button::class,
        platforms = [SettingPlatform.WEB],
    )
    @ActionHandler(TestNotificationAction::class)
    val testNotification: Unit = Unit,

    @Setting(
        titleKey = MagesSettingsKeys.SHOW_MESSAGE_PREVIEW,
        descriptionKey = MagesSettingsKeys.SHOW_MESSAGE_PREVIEW_DESCRIPTION,
        category = Notifications::class,
        type = Toggle::class,
        dependsOn = "notificationsEnabled"
    )
    val notificationShowPreview: Boolean = true,

    @Setting(
        titleKey = MagesSettingsKeys.SHOW_MEDIA_PREVIEW,
        descriptionKey = MagesSettingsKeys.SHOW_MEDIA_PREVIEW_DESCRIPTION,
        category = Notifications::class,
        type = Toggle::class,
        dependsOn = "notificationShowPreview"
    )
    val notificationShowMedia: Boolean = true,

    @Setting(
        titleKey = MagesSettingsKeys.VIBRATE,
        descriptionKey = MagesSettingsKeys.VIBRATE_DESCRIPTION,
        category = Notifications::class,
        type = Toggle::class,
        dependsOn = "notificationsEnabled",
        platforms = [SettingPlatform.ANDROID]
    )
    val notificationVibrate: Boolean = true,

    @Setting(
        titleKey = MagesSettingsKeys.NOTIFICATION_SOUND,
        descriptionKey = MagesSettingsKeys.NOTIFICATION_SOUND_DESCRIPTION,
        category = Notifications::class,
        type = Toggle::class,
        dependsOn = "notificationsEnabled"
    )
    val notificationSound: Boolean = true,

    @Setting(
        titleKey = MagesSettingsKeys.SOUND_ONCE_PER_ROOM,
        descriptionKey = MagesSettingsKeys.SOUND_ONCE_PER_ROOM_DESCRIPTION,
        category = Notifications::class,
        type = Toggle::class,
        dependsOn = "notificationSound"
    )
    val notifySoundOncePerRoom: Boolean = false,

    @Setting(
        titleKey = MagesSettingsKeys.NOTIFY_WHILE_ROOM_OPEN,
        descriptionKey = MagesSettingsKeys.NOTIFY_WHILE_ROOM_OPEN_DESCRIPTION,
        category = Notifications::class,
        type = Toggle::class,
        dependsOn = "notificationsEnabled"
    )
    val notifyWhileRoomOpen: Boolean = false,

    @Setting(
        titleKey = MagesSettingsKeys.QUIET_HOURS,
        descriptionKey = MagesSettingsKeys.QUIET_HOURS_DESCRIPTION,
        category = Notifications::class,
        type = Toggle::class,
        dependsOn = "notificationsEnabled"
    )
    val quietHoursEnabled: Boolean = false,

    @Setting(
        titleKey = MagesSettingsKeys.QUIET_HOURS_START_TIME,
        descriptionKey = MagesSettingsKeys.QUIET_HOURS_START_TIME_DESCRIPTION,
        category = Notifications::class,
        type = TimePickerType::class,
        dependsOn = "quietHoursEnabled"
    )
    val quietHoursStartMinutes: Int = 1380,

    @Setting(
        titleKey = MagesSettingsKeys.QUIET_HOURS_END_TIME,
        descriptionKey = MagesSettingsKeys.QUIET_HOURS_END_TIME_DESCRIPTION,
        category = Notifications::class,
        type = TimePickerType::class,
        dependsOn = "quietHoursEnabled"
    )
    val quietHoursEndMinutes: Int = 420,

    @Setting(
        titleKey = MagesSettingsKeys.AUTO_JOIN_ROOM_INVITES,
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
        titleKey = MagesSettingsKeys.SEND_PUBLIC_READ_RECEIPTS,
        descriptionKey = MagesSettingsKeys.SEND_PUBLIC_READ_RECEIPTS_DESCRIPTION,
        category = Privacy::class,
        type = Toggle::class
    )
    val sendReadReceipts: Boolean = true,

    @Setting(
        titleKey = MagesSettingsKeys.SEND_TYPING_INDICATORS,
        descriptionKey = MagesSettingsKeys.SEND_TYPING_INDICATORS_DESCRIPTION,
        category = Privacy::class,
        type = Toggle::class
    )
    val sendTypingIndicators: Boolean = true,

    @Setting(
        titleKey = MagesSettingsKeys.SHARE_CALL_STATUS,
        descriptionKey = MagesSettingsKeys.SHARE_CALL_STATUS_DESCRIPTION,
        category = Privacy::class,
        type = Toggle::class
    )
    val shareCallStatus: Boolean = false,

    @Setting(
        titleKey = MagesSettingsKeys.BLOCK_INVITES,
        descriptionKey = MagesSettingsKeys.BLOCK_INVITES_DESCRIPTION,
        category = Privacy::class,
        type = Toggle::class
    )
    val blockInvites: Boolean = false,

    @Setting(
        titleKey = MagesSettingsKeys.LINK_PREVIEWS,
        descriptionKey = MagesSettingsKeys.LINK_PREVIEWS_DESCRIPTION,
        category = Privacy::class,
        type = Dropdown::class,
        optionsKey = MagesSettingsKeys.LINK_PREVIEWS_OPTIONS,
    )
    val linkPreviews: LinkPreviewsMode = LinkPreviewsMode.UnencryptedOnly,

    @Setting(
        titleKey = MagesSettingsKeys.PRESENCE,
        descriptionKey = MagesSettingsKeys.PRESENCE_DESCRIPTION,
        category = Privacy::class,
        type = Dropdown::class,
        optionsKey = MagesSettingsKeys.PRESENCE_OPTIONS,
    )
    val presence: PresenceMode = PresenceMode.Online,

    @Setting(
        titleKey = MagesSettingsKeys.STATUS_MESSAGE,
        descriptionKey = MagesSettingsKeys.STATUS_MESSAGE_DESCRIPTION,
        category = Privacy::class,
        type = TextInput::class,
    )
    val statusMessage: String = "",

    @Setting(
        titleKey = MagesSettingsKeys.APP_LOCK,
        descriptionKey = MagesSettingsKeys.APP_LOCK_DESCRIPTION,
        category = Privacy::class,
        type = Toggle::class,
        platforms = [SettingPlatform.ANDROID],
    )
    val appLockEnabled: Boolean = false,

    @Setting(
        titleKey = MagesSettingsKeys.APP_LOCK_TIMEOUT,
        descriptionKey = MagesSettingsKeys.APP_LOCK_TIMEOUT_DESCRIPTION,
        category = Privacy::class,
        type = Dropdown::class,
        optionsKey = MagesSettingsKeys.APP_LOCK_TIMEOUT_OPTIONS,
        dependsOn = "appLockEnabled",
        platforms = [SettingPlatform.ANDROID],
    )
    val appLockTimeout: AppLockTimeout = AppLockTimeout.OneMinute,

    @Setting(
        titleKey = MagesSettingsKeys.SCREEN_SECURITY,
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
        titleKey = MagesSettingsKeys.SHOW_CALL_SCREEN,
        descriptionKey = MagesSettingsKeys.SHOW_CALL_SCREEN_DESCRIPTION,
        dependsOn = "callNotificationsEnabled",
        platforms = [SettingPlatform.ANDROID]
    )
    val showIncomingCallScreen: Boolean = true,

    @Setting(
        category = Calls::class,
        type = Toggle::class,
        titleKey = MagesSettingsKeys.ANSWER_CALLS_WHEN_LOCKED,
        descriptionKey = MagesSettingsKeys.ANSWER_CALLS_WHEN_LOCKED_DESCRIPTION,
        dependsOn = "callNotificationsEnabled",
        platforms = [SettingPlatform.ANDROID]
    )
    val answerCallsWhenLocked: Boolean = true,

    @Setting(
        category = Calls::class,
        type = Toggle::class,
        titleKey = MagesSettingsKeys.CALL_NOTIFICATIONS,
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
        titleKey = MagesSettingsKeys.SELECT_UNIFIED_PUSH_DISTRIBUTOR,
        descriptionKey = MagesSettingsKeys.SELECT_UNIFIED_PUSH_DISTRIBUTOR_DESCRIPTION,
        platforms = [SettingPlatform.ANDROID],
    )
    @ActionHandler(SelectUnifiedPushDistributorAction::class)
    val selectUnifiedPushDistributor: Unit = Unit,

    @Setting(
        category = Notifications::class,
        type = Button::class,
        titleKey = MagesSettingsKeys.RE_REGISTER_UNIFIED_PUSH,
        descriptionKey = MagesSettingsKeys.RE_REGISTER_UNIFIED_PUSH_DESCRIPTION,
        platforms = [SettingPlatform.ANDROID],
    )
    @ActionHandler(ReRegisterUnifiedPushAction::class)
    val reRegisterUnifiedPush: Unit = Unit,

    @Setting(
        category = Notifications::class,
        type = Button::class,
        titleKey = MagesSettingsKeys.COPY_UNIFIED_PUSH_ENDPOINT,
        descriptionKey = MagesSettingsKeys.COPY_UNIFIED_PUSH_ENDPOINT_DESCRIPTION,
        platforms = [SettingPlatform.ANDROID],
    )
    @ActionHandler(CopyUnifiedPushEndpointAction::class)
    val copyUnifiedPushEndpoint: Unit = Unit,

    @Setting(
        titleKey = MagesSettingsKeys.BLOCK_MEDIA_PREVIEWS,
        descriptionKey = MagesSettingsKeys.BLOCK_MEDIA_PREVIEWS_DESCRIPTION,
        category = Storage::class,
        type = Dropdown::class,
        optionsKey = MagesSettingsKeys.BLOCK_MEDIA_PREVIEWS_OPTIONS,
    )
    val mediaPreviews: MediaPreviewsMode = MediaPreviewsMode.On,

    @Setting(
        titleKey = MagesSettingsKeys.AUTO_DOWNLOAD_PHOTOS,
        descriptionKey = MagesSettingsKeys.AUTO_DOWNLOAD_PHOTOS_DESCRIPTION,
        category = Storage::class,
        type = MultiSelect::class,
        options = [AUTO_DOWNLOAD_WIFI, AUTO_DOWNLOAD_MOBILE, AUTO_DOWNLOAD_ROAMING],
        optionsKey = MagesSettingsKeys.AUTO_DOWNLOAD_NETWORKS_OPTIONS,
    )
    val autoDownloadPhotos: Set<String> = setOf(AUTO_DOWNLOAD_WIFI),

    @Setting(
        titleKey = MagesSettingsKeys.AUTO_DOWNLOAD_AUDIO,
        descriptionKey = MagesSettingsKeys.AUTO_DOWNLOAD_AUDIO_DESCRIPTION,
        category = Storage::class,
        type = MultiSelect::class,
        options = [AUTO_DOWNLOAD_WIFI, AUTO_DOWNLOAD_MOBILE, AUTO_DOWNLOAD_ROAMING],
        optionsKey = MagesSettingsKeys.AUTO_DOWNLOAD_NETWORKS_OPTIONS,
    )
    val autoDownloadAudio: Set<String> = setOf(AUTO_DOWNLOAD_WIFI),

    @Setting(
        titleKey = MagesSettingsKeys.AUTO_DOWNLOAD_VIDEO,
        descriptionKey = MagesSettingsKeys.AUTO_DOWNLOAD_VIDEO_DESCRIPTION,
        category = Storage::class,
        type = MultiSelect::class,
        options = [AUTO_DOWNLOAD_WIFI, AUTO_DOWNLOAD_MOBILE, AUTO_DOWNLOAD_ROAMING],
        optionsKey = MagesSettingsKeys.AUTO_DOWNLOAD_NETWORKS_OPTIONS,
    )
    val autoDownloadVideo: Set<String> = setOf(AUTO_DOWNLOAD_WIFI),

    @Setting(
        titleKey = MagesSettingsKeys.AUTO_DOWNLOAD_DOCUMENTS,
        descriptionKey = MagesSettingsKeys.AUTO_DOWNLOAD_DOCUMENTS_DESCRIPTION,
        category = Storage::class,
        type = MultiSelect::class,
        options = [AUTO_DOWNLOAD_WIFI, AUTO_DOWNLOAD_MOBILE, AUTO_DOWNLOAD_ROAMING],
        optionsKey = MagesSettingsKeys.AUTO_DOWNLOAD_NETWORKS_OPTIONS,
    )
    val autoDownloadDocuments: Set<String> = emptySet(),

    @Setting(
        titleKey = MagesSettingsKeys.MEDIA_CACHE,
        descriptionKey = MagesSettingsKeys.MEDIA_CACHE_DESCRIPTION,
        category = Storage::class,
        type = Button::class,
    )
    @ActionHandler(OpenMediaCacheAction::class)
    val openMediaCache: Unit = Unit,

    @Setting(
        titleKey = MagesSettingsKeys.START_IN_TRAY,
        descriptionKey = MagesSettingsKeys.START_IN_TRAY_DESCRIPTION,
        category = Advanced::class,
        type = Toggle::class,
        platforms = [SettingPlatform.JVM]
    )
    val startInTray: Boolean = false,

    @Setting(
        titleKey = MagesSettingsKeys.ENABLE_TRAY,
        descriptionKey = MagesSettingsKeys.ENABLE_TRAY_DESCRIPTION,
        category = Advanced::class,
        type = Toggle::class,
        platforms = [SettingPlatform.JVM]
    )
    val enableTray: Boolean = true,

    @Persisted
    val lastOpenedRoomId: String? = null,

    @Persisted
    val roomDraftsJson: String = "",

    @Persisted
    val threadReadMarksJson: String = "",

    @Persisted
    val roomPrivacyJson: String = "",

    @Persisted
    val searchHistoryJson: String = "",

    @Persisted
    val spaceNotificationScopesJson: String = "",

    @Setting(
        titleKey = MagesSettingsKeys.ENTER_SENDS_MESSAGE,
        descriptionKey = MagesSettingsKeys.ENTER_SENDS_MESSAGE_DESCRIPTION,
        category = Advanced::class,
        type = Toggle::class
    )
    val enterSendsMessage: Boolean = false,

    @Setting(
        titleKey = MagesSettingsKeys.ENABLE_SLASH_COMMANDS,
        descriptionKey = MagesSettingsKeys.ENABLE_SLASH_COMMANDS_DESCRIPTION,
        category = Advanced::class,
        type = Toggle::class
    )
    val enableSlashCommands: Boolean = false,

    @Setting(
        titleKey = MagesSettingsKeys.ABOUT,
        descriptionKey = MagesSettingsKeys.ABOUT_DESCRIPTION,
        category = Advanced::class,
        type = Button::class,
    )
    @ActionHandler(OpenAboutAction::class)
    val openAbout: Unit = Unit,

    @Persisted(key = "developer_mode")
    val developerMode: Boolean = false,

    @Persisted(key = "developer_log_level")
    val developerLogLevel: String = "Info",

    @Persisted(key = "developer_trace_log_packs")
    val developerTraceLogPacks: String = "",

    @Setting(
        titleKey = MagesSettingsKeys.EDIT_LATEST_WITH_UP_ARROW,
        descriptionKey = MagesSettingsKeys.EDIT_LATEST_WITH_UP_ARROW_DESCRIPTION,
        category = Advanced::class,
        type = Toggle::class
    )
    val editLatestWithUpArrow: Boolean = false,

    @Setting(
        titleKey = MagesSettingsKeys.USE_PROXY,
        descriptionKey = MagesSettingsKeys.USE_PROXY_DESCRIPTION,
        category = Advanced::class,
        type = Toggle::class,
        platforms = [SettingPlatform.ANDROID, SettingPlatform.JVM],
    )
    val proxyEnabled: Boolean = false,

    @Setting(
        titleKey = MagesSettingsKeys.PROXY_URL,
        descriptionKey = MagesSettingsKeys.PROXY_URL_DESCRIPTION,
        category = Advanced::class,
        type = TextInput::class,
        dependsOn = "proxyEnabled",
        platforms = [SettingPlatform.ANDROID, SettingPlatform.JVM],
    )
    val proxyUrl: String = "",

    @Setting(
        titleKey = MagesSettingsKeys.LIVE_LOCATION_UPDATE_INTERVAL,
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
object OpenWallpaperAction : SettingAction
object OpenMessageMenuSettingsAction : SettingAction
object OpenQuickReactionsAction : SettingAction
object OpenAboutAction : SettingAction
object OpenNotificationRulesAction : SettingAction
object OpenMediaCacheAction : SettingAction
object RequestNotificationPermissionAction : SettingAction
object TestNotificationAction : SettingAction
