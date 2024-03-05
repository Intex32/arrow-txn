package demo.arrow

import arrow.Txn
import arrow.TxnScope
import arrow.atomic.AtomicInt
import arrow.core.Either
import arrow.core.None
import arrow.core.Option
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.some
import arrow.runTxn
import arrow.txn
import kotlin.random.Random

private data class DomainError(
    val message: String,
)

suspend fun main() {
    val results = buildSet {
        repeat(1000) {
            runTxn {
                example(47L)
            }.let { add(it) }
        }
    }

    println()
    println("likely all possible results:")
    println(results.joinToString("\n"))
}

context(TxnScope<DomainError>)
suspend fun example(id: Long): Int {
    ensure(someCheck()) { DomainError("internal pre check failed") }

    // other txn bind
    val result0 = otherServiceCall(id).bindTxn()

    // wrap inner txn error
    otherServiceCall(id)
//        .mapLeft { DomainError("inner service call failed", it) }] //TODO: Either-like API for Txn
        .bindTxn()

    // service call with custom error
    val result1 = exampleWithCustomServiceError(result0)
        .mapLeft { DomainError("inner service failed: ${it.name}") }
        .bind()

    onSuccess { println("log example") } // log only if txn is successful

    // interpret Option
    val entity = findSomeEntity(result1)
        .toEither { DomainError("entity not found") } // None is interpreted as error here
        .bind()

    // tracked side effect
    val result2 = saga({
        println("a1")
        val result = sideEffectDummy(entity.data)
        return@saga result.length
    }, {
        println("c1")
        sideEffectCompensationDummy()
    })

    // tracked side effect
    val result3 = saga({
        println("a2")
//        if (someCheck()) throw RuntimeException("simulated a2 failure by exception")
        ensure(someCheck()) { DomainError("simulated a2 failure by raise") }
        val result = sideEffectDummy(result2)
        return@saga result + 47
    }, {
        println("c2")
        sideEffectCompensationDummy()
    })

    // simulated failure
    if (someCheck())
//        throw RuntimeException("test")
        raise(DomainError("simulated failure of normal code in txn"))

    registerUncompensableSideEffects {
        //sendMail()
    }

    return result3 * 2
}

enum class CustomServiceError { BISCUIT, COOKIE }

context(TxnScope<DomainError>)
fun exampleWithCustomServiceError(param: Long): Either<CustomServiceError, Long> = either {
    ensure(someCheck()) { CustomServiceError.COOKIE }
    return@either 47L + param
}


/* DUMMY LOGIC */

private data class EntityDummy(
    val id: Long,
    val data: String,
)

private fun findSomeEntity(id: Long): Option<EntityDummy> = when (someCheck()) {
    true -> None
    false -> EntityDummy(id, "data").some()
}

private fun otherServiceCall(param: Long): Txn<DomainError, Long> = txn {
    return@txn param + 1
}

private val sideEffectCounter = AtomicInt(0)

private fun <T> sideEffectDummy(any: T): T {
    sideEffectCounter.incrementAndGet()
    return any
}

private fun sideEffectCompensationDummy() {
    sideEffectCounter.decrementAndGet()
}

private fun someCheck(): Boolean = Random.nextBoolean()
