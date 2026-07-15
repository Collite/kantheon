package org.tatrman.pinakes

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import org.tatrman.pinakes.catalog.InMemoryAssetCatalog
import org.tatrman.pinakes.catalog.LineageStore
import org.tatrman.pinakes.clients.HttpCorpusPageWriter
import org.tatrman.pinakes.clients.HttpKallimachosWriteClient
import org.tatrman.pinakes.clients.HttpLlmGatewayClient
import org.tatrman.pinakes.compile.ContradictionDetector
import org.tatrman.pinakes.compile.Linker
import org.tatrman.pinakes.compile.WikiCompiler
import org.tatrman.pinakes.grpc.PinakesServiceImpl
import org.tatrman.pinakes.pipeline.EmbedSpec
import org.tatrman.pinakes.pipeline.Pipeline
import org.tatrman.pinakes.pipeline.PipelineDefs
import org.tatrman.pinakes.pipeline.PipelineRegistry
import org.tatrman.pinakes.pipeline.PipelineService
import org.tatrman.pinakes.pipeline.Runner
import org.tatrman.pinakes.pipeline.StageLibrary
import org.tatrman.pinakes.pipeline.stages.ChunkStage
import org.tatrman.pinakes.pipeline.stages.ClassifyStage
import org.tatrman.pinakes.pipeline.stages.CompileStage
import org.tatrman.pinakes.pipeline.stages.EmbedStage
import org.tatrman.pinakes.pipeline.stages.ExtractStage
import org.tatrman.pinakes.pipeline.stages.LinkStage
import org.tatrman.pinakes.pipeline.stages.LoadStage
import org.tatrman.pinakes.pipeline.stages.ResolveStage
import org.tatrman.pinakes.resolve.EntityResolver
import org.tatrman.pinakes.resolve.InMemoryConceptIndex
import org.tatrman.pinakes.stage.SeaweedAssetStore
import java.io.File
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import java.net.URI
import java.util.concurrent.TimeUnit

private val log = LoggerFactory.getLogger("org.tatrman.pinakes.Application")

/**
 * Bootstrap. The write-path service (architecture §2): stage raw assets to
 * Seaweed + catalogue them; the mechanical pipeline loads them into Kallimachos.
 * gRPC `PinakesService` on 7281; Ktor probes on 7280.
 */
fun main() {
    val config = ConfigFactory.load()
    val httpPort = config.getInt("pinakes.http.port")
    val grpcPort = config.getInt("pinakes.grpc.port")

    val meterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    val s3 = buildS3Client(config)
    val bucket = config.getString("pinakes.seaweed.bucket")
    val assetStore = SeaweedAssetStore(s3, bucket)
    val catalog = InMemoryAssetCatalog()

    val kallimachosBase =
        "http://${config.getString("pinakes.kallimachos.host")}:${config.getInt("pinakes.kallimachos.port")}"
    val http = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
    Runtime.getRuntime().addShutdownHook(Thread { http.close() })
    val writeClient = HttpKallimachosWriteClient(http, kallimachosBase)

    // The conformed corpus embedding dimension (must agree with Kallimachos's).
    val corpusEmbed =
        EmbedSpec(
            modelId = configOr(config, "pinakes.embed.model-id", "ada-002"), // INTERIM until local bge-m3 is up
            dimensions =
                if (config.hasPath(
                        "pinakes.embed.dimensions",
                    )
                ) {
                    config.getInt("pinakes.embed.dimensions")
                } else {
                    1536
                },
            modelVersion = configOr(config, "pinakes.embed.model-version", "1"),
        )
    // The LLM compile tail (S3.2): Prometheus client + page writer + resolver.
    val llmGatewayBase =
        "http://${configOr(config, "pinakes.llmgateway.host", "llm-gateway")}:" +
            (if (config.hasPath("pinakes.llmgateway.port")) config.getInt("pinakes.llmgateway.port") else 8080)
    val llmGateway =
        HttpLlmGatewayClient(http, llmGatewayBase, configOr(config, "pinakes.llmgateway.model", "sonnet"))
    val pageWriter = HttpCorpusPageWriter(http, kallimachosBase)
    val conceptIndex = InMemoryConceptIndex() // shared by RESOLVE + LINK (compounding)
    val resolver = EntityResolver(conceptIndex)
    val tokenBudget =
        if (config.hasPath(
                "pinakes.compile.token-budget",
            )
        ) {
            config.getInt("pinakes.compile.token-budget")
        } else {
            0
        }

    val library =
        StageLibrary(
            listOf(
                ExtractStage(),
                ClassifyStage(),
                ChunkStage(),
                EmbedStage(writeClient),
                CompileStage(WikiCompiler(llmGateway, tokenBudget = tokenBudget), meterRegistry),
                ResolveStage(resolver, meterRegistry),
                LinkStage(Linker(), pageWriter, conceptIndex, ContradictionDetector(llmGateway)),
                LoadStage(writeClient),
            ),
        )
    val registry = PipelineRegistry(corpusEmbed)
    loadPipelineDefs(config, corpusEmbed).forEach(registry::register)
    val pipelineService = PipelineService(assetStore, catalog, registry, Runner(library), LineageStore())

    val service = PinakesServiceImpl(assetStore, catalog, pipelineService)
    val grpcServer =
        NettyServerBuilder
            .forPort(grpcPort)
            .permitKeepAliveTime(20, TimeUnit.SECONDS)
            .permitKeepAliveWithoutCalls(true)
            .maxInboundMessageSize(33554432) // 32 MiB; matches charon/veles
            .addService(service)
            .build()
    Runtime.getRuntime().addShutdownHook(Thread { grpcServer.shutdownNow() })
    grpcServer.start()
    log.info("Pinakes gRPC server started on :{} (Stage 1.3 — stage + mechanical pipeline)", grpcPort)

    embeddedServer(Netty, port = httpPort, host = "0.0.0.0", module = { module(meterRegistry) }).start(wait = true)
}

private fun buildS3Client(config: Config): S3Client {
    val endpoint = config.getString("pinakes.seaweed.endpoint")
    val region =
        if (config.hasPath(
                "pinakes.seaweed.region",
            )
        ) {
            config.getString("pinakes.seaweed.region")
        } else {
            "us-east-1"
        }
    val accessKey =
        if (config.hasPath(
                "pinakes.seaweed.access-key",
            )
        ) {
            config.getString("pinakes.seaweed.access-key")
        } else {
            ""
        }
    val secretKey =
        if (config.hasPath(
                "pinakes.seaweed.secret-key",
            )
        ) {
            config.getString("pinakes.seaweed.secret-key")
        } else {
            ""
        }
    val builder =
        S3Client
            .builder()
            .region(Region.of(region))
            .endpointOverride(URI.create(endpoint))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
    if (accessKey.isNotBlank() && secretKey.isNotBlank()) {
        builder.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
    }
    return builder.build()
}

private fun configOr(
    config: Config,
    path: String,
    default: String,
): String = if (config.hasPath(path)) config.getString(path) else default

/**
 * Load pipeline definitions from `pinakes.pipelines.path` (a YAML file or a
 * directory of `*.yaml`); falls back to the built-in mechanical pipeline when no
 * defs are present (per-source binding, architecture §7).
 */
private fun loadPipelineDefs(
    config: Config,
    corpusEmbed: EmbedSpec,
): List<Pipeline> {
    val path = if (config.hasPath("pinakes.pipelines.path")) config.getString("pinakes.pipelines.path") else ""
    val file = File(path)
    val yamls =
        when {
            file.isDirectory -> file.listFiles { f -> f.extension in setOf("yaml", "yml") }?.toList().orEmpty()
            file.isFile -> listOf(file)
            else -> emptyList()
        }
    val defined = yamls.flatMap { PipelineDefs.fromYaml(it.readText()) }
    return defined.ifEmpty { listOf(PipelineDefs.mechanicalDefault(corpusEmbed)) }
}

fun Application.module(meterRegistry: PrometheusMeterRegistry) {
    install(ContentNegotiation) { json() }
    routing {
        get("/health") { call.respond(buildJsonObject { put("status", "UP") }) }
        get("/ready") {
            call.respond(
                buildJsonObject {
                    put("status", "UP")
                    put("stage", "1.3")
                },
            )
        }
        get("/status") {
            call.respond(
                buildJsonObject {
                    put("service", "pinakes")
                    put("stage", "1.3")
                    put("path", "stage + mechanical pipeline")
                },
            )
        }
        get("/metrics") { call.respondText(meterRegistry.scrape(), ContentType.Text.Plain) }
    }
}
