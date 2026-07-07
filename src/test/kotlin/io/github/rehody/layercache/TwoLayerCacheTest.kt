package io.github.rehody.layercache

import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer
import kotlin.time.Duration.Companion.milliseconds

class TwoLayerCacheTest {

    private lateinit var l1: CacheStore<String, String>
    private lateinit var l2: CacheStore<String, String>
    private lateinit var cache: TwoLayerCache<String, String>

    private val testKey = "test_key"
    private val testValue = "test_value"

    @BeforeEach
    fun setUp() {
        l1 = mockk(relaxed = true)
        l2 = mockk(relaxed = true)

        every { l1.find(any()) } returns null
        every { l1.isMissing(any()) } returns false
        every { l2.find(any()) } returns null
        every { l2.isMissing(any()) } returns false

        // System Under Test
        cache = object : TwoLayerCache<String, String>(l1, l2) {}
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    @Nested
    inner class Initialization {

        @Test
        fun `should subscribe L1 invalidate to L2 invalidation on init`() {
            // Given
            val consumerSlot = slot<Consumer<String>>()

            // When
            // Действие уже произошло в setUp() при создании cache

            // Then
            verify(exactly = 1) { l2.subscribeInvalidation(capture(consumerSlot)) }

            consumerSlot.captured.accept(testKey)

            verify(exactly = 1) { l1.invalidate(testKey) }
        }
    }

    @Nested
    inner class GetOrLoad {

        @Test
        fun `should return value from L1 when present`() = runTest {
            // Given
            every { l1.find(testKey) } returns testValue
            val loader = mockk<suspend () -> String?>()

            // When
            val result = cache.getOrLoad(testKey, loader)

            // Then
            assertThat(result).isEqualTo(testValue)
            verify(exactly = 0) { l2.find(any()) }
            coVerify(exactly = 0) { loader.invoke() }
        }

        @Test
        fun `should return null when marked as missing in L1`() = runTest {
            // Given
            every { l1.isMissing(testKey) } returns true
            val loader = mockk<suspend () -> String?>()

            // When
            val result = cache.getOrLoad(testKey, loader)

            // Then
            assertThat(result).isNull()
            verify(exactly = 0) { l2.find(any()) }
            coVerify(exactly = 0) { loader.invoke() }
        }

        @Test
        fun `should return value from L2 and populate L1 when present in L2`() = runTest {
            // Given
            every { l2.find(testKey) } returns testValue
            val loader = mockk<suspend () -> String?>()

            // When
            val result = cache.getOrLoad(testKey, loader)

            // Then
            assertThat(result).isEqualTo(testValue)
            verify(exactly = 1) { l1.put(testKey, testValue) }
            coVerify(exactly = 0) { loader.invoke() }
        }

        @Test
        fun `should return null and mark L1 missing when marked as missing in L2`() = runTest {
            // Given
            every { l2.isMissing(testKey) } returns true
            val loader = mockk<suspend () -> String?>()

            // When
            val result = cache.getOrLoad(testKey, loader)

            // Then
            assertThat(result).isNull()
            verify(exactly = 1) { l1.markMissing(testKey) }
            coVerify(exactly = 0) { loader.invoke() }
        }

        @Test
        fun `should load from DB, populate both layers and return value when caches are empty`() = runTest {
            // Given
            val loader: suspend () -> String? = { testValue }

            // When
            val result = cache.getOrLoad(testKey, loader)

            // Then
            assertThat(result).isEqualTo(testValue)
            verify(exactly = 1) { l2.put(testKey, testValue) }
            verify(exactly = 1) { l1.put(testKey, testValue) }
        }

        @Test
        fun `should mark both layers as missing when DB returns null`() = runTest {
            // Given
            val loader: suspend () -> String? = { null }

            // When
            val result = cache.getOrLoad(testKey, loader)

            // Then
            assertThat(result).isNull()
            verify(exactly = 1) { l2.markMissing(testKey) }
            verify(exactly = 1) { l1.markMissing(testKey) }
        }

        @Test
        fun `should execute loader only once for concurrent requests`(): Unit = runBlocking(Dispatchers.Default) {
            withTimeout(2000.milliseconds) {
                val loaderCalls = AtomicInteger(0)
                val loader: suspend () -> String? = {
                    loaderCalls.incrementAndGet()
                    delay(100.milliseconds)
                    testValue
                }

                val deferred1 = async { cache.getOrLoad(testKey, loader) }
                val deferred2 = async { cache.getOrLoad(testKey, loader) }
                val deferred3 = async { cache.getOrLoad(testKey, loader) }

                val results = listOf(deferred1.await(), deferred2.await(), deferred3.await())

                assertThat(results).containsOnly(testValue)
                assertThat(loaderCalls.get()).isEqualTo(1)
            }
        }

        @Test
        fun `should rethrow exception and cleanup state when loader fails`() = runTest {
            // Given
            val expectedException = RuntimeException("DB is down")
            val failingLoader: suspend () -> String? = { throw expectedException }

            // When
            val result = runCatching { cache.getOrLoad(testKey, failingLoader) }

            // Then
            assertThat(result.isFailure)
                .withFailMessage("Exception expected, but got: ${result.getOrNull()}")
                .isTrue()

            val actualException = result.exceptionOrNull()!!

            val exceptionFoundInCauseChain = generateSequence(actualException) { it.cause }
                .any { it == expectedException }

            assertThat(exceptionFoundInCauseChain)
                .withFailMessage("Ожидаемое исключение не найдено. Вместо этого выброшено: $actualException")
                .isTrue()

            val successfulLoader = mockk<suspend () -> String?>()
            coEvery { successfulLoader.invoke() } returns testValue

            val retryResult = cache.getOrLoad(testKey, successfulLoader)
            assertThat(retryResult).isEqualTo(testValue)
        }
    }

    @Nested
    inner class Invalidation {

        @Test
        fun `should invalidate L2 and L1 sequentially`() {
            // Given / Setup done in @BeforeEach

            // When
            cache.invalidate(testKey)

            // Then
            verifyOrder {
                l2.invalidate(testKey)
                l1.invalidate(testKey)
            }
        }

        @Test
        fun `should invalidate L1 even if L2 invalidation throws exception`() {
            // Given
            val expectedExceptionMessage = "L2 unavailable"
            every { l2.invalidate(testKey) } throws RuntimeException(expectedExceptionMessage)

            // When & Then
            assertThatThrownBy {
                cache.invalidate(testKey)
            }.isInstanceOf(RuntimeException::class.java)
                .hasMessage(expectedExceptionMessage)

            verify(exactly = 1) { l1.invalidate(testKey) }
        }
    }
}