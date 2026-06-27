package org.tatrman.kantheon.hebe.api

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SerializationRoundTripTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            classDiscriminator = "type"
        }

    @Test
    fun `RiskLevel round-trips`() {
        listOf(RiskLevel.Low, RiskLevel.Medium, RiskLevel.High).forEach {
            assertEquals(it, json.decodeFromString<RiskLevel>(json.encodeToString(it)))
        }
    }

    @Test
    fun `ProviderCapabilities round-trips`() {
        val caps =
            ProviderCapabilities(
                streaming = true,
                toolUse = true,
                multimodal = false,
                maxContextTokens = 128000,
                supportsPromptCaching = true,
            )
        val encoded = json.encodeToString(caps)
        val decoded = json.decodeFromString<ProviderCapabilities>(encoded)
        assertEquals(caps, decoded)
    }

    @Test
    fun `ChatMessage User round-trips`() {
        val msg = ChatMessage.User("hello", emptyList())
        val encoded = json.encodeToString(ChatMessage.serializer(), msg)
        val decoded = json.decodeFromString(ChatMessage.serializer(), encoded)
        assertEquals(msg, decoded)
    }

    @Test
    fun `ChatMessage Assistant round-trips`() {
        val msg = ChatMessage.Assistant("hi there", emptyList())
        val encoded = json.encodeToString(ChatMessage.serializer(), msg)
        val decoded = json.decodeFromString(ChatMessage.serializer(), encoded)
        assertEquals(msg, decoded)
    }

    @Test
    fun `ChatMessage ToolResult round-trips`() {
        val msg = ChatMessage.ToolResult("call-1", "result content", false)
        val encoded = json.encodeToString(ChatMessage.serializer(), msg)
        val decoded = json.decodeFromString(ChatMessage.serializer(), encoded)
        assertEquals(msg, decoded)
    }

    @Test
    fun `ToolChoice variants round-trip`() {
        val autoEnc = json.encodeToString(ToolChoice.serializer(), ToolChoice.Auto)
        val autoDec = json.decodeFromString(ToolChoice.serializer(), autoEnc)
        assertEquals(ToolChoice.Auto, autoDec)

        val noneEnc = json.encodeToString(ToolChoice.serializer(), ToolChoice.None)
        val noneDec = json.decodeFromString(ToolChoice.serializer(), noneEnc)
        assertEquals(ToolChoice.None, noneDec)

        val reqEnc = json.encodeToString(ToolChoice.serializer(), ToolChoice.Required)
        val reqDec = json.decodeFromString(ToolChoice.serializer(), reqEnc)
        assertEquals(ToolChoice.Required, reqDec)

        val specific = ToolChoice.Specific("my_tool")
        val specEnc = json.encodeToString(ToolChoice.serializer(), specific)
        val specDec = json.decodeFromString(ToolChoice.serializer(), specEnc)
        assertEquals(specific, specDec)
    }

    @Test
    fun `StreamEvent variants round-trip`() {
        val ser = StreamEvent.serializer()
        val td = StreamEvent.TextDelta("hi")
        val tdEnc = json.encodeToString(ser, td)
        assertEquals(td, json.decodeFromString(ser, tdEnc))

        val doneEnc = json.encodeToString(ser, StreamEvent.Done)
        assertEquals(StreamEvent.Done, json.decodeFromString(ser, doneEnc))

        val tu = StreamEvent.TokenUsage(100, 50, 0)
        val tuEnc = json.encodeToString(ser, tu)
        assertEquals(tu, json.decodeFromString(ser, tuEnc))
    }

    @Test
    fun `ExternalThreadId Trusted round-trips`() {
        val id = ExternalThreadId.Trusted("thread-123")
        val encoded = json.encodeToString(ExternalThreadId.serializer(), id)
        val decoded = json.decodeFromString(ExternalThreadId.serializer(), encoded)
        assertEquals(id, decoded)
    }

    @Test
    fun `ExternalThreadId Untrusted round-trips`() {
        val id = ExternalThreadId.Untrusted("thread-456")
        val encoded = json.encodeToString(ExternalThreadId.serializer(), id)
        val decoded = json.decodeFromString(ExternalThreadId.serializer(), encoded)
        assertEquals(id, decoded)
    }

    @Test
    fun `ChannelHealth round-trips`() {
        listOf(ChannelHealth.Up, ChannelHealth.Degraded, ChannelHealth.Down).forEach {
            assertEquals(it, json.decodeFromString<ChannelHealth>(json.encodeToString(it)))
        }
    }

    @Test
    fun `ChatRole round-trips`() {
        listOf(ChatRole.User, ChatRole.Assistant, ChatRole.System, ChatRole.Tool).forEach {
            assertEquals(it, json.decodeFromString<ChatRole>(json.encodeToString(it)))
        }
    }

    @Test
    fun `HitSource round-trips`() {
        listOf(HitSource.Fts, HitSource.Vector, HitSource.Both).forEach {
            assertEquals(it, json.decodeFromString<HitSource>(json.encodeToString(it)))
        }
    }

    @Test
    fun `MemoryScope round-trips`() {
        val ser = MemoryScope.serializer()
        listOf(MemoryScope.Default, MemoryScope.Identity, MemoryScope.Daily).forEach { scope ->
            val enc = json.encodeToString(ser, scope)
            assertEquals(scope, json.decodeFromString(ser, enc))
        }
    }

    @Test
    fun `SlashCommand variants round-trip`() {
        val ser = SlashCommand.serializer()
        assertEquals(
            SlashCommand.Compact,
            json.decodeFromString(ser, json.encodeToString(ser, SlashCommand.Compact)),
        )
        assertEquals(
            SlashCommand.Status,
            json.decodeFromString(ser, json.encodeToString(ser, SlashCommand.Status)),
        )
        assertEquals(
            SlashCommand.Help,
            json.decodeFromString(ser, json.encodeToString(ser, SlashCommand.Help)),
        )
        val sk = SlashCommand.SkillList("code")
        assertEquals(sk, json.decodeFromString(ser, json.encodeToString(ser, sk)))
    }

    @Test
    fun `Submission variants round-trip`() {
        val incomingMsg =
            IncomingMessage(
                id = java.util.UUID.randomUUID(),
                channel = "cli",
                userId = "user1",
                senderId = "user1",
                content = "hello",
                attachments = emptyList(),
                threadId = null,
                metadata = JsonObject(mapOf()),
                receivedAt =
                    kotlin.time.Clock.System
                        .now(),
                isInternal = false,
                isAgentBroadcast = false,
                triggeringMissionId = null,
            )
        val sub1: Submission = Submission.UserInput(incomingMsg)
        val enc1 = json.encodeToString(Submission.serializer(), sub1)
        val dec1 = json.decodeFromString(Submission.serializer(), enc1)
        assertEquals(sub1, dec1)

        val sub2: Submission = Submission.QuitCommand(incomingMsg)
        val enc2 = json.encodeToString(Submission.serializer(), sub2)
        val dec2 = json.decodeFromString(Submission.serializer(), enc2)
        assertEquals(sub2, dec2)

        val sub3: Submission = Submission.SystemCommand(incomingMsg, SlashCommand.Compact)
        val enc3 = json.encodeToString(Submission.serializer(), sub3)
        val dec3 = json.decodeFromString(Submission.serializer(), enc3)
        assertEquals(sub3, dec3)
    }

    @Test
    fun `HandleOutcome Done round-trips`() {
        val outcome = HandleOutcome.Done(OutboundMessage("hello", emptyList(), null))
        val encoded = json.encodeToString(HandleOutcome.serializer(), outcome)
        val decoded = json.decodeFromString(HandleOutcome.serializer(), encoded)
        assertEquals(outcome, decoded)
    }

    @Test
    fun `HandleOutcome Failed round-trips`() {
        val outcome = HandleOutcome.Failed("something went wrong")
        val encoded = json.encodeToString(HandleOutcome.serializer(), outcome)
        val decoded = json.decodeFromString(HandleOutcome.serializer(), encoded)
        assertEquals(outcome, decoded)
    }
}
