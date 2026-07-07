package io.github.rehody.layercache

import java.time.Duration

@JvmRecord
data class CacheProperties(
    val valueTtl: Duration,
    val missTtl: Duration,
    val valueSize: Long,
    val missSize: Long,
    val ttlSpread: Double,
    val keyPrefix: String,
    val invalidationTopic: String,
)