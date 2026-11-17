package com.example

import com.example.core.diagnostics.DiagnosticsCsvExporter
import com.example.core.diagnostics.DiagnosticsCsvRow
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsCsvExporterTest {
    @Test
    fun `empty export contains header and final newline`() {
        assertEquals(
            "timestamp,transport,event_type,peer_id,summary\n",
            DiagnosticsCsvExporter.toCsv(emptyList())
        )
    }

    @Test
    fun `all values are quoted and embedded quotes are escaped`() {
        val csv = DiagnosticsCsvExporter.toCsv(
            listOf(
                DiagnosticsCsvRow(
                    timestamp = "2026-11-17T12:30:00",
                    transport = "WIFI,LAN",
                    eventType = "peer \"connected\"",
                    peerId = "",
                    summary = "line one\nline two"
                )
            )
        )
        assertEquals(
            "timestamp,transport,event_type,peer_id,summary\n" +
                "\"2026-11-17T12:30:00\",\"WIFI,LAN\",\"peer \"\"connected\"\"\",\"\",\"line one\nline two\"\n",
            csv
        )
    }

    @Test
    fun `multiple rows preserve their order`() {
        val csv = DiagnosticsCsvExporter.toCsv(
            listOf(
                DiagnosticsCsvRow("t1", "BLE", "DISCOVERED", "peer-1", "first"),
                DiagnosticsCsvRow("t2", "LAN", "CONNECTED", "peer-2", "second")
            )
        )
        assertTrue(csv.indexOf("\"t1\"") < csv.indexOf("\"t2\""))
    }

    private fun assertTrue(value: Boolean) {
        org.junit.Assert.assertTrue(value)
    }
}
