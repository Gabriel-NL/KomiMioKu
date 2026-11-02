package tachiyomi.domain.localtag.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class CollectionWorkbookTest {
    @Test
    fun `workbook is valid XML and preserves unicode ids and formulas as text`() {
        val bytes = ByteArrayOutputStream()
        CollectionWorkbook.write(
            bytes,
            listOf(
                CollectionWorkbook.Sheet(
                    "Relations",
                    listOf(
                        listOf("9223372036854775807", "=1+1", "A&B <tag>", "A\u00e7\u00e3o \u65e5\u672c"),
                    ),
                ),
            ),
        )
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        assertEquals(5, entries.size)
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        entries.values.forEach { factory.newDocumentBuilder().parse(ByteArrayInputStream(it)) }
        val xml = entries.getValue("xl/worksheets/sheet1.xml").toString(Charsets.UTF_8)
        assertTrue(xml.contains("9223372036854775807"))
        assertTrue(xml.contains("=1+1"))
        assertTrue(xml.contains("A&amp;B &lt;tag&gt;"))
        assertTrue(xml.contains("A\u00e7\u00e3o \u65e5\u672c"))
        assertFalse(xml.contains("<f>"))
    }

    @Test
    fun `oversized cells are rejected rather than silently truncated`() {
        assertThrows(IllegalArgumentException::class.java) {
            CollectionWorkbook.write(ByteArrayOutputStream(), listOf(CollectionWorkbook.Sheet("Tags", listOf(listOf("x".repeat(32768))))))
        }
    }
}
