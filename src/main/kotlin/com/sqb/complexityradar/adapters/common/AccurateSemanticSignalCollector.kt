package com.sqb.complexityradar.adapters.common

import com.intellij.psi.PsiFile
import com.intellij.psi.PsiType
import com.sqb.complexityradar.core.model.AnalyzeMode
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UFile
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UVariable
import org.jetbrains.uast.toUElementOfType
import org.jetbrains.uast.visitor.AbstractUastVisitor

object AccurateSemanticSignalCollector {
    fun collect(
        file: PsiFile,
        mode: AnalyzeMode,
    ): Set<String> {
        if (mode != AnalyzeMode.ACCURATE) {
            return emptySet()
        }
        val uFile = file.toUElementOfType<UFile>() ?: return emptySet()
        val signals = linkedSetOf<String>()
        uFile.accept(
            object : AbstractUastVisitor() {
                override fun visitVariable(node: UVariable): Boolean {
                    signals.addType(node.type)
                    return super.visitVariable(node)
                }

                override fun visitMethod(node: UMethod): Boolean {
                    signals.addType(node.returnType)
                    return super.visitMethod(node)
                }

                override fun visitCallExpression(node: UCallExpression): Boolean {
                    signals.addType(node.receiverType)
                    signals.addType(node.returnType)
                    signals.addIfPresent(node.resolve()?.containingClass?.qualifiedName)
                    return super.visitCallExpression(node)
                }
            },
        )
        return signals
    }

    private fun MutableSet<String>.addType(type: PsiType?) {
        val canonical = type?.canonicalText?.trim().orEmpty()
        if (canonical.isEmpty()) {
            return
        }
        if ('<' !in canonical && '.' !in canonical && canonical[0].isLowerCase()) {
            return
        }
        add(canonical)
    }
}
