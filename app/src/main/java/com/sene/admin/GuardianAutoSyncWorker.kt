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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
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
 *
 * Ahorro de solicitudes (antes: hasta 12 envíos al día, uno cada 2 horas,
 * aunque nada hubiera cambiado; ahora: 1 a 3 al día como máximo):
 * - Si el resumen no cambió desde el último envío aceptado, NO se envía
 *   nada. La comparación usa la huella SHA-256 del contenido, sin fechas,
 *   que calcula GuardianSnapshotExporter.fingerprint.
 * - Aunque nada cambie, se hace 1 envío al día como señal de "sigo vivo":
 *   si pasaron 24 horas o más desde el último envío aceptado, se envía.
 * - Si el envío falla, se reintenta con espera creciente (la de WorkManager),
 *   pero con tope: máximo 3 intentos al día. Al llegar al tope, el Worker
 *   deja de intentarlo ese día y vuelve a revisar en el siguiente ciclo.
 * La huella y los contadores viven solo en este teléfono, en
 * SharedPreferences; nunca se envían al servidor.
 */
class GuardianAutoSyncWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {
    override fun doWork(): Result {
        val app = applicationContext
        val config = GuardianConfigStore.read(app) ?: return Result.success()
        if (!config.consent) return Result.success()

        val fingerprint = try {
            GuardianSnapshotExporter.fingerprint(app, config.installationId)
        } catch (_: Exception) {
            return Result.retry()
        }
        val state = GuardianSyncState.read(app)
        val now = System.currentTimeMillis()
        val nothingChanged = fingerprint == state.lastFingerprint && state.lastFingerprint != null
        val heartbeatDue = state.lastAcceptedAtMillis == 0L ||
            now - state.lastAcceptedAtMillis >= TimeUnit.DAYS.toMillis(1)

        // Nada cambió y el último envío aceptado tiene menos de 24 horas:
        // no hay nada nuevo que contar; se revisa de nuevo en 2 horas.
        if (nothingChanged && !heartbeatDue) {
            GuardianAutoSyncScheduler.scheduleNext(app)
            return Result.success()
        }

        // Tope de reintentos: si hoy ya fallaron 3 intentos, no se insiste
        // más hoy; mañana el contador vuelve a cero y se vuelve a intentar.
        if (state.attemptsToday >= MAX_ATTEMPTS_PER_DAY) {
            GuardianAutoSyncScheduler.scheduleNext(app)
            return Result.success()
        }

        val result = try {
            // doWork ya corre fuera del hilo principal, como exige el exportador.
            GuardianSnapshotExporter.export(app, config.installationId, config.ingestToken)
        } catch (_: Exception) {
            return recordFailureAndDecide(app)
        }
        if (!result.accepted) return recordFailureAndDecide(app)

        // Envío aceptado: el exportador ya guardó la huella y la hora.
        // El siguiente ciclo de dos horas comienza después de un envío aceptado.
        GuardianAutoSyncScheduler.scheduleNext(app)
        return Result.success()
    }

    /**
     * Anota un intento fallido de hoy. Si aún quedan intentos (menos de 3),
     * pide reintento con espera creciente; si se llegó al tope, termina bien
     * y programa la siguiente revisión en 2 horas, sin insistir hoy.
     */
    private fun recordFailureAndDecide(context: Context): Result {
        val attempts = GuardianSyncState.recordFailedAttempt(context)
        if (attempts < MAX_ATTEMPTS_PER_DAY) return Result.retry()
        GuardianAutoSyncScheduler.scheduleNext(context)
        return Result.success()
    }

    companion object {
        /** Máximo de intentos de envío fallidos por día. */
        const val MAX_ATTEMPTS_PER_DAY = 3
    }
}

/**
 * Último envío GUARDIÁN aceptado e intentos fallidos de hoy, guardados solo
 * en este teléfono (SharedPreferences). No contiene credenciales ni datos
 * del negocio: solo la huella del último resumen enviado, la hora en que
 * fue aceptado y cuántos intentos fallidos van hoy.
 */
object GuardianSyncState {
    private const val PREFS = "guardian_sync_state"
    private const val KEY_FINGERPRINT = "last_fingerprint"
    private const val KEY_LAST_ACCEPTED_AT = "last_accepted_at_millis"
    private const val KEY_ATTEMPT_DAY = "attempt_day"
    private const val KEY_ATTEMPTS_TODAY = "attempts_today"

    data class State(
        val lastFingerprint: String?,
        val lastAcceptedAtMillis: Long,
        /** Intentos fallidos en el día actual (UTC); 0 si es otro día. */
        val attemptsToday: Int
    )

    fun read(context: Context): State {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = todayUtc()
        val attempts = if (prefs.getString(KEY_ATTEMPT_DAY, null) == today) {
            prefs.getInt(KEY_ATTEMPTS_TODAY, 0)
        } else {
            0
        }
        return State(
            lastFingerprint = prefs.getString(KEY_FINGERPRINT, null),
            lastAcceptedAtMillis = prefs.getLong(KEY_LAST_ACCEPTED_AT, 0L),
            attemptsToday = attempts
        )
    }

    /** Guarda la huella y la hora de un envío aceptado; borra los fallos de hoy. */
    fun recordAccepted(context: Context, fingerprint: String) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FINGERPRINT, fingerprint)
            .putLong(KEY_LAST_ACCEPTED_AT, System.currentTimeMillis())
            .putString(KEY_ATTEMPT_DAY, todayUtc())
            .putInt(KEY_ATTEMPTS_TODAY, 0)
            .apply()
    }

    /** Anota un intento fallido de hoy y devuelve cuántos van hoy. */
    fun recordFailedAttempt(context: Context): Int {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = todayUtc()
        val attempts = if (prefs.getString(KEY_ATTEMPT_DAY, null) == today) {
            prefs.getInt(KEY_ATTEMPTS_TODAY, 0) + 1
        } else {
            1
        }
        prefs.edit()
            .putString(KEY_ATTEMPT_DAY, today)
            .putInt(KEY_ATTEMPTS_TODAY, attempts)
            .apply()
        return attempts
    }

    /** Borra el estado de envío (al desactivar GUARDIÁN en este teléfono). */
    fun clear(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    private fun todayUtc(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
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
