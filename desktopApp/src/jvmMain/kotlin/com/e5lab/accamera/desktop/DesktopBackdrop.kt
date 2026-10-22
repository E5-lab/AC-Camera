package com.e5lab.accamera.desktop

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Base-map import for the Windows client.
 *
 * Two hard rules learned from the field report "选择底图报错，无法添加图片":
 *
 *  1. **Never** block the Compose UI thread. `JFileChooser.showOpenDialog` has to run on the AWT
 *     event dispatcher, and the previous code called `EventQueue.invokeAndWait { … }` FROM that very
 *     dispatcher (Compose Desktop renders on the AWT EDT), which throws
 *     `Cannot call invokeAndWait from the event dispatcher thread` — the exact dialog in the bug
 *     report. We now detect the EDT and call the chooser inline in that case.
 *
 *  2. A picked file is **copied** into the app's own data directory. The user's plan may live on a
 *     USB stick / network share, and referencing it in place would leave the project pointing at a
 *     path that disappears.
 */
object DesktopBackdrop {

    /** Extensions offered in the picker (all of them, one filter — engineers don't want to guess). */
    val EXTENSIONS = listOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "pdf", "dxf")

    val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")
    val CAD_EXT = setOf("dxf")

    fun kindOf(path: String?): String {
        val ext = path?.substringAfterLast('.', "")?.lowercase() ?: return "?"
        return when {
            ext == "pdf" -> "PDF"
            ext in CAD_EXT -> "CAD"
            ext in IMAGE_EXT -> "图片"
            else -> "未知"
        }
    }

    fun isCad(path: String?): Boolean =
        path?.substringAfterLast('.', "")?.lowercase() in CAD_EXT

    fun isPdf(path: String?): Boolean =
        path?.substringAfterLast('.', "")?.lowercase() == "pdf"

    /**
     * Shows the open dialog. Safe from any thread: on the EDT it runs inline, otherwise it is
     * marshalled to the EDT with `invokeLater` + `invokeAndWait` from a NON-EDT caller.
     */
    fun choose(onDone: (File?) -> Unit) {
        val task = Runnable {
            val fc = JFileChooser().apply {
                dialogTitle = "选择底图（图片 / PDF / CAD-DXF）"
                isAcceptAllFileFilterUsed = true
                fileFilter = FileNameExtensionFilter(
                    "底图 (JPG/PNG/PDF/DXF)", *EXTENSIONS.toTypedArray(),
                )
            }
            val result = if (fc.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) fc.selectedFile else null
            onDone(result)
        }
        if (javax.swing.SwingUtilities.isEventDispatchThread()) {
            task.run()
        } else {
            javax.swing.SwingUtilities.invokeLater(task)
        }
    }

    /**
     * Copies [source] into `~/.ac_camera/backdrops/project_<id>.<ext>` and returns the new path.
     * Any previous base map of the project is removed so stale files cannot linger.
     */
    fun store(source: File, projectId: Long): File {
        val dir = File(dataDir(), "backdrops").apply { mkdirs() }
        val ext = source.extension.lowercase().ifBlank { "png" }
        dir.listFiles()?.forEach { f ->
            if (f.name.startsWith("project_${projectId}.")) f.delete()
        }
        val target = File(dir, "project_$projectId.$ext")
        Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return target
    }

    fun dataDir(): File {
        val dir = File(System.getProperty("user.home"), ".ac_camera")
        dir.mkdirs()
        return dir
    }

    /**
     * Renders page 1 of a PDF to a PNG next to the PDF, returning the PNG path.
     *
     * PDFBox is loaded reflectively: if the packaging step ever drops the optional PDF dependency,
     * importing a raster plan / DXF keeps working and the user gets a clear message instead of a
     * NoClassDefFoundError crash.
     */
    fun renderPdfFirstPage(pdf: File, dpi: Int = 150): File {
        val loader = Thread.currentThread().contextClassLoader
            ?: DesktopBackdrop::class.java.classLoader

        val documentClass = loadClass(
            listOf("org.apache.pdfbox.pdmodel.PDDocument", "org.apache.pdfbox.Loader"), loader,
        )
        val rendererClass = Class.forName("org.apache.pdfbox.rendering.PDFRenderer", true, loader)

        // PDFBox 3 removed PDDocument.load(File) in favour of Loader.loadPDF(File).
        val doc: Any = runCatching {
            documentClass.getMethod("loadPDF", File::class.java).invoke(null, pdf)
        }.getOrElse {
            val pdd = Class.forName("org.apache.pdfbox.pdmodel.PDDocument", true, loader)
            pdd.getMethod("load", File::class.java).invoke(null, pdf)
        } ?: error("PDF 打开失败")

        try {
            val pdd = Class.forName("org.apache.pdfbox.pdmodel.PDDocument", true, loader)
            val pages = pdd.getMethod("getNumberOfPages").invoke(doc) as Int
            require(pages > 0) { "PDF 没有页面" }
            val renderer = rendererClass.getConstructor(pdd).newInstance(doc)
            val image = rendererClass
                .getMethod("renderImage", Int::class.javaPrimitiveType, Float::class.javaPrimitiveType)
                .invoke(renderer, 0, dpi / 72f) as? java.awt.image.BufferedImage
                ?: error("PDF 第 1 页渲染失败")

            val width = image.width
            val height = image.height
            require(width > 0 && height > 0) { "PDF 页面为空" }

            // Flatten onto white: PDF pages are transparent, which would render as black plates.
            val flat = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val g = flat.createGraphics()
            g.color = java.awt.Color.WHITE
            g.fillRect(0, 0, width, height)
            g.drawImage(image, 0, 0, null)
            g.dispose()

            val out = File(pdf.parentFile, pdf.nameWithoutExtension + "_p1.png")
            javax.imageio.ImageIO.write(flat, "png", out)
            return out
        } finally {
            runCatching {
                Class.forName("org.apache.pdfbox.pdmodel.PDDocument", true, loader)
                    .getMethod("close").invoke(doc)
            }
        }
    }

    /** First class that exists out of [names] — keeps us compatible with PDFBox 2 and 3. */
    private fun loadClass(names: List<String>, loader: ClassLoader): Class<*> {
        var last: Throwable? = null
        for (n in names) {
            try {
                return Class.forName(n, true, loader)
            } catch (t: Throwable) {
                last = t
            }
        }
        throw IllegalStateException("PDF 引擎不可用（缺少 PDFBox 依赖）", last)
    }
}
