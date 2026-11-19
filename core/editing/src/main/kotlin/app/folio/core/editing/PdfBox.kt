package app.folio.core.editing

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

/** One-time PDFBox initialisation (loads glyph lists / CMaps from its bundled assets — no network). */
object PdfBox {
    @Volatile private var initialised = false

    fun init(context: Context) {
        if (initialised) return
        synchronized(this) {
            if (!initialised) {
                PDFBoxResourceLoader.init(context.applicationContext)
                initialised = true
            }
        }
    }
}
