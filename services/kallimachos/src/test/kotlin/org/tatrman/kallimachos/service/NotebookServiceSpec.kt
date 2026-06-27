package org.tatrman.kallimachos.service

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.tatrman.kallimachos.adapters.notebook.InMemoryNotebookAdapter

/**
 * P1 Stage 1.2 T6 — marts (notebooks): create / list / get, owner from a fixture
 * principal, `visibility_roles` stored (enforcement is P4).
 */
class NotebookServiceSpec :
    StringSpec({
        "create stores owner + visibility_roles and is retrievable" {
            val service = NotebookService(InMemoryNotebookAdapter())
            val nb =
                service.create(
                    displayName = "Finance",
                    ownerUserId = "bora",
                    visibilityRoles = listOf("kantheon-area-finance"),
                )

            nb.ownerUserId shouldBe "bora"
            nb.visibilityRoles shouldBe listOf("kantheon-area-finance")
            service.get(nb.id)!!.displayName shouldBe "Finance"
        }

        "list returns every mart at v1 (visibility predicate is P4)" {
            val service = NotebookService(InMemoryNotebookAdapter())
            val a = service.create("A", "bora")
            val b = service.create("B", "someone-else")
            service.list("bora").map { it.id } shouldContainExactlyInAnyOrder listOf(a.id, b.id)
        }

        "get of an unknown mart is null" {
            NotebookService(InMemoryNotebookAdapter()).get("nope").shouldBeNull()
        }
    })
