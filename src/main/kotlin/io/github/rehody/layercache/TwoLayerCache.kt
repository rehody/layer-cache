package io.github.rehody.layercache

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap

abstract class TwoLayerCache<K : Any, V : Any>(
    val l1: CacheStore<K, V>,
    val l2: CacheStore<K, V>
) {
    init {
        l2.subscribeInvalidation(l1::invalidate)
    }

    private val deferredValues = ConcurrentHashMap<K, Deferred<V?>>()

    suspend fun getOrLoad(key: K, loader: suspend () -> V?): V? = coroutineScope {

        // check L1
        l1.find(key)?.let { return@coroutineScope it }
        if (l1.isMissing(key)) return@coroutineScope null

        // check L2 and refresh L1 if found
        l2.find(key)?.let {
            l1.put(key, it)
            return@coroutineScope it
        }
        if (l2.isMissing(key)) {
            l1.markMissing(key)
            return@coroutineScope null
        }

        // read DB and refresh L1 and L2
        val deferredDbValue = async(start = CoroutineStart.LAZY) {
            loader().also { dbValue ->
                if (dbValue != null) {
                    put(key, dbValue)
                } else {
                    markMissing(key)
                }
            }
        }

        val actualDeferred = deferredValues.putIfAbsent(key, deferredDbValue) ?: deferredDbValue

        try {
            actualDeferred.await()
        } finally {
            if (actualDeferred === deferredDbValue) {
                deferredValues.remove(key, actualDeferred)
            }
        }
    }

    private fun put(key: K, value: V) {
        l2.put(key, value)
        l1.put(key, value)
    }

    private fun markMissing(key: K) {
        l2.markMissing(key)
        l1.markMissing(key)
    }

    fun invalidate(key: K) {
        try {
            l2.invalidate(key)
        } finally {
            l1.invalidate(key)
        }
    }
}