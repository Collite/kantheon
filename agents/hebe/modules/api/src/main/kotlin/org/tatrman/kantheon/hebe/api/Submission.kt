package org.tatrman.kantheon.hebe.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface Submission {
    @Serializable
    @SerialName("user_input")
    data class UserInput(
        val msg: IncomingMessage,
    ) : Submission

    @Serializable
    @SerialName("system_command")
    data class SystemCommand(
        val msg: IncomingMessage,
        val command: SlashCommand,
    ) : Submission

    @Serializable
    @SerialName("approval")
    data class Approval(
        val msg: IncomingMessage,
        val approvalId: String,
        val approved: Boolean,
    ) : Submission

    @Serializable
    @SerialName("auth_mode")
    data class AuthMode(
        val msg: IncomingMessage,
        val purpose: String,
        val secret: String,
    ) : Submission

    @Serializable
    @SerialName("quit_command")
    data class QuitCommand(
        val msg: IncomingMessage,
    ) : Submission
}

@Serializable
sealed interface SlashCommand {
    @Serializable
    @SerialName("compact")
    data object Compact : SlashCommand

    @Serializable
    @SerialName("status")
    data object Status : SlashCommand

    @Serializable
    @SerialName("help")
    data object Help : SlashCommand

    @Serializable
    @SerialName("skill_list")
    data class SkillList(
        val filter: String?,
    ) : SlashCommand
}
