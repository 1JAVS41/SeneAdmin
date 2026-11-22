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
        .put("admin_installation_id",installationId(c)))
}

/** Sincroniza solicitudes y decisiones en lotes; conserva la cola local cuando no hay red. */
object AdminPayrollRequestSync {
    private const val PREFS="sene_admin_payroll_sync"
    private val running=AtomicBoolean(false)
    fun sync(c:Context,force:Boolean=false) {
        val app=c.applicationContext
        val token=ServerPairing.token(app);if(token.isBlank())return
        val p=app.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
        val now=System.currentTimeMillis()
        if(!force&&now-p.getLong("last_attempt",0L)<5L*60L*1000L)return
        if(!running.compareAndSet(false,true))return
        Thread {
            try {
                val decisions=JSONArray()
                OwnerPayrollStore.decisionsPendingSync(app).forEach{r->
                    decisions.put(JSONObject().put("request_id",r.id).put("decision",r.ownerDecision)
                        .put("reason",r.ownerDecisionReason))
                }
                val payload=JSONObject().put("admin_token",token)
                    .put("cursor",p.getString("cursor","").orEmpty()).put("decisions",decisions)
                p.edit().putLong("last_attempt",now).commit()
                val response=ServerPairing.post("/admin/payroll/requests/sync",payload)
                if(response.optInt("_http_status",0) !in 200..299)return@Thread
                val requests=response.optJSONArray("requests")?:JSONArray()
                for(i in 0 until requests.length()) {
                    val o=requests.optJSONObject(i)?:continue
                    val id=o.optString("request_id",o.optString("id"))
                    val state=o.optString("status","PENDING").uppercase()
                    val profile=o.optString("worker_id",o.optString("profile_id"))
                    if(id.isBlank()||profile.isBlank())continue
                    val date=o.optString("created_at",o.optString("date"))
                    val amount=o.optLong("amount_cents",o.optLong("amountCents"))
                    val type=when(o.optString("type").uppercase()){"LOAN","PRESTAMO","PRÉSTAMO"->"PRESTAMO";"CONSUMPTION","CONSUMO"->"CONSUMO";else->""}
                    if(type.isBlank())continue
                    val req=OwnerPayrollStore.Request(id,profile,type,date,
                        o.optString("description",o.optString("reason")),amount,o.optString("product_code",o.optString("productCode")),
                        o.optInt("quantity"),state,o.optString("decided_by"),o.optLong("decided_at"),
                        o.optString("rejection_reason"))
                    if(state=="APPROVED"||state=="REJECTED") {
                        // Primero asegura el registro pendiente; applyServerDecision solo lo finaliza
                        // cuando todos los efectos locales necesarios se aplicaron correctamente.
                        OwnerPayrollStore.upsertServerRequest(app,req.copy(status="PENDING",ownerDecision="",ownerDecisionReason=""))
                        OwnerPayrollStore.applyServerDecision(app,id,state,req.decidedBy,req.decidedAt,req.rejectionReason)
                    } else OwnerPayrollStore.upsertServerRequest(app,req)
                }
                val cursor=response.optString("next_cursor")
                if(cursor.isNotBlank())p.edit().putString("cursor",cursor).commit()
            } catch(_:Exception) {
                // Sin conexión: la cola local sigue intacta y se reintentará después.
            } finally {running.set(false)}
        }.start()
    }
}
