package org.tatrman.kantheon.themis

import com.typesafe.config.ConfigFactory
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey

object ResolverOtel {
    // NOTE (PT P0·S0.1 T3 census): this reports `service.name = "resolver-agent"`, a
    // pre-fork name, so Themis's Loki/Tempo label does NOT match its module directory.
    // Renaming would orphan existing dashboards and stored telemetry — left as a
    // Bora call, recorded in the arc's census.
    private val serviceName = "resolver-agent"

    /** Whether telemetry is on. Callers gate plugin installs on this (null SDK = no plugin). */
    val enabled: Boolean by lazy {
        val config = ConfigFactory.load()
        config.hasPath("telemetry.enabled") && config.getBoolean("telemetry.enabled")
    }

    val openTelemetry: OpenTelemetry by lazy {
        shared.otel.createOpenTelemetrySdk(
            shared.otel.OtelEndpointConfig(
                serviceName = serviceName,
                protocol = System.getenv("RESOLVER_OTEL_PROTOCOL") ?: "grpc",
            ),
            enabled = enabled,
        )
    }

    /**
     * **Load-bearing.** The shared `otel-config` lib builds its SDK without
     * `setPropagators(...)`, so `getPropagators()` returns `NoopTextMapPropagator` and
     * the Ktor telemetry plugins inject **no `traceparent`** — spans get created, every
     * hop starts a fresh trace, and nothing looks wrong in code.
     *
     * Duplicated from iris-bff's and golem's copies (these modules share no library).
     * The proper home is `otel-config` itself — see PT P0·S0.1 T5 notes.
     */
    fun OpenTelemetry.withW3CPropagators(): OpenTelemetry {
        val delegate = this
        val w3c =
            io.opentelemetry.context.propagation.ContextPropagators
                .create(
                    io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
                        .getInstance(),
                )
        return object : OpenTelemetry {
            override fun getTracerProvider() = delegate.tracerProvider

            override fun getMeterProvider() = delegate.meterProvider

            override fun getLogsBridge() = delegate.logsBridge

            override fun getPropagators() = w3c
        }
    }

    val tracer = openTelemetry.getTracer(serviceName)
    val meter = openTelemetry.getMeter(serviceName)

    val requestCounter =
        meter
            .counterBuilder("resolver_request_total")
            .setDescription("Total resolver requests")
            .setUnit("requests")
            .build()

    val confidenceHistogram =
        meter
            .histogramBuilder("resolver_confidence_distribution")
            .setDescription("Confidence distribution of resolved requests")
            .setUnit("percent")
            .ofLongs()
            .build()

    val hitlRoundCounter =
        meter
            .counterBuilder("resolver_hitl_round_count")
            .setDescription("Number of HITL clarification rounds")
            .setUnit("rounds")
            .build()

    val functionResolvedCounter =
        meter
            .counterBuilder("resolver_function_resolved_total")
            .setDescription("Total resolved function calls")
            .setUnit("calls")
            .build()

    val cacheHitCounter =
        meter
            .counterBuilder("resolver_cache_hit_total")
            .setDescription("Total cache hits by layer")
            .setUnit("hits")
            .build()

    // ---- Phase 3 routing metrics (architecture.md §10.2) -------------------
    // Seven Themis-observable instruments. The two `themis_capabilities_cache_*`
    // metrics in §10.2 are deferred: CapabilitiesReadClient (shared lib) keeps its
    // cache fully internal (no `fetchedAt`/stale hook), so wiring them is a small
    // shared-lib follow-up rather than a hollow instrument here.

    private val layerKey = AttributeKey.stringKey("layer")
    private val agentIdKey = AttributeKey.stringKey("agent_id")
    private val kindKey = AttributeKey.stringKey("kind")
    private val gapKindKey = AttributeKey.stringKey("gap_kind")

    private val routingLayerCounter =
        meter
            .counterBuilder("themis_routing_layer_total")
            .setDescription("routeToAgent decisions by cascade layer (0/1/2/3)")
            .setUnit("decisions")
            .build()

    private val routingChosenCounter =
        meter
            .counterBuilder("themis_routing_chosen_total")
            .setDescription("routeToAgent decisions by chosen agent")
            .setUnit("decisions")
            .build()

    private val routingConfidenceHistogram =
        meter
            .histogramBuilder("themis_routing_confidence")
            .setDescription("Confidence distribution of routing decisions")
            .setUnit("percent")
            .ofLongs()
            .build()

    private val intentKindCounter =
        meter
            .counterBuilder("themis_intent_kind_total")
            .setDescription("classifyIntentKind results by intent kind")
            .setUnit("classifications")
            .build()

    private val intentKindLlmFallbackCounter =
        meter
            .counterBuilder("themis_intent_kind_llm_fallback_total")
            .setDescription("classifyIntentKind tie-breaks that fell back to the CHEAP-tier LLM")
            .setUnit("classifications")
            .build()

    private val multiQuestionDetectedCounter =
        meter
            .counterBuilder("themis_multi_question_detected_total")
            .setDescription("Turns where detectMultiQuestion fired (SPLIT)")
            .setUnit("turns")
            .build()

    private val refusalCounter =
        meter
            .counterBuilder("themis_refusal_total")
            .setDescription("STRICT-mode RefusalWithGaps emissions, by gap kind")
            .setUnit("gaps")
            .build()

    fun recordRoutingDecision(
        layer: Int,
        agentId: String,
        confidence: Double,
    ) {
        routingLayerCounter.add(
            1,
            io.opentelemetry.api.common.Attributes
                .of(layerKey, layer.toString()),
        )
        if (agentId.isNotEmpty()) {
            routingChosenCounter.add(
                1,
                io.opentelemetry.api.common.Attributes
                    .of(agentIdKey, agentId),
            )
        }
        routingConfidenceHistogram.record((confidence * 100).toLong())
    }

    fun recordIntentKind(
        kind: String,
        llmFallback: Boolean,
    ) {
        intentKindCounter.add(
            1,
            io.opentelemetry.api.common.Attributes
                .of(kindKey, kind),
        )
        if (llmFallback) intentKindLlmFallbackCounter.add(1)
    }

    fun recordMultiQuestionDetected() {
        multiQuestionDetectedCounter.add(1)
    }

    fun recordRefusal(gapKind: String) {
        refusalCounter.add(
            1,
            io.opentelemetry.api.common.Attributes
                .of(gapKindKey, gapKind),
        )
    }

    private val cacheSizeHolder =
        java.util
            .concurrent
            .atomic
            .AtomicLong(0)

    init {
        meter
            .gaugeBuilder("resolver_cache_size")
            .setDescription("Current cache size by layer")
            .setUnit("entries")
            .ofLongs()
            .buildWithCallback { measurement ->
                measurement.record(
                    cacheSizeHolder.get(),
                    io.opentelemetry.api.common.Attributes.of(
                        AttributeKey.stringKey("layer"),
                        "nlp",
                    ),
                )
            }
    }

    fun updateCacheSize(size: Long) {
        cacheSizeHolder.set(size)
    }
}
