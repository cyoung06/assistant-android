package com.syeyoung.assistant

import com.syeyoung.assistant.notebook.BlankPdf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BlankPdfTest {
    @Test
    fun `a 20-page dot-grid notebook is a few KB with a correct cross-reference table`() {
        val pdf = BlankPdf.dotGrid(20)
        assertTrue("size ${pdf.size}", pdf.size < 8_000)
        val text = String(pdf, Charsets.US_ASCII)
        assertEquals(20, Regex("/Type /Page /").findAll(text).count())
        // Every xref entry points at the start of its object.
        val xref = text.substring(text.lastIndexOf("startxref") + 10).trim().lines().first().toInt()
        val entries = text.substring(xref).lines().drop(3).takeWhile { it.endsWith(" n ") }
        entries.forEachIndexed { i, e -> assertTrue(text.startsWith("${i + 1} 0 obj", e.take(10).toInt())) }
        File("build/blank-notebook.pdf").writeBytes(pdf) // to look at
    }
}
