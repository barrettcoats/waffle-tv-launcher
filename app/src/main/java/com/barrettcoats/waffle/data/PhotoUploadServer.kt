package com.barrettcoats.waffle.data

import android.graphics.BitmapFactory
import com.barrettcoats.waffle.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.net.URLEncoder

/**
 * "Send photos from your phone": a small web page on the local network, running only while the
 * TV shows the upload screen. Every request must carry [code] (shown on the TV), uploads must
 * decode as images, and names are sanitised before anything is written into [folder].
 */
class PhotoUploadServer(
    port: Int,
    private val folder: File,
    private val code: String,
    private val onAdded: (File) -> Unit,
    private val onRemoved: () -> Unit,
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response = try {
        handle(session)
    } catch (e: Exception) {
        Log.e("Photo upload request failed", e)
        page("Something went wrong", "<p>${esc(e.message ?: "Unknown error")}</p>", status = Response.Status.INTERNAL_ERROR)
    }

    private fun handle(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        if (session.method == Method.POST) {
            val length = session.headers["content-length"]?.toLongOrNull() ?: 0
            if (length > MAX_UPLOAD_BYTES) {
                return page("Too big", "<p>That's more than ${MAX_UPLOAD_BYTES / 1_000_000} MB in one go. Try fewer photos at a time.</p>", Response.Status.PAYLOAD_TOO_LARGE)
            }
            session.parseBody(files)
        }
        val params = session.parameters
        val given = params["code"]?.firstOrNull()?.trim()
        if (given != code) return codePage(wrong = given != null)

        return when {
            session.uri == "/upload" && session.method == Method.POST -> upload(params, files)
            session.uri == "/delete" && session.method == Method.POST -> delete(params["name"]?.firstOrNull())
            session.uri.startsWith("/photo/") -> photo(session.uri.removePrefix("/photo/"))
            else -> home(added = params["added"]?.firstOrNull()?.toIntOrNull(), skipped = params["skipped"]?.firstOrNull()?.toIntOrNull())
        }
    }

    /**
     * Saves every uploaded file part. NanoHTTPD stores repeated parts under "photos", "photos2",
     * "photos3"… (or "photos1"… depending on version), so take all of them in order rather than
     * assuming a scheme, and pair them with the original names where those line up.
     */
    private fun upload(params: Map<String, List<String>>, files: Map<String, String>): Response {
        val names = params["photos"].orEmpty()
        val parts = files.keys
            .filter { it.matches(Regex("photos\\d*")) }
            .sortedBy { it.removePrefix("photos").toIntOrNull() ?: 0 }
        Log.i("Upload: ${parts.size} file part(s), ${names.size} name(s)")
        var added = 0
        var skipped = 0
        parts.forEachIndexed { i, key ->
            val temp = files[key]?.let(::File)
            if (temp == null || !temp.exists() || !isImage(temp)) {
                skipped++
                return@forEachIndexed
            }
            val target = uniqueTarget(names.getOrNull(i) ?: "photo.jpg")
            temp.copyTo(target)
            added++
            Log.i("Received wallpaper ${target.name} (${target.length() / 1024} KB)")
            onAdded(target)
        }
        return redirect("/?code=$code&added=$added&skipped=$skipped")
    }

    private fun delete(name: String?): Response {
        val file = name?.let { safeFile(it) }
        if (file != null && file.exists()) {
            file.delete()
            Log.i("Deleted wallpaper ${file.name}")
            onRemoved()
        }
        return redirect("/?code=$code")
    }

    private fun photo(name: String): Response {
        val file = safeFile(java.net.URLDecoder.decode(name, "UTF-8")) ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        if (!file.exists()) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        return newFixedLengthResponse(Response.Status.OK, mimeOf(file), FileInputStream(file), file.length())
    }

    private fun home(added: Int?, skipped: Int?): Response {
        val photos = folder.listFiles { f -> f.isFile && f.extension.lowercase() in IMAGE_EXTENSIONS }
            ?.sortedBy { it.name.lowercase() }.orEmpty()
        val notice = when {
            added == null -> ""
            added > 0 && (skipped ?: 0) == 0 -> "<p class=ok>Added $added photo${if (added == 1) "" else "s"}. They're on the TV now.</p>"
            added > 0 -> "<p class=ok>Added $added. Skipped $skipped that weren't photos.</p>"
            else -> "<p class=warn>Nothing was added${if ((skipped ?: 0) > 0) ": those didn't look like photos" else ""}.</p>"
        }
        val grid = if (photos.isEmpty()) {
            "<p class=muted>No photos on the TV yet. Until you add some, it shows a sky gradient.</p>"
        } else {
            photos.joinToString("", "<div class=grid>", "</div>") { f ->
                val n = URLEncoder.encode(f.name, "UTF-8")
                """<figure><img loading=lazy src="/photo/$n?code=$code" alt="">
                   <form method=post action="/delete"><input type=hidden name=code value="$code">
                   <input type=hidden name=name value="${esc(f.name)}"><button class=del>Remove</button></form></figure>"""
            }
        }
        val body = """
            $notice
            <form class=card method=post action="/upload" enctype="multipart/form-data">
              <input type=hidden name=code value="$code">
              <label class=pick>Choose photos<input type=file name=photos accept="image/*" multiple required
                onchange="this.form.querySelector('button').disabled=!this.files.length;
                          this.parentNode.firstChild.textContent=this.files.length+' selected '"></label>
              <button disabled>Send to TV</button>
            </form>
            <h2>On the TV (${photos.size})</h2>
            $grid
        """
        return page("Wallpapers", body)
    }

    private fun codePage(wrong: Boolean): Response = page(
        "Enter the code",
        """${if (wrong) "<p class=warn>That code didn't match. Check the TV.</p>" else ""}
           <form class=card method=get action="/"><p>Type the 4-digit code shown on the TV.</p>
           <input name=code inputmode=numeric pattern="[0-9]*" maxlength=4 autofocus required>
           <button>Continue</button></form>""",
        status = if (wrong) Response.Status.FORBIDDEN else Response.Status.OK,
    )

    private fun page(title: String, body: String, status: Response.Status = Response.Status.OK): Response =
        newFixedLengthResponse(
            status,
            "text/html; charset=utf-8",
            """<!doctype html><html><head><meta charset=utf-8>
            <meta name=viewport content="width=device-width,initial-scale=1"><title>Waffle · $title</title>
            <style>
              body{margin:0;font:16px/1.4 -apple-system,system-ui,Segoe UI,Roboto,sans-serif;color:#fff;
                   background:linear-gradient(#3f7fbf,#8ec3ea 60%,#f3ddb0) fixed;min-height:100vh}
              main{max-width:720px;margin:0 auto;padding:24px 16px 48px}
              h1{font-size:28px;margin:8px 0 16px;text-shadow:0 2px 8px #0005} h2{font-size:18px;margin:28px 0 10px;text-shadow:0 1px 6px #0005}
              .card{background:#202024e6;border-radius:20px;padding:20px;display:flex;flex-direction:column;gap:12px}
              input[name=code]{font-size:32px;letter-spacing:12px;text-align:center;padding:10px;border-radius:12px;border:0}
              button{font:inherit;font-weight:600;padding:12px 18px;border-radius:999px;border:0;background:#fff;color:#1c1c1e}
              button:disabled{opacity:.4} .pick{display:block;text-align:center;padding:14px;border-radius:14px;background:#ffffff1f;cursor:pointer}
              .pick input{display:none} .ok,.warn{padding:12px 16px;border-radius:14px;background:#202024e6} .warn{color:#ffc145}
              .muted{opacity:.85} .grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:12px}
              figure{margin:0;background:#202024e6;border-radius:14px;overflow:hidden}
              figure img{width:100%;aspect-ratio:16/9;object-fit:cover;display:block}
              figure form{padding:8px} .del{width:100%;padding:8px;background:#ffffff26;color:#fff}
            </style></head><body><main><h1>$title</h1>$body
            <p class=muted style="margin-top:32px;font-size:13px">This page only works while the TV shows its upload screen.</p>
            </main></body></html>""",
        )

    private fun redirect(to: String): Response =
        newFixedLengthResponse(Response.Status.REDIRECT_SEE_OTHER, "text/plain", "").apply { addHeader("Location", to) }

    private fun isImage(file: File): Boolean {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, opts)
        return opts.outWidth > 0 && opts.outHeight > 0
    }

    /** A clean, unused file name in [folder] that keeps the original name where possible. */
    private fun uniqueTarget(original: String): File {
        val base = original.substringAfterLast('/').substringAfterLast('\\')
        var ext = base.substringAfterLast('.', "").lowercase()
        if (ext !in IMAGE_EXTENSIONS) ext = "jpg"
        val stem = base.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().take(60).ifBlank { "photo" }
        var candidate = File(folder, "$stem.$ext")
        var n = 2
        while (candidate.exists()) candidate = File(folder, "$stem-${n++}.$ext")
        return candidate
    }

    /** Resolves [name] inside [folder] only (no "../" escapes). */
    private fun safeFile(name: String): File? {
        val file = File(folder, name)
        return file.takeIf { it.canonicalFile.parentFile == folder.canonicalFile && it.extension.lowercase() in IMAGE_EXTENSIONS }
    }

    private fun mimeOf(file: File) = when (file.extension.lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "heic", "heif" -> "image/heif"
        else -> "image/jpeg"
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    companion object {
        const val MAX_UPLOAD_BYTES = 300_000_000L
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic", "heif")
    }
}
