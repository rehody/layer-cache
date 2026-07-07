package io.github.rehody.layercache

import java.util.Optional
import java.util.function.Consumer

interface CacheStore<K, V> {

    fun find(key: K): Optional<V>

    fun isMissing(key: K): Boolean

    fun put(key: K, value: V)

    fun markMissing(key: K)

    fun unmarkMissing(key: K)

    fun invalidate(key: K)

    fun subscribeInvalidation(invalidationConsumer: Consumer<K>): Int

    fun unsubscribeInvalidation(listenerId: Int)
}