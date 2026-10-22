package com.e5lab.accamera.ui.components

import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.e5lab.accamera.data.backdrop.BackdropImporter
import com.e5lab.accamera.domain.cad.CadDrawing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Small process-wide cache of decoded base maps.
 *
 * A floor plan is decoded once when a screen opens; without a cache every tab switch would re-decode
 * a multi-megapixel photo (and re-render a PDF page), which is what made the plot appear to
 * "do nothing" for a second or two.
 */
private val bitmapCache = object : LruCache<String, ImageBitmap>(4) {
    override fun sizeOf(key: String, value: ImageBitmap): Int = 1
}

/** True when [path] is a vector CAD drawing (drawn as paths, not decoded to a bitmap). */
fun isCadPath(path: String): Boolean = BackdropImporter.isCad(path)

/**
 * Loads a raster base map off the UI thread: JPG/PNG/WebP photos AND the first page of a PDF
 * drawing. Returns null while loading, for a CAD file, or when the file cannot be decoded.
 */
@Composable
fun rememberFileImageBitmap(path: String?): ImageBitmap? {
    val context = LocalContext.current
    var bitmap by remember(path) {
        mutableStateOf(path?.let { bitmapCache.get(it) })
    }
    LaunchedEffect(path) {
        if (path == null) {
            bitmap = null
            return@LaunchedEffect
        }
        bitmapCache.get(path)?.let { bitmap = it; return@LaunchedEffect }
        bitmap = withContext(Dispatchers.IO) {
            BackdropImporter.decode(context, path)?.asImageBitmap()?.also { bitmapCache.put(path, it) }
        }
    }
    return bitmap
}

/**
 * Loads a CAD (.dxf) base map off the UI thread, parsed into [CadDrawing] by the shared, pure-Kotlin
 * [com.e5lab.accamera.domain.cad.CadReader].
 *
 * Returns null for a raster plan / PDF or an unreadable file — the caller then renders
 * [rememberFileImageBitmap] instead.
 */
@Composable
fun rememberCadDrawing(path: String?): CadDrawing? {
    var drawing by remember(path) { mutableStateOf<CadDrawing?>(null) }
    LaunchedEffect(path) {
        drawing = if (path == null || !BackdropImporter.isCad(path)) {
            null
        } else {
            withContext(Dispatchers.IO) { BackdropImporter.loadCad(path) }
        }
    }
    return drawing
}
