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
 * Every field is quoted and embedded quotes are doubled per RFC 4180. Newlines
 * inside a field are preserved inside its quoted cell.
 */
object DiagnosticsCsvExporter {
    private const val HEADER = "timestamp,transport,event_type,peer_id,summary"

    fun toCsv(rows: List<DiagnosticsCsvRow>): String = buildString {
        append(HEADER)
        append('\n')
        rows.forEach { row ->
            append(
                listOf(row.timestamp, row.transport, row.eventType, row.peerId, row.summary)
                    .joinToString(",") { it.toCsvCell() }
            )
            append('\n')
        }
    }

    private fun String.toCsvCell(): String = "\"" + replace("\"", "\"\"") + "\""
}
