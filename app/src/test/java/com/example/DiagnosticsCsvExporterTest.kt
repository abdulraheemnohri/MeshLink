package com.example

import com.example.core.diagnostics.DiagnosticsCsvExporter
import com.example.core.diagnostics.DiagnosticsCsvRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsCsvExporterTest {
    @Test
    fun `empty export contains header and RFC 4180 record separator`() {
        assertEquals(
            "timestamp,transport,event_type,peer_id,summary\r\n",
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
            "timestamp,transport,event_type,peer_id,summary\r\n" +
                "\"2026-11-17T12:30:00\",\"WIFI,LAN\",\"peer \"\"connected\"\"\",\"\",\"line one\nline two\"\r\n",
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
        assertTrue(csv.endsWith("\r\n"))
    }

    @Test
    fun `formula-like values are neutralized in every exported field`() {
        val dangerousValues = listOf("=1+1", "+SUM(A1:A2)", "-1+2", "@SUM(A1)", "  =cmd()", "\t@payload", "\uFEFF=payload")
        dangerousValues.forEach { value ->
            val csv = DiagnosticsCsvExporter.toCsv(
                listOf(DiagnosticsCsvRow(value, value, value, value, value))
            )
            val escaped = "\"'$value\""
            assertEquals(5, Regex(Regex.escape(escaped)).findAll(csv).count())
        }
    }

    @Test
    fun `ordinary values and embedded line breaks are preserved`() {
        val csv = DiagnosticsCsvExporter.toCsv(
            listOf(DiagnosticsCsvRow("2026-11-17", "BLE", "CONNECTED", "peer-1", "first\nsecond"))
        )
        assertTrue(csv.contains("\"2026-11-17\",\"BLE\",\"CONNECTED\",\"peer-1\",\"first\nsecond\""))
    }
}
