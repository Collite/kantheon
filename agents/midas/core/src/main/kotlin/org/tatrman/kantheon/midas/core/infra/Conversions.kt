@file:OptIn(ExperimentalUuidApi::class)

package org.tatrman.kantheon.midas.core.infra

import com.google.protobuf.Timestamp
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

/**
 * proto ↔ Exposed-v1 column conversions (Stage 1.3). Exposed v1 `uuid()` columns
 * are `kotlin.uuid.Uuid`; proto carries ids/decimals as strings and timestamps as
 * `google.protobuf.Timestamp`. All times are normalised to UTC.
 */

fun String.toUuidColumn(): kotlin.uuid.Uuid = UUID.fromString(this).toKotlinUuid()

fun kotlin.uuid.Uuid.toUuidString(): String = this.toJavaUuid().toString()

fun Timestamp.toOffsetDateTime(): OffsetDateTime =
    OffsetDateTime.ofInstant(Instant.ofEpochSecond(seconds, nanos.toLong()), ZoneOffset.UTC)

fun OffsetDateTime.toProtoTimestamp(): Timestamp {
    val instant = this.toInstant()
    return Timestamp
        .newBuilder()
        .setSeconds(instant.epochSecond)
        .setNanos(instant.nano)
        .build()
}

fun Timestamp.toLocalDate(): LocalDate = toOffsetDateTime().toLocalDate()

fun LocalDate.toProtoTimestamp(): Timestamp = this.atStartOfDay().atOffset(ZoneOffset.UTC).toProtoTimestamp()

/** Decimal string → BigDecimal; blank → ZERO (proto3 default for unset Money). */
fun String.toDecimalOrZero(): BigDecimal = if (isBlank()) BigDecimal.ZERO else BigDecimal(trim())

fun BigDecimal.toDecimalString(): String = this.toPlainString()

/**
 * Enum ↔ DDL-string mapping (the prefix-strip convention locked by
 * `MidasEnumDdlMappingSpec`): proto `CLIENT_ACTIVE` ↔ DDL `'ACTIVE'`.
 */
fun <E : Enum<E>> dbEnum(
    dbValue: String,
    prefix: String,
    valueOf: (String) -> E,
): E = valueOf(prefix + dbValue)

fun Enum<*>.toDbEnum(prefix: String): String = name.removePrefix(prefix)
