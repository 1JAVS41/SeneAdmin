package com.sene.admin

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Sincroniza novedades administrativas y, con consentimiento explícito,
 * exporta indicadores agregados a la instalación aislada de SENE GUARDIÁN.
 */
class SeneAdminAutoSyncWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {
    override fun doWork(): Result {
        val app = applicationContext

        if (ServerPairing.isLinked(app)) {
            val prefs = app.getSharedPreferences("sene_admin_payroll_sync", Context.MODE_PRIVATE)
            val previousSuccess = prefs.getLong("last_success", 0L)
            val finished = CountDownLatch(1)

            AdminPayrollRequestSync.sync(app, true) {
                finished.countDown()
            }

            val completed = try {
                finished.await(8, TimeUnit.MINUTES)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            if (!completed) return Result.retry()

            val latestSuccess = prefs.getLong("last_success", 0L)
            if (latestSuccess <= previousSuccess) return Result.retry()
        }

        // La exportación GUARDIÁN usa credenciales distintas por instalación.
        // Nunca se ejecuta si no existe consentimiento explícito.
        val guardian = GuardianConfigStore.read(app)
        if (guardian != null && guardian.consent) {
            val result = try {
                GuardianSnapshotExporter.export(app, guardian.installationId, guardian.ingestToken)
            } catch (_: Exception) {
                GuardianSnapshotExporter.ExportResult(false, 0, "No se pudo enviar la captura GUARDIÁN.")
            }
            if (!result.accepted) return Result.retry()
        }

        SeneAdminAutoSyncScheduler.scheduleNext(app)
        return Result.success()
    }
}

object SeneAdminAutoSyncScheduler {
    private const val UNIQUE_WORK = "sene_admin_automatic_server_sync"

    private fun nextRequest() =
        OneTimeWorkRequestBuilder<SeneAdminAutoSyncWorker>()
            .setInitialDelay(2, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()

    /** Programa el primer ciclo sin duplicarlo al abrir varias veces la aplicación. */
    fun ensureScheduled(context: Context) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_WORK,
            ExistingWorkPolicy.KEEP,
            nextRequest()
        )
    }

    /** Cada ciclo exitoso programa el siguiente dos horas después de finalizar. */
    fun scheduleNext(context: Context) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_WORK,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            nextRequest()
        )
    }
}
