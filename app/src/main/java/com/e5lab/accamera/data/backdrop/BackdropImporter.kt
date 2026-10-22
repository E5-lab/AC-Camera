package com.e5lab.accamera.data.backdrop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.e5lab.accamera.domain.cad.CadDrawing
import com.e5lab.accamera.domain.cad.CadReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Result of importing a user-selected base map.
 *
 * @param path the file stored under `filesDir/backdrops/` to persist as `backgroundImagePath`
 * @param kind what the file is, so the UI can pick the right renderer
 * @param detail human-readable summary (page count, vertex count…) shown as feedback
 */
data class BackdropImport(
    val path: String,
    val kind: BackdropKind,
    val detail: String,
)

enum class BackdropKind { IMAGE, PDF, CAD, UNKNOWN }

/**
 * Imports a base map chosen by the user — a floor-plan photo (JPG/PNG), a PDF drawing (only the
 * first page is used) or a CAD drawing (DXF).
 *
 * Why a dedicated class: the previous implementation copied every pick to `project_<id>.jpg`
 * regardless of its real type, so a PDF or DXF was stored under a `.jpg` name and then failed to
 * decode — which is exactly the "cannot add the base map" bug. Files now keep their real
 * extension and each type gets a working loader.
 *
 * The pick is copied into private storage (filesDir) because content URIs from the document picker
 * are temporary: they stop working after a reboot, which would silently lose the plan.
 */
object BackdropImporter {

    private const val DIR = "backdrops"

    // File extensions we can display. A wildcard MIME is offered in the picker, so filtering is
    // our job. NOTE: keep this as a line comment. A block comment cannot hold the wildcard MIME
    // literals below, because the "*" + "/" pair they contain would close the comment early and
    // the rest of the line would be parsed as Kotlin code ("Expecting member declaration").
    val PICKER_MIME = arrayOf("image/*", "application/pdf", "application/dxf", "*/*")

    suspend fun import(context: Context, uri: Uri, projectId: Long): Result<BackdropImport> =
        withContext(Dispatchers.IO) {
            runCatching {
                val name = queryDisplayName(context, uri)
                val ext = extensionOf(name, context, uri)
                val kind = kindOf(ext)

                val dir = File(context.filesDir, DIR).apply { mkdirs() }
                // Drop any previous plan of this project so stale files cannot linger.
                dir.listFiles()?.forEach { f ->
                    if (f.name.startsWith("project_${projectId}.")) f.delete()
                }
                val out = File(dir, "project_$projectId$ext")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                } ?: error("无法读取所选文件（权限或文件已移动）")

                when (kind) {
                    BackdropKind.PDF -> {
                        val pages = pdfPageCount(out)
                        BackdropImport(out.absolutePath, kind, "PDF 共 $pages 页，使用第 1 页作为底图")
                    }
                    BackdropKind.CAD -> {
                        val drawing = CadReader.read(out.readText())
                            ?: error("DXF 中未找到可用的线图")
                        BackdropImport(
                            out.absolutePath, kind,
                            "CAD 线图 ${drawing.polygons.size} 段 / 约 ${drawing.vertexCount} 个顶点",
                        )
                    }
                    BackdropKind.IMAGE, BackdropKind.UNKNOWN -> {
                        // Verify it really decodes before reporting success — otherwise the user
                        // sees a silent "selected" with a blank plot.
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(out.absolutePath, bounds)
                        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                            error("不支持的图片格式（请用 JPG / PNG / WebP）")
                        }
                        BackdropImport(
                            out.absolutePath, BackdropKind.IMAGE,
                            "底图 ${bounds.outWidth}×${bounds.outHeight} 像素",
                        )
                    }
                }
            }.onFailure { e ->
                e.printStackTrace()
            }
        }

    /**
     * Decodes the base map at [path] into a bitmap: images directly, PDFs by rendering the first
     * page with the platform [PdfRenderer]. Returns null for CAD (drawn as vectors instead) or on
     * any failure.
     */
    fun decode(context: Context, path: String, maxWidth: Int = 2400): Bitmap? = runCatching {
        if (isCad(path)) return null
        if (path.lowercase().endsWith(".pdf")) return renderPdfFirstPage(File(path), maxWidth)

        // Downsample large photos: a 12 MP plan would otherwise cost ~48 MB of heap.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / sample > maxWidth) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeFile(path, opts)
    }.getOrNull()

    /** Renders page 1 of [pdf] to an ARGB bitmap (PDFs have no bitmap payload, they must be drawn). */
    fun renderPdfFirstPage(pdf: File, maxWidth: Int = 2400): Bitmap? = runCatching {
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                if (renderer.pageCount <= 0) return null
                renderer.openPage(0).use { page ->
                    // Keep the aspect ratio, cap the width so big A0 plans stay manageable.
                    val scale = (maxWidth.toFloat() / page.width).coerceIn(0.2f, 4f)
                    val w = (page.width * scale).toInt().coerceAtLeast(1)
                    val h = (page.height * scale).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    // PDF pages are transparent by default -> paint white so the plan is readable.
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            }
        }
    }.getOrNull()

    private fun pdfPageCount(file: File): Int = runCatching {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { it.pageCount }
        }
    }.getOrDefault(1)

    fun isCad(path: String): Boolean {
        val p = path.lowercase()
        return p.endsWith(".dxf") || p.endsWith(".cad")
    }

    fun isPdf(path: String): Boolean = path.lowercase().endsWith(".pdf")

    fun loadCad(path: String): CadDrawing? = runCatching { CadReader.read(File(path).readText()) }.getOrNull()

    private fun kindOf(ext: String): BackdropKind = when (ext.lowercase()) {
        ".pdf" -> BackdropKind.PDF
        ".dxf", ".cad" -> BackdropKind.CAD
        ".jpg", ".jpeg", ".png", ".webp", ".bmp", ".gif" -> BackdropKind.IMAGE
        else -> BackdropKind.UNKNOWN
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull()

    /**
     * Resolves the real extension. `OpenDocument` URIs carry a MIME type but often no file name, so
     * fall back to the MIME type instead of assuming `.jpg`.
     */
    private fun extensionOf(name: String?, context: Context, uri: Uri): String {
        val fromName = name?.substringAfterLast('.', "")?.lowercase()
        if (!fromName.isNullOrBlank() && fromName.length in 1..5) return ".$fromName"
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()?.lowercase()
        return when {
            mime == null -> ".bin"
            mime.contains("pdf") -> ".pdf"
            mime.contains("dxf") -> ".dxf"
            mime.contains("png") -> ".png"
            mime.contains("webp") -> ".webp"
            mime.contains("jpeg") || mime.contains("jpg") -> ".jpg"
            else -> ".bin"
        }
    }
}
