package com.overdrive.app.launcher.dilink5.enable

import android.content.Context

/** Reflective client that preserves the OEM strategy object and changes only verifyEnable. */
class DiLink5StrategyServiceClient(
    private val access: StrategyAccess,
) {
    enum class DispatchResult {
        ACCEPTED,
        NOT_DISPATCHED,
        DISPATCHED_UNCONFIRMED,
        CANCELLED,
    }

    interface StrategyAccess {
        fun get(): StrategySnapshot?
        fun set(snapshot: StrategySnapshot): Boolean
        fun setIfCurrent(snapshot: StrategySnapshot, isCurrent: () -> Boolean): DispatchResult {
            if (!isCurrent()) return DispatchResult.CANCELLED
            return if (set(snapshot)) DispatchResult.ACCEPTED
            else DispatchResult.DISPATCHED_UNCONFIRMED
        }
    }

    data class StrategySnapshot(val raw: Any, val configs: MutableMap<Any?, Any?>)

    enum class Outcome {
        CONFIRMED,
        CONNECTED_NOT_CONFIRMED,
        NOT_DISPATCHED,
        UNAVAILABLE,
        CANCELLED,
    }

    data class Result(
        val outcome: Outcome,
        val priorVerifyEnable: String?,
        val obdVerifyEnable: String?,
        val readbackVerifyEnable: String?,
        val detail: String,
    )

    fun readVerifyEnable(): String? =
        access.get()?.configs?.get(VERIFY_ENABLE) as? String

    fun setVerifyDisabled(isCurrent: () -> Boolean = { true }): Result {
        if (!isCurrent()) return cancelledResult()
        val snapshot = access.get() ?: return Result(
            Outcome.UNAVAILABLE, null, null, null, "strategy service unavailable",
        )
        val priorValue = snapshot.configs[VERIFY_ENABLE]
        val obd = snapshot.configs[OBD_VERIFY_ENABLE] as? String
        if (priorValue !is String) {
            return Result(
                Outcome.CONNECTED_NOT_CONFIRMED,
                null,
                obd,
                null,
                "strategy verifyEnable is not a readable string; no write was attempted",
            )
        }
        if (!isCurrent()) return cancelledResult(priorValue, obd)
        val prior = priorValue
        snapshot.configs[VERIFY_ENABLE] = "0"
        when (access.setIfCurrent(snapshot, isCurrent)) {
            DispatchResult.CANCELLED -> {
                snapshot.configs[VERIFY_ENABLE] = prior
                return cancelledResult(prior, obd)
            }
            DispatchResult.NOT_DISPATCHED -> {
                snapshot.configs[VERIFY_ENABLE] = prior
                return Result(
                    Outcome.NOT_DISPATCHED,
                    prior,
                    obd,
                    null,
                    "strategy write was not dispatched",
                )
            }
            DispatchResult.DISPATCHED_UNCONFIRMED -> return Result(
                Outcome.CONNECTED_NOT_CONFIRMED,
                prior,
                obd,
                null,
                "strategy write outcome is unconfirmed",
            )
            DispatchResult.ACCEPTED -> Unit
        }
        val readback = access.get()?.configs?.get(VERIFY_ENABLE) as? String
        return Result(
            if (readback == "0") Outcome.CONFIRMED else Outcome.CONNECTED_NOT_CONFIRMED,
            prior,
            obd,
            readback,
            if (readback == "0") "strategy readback confirmed" else "strategy readback not confirmed",
        )
    }

    fun restoreVerifyEnable(
        expectedCurrent: String,
        value: String,
        isCurrent: () -> Boolean = { true },
    ): Result {
        if (!isCurrent()) return cancelledResult()
        val snapshot = access.get() ?: return Result(
            Outcome.UNAVAILABLE, null, null, null, "strategy service unavailable",
        )
        val currentValue = snapshot.configs[VERIFY_ENABLE]
        val obd = snapshot.configs[OBD_VERIFY_ENABLE] as? String
        if (currentValue !is String || currentValue != expectedCurrent) {
            return Result(
                Outcome.CONNECTED_NOT_CONFIRMED,
                currentValue as? String,
                obd,
                currentValue as? String,
                "strategy current value did not match restoration ownership; no write was attempted",
            )
        }
        if (!isCurrent()) return cancelledResult(currentValue, obd)
        val current = currentValue
        snapshot.configs[VERIFY_ENABLE] = value
        when (access.setIfCurrent(snapshot, isCurrent)) {
            DispatchResult.CANCELLED -> {
                snapshot.configs[VERIFY_ENABLE] = current
                return cancelledResult(current, obd)
            }
            DispatchResult.NOT_DISPATCHED -> {
                snapshot.configs[VERIFY_ENABLE] = current
                return Result(
                    Outcome.NOT_DISPATCHED,
                    current,
                    obd,
                    null,
                    "strategy restoration was not dispatched",
                )
            }
            DispatchResult.DISPATCHED_UNCONFIRMED -> return Result(
                Outcome.CONNECTED_NOT_CONFIRMED,
                current,
                obd,
                null,
                "strategy restore outcome is unconfirmed",
            )
            DispatchResult.ACCEPTED -> Unit
        }
        val readback = access.get()?.configs?.get(VERIFY_ENABLE) as? String
        return Result(
            if (readback == value) Outcome.CONFIRMED else Outcome.CONNECTED_NOT_CONFIRMED,
            current,
            obd,
            readback,
            if (readback == value) "strategy restoration confirmed" else "strategy restoration not confirmed",
        )
    }

    private fun cancelledResult(prior: String? = null, obd: String? = null) = Result(
        Outcome.CANCELLED,
        prior,
        obd,
        null,
        "strategy operation cancelled before dispatch",
    )

    class ReflectionAccess(private val context: Context) : StrategyAccess {
        override fun get(): StrategySnapshot? {
            return try {
                val managerClass = Class.forName("android.strategyservice.StrategyManager")
                val manager = context.getSystemService(SERVICE_NAME) ?: return null
                if (!managerClass.isInstance(manager)) return null
                val returned = managerClass
                    .getMethod("getStrategy", String::class.java, List::class.java)
                    .invoke(manager, STRATEGY_GROUP, listOf(VERIFY_ENABLE, OBD_VERIFY_ENABLE))
                    ?: return null
                val configs = returned.javaClass.getField("configs").get(returned) as? MutableMap<*, *>
                    ?: return null
                @Suppress("UNCHECKED_CAST")
                StrategySnapshot(returned, configs as MutableMap<Any?, Any?>)
            } catch (_: Throwable) {
                null
            }
        }

        override fun set(snapshot: StrategySnapshot): Boolean =
            setIfCurrent(snapshot) { true } == DispatchResult.ACCEPTED

        override fun setIfCurrent(
            snapshot: StrategySnapshot,
            isCurrent: () -> Boolean,
        ): DispatchResult {
            val managerClass = try {
                Class.forName("android.strategyservice.StrategyManager")
            } catch (_: Throwable) {
                return DispatchResult.NOT_DISPATCHED
            }
            val manager = try {
                context.getSystemService(SERVICE_NAME)
            } catch (_: Throwable) {
                null
            } ?: return DispatchResult.NOT_DISPATCHED
            if (!managerClass.isInstance(manager)) return DispatchResult.NOT_DISPATCHED
            val method = try {
                managerClass.getMethod(
                    "setStrategy",
                    String::class.java,
                    snapshot.raw.javaClass,
                )
            } catch (_: Throwable) {
                return DispatchResult.NOT_DISPATCHED
            }
            if (!isCurrent()) return DispatchResult.CANCELLED
            return try {
                method.invoke(manager, STRATEGY_GROUP, snapshot.raw)
                DispatchResult.ACCEPTED
            } catch (_: Throwable) {
                // Reflection invocation crossed the dispatch boundary. The remote side may have
                // applied the mutation before a Binder/reflection exception reached this caller.
                DispatchResult.DISPATCHED_UNCONFIRMED
            }
        }
    }

    companion object {
        const val SERVICE_NAME = "strategy"
        const val STRATEGY_GROUP = "config"
        const val VERIFY_ENABLE = "verifyEnable"
        const val OBD_VERIFY_ENABLE = "obdVerifyEnable"

        fun forContext(context: Context) =
            DiLink5StrategyServiceClient(ReflectionAccess(context.applicationContext))
    }
}
