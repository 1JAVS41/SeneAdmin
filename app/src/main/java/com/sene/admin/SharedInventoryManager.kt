package com.sene.admin

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** SAF y archivos compartidos SENE. La lectura nunca modifica el archivo de origen. */
object SharedInventoryManager {
    const val REQUEST_TREE = 7401
    private const val PREFS = "sene_admin_shared_inventory"
    private const val TREE = "tree_uri"

    data class Item(val code: String, val name: String, val price: Double, val stock: Int, val aisle: String, val shelf: String)
    data class Snapshot(val category: String, val fileName: String, val updated: String, val items: List<Item>, val legacy: Boolean)

    fun savedTree(c: Context): Uri? = prefs(c).getString(TREE, null)?.let(Uri::parse)
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun chooseFolder(activity: Activity) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        activity.startActivityForResult(intent, REQUEST_TREE)
    }

    fun acceptFolder(activity: Activity, uri: Uri) {
        activity.contentResolver.takePersistableUriPermission(uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        prefs(activity).edit().putString(TREE, uri.toString()).apply()
    }

    private fun root(c: Context, tree: Uri) =
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    private fun children(c: Context, tree: Uri, parent: Uri): List<Triple<String, String, Uri>> {
        val result = mutableListOf<Triple<String, String, Uri>>()
        val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        c.contentResolver.query(childUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cur ->
            val id = cur.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val name = cur.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mime = cur.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cur.moveToNext()) {
                if (id < 0 || name < 0 || mime < 0) continue
                result.add(Triple(cur.getString(name) ?: continue, cur.getString(mime) ?: "", 
                    DocumentsContract.buildDocumentUriUsingTree(tree, cur.getString(id))))
            }
        }
        return result
    }

    private fun folder(c: Context, tree: Uri, name: String, create: Boolean): Uri? {
        val selectedRoot = root(c, tree)
        // Si el usuario seleccionó la propia carpeta INVENTARIO-SENE, esa carpeta ES
        // el destino compartido; nunca crear otra INVENTARIO-SENE dentro de ella.
        if (name.equals("INVENTARIO-SENE", true)) {
            val selectedName = c.contentResolver.query(selectedRoot,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cur ->
                if (cur.moveToFirst()) cur.getString(0) else null
            }
            if (selectedName.equals("INVENTARIO-SENE", true)) return selectedRoot
        }
        val existing = children(c, tree, selectedRoot).firstOrNull {
            it.first.equals(name, true) && it.second == DocumentsContract.Document.MIME_TYPE_DIR
        }?.third
        if (existing != null || !create) return existing
        return DocumentsContract.createDocument(c.contentResolver, selectedRoot,
            DocumentsContract.Document.MIME_TYPE_DIR, name)
    }

    private fun sortKey(name: String): String {
        val timestamp = Regex("(?i)^inventario-(\\d{8}-\\d{6}-\\d{3})\\.sene$").matchEntire(name)?.groupValues?.get(1)
        if (timestamp != null) return "2" + timestamp
        val sequence = Regex("(?i)^inventario-(\\d+)\\.txt$").matchEntire(name)?.groupValues?.get(1)?.toLongOrNull()
        if (sequence != null) return "1" + sequence.toString().padStart(20, '0')
        return "0" + name
    }

    private fun decode(c: Context, bytes: ByteArray, ownerKey: String, expectedCategory: String, name: String): Snapshot {
        if (SeneSharedFiles.isSharedFile(bytes)) {
            val doc = SeneSharedFiles.decode(bytes, ownerKey)
            require(doc.category == expectedCategory) { "El archivo pertenece a otra categoría SENE." }
            val text = doc.payload.toString(Charsets.UTF_8)
            return Snapshot(doc.category, name, doc.updated, parse(text), false)
        }
        // Compatibilidad de lectura con inventarios TXT anteriores. Nunca se reescriben al abrirlos.
        val text = when {
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            else -> String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
        }
        val items = parse(text)
        require(items.isNotEmpty()) { "El TXT no contiene filas de inventario válidas." }
        return Snapshot(expectedCategory, name, "Archivo TXT anterior", items, true)
    }

    private fun parse(text: String): List<Item> {
        val rows = mutableListOf<Item>()
        for (raw in text.replace("\r", "").lines()) {
            val line = raw.trim()
            if (line.isBlank() || line.startsWith("INVENTARIO", true) || line.startsWith("Actualizado:", true) ||
                line.startsWith("ETIQUETA:", true) || line.startsWith("SENE VENTAS", true)) continue
            val normalized = java.text.Normalizer.normalize(line, java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)
            if (normalized.contains("codigo") && normalized.contains("producto")) continue
            val p = when {
                line.contains("|") -> line.split("|").map { it.trim() }
                line.contains("\t") -> line.split("\t").map { it.trim() }
                line.contains(";") -> line.split(";").map { it.trim() }
                else -> emptyList()
            }
            if (p.size < 4) continue
            val priceText = p[2].replace("$", "").replace(" ", "")
            val priceNorm = if (priceText.contains(".") && priceText.contains(",")) priceText.replace(",", "") else priceText.replace(",", ".")
            val price = priceNorm.toDoubleOrNull()
            val stock = Regex("-?\\d+").find(p[3])?.value?.toIntOrNull()
            if (p[0].isNotBlank() && p[1].isNotBlank() && price != null && stock != null)
                rows.add(Item(p[0], p[1], price, stock, p.getOrElse(4) { "" }, p.getOrElse(5) { "" }))
        }
        return rows
    }

    fun list(c: Context, ownerKey: String, category: String): List<Snapshot> {
        val tree = savedTree(c) ?: throw IllegalStateException("Primero selecciona la carpeta INVENTARIO SENE.")
        val dirName = if (category == "INVENTARIO_MAESTRO") "INVENTARIO-MAESTRO" else "INVENTARIO-SENE"
        val dir = folder(c, tree, dirName, false) ?: return emptyList()
        // Leer también las carpetas INVENTARIO-SENE anidadas por versiones anteriores.
        // Así los respaldos existentes siguen visibles; no se mueven ni se eliminan.
        val files = mutableListOf<Triple<String, String, Uri>>()
        val visited = mutableSetOf<String>()
        fun collect(parent: Uri, depth: Int) {
            if (depth > 8) return
            val parentId = DocumentsContract.getDocumentId(parent)
            if (!visited.add(parentId)) return
            for ((name, mime, uri) in children(c, tree, parent)) {
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (name.equals(dirName, true)) collect(uri, depth + 1)
                } else if (name.endsWith(".sene", true) || name.endsWith(".txt", true)) {
                    files.add(Triple(name, mime, uri))
                }
            }
        }
        collect(dir, 0)
        return files.sortedByDescending { sortKey(it.first) }.mapNotNull { (name, _, uri) ->
            try {
                val bytes = c.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@mapNotNull null
                decode(c, bytes, ownerKey, category, name)
            } catch (_: Exception) { null }
        }
    }

    fun save(c: Context, ownerKey: String, category: String, items: List<Item>, updatedOverride: String? = null): String {
        require(items.isNotEmpty()) { "No hay productos para guardar." }
        val tree = savedTree(c) ?: throw IllegalStateException("Primero selecciona la carpeta INVENTARIO SENE.")
        val dirName = if (category == "INVENTARIO_MAESTRO") "INVENTARIO-MAESTRO" else "INVENTARIO-SENE"
        val dir = folder(c, tree, dirName, true) ?: throw IllegalStateException("No se pudo crear $dirName.")
        val updated = normalizeUpdated(updatedOverride) ?: SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val text = buildString {
            append("INVENTARIO_SENE_V1\nActualizado: ").append(updated)
            append("\nCódigo | Producto | Precio | Existencia | Pasillo | Estante")
            items.forEach { append("\n").append(it.code).append(" | ").append(it.name).append(" | ")
                .append(it.price).append(" | ").append(it.stock).append(" | ").append(it.aisle).append(" | ").append(it.shelf) }
        }
        val bytes = SeneSharedFiles.encode(category, ownerKey, text.toByteArray(Charsets.UTF_8), updated)
        val name = "inventario-" + SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date()) + ".sene"
        val uri = DocumentsContract.createDocument(c.contentResolver, dir, "application/octet-stream", name)
            ?: throw IllegalStateException("No se pudo crear el respaldo $name.")
        c.contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
            ?: throw IllegalStateException("No se pudo escribir el respaldo $name.")
        // Copia privada adicional: si se pierde el acceso SAF, el último respaldo local sigue disponible.
        val local = java.io.File(c.filesDir, "SENE-ADMIN/$dirName")
        if (!local.exists() && !local.mkdirs()) throw IllegalStateException("No se pudo crear la copia local.")
        java.io.File(local, name).writeBytes(bytes)
        cleanupIfDue(c, tree, dirName, ownerKey)
        return name
    }

    /** Guarda el mensaje de reporte ya cifrado por SENE VENTAS en la carpeta compartida. */
    fun saveReport(c: Context, report: SeneReport, encryptedMessage: String, ownerKey: String): String {
        val tree = savedTree(c) ?: throw IllegalStateException("Carpeta compartida no seleccionada.")
        val dir = folder(c, tree, "REPORTES", true) ?: throw IllegalStateException("No se pudo crear REPORTES.")
        val datePart = report.date.replace("-", "")
        val timePart = report.time.replace(":", "").take(6).padEnd(6, '0')
        val safeWorker = report.workerId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(30)
        val name = "reporte-$datePart-$timePart-$safeWorker-${report.number}-${report.id.take(8)}.sene"
        val uri = DocumentsContract.createDocument(c.contentResolver, dir, "application/octet-stream", name)
            ?: throw IllegalStateException("No se pudo crear el respaldo del reporte.")
        c.contentResolver.openOutputStream(uri, "w")?.use { it.write(encryptedMessage.toByteArray(Charsets.UTF_8)) }
            ?: throw IllegalStateException("No se pudo escribir el respaldo del reporte.")
        val local = java.io.File(c.filesDir, "SENE-ADMIN/REPORTES")
        if (!local.exists() && !local.mkdirs()) throw IllegalStateException("No se pudo crear la copia local de reportes.")
        java.io.File(local, name).writeText(encryptedMessage, Charsets.UTF_8)
        cleanupIfDue(c, tree, "REPORTES", ownerKey)
        return name
    }

    /** Recupera reportes cifrados desde la carpeta compartida sin modificar los archivos originales. */
    fun archivedReports(c: Context, ownerKey: String): List<SeneReport> {
        val tree = savedTree(c) ?: return emptyList()
        val dir = folder(c, tree, "REPORTES", false) ?: return emptyList()
        return children(c, tree, dir).filter { it.first.endsWith(".sene", true) || it.first.endsWith(".txt", true) }
            .sortedByDescending { it.first }.mapNotNull { (_, _, uri) ->
                try {
                    val raw = c.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@mapNotNull null
                    SeneReportReader.read(String(raw, Charsets.UTF_8), ownerKey)
                } catch (_: Exception) { null }
            }
    }

    /** Limpieza cada 30 días: conserva los 10 respaldos más recientes de cada categoría. */
    private fun cleanupIfDue(c: Context, tree: Uri, dirName: String, ownerKey: String) {
        val prefKey = "last_cleanup_" + dirName
        val now = System.currentTimeMillis()
        val last = prefs(c).getLong(prefKey, 0L)
        if (last > 0L && now - last < 30L * 24L * 60L * 60L * 1000L) return
        val dir = folder(c, tree, dirName, false) ?: return
        val files = children(c, tree, dir).filter { it.first.endsWith(".sene", true) || it.first.endsWith(".txt", true) }
            .sortedByDescending { sortKey(it.first) }
        // Nunca limpiar si no se pudo comprobar la clave de todos los respaldos cifrados existentes.
        // Los TXT heredados se conservan intactos; una clave incorrecta no autoriza ninguna eliminación.
        val valid = files.all { (_, _, uri) ->
            try {
                val raw = c.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@all false
                if (dirName == "REPORTES") {
                    SeneReportReader.read(String(raw, Charsets.UTF_8), ownerKey)
                    true
                } else if (SeneSharedFiles.isSharedFile(raw)) {
                    val doc = SeneSharedFiles.decode(raw, ownerKey)
                    val expected = if (dirName == "INVENTARIO-MAESTRO") "INVENTARIO_MAESTRO" else "INVENTARIO_SENE"
                    doc.category == expected
                } else false
            } catch (_: Exception) { false }
        }
        if (!valid) return
        var complete = true
        files.drop(10).forEach { (_, _, uri) ->
            try { if (!DocumentsContract.deleteDocument(c.contentResolver, uri)) complete = false }
            catch (_: Exception) { complete = false }
        }
        val local = java.io.File(c.filesDir, "SENE-ADMIN/$dirName")
        val localFiles = local.listFiles()?.filter { it.isFile }?.sortedByDescending { sortKey(it.name) } ?: emptyList()
        val localValid = localFiles.all { file ->
            try {
                val raw = file.readBytes()
                if (dirName == "REPORTES") {
                    SeneReportReader.read(String(raw, Charsets.UTF_8), ownerKey)
                    true
                } else {
                    val doc = SeneSharedFiles.decode(raw, ownerKey)
                    val expected = if (dirName == "INVENTARIO-MAESTRO") "INVENTARIO_MAESTRO" else "INVENTARIO_SENE"
                    doc.category == expected
                }
            } catch (_: Exception) { false }
        }
        if (localValid) localFiles.drop(10).forEach { if (!it.delete()) complete = false }
        else complete = false
        if (complete) prefs(c).edit().putLong(prefKey, now).apply()
    }

    private fun normalizeUpdated(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val formats = listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss", "dd/MM/yyyy HH:mm:ss", "yyyy-MM-dd")
        for (pattern in formats) try {
            val parser = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }
            val date = parser.parse(value.trim()) ?: continue
            return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(date)
        } catch (_: Exception) { }
        return null
    }

    /** Solo un reporte más reciente puede sustituir el inventario maestro; mensajes atrasados no revierten stock. */
    fun updateMasterFromReport(c: Context, ownerKey: String, reportBody: String, reportUpdated: String): String? {
        val incoming = normalizeUpdated(reportUpdated) ?: return null
        val current = list(c, ownerKey, "INVENTARIO_MAESTRO").firstOrNull()?.updated
        val currentNormalized = normalizeUpdated(current)
        if (currentNormalized != null && incoming <= currentNormalized) return null
        val lines = reportBody.replace("\r", "").lines()
        val marker = lines.indexOfLast { it.trim().equals("ETIQUETA: EXISTENCIAS ACTUALES", true) }
            .takeIf { it >= 0 } ?: lines.indexOfLast { it.trim().equals("INVENTARIO_SENE_V1", true) }
        if (marker < 0) return null
        val section = lines.drop(marker + 1).takeWhile { !it.trim().startsWith("ETIQUETA:", true) }
        val items = parse(section.joinToString("\n"))
        if (items.isEmpty()) return null
        return save(c, ownerKey, "INVENTARIO_MAESTRO", items, incoming)
    }
}
