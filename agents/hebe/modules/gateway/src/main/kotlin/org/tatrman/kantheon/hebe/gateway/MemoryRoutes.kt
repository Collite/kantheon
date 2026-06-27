package org.tatrman.kantheon.hebe.gateway

import org.tatrman.kantheon.hebe.api.MemoryScope
import org.tatrman.kantheon.hebe.api.MemoryStore
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.slf4j.LoggerFactory

object MemoryRoutes {
    private val logger = LoggerFactory.getLogger(MemoryRoutes::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private const val DEFAULT_K = 10

    fun register(
        routing: Route,
        memoryStore: MemoryStore,
    ) {
        routing.get("/api/memory/search") { searchHandler(memoryStore, call) }
        routing.get("/api/memory/tree") { treeHandler(memoryStore, call) }
        routing.get("/api/memory/doc") { readDocHandler(memoryStore, call) }
    }

    private suspend fun searchHandler(
        memoryStore: MemoryStore,
        call: ApplicationCall,
    ) {
        val query = call.request.queryParameters["q"]
        if (query == null) {
            call.respondText(
                buildJsonObject { put("error", JsonPrimitive("q parameter required")) }.toString(),
                status = HttpStatusCode.BadRequest,
                contentType = ContentType.Application.Json,
            )
            return
        }

        val k = call.request.queryParameters["k"]?.toIntOrNull() ?: DEFAULT_K
        val scope =
            try {
                MemoryScope.valueOf(call.request.queryParameters["scope"] ?: "Default")
            } catch (
                @Suppress("TooGenericExceptionCaught", "SwallowedException")
                e: Exception,
            ) {
                MemoryScope.Default
            }

        try {
            val results = memoryStore.search(query, k, scope)
            call.respondText(
                buildJsonObject {
                    put("results", JsonPrimitive(json.encodeToString(results)))
                }.toString(),
                contentType = ContentType.Application.Json,
            )
        } catch (
            @Suppress("TooGenericExceptionCaught", "SwallowedException")
            e: Exception,
        ) {
            logger.error("memory search failed", e)
            call.respondText(
                buildJsonObject { put("error", JsonPrimitive(e.message ?: "Search failed")) }.toString(),
                status = HttpStatusCode.InternalServerError,
                contentType = ContentType.Application.Json,
            )
        }
    }

    private suspend fun treeHandler(
        memoryStore: MemoryStore,
        call: ApplicationCall,
    ) {
        val prefix = call.request.queryParameters["prefix"] ?: ""

        try {
            val docs = memoryStore.listDocs(prefix)
            call.respondText(
                buildJsonObject {
                    put("docs", JsonPrimitive(json.encodeToString(docs)))
                }.toString(),
                contentType = ContentType.Application.Json,
            )
        } catch (
            @Suppress("TooGenericExceptionCaught", "SwallowedException")
            e: Exception,
        ) {
            logger.error("tree failed", e)
            call.respondText(
                buildJsonObject { put("error", JsonPrimitive(e.message ?: "Tree failed")) }.toString(),
                status = HttpStatusCode.InternalServerError,
                contentType = ContentType.Application.Json,
            )
        }
    }

    private suspend fun readDocHandler(
        memoryStore: MemoryStore,
        call: ApplicationCall,
    ) {
        val pathParam = call.request.queryParameters["path"] ?: ""

        if (pathParam.isEmpty()) {
            call.respondText(
                buildJsonObject { put("error", JsonPrimitive("path required")) }.toString(),
                status = HttpStatusCode.BadRequest,
                contentType = ContentType.Application.Json,
            )
            return
        }

        try {
            val content = memoryStore.readDoc(pathParam)
            if (content == null) {
                call.respondText(
                    buildJsonObject { put("error", JsonPrimitive("Document not found")) }.toString(),
                    status = HttpStatusCode.NotFound,
                    contentType = ContentType.Application.Json,
                )
            } else {
                call.respondText(
                    buildJsonObject {
                        put("path", JsonPrimitive(pathParam))
                        put("content", JsonPrimitive(content))
                    }.toString(),
                    contentType = ContentType.Application.Json,
                )
            }
        } catch (
            @Suppress("TooGenericExceptionCaught", "SwallowedException")
            e: Exception,
        ) {
            logger.error("read doc failed", e)
            call.respondText(
                buildJsonObject { put("error", JsonPrimitive(e.message ?: "Read failed")) }.toString(),
                status = HttpStatusCode.InternalServerError,
                contentType = ContentType.Application.Json,
            )
        }
    }
}
