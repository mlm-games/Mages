package org.mlm.mages.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.mlmgames.settings.core.resources.StringResourceProvider
import mages.shared.generated.resources.*
import org.jetbrains.compose.resources.StringArrayResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import org.mlm.mages.platform.LocalAppLocale

private class ComposeSettingsStringResourceProvider(
    private val strings: Map<String, String>,
    private val arrays: Map<String, List<String>>,
) : StringResourceProvider {
    override fun getString(resId: Int): String = ""

    override fun getString(resId: Int, vararg formatArgs: Any): String = ""

    override fun getStringArray(resId: Int): List<String> = emptyList()

    override fun getString(key: String): String = strings[key].orEmpty()

    override fun getString(key: String, vararg formatArgs: Any): String {
        val value = getString(key)
        if (formatArgs.isEmpty()) return value
        return FORMAT_REGEX.replace(value) { match ->
            formatArgs.getOrNull(match.groupValues[1].toInt() - 1)?.toString() ?: match.value
        }
    }

    override fun getStringArray(key: String): List<String> = arrays[key].orEmpty()

    private companion object {
        val FORMAT_REGEX = Regex("""%(\d+)\$[ds]""")
    }
}

private val categoryResources: Map<String, StringResource> = mapOf(
    MagesSettingsKeys.CATEGORY_ACCOUNT to Res.string.account,
    MagesSettingsKeys.CATEGORY_APPEARANCE to Res.string.appearance,
    MagesSettingsKeys.CATEGORY_TIMELINE to Res.string.timeline,
    MagesSettingsKeys.CATEGORY_NOTIFICATIONS to Res.string.notifications,
    MagesSettingsKeys.CATEGORY_PRIVACY to Res.string.privacy,
    MagesSettingsKeys.CATEGORY_CALLS to Res.string.calls,
    MagesSettingsKeys.CATEGORY_STORAGE to Res.string.storage,
    MagesSettingsKeys.CATEGORY_ADVANCED to Res.string.advanced,
)

private val titleResources: Map<String, StringResource> = mapOf(
    MagesSettingsKeys.THEME_MODE to Res.string.setting_theme,
    MagesSettingsKeys.DYNAMIC_COLORS to Res.string.setting_dynamic_colors,
    MagesSettingsKeys.OLED_BLACK to Res.string.setting_oled_black,
    MagesSettingsKeys.LANGUAGE to Res.string.setting_language,
    MagesSettingsKeys.FONT_SIZE to Res.string.setting_font_size,
    MagesSettingsKeys.SHOW_MESSAGE_AVATARS to Res.string.setting_show_message_avatars,
    MagesSettingsKeys.SHOW_REACTION_AVATARS to Res.string.setting_show_reaction_avatars,
    MagesSettingsKeys.SHOW_USERNAME_IN_DMS to Res.string.setting_show_username_in_dms,
    MagesSettingsKeys.BUBBLE_ANIMATIONS to Res.string.setting_bubble_animations,
    MagesSettingsKeys.HIDE_MEMBERSHIP_EVENTS to Res.string.setting_hide_membership_events,
    MagesSettingsKeys.HIDE_PROFILE_CHANGES to Res.string.setting_hide_profile_changes,
    MagesSettingsKeys.HIDE_TOPIC_CHANGES to Res.string.setting_hide_topic_changes,
    MagesSettingsKeys.HIDE_REDACTED_EVENTS to Res.string.setting_hide_redacted_events,
    MagesSettingsKeys.HIDE_ROOM_NAME_CHANGES to Res.string.setting_hide_room_name_changes,
    MagesSettingsKeys.HIDE_ROOM_AVATAR_CHANGES to Res.string.setting_hide_room_avatar_changes,
    MagesSettingsKeys.HIDE_ENCRYPTION_CHANGES to Res.string.setting_hide_encryption_changes,
    MagesSettingsKeys.HIDE_PINNED_EVENT_UPDATES to Res.string.setting_hide_pinned_event_updates,
    MagesSettingsKeys.HIDE_POWER_LEVEL_CHANGES to Res.string.setting_hide_power_level_changes,
    MagesSettingsKeys.HIDE_CANONICAL_ALIAS_CHANGES to Res.string.setting_hide_canonical_alias_changes,
    MagesSettingsKeys.HIDE_JOIN_RULE_CHANGES to Res.string.setting_hide_join_rule_changes,
    MagesSettingsKeys.HIDE_HISTORY_VISIBILITY_CHANGES to Res.string.setting_hide_history_visibility_changes,
    MagesSettingsKeys.HIDE_GUEST_ACCESS_CHANGES to Res.string.setting_hide_guest_access_changes,
    MagesSettingsKeys.HIDE_SERVER_ACL_CHANGES to Res.string.setting_hide_server_acl_changes,
    MagesSettingsKeys.HIDE_TOMBSTONE_EVENTS to Res.string.setting_hide_tombstone_events,
    MagesSettingsKeys.HIDE_SPACE_CHILD_EVENTS to Res.string.setting_hide_space_child_events,
    MagesSettingsKeys.HIDE_OTHER_STATE_EVENTS to Res.string.setting_hide_other_state_events,
    MagesSettingsKeys.AUTO_PAGINATE_OLDER_MESSAGES to Res.string.setting_auto_paginate_older_messages,
    MagesSettingsKeys.INCLUDE_SILENT_UNREAD_IN_FILTER to Res.string.setting_include_silent_unread_in_filter,
    MagesSettingsKeys.CHAT_BUBBLES to Res.string.setting_chat_bubbles,
    MagesSettingsKeys.NOTIFICATION_RULES to Res.string.setting_notification_rules,
    MagesSettingsKeys.ENABLE_NOTIFICATIONS to Res.string.setting_enable_notifications,
    MagesSettingsKeys.REQUEST_NOTIFICATION_PERMISSION to Res.string.setting_request_notification_permission,
    MagesSettingsKeys.TEST_BROWSER_NOTIFICATION to Res.string.setting_test_browser_notification,
    MagesSettingsKeys.SHOW_MESSAGE_PREVIEW to Res.string.setting_show_message_preview,
    MagesSettingsKeys.VIBRATE to Res.string.setting_vibrate,
    MagesSettingsKeys.NOTIFICATION_SOUND to Res.string.setting_notification_sound,
    MagesSettingsKeys.SOUND_ONCE_PER_ROOM to Res.string.setting_sound_once_per_room,
    MagesSettingsKeys.QUIET_HOURS to Res.string.setting_quiet_hours,
    MagesSettingsKeys.QUIET_HOURS_START_TIME to Res.string.setting_quiet_hours_start_time,
    MagesSettingsKeys.QUIET_HOURS_END_TIME to Res.string.setting_quiet_hours_end_time,
    MagesSettingsKeys.AUTO_JOIN_ROOM_INVITES to Res.string.setting_auto_join_room_invites,
    MagesSettingsKeys.SEND_PUBLIC_READ_RECEIPTS to Res.string.setting_send_public_read_receipts,
    MagesSettingsKeys.SEND_TYPING_INDICATORS to Res.string.setting_send_typing_indicators,
    MagesSettingsKeys.PRESENCE to Res.string.setting_presence,
    MagesSettingsKeys.APP_LOCK to Res.string.setting_app_lock,
    MagesSettingsKeys.APP_LOCK_TIMEOUT to Res.string.setting_app_lock_timeout,
    MagesSettingsKeys.SCREEN_SECURITY to Res.string.setting_screen_security,
    MagesSettingsKeys.SHOW_CALL_SCREEN to Res.string.setting_show_call_screen,
    MagesSettingsKeys.CALL_NOTIFICATIONS to Res.string.setting_call_notifications,
    MagesSettingsKeys.SELECT_UNIFIED_PUSH_DISTRIBUTOR to Res.string.setting_select_unified_push_distributor,
    MagesSettingsKeys.RE_REGISTER_UNIFIED_PUSH to Res.string.setting_re_register_unified_push,
    MagesSettingsKeys.COPY_UNIFIED_PUSH_ENDPOINT to Res.string.setting_copy_unified_push_endpoint,
    MagesSettingsKeys.BLOCK_MEDIA_PREVIEWS to Res.string.setting_block_media_previews,
    MagesSettingsKeys.MEDIA_CACHE to Res.string.setting_media_cache,
    MagesSettingsKeys.START_IN_TRAY to Res.string.setting_start_in_tray,
    MagesSettingsKeys.ENTER_SENDS_MESSAGE to Res.string.setting_enter_sends_message,
    MagesSettingsKeys.USE_PROXY to Res.string.setting_use_proxy,
    MagesSettingsKeys.PROXY_URL to Res.string.setting_proxy_url,
    MagesSettingsKeys.LIVE_LOCATION_UPDATE_INTERVAL to Res.string.setting_live_location_update_interval,
)

private val descriptionResources: Map<String, StringResource> = mapOf(
    MagesSettingsKeys.THEME_MODE_DESCRIPTION to Res.string.setting_theme_description,
    MagesSettingsKeys.DYNAMIC_COLORS_DESCRIPTION to Res.string.setting_dynamic_colors_description,
    MagesSettingsKeys.OLED_BLACK_DESCRIPTION to Res.string.setting_oled_black_description,
    MagesSettingsKeys.LANGUAGE_DESCRIPTION to Res.string.setting_language_description,
    MagesSettingsKeys.FONT_SIZE_DESCRIPTION to Res.string.setting_font_size_description,
    MagesSettingsKeys.SHOW_MESSAGE_AVATARS_DESCRIPTION to Res.string.setting_show_message_avatars_description,
    MagesSettingsKeys.SHOW_REACTION_AVATARS_DESCRIPTION to Res.string.setting_show_reaction_avatars_description,
    MagesSettingsKeys.SHOW_USERNAME_IN_DMS_DESCRIPTION to Res.string.setting_show_username_in_dms_description,
    MagesSettingsKeys.BUBBLE_ANIMATIONS_DESCRIPTION to Res.string.setting_bubble_animations_description,
    MagesSettingsKeys.HIDE_MEMBERSHIP_EVENTS_DESCRIPTION to Res.string.setting_hide_membership_events_description,
    MagesSettingsKeys.HIDE_PROFILE_CHANGES_DESCRIPTION to Res.string.setting_hide_profile_changes_description,
    MagesSettingsKeys.HIDE_TOPIC_CHANGES_DESCRIPTION to Res.string.setting_hide_topic_changes_description,
    MagesSettingsKeys.HIDE_REDACTED_EVENTS_DESCRIPTION to Res.string.setting_hide_redacted_events_description,
    MagesSettingsKeys.HIDE_ROOM_NAME_CHANGES_DESCRIPTION to Res.string.setting_hide_room_name_changes_description,
    MagesSettingsKeys.HIDE_ROOM_AVATAR_CHANGES_DESCRIPTION to Res.string.setting_hide_room_avatar_changes_description,
    MagesSettingsKeys.HIDE_ENCRYPTION_CHANGES_DESCRIPTION to Res.string.setting_hide_encryption_changes_description,
    MagesSettingsKeys.HIDE_PINNED_EVENT_UPDATES_DESCRIPTION to Res.string.setting_hide_pinned_event_updates_description,
    MagesSettingsKeys.HIDE_POWER_LEVEL_CHANGES_DESCRIPTION to Res.string.setting_hide_power_level_changes_description,
    MagesSettingsKeys.HIDE_CANONICAL_ALIAS_CHANGES_DESCRIPTION to Res.string.setting_hide_canonical_alias_changes_description,
    MagesSettingsKeys.HIDE_JOIN_RULE_CHANGES_DESCRIPTION to Res.string.setting_hide_join_rule_changes_description,
    MagesSettingsKeys.HIDE_HISTORY_VISIBILITY_CHANGES_DESCRIPTION to Res.string.setting_hide_history_visibility_changes_description,
    MagesSettingsKeys.HIDE_GUEST_ACCESS_CHANGES_DESCRIPTION to Res.string.setting_hide_guest_access_changes_description,
    MagesSettingsKeys.HIDE_SERVER_ACL_CHANGES_DESCRIPTION to Res.string.setting_hide_server_acl_changes_description,
    MagesSettingsKeys.HIDE_TOMBSTONE_EVENTS_DESCRIPTION to Res.string.setting_hide_tombstone_events_description,
    MagesSettingsKeys.HIDE_SPACE_CHILD_EVENTS_DESCRIPTION to Res.string.setting_hide_space_child_events_description,
    MagesSettingsKeys.HIDE_OTHER_STATE_EVENTS_DESCRIPTION to Res.string.setting_hide_other_state_events_description,
    MagesSettingsKeys.AUTO_PAGINATE_OLDER_MESSAGES_DESCRIPTION to Res.string.setting_auto_paginate_older_messages_description,
    MagesSettingsKeys.INCLUDE_SILENT_UNREAD_IN_FILTER_DESCRIPTION to Res.string.setting_include_silent_unread_in_filter_description,
    MagesSettingsKeys.CHAT_BUBBLES_DESCRIPTION to Res.string.setting_chat_bubbles_description,
    MagesSettingsKeys.NOTIFICATION_RULES_DESCRIPTION to Res.string.setting_notification_rules_description,
    MagesSettingsKeys.ENABLE_NOTIFICATIONS_DESCRIPTION to Res.string.setting_enable_notifications_description,
    MagesSettingsKeys.REQUEST_NOTIFICATION_PERMISSION_DESCRIPTION to Res.string.setting_request_notification_permission_description,
    MagesSettingsKeys.TEST_BROWSER_NOTIFICATION_DESCRIPTION to Res.string.setting_test_browser_notification_description,
    MagesSettingsKeys.SHOW_MESSAGE_PREVIEW_DESCRIPTION to Res.string.setting_show_message_preview_description,
    MagesSettingsKeys.VIBRATE_DESCRIPTION to Res.string.setting_vibrate_description,
    MagesSettingsKeys.NOTIFICATION_SOUND_DESCRIPTION to Res.string.setting_notification_sound_description,
    MagesSettingsKeys.SOUND_ONCE_PER_ROOM_DESCRIPTION to Res.string.setting_sound_once_per_room_description,
    MagesSettingsKeys.QUIET_HOURS_DESCRIPTION to Res.string.setting_quiet_hours_description,
    MagesSettingsKeys.QUIET_HOURS_START_TIME_DESCRIPTION to Res.string.setting_quiet_hours_start_time_description,
    MagesSettingsKeys.QUIET_HOURS_END_TIME_DESCRIPTION to Res.string.setting_quiet_hours_end_time_description,
    MagesSettingsKeys.AUTO_JOIN_ROOM_INVITES_DESCRIPTION to Res.string.setting_auto_join_room_invites_description,
    MagesSettingsKeys.SEND_PUBLIC_READ_RECEIPTS_DESCRIPTION to Res.string.setting_send_public_read_receipts_description,
    MagesSettingsKeys.SEND_TYPING_INDICATORS_DESCRIPTION to Res.string.setting_send_typing_indicators_description,
    MagesSettingsKeys.PRESENCE_DESCRIPTION to Res.string.setting_presence_description,
    MagesSettingsKeys.APP_LOCK_DESCRIPTION to Res.string.setting_app_lock_description,
    MagesSettingsKeys.APP_LOCK_TIMEOUT_DESCRIPTION to Res.string.setting_app_lock_timeout_description,
    MagesSettingsKeys.SCREEN_SECURITY_DESCRIPTION to Res.string.setting_screen_security_description,
    MagesSettingsKeys.SHOW_CALL_SCREEN_DESCRIPTION to Res.string.setting_show_call_screen_description,
    MagesSettingsKeys.CALL_NOTIFICATIONS_DESCRIPTION to Res.string.setting_call_notifications_description,
    MagesSettingsKeys.SELECT_UNIFIED_PUSH_DISTRIBUTOR_DESCRIPTION to Res.string.setting_select_unified_push_distributor_description,
    MagesSettingsKeys.RE_REGISTER_UNIFIED_PUSH_DESCRIPTION to Res.string.setting_re_register_unified_push_description,
    MagesSettingsKeys.COPY_UNIFIED_PUSH_ENDPOINT_DESCRIPTION to Res.string.setting_copy_unified_push_endpoint_description,
    MagesSettingsKeys.BLOCK_MEDIA_PREVIEWS_DESCRIPTION to Res.string.setting_block_media_previews_description,
    MagesSettingsKeys.MEDIA_CACHE_DESCRIPTION to Res.string.setting_media_cache_description,
    MagesSettingsKeys.START_IN_TRAY_DESCRIPTION to Res.string.setting_start_in_tray_description,
    MagesSettingsKeys.ENTER_SENDS_MESSAGE_DESCRIPTION to Res.string.setting_enter_sends_message_description,
    MagesSettingsKeys.USE_PROXY_DESCRIPTION to Res.string.setting_use_proxy_description,
    MagesSettingsKeys.PROXY_URL_DESCRIPTION to Res.string.setting_proxy_url_description,
    MagesSettingsKeys.LIVE_LOCATION_UPDATE_INTERVAL_DESCRIPTION to Res.string.setting_live_location_update_interval_description,
)

private val arrayResources: Map<String, StringArrayResource> = mapOf(
    MagesSettingsKeys.THEME_OPTIONS to Res.array.setting_theme_options,
    MagesSettingsKeys.LANGUAGE_OPTIONS to Res.array.setting_language_options,
    MagesSettingsKeys.HIDE_IN_ROOMS_OPTIONS to Res.array.setting_hide_in_rooms_options,
    MagesSettingsKeys.APP_LOCK_TIMEOUT_OPTIONS to Res.array.setting_app_lock_timeout_options,
    MagesSettingsKeys.PRESENCE_OPTIONS to Res.array.setting_presence_options,
)

@Composable
fun rememberSettingsStringResourceProvider(): StringResourceProvider {
    val locale = LocalAppLocale.current
    val strings = mutableMapOf<String, String>()
    for ((key, resource) in categoryResources) {
        strings[key] = stringResource(resource)
    }
    for ((key, resource) in titleResources) {
        strings[key] = stringResource(resource)
    }
    for ((key, resource) in descriptionResources) {
        strings[key] = stringResource(resource)
    }
    val arrays = mutableMapOf<String, List<String>>()
    for ((key, resource) in arrayResources) {
        arrays[key] = stringArrayResource(resource)
    }
    return remember(locale, strings, arrays) {
        ComposeSettingsStringResourceProvider(strings, arrays)
    }
}
