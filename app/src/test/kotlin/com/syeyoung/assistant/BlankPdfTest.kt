package com.syeyoung.assistant

import com.syeyoung.assistant.notebook.BlankPdf
import com.syeyoung.assistant.notebook.PageStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BlankPdfTest {
    @Test
    fun `every style makes a small notebook (vector, compressed) with a correct cross-reference table`() {
        for (style in PageStyle.entries) {
            val pdf = BlankPdf.notebook(20, style)
            assertTrue("$style size ${pdf.size}", pdf.size < 16_000)
            val text = String(pdf, Charsets.ISO_8859_1)
            assertEquals(20, Regex("/Type /Page /").findAll(text).count())
            // Every xref entry points at the start of its object.
            val xref = text.substring(text.lastIndexOf("startxref") + 10).trim().lines().first().toInt()
            val entries = text.substring(xref).lines().drop(3).takeWhile { it.endsWith(" n ") }
            entries.forEachIndexed { i, e -> assertTrue("$style obj ${i + 1}", text.startsWith("${i + 1} 0 obj", e.take(10).toInt())) }
            File("build/notebook-${style.name.lowercase()}.pdf").writeBytes(pdf) // to look at
        }
    }
}
