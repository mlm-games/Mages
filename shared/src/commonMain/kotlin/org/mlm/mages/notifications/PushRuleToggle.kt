package org.mlm.mages.notifications

import mages.shared.generated.resources.*
import org.mlm.mages.matrix.PushRuleKind
import org.jetbrains.compose.resources.StringResource
import mages.shared.generated.resources.Res

data class PushRuleBinding(
    val kind: PushRuleKind,
    val ruleId: String,
)

data class PushRuleToggle(
    val id: String,
    val label: StringResource,
    val description: StringResource,
    val rules: List<PushRuleBinding>,
    val invertedSemantics: Boolean = false,
    val defaultUiValue: Boolean = true,
    val eventType: String? = null,
)

object NotificationToggles {

    val dmMessages = PushRuleToggle(
        id = "dm_messages",
        label = Res.string.messages_in_dms,
        description = Res.string.direct_messages_from_contacts,
        rules = listOf(
            PushRuleBinding(PushRuleKind.Underride, ".m.rule.room_one_to_one"),
            PushRuleBinding(PushRuleKind.Underride, ".m.rule.encrypted_room_one_to_one"),
        ),
    )

    val groupMessages = PushRuleToggle(
        id = "group_messages",
        label = Res.string.messages_in_groups,
        description = Res.string.messages_in_group_rooms,
        rules = listOf(
            PushRuleBinding(PushRuleKind.Underride, ".m.rule.message"),
            PushRuleBinding(PushRuleKind.Underride, ".m.rule.encrypted"),
        ),
    )

    val mentions = PushRuleToggle(
        id = "mentions",
        label = Res.string.mentions,
        description = Res.string.when_someone_mentions_you_by_name,
        rules = listOf(
            PushRuleBinding(PushRuleKind.Override, ".m.rule.is_user_mention"),
        ),
    )

    val roomMentions = PushRuleToggle(
        id = "room_mentions",
        label = Res.string.at_room_mentions,
        description = Res.string.when_someone_uses_at_room,
        rules = listOf(
            PushRuleBinding(PushRuleKind.Override, ".m.rule.is_room_mention"),
        ),
    )

    val reactions = PushRuleToggle(
        id = "reactions",
        label = Res.string.reactions,
        description = Res.string.emoji_reactions_to_messages,
        invertedSemantics = false,
        rules = emptyList(),
        defaultUiValue = false,
    )

    val stickers = PushRuleToggle(
        id = "stickers",
        label = Res.string.stickers,
        description = Res.string.notify_for_stickers,
        rules = listOf(
            PushRuleBinding(PushRuleKind.Underride, "org.mlm.mages.sticker"),
        ),
        defaultUiValue = false,
        eventType = "m.sticker",
    )

    val liveLocation = PushRuleToggle(
        id = "live_location",
        label = Res.string.live_location_notifications,
        description = Res.string.notify_for_live_location,
        rules = listOf(
            PushRuleBinding(PushRuleKind.Underride, "org.mlm.mages.live_location"),
        ),
        defaultUiValue = false,
        eventType = "m.beacon_info",
    )

    val invites = PushRuleToggle(
        id = "invites",
        label = Res.string.invites,
        description = Res.string.room_invitations,
        rules = listOf(
            PushRuleBinding(PushRuleKind.Override, ".m.rule.invite_for_me"),
        ),
    )

    val calls = PushRuleToggle(
        id = "calls",
        label = Res.string.calls,
        description = Res.string.incoming_voice_and_video_calls,
        rules = listOf(
            PushRuleBinding(PushRuleKind.Underride, ".m.rule.call"),
        ),
    )

    val roomUpgrades = PushRuleToggle(
        id = "room_upgrades",
        label = Res.string.room_upgrades,
        description = Res.string.when_a_room_is_upgraded_to_a_new_version,
        rules = listOf(
            PushRuleBinding(PushRuleKind.Override, ".m.rule.tombstone"),
        ),
    )

    val suppressBotNotices = PushRuleToggle(
        id = "bot_notices",
        label = Res.string.bot_messages,
        description = Res.string.messages_from_bots_and_bridges,
        invertedSemantics = true,
        rules = listOf(
            PushRuleBinding(PushRuleKind.Override, ".m.rule.suppress_notices"),
        ),
        defaultUiValue = false,
    )

    val all: List<PushRuleToggle> = listOf(
        dmMessages,
        groupMessages,
        mentions,
        roomMentions,
        reactions,
        stickers,
        liveLocation,
        invites,
        calls,
        roomUpgrades,
        suppressBotNotices,
    )
}
