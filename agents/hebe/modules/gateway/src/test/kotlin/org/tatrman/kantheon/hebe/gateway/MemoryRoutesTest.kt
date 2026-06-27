package org.tatrman.kantheon.hebe.gateway

import org.tatrman.kantheon.hebe.api.HitSource
import org.tatrman.kantheon.hebe.api.MemoryHit
import org.tatrman.kantheon.hebe.api.MemoryStore
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MemoryRoutesTest {
    private fun makeMemoryStore(): MemoryStore {
        val store = mockk<MemoryStore>(relaxed = true)
        coEvery { store.search(any(), any(), any(), any()) } returns
            listOf(
                MemoryHit(
                    docPath = "docs/test.md",
                    chunkIdx = 0,
                    snippet = "a relevant snippet",
                    score = 0.9,
                    source = HitSource.Fts,
                ),
            )
        coEvery { store.listDocs(any()) } returns listOf("docs/a.md", "docs/b.md")
        coEvery { store.readDoc("docs/a.md") } returns "content of a"
        coEvery { store.readDoc("docs/missing.md") } returns null
        return store
    }

    @Test
    fun `search returns results for valid q param`() =
        testApplication {
            val store = makeMemoryStore()
            application {
                routing { MemoryRoutes.register(this, store) }
            }

            val response = client.get("/api/memory/search?q=test+query")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue("results" in body)
        }

    @Test
    fun `search without q param returns 400`() =
        testApplication {
            val store = makeMemoryStore()
            application {
                routing { MemoryRoutes.register(this, store) }
            }

            val response = client.get("/api/memory/search")
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = response.bodyAsText()
            assertTrue("q parameter required" in body)
        }

    @Test
    fun `tree returns list of docs`() =
        testApplication {
            val store = makeMemoryStore()
            application {
                routing { MemoryRoutes.register(this, store) }
            }

            val response = client.get("/api/memory/tree")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue("docs" in body)
        }

    @Test
    fun `doc returns content for existing path`() =
        testApplication {
            val store = makeMemoryStore()
            application {
                routing { MemoryRoutes.register(this, store) }
            }

            val response = client.get("/api/memory/doc?path=docs/a.md")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue("content of a" in body)
        }

    @Test
    fun `doc returns 404 for missing path`() =
        testApplication {
            val store = makeMemoryStore()
            application {
                routing { MemoryRoutes.register(this, store) }
            }

            val response = client.get("/api/memory/doc?path=docs/missing.md")
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `doc without path param returns 400`() =
        testApplication {
            val store = makeMemoryStore()
            application {
                routing { MemoryRoutes.register(this, store) }
            }

            val response = client.get("/api/memory/doc")
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
}
