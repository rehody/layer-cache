package io.github.rehody.layercache

import java.util.Optional
import java.util.function.Supplier

abstract class TwoLayerCache<K, V> {

    fun getOrLoad(key: K, loader: Supplier<Optional<V>>): Optional<V> {
        TODO()
    }

    fun invalidate(key: K) {
    }
}