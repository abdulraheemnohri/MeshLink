package com.example.core.diagnostics

data class DiagnosticsCsvRow(
    val timestamp: String,
    val transport: String,
    val eventType: String,
    val peerId: String,
    val summary: String
)

/**
 * Serializes the currently selected diagnostics events.
 *
 * Every field is quoted and embedded quotes are doubled per RFC 4180.
 * Record separators use CRLF; newlines inside a field remain inside its
 * quoted cell. Formula-like values are prefixed with an apostrophe so a
 * spreadsheet does not evaluate untrusted event text as a formula.
 */
object DiagnosticsCsvExporter {
    private const val HEADER = "timestamp,transport,event_type,peer_id,summary"
    private const val RECORD_SEPARATOR = "\r\n"
    private val formulaPrefixes = setOf('=', '+', '-', '@')

    fun toCsv(rows: List<DiagnosticsCsvRow>): String = buildString {
        append(HEADER)
        append(RECORD_SEPARATOR)
        rows.forEach { row ->
            append(
                listOf(row.timestamp, row.transport, row.eventType, row.peerId, row.summary)
                    .joinToString(",") { it.toCsvCell() }
            )
            append(RECORD_SEPARATOR)
        }
    }

    private fun String.toCsvCell(): String {
        val firstMeaningfulCharacter = firstOrNull { !it.isWhitespace() && it != '\uFEFF' }
        val safeValue = if (firstMeaningfulCharacter in formulaPrefixes) "'$this" else this
        return "\"" + safeValue.replace("\"", "\"\"") + "\""
    }
}
