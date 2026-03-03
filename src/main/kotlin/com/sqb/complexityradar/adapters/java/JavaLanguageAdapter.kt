package com.sqb.complexityradar.adapters.java

import com.intellij.psi.JavaRecursiveElementVisitor
import com.intellij.psi.JavaTokenType
import com.intellij.psi.PsiBinaryExpression
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiConditionalExpression
import com.intellij.psi.PsiDoWhileStatement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiForStatement
import com.intellij.psi.PsiForeachStatement
import com.intellij.psi.PsiIfStatement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiPolyadicExpression
import com.intellij.psi.PsiSwitchLabelStatementBase
import com.intellij.psi.PsiSwitchStatement
import com.intellij.psi.PsiTryStatement
import com.intellij.psi.PsiWhileStatement
import com.intellij.psi.util.PsiTreeUtil
import com.sqb.complexityradar.adapters.LanguageAdapter
import com.sqb.complexityradar.adapters.common.AnalysisSupport
import com.sqb.complexityradar.adapters.common.AccurateSemanticSignalCollector
import com.sqb.complexityradar.adapters.common.DomainEvidenceCollector
import com.sqb.complexityradar.adapters.common.addIfPresent
import com.sqb.complexityradar.core.model.AnalyzeMode
import com.sqb.complexityradar.core.model.FileAstSummary
import com.sqb.complexityradar.core.model.Hotspot
import com.sqb.complexityradar.core.model.RadarConfig
import com.sqb.complexityradar.core.scoring.ComplexityScorer

class JavaLanguageAdapter(
    private val scorer: ComplexityScorer = ComplexityScorer(),
) : LanguageAdapter {
    override fun supports(file: PsiFile): Boolean = file is PsiJavaFile

    override fun summarize(
        file: PsiFile,
        mode: AnalyzeMode,
        config: RadarConfig,
    ): FileAstSummary {
        file as PsiJavaFile
        val text = file.text
        var statementCount = file.classes.sumOf { it.methods.size + it.fields.size }
        var functionCount = 0
        var typeCount = 0
        var branchCount = 0.0
        var loopCount = 0
        var tryCatchCount = 0
        var ternaryCount = 0
        var logicalOpCount = 0
        var maxBlockDepth = 0
        var nestingPenalty = 0
        var currentDepth = 0
        var maxFunctionLoc = 0
        var maxParamCount = 0
        var emptyCatchCount = 0

        val tokens = mutableListOf<String>()
        val annotations = linkedSetOf<String>()
        val superTypes = linkedSetOf<String>()
        val classNames = linkedSetOf<String>()

        file.importList?.allImportStatements?.forEach { statement ->
            tokens.addIfPresent(statement.importReference?.qualifiedName)
        }

        file.accept(
            object : JavaRecursiveElementVisitor() {
                override fun visitClass(aClass: PsiClass) {
                    typeCount += 1
                    classNames.addIfPresent(aClass.name)
                    aClass.extendsListTypes.forEach { type ->
                        val text = type.canonicalText
                        superTypes += text
                        tokens += text
                    }
                    aClass.implementsListTypes.forEach { type ->
                        val text = type.canonicalText
                        superTypes += text
                        tokens += text
                    }
                    aClass.modifierList?.annotations?.forEach { annotation ->
                        annotation.qualifiedName?.let {
                            annotations += it
                            tokens += it
                        }
                    }
                    super.visitClass(aClass)
                }

                override fun visitMethod(method: PsiMethod) {
                    functionCount += 1
                    maxParamCount = maxOf(maxParamCount, method.parameterList.parametersCount)
                    maxFunctionLoc = maxOf(maxFunctionLoc, AnalysisSupport.effectiveLoc(method.text))
                    method.modifierList.annotations.forEach { annotation ->
                        annotation.qualifiedName?.let {
                            annotations += it
                            tokens += it
                        }
                    }
                    super.visitMethod(method)
                }

                override fun visitIfStatement(statement: PsiIfStatement) {
                    branchCount += 1
                    currentDepth += 1
                    maxBlockDepth = maxOf(maxBlockDepth, currentDepth)
                    nestingPenalty = AnalysisSupport.mergePenalty(nestingPenalty, currentDepth)
                    super.visitIfStatement(statement)
                    currentDepth -= 1
                }

                override fun visitSwitchStatement(statement: PsiSwitchStatement) {
                    val labels = statement.body?.statements?.count { it is PsiSwitchLabelStatementBase } ?: 0
                    branchCount += labels.toDouble()
                    currentDepth += 1
                    maxBlockDepth = maxOf(maxBlockDepth, currentDepth)
                    nestingPenalty = AnalysisSupport.mergePenalty(nestingPenalty, currentDepth)
                    super.visitSwitchStatement(statement)
                    currentDepth -= 1
                }

                override fun visitForStatement(statement: PsiForStatement) {
                    loopCount += 1
                    currentDepth += 1
                    maxBlockDepth = maxOf(maxBlockDepth, currentDepth)
                    nestingPenalty = AnalysisSupport.mergePenalty(nestingPenalty, currentDepth)
                    super.visitForStatement(statement)
                    currentDepth -= 1
                }

                override fun visitForeachStatement(statement: PsiForeachStatement) {
                    loopCount += 1
                    currentDepth += 1
                    maxBlockDepth = maxOf(maxBlockDepth, currentDepth)
                    nestingPenalty = AnalysisSupport.mergePenalty(nestingPenalty, currentDepth)
                    super.visitForeachStatement(statement)
                    currentDepth -= 1
                }

                override fun visitWhileStatement(statement: PsiWhileStatement) {
                    loopCount += 1
                    currentDepth += 1
                    maxBlockDepth = maxOf(maxBlockDepth, currentDepth)
                    nestingPenalty = AnalysisSupport.mergePenalty(nestingPenalty, currentDepth)
                    super.visitWhileStatement(statement)
                    currentDepth -= 1
                }

                override fun visitDoWhileStatement(statement: PsiDoWhileStatement) {
                    loopCount += 1
                    currentDepth += 1
                    maxBlockDepth = maxOf(maxBlockDepth, currentDepth)
                    nestingPenalty = AnalysisSupport.mergePenalty(nestingPenalty, currentDepth)
                    super.visitDoWhileStatement(statement)
                    currentDepth -= 1
                }

                override fun visitTryStatement(statement: PsiTryStatement) {
                    tryCatchCount += statement.catchSections.size
                    if (statement.catchSections.any { (it.catchBlock?.statements?.isEmpty() ?: true) }) {
                        emptyCatchCount += statement.catchSections.count { it.catchBlock?.statements?.isEmpty() ?: true }
                    }
                    currentDepth += 1
                    maxBlockDepth = maxOf(maxBlockDepth, currentDepth)
                    nestingPenalty = AnalysisSupport.mergePenalty(nestingPenalty, currentDepth)
                    super.visitTryStatement(statement)
                    currentDepth -= 1
                }

                override fun visitConditionalExpression(expression: PsiConditionalExpression) {
                    ternaryCount += 1
                    super.visitConditionalExpression(expression)
                }

                override fun visitPolyadicExpression(expression: PsiPolyadicExpression) {
                    if (expression.operationTokenType == JavaTokenType.ANDAND || expression.operationTokenType == JavaTokenType.OROR) {
                        logicalOpCount += (expression.operands.size - 1).coerceAtLeast(1)
                    }
                    super.visitPolyadicExpression(expression)
                }

                override fun visitBinaryExpression(expression: PsiBinaryExpression) {
                    if (expression.operationTokenType == JavaTokenType.ANDAND || expression.operationTokenType == JavaTokenType.OROR) {
                        logicalOpCount += 1
                    }
                    super.visitBinaryExpression(expression)
                }
            },
        )

        val semanticSignals = AccurateSemanticSignalCollector.collect(file, mode)
        val domainAnalysis = DomainEvidenceCollector.collect(tokens + annotations + superTypes + classNames + semanticSignals)
        return FileAstSummary(
            fileName = file.name,
            effectiveLoc = AnalysisSupport.effectiveLoc(text),
            statementCount = statementCount,
            functionCount = functionCount,
            typeCount = typeCount,
            branchCount = branchCount,
            simpleWhenBranchCount = 0,
            loopCount = loopCount,
            tryCatchCount = tryCatchCount,
            ternaryCount = ternaryCount,
            logicalOpCount = logicalOpCount,
            maxBlockDepth = maxBlockDepth,
            maxLambdaDepth = 0,
            nestingPenalty = nestingPenalty,
            domainTagsHit = domainAnalysis.tags,
            evidences = domainAnalysis.evidences,
            maxFunctionLoc = maxFunctionLoc,
            maxParamCount = maxParamCount,
            todoCount = AnalysisSupport.countTodo(text),
            emptyCatchCount = emptyCatchCount,
            bangBangCount = 0,
            magicNumberCount = AnalysisSupport.countMagicNumbers(text),
            androidKind = resolveAndroidKind(superTypes),
            annotations = annotations,
            superTypes = superTypes,
            classNames = classNames,
        )
    }

    override fun hotspots(
        file: PsiFile,
        mode: AnalyzeMode,
        config: RadarConfig,
    ): List<Hotspot> {
        file as PsiJavaFile
        return PsiTreeUtil.findChildrenOfType(file, PsiMethod::class.java)
            .asSequence()
            .map { method ->
                val metrics = collectMethodMetrics(method)
                scorer.scoreHotspot(
                    methodName = method.name,
                    line = AnalysisSupport.lineNumber(file, method.nameIdentifier ?: method),
                    length = metrics.length,
                    controlFlow = metrics.controlFlow,
                    nestingPenalty = metrics.nestingPenalty,
                    snippet = AnalysisSupport.snippet(method),
                    config = config,
                )
            }.sortedByDescending { it.score }
            .take(config.hotspot.maxHotspotsPerFile)
            .toList()
    }

    private fun collectMethodMetrics(method: PsiMethod): JavaMethodMetrics {
        var branchCount = 0.0
        var loopCount = 0
        var tryCatchCount = 0
        var ternaryCount = 0
        var logicalOpCount = 0
        var currentDepth = 0
        var maxDepth = 0
        var penalty = 0

        method.accept(
            object : JavaRecursiveElementVisitor() {
                override fun visitIfStatement(statement: PsiIfStatement) {
                    branchCount += 1
                    currentDepth += 1
                    maxDepth = maxOf(maxDepth, currentDepth)
                    penalty = AnalysisSupport.mergePenalty(penalty, currentDepth)
                    super.visitIfStatement(statement)
                    currentDepth -= 1
                }

                override fun visitSwitchStatement(statement: PsiSwitchStatement) {
                    branchCount += (statement.body?.statements?.count { it is PsiSwitchLabelStatementBase } ?: 0).toDouble()
                    currentDepth += 1
                    maxDepth = maxOf(maxDepth, currentDepth)
                    penalty = AnalysisSupport.mergePenalty(penalty, currentDepth)
                    super.visitSwitchStatement(statement)
                    currentDepth -= 1
                }

                override fun visitForStatement(statement: PsiForStatement) {
                    loopCount += 1
                    currentDepth += 1
                    maxDepth = maxOf(maxDepth, currentDepth)
                    penalty = AnalysisSupport.mergePenalty(penalty, currentDepth)
                    super.visitForStatement(statement)
                    currentDepth -= 1
                }

                override fun visitForeachStatement(statement: PsiForeachStatement) {
                    loopCount += 1
                    currentDepth += 1
                    maxDepth = maxOf(maxDepth, currentDepth)
                    penalty = AnalysisSupport.mergePenalty(penalty, currentDepth)
                    super.visitForeachStatement(statement)
                    currentDepth -= 1
                }

                override fun visitWhileStatement(statement: PsiWhileStatement) {
                    loopCount += 1
                    currentDepth += 1
                    maxDepth = maxOf(maxDepth, currentDepth)
                    penalty = AnalysisSupport.mergePenalty(penalty, currentDepth)
                    super.visitWhileStatement(statement)
                    currentDepth -= 1
                }

                override fun visitDoWhileStatement(statement: PsiDoWhileStatement) {
                    loopCount += 1
                    currentDepth += 1
                    maxDepth = maxOf(maxDepth, currentDepth)
                    penalty = AnalysisSupport.mergePenalty(penalty, currentDepth)
                    super.visitDoWhileStatement(statement)
                    currentDepth -= 1
                }

                override fun visitTryStatement(statement: PsiTryStatement) {
                    tryCatchCount += statement.catchSections.size
                    currentDepth += 1
                    maxDepth = maxOf(maxDepth, currentDepth)
                    penalty = AnalysisSupport.mergePenalty(penalty, currentDepth)
                    super.visitTryStatement(statement)
                    currentDepth -= 1
                }

                override fun visitConditionalExpression(expression: PsiConditionalExpression) {
                    ternaryCount += 1
                    super.visitConditionalExpression(expression)
                }

                override fun visitPolyadicExpression(expression: PsiPolyadicExpression) {
                    if (expression.operationTokenType == JavaTokenType.ANDAND || expression.operationTokenType == JavaTokenType.OROR) {
                        logicalOpCount += (expression.operands.size - 1).coerceAtLeast(1)
                    }
                    super.visitPolyadicExpression(expression)
                }

                override fun visitBinaryExpression(expression: PsiBinaryExpression) {
                    if (expression.operationTokenType == JavaTokenType.ANDAND || expression.operationTokenType == JavaTokenType.OROR) {
                        logicalOpCount += 1
                    }
                    super.visitBinaryExpression(expression)
                }
            },
        )

        val controlFlow =
            branchCount +
                loopCount * 1.2 +
                tryCatchCount +
                ternaryCount * 0.7 +
                logicalOpCount * 0.4
        return JavaMethodMetrics(
            length = AnalysisSupport.effectiveLoc(method.text),
            controlFlow = controlFlow,
            nestingPenalty = maxOf(penalty, AnalysisSupport.nestingPenalty(maxDepth)),
        )
    }

    private fun resolveAndroidKind(superTypes: Set<String>): String? {
        val joined = superTypes.joinToString(" ")
        return when {
            joined.contains("Activity") -> "Activity"
            joined.contains("Fragment") -> "Fragment"
            joined.contains("Application") -> "Application"
            joined.contains("ContentProvider") -> "ContentProvider"
            joined.contains("ViewModel") -> "ViewModel"
            else -> null
        }
    }
}

private data class JavaMethodMetrics(
    val length: Int,
    val controlFlow: Double,
    val nestingPenalty: Int,
)
