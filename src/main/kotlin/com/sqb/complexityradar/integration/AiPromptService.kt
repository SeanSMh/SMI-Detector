package com.sqb.complexityradar.integration

import com.sqb.complexityradar.core.model.ComplexityResult
import java.nio.file.Files
import java.nio.file.Path

class AiPromptService(
    private val projectBasePath: String?,
) {
    fun buildPrompt(result: ComplexityResult): String {
        val hotspotSection =
            result.hotspots.take(3).joinToString("\n\n") { hotspot ->
                buildString {
                    append("### ${hotspot.methodName} (line ${hotspot.line}, score ${hotspot.score})\n")
                    append(hotspot.snippet ?: "Snippet unavailable")
                    append("\nKey contributors: ${hotspot.contributions.joinToString { it.type.displayName }}")
                }
            }

        val factorSection =
            result.contributions.take(5).joinToString("\n") { contribution ->
                "- ${contribution.type.displayName}: ${(contribution.weightedScore * 100).toInt()} (${contribution.explanation})"
            }

        return buildString {
            appendLine("Refactor the following file while preserving behavior.")
            appendLine()
            appendLine("File score: ${result.score} (${result.severity.label})")
            appendLine("Primary factors:")
            appendLine(factorSection)
            appendLine()
            appendLine("Hotspots:")
            appendLine(hotspotSection.ifBlank { "No hotspots detected." })
            appendLine()
            appendLine("Constraints:")
            appendLine("- Do not change public APIs unless necessary.")
            appendLine("- Prefer reducing nesting, splitting responsibilities, and extracting helper functions.")
            appendLine("- Keep behavior unchanged and propose tests for risky changes.")
            appendLine()
            appendLine("Output:")
            appendLine("- Refactoring steps")
            appendLine("- Updated code or patch")
            appendLine("- Risks and validation notes")
        }
    }

    fun savePrompt(result: ComplexityResult): Path? {
        val base = projectBasePath ?: return null
        val directory = Path.of(base).resolve("complexity-radar-prompts")
        Files.createDirectories(directory)
        val fileName = result.filePath.substringAfterLast('/').replace('.', '_') + ".md"
        val path = directory.resolve(fileName)
        Files.writeString(path, buildPrompt(result))
        return path
    }
}
