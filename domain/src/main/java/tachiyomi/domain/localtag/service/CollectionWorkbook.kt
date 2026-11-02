package tachiyomi.domain.localtag.service

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A text-cell XLSX export: IDs keep all digits and user text never becomes a formula. */
object CollectionWorkbook {
    data class Sheet(val name: String, val rows: List<List<String>>)

    fun write(output: OutputStream, sheets: List<Sheet>) {
        require(sheets.isNotEmpty())
        ZipOutputStream(output).use { zip ->
            fun entry(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry(
                "[Content_Types].xml",
                """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""" +
                    sheets.indices.joinToString("") { """<Override PartName="/xl/worksheets/sheet${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""" } + "</Types>",
            )
            entry("_rels/.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            entry(
                "xl/workbook.xml",
                """<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""" +
                    sheets.mapIndexed { i, sheet -> """<sheet name="${escape(sheet.name)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""" }.joinToString("") + "</sheets></workbook>",
            )
            entry(
                "xl/_rels/workbook.xml.rels",
                """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                    sheets.indices.joinToString("") { """<Relationship Id="rId${it + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${it + 1}.xml"/>""" } + "</Relationships>",
            )
            sheets.forEachIndexed { i, sheet ->
                require(sheet.rows.size <= 1048576) { "Too many rows for Excel" }
                zip.putNextEntry(ZipEntry("xl/worksheets/sheet${i + 1}.xml"))
                fun append(value: String) {
                    zip.write(value.toByteArray(Charsets.UTF_8))
                }
                append("""<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
                sheet.rows.forEachIndexed { row, cells ->
                    append("""<row r="${row + 1}">""")
                    cells.forEach { cell ->
                        require(cell.length <= 32767) { "Cell is too long for Excel" }
                        append("""<c t="inlineStr"><is><t xml:space="preserve">${escape(cell)}</t></is></c>""")
                    }
                    append("</row>")
                }
                append("</sheetData></worksheet>")
                zip.closeEntry()
            }
        }
    }

    private fun escape(text: String): String = buildString {
        text.forEach { c ->
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '\"' -> append("&quot;")
                else -> if (c == '\t' || c == '\n' || c == '\r' || c >= ' ') append(c)
            }
        }
    }
}
