package org.tatrman.kantheon.hebe.core.submission

import org.tatrman.kantheon.hebe.api.SlashCommand
import org.tatrman.kantheon.hebe.api.Submission
import java.util.UUID
import kotlin.time.Clock
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

class SubmissionParserTest {
    private fun makeMsg(
        content: String,
        metadata: Map<String, String> = emptyMap(),
    ) = org.tatrman.kantheon.hebe.api.IncomingMessage(
        id = UUID.randomUUID(),
        channel = "test",
        userId = "user1",
        senderId = "sender1",
        content = content,
        attachments = emptyList(),
        threadId = null,
        metadata =
            buildMap {
                metadata.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
            }.let { kotlinx.serialization.json.JsonObject(it) },
        receivedAt = Clock.System.now(),
    )

    @Test
    fun `plain text becomes UserInput`() {
        val msg = makeMsg("Hello, world")
        val result = SubmissionParser.parse(msg)
        assert(result is Submission.UserInput)
    }

    @Test
    fun `authMode flag takes precedence`() {
        val msg = makeMsg("secret_token", mapOf("authMode" to "true"))
        val result = SubmissionParser.parse(msg)
        assert(result is Submission.AuthMode)
        val authMode = result as Submission.AuthMode
        assert(authMode.purpose == "")
        assert(authMode.secret == "secret_token")
    }

    @Test
    fun `quit command parsed`() {
        val result = SubmissionParser.parse(makeMsg("/quit"))
        assert(result is Submission.QuitCommand)
    }

    @Test
    fun `exit command parsed`() {
        val result = SubmissionParser.parse(makeMsg("/exit"))
        assert(result is Submission.QuitCommand)
    }

    @Test
    fun `approve with id parsed`() {
        val result = SubmissionParser.parse(makeMsg("/approve 8a4f"))
        assert(result is Submission.Approval)
        val approval = result as Submission.Approval
        assert(approval.approvalId == "8a4f")
        assert(approval.approved)
    }

    @Test
    fun `deny with id parsed`() {
        val result = SubmissionParser.parse(makeMsg("/deny abc123"))
        assert(result is Submission.Approval)
        val approval = result as Submission.Approval
        assert(approval.approvalId == "abc123")
        assert(!approval.approved)
    }

    @Test
    fun `compact command parsed`() {
        val result = SubmissionParser.parse(makeMsg("/compact"))
        assert(result is Submission.SystemCommand)
        assert((result as Submission.SystemCommand).command == SlashCommand.Compact)
    }

    @Test
    fun `status command parsed`() {
        val result = SubmissionParser.parse(makeMsg("/status"))
        assert(result is Submission.SystemCommand)
        assert((result as Submission.SystemCommand).command == SlashCommand.Status)
    }

    @Test
    fun `help command parsed`() {
        val result = SubmissionParser.parse(makeMsg("/help"))
        assert(result is Submission.SystemCommand)
        assert((result as Submission.SystemCommand).command == SlashCommand.Help)
    }

    @Test
    fun `skills command parsed with filter`() {
        val result = SubmissionParser.parse(makeMsg("/skills file"))
        assert(result is Submission.SystemCommand)
        val sc = result as Submission.SystemCommand
        assert(sc.command is SlashCommand.SkillList)
        assert((sc.command as SlashCommand.SkillList).filter == "file")
    }

    @Test
    fun `skills command parsed without filter`() {
        val result = SubmissionParser.parse(makeMsg("/skills"))
        assert(result is Submission.SystemCommand)
        val sc = result as Submission.SystemCommand
        assert(sc.command is SlashCommand.SkillList)
        assert((sc.command as SlashCommand.SkillList).filter == null)
    }

    @Test
    fun `unknown slash command falls back to UserInput`() {
        val result = SubmissionParser.parse(makeMsg("/unknown_command"))
        assert(result is Submission.UserInput)
    }

    @Test
    fun `text starting with slash but not a command falls back to UserInput`() {
        val result = SubmissionParser.parse(makeMsg("/hello world"))
        assert(result is Submission.UserInput)
    }
}
