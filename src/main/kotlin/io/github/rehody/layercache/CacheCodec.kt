package io.github.rehody.layercache


interface CacheCodec<T> {

    fun read(value: String): T

    fun write(value: T): String
}
