package org.tatrman.pinakes.stage

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectResponse

/**
 * P1 Stage 1.3 T3 — the Seaweed stage against a MOCKED S3 client (no live
 * Seaweed; planning-conventions §4). Asserts the put + the deterministic key
 * scheme `{feed}/{assetId}-{originalName}`. The live round-trip is integration.
 */
class SeaweedAssetStoreSpec :
    StringSpec({
        "put stages to the bucket under the deterministic feed-partitioned key" {
            val s3 = mockk<S3Client>()
            val reqSlot = slot<PutObjectRequest>()
            every { s3.putObject(capture(reqSlot), any<RequestBody>()) } returns PutObjectResponse.builder().build()

            val store = SeaweedAssetStore(s3, "docwh-stage")
            val ref = store.put("erp", "a1", "doc.txt", "text/plain", "hello".toByteArray())

            ref shouldBe "erp/a1-doc.txt"
            reqSlot.captured.bucket() shouldBe "docwh-stage"
            reqSlot.captured.key() shouldBe "erp/a1-doc.txt"
            reqSlot.captured.contentType() shouldBe "text/plain"
            verify(exactly = 1) { s3.putObject(any<PutObjectRequest>(), any<RequestBody>()) }
        }

        "keyFor trims a leading slash on the feed" {
            val store = SeaweedAssetStore(mockk(), "docwh-stage")
            store.keyFor("/sharepoint", "x", "a.md") shouldBe "sharepoint/x-a.md"
        }
    })
