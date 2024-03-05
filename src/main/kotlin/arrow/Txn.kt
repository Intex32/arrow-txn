package arrow

import arrow.atomic.AtomicInt
import arrow.atomic.AtomicLong
import arrow.core.*
import arrow.core.continuations.AtomicRef
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.resilience.SagaDSLMarker
import io.ktor.utils.io.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.UUID

typealias Txn<E, A> = suspend context(TxnScope<E>) () -> A

@DslMarker annotation class TxnDslMarker

interface TxnScope<E> : Raise<E>, SagaScope<E> {

    /**
     * globally unique ID identifying this txn
     */
    val id: String

    /**
     * unique ID for this local runtime instance
     */
    val localRuntimeId: Long

    suspend fun <A> Txn<E, A>.bindTxn(): A = invoke(this@TxnScope)

    /**
     * queued to be executed at the end of transaction
     * after successful commit.
     *
     * should only contain non-critical side effects
     * that reliably should not fail,
     * as transaction cannot be rolled back anymore.
     */
    @TxnDslMarker
    fun onSuccess(f: () -> Unit)

    @TxnDslMarker
    fun onRollback(f: () -> Unit)

    @TxnDslMarker
    fun onCompleted(f: () -> Unit)

    /**
     * for side effects that cannot be undone
     * by a corresponding compensating action.
     *
     * problematic, if there is more than one uncompensable side effect.
     * as one fails and the remaining succeed,
     * the succeeding actions cannot be compensated.
     *
     * if transaction fails and uncompensable actions have been executed,
     * the incident may e.g. be logged by the responsible handler.
     */
    @TxnDslMarker
    fun <T> registerUncompensableSideEffects(f: () -> T): T
}

inline fun <E, A> txn(noinline block: suspend context(TxnScope<E>) () -> A): Txn<E, A> = block

suspend fun <E, A> Txn<E, A>.transact(): Either<E, A> { //TODO: calling transact on Txn outside this file doesn't work
    var builder: TxnBuilder<E>? = null
    return either {
        builder = TxnBuilder(raiseContext = this@either)
        try {
            invoke(builder!!)
        } catch (e: CancellationException) {
            runReleaseAndRethrow(e) { /*builder!!.totalCompensation()*/ }
        } catch (t: Throwable) {
            runReleaseAndRethrow(t) { builder!!.totalCompensation() }
        }
    }.onRight {
        builder!!.runOnCompleteActions()
    }.onLeft {
        withContext(NonCancellable) { builder!!.totalCompensation() }
    }
}

suspend fun <E, A> runTxn(txn: suspend TxnScope<E>.() -> A): Either<E, A> =
    txn.transact()









private val localRuntimeIdCounter = AtomicLong(0)

// Internal implementation of the `txn { }` builder.
@PublishedApi
internal class TxnBuilder<E>(
    private val stack: AtomicRef<List<suspend () -> Unit>> = AtomicRef(emptyList()),
    raiseContext: Raise<E>,
) : TxnScope<E>, Raise<E> by raiseContext {

    override val id = UUID.randomUUID().toString()
    override val localRuntimeId = localRuntimeIdCounter.incrementAndGet()

    private val onSuccessfulActions: AtomicRef<List<() -> Unit>> = AtomicRef(emptyList())
    private val onFailureActions: AtomicRef<List<() -> Unit>> = AtomicRef(emptyList())
    private val uncompensableActionCounter = AtomicInt(0)

    override fun onSuccess(f: () -> Unit) {
        onSuccessfulActions.updateAndGet { listOf(f) + it }
    }

    override fun onRollback(f: () -> Unit) {
        onFailureActions.updateAndGet { listOf(f) + it }
    }

    override fun onCompleted(f: () -> Unit) {
        onSuccessfulActions.updateAndGet { listOf(f) + it }
        onFailureActions.updateAndGet { listOf(f) + it }
    }

    override fun <T> registerUncompensableSideEffects(f: () -> T): T {
        uncompensableActionCounter.incrementAndGet()
        return f()
    }

    @SagaDSLMarker
    override suspend fun <A> saga(
        action: suspend Raise<E>.() -> A,
        compensation: suspend (A) -> Unit
    ): A {
        val eitherRes: Either<E, A> = try {
            either { action() }
        } catch (e: CancellationException) {
            runReleaseAndRethrow(e) { }
        } catch (t: Throwable) {
            runReleaseAndRethrow(t) { }
        }
        val res = eitherRes.getOrElse { this@TxnBuilder.raise(it) } // re-raise error to higher level
        withContext(NonCancellable) {
            stack.updateAndGet { listOf(suspend { compensation(res) }) + it }
        }
        return res
    }

    @PublishedApi
    internal suspend fun totalCompensation() {
        stack
            .get()
            .fold<suspend () -> Unit, Throwable?>(null) { acc, finalizer ->
                try {
                    finalizer()
                    acc
                } catch (e: Throwable) {
                    e.nonFatalOrThrow()
                    acc?.apply { addSuppressed(e) } ?: e
                }
            }
            ?.let { throw it }
    }

    @PublishedApi
    internal fun runOnCompleteActions() =
        onSuccessfulActions.get().forEach { it.invoke() } //TODO: handle possible exceptions
}

private suspend fun runReleaseAndRethrow(original: Throwable, f: suspend () -> Unit): Nothing {
    try {
        withContext(NonCancellable) { f() }
    } catch (e: Throwable) {
        original.addSuppressed(e.nonFatalOrThrow())
    }
    throw original
}
