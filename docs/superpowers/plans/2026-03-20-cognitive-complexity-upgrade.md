# Cognitive Complexity 升级实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用 SonarSource Cognitive Complexity 标准替换现有手工权重控制流评分，同步接入 Code Churn、修正 smell 系数与 DTO 乘数。

**Architecture:** 共 7 个 Task，依赖顺序：Task 1 → Task 2 → Task 3 & 4（可并行）→ Task 5 → Task 6 → Task 7（缓存 bump 最后）。Task 3/4 同时修改 Adapter 和 scoreHotspot 调用点，保证单 Task 内编译始终通过。

**Tech Stack:** Kotlin, IntelliJ Platform SDK (PSI), JUnit 5, `com.intellij.openapi.vcs`

**Spec:** `docs/superpowers/specs/2026-03-20-cognitive-complexity-upgrade-design.md`

---

## 文件变更总表

| 文件 | 操作 | Task |
|---|---|---|
| `src/main/kotlin/.../core/model/Models.kt` | 新增字段：FileAstSummary.cognitiveComplexity，NormalizationConfig.churnPoints，更新 controlFlowPoints 断点，修正 DTO 乘数 | 1 |
| `src/main/kotlin/.../core/scoring/ComplexityScorer.kt` | smell 系数修正 | 2 |
| `src/test/kotlin/.../core/scoring/ComplexityScorerTest.kt` | baseSummary 加 cognitiveComplexity，新增 smell/DTO/churn 测试 | 1, 2 |
| `src/main/kotlin/.../adapters/kotlin/KotlinLanguageAdapter.kt` | CC visitor 实现，KotlinMethodMetrics 改为 cognitiveComplexity，scoreHotspot 调用改名 | 3 |
| `src/main/kotlin/.../adapters/java/JavaLanguageAdapter.kt` | CC visitor 实现，JavaMethodMetrics 改为 cognitiveComplexity，scoreHotspot 调用改名 | 4 |
| `src/main/kotlin/.../core/scoring/ComplexityScorer.kt` | controlFlowRaw → cognitiveComplexity，scoreHotspot 参数改名 | 5 |
| `src/main/kotlin/.../adapters/common/AnalysisSupport.kt` | 删除 computeControlFlow（死代码） | 5 |
| `src/main/kotlin/.../integration/VcsFacade.kt` | 新增 commitCountFor() | 6 |
| `src/main/kotlin/.../ide/services/ComplexityRadarProjectService.kt` | churnNormalized 真实传值 | 6 |
| `src/main/kotlin/.../ide/cache/ComplexityResultStore.kt` | cache 版本号 bump | 7 |

---

## Task 1: Models 变更 — 新增字段与配置

**目标：** 新增数据模型字段（带默认值，不破坏编译），更新归一化断点，修正 DTO 乘数

**Files:**
- Modify: `src/main/kotlin/com/sqb/complexityradar/core/model/Models.kt`
- Modify: `src/test/kotlin/com/sqb/complexityradar/core/scoring/ComplexityScorerTest.kt`

---

- [ ] **Step 1: FileAstSummary 新增 cognitiveComplexity 字段**

在 `Models.kt` 的 `FileAstSummary` data class 末尾（`classNames: Set<String>` 之后）新增：

```kotlin
val cognitiveComplexity: Int = 0,   // 默认 0：现有 Adapter 调用不传此参数也能编译
```

- [ ] **Step 2: NormalizationConfig 新增 churnPoints 字段**

在 `NormalizationConfig` data class 末尾（`smellPoints` 之后）新增：

```kotlin
val churnPoints: List<ScalePoint> = listOf(
    ScalePoint(0.0, 0.00),
    ScalePoint(5.0, 0.20),
    ScalePoint(15.0, 0.50),
    ScalePoint(30.0, 0.80),
    ScalePoint(50.0, 1.00),
),
```

- [ ] **Step 3: 更新 RadarConfigDefaults 中的 controlFlowPoints 断点**

在 `RadarConfigDefaults.defaultConfig` 的 `normalization = NormalizationConfig(...)` 块中，找到：
```kotlin
controlFlowPoints = listOf(ScalePoint(0.0, 0.0), ScalePoint(20.0, 0.2), ScalePoint(60.0, 0.6), ScalePoint(120.0, 0.9), ScalePoint(200.0, 1.0)),
```
替换为（CC 刻度，参考 SonarQube 评级）：
```kotlin
controlFlowPoints = listOf(
    ScalePoint(0.0, 0.00),
    ScalePoint(15.0, 0.20),
    ScalePoint(30.0, 0.45),
    ScalePoint(60.0, 0.72),
    ScalePoint(100.0, 0.90),
    ScalePoint(150.0, 1.00),
),
```

- [ ] **Step 4: 修正 DTO 乘数**

在同一文件 `RadarConfigDefaults.defaultConfig` 的 `multipliers` 列表中，找到：
```kotlin
MultiplierRule("name:*Dto*|*Entity*|*Model*|*VO*|*PO*", 0.30),
```
改为：
```kotlin
MultiplierRule("name:*Dto*|*Entity*|*Model*|*VO*|*PO*", 0.55),
```

- [ ] **Step 5: 更新测试的 baseSummary**

在 `ComplexityScorerTest.kt` 的 `baseSummary()` 方法末尾添加：
```kotlin
cognitiveComplexity = 35,
```

- [ ] **Step 6: 运行测试，确认通过**

```bash
cd /Users/sqb/projects/code_analy_plugin
./gradlew test
```

期望：BUILD SUCCESS，所有现有测试 PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/sqb/complexityradar/core/model/Models.kt \
        src/test/kotlin/com/sqb/complexityradar/core/scoring/ComplexityScorerTest.kt
git commit -m "feat: add cognitiveComplexity/churnPoints fields, update CC normalization breakpoints, fix DTO multiplier"
```

---

## Task 2: ComplexityScorer — Smell 系数修正

**目标：** 修正 emptyCatchCount 和 bangBangCount 系数，确保严重代码坏味道有实质惩罚

**Files:**
- Modify: `src/main/kotlin/com/sqb/complexityradar/core/scoring/ComplexityScorer.kt`
- Modify: `src/test/kotlin/com/sqb/complexityradar/core/scoring/ComplexityScorerTest.kt`

> 注：此 Task **不**修改 `scoreHotspot` 签名（那在 Task 5 做），仅改 smell 系数。

---

- [ ] **Step 1: 写 smell 系数失败测试**

在 `ComplexityScorerTest.kt` 中新增：

```kotlin
@Test
fun `three empty catches push readability normalized above 0 point 6`() {
    val result = score(
        baseSummary().copy(
            emptyCatchCount = 3,
            todoCount = 0,
            bangBangCount = 0,
            magicNumberCount = 0,
            maxFunctionLoc = 0,
            maxParamCount = 0,
        )
    )
    val readability = result.contributions.first { it.type == FactorType.READABILITY }
    assertTrue(
        readability.normalized > 0.60,
        "Expected readability.normalized > 0.60 for 3 empty catches, was ${readability.normalized}"
    )
}

@Test
fun `ten bang bang operators push readability normalized above 0 point 3`() {
    val result = score(
        baseSummary().copy(
            bangBangCount = 10,
            emptyCatchCount = 0,
            todoCount = 0,
            magicNumberCount = 0,
            maxFunctionLoc = 0,
            maxParamCount = 0,
        )
    )
    val readability = result.contributions.first { it.type == FactorType.READABILITY }
    assertTrue(
        readability.normalized > 0.30,
        "Expected readability.normalized > 0.30 for 10 !! operators, was ${readability.normalized}"
    )
}
```

同时在文件顶部添加 import（如尚未存在）：
```kotlin
import com.sqb.complexityradar.core.model.FactorType
```

- [ ] **Step 2: 运行新测试，确认 FAIL**

```bash
./gradlew test --tests "com.sqb.complexityradar.core.scoring.ComplexityScorerTest.three empty catches push readability normalized above 0 point 6"
```

期望：FAIL

- [ ] **Step 3: 修改 ComplexityScorer.kt 中的 smellsRaw 系数**

在 `score()` 方法中找到：
```kotlin
val smellsRaw =
    summary.todoCount * 0.03 +
    summary.emptyCatchCount * 0.12 +
    summary.bangBangCount * 0.02 +
    summary.magicNumberCount * 0.01
```
替换为：
```kotlin
val smellsRaw =
    summary.todoCount        * 0.03 +
    summary.emptyCatchCount  * 0.40 +
    summary.bangBangCount    * 0.08 +
    summary.magicNumberCount * 0.01
```

- [ ] **Step 4: 运行全部测试，确认通过**

```bash
./gradlew test
```

期望：BUILD SUCCESS，所有测试 PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/sqb/complexityradar/core/scoring/ComplexityScorer.kt \
        src/test/kotlin/com/sqb/complexityradar/core/scoring/ComplexityScorerTest.kt
git commit -m "fix: raise empty catch and !! smell coefficients to reflect actual severity"
```

---

## Task 3: KotlinLanguageAdapter — CC 实现

**目标：** Kotlin PSI 遍历中实现 CC 算法，同步修改 `KotlinMethodMetrics` 字段类型与 `scoreHotspot` 调用参数名

**重要说明：** 本 Task 同时修改 `KotlinMethodMetrics` 中的 `controlFlow: Double` → `cognitiveComplexity: Int`，以及 `analyze()`/`hotspots()` 中对 `scorer.scoreHotspot()` 的调用参数名。**此时 `ComplexityScorer.scoreHotspot` 的形参名仍是 `controlFlow`，但 Kotlin 命名参数与实参类型匹配，编译会报错。** 解决方案：Task 3 的 Step 1 先将调用改为位置参数（去掉参数名），Task 5 再统一改签名。

**Files:**
- Modify: `src/main/kotlin/com/sqb/complexityradar/adapters/kotlin/KotlinLanguageAdapter.kt`

---

- [ ] **Step 1: KotlinMethodMetrics 类型变更 + 调用改为位置参数**

将文件末尾的 `KotlinMethodMetrics` data class 改为：
```kotlin
private data class KotlinMethodMetrics(
    val length: Int,
    val cognitiveComplexity: Int,   // 原为 controlFlow: Double
    val nestingPenalty: Int,
)
```

将 `analyze()` 和 `hotspots()` 中的 `scoreHotspot` 调用，把命名参数 `controlFlow = metrics.controlFlow` 改为位置参数调用（去除参数名），传入 `metrics.cognitiveComplexity.toDouble()`：

```kotlin
scorer.scoreHotspot(
    function.name ?: "<anonymous>",
    AnalysisSupport.lineNumber(file, function.nameIdentifier ?: function),
    metrics.length,
    metrics.cognitiveComplexity.toDouble(),   // 位置参数：对应现有 controlFlow: Double
    metrics.nestingPenalty,
    AnalysisSupport.snippet(function),
    config,
)
```

- [ ] **Step 2: 实现 collectMethodMetrics — CC 追踪**

完整替换 `collectMethodMetrics` 方法（保留原有 `penalty`/`currentDepth`/`maxDepth` 用于 `nestingPenalty` 计算）：

```kotlin
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
                if (innerFunction !== function) return  // 跳过嵌套具名函数
                super.visitNamedFunction(innerFunction)
            }

            override fun visitIfExpression(expression: KtIfExpression) {
                val isElseContinuation = isElseBranch(expression)
                if (isElseContinuation) {
                    ccScore += 1                    // else if：平加 1
                } else {
                    ccScore += 1 + ccDepth          // if：+1 + 当前深度
                }
                // plain else（不是 else-if 的 else 子句）
                val elseClause = expression.`else`
                if (elseClause != null && elseClause !is KtIfExpression) {
                    ccScore += 1
                }
                // 仅非 else-if 才增加嵌套深度
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
                // 每个非 else branch：+1+depth
                ccScore += nonElseBranches * (1 + ccDepth)
                // else branch：+1（平加）
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
                // try 本身不加分，增加深度供 catch 使用
                ccDepth += 1
                currentDepth += 1
                maxDepth = maxOf(maxDepth, currentDepth)
                penalty = AnalysisSupport.mergePenalty(penalty, currentDepth)
                super.visitTryExpression(expression)
                ccDepth -= 1
                currentDepth -= 1
            }

            override fun visitCatchSection(catchClause: KtCatchClause) {
                // catch 使用进入 try 后的深度（ccDepth 此时已是 try 内的深度）
                // 用 ccDepth - 1（try 外层深度）避免 try 层重复计入
                ccScore += 1 + (ccDepth - 1).coerceAtLeast(0)
                super.visitCatchSection(catchClause)
            }

            override fun visitLambdaExpression(lambdaExpression: KtLambdaExpression) {
                ccDepth += 1
                super.visitLambdaExpression(lambdaExpression)
                ccDepth -= 1
            }

            override fun visitBinaryExpression(expression: KtBinaryExpression) {
                val op = expression.operationToken
                if (op == KtTokens.ANDAND || op == KtTokens.OROR) {
                    // 父节点是相同运算符时不计数（避免连续同类运算符重复计）
                    val parentOp = (expression.parent as? KtBinaryExpression)?.operationToken
                    if (parentOp != op) {
                        ccScore += 1
                    }
                }
                super.visitBinaryExpression(expression)
            }

            override fun visitCallExpression(expression: KtCallExpression) {
                // 递归检测：调用自身方法名时 +1
                val callee = expression.calleeExpression?.text?.substringAfterLast(".")
                if (methodName != null && callee == methodName) {
                    ccScore += 1
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
```

- [ ] **Step 3: 文件级 CC 采集（summarize 方法）**

在 `summarize()` 方法的 visitor 变量声明区顶部（紧接 `var currentBlockDepth = 0` 之后）新增：
```kotlin
var ccScore = 0
var ccNestingDepth = 0
```

在 visitor 内部，将现有 `visitIfExpression` 替换为（完整示例，其他 visit 方法照此模式叠加 CC）：

```kotlin
override fun visitIfExpression(expression: KtIfExpression) {
    branchCount += 1   // ← 保留原有统计
    // CC 计分
    val isElse = isElseBranch(expression)
    if (isElse) {
        ccScore += 1
    } else {
        ccScore += 1 + ccNestingDepth
    }
    val elseClause = expression.`else`
    if (elseClause != null && elseClause !is KtIfExpression) {
        ccScore += 1   // plain else
    }
    // 原有深度追踪
    currentBlockDepth += 1
    maxBlockDepth = maxOf(maxBlockDepth, currentBlockDepth)
    nestingPenalty = AnalysisSupport.mergePenalty(nestingPenalty, currentBlockDepth)
    if (!isElse) ccNestingDepth += 1
    super.visitIfExpression(expression)
    currentBlockDepth -= 1
    if (!isElse) ccNestingDepth -= 1
}
```

其余 visit 方法（`visitWhenExpression`、`visitForExpression`、`visitWhileExpression`、`visitDoWhileExpression`、`visitTryExpression`、`visitCatchSection`、`visitLambdaExpression`）：**在原有逻辑前后各加 `ccScore +=` 和 `ccNestingDepth +=/-=`，规则与 `collectMethodMetrics` 完全一致**（catch 用 `(ccNestingDepth - 1).coerceAtLeast(0)`，try 只改 depth 不加 ccScore）。

`visitBinaryExpression` 中同样添加布尔序列去重（`parentOp != op` 时 `ccScore += 1`）。

在 `FileAstSummary(...)` 构造调用末尾添加：
```kotlin
cognitiveComplexity = ccScore,
```

- [ ] **Step 4: 确认编译通过**

```bash
./gradlew compileKotlin 2>&1 | head -30
```

期望：0 编译错误

- [ ] **Step 5: 运行测试**

```bash
./gradlew test
```

期望：全部 PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/sqb/complexityradar/adapters/kotlin/KotlinLanguageAdapter.kt
git commit -m "feat: implement Cognitive Complexity visitor in KotlinLanguageAdapter"
```

---

## Task 4: JavaLanguageAdapter — CC 实现

**目标：** Java PSI 遍历中实现 CC，与 Kotlin Adapter 对称

**Files:**
- Modify: `src/main/kotlin/com/sqb/complexityradar/adapters/java/JavaLanguageAdapter.kt`

---

- [ ] **Step 1: JavaMethodMetrics 类型变更 + 调用改为位置参数**

将文件末尾的 `JavaMethodMetrics` data class 改为：
```kotlin
private data class JavaMethodMetrics(
    val length: Int,
    val cognitiveComplexity: Int,   // 原为 controlFlow: Double
    val nestingPenalty: Int,
)
```

将 `analyze()` 和 `hotspots()` 中的 `scoreHotspot` 调用改为位置参数，传入 `metrics.cognitiveComplexity.toDouble()`（同 Task 3 Step 1 策略）。

- [ ] **Step 2: 实现 collectMethodMetrics — Java CC 追踪**

完整替换 `collectMethodMetrics` 方法：

```kotlin
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

            override fun visitSwitchExpression(expression: com.intellij.psi.PsiSwitchExpression) {
                // Java 14+ switch expression，与 statement 相同
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
                ccDepth += 1; currentDepth += 1
                maxDepth = maxOf(maxDepth, currentDepth)
                penalty = AnalysisSupport.mergePenalty(penalty, currentDepth)
                super.visitTryStatement(statement)
                ccDepth -= 1; currentDepth -= 1
            }

            override fun visitCatchSection(section: com.intellij.psi.PsiCatchSection) {
                ccScore += 1 + (ccDepth - 1).coerceAtLeast(0)
                super.visitCatchSection(section)
            }

            override fun visitPolyadicExpression(expression: PsiPolyadicExpression) {
                val op = expression.operationTokenType
                if (op == JavaTokenType.ANDAND || op == JavaTokenType.OROR) {
                    // PsiPolyadicExpression 已是同类运算符的展平序列，每个计 1
                    ccScore += 1
                }
                super.visitPolyadicExpression(expression)
            }

            override fun visitMethodCallExpression(expression: com.intellij.psi.PsiMethodCallExpression) {
                if (expression.methodExpression.referenceName == methodName) {
                    ccScore += 1  // 递归
                }
                super.visitMethodCallExpression(expression)
            }

            override fun visitBreakStatement(statement: com.intellij.psi.PsiBreakStatement) {
                if (statement.labelIdentifier != null) ccScore += 1  // break with label
                super.visitBreakStatement(statement)
            }

            override fun visitContinueStatement(statement: com.intellij.psi.PsiContinueStatement) {
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
```

- [ ] **Step 3: 文件级 CC 采集（summarize 方法）**

在 `summarize()` visitor 顶部新增 `var ccScore = 0` 和 `var ccNestingDepth = 0`。

完整替换 `visitIfStatement`（Kotlin 是 `visitIfExpression`，Java 是 `visitIfStatement`）为（含 else-if 检测逻辑）：

```kotlin
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
    branchCount += 1  // ← 保留原有统计
    currentDepth += 1
    maxBlockDepth = maxOf(maxBlockDepth, currentDepth)
    nestingPenalty = AnalysisSupport.mergePenalty(nestingPenalty, currentDepth)
    if (!isElseContinuation) ccNestingDepth += 1
    super.visitIfStatement(statement)
    currentDepth -= 1
    if (!isElseContinuation) ccNestingDepth -= 1
}
```

其余 visit 方法同 `collectMethodMetrics`，在原有深度追踪逻辑旁叠加 `ccScore` 计分。新增 `visitSwitchExpression`（Java 14+）与 `visitBreakStatement`/`visitContinueStatement`（带 label 时 `ccScore += 1`）。

`FileAstSummary(...)` 末尾添加 `cognitiveComplexity = ccScore`。

- [ ] **Step 4: 确认编译 + 测试**

```bash
./gradlew test
```

期望：全部 PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/sqb/complexityradar/adapters/java/JavaLanguageAdapter.kt
git commit -m "feat: implement Cognitive Complexity visitor in JavaLanguageAdapter"
```

---

## Task 5: ComplexityScorer 最终接入 — controlFlowRaw 替换 + 签名清理

**目标：** Scorer 正式使用 `cognitiveComplexity`，更新 `scoreHotspot` 签名为具名参数，清理死代码

**Files:**
- Modify: `src/main/kotlin/com/sqb/complexityradar/core/scoring/ComplexityScorer.kt`
- Modify: `src/main/kotlin/com/sqb/complexityradar/adapters/common/AnalysisSupport.kt`

---

- [ ] **Step 1: 写 CC 评分测试（验证 cognitiveComplexity 驱动控制流分）**

> **说明：** CC PSI 级别的遍历算法测试（if/when/布尔序列等）需要 IntelliJ 测试 fixture，超出本次范围。此处以 Scorer 级别测试作为代理验证：给定不同 `cognitiveComplexity` 值，确认 `CONTROL_FLOW` 因子归一化结果符合预期。

在 `ComplexityScorerTest.kt` 中新增三个测试：

```kotlin
@Test
fun `cc zero produces zero control flow normalized`() {
    val result = score(baseSummary().copy(cognitiveComplexity = 0))
    val cf = result.contributions.first { it.type == FactorType.CONTROL_FLOW }
    assertEquals(0.0, cf.normalized, 0.001)
}

@Test
fun `cc at sonarqube A boundary normalizes to about 0 point 2`() {
    // CC=15 → controlFlowPoints 中对应归一化值 0.20
    val result = score(baseSummary().copy(cognitiveComplexity = 15))
    val cf = result.contributions.first { it.type == FactorType.CONTROL_FLOW }
    assertTrue(cf.normalized in 0.18..0.22,
        "Expected normalized ~0.20 for CC=15, was ${cf.normalized}")
}

@Test
fun `high cc 80 normalizes above 0 point 8`() {
    val result = score(baseSummary().copy(cognitiveComplexity = 80))
    val cf = result.contributions.first { it.type == FactorType.CONTROL_FLOW }
    assertTrue(cf.normalized > 0.80,
        "Expected normalized > 0.80 for CC=80, was ${cf.normalized}")
}
```

- [ ] **Step 2: 运行新测试，确认 FAIL**

```bash
./gradlew test --tests "*.high cognitive complexity raises control flow contribution"
```

期望：FAIL（Scorer 目前仍使用 `controlFlowRaw`）

- [ ] **Step 3: 替换 controlFlowRaw 为 cognitiveComplexity**

在 `ComplexityScorer.kt` 的 `score()` 方法中，找到并删除整个 `controlFlowRaw` 计算块（约 7 行），替换为：

```kotlin
// 原：
// val controlFlowRaw = summary.branchCount + ... (整块删除)
// val controlFlow = Normalization.piecewise(controlFlowRaw, config.normalization.controlFlowPoints)

// 新：
val controlFlow = Normalization.piecewise(
    summary.cognitiveComplexity.toDouble(),
    config.normalization.controlFlowPoints,
)
```

同时更新 `contributions` 中 `CONTROL_FLOW` 的 `rawValue`：
```kotlin
FactorType.CONTROL_FLOW -> summary.cognitiveComplexity.toDouble()
```

- [ ] **Step 4: 更新 scoreHotspot 签名为具名参数**

将 `scoreHotspot` 的参数 `controlFlow: Double` 改为 `cognitiveComplexity: Int`，内部更新：

```kotlin
fun scoreHotspot(
    methodName: String,
    line: Int,
    length: Int,
    cognitiveComplexity: Int,       // 原为 controlFlow: Double
    nestingPenalty: Int,
    snippet: String?,
    config: RadarConfig,
): Hotspot {
    val nesting     = Normalization.piecewise(nestingPenalty.toDouble(),        config.normalization.nestingPenaltyPoints)
    val control     = Normalization.piecewise(cognitiveComplexity.toDouble(),   config.normalization.controlFlowPoints)
    val methodLength = Normalization.piecewise(length.toDouble(),               config.normalization.maxFunctionLocPoints)
    val score = (100.0 * (0.45 * nesting + 0.35 * control + 0.20 * methodLength)).roundToInt().coerceIn(0, 100)
    val severity = config.severityFor(score)
    val contributions = listOf(
        FactorContribution(FactorType.NESTING,       nesting,      0.45, nesting * 0.45,      nestingPenalty.toDouble(),      "Nested blocks are concentrated in this method."),
        FactorContribution(FactorType.CONTROL_FLOW,  control,      0.35, control * 0.35,      cognitiveComplexity.toDouble(), "Cognitive complexity is concentrated in this method."),
        FactorContribution(FactorType.SIZE,          methodLength, 0.20, methodLength * 0.20, length.toDouble(),              "Method length is adding local comprehension cost."),
    ).sortedByDescending { it.weightedScore }
    return Hotspot(
        methodName     = methodName,
        line           = line,
        score          = score,
        severity       = severity,
        contributions  = contributions,
        recommendation = "Reduce nesting, split responsibilities, and extract branches into named helpers.",
        snippet        = snippet,
    )
}
```

- [ ] **Step 5: 将两个 Adapter 中的位置参数调用恢复为具名参数**

在 `KotlinLanguageAdapter` 和 `JavaLanguageAdapter` 的 `analyze()` 和 `hotspots()` 中，将临时的位置参数调用改回具名参数：

```kotlin
scorer.scoreHotspot(
    methodName          = function.name ?: "<anonymous>",
    line                = AnalysisSupport.lineNumber(file, function.nameIdentifier ?: function),
    length              = metrics.length,
    cognitiveComplexity = metrics.cognitiveComplexity,
    nestingPenalty      = metrics.nestingPenalty,
    snippet             = AnalysisSupport.snippet(function),
    config              = config,
)
```

Java Adapter 同理（`method.name` 替代 `function.name ?? "<anonymous>"`）。

- [ ] **Step 6: 删除 AnalysisSupport.computeControlFlow（死代码）**

在 `AnalysisSupport.kt` 中，找到并删除整个 `computeControlFlow` 方法（第 91–105 行附近）。

- [ ] **Step 7: 运行全部测试，确认通过**

```bash
./gradlew test
```

期望：BUILD SUCCESS，所有测试 PASS（含 Step 1 新增的测试）

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/com/sqb/complexityradar/core/scoring/ComplexityScorer.kt \
        src/main/kotlin/com/sqb/complexityradar/adapters/common/AnalysisSupport.kt \
        src/main/kotlin/com/sqb/complexityradar/adapters/kotlin/KotlinLanguageAdapter.kt \
        src/main/kotlin/com/sqb/complexityradar/adapters/java/JavaLanguageAdapter.kt \
        src/test/kotlin/com/sqb/complexityradar/core/scoring/ComplexityScorerTest.kt
git commit -m "feat: ComplexityScorer now drives CONTROL_FLOW from cognitiveComplexity, clean up dead controlFlowRaw"
```

---

## Task 6: VcsFacade + 服务层 Churn 接入

**目标：** 实现 `commitCountFor()`，将真实 churnNormalized 传入 scorer，使 priority 排序有实际意义

**Files:**
- Modify: `src/main/kotlin/com/sqb/complexityradar/integration/VcsFacade.kt`
- Modify: `src/main/kotlin/com/sqb/complexityradar/ide/services/ComplexityRadarProjectService.kt`

---

- [ ] **Step 1: 实现 VcsFacade.commitCountFor()**

在 `VcsFacade.kt` 顶部新增 imports：
```kotlin
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.vcsUtil.VcsUtil
```

在 `changedFiles()` 方法后新增：
```kotlin
/**
 * 统计文件在最近 [days] 天内的 VCS 提交次数。
 * 使用 IntelliJ 通用 VCS API（不依赖 git4idea），支持 Git/SVN/Hg 等。
 *
 * 行为说明：
 * - VCS 未初始化 / historyProvider 不支持 → 返回 0（降级）
 * - createSessionFor 返回 null → 返回 0（文件无 VCS 历史）
 * - session.revisionList 为空 → 返回 0（真实 0 次提交，与降级结果相同，可接受）
 * - 历史加载按时间窗口在内存中过滤；对大型仓库可能加载较多数据，
 *   但分析本身已在后台线程执行，不阻塞 EDT。
 */
fun commitCountFor(file: VirtualFile, days: Int = 90): Int = runCatching {
    val vcs = ProjectLevelVcsManager.getInstance(project).getVcsFor(file)
        ?: return@runCatching 0
    val historyProvider = vcs.vcsHistoryProvider
        ?: return@runCatching 0
    val filePath = VcsUtil.getFilePath(file)
    val session = historyProvider.createSessionFor(filePath)
        ?: return@runCatching 0
    val cutoff = System.currentTimeMillis() - days.toLong() * 86_400_000L
    session.revisionList.count { (it.revisionDate?.time ?: 0L) >= cutoff }
}.getOrDefault(0)
```

- [ ] **Step 2: 写 Churn 归一化单元测试**

VcsFacade 内部依赖 IntelliJ API，无法在普通 JUnit 中 mock。改为在 `ComplexityScorerTest.kt` 中验证归一化行为（不依赖 IntelliJ 环境）：

在 `ComplexityScorerTest.kt` 末尾新增：

```kotlin
@Test
fun `churn normalized 0 point 5 raises priority above score`() {
    // 模拟：commitCountFor 返回 15 次 → churnNormalized ≈ 0.50
    // priority = score * (1 + 0.5 * 0.5) = score * 1.25
    val summary = baseSummary()
    val result = scorer.score(
        summary    = summary,
        fileUrl    = "file:///Foo.kt",
        filePath   = "/tmp/Foo.kt",
        mode       = AnalyzeMode.ACCURATE,
        config     = RadarConfigDefaults.defaultConfig,
        hotspots   = emptyList(),
        churnNormalized = 0.50,
    )
    assertTrue(result.priority > result.score,
        "Expected priority ${result.priority} > score ${result.score} when churn is active")
}
```

- [ ] **Step 3: 在 ComplexityRadarProjectService 中传入真实 churn**

在 `computeResult()` 方法中找到 `scorer.score(...)` 调用处的 `churnNormalized = 0.0`，替换为：

```kotlin
churnNormalized = Normalization.piecewise(
    vcsFacade.commitCountFor(file).toDouble(),
    config.normalization.churnPoints,
),
```

在文件顶部 import 区添加（若尚未存在）：
```kotlin
import com.sqb.complexityradar.core.scoring.Normalization
```

- [ ] **Step 4: 运行测试**

```bash
./gradlew test
```

期望：全部 PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/sqb/complexityradar/integration/VcsFacade.kt \
        src/main/kotlin/com/sqb/complexityradar/ide/services/ComplexityRadarProjectService.kt \
        src/test/kotlin/com/sqb/complexityradar/core/scoring/ComplexityScorerTest.kt
git commit -m "feat: wire real code churn into priority scoring via VcsFacade.commitCountFor"
```

---

## Task 7: 缓存版本 Bump + 全量构建验证

**目标：** 递增缓存版本号使旧结果失效，验证完整构建通过

**Files:**
- Modify: `src/main/kotlin/com/sqb/complexityradar/ide/cache/ComplexityResultStore.kt`

---

- [ ] **Step 1: 递增缓存目录版本号**

在 `ComplexityResultStore.kt` 的 `cacheDir()` 方法中，找到字符串 `"cache-v2.1"` 并改为 `"cache-v3.0"`：

```kotlin
// 原：
return Path.of(base).resolve(".idea").resolve("complexity-radar").resolve("cache-v2.1")
// 改：
return Path.of(base).resolve(".idea").resolve("complexity-radar").resolve("cache-v3.0")
```

> 旧目录 `cache-v2.1` 不会被读取，用户下次启动 IDE 时自动触发全量重新分析。旧目录数据不删除，用户可手动清理。

- [ ] **Step 2: 全量构建**

```bash
./gradlew build
```

期望：BUILD SUCCESS，0 测试失败，0 编译错误

- [ ] **Step 3: 沙箱 IDE 手动验证**

```bash
./gradlew runIde
```

打开项目后检查：
1. 打开一个含空 catch 的 Kotlin 文件 → Editor Banner 分数应高于之前
2. 打开一个 `*Dto.kt` 文件 → 分数应为 GREEN 或 LOW YELLOW（不再被拉到 RED）
3. 点击 Editor Banner 的 "Open Radar" → 面板打开，切换到 Current File 的 Overview tab
4. Issues 面板中，含空 catch 的文件在 ORANGE/RED 区有体现

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/sqb/complexityradar/ide/cache/ComplexityResultStore.kt
git commit -m "chore: bump cache version to v3.0, invalidate pre-CC-upgrade cached results"
```

---

## 完成标准

- [ ] `./gradlew build` 全绿，0 测试失败
- [ ] `FileAstSummary.cognitiveComplexity` 字段存在，两个 Adapter 均填充真实 CC 值
- [ ] `ComplexityScorer.score()` 使用 `cognitiveComplexity` 驱动 `CONTROL_FLOW` 因子
- [ ] 3 个空 catch → Readability normalized > 0.60
- [ ] 10 个 `!!` → Readability normalized > 0.30
- [ ] `*Dto*` 文件分数约为同等复杂度普通文件的 55%（±10%）
- [ ] `priority` 在高频 git 提交的高分文件上 > `score`
- [ ] 缓存路径为 `cache-v3.0`
