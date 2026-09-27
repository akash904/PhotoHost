package io.github.akash904.photohost.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * Named, size-capped dispatchers. Nothing in this project calls `Dispatchers.IO` directly.
 *
 * The sizes are not arbitrary and are not about maximising throughput:
 *
 *  - **storeWrite = 1.** A single writer. Concurrent writes to exFAT fragment badly, and one writer
 *    makes the temp-then-rename commit protocol reasonable to reason about.
 *  - **storeRead = 3.** One for an import's source read, two for HTTP streaming and thumbnailing.
 *    More is actively *slower* on a USB disk and just adds Binder pressure on SAF.
 *  - **decode = half the cores.** Bitmap and video decode is what heats the phone. Half the cores
 *    leaves headroom so thermal throttling does not kick in and halve everything.
 *  - **dbIo = 1.** Serialises explicit transactions; Room's own executors handle everything else.
 */
class AppDispatchers(cores: Int = Runtime.getRuntime().availableProcessors()) {

    @OptIn(ExperimentalCoroutinesApi::class)
    val storeWrite: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    @OptIn(ExperimentalCoroutinesApi::class)
    val storeRead: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(3)

    @OptIn(ExperimentalCoroutinesApi::class)
    val decode: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(maxOf(1, cores / 2))

    @OptIn(ExperimentalCoroutinesApi::class)
    val dbIo: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    val decodeParallelism: Int = maxOf(1, cores / 2)
}
