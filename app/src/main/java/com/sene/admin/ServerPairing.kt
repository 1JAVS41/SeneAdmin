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
import java.util.concurrent.atomic.AtomicBoolean

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
        .put("rp",ReportKeyPairStore.publicKeyBase64()))
}

/** Sincroniza solicitudes y decisiones en lotes; conserva la cola local cuando no hay red. */
object AdminPayrollRequestSync {
    private const val PREFS="sene_admin_payroll_sync"
    private val running=AtomicBoolean(false)
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
    private fun wireDate(value:Any?):String {
        val millis=when(value) {
            is Number -> wireMillis(value.toLong())
            is String -> value.toLongOrNull()?.let(::wireMillis) ?: 0L
            else -> 0L
        }
        if(millis>0L) return java.text.SimpleDateFormat("yyyy-MM-dd",java.util.Locale.US).format(java.util.Date(millis))
        return value?.toString().orEmpty().take(10)
    }
    fun sync(c:Context,force:Boolean=false) {
        val app=c.applicationContext
        val token=ServerPairing.token(app);if(token.isBlank())return
        val p=app.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
        val now=System.currentTimeMillis()
        if(!force&&now-p.getLong("last_attempt",0L)<15L*60L*1000L)return
        if(!running.compareAndSet(false,true))return
        Thread {
            try {
                val decisions=JSONArray()
                OwnerPayrollStore.decisionsPendingSync(app).forEach{r->
                    decisions.put(JSONObject().put("e",r.id)
                        .put("x",if(r.ownerDecision=="APPROVE") SeneWireProtocol.APPROVED else SeneWireProtocol.REJECTED)
                        .put("r",r.ownerDecisionReason))
                }
                val payload=JSONObject().put("v",SeneWireProtocol.VERSION).put("t",SeneWireProtocol.SYNC)
                    .put("a",token).put("c",p.getString("cursor","").orEmpty()).put("d",decisions)
                    .put("ha",try{JSONArray(p.getString(ATTENDANCE_ACKS,"[]")?:"[]")}catch(_:Exception){JSONArray()})
                    .put("gi",try{JSONArray(p.getString(INVENTORY_ACKS,"[]")?:"[]")}catch(_:Exception){JSONArray()})
                    .put("ba",ReportInboxStore.pendingAcks(app))
                    .put("rp",ReportKeyPairStore.publicKeyBase64())
                p.edit().putLong("last_attempt",now).commit()
                val response=ServerPairing.post("/admin/payroll/requests/sync",payload)
                if(response.optInt("_http_status",0) !in 200..299)return@Thread
                if(response.optInt("v",SeneWireProtocol.VERSION)!=SeneWireProtocol.VERSION)return@Thread
                val requests=response.optJSONArray("items")?:response.optJSONArray("requests")?:JSONArray()
                for(i in 0 until requests.length()) {
                    val o=requests.optJSONObject(i)?:continue
                    val id=o.optString("e",o.optString("request_id",o.optString("id")))
                    val state=when(o.optString("x",o.optString("status","PENDING")).uppercase()) {
                        SeneWireProtocol.APPROVED -> "APPROVED"; SeneWireProtocol.REJECTED -> "REJECTED"; else -> o.optString("status","PENDING").uppercase()
                    }
                    val profile=o.optString("w",o.optString("worker_id",o.optString("profile_id")))
                    if(id.isBlank()||profile.isBlank())continue
                    val date=wireDate(o.opt("i") ?: o.opt("created_at") ?: o.opt("date"))
                    val amount=o.optLong("a",o.optLong("amount_cents",o.optLong("amountCents")))
                    val type=when(o.optString("t",o.optString("type")).uppercase()) {
                        "[P$]","LOAN","PRESTAMO","PRÉSTAMO" -> "PRESTAMO"
                        "[C$]","CONSUMPTION","CONSUMO" -> "CONSUMO"
                        else -> ""
                    }
                    if(type.isBlank())continue
                    val req=OwnerPayrollStore.Request(id,profile,type,date,
                        o.optString("z",o.optString("description",o.optString("r",o.optString("reason")))),amount,
                        o.optString("p",o.optString("product_code",o.optString("productCode"))),
                        o.optInt("q",o.optInt("quantity")),state,o.optString("by",o.optString("decided_by")),
                        wireMillis(o.optLong("at",o.optLong("decided_at"))),o.optString("rr",o.optString("rejection_reason")))
                    if(state=="APPROVED"||state=="REJECTED") {
                        // Primero asegura el registro pendiente; applyServerDecision solo lo finaliza
                        // cuando todos los efectos locales necesarios se aplicaron correctamente.
                        OwnerPayrollStore.upsertServerRequest(app,req.copy(status="PENDING",ownerDecision="",ownerDecisionReason=""))
                        OwnerPayrollStore.applyServerDecision(app,id,state,req.decidedBy,req.decidedAt,req.rejectionReason)
                    } else OwnerPayrollStore.upsertServerRequest(app,req)
                }
                val attendance=response.optJSONArray("h")?:response.optJSONArray("attendance")?:JSONArray()
                val acceptedAttendance=JSONArray()
                for(i in 0 until attendance.length()) {
                    val event=attendance.optJSONObject(i)?:continue
                    if(OwnerPayrollStore.applySyncedAttendance(app,event))acceptedAttendance.put(event.optString("e"))
                }
                mergeAcks(p,acceptedAttendance)
                val serverAck=response.optJSONArray("haa")?:response.optJSONArray("attendance_ack_acks")?:JSONArray()
                clearAcks(p,serverAck)
                val inventory=response.optJSONArray("g")?:response.optJSONArray("inventory")?:JSONArray()
                val acceptedInventory=JSONArray()
                for(i in 0 until inventory.length()) {
                    val event=inventory.optJSONObject(i)?:continue
                    if(SharedInventoryManager.applySyncedInventoryEvent(app,event))acceptedInventory.put(event.optString("e"))
                }
                mergeInventoryAcks(p,acceptedInventory)
                val inventoryServerAck=response.optJSONArray("gaa")?:response.optJSONArray("inventory_ack_acks")?:JSONArray()
                clearInventoryAcks(p,inventoryServerAck)

                // Persistir los sobres antes de confirmar su recepción al servidor.
                val reports=response.optJSONArray("b")?:response.optJSONArray("reports")?:JSONArray()
                ReportInboxStore.accept(app,reports)
                val reportAckConfirmations=response.optJSONArray("baa")?:response.optJSONArray("report_ack_acks")?:JSONArray()
                ReportInboxStore.acknowledgeAcks(app,reportAckConfirmations)

                val cursor=response.optString("n",response.optString("next_cursor"))
                if(cursor.isNotBlank())p.edit().putString("cursor",cursor).commit()
            } catch(_:Exception) {
                // Sin conexión: la cola local sigue intacta y se reintentará después.
            } finally {running.set(false)}
        }.start()
    }
}
