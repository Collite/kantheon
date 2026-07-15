package org.tatrman.kallimachos

import com.typesafe.config.Config
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.tatrman.kallimachos.adapters.exposed.ExposedGraphAdapter
import org.tatrman.kallimachos.adapters.exposed.ExposedNotebookAdapter
import org.tatrman.kallimachos.adapters.exposed.ExposedRelationalAdapter
import org.tatrman.kallimachos.adapters.exposed.ExposedTransactor
import org.tatrman.kallimachos.adapters.exposed.PgVectorAdapter
import org.tatrman.kallimachos.adapters.exposed.PostgresFullTextAdapter
import org.tatrman.kallimachos.adapters.fulltext.FullTextPort
import org.tatrman.kallimachos.adapters.fulltext.InMemoryFullTextAdapter
import org.tatrman.kallimachos.adapters.graph.GraphPort
import org.tatrman.kallimachos.adapters.graph.InMemoryGraphAdapter
import org.tatrman.kallimachos.adapters.notebook.InMemoryNotebookAdapter
import org.tatrman.kallimachos.adapters.notebook.NotebookPort
import org.tatrman.kallimachos.adapters.relational.InMemoryRelationalAdapter
import org.tatrman.kallimachos.adapters.relational.RelationalPort
import org.tatrman.kallimachos.adapters.vector.InMemoryVectorAdapter
import org.tatrman.kallimachos.adapters.vector.VectorPort
import org.tatrman.kallimachos.embeddings.EmbedConfig
import org.tatrman.kallimachos.embeddings.EmbeddingsPort
import org.tatrman.kallimachos.embeddings.LlmGatewayEmbeddingsClient
import org.tatrman.kallimachos.tx.SnapshotTransactor
import org.tatrman.kallimachos.tx.Transactor

/**
 * The wired corpus planes + the transaction boundary + the embedding egress.
 * Selected by `kallimachos.storage.profile` (architecture §3 — polyglot behind
 * the Ports):
 *  - `memory` (P1 default): in-memory adapters + a snapshot transactor.
 *  - `postgres` (deploy): Hikari pool + Flyway migrate + the Exposed/PG adapters.
 *
 * The vector plane + the LLM-gateway embeddings client (P2 Stage 2.1) join here;
 * the embedding model is a conformed corpus dimension ([EmbedConfig]).
 */
class CorpusStores(
    val relational: RelationalPort,
    val fullText: FullTextPort,
    val notebooks: NotebookPort,
    val graph: GraphPort,
    val vector: VectorPort,
    val embeddings: EmbeddingsPort,
    val embedConfig: EmbedConfig,
    val transactor: Transactor,
    val profile: String,
) {
    companion object {
        fun fromConfig(config: Config): CorpusStores {
            val profile =
                if (config.hasPath(
                        "kallimachos.storage.profile",
                    )
                ) {
                    config.getString("kallimachos.storage.profile")
                } else {
                    "memory"
                }
            return if (profile == "postgres") postgres(config) else memory(config)
        }

        private fun embedConfigOf(config: Config): EmbedConfig =
            if (config.hasPath("kallimachos.embed.model-id")) {
                EmbedConfig(
                    modelId = config.getString("kallimachos.embed.model-id"),
                    modelVersion = config.getString("kallimachos.embed.model-version"),
                    dimensions = config.getInt("kallimachos.embed.dimensions"),
                )
            } else {
                EmbedConfig("ada-002", "1", 1536) // INTERIM: ada-002/1536 until the local bge-m3 server is up
            }

        private fun embeddingsOf(
            config: Config,
            embedConfig: EmbedConfig,
        ): EmbeddingsPort {
            val base =
                if (config.hasPath("kallimachos.llmgateway.base-url")) {
                    config.getString("kallimachos.llmgateway.base-url")
                } else {
                    "http://${configOr(config, "kallimachos.llmgateway.host", "llm-gateway")}:" +
                        (
                            if (config.hasPath(
                                    "kallimachos.llmgateway.port",
                                )
                            ) {
                                config.getInt("kallimachos.llmgateway.port")
                            } else {
                                8080
                            }
                        )
                }
            // Lenient: the OpenAI-shaped LLM-gateway reply carries extra keys
            // (`object`, `usage`, …) the client does not model.
            val http =
                HttpClient(CIO) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            val apiKey = configOr(config, "kallimachos.llmgateway.api-key", "")
            return LlmGatewayEmbeddingsClient(http, base, embedConfig, apiKey)
        }

        private fun configOr(
            config: Config,
            path: String,
            default: String,
        ): String = if (config.hasPath(path)) config.getString(path) else default

        fun memory(config: Config): CorpusStores {
            val relational = InMemoryRelationalAdapter()
            val fullText = InMemoryFullTextAdapter()
            val notebooks = InMemoryNotebookAdapter()
            val graph = InMemoryGraphAdapter()
            val embedConfig = embedConfigOf(config)
            return CorpusStores(
                relational = relational,
                fullText = fullText,
                notebooks = notebooks,
                graph = graph,
                vector = InMemoryVectorAdapter(),
                embeddings = embeddingsOf(config, embedConfig),
                embedConfig = embedConfig,
                transactor = SnapshotTransactor(relational, fullText, notebooks, graph),
                profile = "memory",
            )
        }

        private fun postgres(config: Config): CorpusStores {
            val ds =
                HikariDataSource(
                    HikariConfig().apply {
                        jdbcUrl = config.getString("kallimachos.db.url")
                        username = config.getString("kallimachos.db.user")
                        password =
                            if (config.hasPath(
                                    "kallimachos.db.password",
                                )
                            ) {
                                config.getString("kallimachos.db.password")
                            } else {
                                ""
                            }
                        maximumPoolSize = 8
                    },
                )
            Flyway
                .configure()
                .dataSource(ds)
                .load()
                .migrate()
            val database = Database.connect(ds)
            val embedConfig = embedConfigOf(config)
            return CorpusStores(
                relational = ExposedRelationalAdapter(),
                fullText = PostgresFullTextAdapter(),
                notebooks = ExposedNotebookAdapter(),
                graph = ExposedGraphAdapter(),
                vector = PgVectorAdapter(embedConfig),
                embeddings = embeddingsOf(config, embedConfig),
                embedConfig = embedConfig,
                transactor = ExposedTransactor(database),
                profile = "postgres",
            )
        }
    }
}
