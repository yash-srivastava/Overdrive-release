package com.overdrive.app.launcher.dilink5.enable

import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.ResultReceiver
import java.util.concurrent.Executors

/** Private process boundary that performs one serialized account-service operation at a time. */
open class DiLink5AdbWorkerService : Service() {
    private val operations = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "DiLink5AdbWorker").apply { isDaemon = true }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val operationId = intent?.getLongExtra(EXTRA_OPERATION_ID, -1L) ?: -1L
        val receiver = intent?.getParcelableExtra<ResultReceiver>(EXTRA_RESULT_RECEIVER)
        val operation = intent?.getStringExtra(EXTRA_OPERATION)
        val content = intent?.getStringExtra(EXTRA_CONTENT)
        val expectedCurrent = intent?.getStringExtra(EXTRA_EXPECTED_CURRENT)
        val modeGeneration = intent?.getStringExtra(EXTRA_MODE_GENERATION)
        val operationAuthority = intent?.extras?.getBinder(EXTRA_OPERATION_AUTHORITY)
        val candidate = DiLink5AdbWorkerProcess.candidateForService(javaClass)
        val validProcess = candidate != null && candidate.processName == Application.getProcessName()
        if (intent?.action != ACTION_RUN || operationId < 0 || receiver == null || !validProcess ||
            !modeGenerationCurrent(modeGeneration) ||
            !DiLink5AdbWorkerAuthority.isCurrent(operationAuthority)) {
            receiver?.send(RESULT_REJECTED, Bundle().apply {
                putLong(EXTRA_OPERATION_ID, operationId)
                putString(KEY_DETAIL, "invalid private worker request")
            })
            stopSelf(startId)
            return START_NOT_STICKY
        }
        operations.execute {
            val result = when (operation) {
                OP_INSPECT -> DiLink5AccountServiceClient(this).inspect {
                    modeGenerationCurrent(modeGeneration) &&
                        DiLink5AdbWorkerAuthority.isCurrent(operationAuthority)
                }
                OP_WRITE -> if (content != null && modeGeneration != null) {
                    DiLink5AccountServiceClient(this).write(content) {
                        modeGenerationCurrent(modeGeneration) &&
                            DiLink5AdbWorkerAuthority.isCurrent(operationAuthority)
                    }
                } else null
                OP_RESTORE -> if (expectedCurrent != null && content != null && modeGeneration != null) {
                    DiLink5AccountServiceClient(this).restore(expectedCurrent, content) {
                        modeGenerationCurrent(modeGeneration) &&
                            DiLink5AdbWorkerAuthority.isCurrent(operationAuthority)
                    }
                } else null
                else -> null
            }
            val bundle = Bundle().apply {
                putLong(EXTRA_OPERATION_ID, operationId)
                putString(KEY_WORKER, candidate.processName)
                if (result != null) putAccountResult(result)
                else putString(KEY_DETAIL, "unsupported worker operation")
            }
            receiver.send(if (result != null) RESULT_OK else RESULT_REJECTED, bundle)
            stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    private fun modeGenerationCurrent(expected: String?): Boolean {
        if (BydManualAdbModeAuthority.isManualAuthority(expected)) {
            return BydManualAdbModeAuthority.isCurrent(expected)
        }
        pendingGenerationFromAuthority(expected.orEmpty())?.let { staged ->
            return com.overdrive.app.camera.dilink5.DiLink5Platform
                .currentPendingDiLink5Generation() == staged
        }
        if (expected == DiLink5AdbEnableCoordinator.CONFIGURED_DILINK5_AUTHORITY) {
            return com.overdrive.app.camera.dilink5.DiLink5Platform
                .isConfiguredDiLink5WithoutCommittedMode()
        }
        val snapshot = com.overdrive.app.camera.dilink5.DiLink5Platform
            .currentActiveModeSnapshot() ?: return false
        return DiLink5AdbWorkerModeFence.matches(
            expected,
            snapshot.mode,
            snapshot.generation,
        )
    }

    override fun onDestroy() {
        operations.shutdownNow()
        super.onDestroy()
    }

    private fun Bundle.putAccountResult(result: DiLink5AccountServiceClient.Result) {
        putBoolean(KEY_BOUND, result.bound)
        putBoolean(KEY_CONNECTED, result.connected)
        putBoolean(KEY_BINDER_DIED, result.binderDied)
        putBoolean(KEY_WRITE_DISPATCHED, result.writeDispatched)
        putBoolean(KEY_REPLY_LOST_AFTER_DISPATCH, result.replyLostAfterDispatch)
        putString(KEY_PRIOR_STATE, result.prior.state.name)
        putString(KEY_PRIOR_VALUE, result.prior.value)
        if (result.writeResult != null) {
            putBoolean(KEY_WRITE_PRESENT, true)
            putInt(KEY_WRITE_RESULT, result.writeResult)
        }
        if (result.exists != null) {
            putBoolean(KEY_EXISTS_PRESENT, true)
            putBoolean(KEY_EXISTS, result.exists)
        }
        putString(KEY_AFTER_STATE, result.after.state.name)
        putString(KEY_AFTER_VALUE, result.after.value)
        if (result.conditionalMatched != null) {
            putBoolean(KEY_CONDITIONAL_PRESENT, true)
            putBoolean(KEY_CONDITIONAL_MATCHED, result.conditionalMatched)
        }
        putString(KEY_DETAIL, result.detail)
    }

    companion object {
        const val ACTION_RUN = "com.overdrive.app.action.RUN_DILINK5_ADB_WORKER"
        const val EXTRA_OPERATION_ID = "operation_id"
        const val EXTRA_RESULT_RECEIVER = "result_receiver"
        const val EXTRA_OPERATION = "operation"
        const val EXTRA_CONTENT = "content"
        const val EXTRA_EXPECTED_CURRENT = "expected_current"
        const val EXTRA_MODE_GENERATION = "mode_generation"
        const val EXTRA_OPERATION_AUTHORITY = "operation_authority"
        const val OP_INSPECT = "inspect"
        const val OP_WRITE = "write"
        const val OP_RESTORE = "restore"
        const val RESULT_OK = 1
        const val RESULT_REJECTED = 2
        const val KEY_WORKER = "worker"
        const val KEY_BOUND = "bound"
        const val KEY_CONNECTED = "connected"
        const val KEY_BINDER_DIED = "binder_died"
        const val KEY_WRITE_DISPATCHED = "write_dispatched"
        const val KEY_REPLY_LOST_AFTER_DISPATCH = "reply_lost_after_dispatch"
        const val KEY_PRIOR_STATE = "prior_state"
        const val KEY_PRIOR_VALUE = "prior_value"
        const val KEY_WRITE_PRESENT = "write_present"
        const val KEY_WRITE_RESULT = "write_result"
        const val KEY_EXISTS_PRESENT = "exists_present"
        const val KEY_EXISTS = "exists"
        const val KEY_AFTER_STATE = "after_state"
        const val KEY_AFTER_VALUE = "after_value"
        const val KEY_CONDITIONAL_PRESENT = "conditional_present"
        const val KEY_CONDITIONAL_MATCHED = "conditional_matched"
        const val KEY_DETAIL = "detail"
    }
}

internal object DiLink5AdbWorkerModeFence {
    fun matches(expectedGeneration: String?, activeMode: String?, activeGeneration: String?): Boolean =
        !expectedGeneration.isNullOrEmpty() && activeMode == "dilink5" &&
            activeGeneration == expectedGeneration
}

internal object DiLink5AdbWorkerAuthority {
    private const val DESCRIPTOR = "com.overdrive.app.dilink5.IWorkerOperationAuthority"
    private const val TX_IS_CURRENT = IBinder.FIRST_CALL_TRANSACTION

    fun create(isCurrent: () -> Boolean): IBinder = object : android.os.Binder() {
        init { attachInterface(null, DESCRIPTOR) }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != TX_IS_CURRENT) return super.onTransact(code, data, reply, flags)
            return try {
                data.enforceInterface(DESCRIPTOR)
                if (data.dataAvail() != 0) return false
                reply?.writeNoException()
                reply?.writeInt(if (isCurrent()) 1 else 0)
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    fun isCurrent(authority: IBinder?): Boolean {
        authority ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            if (!authority.transact(TX_IS_CURRENT, data, reply, 0)) return false
            reply.readException()
            reply.readInt() == 1
        } catch (_: Throwable) {
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
