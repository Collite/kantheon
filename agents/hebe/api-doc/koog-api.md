# Koog 0.8.0 API Documentation

This document collects findings from studying the koog 0.8.0 source code at `/Users/bora/Dev/view-only/koog`.

**Status: IN PROGRESS — expanding weekly**

---

## 1. Core Types

### `AIAgent<Input, Output>` (abstract class)

File: `agents/agents-core/src/commonMain/kotlin/ai/koog/agents/core/agent/AIAgent.kt`

```kotlin
public expect abstract class AIAgent<Input, Output>() : Closeable {
    public abstract val id: String
    public abstract val agentConfig: AIAgentConfig

    public abstract suspend fun run(agentInput: Input, sessionId: String? = null): Output
    public abstract fun createSession(sessionId: String? = null): AIAgentRunSession<Input, Output, out AIAgentContext>

    public companion object {
        @JvmStatic
        public fun builder(): AIAgentBuilder
    }
}
```

### `GraphAIAgent<Input, Output>`

File: `agents/agents-core/src/commonMain/kotlin/ai/koog/agents/core/agent/GraphAIAgent.kt`

```kotlin
public open class GraphAIAgent<Input, Output>(
    public val promptExecutor: PromptExecutor,
    override val agentConfig: AIAgentConfig,
    override val strategy: AIAgentGraphStrategy<Input, Output>,
    public val toolRegistry: ToolRegistry = ToolRegistry.EMPTY,
    id: String? = null,
    public val clock: KoogClock = KoogClock.System,
    @property:InternalAgentsApi
    public val installFeatures: FeatureContext.() -> Unit = {}
) : AIAgentBase<Input, Output, AIAgentGraphContextBase>(...)
```

### `AIAgentBuilder`

File: `agents/agents-core/src/commonMain/kotlin/ai/koog/agents/core/agent/AIAgentBuilder.kt`

`AIAgent.builder()` returns `AIAgentBuilder` (expect class, JVM actual).

Builder methods:
- `.promptExecutor(PromptExecutor)`
- `.llmModel(LLModel)`
- `.toolRegistry(ToolRegistry)`
- `.systemPrompt(String)`
- `.prompt(Prompt)`
- `.temperature(Double)`
- `.maxIterations(Int)`
- `.id(String?)`
- `.clock(KoogClock)`
- `.agentConfig(AIAgentConfig)`
- `.install(feature, configure)`

### `AIAgentConfig`

```kotlin
public expect class AIAgentConfig(
    prompt: Prompt,
    model: LLModel,
    maxAgentIterations: Int,
    missingToolsConversionStrategy: MissingToolsConversionStrategy = MissingToolsConversionStrategy.Missing(ToolCallDescriber.JSON),
    responseProcessor: ResponseProcessor? = null,
    serializer: JSONSerializer = KotlinxSerializer(),
) : AIAgentConfigBase {
    public val prompt: Prompt
    public val model: LLModel
    public val maxAgentIterations: Int
    public val responseProcessor: ResponseProcessor?
    public val missingToolsConversionStrategy: MissingToolsConversionStrategy
    public val serializer: JSONSerializer

    public companion object {
        public fun withSystemPrompt(
            prompt: String,
            llm: LLModel = OpenAIModels.Chat.GPT4o,
            id: String = "koog-agents",
            maxAgentIterations: Int = 3,
        ): AIAgentConfig
    }
}
```

---

## 2. `PromptExecutor`

```kotlin
public expect abstract class PromptExecutor() : PromptExecutorAPI

public interface PromptExecutorAPI : AutoCloseable {
    public suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor> = emptyList()
    ): List<Message.Response>  // Note: returns LIST, one per choice

    public fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor> = emptyList()
    ): Flow<StreamFrame>

    public suspend fun executeMultipleChoices(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): List<LLMChoice> = listOf(execute(prompt, model, tools))

    public suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult

    public suspend fun models(): List<LLModel> {
        throw UnsupportedOperationException("Not implemented for this executor")
    }

    public fun getStandardJsonSchemaGenerator(model: LLModel): StandardJsonSchemaGenerator
    public fun getBasicJsonSchemaGenerator(model: LLModel): BasicJsonSchemaGenerator

    override fun close() {}
}
```

**Key:** `execute` returns `List<Message.Response>` (multiple choices), not a single response.

---

## 3. `Message`

```kotlin
@Serializable
public sealed interface Message {
    public val content: String  // computed: parts.filterIsInstance<Text>().joinToString("\n")
    public val parts: List<ContentPart>
    public val role: Role
    public val metaInfo: MessageMetaInfo

    public sealed interface Request : Message { override val metaInfo: RequestMetaInfo }
    public sealed interface Response : Message { override val metaInfo: ResponseMetaInfo; public fun copy(updatedMetaInfo: ResponseMetaInfo): Response }

    public enum class Role { System, User, Assistant, Reasoning, Tool }

    public data class User(
        override val parts: List<ContentPart>,
        override val metaInfo: RequestMetaInfo,
        val cacheControl: CacheControl? = null,
    ) : Request {
        constructor(content: String, metaInfo: RequestMetaInfo, cacheControl: CacheControl? = null)
        override val role: Role = Role.User
    }

    public data class Assistant(
        override val parts: List<ContentPart>,
        override val metaInfo: ResponseMetaInfo,
        val finishReason: String? = null,
        val cacheControl: CacheControl? = null,
    ) : Response {
        constructor(content: String, metaInfo: ResponseMetaInfo, finishReason: String? = null, cacheControl: CacheControl? = null)
        override val role: Role = Role.Assistant
        override fun copy(updatedMetaInfo: ResponseMetaInfo): Assistant = this.copy(metaInfo = updatedMetaInfo)
    }

    public data class Reasoning(
        public val id: String? = null,
        public val encrypted: String? = null,
        override val parts: List<ContentPart.Text>,
        public val summary: List<ContentPart.Text>? = null,
        override val metaInfo: ResponseMetaInfo
    ) : Response {
        override val role: Role = Role.Reasoning
        override fun copy(updatedMetaInfo: ResponseMetaInfo): Reasoning = this.copy(metaInfo = updatedMetaInfo)
    }

    public sealed interface Tool : Message {
        public val id: String?
        public val tool: String

        public data class Call(
            override val id: String?,
            override val tool: String,
            override val parts: List<ContentPart.Text>,
            override val metaInfo: ResponseMetaInfo
        ) : Tool, Response {
            constructor(id: String?, tool: String, content: String, metaInfo: ResponseMetaInfo)
            override val role: Role = Role.Tool
            val contentJsonResult: Result<JsonObject>
            val contentJson: JsonObject
            override fun copy(updatedMetaInfo: ResponseMetaInfo): Call = this.copy(metaInfo = updatedMetaInfo)
        }

        public data class Result(
            override val id: String?,
            override val tool: String,
            override val parts: List<ContentPart.Text>,
            override val metaInfo: RequestMetaInfo,
            val isError: Boolean = false,
            val cacheControl: CacheControl? = null,
        ) : Tool, Request {
            constructor(id: String?, tool: String, content: String, metaInfo: RequestMetaInfo, isError: Boolean = false, cacheControl: CacheControl? = null)
            override val role: Role = Role.Tool
        }
    }

    public data class System(
        override val parts: List<ContentPart.Text>,
        override val metaInfo: RequestMetaInfo,
        val cacheControl: CacheControl? = null
    ) : Request {
        constructor(content: String, metaInfo: RequestMetaInfo, cacheControl: CacheControl? = null)
        override val role: Role = Role.System
    }
}
```

**Key:** Tool calls are `Message.Tool.Call` — NOT a separate `ToolCall` type. The tool name is in `tool` field, call ID in `id` field, args in `content` (as JSON string). Tool results are `Message.Tool.Result`.

**ContentPart:** `Message` uses `parts: List<ContentPart>` which includes `ContentPart.Text` and `ContentPart.Attachment`. The `content: String` computed property joins all text parts.

---

## 4. `StreamFrame`

```kotlin
@Serializable
public sealed interface StreamFrame {
    public sealed interface CompleteFrame : StreamFrame { public val index: Int? }
    public sealed interface DeltaFrame : StreamFrame { public val index: Int? }

    @Serializable
    public data class TextDelta(val text: String, override val index: Int? = null) : DeltaFrame, StreamFrame

    @Serializable
    public data class TextComplete(val text: String, override val index: Int? = null) : CompleteFrame, StreamFrame

    @Serializable
    public data class ReasoningDelta(val id: String? = null, val text: String? = null, val summary: String? = null, override val index: Int? = null) : DeltaFrame, StreamFrame

    @Serializable
    public data class ReasoningComplete(val id: String?, val text: List<String>, val summary: List<String>? = null, public val encrypted: String? = null, override val index: Int? = null) : CompleteFrame, StreamFrame

    @Serializable
    public data class ToolCallDelta(val id: String?, val name: String?, val content: String?, override val index: Int? = null) : DeltaFrame, StreamFrame

    @Serializable
    public data class ToolCallComplete(val id: String?, val name: String, val content: String, override val index: Int? = null) : CompleteFrame, StreamFrame {
        val contentJsonResult: Result<JsonObject>
        val contentJson: JsonObject
    }

    @Serializable
    public data class End(val finishReason: String? = null, val metaInfo: ResponseMetaInfo = ResponseMetaInfo.Empty) : StreamFrame
}
```

**Key:** No `Done` — use `End`. Tool calls stream as `ToolCallDelta` (partial) → `ToolCallComplete` (complete with full JSON args in `content`).

---

## 5. `Prompt`

```kotlin
@Serializable
public data class Prompt(
    val messages: List<Message>,
    val id: String,
    val params: LLMParams = LLMParams()
) {
    public companion object {
        @JvmField
        public val Empty: Prompt = Prompt(emptyList(), "default")

        @JvmStatic
        public fun builder(id: String, clock: KoogClock = KoogClock.System): PromptBuilder

        @JvmOverloads
        public fun build(id: String, params: LLMParams = LLMParams(), clock: KoogClock = KoogClock.System, init: PromptBuilder.() -> Unit): Prompt

        public fun build(prompt: Prompt, clock: KoogClock = KoogClock.System, init: PromptBuilder.() -> Unit): Prompt
    }

    public val latestTokenUsage: Int
    public val totalTimeSpent: Duration

    public fun withMessages(update: (List<Message>) -> List<Message>): Prompt
    public fun withParams(newParams: LLMParams): Prompt
    public fun withUpdatedParams(update: LLMParamsUpdateContext.() -> Unit): Prompt
}
```

---

## 6. `LLModel`

```kotlin
@Serializable
public data class LLModel(
    val provider: LLMProvider,
    val id: String,
    val capabilities: List<LLMCapability>? = null,
    val contextLength: Long? = null,
    val maxOutputTokens: Long? = null,
) {
    public fun supports(capability: LLMCapability): Boolean
}
```

---

## 7. `ToolDescriptor` and `ToolRegistry`

```kotlin
public open class ToolDescriptor(
    public val name: String,
    public val description: String,
    public val requiredParameters: List<ToolParameterDescriptor> = emptyList(),
    public val optionalParameters: List<ToolParameterDescriptor> = emptyList(),
    public val cacheControl: CacheControl? = null,
)

public class ToolRegistry internal constructor(tools: List<Tool<*, *>> = emptyList()) {
    public constructor(init: ToolRegistryBuilder.() -> Unit)
    public val tools: List<Tool<*, *>>
    public fun getToolOrNull(toolName: String): Tool<*, *>?
    public companion object {
        public val EMPTY: ToolRegistry
        public fun builder(): ToolRegistryBuilder
    }
}
```

---

## 8. `singleRunStrategy`

```kotlin
@JvmOverloads
public fun singleRunStrategy(runMode: ToolCalls = ToolCalls.SEQUENTIAL): AIAgentGraphStrategy<String, String>

public enum class ToolCalls {
    SEQUENTIAL,      // multiple tool calls, sequential execution
    PARALLEL,        // multiple tool calls, parallel execution
    SINGLE_RUN_SEQUENTIAL  // one tool call per step
}
```

Returns `AIAgentGraphStrategy<String, String>` — both input and output are String.

---

## 9. Minimal Agent Pattern (from trip-planning example)

```kotlin
val agent = AIAgent<String, String>()
    .promptExecutor(promptExecutor)
    .llmModel(OpenAIModels.Chat.GPT4o)
    .toolRegistry(ToolRegistry {
        tool(::myTool)
        tools(myToolInstances)
    })
    .systemPrompt("You are a helpful assistant.")
    .maxIterations(50)
    .build()

val result = agent.run("Hello world")
```

Or with a custom graph strategy:

```kotlin
val strategy = strategy<String, String>("my-strategy") {
    val nodeA by nodeLLMRequest()
    val nodeB by nodeExecuteTool()
    edge(nodeStart forwardTo nodeA)
    edge(nodeA forwardTo nodeB onToolCall { true })
    edge(nodeB forwardTo nodeFinish)
}

val agent = GraphAIAgent(
    promptExecutor = promptExecutor,
    agentConfig = AIAgentConfig.withSystemPrompt("You are helpful"),
    strategy = strategy,
    toolRegistry = toolRegistry,
)
```

---

## 10. Key Differences from Prior Broken Implementation

| My Assumption | Actual Koog 0.8.0 |
|---|---|
| `Prompt` has `blocks: List<PromptBlock>` | `Prompt` has `messages: List<Message>` directly |
| `Message.Response` has `toolCallResults: List<...>` | Tool calls are separate `Message.Tool.Call` messages in the messages list |
| `StreamFrame.Done` exists | Use `StreamFrame.End` |
| `execute` returns single `Message.Response` | Returns `List<Message.Response>` (one per choice) |
| `PromptExecutor` is an interface | It's an `expect abstract class` implementing `PromptExecutorAPI` |
| `GraphAIAgent` constructed with just promptExecutor + agentConfig | Requires `strategy: AIAgentGraphStrategy<Input, Output>` |
| `AIAgentBuilder` has `.model()` | Use `.llmModel(LLModel)` |

---

## 11. Open Questions

1. How does `AIAgentConfigBase` work?
2. What is `ResponseProcessor` used for?
3. How does `install(feature) { ... }` on the builder translate to `GraphAIAgent.FeatureContext.install()`?
4. What is the full `PromptBuilder` DSL?
5. How does koog handle multi-turn conversations internally (session management)?
6. How does `AIAgentBuilder` actually resolve the expect/actual for the JVM platform?