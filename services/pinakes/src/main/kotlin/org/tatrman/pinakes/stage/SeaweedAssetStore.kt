package org.tatrman.pinakes.stage

import software.amazon.awssdk.core.ResponseBytes
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.PutObjectRequest

/**
 * Raw-asset landing on SeaweedFS via the S3 SDK (`data-seaweedfs:8333`,
 * architecture §2 — reuse the Seaweed infra Charon uses, not the Charon service).
 * The stage is immutable raw truth (Karpathy's "raw sources").
 *
 * Key scheme is deterministic: `{sourceFeed}/{assetId}-{originalName}` — feed
 * partitions the bucket, the assetId keeps keys unique, the original name aids
 * eyeballing. The bucket (`docwh-stage`) is created on first use.
 */
class SeaweedAssetStore(
    private val s3: S3Client,
    private val bucket: String,
) {
    fun keyFor(
        sourceFeed: String,
        assetId: String,
        originalName: String,
    ): String = "${sourceFeed.trim('/')}/$assetId-$originalName"

    /** Stage raw bytes; returns the `asset_ref` (the S3 key). */
    fun put(
        sourceFeed: String,
        assetId: String,
        originalName: String,
        mimeType: String,
        content: ByteArray,
    ): String {
        val key = keyFor(sourceFeed, assetId, originalName)
        s3.putObject(
            PutObjectRequest
                .builder()
                .bucket(bucket)
                .key(key)
                .contentType(mimeType)
                .build(),
            RequestBody.fromBytes(content),
        )
        return key
    }

    fun get(assetRef: String): ByteArray {
        val resp: ResponseBytes<GetObjectResponse> =
            s3.getObjectAsBytes(
                GetObjectRequest
                    .builder()
                    .bucket(bucket)
                    .key(assetRef)
                    .build(),
            )
        return resp.asByteArray()
    }
}
