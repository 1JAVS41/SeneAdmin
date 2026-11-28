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
import java.util.concurrent.TimeUnit

/**
 * Envío automático de la captura GUARDIÁN al Worker de producción.
 *
 * Reutiliza el patrón que YA existe en SeneAdminAutoSyncWorker /
 * SeneAdminAutoSyncScheduler (WorkManager, cada 2 horas, solo con red,
 * reintento exponencial de 15 minutos, trabajo único sin duplicados).
 * No se inventó ningún permiso nuevo ni pantalla nueva: INTERNET ya está
 * en el manifiesto y work-runtime-ktx ya es dependencia de la app.
 *
 * Nunca envía sin consentimiento: en cada ejecución vuelve a leer
 * GuardianConfigStore y si no hay configuración guardada o el
 * consentimiento está desactivado, termina sin enviar nada. Solo envía
 * los datos de esta instalación (installationId e ingestToken guardados
 * de forma cifrada); nunca toca otra instalación.
 */
class GuardianAutoSyncWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {
    override fun doWork(): Result {
        val app = applicationContext
        val config = GuardianConfigStore.read(app) ?: return Result.success()
        if (!config.consent) return Result.success()

        val result = try {
            // doWork ya corre fuera del hilo principal, como exige el exportador.
            GuardianSnapshotExporter.export(app, config.installationId, config.ingestToken)
        } catch (_: Exception) {
            return Result.retry()
        }
        if (!result.accepted) return Result.retry()

        // El siguiente ciclo de dos horas comienza después de un envío aceptado.
        GuardianAutoSyncScheduler.scheduleNext(app)
        return Result.success()
    }
}

object GuardianAutoSyncScheduler {
    private const val UNIQUE_WORK = "sene_guardian_automatic_snapshot"

    private fun nextRequest() =
        OneTimeWorkRequestBuilder<GuardianAutoSyncWorker>()
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

    /** Cada envío aceptado programa el siguiente dos horas después de finalizar. */
    fun scheduleNext(context: Context) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_WORK,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            nextRequest()
        )
    }

    /** Cancela el envío automático (al retirar el consentimiento o desactivar). */
    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_WORK)
    }
}
