package org.tatrman.kantheon.report.store

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

/**
 * Stage 3.4 T6 — the filesystem artifact store: a UUID-keyed write/read/delete round-trip and
 * the id-validation that keeps a caller-supplied id from smuggling a glob or a path segment to
 * the filesystem.
 */
class ArtifactStoreSpec :
    StringSpec({

        "write → read → delete round-trips by UUID" {
            val store = ArtifactStore(Files.createTempDirectory("artifacts-"))
            val a = store.write("hello".toByteArray(), "xlsx")
            store.read(a.artifactId)?.decodeToString() shouldBe "hello"
            store.delete(a.artifactId) shouldBe true
            store.read(a.artifactId) shouldBe null
        }

        "a non-UUID id (glob / traversal attempt) never reaches the filesystem" {
            val store = ArtifactStore(Files.createTempDirectory("artifacts-"))
            store.write("secret".toByteArray(), "xlsx") // a real artifact exists in the dir

            store.read("*") shouldBe null // a glob must not match any artifact
            store.read("../../etc/passwd") shouldBe null // a path segment must not escape
            store.delete("{a,b}") shouldBe false // glob metachars must not throw, just miss
            store.read("00000000-0000-0000-0000-000000000000") shouldBe null // well-formed but absent
        }
    })
