@file:Suppress("CyclomaticComplexMethod", "ReturnCount")

package org.tatrman.kantheon.hebe.core.submission

import org.tatrman.kantheon.hebe.api.IncomingMessage
import org.tatrman.kantheon.hebe.api.SlashCommand
import org.tatrman.kantheon.hebe.api.Submission
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

object SubmissionParser {
    fun parse(msg: IncomingMessage): Submission {
        if (msg.metadata["authMode"]?.safeString() == "true") {
            val purpose = msg.metadata["authPurpose"]?.safeString() ?: ""
            val secret = msg.content
            return Submission.AuthMode(msg, purpose, secret)
        }

        val content = msg.content
        if (content.startsWith("/")) {
            val parts = content.split(" ", limit = 2)
            val command = parts[0].lowercase()
            val args = parts.getOrNull(1)?.trim() ?: ""

            return when {
                command == "/quit" || command == "/exit" -> {
                    Submission.QuitCommand(msg)
                }
                command == "/approve" && args.isNotEmpty() -> {
                    Submission.Approval(msg, args, approved = true)
                }
                command == "/deny" && args.isNotEmpty() -> {
                    Submission.Approval(msg, args, approved = false)
                }
                command == "/compact" -> {
                    Submission.SystemCommand(msg, SlashCommand.Compact)
                }
                command == "/status" -> {
                    Submission.SystemCommand(msg, SlashCommand.Status)
                }
                command == "/help" -> {
                    Submission.SystemCommand(msg, SlashCommand.Help)
                }
                command == "/skills" -> {
                    Submission.SystemCommand(msg, SlashCommand.SkillList(filter = args.ifEmpty { null }))
                }
                else -> {
                    Submission.UserInput(msg)
                }
            }
        }

        return Submission.UserInput(msg)
    }

    private fun JsonElement.safeString(): String = (this as? JsonPrimitive)?.content ?: ""
}
