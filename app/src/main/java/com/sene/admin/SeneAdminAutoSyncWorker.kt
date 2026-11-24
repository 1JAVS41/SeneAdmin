package com.sene.admin

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
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
    override fun doWork(): Result {
        val app = applicationContext
        if (!ServerPairing.isLinked(app)) return Result.success()

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
        return if (latestSuccess > previousSuccess) Result.success() else Result.retry()
    }
}

object SeneAdminAutoSyncScheduler {
    private const val UNIQUE_WORK = "sene_admin_automatic_server_sync"

    fun ensureScheduled(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<SeneAdminAutoSyncWorker>(
            2, TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            UNIQUE_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
