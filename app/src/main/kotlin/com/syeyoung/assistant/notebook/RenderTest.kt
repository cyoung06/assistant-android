package com.syeyoung.assistant.notebook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Renders a dot-grid page at 4× twice: whole, and only a region (through a translated transform),
 * into the app's external files dir, to compare where PdfRenderer actually puts it.
 */
object RenderTest {
    private fun patternGrid(): ByteArray {
        val cell = "0.85 0.85 0.88 RG 0.4 w 0 0 m 17 0 l S 0 0 m 0 17 l S"
        val fill = "/Pattern cs /P scn 28 28 527.5 782.5 re f"
        val objs = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Count 1 /Kids [3 0 R] >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R /Resources << /Pattern << /P 5 0 R >> >> >>",
            "<< /Length ${fill.length} >>\nstream\n$fill\nendstream",
            "<< /Type /Pattern /PatternType 1 /PaintType 1 /TilingType 1 /BBox [0 0 17 17] /XStep 17 /YStep 17 /Resources << >> /Matrix [1 0 0 1 28 28] /Length ${cell.length} >>\nstream\n$cell\nendstream",
        )
        val out = java.io.ByteArrayOutputStream()
        out.write("%PDF-1.4\n".toByteArray())
        val offsets = objs.mapIndexed { i, o -> out.size().also { out.write("${i + 1} 0 obj\n$o\nendobj\n".toByteArray()) } }
        val xref = out.size()
        out.write(("xref\n0 ${objs.size + 1}\n0000000000 65535 f \n" + offsets.joinToString("") { "%010d 00000 n \n".format(it) } +
            "trailer\n<< /Size ${objs.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n").toByteArray())
        return out.toByteArray()
    }

    /** A page of irregular marks (nothing repeats), to measure shifts unambiguously. */
    private fun marks(): ByteArray {
        val r = java.util.Random(7)
        val ops = StringBuilder("0 0 0 RG 1.2 w\n")
        repeat(160) { ops.append("${r.nextInt(560) + 15} ${r.nextInt(800) + 20} m ${r.nextInt(560) + 15} ${r.nextInt(800) + 20} l S\n") }
        repeat(60) { ops.append("${r.nextInt(560) + 15} ${r.nextInt(800) + 20} ${r.nextInt(30) + 4} ${r.nextInt(30) + 4} re f\n") }
        val content = ops.toString()
        val objs = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Count 1 /Kids [3 0 R] >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R /Resources << >> >>",
            "<< /Length ${content.length} >>\nstream\n$content\nendstream",
        )
        val out = java.io.ByteArrayOutputStream()
        out.write("%PDF-1.4\n".toByteArray())
        val offsets = objs.mapIndexed { i, o -> out.size().also { out.write("${i + 1} 0 obj\n$o\nendobj\n".toByteArray()) } }
        val xref = out.size()
        out.write(("xref\n0 ${objs.size + 1}\n0000000000 65535 f \n" + offsets.joinToString("") { "%010d 00000 n \n".format(it) } +
            "trailer\n<< /Size ${objs.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n").toByteArray())
        return out.toByteArray()
    }

    fun run(context: Context) {
        val dir = context.getExternalFilesDir("render-test")!!.apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val pdf = File(dir, "page.pdf").apply { writeBytes(BlankPdf.notebook(1, PageStyle.GRID)) }
        // The same grid as a tiling pattern (how pages were made before), to compare.
        val old = File(dir, "pattern.pdf").apply { writeBytes(patternGrid()) }
        PdfRenderer(ParcelFileDescriptor.open(old, ParcelFileDescriptor.MODE_READ_ONLY)).use { r ->
            r.openPage(0).use { page ->
                val z = 2.7313f
                val b = Bitmap.createBitmap((page.width * z).toInt(), (page.height * z).toInt(), Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                page.render(b, null, Matrix().apply { setScale(z, z) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                File(dir, "patternz.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        File(dir, "marks.pdf").writeBytes(marks())
        PdfRenderer(ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)).use { r ->
            r.openPage(0).use { page ->
                val s = 4f
                fun out(name: String, w: Int, h: Int, m: Matrix) {
                    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                    page.render(b, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
                out("full", (page.width * s).toInt(), (page.height * s).toInt(), Matrix().apply { setScale(s, s) })
                out("region", 800, 600, Matrix().apply { setScale(s, s); postTranslate(-60.5f, -90.25f) })
                // A fractional zoom, as pinching gives.
                val z = 2.7313f
                out("fullz", (page.width * z).toInt(), (page.height * z).toInt(), Matrix().apply { setScale(z, z) })
                out("regionz", 700, 500, Matrix().apply { setScale(z, z); postTranslate(-41.7f, -63.9f) })
                File(dir, "info.txt").writeText("page ${page.width}x${page.height} sdk ${android.os.Build.VERSION.SDK_INT}\n")
            }
        }
    }
}
