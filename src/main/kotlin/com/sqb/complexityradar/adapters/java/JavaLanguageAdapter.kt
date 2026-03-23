package com.bril.code_radar.adapters.java

import com.intellij.psi.JavaRecursiveElementVisitor
import com.intellij.psi.JavaTokenType
import com.intellij.psi.PsiBreakStatement
import com.intellij.psi.PsiCatchSection
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiConditionalExpression
import com.intellij.psi.PsiContinueStatement
import com.intellij.psi.PsiDoWhileStatement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiForStatement
import com.intellij.psi.PsiForeachStatement
import com.intellij.psi.PsiIfStatement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiPolyadicExpression
import com.intellij.psi.PsiSwitchExpression
import com.intellij.psi.PsiSwitchLabelStatementBase
import com.intellij.psi.PsiSwitchStatement
import com.intellij.psi.PsiTryStatement
import com.intellij.psi.PsiWhileStatement
import com.intellij.psi.util.PsiTreeUtil
import com.bril.code_radar.adapters.FileAnalysis
import com.bril.code_radar.adapters.LanguageAdapter
import com.bril.code_radar.adapters.common.AnalysisSupport
import com.bril.code_radar.adapters.common.AccurateSemanticSignalCollector
import com.bril.code_radar.adapters.common.DomainEvidenceCollector
import com.bril.code_radar.adapters.common.addIfPresent
import com.bril.code_radar.core.model.AnalyzeMode
import com.bril.code_radar.core.model.FileAstSummary
import com.bril.code_radar.core.model.Hotspot
import com.bril.code_radar.core.model.RadarConfig
import com.bril.code_radar.core.scoring.ComplexityScorer

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
        var ccScore = 0
        var ccNestingDepth = 0
        // Note: recursion detection and labeled break/continue are not tracked at the file level
        // because a flat visitor cannot easily correlate call sites with their enclosing method name.
        // These are tracked per-method in collectMethodMetrics() instead.

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
                    val isElseContinuation = statement.parent is PsiIfStatement &&
                        (statement.parent as PsiIfStatement).elseBranch === statement
                    if (isElseContinuation) {
                        ccScore += 1
                    } else {
                        ccScore += 1 + ccNestingDepth
                    }
                    val elseBranch = statement.elseBranch
                    if (elseBranch != null && elseBranch !is PsiIfStatement) {
                        ccScore += 1
                    }
                    branchCount += 1
                    currentDepth += 1
                    maxBlockDepth = maxOf(maxBlockDepth, currentDepth)
                    nestingPenalty = AnalysisSupport.mergePenalty(nestingPenalty, currentDepth)
                    if (!isElseContinuation) ccNestingDepth += 1
                    super.visitIfStatement(statement)
                    currentDepth -= 1
                    if (!isElseContinuation) ccNestingDepth -= 1
                }

                override fun visitSwitchStatement(statement: PsiSwitchStatement) {
                    ccScore += 1 + ccNestingDepth
                    val labels = statement.body?.statements?.count { it is PsiSwitchLabelStatementBase } ?: 0
                    branchCount += labels.toDouble()
                    currentDepth += 1
                    maxBlockDepth = maxOf(maxBlockDepth, currentDepth)
                    nestingPenalty = AnalysisSupport.mergePenalty(nestingPenalty, currentDepth)
                    ccNestingDepth += 1
                    super.visitSwitchStatement(statement)
                    currentDepth -= 1
                    ccNestingDepth -= 1
                }

                override fun visitSwitchExpression(expression: PsiSwitchExpression) {
                    ccScore += 1 + ccNestingDepth
                    val labels = expression.body?.statements?.count { it is PsiSwitchLabelStatementBase } ?: 0
                    branchCount += labels.toDouble()
                    currentDepth += 1
                    maxBlockDepth = maxOf(maxBlockDepth, currentDepth)
                    nestingPenalty = AnalysisSupport.mergePenalty(nestingPenalty, currentDepth)
                    ccNestingDepth += 1
                    super.visitSwitchExpression(expression)
                    currentDepth -= 1
                    ccNestingDepth -= 1
                }

                override fun visitForStatement(statement: PsiForStatement) {
                    ccScore += 1 + ccNestingDepth
                    loopCount += 1
                    currentDepth += 1
                    maxBlockDepth = maxOf(maxBlockDepth, currentDepth)
                    nestingPenalty = AnalysisSupport.mergePenalty(nestingPenalty, currentDepth)
                    ccNestingDepth += 1
                    super.visitForStatement(statement)
                    currentDepth -= 1
                    ccNestingDepth -= 1
                }

                override fun visitForeachStatement(statement: PsiForeachStatement) {
                    ccScore += 1 + ccNestingDepth
                    loopCount += 1
                    currentDepth += 1
                    maxBlockDepth = maxOf(maxBlockDepth, currentDepth)
                    nestingPenalty = AnalysisSupport.mergePenalty(nestingPenalty, currentDepth)
                    ccNestingDepth += 1
                    super.visitForeachStatement(statement)
                    currentDepth -= 1
                    ccNestingDepth -= 1
                }

                override fun visitWhileStatement(statement: PsiWhileStatement) {
                    ccScore += 1 + ccNestingDepth
                    loopCount += 1
                    currentDepth += 1
                    maxBlockDepth = maxOf(maxBlockDepth, currentDepth)
                    nestingPenalty = AnalysisSupport.mergePenalty(nestingPenalty, currentDepth)
                    ccNestingDepth += 1
                    super.visitWhileStatement(statement)
                    currentDepth -= 1
                    ccNestingDepth -= 1
                }

                override fun visitDoWhileStatement(statement: PsiDoWhileStatement) {
                    ccScore += 1 + ccNestingDepth
                    loopCount += 1
                    currentDepth += 1
                    maxBlockDepth = maxOf(maxBlockDepth, currentDepth)
                    nestingPenalty = AnalysisSupport.mergePenalty(nestingPenalty, currentDepth)
                    ccNestingDepth += 1
                    super.visitDoWhileStatement(statement)
                    currentDepth -= 1
                    ccNestingDepth -= 1
                }

                override fun visitTryStatement(statement: PsiTryStatement) {
                    tryCatchCount += statement.catchSections.size
                    if (statement.catchSections.any { (it.catchBlock?.statements?.isEmpty() ?: true) }) {
                        emptyCatchCount += statement.catchSections.count { it.catchBlock?.statements?.isEmpty() ?: true }
                    }
                    currentDepth += 1
                    maxBlockDepth = maxOf(maxBlockDepth, currentDepth)
                    nestingPenalty = AnalysisSupport.mergePenalty(nestingPenalty, currentDepth)
                    ccNestingDepth += 1
                    super.visitTryStatement(statement)
                    currentDepth -= 1
                    ccNestingDepth -= 1
                }

                override fun visitCatchSection(section: PsiCatchSection) {
                    ccScore += 1 + (ccNestingDepth - 1).coerceAtLeast(0)
                    super.visitCatchSection(section)
                }

                override fun visitConditionalExpression(expression: PsiConditionalExpression) {
                    ternaryCount += 1
                    super.visitConditionalExpression(expression)
                }

                override fun visitPolyadicExpression(expression: PsiPolyadicExpression) {
                    val op = expression.operationTokenType
                    if (op == JavaTokenType.ANDAND || op == JavaTokenType.OROR) {
                        logicalOpCount += (expression.operands.size - 1).coerceAtLeast(1)
                        ccScore += 1
                    }
                    super.visitPolyadicExpression(expression)
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
            androidKind = AnalysisSupport.resolveAndroidKind(superTypes),
            annotations = annotations,
            superTypes = superTypes,
            classNames = classNames,
            cognitiveComplexity = ccScore,
        )
    }

    override fun analyze(
        file: PsiFile,
        mode: AnalyzeMode,
        config: RadarConfig,
    ): FileAnalysis {
        val summary = summarize(file, mode, config)
        // Collect methods via a single lightweight traversal instead of PsiTreeUtil.findChildrenOfType
        val methods = mutableListOf<PsiMethod>()
        (file as PsiJavaFile).accept(object : JavaRecursiveElementVisitor() {
            override fun visitMethod(method: PsiMethod) {
                methods.add(method)
                super.visitMethod(method)
            }
        })
        val hotspots = methods
            .map { method ->
                val metrics = collectMethodMetrics(method)
                scorer.scoreHotspot(
                    methodName          = method.name,
                    line                = AnalysisSupport.lineNumber(file, method.nameIdentifier ?: method),
                    length              = metrics.length,
                    cognitiveComplexity = metrics.cognitiveComplexity,
                    nestingPenalty      = metrics.nestingPenalty,
                    snippet             = AnalysisSupport.snippet(method),
                    config              = config,
                )
            }.sortedByDescending { it.score }
            .take(config.hotspot.maxHotspotsPerFile)
        return FileAnalysis(summary, hotspots)
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
                    methodName          = method.name,
                    line                = AnalysisSupport.lineNumber(file, method.nameIdentifier ?: method),
                    length              = metrics.length,
                    cognitiveComplexity = metrics.cognitiveComplexity,
                    nestingPenalty      = metrics.nestingPenalty,
                    snippet             = AnalysisSupport.snippet(method),
                    config              = config,
                )
            }.sortedByDescending { it.score }
            .take(config.hotspot.maxHotspotsPerFile)
            .toList()
    }

    private fun collectMethodMetrics(method: PsiMethod): JavaMethodMetrics {
        var ccScore = 0
        var ccDepth = 0
        var currentDepth = 0
        var maxDepth = 0
        var penalty = 0
        val methodName = method.name

        method.accept(
            object : JavaRecursiveElementVisitor() {
                override fun visitIfStatement(statement: PsiIfStatement) {
                    val isElseContinuation = statement.parent is PsiIfStatement &&
                        (statement.parent as PsiIfStatement).elseBranch === statement
                    if (isElseContinuation) {
                        ccScore += 1
                    } else {
                        ccScore += 1 + ccDepth
                    }
                    val elseBranch = statement.elseBranch
                    if (elseBranch != null && elseBranch !is PsiIfStatement) {
                        ccScore += 1
                    }
                    if (!isElseContinuation) {
                        ccDepth += 1
                        currentDepth += 1
                        maxDepth = maxOf(maxDepth, currentDepth)
                        penalty = AnalysisSupport.mergePenalty(penalty, currentDepth)
                    }
                    super.visitIfStatement(statement)
                    if (!isElseContinuation) {
                        ccDepth -= 1
                        currentDepth -= 1
                    }
                }

                override fun visitSwitchStatement(statement: PsiSwitchStatement) {
                    ccScore += 1 + ccDepth
                    ccDepth += 1
                    currentDepth += 1
                    maxDepth = maxOf(maxDepth, currentDepth)
                    penalty = AnalysisSupport.mergePenalty(penalty, currentDepth)
                    super.visitSwitchStatement(statement)
                    ccDepth -= 1
                    currentDepth -= 1
                }

                override fun visitSwitchExpression(expression: PsiSwitchExpression) {
                    // Java 14+ switch expression
                    ccScore += 1 + ccDepth
                    ccDepth += 1
                    currentDepth += 1
                    maxDepth = maxOf(maxDepth, currentDepth)
                    penalty = AnalysisSupport.mergePenalty(penalty, currentDepth)
                    super.visitSwitchExpression(expression)
                    ccDepth -= 1
                    currentDepth -= 1
                }

                override fun visitForStatement(statement: PsiForStatement) {
                    ccScore += 1 + ccDepth
                    ccDepth += 1; currentDepth += 1
                    maxDepth = maxOf(maxDepth, currentDepth)
                    penalty = AnalysisSupport.mergePenalty(penalty, currentDepth)
                    super.visitForStatement(statement)
                    ccDepth -= 1; currentDepth -= 1
                }

                override fun visitForeachStatement(statement: PsiForeachStatement) {
                    ccScore += 1 + ccDepth
                    ccDepth += 1; currentDepth += 1
                    maxDepth = maxOf(maxDepth, currentDepth)
                    penalty = AnalysisSupport.mergePenalty(penalty, currentDepth)
                    super.visitForeachStatement(statement)
                    ccDepth -= 1; currentDepth -= 1
                }

                override fun visitWhileStatement(statement: PsiWhileStatement) {
                    ccScore += 1 + ccDepth
                    ccDepth += 1; currentDepth += 1
                    maxDepth = maxOf(maxDepth, currentDepth)
                    penalty = AnalysisSupport.mergePenalty(penalty, currentDepth)
                    super.visitWhileStatement(statement)
                    ccDepth -= 1; currentDepth -= 1
                }

                override fun visitDoWhileStatement(statement: PsiDoWhileStatement) {
                    ccScore += 1 + ccDepth
                    ccDepth += 1; currentDepth += 1
                    maxDepth = maxOf(maxDepth, currentDepth)
                    penalty = AnalysisSupport.mergePenalty(penalty, currentDepth)
                    super.visitDoWhileStatement(statement)
                    ccDepth -= 1; currentDepth -= 1
                }

                override fun visitTryStatement(statement: PsiTryStatement) {
                    // try itself adds no CC, only increases depth for catch
                    ccDepth += 1; currentDepth += 1
                    maxDepth = maxOf(maxDepth, currentDepth)
                    penalty = AnalysisSupport.mergePenalty(penalty, currentDepth)
                    super.visitTryStatement(statement)
                    ccDepth -= 1; currentDepth -= 1
                }

                override fun visitCatchSection(section: PsiCatchSection) {
                    // use outer depth (ccDepth - 1) to avoid double-counting try's depth increment
                    ccScore += 1 + (ccDepth - 1).coerceAtLeast(0)
                    super.visitCatchSection(section)
                }

                override fun visitPolyadicExpression(expression: PsiPolyadicExpression) {
                    val op = expression.operationTokenType
                    if (op == JavaTokenType.ANDAND || op == JavaTokenType.OROR) {
                        // PsiPolyadicExpression already groups same-operator sequences
                        ccScore += 1
                    }
                    super.visitPolyadicExpression(expression)
                }

                override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                    if (expression.methodExpression.referenceName == methodName) {
                        ccScore += 1  // recursion
                    }
                    super.visitMethodCallExpression(expression)
                }

                override fun visitBreakStatement(statement: PsiBreakStatement) {
                    if (statement.labelIdentifier != null) ccScore += 1  // break with label
                    super.visitBreakStatement(statement)
                }

                override fun visitContinueStatement(statement: PsiContinueStatement) {
                    if (statement.labelIdentifier != null) ccScore += 1  // continue with label
                    super.visitContinueStatement(statement)
                }
            },
        )

        return JavaMethodMetrics(
            length = AnalysisSupport.effectiveLoc(method.text),
            cognitiveComplexity = ccScore,
            nestingPenalty = maxOf(penalty, AnalysisSupport.nestingPenalty(maxDepth)),
        )
    }

}

private data class JavaMethodMetrics(
    val length: Int,
    val cognitiveComplexity: Int,
    val nestingPenalty: Int,
)
