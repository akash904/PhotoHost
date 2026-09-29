package io.github.akash904.photohost.jobs

import android.util.Log
import io.github.akash904.photohost.data.dao.JobDao
import io.github.akash904.photohost.data.entity.JobEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.math.min
import kotlin.math.pow

private const val TAG = "photohost"

/** What a handler decided. The distinction between [Fail] and [Blocked] is the important one. */
sealed interface Outcome {
    data object Done : Outcome
    data class Retry(val reason: String) : Outcome
    data class Fail(val reason: String) : Outcome

    /**
     * The work is fine but the storage is not there -- an unplugged drive, an unmounted volume.
     * Parking the job means a replug resumes it, rather than the retry budget quietly draining
     * while the drive sits on a desk.
     */
    data class Blocked(val reason: String) : Outcome
}

interface JobHandler {
    suspend fun run(job: JobEntity): Outcome
}

/**
 * Drains the `jobs` table.
 *
 * Only one of these runs, inside the foreground service. Deliberately simple: claim a batch, run it
 * under a semaphore, record outcomes, repeat. The queue is a table, so nothing is lost if the
 * process dies mid-batch -- orphaned leases are reset at startup.
 *
 * Concurrency is a *function*, re-read on every batch, because the right amount of parallelism on a
 * phone changes minute to minute with temperature and charge state.
 */
class JobRunner(
    private val jobs: JobDao,
    private val handlers: Map<String, JobHandler>,
    private val dispatcher: CoroutineDispatcher,
    private val concurrency: () -> Int,
    private val owner: String,
) {
    private var loop: Job? = null

    @Volatile
    var lastError: String? = null
        private set

    fun start(scope: CoroutineScope) {
        if (loop != null) return
        loop = scope.launch {
            // Single process: anything still LEASED belongs to a run that died, so a blanket reset
            // is both correct and simpler than lease-expiry arithmetic.
            withContext(dispatcher) { jobs.resetOrphanedLeases(now()) }
            Log.i(TAG, "job runner started as $owner")

            while (isActive) {
                val limit = concurrency().coerceAtLeast(0)
                if (limit == 0) {
                    // Thermally throttled or on battery: hold off entirely rather than crawl.
                    delay(30_000)
                    continue
                }
                val batch = try {
                    withContext(dispatcher) { jobs.claim(now(), owner, LEASE_MS, limit) }
                } catch (t: Throwable) {
                    lastError = "claim: ${t.javaClass.simpleName}: ${t.message}"
                    Log.e(TAG, "job claim failed", t)
                    delay(5_000)
                    continue
                }
                if (batch.isEmpty()) {
                    delay(2_000)
                    continue
                }
                val gate = Semaphore(limit)
                batch.forEach { job ->
                    launch {
                        gate.withPermit { execute(job) }
                    }
                }
            }
        }
    }

    suspend fun stop() {
        loop?.cancelAndJoin()
        loop = null
    }

    private suspend fun execute(job: JobEntity) {
        val handler = handlers[job.type]
        if (handler == null) {
            withContext(dispatcher) { jobs.fail(job.id, "no handler for ${job.type}", now()) }
            return
        }
        val outcome = try {
            handler.run(job)
        } catch (t: Throwable) {
            Outcome.Retry("${t.javaClass.simpleName}: ${t.message}")
        }
        withContext(dispatcher) {
            when (outcome) {
                is Outcome.Done -> jobs.complete(job.id, now())
                is Outcome.Blocked -> jobs.block(job.id, outcome.reason, now())
                is Outcome.Fail -> jobs.fail(job.id, outcome.reason, now())
                is Outcome.Retry -> {
                    if (job.attempts >= job.maxAttempts) {
                        jobs.fail(job.id, "gave up after ${job.attempts}: ${outcome.reason}", now())
                    } else {
                        jobs.retryLater(job.id, now() + backoffMs(job.attempts), outcome.reason, now())
                    }
                }
            }
        }
        if (outcome !is Outcome.Done) {
            lastError = "${job.type}: $outcome"
            Log.w(TAG, "job ${job.id} ${job.type} -> $outcome")
        }
    }

    /** Exponential with a ceiling, so a persistently failing job stops hammering the store. */
    private fun backoffMs(attempts: Int): Long =
        min(30 * 60_000.0, 2_000.0 * 2.0.pow(attempts.coerceAtMost(10))).toLong()

    private fun now() = System.currentTimeMillis()

    private companion object {
        const val LEASE_MS = 10 * 60_000L
    }
}
