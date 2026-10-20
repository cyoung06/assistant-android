package com.syeyoung.assistant.notebook

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.util.Matrix
import java.io.File

/**
 * PDFs with their annotations drawn into the pages. Android's PdfRenderer (and the viewers built on
 * it) leaves annotations out, and that's where our ink and images are kept; a flattened copy shows
 * them anywhere. Links and popups stay annotations.
 */
object Flatten {
    /** Changes whenever what [flatten] makes does: copies made by another version are made again. */
    const val VERSION = 2

    /** [pdf] with its annotations drawn in, written to [out]; false (and nothing written) if it has none to draw. */
    fun flatten(context: Context, pdf: File, out: File): Boolean {
        PDFBoxResourceLoader.init(context.applicationContext)
        PDDocument.load(pdf, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            var drawn = 0
            for (page in doc.pages) {
                val annotations = page.annotations
                val flat = annotations.filter { a ->
                    a.subtype != "Link" && a.subtype != "Popup" && !a.isHidden && !a.isNoView && a.normalAppearanceStream != null && a.rectangle != null
                }
                if (flat.isEmpty()) continue
                // Appended after the page's own content, which is wrapped so its graphics state can't leak.
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    for (a in flat) {
                        val ap = a.normalAppearanceStream
                        val box = ap.bBox ?: continue
                        val rect = a.rectangle
                        // The appearance's box as its matrix places it, fitted to the annotation's rectangle (PDF 12.5.5).
                        val m = ap.matrix
                        val xs = listOf(box.lowerLeftX to box.lowerLeftY, box.upperRightX to box.lowerLeftY, box.lowerLeftX to box.upperRightY, box.upperRightX to box.upperRightY)
                            .map { (x, y) -> m.scaleX * x + m.shearX * y + m.translateX to m.shearY * x + m.scaleY * y + m.translateY }
                        val minX = xs.minOf { it.first }; val maxX = xs.maxOf { it.first }
                        val minY = xs.minOf { it.second }; val maxY = xs.maxOf { it.second }
                        if (maxX <= minX || maxY <= minY) continue
                        val sx = rect.width / (maxX - minX)
                        val sy = rect.height / (maxY - minY)
                        cs.saveGraphicsState()
                        cs.transform(Matrix(sx, 0f, 0f, sy, rect.lowerLeftX - minX * sx, rect.lowerLeftY - minY * sy))
                        cs.drawForm(ap)
                        cs.restoreGraphicsState()
                        drawn++
                    }
                }
                page.annotations = annotations - flat.toSet()
            }
            if (drawn == 0) return false
            doc.save(out)
        }
        return true
    }
}
