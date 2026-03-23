package com.bril.code_radar.adapters.kotlin

import com.intellij.psi.PsiFile
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
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDoWhileExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.KtTryExpression
import org.jetbrains.kotlin.psi.KtWhenExpression
import org.jetbrains.kotlin.psi.KtWhenEntry
import org.jetbrains.kotlin.psi.KtWhileExpression

class KotlinLanguageAdapter(
    private val scorer: ComplexityScorer = ComplexityScorer(),
) : LanguageAdapter {
    override fun supports(file: PsiFile): Boolean = file is KtFile

    override fun summarize(
        file: PsiFile,
        mode: AnalyzeMode,
        config: RadarConfig,
    ): FileAstSummary {
        file as KtFile
        val text = file.text
        var statementCount = file.declarations.size
        var functionCount = 0
        var typeCount = 0
        var branchCount = 0.0
        var simpleWhenBranchCount = 0
        var loopCount = 0
        var tryCatchCount = 0
        var ternaryCount = 0
        var logicalOpCount = 0
        var maxBlockDepth = 0
        var maxLambdaDepth = 0
        var nestingPenalty = 0
        var currentBlockDepth = 0
        var currentLambdaDepth = 0
        var ccScore = 0
        var ccNestingDepth = 0
        // Note: recursion detection and labeled break/continue are not tracked at the file level
        // because a flat visitor cannot easily correlate call sites with their enclosing method name.
        // These are tracked per-method in collectMethodMetrics() instead.
        var maxFunctionLoc = 0
        var maxParamCount = 0
        var emptyCatchCount = 0

        val tokens = mutableListOf<String>()
        val annotations = linkedSetOf<String>()
        val superTypes = linkedSetOf<String>()
        val classNames = linkedSetOf<String>()

        file.importDirectives.forEach { directive ->
            tokens.addIfPresent(directive.importedFqName?.asString())
        }

        file.accept(
            object : KtTreeVisitorVoid() {
                override fun visitBlockExpression(expression: KtBlockExpression) {
                    statementCount += expression.statements.size
                    super.visitBlockExpression(expression)
                }

                override fun visitClassOrObject(classOrObject: KtClassOrObject) {
                    typeCount += 1
                    classNames.addIfPresent(classOrObject.name)
                    classOrObject.superTypeListEntries.forEach { entry ->
                        val text = entry.typeReference?.text ?: entry.text
                        tokens += text
                        superTypes += text
                    }
                    classOrObject.annotationEntries.forEach { entry ->
                        entry.shortName?.asString()?.let {
                            annotations += it
                            tokens += it
                        }
                    }
                    super.visitClassOrObject(classOrObject)
                }

                override fun visitNamedFunction(function: KtNamedFunction) {
                    functionCount += 1
                    maxParamCount = maxOf(maxParamCount, function.valueParameters.size)
                    val loc = AnalysisSupport.effectiveLoc(function.text)
                    maxFunctionLoc = maxOf(maxFunctionLoc, loc)
                    function.annotationEntries.forEach { entry ->
                        entry.shortName?.asString()?.let {
                            annotations += it
                            tokens += it
                        }
                    }
                    super.visitNamedFunction(function)
                }

                override fun visitIfExpression(expression: KtIfExpression) {
                    branchCount += 1
                    val isElse = isElseBranch(expression)
                    if (isElse) {
                        ccScore += 1
                    } else {
                        ccScore += 1 + ccNestingDepth
                    }
                    val elseClause = expression.`else`
                    if (elseClause != null && elseClause !is KtIfExpression) {
                        ccScore += 1
                    }
                    currentBlockDepth += 1
                    maxBlockDepth = maxOf(maxBlockDepth, currentBlockDepth)
                    nestingPenalty = AnalysisSupport.mergePenalty(nestingPenalty, currentBlockDepth)
                    if (!isElse) ccNestingDepth += 1
                    super.visitIfExpression(expression)
                    currentBlockDepth -= 1
                    if (!isElse) ccNestingDepth -= 1
                }

                override fun visitWhenExpression(expression: KtWhenExpression) {
                    val entries = expression.entries.filterNot(KtWhenEntry::isElse)
                    val simpleEntries = entries.count(::isSimpleWhenEntry)
                    simpleWhenBranchCount += simpleEntries
                    branchCount += (entries.size - simpleEntries).toDouble()
                    val nonElseBranches = expression.entries.count { !it.isElse }
                    ccScore += nonElseBranches * (1 + ccNestingDepth)
                    if (expression.entries.any { it.isElse }) ccScore += 1
                    currentBlockDepth += 1
                    maxBlockDepth = maxOf(maxBlockDepth, currentBlockDepth)
                    nestingPenalty = AnalysisSupport.mergePenalty(nestingPenalty, currentBlockDepth)
                    ccNestingDepth += 1
                    super.visitWhenExpression(expression)
                    currentBlockDepth -= 1
                    ccNestingDepth -= 1
                }

                override fun visitForExpression(expression: KtForExpression) {
                    loopCount += 1
                    ccScore += 1 + ccNestingDepth
                    currentBlockDepth += 1
                    maxBlockDepth = maxOf(maxBlockDepth, currentBlockDepth)
                    nestingPenalty = AnalysisSupport.mergePenalty(nestingPenalty, currentBlockDepth)
                    ccNestingDepth += 1
                    super.visitForExpression(expression)
                    currentBlockDepth -= 1
                    ccNestingDepth -= 1
                }

                override fun visitWhileExpression(expression: KtWhileExpression) {
                    loopCount += 1
                    ccScore += 1 + ccNestingDepth
                    currentBlockDepth += 1
                    maxBlockDepth = maxOf(maxBlockDepth, currentBlockDepth)
                    nestingPenalty = AnalysisSupport.mergePenalty(nestingPenalty, currentBlockDepth)
                    ccNestingDepth += 1
                    super.visitWhileExpression(expression)
                    currentBlockDepth -= 1
                    ccNestingDepth -= 1
                }

                override fun visitDoWhileExpression(expression: KtDoWhileExpression) {
                    loopCount += 1
                    ccScore += 1 + ccNestingDepth
                    currentBlockDepth += 1
                    maxBlockDepth = maxOf(maxBlockDepth, currentBlockDepth)
                    nestingPenalty = AnalysisSupport.mergePenalty(nestingPenalty, currentBlockDepth)
                    ccNestingDepth += 1
                    super.visitDoWhileExpression(expression)
                    currentBlockDepth -= 1
                    ccNestingDepth -= 1
                }

                override fun visitTryExpression(expression: KtTryExpression) {
                    tryCatchCount += expression.catchClauses.size
                    currentBlockDepth += 1
                    maxBlockDepth = maxOf(maxBlockDepth, currentBlockDepth)
                    nestingPenalty = AnalysisSupport.mergePenalty(nestingPenalty, currentBlockDepth)
                    ccNestingDepth += 1
                    super.visitTryExpression(expression)
                    currentBlockDepth -= 1
                    ccNestingDepth -= 1
                }

                override fun visitCatchSection(catchClause: KtCatchClause) {
                    val body = catchClause.catchBody
                    if (body is KtBlockExpression && body.statements.isEmpty()) {
                        emptyCatchCount += 1
                    }
                    ccScore += 1 + (ccNestingDepth - 1).coerceAtLeast(0)
                    super.visitCatchSection(catchClause)
                }

                override fun visitLambdaExpression(lambdaExpression: KtLambdaExpression) {
                    currentLambdaDepth += 1
                    maxLambdaDepth = maxOf(maxLambdaDepth, currentLambdaDepth)
                    nestingPenalty = AnalysisSupport.mergePenalty(nestingPenalty, currentLambdaDepth)
                    ccNestingDepth += 1
                    super.visitLambdaExpression(lambdaExpression)
                    currentLambdaDepth -= 1
                    ccNestingDepth -= 1
                }

                override fun visitBinaryExpression(expression: KtBinaryExpression) {
                    when (expression.operationToken) {
                        KtTokens.ANDAND, KtTokens.OROR -> logicalOpCount += 1
                        KtTokens.ELVIS -> ternaryCount += 1
                    }
                    val op = expression.operationToken
                    if (op == KtTokens.ANDAND || op == KtTokens.OROR) {
                        val parentOp = (expression.parent as? KtBinaryExpression)?.operationToken
                        if (parentOp != op) {
                            ccScore += 1
                        }
                    }
                    super.visitBinaryExpression(expression)
                }

                override fun visitCallExpression(expression: KtCallExpression) {
                    tokens.addIfPresent(expression.calleeExpression?.text)
                    super.visitCallExpression(expression)
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
            simpleWhenBranchCount = simpleWhenBranchCount,
            loopCount = loopCount,
            tryCatchCount = tryCatchCount,
            ternaryCount = ternaryCount,
            logicalOpCount = logicalOpCount,
            maxBlockDepth = maxBlockDepth,
            maxLambdaDepth = maxLambdaDepth,
            nestingPenalty = nestingPenalty,
            domainTagsHit = domainAnalysis.tags,
            evidences = domainAnalysis.evidences,
            maxFunctionLoc = maxFunctionLoc,
            maxParamCount = maxParamCount,
            todoCount = AnalysisSupport.countTodo(text),
            emptyCatchCount = emptyCatchCount,
            bangBangCount = AnalysisSupport.countBangBang(text),
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
        // Collect functions via a single lightweight traversal instead of PsiTreeUtil.findChildrenOfType
        val functions = mutableListOf<KtNamedFunction>()
        (file as KtFile).accept(object : KtTreeVisitorVoid() {
            override fun visitNamedFunction(function: KtNamedFunction) {
                if (function.nameIdentifier != null) functions.add(function)
                super.visitNamedFunction(function)
            }
        })
        val hotspots = functions
            .map { function ->
                val metrics = collectMethodMetrics(function)
                scorer.scoreHotspot(
                    methodName          = function.name ?: "<anonymous>",
                    line                = AnalysisSupport.lineNumber(file, function.nameIdentifier ?: function),
                    length              = metrics.length,
                    cognitiveComplexity = metrics.cognitiveComplexity,
                    nestingPenalty      = metrics.nestingPenalty,
                    snippet             = AnalysisSupport.snippet(function),
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
        file as KtFile
        return PsiTreeUtil.findChildrenOfType(file, KtNamedFunction::class.java)
            .asSequence()
            .filter { it.nameIdentifier != null }
            .map { function ->
                val metrics = collectMethodMetrics(function)
                scorer.scoreHotspot(
                    methodName          = function.name ?: "<anonymous>",
                    line                = AnalysisSupport.lineNumber(file, function.nameIdentifier ?: function),
                    length              = metrics.length,
                    cognitiveComplexity = metrics.cognitiveComplexity,
                    nestingPenalty      = metrics.nestingPenalty,
                    snippet             = AnalysisSupport.snippet(function),
                    config              = config,
                )
            }.sortedByDescending { it.score }
            .take(config.hotspot.maxHotspotsPerFile)
            .toList()
    }

    private fun collectMethodMetrics(function: KtNamedFunction): KotlinMethodMetrics {
        var ccScore = 0
        var ccDepth = 0
        var currentDepth = 0
        var maxDepth = 0
        var penalty = 0
        val methodName = function.name

        function.accept(
            object : KtTreeVisitorVoid() {
                override fun visitNamedFunction(innerFunction: KtNamedFunction) {
                    if (innerFunction !== function) return  // skip nested named functions
                    super.visitNamedFunction(innerFunction)
                }

                override fun visitIfExpression(expression: KtIfExpression) {
                    val isElseContinuation = isElseBranch(expression)
                    if (isElseContinuation) {
                        ccScore += 1                    // else if: flat +1
                    } else {
                        ccScore += 1 + ccDepth          // if: +1 + current depth
                    }
                    // plain else (not an else-if)
                    val elseClause = expression.`else`
                    if (elseClause != null && elseClause !is KtIfExpression) {
                        ccScore += 1
                    }
                    // only non-else-if increases nesting depth
                    if (!isElseContinuation) {
                        ccDepth += 1
                        currentDepth += 1
                        maxDepth = maxOf(maxDepth, currentDepth)
                        penalty = AnalysisSupport.mergePenalty(penalty, currentDepth)
                    }
                    super.visitIfExpression(expression)
                    if (!isElseContinuation) {
                        ccDepth -= 1
                        currentDepth -= 1
                    }
                }

                override fun visitWhenExpression(expression: KtWhenExpression) {
                    val nonElseBranches = expression.entries.count { !it.isElse }
                    ccScore += nonElseBranches * (1 + ccDepth)
                    if (expression.entries.any { it.isElse }) ccScore += 1
                    ccDepth += 1
                    currentDepth += 1
                    maxDepth = maxOf(maxDepth, currentDepth)
                    penalty = AnalysisSupport.mergePenalty(penalty, currentDepth)
                    super.visitWhenExpression(expression)
                    ccDepth -= 1
                    currentDepth -= 1
                }

                override fun visitForExpression(expression: KtForExpression) {
                    ccScore += 1 + ccDepth
                    ccDepth += 1
                    currentDepth += 1
                    maxDepth = maxOf(maxDepth, currentDepth)
                    penalty = AnalysisSupport.mergePenalty(penalty, currentDepth)
                    super.visitForExpression(expression)
                    ccDepth -= 1
                    currentDepth -= 1
                }

                override fun visitWhileExpression(expression: KtWhileExpression) {
                    ccScore += 1 + ccDepth
                    ccDepth += 1
                    currentDepth += 1
                    maxDepth = maxOf(maxDepth, currentDepth)
                    penalty = AnalysisSupport.mergePenalty(penalty, currentDepth)
                    super.visitWhileExpression(expression)
                    ccDepth -= 1
                    currentDepth -= 1
                }

                override fun visitDoWhileExpression(expression: KtDoWhileExpression) {
                    ccScore += 1 + ccDepth
                    ccDepth += 1
                    currentDepth += 1
                    maxDepth = maxOf(maxDepth, currentDepth)
                    penalty = AnalysisSupport.mergePenalty(penalty, currentDepth)
                    super.visitDoWhileExpression(expression)
                    ccDepth -= 1
                    currentDepth -= 1
                }

                override fun visitTryExpression(expression: KtTryExpression) {
                    // try itself adds no CC, but increases depth for catch
                    ccDepth += 1
                    currentDepth += 1
                    maxDepth = maxOf(maxDepth, currentDepth)
                    penalty = AnalysisSupport.mergePenalty(penalty, currentDepth)
                    super.visitTryExpression(expression)
                    ccDepth -= 1
                    currentDepth -= 1
                }

                override fun visitCatchSection(catchClause: KtCatchClause) {
                    // use outer depth (ccDepth - 1) to avoid double-counting try's depth increment
                    ccScore += 1 + (ccDepth - 1).coerceAtLeast(0)
                    super.visitCatchSection(catchClause)
                }

                override fun visitLambdaExpression(lambdaExpression: KtLambdaExpression) {
                    ccDepth += 1
                    currentDepth += 1
                    maxDepth = maxOf(maxDepth, currentDepth)
                    penalty = AnalysisSupport.mergePenalty(penalty, currentDepth)
                    super.visitLambdaExpression(lambdaExpression)
                    ccDepth -= 1
                    currentDepth -= 1
                }

                override fun visitBinaryExpression(expression: KtBinaryExpression) {
                    val op = expression.operationToken
                    if (op == KtTokens.ANDAND || op == KtTokens.OROR) {
                        val parentOp = (expression.parent as? KtBinaryExpression)?.operationToken
                        if (parentOp != op) {
                            ccScore += 1
                        }
                    }
                    super.visitBinaryExpression(expression)
                }

                override fun visitCallExpression(expression: KtCallExpression) {
                    val callee = expression.calleeExpression?.text?.substringAfterLast(".")
                    if (methodName != null && callee == methodName) {
                        ccScore += 1  // recursion
                    }
                    super.visitCallExpression(expression)
                }

                override fun visitBreakExpression(expression: org.jetbrains.kotlin.psi.KtBreakExpression) {
                    if (expression.getLabelName() != null) ccScore += 1  // break with label
                    super.visitBreakExpression(expression)
                }

                override fun visitContinueExpression(expression: org.jetbrains.kotlin.psi.KtContinueExpression) {
                    if (expression.getLabelName() != null) ccScore += 1  // continue with label
                    super.visitContinueExpression(expression)
                }
            },
        )

        return KotlinMethodMetrics(
            length = AnalysisSupport.effectiveLoc(function.text),
            cognitiveComplexity = ccScore,
            nestingPenalty = maxOf(penalty, AnalysisSupport.nestingPenalty(maxDepth)),
        )
    }

    private fun isElseBranch(expression: KtIfExpression): Boolean {
        val parent = expression.parent
        return parent is KtIfExpression && parent.`else` === expression
    }

    private fun isSimpleWhenEntry(entry: KtWhenEntry): Boolean {
        val text = entry.expression?.text?.trim() ?: return false
        return '\n' !in text && text.length <= 80
    }

}

private data class KotlinMethodMetrics(
    val length: Int,
    val cognitiveComplexity: Int,
    val nestingPenalty: Int,
)
