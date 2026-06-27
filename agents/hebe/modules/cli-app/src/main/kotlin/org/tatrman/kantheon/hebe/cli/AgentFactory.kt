@file:Suppress(
    "LongParameterList",
    "TooGenericExceptionCaught",
    "MagicNumber",
    "NestedBlockDepth",
)

package org.tatrman.kantheon.hebe.cli

import org.tatrman.kantheon.hebe.api.Channel
import org.tatrman.kantheon.hebe.api.ChannelHealth
import org.tatrman.kantheon.hebe.api.IncomingMessage
import org.tatrman.kantheon.hebe.api.MemoryStore
import org.tatrman.kantheon.hebe.api.Observer
import org.tatrman.kantheon.hebe.api.OutboundMessage
import org.tatrman.kantheon.hebe.api.ReplyContext
import org.tatrman.kantheon.hebe.api.SecretLookup
import org.tatrman.kantheon.hebe.channels.ChannelManagerImpl
import org.tatrman.kantheon.hebe.channels.telegram.TelegramChannel
import org.tatrman.kantheon.hebe.channels.web.WebChannel
import org.tatrman.kantheon.hebe.config.HebeConfig
import org.tatrman.kantheon.hebe.config.SecretStoreProvider
import org.tatrman.kantheon.hebe.core.agent.HebeAgent
import org.tatrman.kantheon.hebe.core.compaction.Compactor
import org.tatrman.kantheon.hebe.core.compaction.PreemptivePruner
import org.tatrman.kantheon.hebe.core.cost.CostGuard
import org.tatrman.kantheon.hebe.core.submission.SubmissionParser
import org.tatrman.kantheon.hebe.memory.db.DbFactory
import org.tatrman.kantheon.hebe.memory.db.PgConnectionSpec
import org.tatrman.kantheon.hebe.memory.db.PgDb
import org.tatrman.kantheon.hebe.memory.db.PgDbFactory
import org.tatrman.kantheon.hebe.memory.workspace.WorkspaceFs
import org.tatrman.kantheon.hebe.memory.workspace.WorkspaceSeeder
import org.tatrman.kantheon.hebe.memory.embeddings.CachedEmbeddingProvider
import org.tatrman.kantheon.hebe.memory.embeddings.OpenAiCompatEmbeddingProvider
import org.tatrman.kantheon.hebe.memory.hygiene.HygieneScanner
import org.tatrman.kantheon.hebe.plugins.HebePluginManager
import org.tatrman.kantheon.hebe.plugins.Lifecycle
import org.tatrman.kantheon.hebe.plugins.PluginRegistrationStore
import org.tatrman.kantheon.hebe.plugins.host.HostFactory
import org.tatrman.kantheon.hebe.plugins.signature.SignatureVerifier
import org.tatrman.kantheon.hebe.providers.openai.HttpClientFactory
import org.tatrman.kantheon.hebe.providers.openai.OpenAiCompatProvider
import org.tatrman.kantheon.hebe.security.approval.ApprovalGate
import org.tatrman.kantheon.hebe.security.approval.PendingApprovalsRepo
import org.tatrman.kantheon.hebe.security.policy.LeakDetector
import org.tatrman.kantheon.hebe.security.policy.PolicyChain
import org.tatrman.kantheon.hebe.security.receipts.Receipts
import org.tatrman.kantheon.hebe.security.receipts.SigningKey
import org.tatrman.kantheon.hebe.tools.builtin.ask.AskUserTool
import org.tatrman.kantheon.hebe.tools.builtin.file.FileSystemAppendTool
import org.tatrman.kantheon.hebe.tools.builtin.file.FileSystemGlobTool
import org.tatrman.kantheon.hebe.tools.builtin.file.FileSystemListTool
import org.tatrman.kantheon.hebe.tools.builtin.file.FileSystemReadTool
import org.tatrman.kantheon.hebe.tools.builtin.file.FileSystemWriteTool
import org.tatrman.kantheon.hebe.tools.builtin.git.GitTool
import org.tatrman.kantheon.hebe.tools.builtin.http.HttpTool
import org.tatrman.kantheon.hebe.tools.builtin.memory.MemoryReadTool
import org.tatrman.kantheon.hebe.tools.builtin.memory.MemorySearchTool
import org.tatrman.kantheon.hebe.tools.builtin.memory.MemoryWriteTool
import org.tatrman.kantheon.hebe.tools.builtin.schedule.ScheduleTool
import org.tatrman.kantheon.hebe.tools.builtin.search.WebSearchTool
import org.tatrman.kantheon.hebe.tools.builtin.shell.ShellTool
import org.tatrman.kantheon.hebe.tools.dispatch.ToolDispatcher
import org.tatrman.kantheon.hebe.tools.dispatch.ToolRegistry
import org.tatrman.kantheon.hebe.tools.mcp.McpClientManager
import org.tatrman.kantheon.hebe.memory.MemoryStoreFactory
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.pf4j.DefaultPluginManager
import org.slf4j.Logger

object AgentFactory {
    data class AgentComponents(
        val agent: HebeAgent,
        val dispatcher: ToolDispatcher,
        val channelManager: ChannelManagerImpl,
        val webChannel: WebChannel,
        val telegramChannel: TelegramChannel?,
        val scheduler: SchedulerFacade?,
        val mcpClientManager: McpClientManagerFacade?,
        val shutdown: suspend () -> Unit,
    )

    interface SchedulerFacade {
        fun start(scope: CoroutineScope)
    }

    interface McpClientManagerFacade {
        suspend fun disconnectAll()
    }

    fun build(
        config: HebeConfig,
        secretStore: SecretStoreProvider,
        workspaceRoot: Path,
        observer: Observer,
        log: Logger,
        // P2 Stage 2.2 — the resolved axis model selects the LLM target
        // (`llm.source`). Defaults to the `local` preset so existing callers /
        // tests are unaffected (BYOK).
        axes: org.tatrman.kantheon.hebe.config.Axes =
            org.tatrman.kantheon.hebe.config.ProfileResolver.resolve(
                org.tatrman.kantheon.hebe.config.RawAxisConfig(),
            ),
    ): AgentComponents {
        val receiptsDir = workspaceRoot.resolve("receipts")
        val pluginsDir = workspaceRoot.resolve("plugins")
        val dbPath = workspaceRoot.resolve("hebe.db")

        // ── Infrastructure ────────────────────────────────────────────────

        val signingKey = runBlocking { SigningKey.bootstrap(secretStore) }
        val receipts = Receipts(receiptsDir, signingKey)

        val memoryDb = DbFactory.open(dbPath, observer)

        val workspaceFs = WorkspaceFs(workspaceRoot)
        WorkspaceSeeder.seedIfMissing(workspaceFs, workspaceRoot)

        // ── LLM provider ─────────────────────────────────────────────────

        val llmApiKey =
            runBlocking {
                org.tatrman.kantheon.hebe.config.SecretRef
                    .resolve(config.llm.apiKeySecret, secretStore) ?: ""
            }

        // P2 Stage 2.2 — `llm.source` selects the LLM target. `byok` (local) keeps
        // the existing client untouched; `gateway` / `gateway_with_byok_fallback`
        // point the *same* OpenAI-compat provider at the Kantheon llm-gateway with
        // auth + cost-attribution headers (the byok-fallback runtime is Stage 2.5).
        val usesGateway = axes.llm.source != org.tatrman.kantheon.hebe.config.LlmSource.BYOK
        val httpClient =
            if (usesGateway) {
                org.tatrman.kantheon.hebe.providers.openai.GatewayClient
                    .build(
                        apiKey = llmApiKey,
                        costCenter =
                            org.tatrman.kantheon.hebe.providers.openai.GatewayClient
                                .costCenter(axes.instanceId),
                    )
            } else {
                HttpClientFactory.create(llmApiKey)
            }
        if (usesGateway) {
            log.info("LLM target: llm-gateway ({}), cost-center hebe/{}", axes.llm.source.token, axes.instanceId)
        }

        val llmProvider: org.tatrman.kantheon.hebe.api.LlmProvider =
            OpenAiCompatProvider(
                baseUrl = config.llm.baseUrl,
                defaultModel = config.llm.defaultModel,
                httpClient = httpClient,
            )

        // ── Memory store ────────────────────────────────────────────────────

        val embeddingProvider =
            CachedEmbeddingProvider(
                OpenAiCompatEmbeddingProvider(
                    client = httpClient,
                    baseUrl = config.llm.baseUrl,
                    apiKey = llmApiKey,
                    model = config.llm.embeddingModel.ifEmpty { "text-embedding-3-small" },
                    dim = config.llm.embeddingDim,
                ),
            )
        val hygieneScanner = HygieneScanner()
        // P3 Stage 3.1 T6 — the memory backend is selected by the resolved
        // `storage.backend` axis (never the profile name), through MemoryStoreFactory.
        // `local`/`personal` resolve SQLITE (byte-for-byte unchanged). For `server`/`k8s`
        // the live PgDb is opened here from the `pg` secret (JDBC URL incl. creds, the
        // provision.sh / per-instance K8s Secret) into the `hebe_<instance_id>` schema;
        // real-Postgres boot is exercised in the integration tier (planning-conventions §4).
        val backend = memoryBackend(axes)
        val pgDb: PgDb? = if (backend == MemoryStoreFactory.Backend.POSTGRES) openPgDb(axes, secretStore, log) else null
        val memoryStore: MemoryStore =
            MemoryStoreFactory.create(
                backend = backend,
                sqliteDb = memoryDb,
                pgDb = pgDb,
                workspaceFs = workspaceFs,
                embeddings = embeddingProvider,
                hygieneScanner = hygieneScanner,
                observer = observer,
            )

        // ── Tool stack ────────────────────────────────────────────────────

        val registry = ToolRegistry()
        val secretLookup = buildSecretLookup(secretStore)
        registerBuiltinTools(registry, workspaceFs, secretLookup, memoryStore)

        val validators = PolicyChain.standard(config, workspaceRoot)

        val pendingApprovalsRepo = PendingApprovalsRepo(memoryDb.dataSource)
        val approvalGate: ApprovalGate = ApprovalGate(pendingApprovalsRepo)

        val leakDetector = LeakDetector()

        // P2 Stage 2.4 — tool posture from the `tools.posture` axis (k8s default
        // restricted) plus the per-instance `enable`/`disable` opt-in lists.
        val postureGate = postureGate(axes)

        val dispatcher =
            ToolDispatcher(
                registry = registry,
                validators = validators,
                approvalGate = approvalGate,
                memory = memoryStore,
                observer = observer,
                leakDetector = leakDetector,
                receipts = receipts,
                postureGate = postureGate,
            )

        // ── Cost guard ─────────────────────────────────────────────────
        val costGuard = CostGuard(memoryDb.dataSource, config, observer)

        // ── Compactor ─────────────────────────────────────────────────
        val compactorInstance = Compactor(llmProvider, workspaceFs, config)
        val compactor = PreemptivePruner(compactorInstance)

        // ── Channels ──────────────────────────────────────────────────────

        val webChannel = WebChannel()

        val telegramChannel: TelegramChannel? =
            if (config.channels.telegram.enabled) {
                val botToken =
                    runBlocking {
                        secretStore
                            .get(config.channels.telegram.botTokenSecret)
                            ?.let { String(it, Charsets.UTF_8) }
                            ?: ""
                    }
                if (botToken.isNotEmpty()) {
                    // P2 Stage 2.3 T5 — channel-identity enforcement. On a keycloak
                    // profile, admission is by `chat_user_map` (chat_id → Keycloak
                    // user) instead of the single-operator allowlist; the map must
                    // include the bound user or boot fails fast. On identity-less
                    // profiles the predicate is null and the operator allowlist
                    // applies unchanged. (Resolving the acting user for OBO is Phase 4.)
                    val identityCheck = channelIdentityCheck(axes, config.channels.telegram.chatUserMap)
                    TelegramChannel(
                        botToken = botToken,
                        operatorTelegramId = config.channels.telegram.operatorTelegramId,
                        identityCheck = identityCheck,
                    )
                } else {
                    log.warn("Telegram channel enabled but bot token not found in secret store")
                    null
                }
            } else {
                null
            }

        // ── Build agent ───────────────────────────────────────────────────

        val secretLookupForAgent = buildSecretLookup(secretStore)
        val systemPrompt = "You are Hebe, a local AI agent. Running with autonomy level: ${config.autonomy.level.name}."

        val agentToolsProvider = registry::list

        val agent =
            HebeAgent(
                sessionManager =
                    org.tatrman.kantheon.hebe.core.agent
                        .SessionManager(),
                submissionParser = SubmissionParser,
                channel = dummyChannel,
                memory = memoryStore,
                dispatcher = dispatcher,
                llmProvider = llmProvider,
                costGuard = costGuard,
                compactor = compactor,
                hooks =
                    org.tatrman.kantheon.hebe.core.hooks
                        .HookRunner(),
                observer = observer,
                approvalGate = approvalGate,
                secretLookup = secretLookupForAgent,
                secretStore = secretStore,
                systemPrompt = systemPrompt,
                toolsProvider = { _ -> agentToolsProvider().map { it.spec } },
                activeSkills = emptyList(),
            )

        val channelManager = ChannelManagerImpl(agent, observer)

        // ── Plugin loading ────────────────────────────────────────────────

        val pluginStore = PluginRegistrationStore()
        val pluginRegistryWrapper =
            object : Lifecycle.ToolRegistryWrapper {
                override fun register(
                    name: String,
                    tool: org.tatrman.kantheon.hebe.api.Tool,
                ) {
                    registry.register(tool)
                }

                override fun unregister(name: String) {
                    registry.unregister(name)
                }
            }
        val hostFactory =
            HostFactory(
                secretResolver = { name -> runBlocking { secretStore.get(name)?.let { String(it, Charsets.UTF_8) } } },
                observer = observer,
                logger = log,
            )
        val signatureVerifier =
            SignatureVerifier(
                signatureMode = config.security.pluginSignatureMode,
                trustedPublisherKeys = config.plugins.publisherKeys,
                log = log,
            )
        // DefaultPluginManager() breaks the Lifecycle↔HebePluginManager circular dependency;
        // its stopPlugin is only reached on plugin startup failures (error path, caught).
        val pluginLifecycle =
            Lifecycle(
                pluginManager = DefaultPluginManager(),
                pluginDir = pluginsDir,
                toolRegistry = pluginRegistryWrapper,
                hostFactory = hostFactory,
                signatureVerifier = signatureVerifier,
                observer = observer,
                pluginStore = pluginStore,
                secretResolver = { name -> runBlocking { secretStore.get(name)?.let { String(it, Charsets.UTF_8) } } },
                log = log,
            )
        val pluginManager = HebePluginManager(pluginsDir, pluginLifecycle)
        pluginManager.loadPlugins()
        pluginManager.startPlugins()

        // ── Scheduler ─────────────────────────────────────────────────────

        val jobRepo =
            org.tatrman.kantheon.hebe.scheduler
                .JobRepo(memoryDb)
        val routinesEngine =
            org.tatrman.kantheon.hebe.scheduler
                .RoutinesEngine(jobRepo)
        val jobRunner =
            org.tatrman.kantheon.hebe.scheduler.JobRunner(
                repo = jobRepo,
                memory = memoryStore,
                dispatcher = dispatcher,
                llmProvider = llmProvider,
                costGuard = costGuard,
                compactor = compactor,
                observer = observer,
                modelName = config.llm.defaultModel,
                systemPrompt = systemPrompt,
                tools = registry.list().map { it.spec },
            )
        val schedulerImpl =
            org.tatrman.kantheon.hebe.scheduler
                .Scheduler(jobRepo, jobRunner, routinesEngine)
        val schedulerFacade =
            object : SchedulerFacade {
                override fun start(scope: CoroutineScope) {
                    schedulerImpl.start(scope)
                }
            }

        // ── MCP client ─────────────────────────────────────────────────────

        val mcpClientManagerImpl =
            McpClientManager(
                registry = registry,
                secretLookup = secretLookup,
            )
        runBlocking {
            if (config.mcp.client.servers
                    .isNotEmpty()
            ) {
                mcpClientManagerImpl.connect(config.mcp.client.servers)
            }
        }
        val mcpClientManagerFacade =
            object : McpClientManagerFacade {
                override suspend fun disconnectAll() {
                    mcpClientManagerImpl.disconnectAll()
                }
            }

        // ── Shutdown ───────────────────────────────────────────────────────

        val shutdown: suspend () -> Unit = {
            log.info("shutting down agent components")
            runBlocking {
                channelManager.shutdown()
                mcpClientManagerImpl.disconnectAll()
                memoryDb.close()
                pgDb?.close()
                pluginManager.stopPlugins()
                pluginManager.unloadPlugins()
                httpClient.close()
            }
        }

        return AgentComponents(
            agent = agent,
            dispatcher = dispatcher,
            channelManager = channelManager,
            webChannel = webChannel,
            telegramChannel = telegramChannel,
            scheduler = schedulerFacade,
            mcpClientManager = mcpClientManagerFacade,
            shutdown = shutdown,
        )
    }

    /**
     * Maps the resolved `storage.backend` axis (never the profile name) to the
     * [MemoryStoreFactory] backend (P3 Stage 3.1 T6). `sqlite`/`postgres` select the
     * SQLite or Postgres [MemoryStore].
     */
    fun memoryBackend(axes: org.tatrman.kantheon.hebe.config.Axes): MemoryStoreFactory.Backend =
        when (axes.storage.backend) {
            org.tatrman.kantheon.hebe.config.StorageBackend.SQLITE -> MemoryStoreFactory.Backend.SQLITE
            org.tatrman.kantheon.hebe.config.StorageBackend.POSTGRES -> MemoryStoreFactory.Backend.POSTGRES
        }

    /**
     * Opens the per-instance Postgres pool for the `server`/`k8s` profiles. The
     * connection is the `pg` secret (a JDBC URL carrying its own credentials, created by
     * `deploy/provision.sh` and mounted via the per-instance K8s Secret); the schema is
     * `hebe_<instance_id>` (instance isolation, architecture §5.1). Fails fast with an
     * actionable message when the secret is absent — never a silent fallback to SQLite.
     */
    fun openPgDb(
        axes: org.tatrman.kantheon.hebe.config.Axes,
        secretStore: SecretStoreProvider,
        log: Logger,
    ): PgDb {
        val jdbcUrl =
            runBlocking { secretStore.get("pg")?.let { String(it, Charsets.UTF_8) } }
                ?.takeIf { it.isNotBlank() }
                ?: throw org.tatrman.kantheon.hebe.config.ConfigValidationException(
                    "storage.backend=postgres requires the 'pg' secret (a Postgres JDBC URL) — " +
                        "provision the instance (deploy/provision.sh) and populate the hebe-<id> Secret",
                )
        val schema = "hebe_${axes.instanceId}"
        log.info("opening Postgres memory backend (schema {})", schema)
        return PgDbFactory.open(PgConnectionSpec(jdbcUrl = jdbcUrl, schema = schema))
    }

    /**
     * Maps the resolved `tools.*` axes to the dispatch-layer [PostureGate]
     * (config's `Posture` enum is deliberately decoupled from dispatch's
     * `ToolPosture`). Shared by the agent path and the MCP-server path so both
     * dispatch surfaces enforce the same posture + opt-in lists.
     */
    fun postureGate(axes: org.tatrman.kantheon.hebe.config.Axes): org.tatrman.kantheon.hebe.tools.dispatch.PostureGate =
        org.tatrman.kantheon.hebe.tools.dispatch.PostureGate(
            posture =
                when (axes.tools.posture) {
                    org.tatrman.kantheon.hebe.config.Posture.FULL ->
                        org.tatrman.kantheon.hebe.tools.dispatch.ToolPosture.FULL
                    org.tatrman.kantheon.hebe.config.Posture.RESTRICTED ->
                        org.tatrman.kantheon.hebe.tools.dispatch.ToolPosture.RESTRICTED
                },
            enable = axes.tools.enable,
            disable = axes.tools.disable,
        )

    /**
     * Builds the Telegram admission predicate from the identity axes (P2 Stage
     * 2.3 T5). Returns `null` on identity-less profiles (caller keeps the
     * operator allowlist). On keycloak it requires a `bound_user` and validates
     * that `chat_user_map` includes it (boot fails fast), then admits a chat iff
     * it maps to a Keycloak user.
     */
    fun channelIdentityCheck(
        axes: org.tatrman.kantheon.hebe.config.Axes,
        chatUserMap: Map<String, String>,
    ): ((String) -> Boolean)? {
        if (axes.security.platformIdentity != org.tatrman.kantheon.hebe.config.PlatformIdentity.KEYCLOAK) {
            return null
        }
        val boundUser =
            axes.boundUser
                ?: throw org.tatrman.kantheon.hebe.config.ConfigValidationException(
                    "security.platform_identity=keycloak requires bound_user (the Keycloak user this instance acts as)",
                )
        org.tatrman.kantheon.hebe.security.auth.ChannelIdentityGuard
            .validateAtBoot(axes.security.platformIdentity, chatUserMap, boundUser)
        val guard =
            org.tatrman.kantheon.hebe.security.auth.ChannelIdentityGuard(
                platformIdentity = axes.security.platformIdentity,
                chatUserMap = chatUserMap,
            )
        return { chatId -> guard.isAllowed(chatId) }
    }

    private fun registerBuiltinTools(
        registry: ToolRegistry,
        workspaceFs: WorkspaceFs,
        secretLookup: org.tatrman.kantheon.hebe.api.SecretLookup,
        memoryStore: MemoryStore,
    ) {
        registry.register(FileSystemReadTool(workspaceFs))
        registry.register(FileSystemWriteTool(workspaceFs))
        registry.register(FileSystemAppendTool(workspaceFs))
        registry.register(FileSystemListTool(workspaceFs))
        registry.register(FileSystemGlobTool(workspaceFs))
        registry.register(ShellTool(workspaceFs.workspaceRoot))
        registry.register(HttpTool(secretLookup))
        registry.register(AskUserTool())
        registry.register(WebSearchTool(secretLookup))
        registry.register(MemoryReadTool(memoryStore))
        registry.register(MemoryWriteTool(memoryStore))
        registry.register(MemorySearchTool(memoryStore))
        registry.register(ScheduleTool())
        registry.register(GitTool(workspaceFs.workspaceRoot))
    }

    private fun buildSecretLookup(secretStore: SecretStoreProvider): org.tatrman.kantheon.hebe.api.SecretLookup =
        object : org.tatrman.kantheon.hebe.api.SecretLookup {
            override fun secret(name: String): String? = runBlocking { secretStore.get(name)?.let { String(it, Charsets.UTF_8) } }
        }

    private val dummyChannel: Channel =
        object : Channel {
            override val name: String = "agent-factory"

            override suspend fun start(scope: CoroutineScope): Flow<IncomingMessage> = flowOf()

            override suspend fun reply(
                ctx: ReplyContext,
                msg: OutboundMessage,
            ) {}

            override fun supportsDraftUpdates(): Boolean = false

            override suspend fun updateDraft(
                ctx: ReplyContext,
                partial: String,
            ) {}

            override suspend fun broadcast(
                userId: String,
                msg: OutboundMessage,
            ) {}

            override suspend fun healthCheck(): ChannelHealth = ChannelHealth.Up

            override suspend fun shutdown() {}
        }
}
