package com.sqb.complexityradar.adapters.common

import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.sqb.complexityradar.core.model.DomainTag
import com.sqb.complexityradar.core.model.RuleEvidence
import kotlin.math.max

data class DomainAnalysis(
    val tags: Set<DomainTag>,
    val evidences: List<RuleEvidence>,
)

object AnalysisSupport {
    fun effectiveLoc(text: String): Int =
        text
            .lineSequence()
            .map { it.trim() }
            .count { line ->
                line.isNotEmpty() &&
                    !line.startsWith("//") &&
                    !line.startsWith("/*") &&
                    !line.startsWith("*") &&
                    !line.startsWith("*/")
            }

    fun countTodo(text: String): Int =
        Regex("""\b(TODO|FIXME)\b""").findAll(text).count()

    fun countBangBang(text: String): Int = Regex("""!!""").findAll(text).count()

    fun countMagicNumbers(text: String): Int =
        Regex("""(?<![\w.])-?\b\d+\b(?!\s*([:A-Za-z_]))""")
            .findAll(text)
            .count { match ->
                val value = match.value.toIntOrNull() ?: return@count false
                value !in setOf(-1, 0, 1)
            }

    fun lineNumber(
        file: PsiFile,
        element: PsiElement,
    ): Int {
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return 1
        return document.getLineNumber(element.textRange.startOffset) + 1
    }

    fun snippet(
        element: PsiElement,
        maxLength: Int = 480,
    ): String {
        val text = element.text.replace('\t', ' ').trim()
        return if (text.length <= maxLength) text else text.take(maxLength) + "..."
    }

    fun nestingPenalty(depth: Int): Int {
        if (depth < 3) {
            return 0
        }
        var total = 0
        for (current in 3..depth) {
            total += 1 shl (current - 3)
        }
        return total
    }

    fun mergePenalty(
        current: Int,
        depth: Int,
    ): Int = current + nestingPenalty(depth)

    fun maxDocumentLine(document: Document?): Int = document?.lineCount ?: 0
}

object DomainEvidenceCollector {
    fun collect(candidates: Iterable<String>): DomainAnalysis {
        val tags = linkedSetOf<DomainTag>()
        val evidences = mutableListOf<RuleEvidence>()
        candidates.forEach { raw ->
            val value = raw.trim()
            if (value.isEmpty()) {
                return@forEach
            }
            classify(value)?.let { tag ->
                if (tags.add(tag)) {
                    evidences += RuleEvidence(rule = "domain:${tag.name.lowercase()}", kind = "token", value = value)
                } else {
                    evidences += RuleEvidence(rule = "domain:${tag.name.lowercase()}", kind = "token", value = value)
                }
            }
        }
        return DomainAnalysis(tags, evidences)
    }

    private fun classify(value: String): DomainTag? {
        val candidate = value.lowercase()
        return when {
            matchesAny(candidate, "android.view", "android.widget", "compose", "fragment", "activity", "viewmodel", "recyclerview") -> DomainTag.UI
            matchesAny(candidate, "retrofit", "okhttp", "http", "api", "socket", "webservice") -> DomainTag.NETWORK
            matchesAny(candidate, "room", "database", "dao", "sql", "datastore", "sharedpreferences", "contentresolver") -> DomainTag.STORAGE
            matchesAny(candidate, "gson", "jackson", "moshi", "serialization", "parcelize", "protobuf") -> DomainTag.SERIALIZATION
            matchesAny(candidate, "hilt", "dagger", "koin", "inject", "provides", "module") -> DomainTag.DI
            matchesAny(candidate, "coroutine", "dispatcher", "flow", "executor", "thread", "rx", "suspend") -> DomainTag.CONCURRENCY
            matchesAny(candidate, "intent", "service", "broadcast", "process", "system", "binder") -> DomainTag.SYSTEM
            else -> null
        }
    }

    private fun matchesAny(
        value: String,
        vararg candidates: String,
    ): Boolean = candidates.any { value.contains(it) }
}

internal fun MutableCollection<String>.addIfPresent(value: String?) {
    if (!value.isNullOrBlank()) {
        add(value)
    }
}

internal fun Int.coerceAtLeastOne(): Int = max(1, this)
