package com.sqb.complexityradar.integration

import com.sqb.complexityradar.core.model.ComplexityResult
import com.sqb.complexityradar.ide.cache.JsonSupport
import java.nio.file.Files
import java.nio.file.Path

class ExportService(
    private val projectBasePath: String?,
) {
    fun exportAll(
        allResults: List<ComplexityResult>,
        changedResults: List<ComplexityResult>,
        outputDir: Path? = null,
    ): Path? {
        val directory = outputDir ?: run {
            val base = projectBasePath ?: return null
            Path.of(base).resolve("complexity-radar-report")
        }
        Files.createDirectories(directory)

        val payload =
            mapOf(
                "generatedAt" to System.currentTimeMillis(),
                "allResults" to allResults,
                "changedResults" to changedResults,
            )
        Files.writeString(
            directory.resolve("report.json"),
            JsonSupport.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload),
        )
        Files.writeString(directory.resolve("report.md"), markdown(allResults, changedResults))
        Files.writeString(directory.resolve("report.html"), html(allResults, changedResults))
        return directory
    }

    private fun markdown(
        allResults: List<ComplexityResult>,
        changedResults: List<ComplexityResult>,
    ): String =
        buildString {
            appendLine("# Complexity Radar Report")
            appendLine()
            appendLine("## Top Files")
            allResults.take(50).forEach { result ->
                appendLine("- `${result.filePath}`: ${result.score} (${result.severity.label})")
            }
            appendLine()
            appendLine("## Changed Files")
            if (changedResults.isEmpty()) {
                appendLine("- No changed files detected.")
            } else {
                changedResults.forEach { result ->
                    appendLine("- `${result.filePath}`: ${result.score} (${result.severity.label})")
                }
            }
        }

    private fun html(
        allResults: List<ComplexityResult>,
        changedResults: List<ComplexityResult>,
    ): String =
        buildString {
            appendLine("<html><head><meta charset=\"utf-8\"><title>Complexity Radar Report</title>")
            appendLine("<style>body{font-family:Arial,sans-serif;padding:24px;}table{border-collapse:collapse;width:100%;margin-bottom:24px;}th,td{border:1px solid #ddd;padding:8px;text-align:left;}th{background:#f5f5f5;}</style></head><body>")
            appendLine("<h1>Complexity Radar Report</h1>")
            appendLine("<h2>Top Files</h2>")
            appendLine("<table><tr><th>File</th><th>Score</th><th>Severity</th></tr>")
            allResults.take(50).forEach { result ->
                appendLine("<tr><td>${escape(result.filePath)}</td><td>${result.score}</td><td>${result.severity.label}</td></tr>")
            }
            appendLine("</table>")
            appendLine("<h2>Changed Files</h2>")
            appendLine("<table><tr><th>File</th><th>Score</th><th>Severity</th></tr>")
            if (changedResults.isEmpty()) {
                appendLine("<tr><td colspan=\"3\">No changed files detected.</td></tr>")
            } else {
                changedResults.forEach { result ->
                    appendLine("<tr><td>${escape(result.filePath)}</td><td>${result.score}</td><td>${result.severity.label}</td></tr>")
                }
            }
            appendLine("</table></body></html>")
        }

    private fun escape(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
}
