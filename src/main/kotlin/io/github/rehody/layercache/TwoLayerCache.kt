package io.github.rehody.layercache

import java.util.Optional
import java.util.function.Supplier

abstract class TwoLayerCache<K, V>(
    val l1: CacheStore<K, V>,
    val l2: CacheStore<K, V>
) {
    fun getOrLoad(key: K, loader: Supplier<Optional<V>>): Optional<out V> {

        // check L1
        val l1Value = l1.find(key)
        if (l1Value.isPresent) {
            return l1Value
        }
        if (l1.isMissing(key)) {
            return Optional.empty()
        }

        // check L2 and refresh L1 if found
        val l2Value: Optional<V> = l2.find(key)
        if (l2Value.isPresent) {
            l1.put(key, l2Value.get())
            return l2Value
        }
        if (l2.isMissing(key)) {
            l1.markMissing(key)
            return Optional.empty()
        }

        // read DB and refresh L1 and L2
        val dbValue: Optional<V> = loader.get()
        if (dbValue.isPresent) {
            val value: V = dbValue.get()
            put(key, value)
        } else {
            markMissing(key)
        }

        return dbValue
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
        l2.invalidate(key)
        l1.invalidate(key)
    }
}