package dev.satya.workbench.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ShelfItem(
    val id: String,
    /** "text" or "file". */
    val kind: String,
    val mime: String,
    val name: String,
    val text: String,
    val path: String,
    val time: Long,
    /** "clip" when it came from the clipboard history, "drop" when it was dropped or pasted in. */
    val src: String = "drop",
    /** Content hash of a file item, used to keep the shelf free of duplicates. */
    val hash: String = "",
) {
    val isImage get() = kind == "file" && mime.startsWith("image/")
}

/**
 * The bench's drop tray: anything dragged out of an app (text, images, files) is kept here as a private copy,
 * so it survives the drag permission. Items can be dragged back into any app or handed to ColorOS FileDock.
 */
object Shelf {
    private const val TAG = "Workbench"
    const val AUTHORITY = "dev.satya.workbench.files"
    /** ColorOS FileDock (Smart Sidebar "Transfer Dock") takes SEND / SEND_MULTIPLE. */
    val FILEDOCK = ComponentName("com.coloros.smartsidebar", "com.oplus.smartsidebar.panelview.transferdock.view.TransferDockReceiveActivity")

    val items = MutableStateFlow<List<ShelfItem>>(emptyList())
    private lateinit var dir: File
    private val thumbs = HashMap<String, Bitmap?>()

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "shelf").apply { mkdirs() }
        items.value = runCatching {
            val a = JSONArray(File(dir, "index.json").readText())
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                ShelfItem(o.getString("id"), o.getString("kind"), o.optString("mime"), o.optString("name"), o.optString("text"), o.optString("path"), o.optLong("time"), o.optString("src", "drop"), o.optString("hash"))
            }.filter { it.kind == "text" || File(it.path).exists() }
        }.getOrDefault(emptyList())
        dedupeLoaded()
        lastClipStamp = runCatching { File(dir, "clipstamp").readText().toLong() }.getOrDefault(0L)
    }

    private fun save() {
        val a = JSONArray()
        for (s in items.value) a.put(
            JSONObject().put("id", s.id).put("kind", s.kind).put("mime", s.mime).put("name", s.name).put("text", s.text)
                .put("path", s.path).put("time", s.time).put("src", s.src).put("hash", s.hash)
        )
        runCatching { File(dir, "index.json").writeText(a.toString()) }
    }

    /** Newest first, no duplicates (a repeat moves the old copy to the front), at most [MAX] items. */
    @Synchronized
    private fun add(s: ShelfItem) {
        val dup = items.value.firstOrNull { it.sameAs(s) }
        if (dup != null && s.kind == "file" && dup.path != s.path) runCatching { File(s.path).delete() }
        val front = dup?.copy(time = s.time, src = if (s.src == "drop") "drop" else dup.src) ?: s
        val all = listOf(front) + items.value.filter { it.id != front.id }
        for (old in all.drop(MAX)) if (old.kind == "file") { runCatching { File(old.path).delete() }; thumbs.remove(old.id) }
        items.value = all.take(MAX)
        save()
    }

    private fun ShelfItem.sameAs(o: ShelfItem) = kind == o.kind && when (kind) {
        "text" -> text.trim() == o.text.trim()
        else -> hash.isNotEmpty() && hash == o.hash
    }

    const val MAX = 50

    /** Items kept before the shelf deduplicated: hash old files, drop repeats (newest copy wins), cap the list. */
    private fun dedupeLoaded() {
        val hashed = items.value.map { s ->
            if (s.kind != "file" || s.hash.isNotEmpty()) s
            else s.copy(hash = runCatching { hashFile(File(s.path)) }.getOrDefault(""))
        }
        val kept = ArrayList<ShelfItem>()
        for (s in hashed) {
            if (kept.any { it.sameAs(s) } || kept.size >= MAX) { if (s.kind == "file") runCatching { File(s.path).delete() } }
            else kept += s
        }
        if (kept != items.value) { items.value = kept; save() }
    }

    private fun hashFile(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-1")
        f.inputStream().use { inp -> val buf = ByteArray(64 * 1024); while (true) { val n = inp.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun addText(t: String, src: String = "drop") {
        if (t.isBlank()) return
        add(ShelfItem("s" + System.nanoTime(), "text", "text/plain", "", t, "", System.currentTimeMillis(), src))
    }

    /** When the clip last taken from the clipboard was set, so the same clip is never taken twice. */
    @Volatile private var lastClipStamp = 0L

    /**
     * Clipboard history: take what was just copied (text, images, files) into the shelf. Our own copies
     * (label "Shelf") and clips marked sensitive (passwords) are skipped. Call off the main thread.
     */
    fun ingestClip(ctx: Context, clip: ClipData?) {
        if (clip == null || clip.itemCount == 0) return
        val d = clip.description
        if (d.label == "Shelf") return
        val stamp = d.timestamp
        if (stamp != 0L && stamp == lastClipStamp) return
        lastClipStamp = stamp
        runCatching { File(dir, "clipstamp").writeText(stamp.toString()) }
        if (d.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true) return
        for (i in 0 until clip.itemCount) {
            val it = clip.getItemAt(i)
            val uri = it.uri
            if (uri != null) addUri(ctx, uri, if (i < d.mimeTypeCount) d.getMimeType(i) else null, "clip")
            else it.text?.toString()?.let { t -> addText(t, "clip") }
        }
    }

    /** Copy a content:// (or file://) item into the shelf. Call off the main thread while the drop permission is held. */
    fun addUri(ctx: Context, uri: Uri, mimeHint: String?, src: String = "drop"): Boolean = runCatching {
        val cr = ctx.contentResolver
        val mime = cr.getType(uri) ?: mimeHint ?: "application/octet-stream"
        var name = runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "item"
        if (!name.contains('.')) android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.let { name += ".$it" }
        val id = "s" + System.nanoTime()
        val f = File(dir, id + "_" + name.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(80))
        val md = java.security.MessageDigest.getInstance("SHA-1")
        cr.openInputStream(uri)!!.use { inp -> java.security.DigestInputStream(inp, md).use { d -> f.outputStream().use { d.copyTo(it) } } }
        val hash = md.digest().joinToString("") { "%02x".format(it) }
        add(ShelfItem(id, "file", mime, name, "", f.absolutePath, System.currentTimeMillis(), src, hash))
        true
    }.onFailure { Log.w(TAG, "shelf: copy $uri", it) }.getOrDefault(false)

    fun remove(s: ShelfItem) {
        items.value = items.value.filter { it.id != s.id }
        if (s.kind == "file") runCatching { File(s.path).delete() }
        thumbs.remove(s.id)
        save()
    }

    fun clear() { for (s in items.value.toList()) remove(s) }

    fun uri(ctx: Context, s: ShelfItem): Uri = FileProvider.getUriForFile(ctx, AUTHORITY, File(s.path))

    /** What a drag out of the shelf (or a copy) carries. */
    fun clip(ctx: Context, s: ShelfItem): ClipData =
        if (s.kind == "text") ClipData.newPlainText("Shelf", s.text)
        else ClipData("Shelf", arrayOf(s.mime), ClipData.Item(uri(ctx, s)))

    fun copy(ctx: Context, s: ShelfItem) {
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip(ctx, s))
    }

    /** Hand items to ColorOS FileDock. */
    fun toFileDock(ctx: Context, list: List<ShelfItem>): Boolean {
        if (list.isEmpty()) return false
        val texts = list.filter { it.kind == "text" }
        val files = list.filter { it.kind == "file" }
        var ok = true
        if (files.isNotEmpty()) ok = ok and send(ctx, files.map { uri(ctx, it) }, if (files.map { it.mime }.distinct().size == 1) files[0].mime else "*/*", null)
        for (t in texts) ok = ok and send(ctx, emptyList(), "text/plain", t.text)
        return ok
    }

    fun share(ctx: Context, s: ShelfItem) {
        val i = sendIntent(ctx, if (s.kind == "file") listOf(uri(ctx, s)) else emptyList(), s.mime, if (s.kind == "text") s.text else null)
        runCatching { ctx.startActivity(Intent.createChooser(i, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
    }

    private fun sendIntent(ctx: Context, uris: List<Uri>, mime: String, text: String?): Intent {
        val i = if (uris.size > 1) Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        else Intent(Intent.ACTION_SEND).apply { uris.firstOrNull()?.let { putExtra(Intent.EXTRA_STREAM, it) } }
        i.type = mime
        if (text != null) i.putExtra(Intent.EXTRA_TEXT, text)
        if (uris.isNotEmpty()) {
            val c = ClipData("Shelf", arrayOf(mime), ClipData.Item(uris[0]))
            for (u in uris.drop(1)) c.addItem(ClipData.Item(u))
            i.clipData = c
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun send(ctx: Context, uris: List<Uri>, mime: String, text: String?): Boolean = runCatching {
        for (u in uris) ctx.grantUriPermission(FILEDOCK.packageName, u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(sendIntent(ctx, uris, mime, text).setComponent(FILEDOCK))
        true
    }.onFailure { Log.w(TAG, "filedock", it) }.getOrDefault(false)

    /** Small preview of an image item (decoded once, then cached). */
    fun thumb(s: ShelfItem, px: Int = 360): Bitmap? = thumbs.getOrPut(s.id) {
        runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(s.path, o)
            var n = 1
            while (o.outWidth / (n * 2) >= px && o.outHeight / (n * 2) >= px) n *= 2
            BitmapFactory.decodeFile(s.path, BitmapFactory.Options().apply { inSampleSize = n })
        }.getOrNull()
    }
}
