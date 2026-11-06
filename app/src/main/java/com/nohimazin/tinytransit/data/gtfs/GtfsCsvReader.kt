package com.nohimazin.tinytransit.data.gtfs

/**
 * 最小限の RFC 4180 準拠 CSV リーダー。
 * GTFS の quoted CSV をパースしてヘッダーと行のリストを返します。
 */
object GtfsCsvReader {

    fun parseLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var atFieldStart = true
        var i = 0
        val n = line.length
        while (i < n) {
            val c = line[i]
            when {
                inQuotes -> {
                    if (c == '"') {
                        if (i + 1 < n && line[i + 1] == '"') {
                            sb.append('"')
                            i++
                        } else {
                            inQuotes = false
                        }
                    } else {
                        sb.append(c)
                    }
                }
                c == '"' && atFieldStart -> {
                    inQuotes = true
                    atFieldStart = false
                }
                c == ',' -> {
                    fields.add(sb.toString())
                    sb.setLength(0)
                    atFieldStart = true
                }
                c == '\r' -> {
                    // ignore
                }
                else -> {
                    sb.append(c)
                    atFieldStart = false
                }
            }
            i++
        }
        fields.add(sb.toString())
        return fields
    }

    fun parse(text: String): List<Map<String, String>> {
        // UTF-8 BOM を除去
        val cleaned = if (text.startsWith("\uFEFF")) text.substring(1) else text
        val rows = parseRows(cleaned)
        if (rows.isEmpty()) return emptyList()
        val header = rows.first()
        val result = mutableListOf<Map<String, String>>()
        for (i in 1 until rows.size) {
            val row = rows[i]
            // 空行はスキップ
            if (row.isEmpty() || (row.size == 1 && row[0].isEmpty())) continue
            val map = LinkedHashMap<String, String>()
            for (j in header.indices) {
                val key = header.getOrElse(j) { "" }
                val value = row.getOrElse(j) { "" }
                if (key.isNotEmpty()) map[key] = value
            }
            result.add(map)
        }
        return result
    }

    private fun parseRows(text: String): List<List<String>> {
        val rows = mutableListOf<MutableList<String>>()
        var current = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var atFieldStart = true
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                inQuotes -> {
                    if (c == '"') {
                        // エスケープされた引用符
                        if (i + 1 < n && text[i + 1] == '"') {
                            sb.append('"')
                            i++
                        } else {
                            inQuotes = false
                        }
                    } else {
                        sb.append(c)
                    }
                }
                c == '"' && atFieldStart -> {
                    inQuotes = true
                    atFieldStart = false
                }
                c == ',' -> {
                    current.add(sb.toString())
                    sb.setLength(0)
                    atFieldStart = true
                }
                c == '\n' -> {
                    current.add(sb.toString())
                    rows.add(current)
                    current = mutableListOf()
                    sb.setLength(0)
                    atFieldStart = true
                }
                c == '\r' -> {
                    // CRLF 対応: \r は無視
                }
                else -> {
                    sb.append(c)
                    atFieldStart = false
                }
            }
            i++
        }
        // 最後のフィールド/行
        if (sb.length > 0 || current.isNotEmpty() || !atFieldStart) {
            current.add(sb.toString())
            if (current.isNotEmpty()) rows.add(current)
        }
        return rows
    }
}