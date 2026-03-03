package com.sqb.complexityradar.adapters.kotlin

import com.intellij.psi.PsiFile
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
                    currentBlockDepth += 1
                    maxBlockDepth = maxOf(maxBlockDepth, currentBlockDepth)
                    nestingPenalty = AnalysisSupport.mergePenalty(nestingPenalty, currentBlockDepth)
                    super.visitIfExpression(expression)
                    currentBlockDepth -= 1
                }

                override fun visitWhenExpression(expression: KtWhenExpression) {
                    val entries = expression.entries.filterNot(KtWhenEntry::isElse)
                    val simpleEntries = entries.count(::isSimpleWhenEntry)
                    simpleWhenBranchCount += simpleEntries
                    branchCount += (entries.size - simpleEntries).toDouble()
                    currentBlockDepth += 1
                    maxBlockDepth = maxOf(maxBlockDepth, currentBlockDepth)
                    nestingPenalty = AnalysisSupport.mergePenalty(nestingPenalty, currentBlockDepth)
                    super.visitWhenExpression(expression)
                    currentBlockDepth -= 1
                }

                override fun visitForExpression(expression: KtForExpression) {
                    loopCount += 1
                    currentBlockDepth += 1
                    maxBlockDepth = maxOf(maxBlockDepth, currentBlockDepth)
                    nestingPenalty = AnalysisSupport.mergePenalty(nestingPenalty, currentBlockDepth)
                    super.visitForExpression(expression)
                    currentBlockDepth -= 1
                }

                override fun visitWhileExpression(expression: KtWhileExpression) {
                    loopCount += 1
                    currentBlockDepth += 1
                    maxBlockDepth = maxOf(maxBlockDepth, currentBlockDepth)
                    nestingPenalty = AnalysisSupport.mergePenalty(nestingPenalty, currentBlockDepth)
                    super.visitWhileExpression(expression)
                    currentBlockDepth -= 1
                }

                override fun visitDoWhileExpression(expression: KtDoWhileExpression) {
                    loopCount += 1
                    currentBlockDepth += 1
                    maxBlockDepth = maxOf(maxBlockDepth, currentBlockDepth)
                    nestingPenalty = AnalysisSupport.mergePenalty(nestingPenalty, currentBlockDepth)
                    super.visitDoWhileExpression(expression)
                    currentBlockDepth -= 1
                }

                override fun visitTryExpression(expression: KtTryExpression) {
                    tryCatchCount += expression.catchClauses.size
                    currentBlockDepth += 1
                    maxBlockDepth = maxOf(maxBlockDepth, currentBlockDepth)
                    nestingPenalty = AnalysisSupport.mergePenalty(nestingPenalty, currentBlockDepth)
                    super.visitTryExpression(expression)
                    currentBlockDepth -= 1
                }

                override fun visitCatchSection(catchClause: KtCatchClause) {
                    val body = catchClause.catchBody
                    if (body is KtBlockExpression && body.statements.isEmpty()) {
                        emptyCatchCount += 1
                    }
                    super.visitCatchSection(catchClause)
                }

                override fun visitLambdaExpression(lambdaExpression: KtLambdaExpression) {
                    currentLambdaDepth += 1
                    maxLambdaDepth = maxOf(maxLambdaDepth, currentLambdaDepth)
                    nestingPenalty = AnalysisSupport.mergePenalty(nestingPenalty, currentLambdaDepth)
                    super.visitLambdaExpression(lambdaExpression)
                    currentLambdaDepth -= 1
                }

                override fun visitBinaryExpression(expression: KtBinaryExpression) {
                    when (expression.operationToken) {
                        KtTokens.ANDAND, KtTokens.OROR -> logicalOpCount += 1
                        KtTokens.ELVIS -> ternaryCount += 1
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
        file as KtFile
        return PsiTreeUtil.findChildrenOfType(file, KtNamedFunction::class.java)
            .asSequence()
            .filter { it.nameIdentifier != null }
            .map { function ->
                val metrics = collectMethodMetrics(function)
                scorer.scoreHotspot(
                    methodName = function.name ?: "<anonymous>",
                    line = AnalysisSupport.lineNumber(file, function.nameIdentifier ?: function),
                    length = metrics.length,
                    controlFlow = metrics.controlFlow,
                    nestingPenalty = metrics.nestingPenalty,
                    snippet = AnalysisSupport.snippet(function),
                    config = config,
                )
            }.sortedByDescending { it.score }
            .take(config.hotspot.maxHotspotsPerFile)
            .toList()
    }

    private fun collectMethodMetrics(function: KtNamedFunction): KotlinMethodMetrics {
        var branchCount = 0.0
        var simpleWhenBranchCount = 0
        var loopCount = 0
        var tryCatchCount = 0
        var ternaryCount = 0
        var logicalOpCount = 0
        var currentDepth = 0
        var maxDepth = 0
        var penalty = 0

        function.accept(
            object : KtTreeVisitorVoid() {
                override fun visitNamedFunction(innerFunction: KtNamedFunction) {
                    if (innerFunction !== function) {
                        return
                    }
                    super.visitNamedFunction(innerFunction)
                }

                override fun visitIfExpression(expression: KtIfExpression) {
                    branchCount += 1
                    currentDepth += 1
                    maxDepth = maxOf(maxDepth, currentDepth)
                    penalty = AnalysisSupport.mergePenalty(penalty, currentDepth)
                    super.visitIfExpression(expression)
                    currentDepth -= 1
                }

                override fun visitWhenExpression(expression: KtWhenExpression) {
                    val entries = expression.entries.filterNot(KtWhenEntry::isElse)
                    val simpleEntries = entries.count(::isSimpleWhenEntry)
                    simpleWhenBranchCount += simpleEntries
                    branchCount += (entries.size - simpleEntries).toDouble()
                    currentDepth += 1
                    maxDepth = maxOf(maxDepth, currentDepth)
                    penalty = AnalysisSupport.mergePenalty(penalty, currentDepth)
                    super.visitWhenExpression(expression)
                    currentDepth -= 1
                }

                override fun visitForExpression(expression: KtForExpression) {
                    loopCount += 1
                    currentDepth += 1
                    maxDepth = maxOf(maxDepth, currentDepth)
                    penalty = AnalysisSupport.mergePenalty(penalty, currentDepth)
                    super.visitForExpression(expression)
                    currentDepth -= 1
                }

                override fun visitWhileExpression(expression: KtWhileExpression) {
                    loopCount += 1
                    currentDepth += 1
                    maxDepth = maxOf(maxDepth, currentDepth)
                    penalty = AnalysisSupport.mergePenalty(penalty, currentDepth)
                    super.visitWhileExpression(expression)
                    currentDepth -= 1
                }

                override fun visitDoWhileExpression(expression: KtDoWhileExpression) {
                    loopCount += 1
                    currentDepth += 1
                    maxDepth = maxOf(maxDepth, currentDepth)
                    penalty = AnalysisSupport.mergePenalty(penalty, currentDepth)
                    super.visitDoWhileExpression(expression)
                    currentDepth -= 1
                }

                override fun visitTryExpression(expression: KtTryExpression) {
                    tryCatchCount += expression.catchClauses.size
                    currentDepth += 1
                    maxDepth = maxOf(maxDepth, currentDepth)
                    penalty = AnalysisSupport.mergePenalty(penalty, currentDepth)
                    super.visitTryExpression(expression)
                    currentDepth -= 1
                }

                override fun visitBinaryExpression(expression: KtBinaryExpression) {
                    when (expression.operationToken) {
                        KtTokens.ANDAND, KtTokens.OROR -> logicalOpCount += 1
                        KtTokens.ELVIS -> ternaryCount += 1
                    }
                    super.visitBinaryExpression(expression)
                }
            },
        )

        val controlFlow =
            branchCount +
                simpleWhenBranchCount * 0.25 +
                loopCount * 1.2 +
                tryCatchCount +
                ternaryCount * 0.7 +
                logicalOpCount * 0.4
        return KotlinMethodMetrics(
            length = AnalysisSupport.effectiveLoc(function.text),
            controlFlow = controlFlow,
            nestingPenalty = maxOf(penalty, AnalysisSupport.nestingPenalty(maxDepth)),
        )
    }

    private fun isSimpleWhenEntry(entry: KtWhenEntry): Boolean {
        val text = entry.expression?.text?.trim() ?: return false
        return '\n' !in text && text.length <= 80
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

private data class KotlinMethodMetrics(
    val length: Int,
    val controlFlow: Double,
    val nestingPenalty: Int,
)
