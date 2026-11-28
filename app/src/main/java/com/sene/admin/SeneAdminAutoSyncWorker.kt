package com.sene.admin

import android.content.Context
import android.content.SharedPreferences
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
 * Consulta únicamente las novedades pendientes. El servidor conserva la entrega
 * hasta que SeneAdmin guarda los eventos y confirma sus ACK.
 */
class SeneAdminAutoSyncWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {
    // Ahorro (A4): maximo 1 reintento por hora ante fallo del servidor.
    // Si ya se reintento una vez en la ultima hora, no se reintenta cada
    // 15 min sin tope: se deja para el proximo ciclo programado de 2 h.
    private fun retryOncePerHour(app: Context, prefs: SharedPreferences): Result {
        val now = System.currentTimeMillis()
        val lastRetry = prefs.getLong("last_sync_retry_at", 0L)
        if (now - lastRetry < 60L * 60L * 1000L) {
            SeneAdminAutoSyncScheduler.scheduleNext(app)
            return Result.success()
        }
        prefs.edit().putLong("last_sync_retry_at", now).apply()
        return Result.retry()
    }

    override fun doWork(): Result {
        val app = applicationContext
        if (!ServerPairing.isLinked(app)) return Result.success()

        val prefs = app.getSharedPreferences("sene_admin_payroll_sync", Context.MODE_PRIVATE)
        val previousSuccess = prefs.getLong("last_success", 0L)
        // El freno de 2 h vive en sync(): con force=false no llama al servidor
        // si la ultima llamada fue hace menos de 2 h, y last_attempt no avanza.
        val attemptBefore = prefs.getLong("last_attempt", 0L)
        val finished = CountDownLatch(1)

        AdminPayrollRequestSync.sync(app, false) {
            finished.countDown()
        }

        val completed = try {
            finished.await(8, TimeUnit.MINUTES)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!completed) return retryOncePerHour(app, prefs)

        val latestSuccess = prefs.getLong("last_success", 0L)
        val attempted = prefs.getLong("last_attempt", 0L) != attemptBefore
        if (!attempted) {
            // Ahorro (A4): la sincronizacion fue frenada por el limite de 2 h:
            // no hubo llamada al servidor, asi que no hay nada que reintentar.
            // El ciclo normal sigue en 2 h.
            SeneAdminAutoSyncScheduler.scheduleNext(app)
            return Result.success()
        }
        if (latestSuccess <= previousSuccess) return retryOncePerHour(app, prefs)

        prefs.edit().remove("last_sync_retry_at").apply()
        // El siguiente contador de dos horas comienza después de una respuesta exitosa.
        SeneAdminAutoSyncScheduler.scheduleNext(app)
        return Result.success()
    }
}

object SeneAdminAutoSyncScheduler {
    private const val UNIQUE_WORK = "sene_admin_automatic_server_sync"
    private const val PREFS = "sene_admin_payroll_sync"
    private const val NEXT_SCHEDULED_SYNC_AT = "next_scheduled_sync_at"
    private const val TWO_HOURS_MS = 2L * 60L * 60L * 1000L

    // Guarda cuando toca la proxima sincronizacion programada, para que
    // AdminPayrollRequestSync (A3) sepa si vale la pena confirmar ACKs
    // de inmediato o esperar a ese ciclo.
    private fun recordNextSyncAt(context: Context, atMillis: Long) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(NEXT_SCHEDULED_SYNC_AT, atMillis).apply()
    }

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
        // Con KEEP puede haber ya un ciclo pendiente: solo se anota la fecha
        // si no hay ninguna futura guardada.
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getLong(NEXT_SCHEDULED_SYNC_AT, 0L) <= System.currentTimeMillis()) {
            recordNextSyncAt(context, System.currentTimeMillis() + TWO_HOURS_MS)
        }
    }

    /** Cada ciclo exitoso programa el siguiente dos horas después de finalizar. */
    fun scheduleNext(context: Context) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_WORK,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            nextRequest()
        )
        recordNextSyncAt(context, System.currentTimeMillis() + TWO_HOURS_MS)
    }
}
