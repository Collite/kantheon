package org.tatrman.kantheon.golem.context

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.tatrman.meta.v1.DrillMapDetail
import org.tatrman.meta.v1.GetModelResponse
import org.tatrman.meta.v1.ModelBundle
import org.tatrman.meta.v1.ModelBundleEntity
import org.tatrman.meta.v1.ModelBundleQuery
import org.tatrman.meta.v1.ObjectDescriptor
import org.tatrman.meta.v1.PackageVersion
import org.tatrman.veles.client.MetadataGrpcClient
import org.tatrman.plan.v1.QualifiedName

private fun qname(
    ns: String,
    name: String,
): QualifiedName =
    QualifiedName
        .newBuilder()
        .setNamespace(ns)
        .setName(name)
        .build()

private fun desc(
    name: String,
    local: String = name,
): ObjectDescriptor =
    ObjectDescriptor
        .newBuilder()
        .setQualifiedName(qname("erp", name))
        .setLocalName(local)
        .build()

private fun entity(name: String): ModelBundleEntity =
    ModelBundleEntity.newBuilder().setObjectDescriptor(desc(name)).build()

private fun pattern(
    id: String,
    sql: String,
): ModelBundleQuery =
    ModelBundleQuery
        .newBuilder()
        .setObjectDescriptor(desc(id))
        .setSourceText(sql)
        .build()

private fun drill(
    name: String,
    from: String,
    to: String,
): DrillMapDetail =
    DrillMapDetail
        .newBuilder()
        .setName(name)
        .setFromPattern(qname("erp", from))
        .setToPattern(qname("erp", to))
        .build()

private fun version(hash: String): PackageVersion =
    PackageVersion
        .newBuilder()
        .setPackageName("erp")
        .setContentHash(hash)
        .build()

private fun response(hash: String): GetModelResponse {
    val bundle =
        ModelBundle
            .newBuilder()
            .addEntities(entity("customer"))
            .addPatternQueries(pattern("listUnpaidInvoices", "SELECT ..."))
            .addDrillMaps(drill("invoice_detail", "listUnpaidInvoices", "invoiceLines"))
            .addPackageVersions(version(hash))
            .build()
    return GetModelResponse.newBuilder().setModel(bundle).build()
}

/**
 * [PackageContext] over recorded `ModelBundle` fixtures: indexing, hash-keyed
 * no-op refresh, and the not-loaded guard. The gRPC transport itself is the
 * shared ariadne-client's concern; here the client is mocked.
 */
class PackageContextSpec :
    StringSpec({

        "refresh loads the bundle and indexes entities / queries / drill maps" {
            runTest {
                val client = mockk<MetadataGrpcClient>()
                coEvery { client.getModel(any(), any(), any(), any(), any()) } returns response("h1")
                val ctx = PackageContext(client, packages = listOf("erp"))

                val snap = ctx.refresh()

                ctx.isLoaded shouldBe true
                snap.entity(qname("erp", "customer")).shouldNotBeNull()
                snap.entity(qname("erp", "unknown")) shouldBe null
                snap.patternQuery("listUnpaidInvoices").shouldNotBeNull().sourceText shouldBe "SELECT ..."
                snap.drillsFrom(qname("erp", "listUnpaidInvoices")).single().name shouldBe "invoice_detail"
                snap.drillsFrom(qname("erp", "customer")).shouldBeEmpty()
            }
        }

        "current() before the first refresh throws" {
            val ctx = PackageContext(mockk(), packages = listOf("erp"))
            ctx.isLoaded shouldBe false
            shouldThrow<IllegalStateException> { ctx.current() }
        }

        "refresh with an unchanged package hash keeps the held snapshot (no rebuild)" {
            runTest {
                val client = mockk<MetadataGrpcClient>()
                coEvery { client.getModel(any(), any(), any(), any(), any()) } returns response("same")
                val ctx = PackageContext(client, packages = listOf("erp"))

                val first = ctx.refresh()
                val second = ctx.refresh()

                (first === second) shouldBe true
                coVerify(exactly = 2) { client.getModel(any(), any(), any(), any(), any()) }
            }
        }

        "refresh with a changed package hash swaps in a new snapshot" {
            runTest {
                val client = mockk<MetadataGrpcClient>()
                coEvery {
                    client.getModel(any(), any(), any(), any(), any())
                } returnsMany listOf(response("h1"), response("h2"))
                val ctx = PackageContext(client, packages = listOf("erp"))

                val first = ctx.refresh()
                val second = ctx.refresh()

                (first === second) shouldBe false
                second.hash shouldBe "erp=h2"
                ctx.current() shouldBe second
            }
        }
    })
