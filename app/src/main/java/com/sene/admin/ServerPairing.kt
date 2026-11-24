package com.sene.admin

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Identidad y credencial de SeneAdmin, protegidas por Android Keystore. */
object ServerPairing {
    private const val PREFS="sene_admin_server_pairing"
    private const val ALIAS="SENE_ADMIN_SERVER_TOKEN_V1"
    private const val IV=12
    private const val BASE="https://server-sene-api.nagavs28.workers.dev"

    private fun prefs(c:Context)=c.applicationContext.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
    fun installationId(c:Context):String {
        val p=prefs(c);p.getString("installation_id",null)?.takeIf{it.isNotBlank()}?.let{return it}
        val id=UUID.randomUUID().toString()
        p.edit().putString("installation_id",id).commit()
        return id
    }
    private fun key():SecretKey {
        val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        (ks.getKey(ALIAS,null) as? SecretKey)?.let{return it}
        val gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return gen.generateKey()
    }
    private fun protect(value:String):String {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key())
        return Base64.encodeToString(cipher.iv+cipher.doFinal(value.toByteArray(Charsets.UTF_8)),Base64.NO_WRAP)
    }
    private fun reveal(value:String):String {
        val raw=Base64.decode(value,Base64.NO_WRAP);require(raw.size>IV)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,raw.copyOfRange(0,IV)))
        return String(cipher.doFinal(raw.copyOfRange(IV,raw.size)),Charsets.UTF_8)
    }
    fun token(c:Context):String=try{prefs(c).getString("token_enc",null)?.let{reveal(it)}.orEmpty()}catch(_:Exception){""}
    fun businessName(c:Context)=prefs(c).getString("business_name","").orEmpty()
    fun isLinked(c:Context)=token(c).isNotBlank()
    fun saveClaim(c:Context,response:JSONObject):Boolean {
        val token=response.optString("admin_token")
        val businessId=response.optString("business_id")
        if(token.length<24||businessId.isBlank())return false
        return prefs(c).edit().putString("token_enc",protect(token))
            .putString("business_id",businessId).putString("business_name",response.optString("business_name"))
            .putLong("linked_at",System.currentTimeMillis()).commit()
    }
    fun post(path:String,payload:JSONObject):JSONObject {
        val connection=(URL(BASE+path).openConnection() as HttpURLConnection).apply{
            requestMethod="POST";connectTimeout=12000;readTimeout=12000;doOutput=true;useCaches=false
            setRequestProperty("Content-Type","application/json; charset=utf-8")
            setRequestProperty("Accept","application/json")
        }
        try {
            connection.outputStream.use{it.write(payload.toString().toByteArray(Charsets.UTF_8))}
            val status=connection.responseCode
            val stream=if(status in 200..299)connection.inputStream else connection.errorStream
            val body=stream?.bufferedReader(Charsets.UTF_8)?.use{it.readText()}.orEmpty()
            return (if(body.isBlank())JSONObject() else JSONObject(body)).put("_http_status",status)
        } finally {connection.disconnect()}
    }
    fun claim(c:Context,code:String):JSONObject=post("/admin/pairing/claim",JSONObject()
        .put("pairing_code",code.trim().uppercase())
        .put("admin_installation_id",installationId(c))
        .put(SeneWireProtocol.FIELD_REPORT_PUBLIC_KEY,ReportKeyPairStore.publicKeyBase64()))
}

/** Sincroniza solicitudes y decisiones en lotes; conserva la cola local cuando no hay red. */
object AdminPayrollRequestSync {
    private const val PREFS="sene_admin_payroll_sync"
    private val syncLock = Any()
    private val completionCallbacks = mutableListOf<() -> Unit>()
    private var syncRunning = false
    private const val ATTENDANCE_ACKS="attendance_acks"
    private const val INVENTORY_ACKS="inventory_acks"
    private fun mergeAcks(p:android.content.SharedPreferences,ids:JSONArray) {
        val old=try{JSONArray(p.getString(ATTENDANCE_ACKS,"[]")?:"[]")}catch(_:Exception){JSONArray()}
        val set=linkedSetOf<String>()
        for(i in 0 until old.length())old.optString(i).takeIf{it.isNotBlank()}?.let(set::add)
        for(i in 0 until ids.length())ids.optString(i).takeIf{it.isNotBlank()}?.let(set::add)
        p.edit().putString(ATTENDANCE_ACKS,JSONArray(set.toList()).toString()).commit()
    }
    private fun clearAcks(p:android.content.SharedPreferences,ids:JSONArray) {
        if(ids.length()==0)return
        val remove=buildSet{for(i in 0 until ids.length())ids.optString(i).takeIf{it.isNotBlank()}?.let(::add)}
        val old=try{JSONArray(p.getString(ATTENDANCE_ACKS,"[]")?:"[]")}catch(_:Exception){JSONArray()}
        val keep=JSONArray()
        for(i in 0 until old.length())old.optString(i).takeIf{it.isNotBlank()&&it !in remove}?.let(keep::put)
        p.edit().putString(ATTENDANCE_ACKS,keep.toString()).commit()
    }
    private fun mergeInventoryAcks(p:android.content.SharedPreferences,ids:JSONArray) {
        val old=try{JSONArray(p.getString(INVENTORY_ACKS,"[]")?:"[]")}catch(_:Exception){JSONArray()}
        val set=linkedSetOf<String>()
        for(i in 0 until old.length())old.optString(i).takeIf{it.isNotBlank()}?.let(set::add)
        for(i in 0 until ids.length())ids.optString(i).takeIf{it.isNotBlank()}?.let(set::add)
        p.edit().putString(INVENTORY_ACKS,JSONArray(set.toList()).toString()).commit()
    }
    private fun clearInventoryAcks(p:android.content.SharedPreferences,ids:JSONArray) {
        if(ids.length()==0)return
        val remove=buildSet{for(i in 0 until ids.length())ids.optString(i).takeIf{it.isNotBlank()}?.let(::add)}
        val old=try{JSONArray(p.getString(INVENTORY_ACKS,"[]")?:"[]")}catch(_:Exception){JSONArray()}
        val keep=JSONArray()
        for(i in 0 until old.length())old.optString(i).takeIf{it.isNotBlank()&&it !in remove}?.let{keep.put(it)}
        p.edit().putString(INVENTORY_ACKS,keep.toString()).commit()
    }
    private fun wireMillis(value:Long):Long = if(value in 1L until 100000000000L) value*1000L else value
    /** El Worker envía decided_at como ISO-8601; no debe convertirse accidentalmente en cero. */
    private fun wireTimestamp(value:Any?):Long = when(value) {
        is Number -> wireMillis(value.toLong())
        is String -> {
            val raw=value.trim()
            raw.toLongOrNull()?.let(::wireMillis) ?: try {
                val pattern=if(raw.contains(".")) "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'" else "yyyy-MM-dd'T'HH:mm:ss'Z'"
                java.text.SimpleDateFormat(pattern,java.util.Locale.US).apply {
                    isLenient=false
                    timeZone=java.util.TimeZone.getTimeZone("UTC")
                }.parse(raw)?.time ?: 0L
            } catch(_:Exception) { 0L }
        }
        else -> 0L
    }
    private fun wireDate(value:Any?):String {
        val millis=when(value) {
            is Number -> wireMillis(value.toLong())
            is String -> value.toLongOrNull()?.let(::wireMillis) ?: 0L
            else -> 0L
        }
        if(millis>0L) return java.text.SimpleDateFormat("yyyy-MM-dd",java.util.Locale.US).format(java.util.Date(millis))
        return value?.toString().orEmpty().take(10)
    }
    fun sync(c:Context,force:Boolean=false,onComplete:(()->Unit)?=null) {
        val app=c.applicationContext
        val token=ServerPairing.token(app)
        if(token.isBlank()) {
            if(onComplete!=null) android.os.Handler(android.os.Looper.getMainLooper()).post { onComplete.invoke() }
            return
        }
        val p=app.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
        val now=System.currentTimeMillis()
        val throttled = !force && now-p.getLong("last_attempt",0L)<15L*60L*1000L
        synchronized(syncLock) {
            // Si ya hay una consulta en curso, espera a que termine para refrescar
            // también la pantalla del trabajador sin iniciar otra llamada al servidor.
            if (syncRunning) {
                if (onComplete != null) completionCallbacks.add(onComplete)
                return
            }
            if (throttled) {
                if (onComplete != null) android.os.Handler(android.os.Looper.getMainLooper()).post { onComplete.invoke() }
                return
            }
            if (onComplete != null) completionCallbacks.add(onComplete)
            syncRunning = true
        }
        Thread {
            var flushAcks = false
            try {
                val decisions=JSONArray()
                OwnerPayrollStore.decisionsPendingSync(app).forEach{r->
                    decisions.put(JSONObject().put("e",r.id)
                        .put("x",if(r.ownerDecision=="APPROVE") SeneWireProtocol.APPROVED else SeneWireProtocol.REJECTED)
                        .put("r",r.ownerDecisionReason))
                }
                val payload=JSONObject().put(SeneWireProtocol.FIELD_VERSION,SeneWireProtocol.VERSION).put(SeneWireProtocol.FIELD_TYPE,SeneWireProtocol.SYNC)
                    .put(SeneWireProtocol.FIELD_ADMIN_TOKEN,token).put(SeneWireProtocol.FIELD_CURSOR,p.getString("cursor","").orEmpty()).put(SeneWireProtocol.FIELD_DECISIONS,decisions)
                    .put(SeneWireProtocol.FIELD_ATTENDANCE_ACKS,try{JSONArray(p.getString(ATTENDANCE_ACKS,"[]")?:"[]")}catch(_:Exception){JSONArray()})
                    .put(SeneWireProtocol.FIELD_INVENTORY_ACKS,try{JSONArray(p.getString(INVENTORY_ACKS,"[]")?:"[]")}catch(_:Exception){JSONArray()})
                    .put(SeneWireProtocol.FIELD_REPORT_ACKS,ReportInboxStore.pendingAcks(app))
                    .put(SeneWireProtocol.FIELD_REPORT_PUBLIC_KEY,ReportKeyPairStore.publicKeyBase64())
                p.edit().putLong("last_attempt",now).commit()
                val response=ServerPairing.post("/admin/payroll/requests/sync",payload)
                if(response.optInt("_http_status",0) !in 200..299)return@Thread
                if(response.optInt(SeneWireProtocol.FIELD_VERSION,SeneWireProtocol.VERSION)!=SeneWireProtocol.VERSION)return@Thread
                val requests=response.optJSONArray("items")?:response.optJSONArray("requests")?:JSONArray()
                var requestsPersisted=true
                for(i in 0 until requests.length()) {
                    val o=requests.optJSONObject(i)
                    if(o == null) {
                        requestsPersisted=false
                        continue
                    }
                    val id=o.optString("e",o.optString("request_id",o.optString("id")))
                    val state=when(o.optString("x",o.optString("status","PENDING")).uppercase()) {
                        SeneWireProtocol.APPROVED,"APPROVED" -> "APPROVED"
                        SeneWireProtocol.REJECTED,"REJECTED" -> "REJECTED"
                        else -> "PENDING"
                    }
                    val profile=o.optString("w",o.optString("worker_id",o.optString("profile_id")))
                    if(id.isBlank()||profile.isBlank()) {
                        requestsPersisted=false
                        continue
                    }
                    // Un perfil eliminado se ignora deliberadamente: no debe recrearse desde un evento antiguo.
                    if(OwnerPayrollStore.isDeletedProfile(app,profile))continue
                    val date=wireDate(o.opt("i") ?: o.opt("created_at") ?: o.opt("date"))
                    val amount=o.optLong("a",o.optLong("amount_cents",o.optLong("amountCents")))
                    val type=when(o.optString("t",o.optString("type")).uppercase()) {
                        "[P$]","LOAN","PRESTAMO","PRÉSTAMO" -> "PRESTAMO"
                        "[C$]","CONSUMPTION","CONSUMO" -> "CONSUMO"
                        else -> ""
                    }
                    if(type.isBlank()||amount<=0L) {
                        requestsPersisted=false
                        continue
                    }
                    val req=OwnerPayrollStore.Request(id,profile,type,date,
                        o.optString("z",o.optString("description",o.optString("r",o.optString("reason")))),amount,
                        o.optString("p",o.optString("product_code",o.optString("productCode"))),
                        o.optInt("q",o.optInt("quantity")),state,o.optString("by",o.optString("decided_by")),
                        wireTimestamp(o.opt("at") ?: o.opt("decided_at")),o.optString("rr",o.optString("rejection_reason")))
                    val persisted=if(state=="APPROVED"||state=="REJECTED") {
                        // El cursor solo avanza después de guardar y aplicar la decisión localmente.
                        val staged=OwnerPayrollStore.upsertServerRequest(app,req.copy(status="PENDING",ownerDecision="",ownerDecisionReason=""))
                        staged && OwnerPayrollStore.applyServerDecision(app,id,state,req.decidedBy,req.decidedAt,req.rejectionReason)
                    } else OwnerPayrollStore.upsertServerRequest(app,req)
                    if(!persisted)requestsPersisted=false
                }
                // El cursor solo puede avanzar cuando TODOS los tipos de novedades
                // quedaron guardados localmente. Si uno falla, el servidor debe reenviarlo.
                var incomingEventsPersisted = true
                val attendance=response.optJSONArray("h")?:response.optJSONArray("attendance")?:JSONArray()
                val acceptedAttendance=JSONArray()
                for(i in 0 until attendance.length()) {
                    val event=attendance.optJSONObject(i)
                    if(event == null) {
                        incomingEventsPersisted = false
                        continue
                    }
                    if(OwnerPayrollStore.applySyncedAttendance(app,event)) acceptedAttendance.put(event.optString("e"))
                    else incomingEventsPersisted = false
                }
                mergeAcks(p,acceptedAttendance)
                if (acceptedAttendance.length() > 0) flushAcks = true
                val serverAck=response.optJSONArray(SeneWireProtocol.FIELD_ATTENDANCE_ACK_CONFIRMATIONS)?:response.optJSONArray("attendance_ack_acks")?:JSONArray()
                clearAcks(p,serverAck)
                val inventory=response.optJSONArray("g")?:response.optJSONArray("inventory")?:JSONArray()
                val acceptedInventory=JSONArray()
                for(i in 0 until inventory.length()) {
                    val event=inventory.optJSONObject(i)
                    if(event == null) {
                        incomingEventsPersisted = false
                        continue
                    }
                    if(SharedInventoryManager.applySyncedInventoryEvent(app,event)) acceptedInventory.put(event.optString("e"))
                    else incomingEventsPersisted = false
                }
                mergeInventoryAcks(p,acceptedInventory)
                if (acceptedInventory.length() > 0) flushAcks = true
                val inventoryServerAck=response.optJSONArray(SeneWireProtocol.FIELD_INVENTORY_ACK_CONFIRMATIONS)?:response.optJSONArray("inventory_ack_acks")?:JSONArray()
                clearInventoryAcks(p,inventoryServerAck)

                // Aplicar decisiones después de guardar inventario: un consumo aprobado
                // puede depender de la fotografía o del delta recibido en este mismo lote.
                val decisionAcks=response.optJSONArray("ada")?:response.optJSONArray("admin_decision_acks")?:JSONArray()
                for(i in 0 until decisionAcks.length()) {
                    val decision=decisionAcks.optJSONObject(i)?:continue
                    val id=decision.optString("e",decision.optString("id"))
                    val state=when(decision.optString("x",decision.optString("status")).uppercase()) {
                        SeneWireProtocol.APPROVED,"APPROVED"->"APPROVED"
                        SeneWireProtocol.REJECTED,"REJECTED"->"REJECTED"
                        else->""
                    }
                    if(id.isBlank()||state !in setOf("APPROVED","REJECTED"))continue
                    OwnerPayrollStore.applyServerDecision(app,id,state,
                        decision.optString("by",decision.optString("decided_by")),
                        wireTimestamp(decision.opt("at")?:decision.opt("decided_at")),
                        decision.optString("rr",decision.optString("rejection_reason")))
                }

                // Persistir los sobres antes de confirmar su recepción al servidor.
                val reports=response.optJSONArray("b")?:response.optJSONArray("reports")?:JSONArray()
                val acceptedReports = ReportInboxStore.accept(app,reports)
                if (acceptedReports.length() != reports.length()) incomingEventsPersisted = false
                if (acceptedReports.length() > 0) flushAcks = true
                val reportAckConfirmations=response.optJSONArray(SeneWireProtocol.FIELD_REPORT_ACK_CONFIRMATIONS)?:response.optJSONArray("report_ack_acks")?:JSONArray()
                ReportInboxStore.acknowledgeAcks(app,reportAckConfirmations)
                // La pantalla se refresca desde la caché después de que termine toda la consulta.

                val cursor=response.optString(SeneWireProtocol.FIELD_NEXT_CURSOR,response.optString("next_cursor"))
                // Si algún registro no quedó persistido, se conserva el cursor para que el servidor lo reenvíe.
                if(cursor.isNotBlank() && requestsPersisted && incomingEventsPersisted)
                    p.edit().putString("cursor",cursor).commit()
            } catch(_:Exception) {
                // Sin conexión: la cola local sigue intacta y se reintentará después.
            } finally {
                val callbacks = synchronized(syncLock) {
                    syncRunning = false
                    completionCallbacks.toList().also { completionCallbacks.clear() }
                }
                if (callbacks.isNotEmpty() || flushAcks) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        callbacks.forEach { callback -> try { callback.invoke() } catch (_: Exception) { } }
                        // Confirma al servidor lo que ya quedó guardado localmente. Así la
                        // siguiente pulsación de ACTUALIZAR no vuelve a descargar el mismo lote.
                        if (flushAcks) sync(app, true)
                    }
                }
            }
        }.start()
    }
}
