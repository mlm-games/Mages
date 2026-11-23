package org.mlm.mages.commands

import org.mlm.mages.matrix.MessageSendType

data class SlashCommandSuggestion(
    val command: String,
    val usage: String,
    val description: String,
    val threadSupported: Boolean,
)

sealed interface ParsedSlashCommand {
    data object NotACommand : ParsedSlashCommand
    data class Error(val message: String) : ParsedSlashCommand
    data class Command(val value: SlashCommand) : ParsedSlashCommand
}

sealed interface SlashCommand {
    data class Send(
        val message: String,
        val sendType: MessageSendType = MessageSendType.Text,
        val asPlainText: Boolean = false,
    ) : SlashCommand

    data class Ban(val userId: String, val reason: String?) : SlashCommand
    data class Unban(val userId: String, val reason: String?) : SlashCommand
    data class Kick(val userId: String, val reason: String?) : SlashCommand
    data class Invite(val userId: String) : SlashCommand
    data class Ignore(val userId: String) : SlashCommand
    data class Unignore(val userId: String) : SlashCommand
    data class SetRoomName(val name: String) : SlashCommand
    data class SetRoomTopic(val topic: String) : SlashCommand
    data class SetDisplayName(val name: String) : SlashCommand
    data class SetRoomDisplayName(val name: String) : SlashCommand
    data class ShowUser(val userId: String) : SlashCommand
    data class SendRainbow(val message: String) : SlashCommand
    data class SendRainbowEmote(val message: String) : SlashCommand
    data class SendSpoiler(val message: String) : SlashCommand

    data class JoinRoom(val roomIdOrAlias: String) : SlashCommand
    data object LeaveRoom : SlashCommand
}

private data class CommandSpec(
    val aliases: List<String>,
    val usage: String,
    val description: String,
    val threadSupported: Boolean,
)

object SlashCommands {
    private val commandSpecs = listOf(
        CommandSpec(listOf("/me"), "/me <message>", "Send an emote message", true),
        CommandSpec(listOf("/plain"), "/plain <message>", "Send plain text", true),
        CommandSpec(listOf("/shrug"), "/shrug [message]", "Prefix with shrug", true),
        CommandSpec(listOf("/lenny"), "/lenny [message]", "Prefix with lenny face", true),
        CommandSpec(listOf("/tableflip"), "/tableflip [message]", "Prefix with table flip", true),
        CommandSpec(listOf("/unflip"), "/unflip [message]", "Prefix with table unflip", true),
        CommandSpec(listOf("/ban"), "/ban <@user:server> [reason]", "Ban a user", false),
        CommandSpec(listOf("/unban"), "/unban <@user:server> [reason]", "Unban a user", false),
        CommandSpec(listOf("/kick", "/remove"), "/kick <@user:server> [reason]", "Kick a user", false),
        CommandSpec(listOf("/invite"), "/invite <@user:server>", "Invite a user", false),
        CommandSpec(listOf("/ignore"), "/ignore <@user:server>", "Ignore a user", false),
        CommandSpec(listOf("/unignore"), "/unignore <@user:server>", "Unignore a user", false),
        CommandSpec(listOf("/roomname"), "/roomname <name>", "Set room name", false),
        CommandSpec(listOf("/topic"), "/topic <topic>", "Set room topic", false),
        CommandSpec(listOf("/nick"), "/nick <display-name>", "Set your display name", true),
        CommandSpec(listOf("/myroomnick", "/roomnick"), "/myroomnick <display-name>", "Set your display name in this room", false),
        CommandSpec(listOf("/whois"), "/whois <@user:server>", "Show user information", true),
        CommandSpec(listOf("/rainbow"), "/rainbow <message>", "Send a rainbow message", true),
        CommandSpec(listOf("/rainbowme"), "/rainbowme <message>", "Send a rainbow emote", true),
        CommandSpec(listOf("/spoiler"), "/spoiler <message>", "Send a spoiler message", true),
        CommandSpec(listOf("/join", "/j", "/goto"), "/join <#alias:server|!id:server>", "Join a room", false),
        CommandSpec(listOf("/leave", "/part"), "/leave", "Leave current room", false),
    )

    val suggestions: List<SlashCommandSuggestion> =
        commandSpecs.flatMap { spec ->
            spec.aliases.map { alias ->
                SlashCommandSuggestion(
                    command = alias,
                    usage = spec.usage,
                    description = spec.description,
                    threadSupported = spec.threadSupported,
                )
            }
        }

    fun parse(
        rawInput: String,
        enabled: Boolean,
        isInThread: Boolean,
    ): ParsedSlashCommand {
        if (!enabled) return ParsedSlashCommand.NotACommand
        val input = rawInput.trim()
        if (!input.startsWith('/')) return ParsedSlashCommand.NotACommand
        if (input == "/" || input.startsWith("//")) return ParsedSlashCommand.NotACommand

        val commandToken = input.substringBefore(' ').lowercase()
        val args = input.substringAfter(' ', "").trim()

        val spec = commandSpecs.firstOrNull { commandToken in it.aliases }
            ?: return ParsedSlashCommand.Error("Unknown slash command: $commandToken")
        if (isInThread && !spec.threadSupported) {
            return ParsedSlashCommand.Error("$commandToken is not supported in threads")
        }

        return when (commandToken) {
            "/me" -> parseRequiredMessage(commandToken, args) { msg ->
                SlashCommand.Send(msg, sendType = MessageSendType.Emote)
            }

            "/plain" -> parseRequiredMessage(commandToken, args) { msg ->
                SlashCommand.Send(msg, sendType = MessageSendType.Text, asPlainText = true)
            }

            "/shrug" -> ParsedSlashCommand.Command(
                SlashCommand.Send(prefixMessage("\u00AF\\_(\u30C4)_/\u00AF", args))
            )

            "/lenny" -> ParsedSlashCommand.Command(
                SlashCommand.Send(prefixMessage("( \u0361\u00B0 \u035C\u0296 \u0361\u00B0)", args))
            )

            "/tableflip" -> ParsedSlashCommand.Command(
                SlashCommand.Send(prefixMessage("(\u256F\u00B0\u25A1\u00B0\uFF09\u256F\uFE35 \u253B\u2501\u253B", args))
            )

            "/unflip" -> ParsedSlashCommand.Command(
                SlashCommand.Send(prefixMessage("\u252C\u2500\u2500\u252C \u30CE( \u309C-\u309C\u30CE)", args))
            )

            "/ban" -> parseUserAndOptionalReason(commandToken, args) { userId, reason ->
                SlashCommand.Ban(userId, reason)
            }

            "/unban" -> parseUserAndOptionalReason(commandToken, args) { userId, reason ->
                SlashCommand.Unban(userId, reason)
            }

            "/kick", "/remove" -> parseUserAndOptionalReason(commandToken, args) { userId, reason ->
                SlashCommand.Kick(userId, reason)
            }

            "/invite" -> parseRequiredUser(commandToken, args) { userId ->
                SlashCommand.Invite(userId)
            }

            "/ignore" -> parseRequiredUser(commandToken, args) { userId ->
                SlashCommand.Ignore(userId)
            }

            "/unignore" -> parseRequiredUser(commandToken, args) { userId ->
                SlashCommand.Unignore(userId)
            }

            "/roomname" -> parseRequiredMessage(commandToken, args) { name ->
                SlashCommand.SetRoomName(name)
            }

            "/topic" -> parseRequiredMessage(commandToken, args) { topic ->
                SlashCommand.SetRoomTopic(topic)
            }

            "/nick" -> parseRequiredMessage(commandToken, args) { name ->
                SlashCommand.SetDisplayName(name)
            }

            "/myroomnick", "/roomnick" -> parseRequiredMessage(commandToken, args) { name ->
                SlashCommand.SetRoomDisplayName(name)
            }

            "/whois" -> parseRequiredUser(commandToken, args) { userId ->
                SlashCommand.ShowUser(userId)
            }

            "/rainbow" -> parseRequiredMessage(commandToken, args) { message ->
                SlashCommand.SendRainbow(message)
            }

            "/rainbowme" -> parseRequiredMessage(commandToken, args) { message ->
                SlashCommand.SendRainbowEmote(message)
            }

            "/spoiler" -> parseRequiredMessage(commandToken, args) { message ->
                SlashCommand.SendSpoiler(message)
            }

            "/join", "/j", "/goto" -> {
                val room = args.substringBefore(' ').trim()
                when {
                    room.isEmpty() -> ParsedSlashCommand.Error("Usage: /join <#alias:server|!id:server>")
                    !looksLikeRoomIdOrAlias(room) -> ParsedSlashCommand.Error("Invalid room id or alias: $room")
                    else -> ParsedSlashCommand.Command(SlashCommand.JoinRoom(room))
                }
            }

            "/leave", "/part" -> {
                if (args.isNotEmpty()) {
                    ParsedSlashCommand.Error("Usage: /leave")
                } else {
                    ParsedSlashCommand.Command(SlashCommand.LeaveRoom)
                }
            }

            else -> ParsedSlashCommand.Error("Unknown slash command: $commandToken")
        }
    }

    fun suggestionsFor(
        query: String,
        isInThread: Boolean,
    ): List<SlashCommandSuggestion> {
        val normalized = query.lowercase()
        return suggestions
            .asSequence()
            .filter { !isInThread || it.threadSupported }
            .filter { it.command.startsWith("/$normalized") }
            .take(12)
            .toList()
    }

    private inline fun parseRequiredMessage(
        command: String,
        args: String,
        crossinline builder: (String) -> SlashCommand,
    ): ParsedSlashCommand {
        if (args.isBlank()) return ParsedSlashCommand.Error("Usage: $command <message>")
        return ParsedSlashCommand.Command(builder(args))
    }

    private inline fun parseRequiredUser(
        command: String,
        args: String,
        crossinline builder: (String) -> SlashCommand,
    ): ParsedSlashCommand {
        val userId = args.substringBefore(' ').trim()
        return when {
            userId.isEmpty() -> ParsedSlashCommand.Error("Usage: $command <@user:server>")
            !looksLikeUserId(userId) -> ParsedSlashCommand.Error("Invalid user id: $userId")
            else -> ParsedSlashCommand.Command(builder(userId))
        }
    }

    private inline fun parseUserAndOptionalReason(
        command: String,
        args: String,
        crossinline builder: (String, String?) -> SlashCommand,
    ): ParsedSlashCommand {
        val userId = args.substringBefore(' ').trim()
        val reason = args.substringAfter(' ', "").trim().ifBlank { null }
        return when {
            userId.isEmpty() -> ParsedSlashCommand.Error("Usage: $command <@user:server> [reason]")
            !looksLikeUserId(userId) -> ParsedSlashCommand.Error("Invalid user id: $userId")
            else -> ParsedSlashCommand.Command(builder(userId, reason))
        }
    }

    private fun prefixMessage(prefix: String, message: String): String {
        val body = message.trim()
        return if (body.isBlank()) prefix else "$prefix $body"
    }

    private fun looksLikeUserId(value: String): Boolean =
        value.startsWith("@") && value.contains(':') && value.length > 3

    private fun looksLikeRoomIdOrAlias(value: String): Boolean =
        (value.startsWith("!") || value.startsWith("#")) && value.contains(':')
}
