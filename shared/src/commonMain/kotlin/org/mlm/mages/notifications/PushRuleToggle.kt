package org.mlm.mages.notifications

import org.mlm.mages.matrix.PushRuleKind

data class PushRuleBinding(
    val kind: PushRuleKind,
    val ruleId: String,
)

data class PushRuleToggle(
    val id: String,
    val label: String,
    val description: String,
    val rules: List<PushRuleBinding>,
    val invertedSemantics: Boolean = false,
    val defaultUiValue: Boolean = true,
)

object NotificationToggles {

    val dmMessages = PushRuleToggle(
        id = "dm_messages",
        label = "Messages in DMs",
        description = "Direct messages from contacts",
        rules = listOf(
            PushRuleBinding(PushRuleKind.Underride, ".m.rule.room_one_to_one"),
            PushRuleBinding(PushRuleKind.Underride, ".m.rule.encrypted_room_one_to_one"),
        ),
    )

    val groupMessages = PushRuleToggle(
        id = "group_messages",
        label = "Messages in groups",
        description = "Messages in group rooms",
        rules = listOf(
            PushRuleBinding(PushRuleKind.Underride, ".m.rule.message"),
            PushRuleBinding(PushRuleKind.Underride, ".m.rule.encrypted"),
        ),
    )

    val mentions = PushRuleToggle(
        id = "mentions",
        label = "Mentions",
        description = "When someone mentions you by name",
        rules = listOf(
            PushRuleBinding(PushRuleKind.Override, ".m.rule.is_user_mention"),
        ),
    )

    val roomMentions = PushRuleToggle(
        id = "room_mentions",
        label = "@room mentions",
        description = "When someone uses @room",
        rules = listOf(
            PushRuleBinding(PushRuleKind.Override, ".m.rule.is_room_mention"),
        ),
    )

    val reactions = PushRuleToggle(
        id = "reactions",
        label = "Reactions",
        description = "Emoji reactions to messages",
        invertedSemantics = false,
        rules = emptyList(),
        defaultUiValue = false,
    )

    val invites = PushRuleToggle(
        id = "invites",
        label = "Invites",
        description = "Room invitations",
        rules = listOf(
            PushRuleBinding(PushRuleKind.Override, ".m.rule.invite_for_me"),
        ),
    )

    val calls = PushRuleToggle(
        id = "calls",
        label = "Calls",
        description = "Incoming voice and video calls",
        rules = listOf(
            PushRuleBinding(PushRuleKind.Underride, ".m.rule.call"),
        ),
    )

    val roomUpgrades = PushRuleToggle(
        id = "room_upgrades",
        label = "Room upgrades",
        description = "When a room is upgraded to a new version",
        rules = listOf(
            PushRuleBinding(PushRuleKind.Override, ".m.rule.tombstone"),
        ),
    )

    val suppressBotNotices = PushRuleToggle(
        id = "bot_notices",
        label = "Bot messages",
        description = "Messages from bots and bridges",
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
        invites,
        calls,
        roomUpgrades,
        suppressBotNotices,
    )
}
