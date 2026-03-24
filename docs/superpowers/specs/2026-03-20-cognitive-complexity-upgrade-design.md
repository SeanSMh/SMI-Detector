# Cognitive Complexity 升级设计文档

**日期：** 2026-03-20
**状态：** 待实现
**范围：** 方案二 — 完整替换控制流 + 全面修复

---

## 背景与目标

现有控制流评分使用手工权重拼凑（循环×1.2、三元×0.7 等），缺乏行业依据。本次升级引入 SonarSource Cognitive Complexity 开放标准替换该逻辑，同步修复 smell 系数过宽、DTO 乘数过激、Code Churn 未接入等已知问题。

**最终输出分数范围不变：0–100 整数。**

---

## 变动范围概览

```
adapters/kotlin/KotlinLanguageAdapter.kt
adapters/java/JavaLanguageAdapter.kt
  ① 新增 CognitiveComplexityVisitor（文件级 + 方法级）
     KotlinMethodMetrics / JavaMethodMetrics 中 controlFlow: Double → cognitiveComplexity: Int

core/model/Models.kt
  ② FileAstSummary 新增 cognitiveComplexity: Int
     NormalizationConfig 新增 churnPoints: List<ScalePoint>（含默认值）

core/scoring/ComplexityScorer.kt
  ③ controlFlowRaw → cognitiveComplexity（Int 直接传入 piecewise）
  ③ smell 系数修正
  ③ churnNormalized 真实传值

integration/VcsFacade.kt
  ④ commitCountFor()：使用 IntelliJ Git API，区分"0次提交"与"命令失败"

ide/services/ComplexityRadarProjectService.kt
  ④ 归一化 churn 后传入 scorer

ide/cache/ComplexityResultStore.kt
  ⑤ cache 版本号 bump
```

**实现顺序依赖：**
- ① 必须先于 ③（Scorer 依赖 cognitiveComplexity 字段）
- ② 与 ① 同步进行（FileAstSummary 字段变更）
- ④ 可与 ①②③ 并行
- ⑤ 最后执行（改版本号触发全量重新分析）

**不变：** UI 层全部、piecewise 框架、权重分配、severity 阈值、plugin.xml。

---

## 一、Cognitive Complexity 算法规范

### 1.1 计分规则

**结构加分（`score += 1 + currentNestingDepth`）：**

| 结构 | Kotlin PSI 节点 | Java PSI 节点 | 备注 |
|---|---|---|---|
| `if` | `KtIfExpression` | `PsiIfStatement` | +1 + depth |
| `else if` | 同上（链式） | 同上（链式） | +1，不加 depth（不增加嵌套） |
| `else` | `KtIfExpression.else` | `PsiIfStatement.elseBranch` | +1，不加 depth |
| `for` | `KtForExpression` | `PsiForStatement` / `PsiForeachStatement` | +1 + depth |
| `while` | `KtWhileExpression` | `PsiWhileStatement` | +1 + depth |
| `do-while` | `KtDoWhileExpression` | `PsiDoWhileStatement` | +1 + depth |
| `catch` | `KtCatchClause` | `PsiCatchSection` | +1 + depth |
| `when`（有 subject） | `KtWhenExpression` 下每个非 else `KtWhenEntry` | — | 每个非 else branch +1 + depth |
| `when`（无 subject） | 同上，条件作为独立 branch | — | 与有 subject 相同处理 |
| `switch` | — | `PsiSwitchStatement` 整体 | +1 + depth（不拆 case） |
| `switch expression` | — | `PsiSwitchExpression`（Java 14+） | +1 + depth，与 statement 相同 |
| 递归调用 | 见 §1.4 | 见 §1.4 | +1，不加 depth |
| 标签跳转 | `break`/`continue` with `@label` | `break`/`continue` with label | +1，不加 depth |

**`else` / `else if` 详细规则：**
```
if (a) {        // +1 + depth=0 → +1
} else if (b) { // +1（不加 depth，else if 不增加嵌套层）
} else {        // +1（不加 depth）
}
```

### 1.2 布尔序列去重（PSI 实现）

连续相同运算符只计 1 次；运算符切换时重新计数。

**Kotlin 实现（`KtBinaryExpression` 树展平）：**
```kotlin
// 将右结合的 KtBinaryExpression 树展平为运算符序列
// a && b && c  →  [&&, &&]  →  按切换点计数 → 1
// a && b || c  →  [&&, ||]  →  切换一次 → 2
fun countBooleanSequences(expr: KtBinaryExpression): Int {
    val ops = flattenBoolOps(expr)  // 展平为 List<IElementType>
    if (ops.isEmpty()) return 0
    var count = 1
    for (i in 1 until ops.size) {
        if (ops[i] != ops[i - 1]) count++
    }
    return count
}
```

**Java 实现（`PsiPolyadicExpression` 已是展平结构）：**
```kotlin
// PsiPolyadicExpression 包含多个操作数和操作符
// 遍历操作符列表，每次切换 && ↔ || 时 count++
fun countBooleanSequences(expr: PsiPolyadicExpression): Int {
    val tokenType = expr.operationTokenType
    if (tokenType != JavaTokenType.ANDAND && tokenType != JavaTokenType.OROR) return 0
    return 1  // PsiPolyadicExpression 本身已是同类运算符的展平序列
    // 嵌套不同运算符时 Java 会生成独立的 PsiPolyadicExpression 节点
}
```

### 1.3 嵌套深度维护

进入以下结构时 `nestingDepth += 1`，离开时 `nestingDepth -= 1`：
- `if` / `for` / `while` / `do-while` / `when` / `catch`
- 匿名类 / lambda / Kotlin 函数字面量（`KtLambdaExpression`）

`else` 和 `else if` **不修改** `nestingDepth`（它们不增加新的嵌套层级）。

### 1.4 递归检测

**实现策略：方法名字符串匹配（不解析引用，避免性能开销）：**

```kotlin
// Kotlin：遍历到 KtCallExpression 时
if (callExpr.calleeExpression?.text == currentMethodName) score += 1

// Java：遍历到 PsiMethodCallExpression 时
if (callExpr.methodExpression.referenceName == currentMethodName) score += 1
```

**已知局限（可接受的误差）：**
- 同名不同签名的重载方法会产生假阳性
- extension function 调用自身时 `text` 可能含限定符，需 `.substringAfterLast(".")` 处理
- 匿名函数无方法名，跳过递归检测

### 1.5 方法级 CC 输出（数据类变更）

`collectMethodMetrics()` 返回值中 `controlFlow: Double` → `cognitiveComplexity: Int`：

```kotlin
// Kotlin
data class KotlinMethodMetrics(
    val name: String,
    val line: Int,
    val length: Int,
    val cognitiveComplexity: Int,  // 替换 controlFlow: Double
    val nestingPenalty: Int,
    val snippet: String?,
)

// Java
data class JavaMethodMetrics(
    val name: String,
    val line: Int,
    val length: Int,
    val cognitiveComplexity: Int,  // 替换 controlFlow: Double
    val nestingPenalty: Int,
    val snippet: String?,
)
```

`ComplexityScorer.scoreHotspot()` 的 `controlFlow: Double` 参数改为 `cognitiveComplexity: Int`，内部 `Normalization.piecewise()` 调用直接使用 `cognitiveComplexity.toDouble()`。

### 1.6 归一化断点（更新 `controlFlowPoints`）

| CC 原始值 | 归一化值 | 说明 |
|---|---|---|
| 0 | 0.00 | |
| 15 | 0.20 | SonarQube A 级上限 |
| 30 | 0.45 | |
| 60 | 0.72 | |
| 100 | 0.90 | |
| 150 | 1.00 | 超出部分 piecewise 自动 clamp |

---

## 二、FileAstSummary 字段变更

文件：`core/model/Models.kt`

```kotlin
data class FileAstSummary(
    // ... 现有字段全部保留（branchCount 等不删除，避免破坏现有单元测试）...
    val cognitiveComplexity: Int,   // 新增：文件级 CC 总分
)
```

**序列化说明：**
- `FileAstSummary` 是内存中间对象，**不序列化到磁盘**，此字段新增无反序列化风险
- `ComplexityResult`（磁盘缓存对象）不含 `FileAstSummary`，缓存格式不受影响
- 但 `NormalizationConfig` 新增 `churnPoints` 会改变 `RadarConfig.toString()`，进而改变 `configHash`，导致所有现存缓存条目在首次运行后因 hash 不匹配而重新分析 —— 这与缓存版本 bump 效果叠加，两者均触发无问题（结果相同：全量重新分析）

---

## 三、Smell 系数修正

文件：`core/scoring/ComplexityScorer.kt`

```kotlin
// 修改前
val smellsRaw =
    summary.todoCount        * 0.03 +
    summary.emptyCatchCount  * 0.12 +
    summary.bangBangCount    * 0.02 +
    summary.magicNumberCount * 0.01

// 修改后
val smellsRaw =
    summary.todoCount        * 0.03 +
    summary.emptyCatchCount  * 0.40 +   // 3 个空 catch → ~1.2，进入严重区
    summary.bangBangCount    * 0.08 +   // 10 个 !! → ~0.8
    summary.magicNumberCount * 0.01
```

---

## 四、DTO 全局乘数修正

文件：`core/model/Models.kt`（`RadarConfigDefaults`）

```kotlin
// 修改前
MultiplierRule("name:*Dto*|*Entity*|*Model*|*VO*|*PO*", 0.30)

// 修改后
MultiplierRule("name:*Dto*|*Entity*|*Model*|*VO*|*PO*", 0.55)
```

---

## 五、Code Churn 接入

### 5.1 VcsFacade 新增方法

文件：`integration/VcsFacade.kt`

**使用 IntelliJ Git API，不直接调用系统 `git` 命令：**

```kotlin
fun commitCountFor(file: VirtualFile, days: Int = 90): Int {
    return try {
        val repository = GitRepositoryManager.getInstance(project)
            .getRepositoryForFile(file) ?: return 0
        val handler = GitLineHandler(project, repository.root, GitCommand.LOG)
        handler.addParameters("--since=${days} days ago", "--follow", "--oneline", "--", file.path)
        val output = Git.getInstance().runCommand(handler)
        if (output.success()) output.output.size else 0
    } catch (_: Throwable) {
        0  // 非 git 仓库、git 不可用时静默降级
    }
}
```

**"0次提交"与"命令失败"的区分：**
- `output.success() == true` 且 `output.output.isEmpty()` → 0 次提交（文件无历史）
- `output.success() == false` → 命令失败 → 返回 0（降级，不抛异常）

### 5.2 NormalizationConfig 新增断点

```kotlin
data class NormalizationConfig(
    // ... 现有字段 ...
    val churnPoints: List<ScalePoint> = listOf(   // 有默认值，radar.yaml 不配置时自动使用
        ScalePoint(0.0, 0.00),
        ScalePoint(5.0, 0.20),
        ScalePoint(15.0, 0.50),
        ScalePoint(30.0, 0.80),
        ScalePoint(50.0, 1.00),
    ),
)
```

**radar.yaml 向后兼容：** `churnPoints` 有默认值，旧版 yaml 缺失此键时不会抛出异常。

### 5.3 ComplexityRadarProjectService 传值

```kotlin
churnNormalized = Normalization.piecewise(
    vcsFacade.commitCountFor(file).toDouble(),
    config.normalization.churnPoints
)
```

---

## 六、缓存版本 Bump

文件：`ide/cache/ComplexityResultStore.kt`

- 将缓存文件版本常量递增（如 `CACHE_VERSION = 2` → `CACHE_VERSION = 3`）
- 启动时检测到旧版本文件，静默忽略并触发全量重新分析
- 与 `configHash` 变更叠加，两次失效效果等同，无副作用

---

## 七、数据流变化对比

```
修改前：
Adapter → branchCount / loopCount / ternaryCount / logicalOpCount（多字段）
Scorer  → controlFlowRaw = branchCount + loopCount×1.2 + ...（手工权重）
        → Normalization.piecewise(controlFlowRaw, controlFlowPoints)

修改后：
Adapter → cognitiveComplexity: Int（单一标准 CC 值）
Scorer  → Normalization.piecewise(cognitiveComplexity.toDouble(), controlFlowPoints)
          （controlFlowPoints 断点更新为 CC 刻度）
```

`branchCount`、`loopCount`、`ternaryCount`、`logicalOpCount` 在 `FileAstSummary` 中保留，不删除（不破坏现有测试），但不再参与 CONTROL_FLOW 评分。

---

## 八、测试策略

### 单元测试：CC 算法（KotlinCCVisitorTest / JavaCCVisitorTest）

必须覆盖以下用例（Kotlin/Java 各一份）：

| 用例 | 预期 CC |
|---|---|
| 空方法 | 0 |
| 单个 `if` | 1 |
| `if` + `else` | 2 |
| `if` + `else if` + `else` | 3 |
| 嵌套 `if`（depth=1 时内层 +2） | 1 + 2 = 3 |
| `for` 内含 `if` | 1 + 2 = 3 |
| `a && b && c`（连续同类） | 1 |
| `a && b \|\| c`（切换运算符） | 2 |
| 递归调用 | +1 |
| `when` 3 个非 else branch | 3 |
| `else` 不加嵌套深度验证 | 见上 `if+else+else if` |
| 深度为 0 时控制结构仍 +1 | 1 |

### 单元测试：ComplexityScorer

- smell 新系数：1 个空 catch → smellsRaw == 0.40
- smell 新系数：10 个 `!!` → smellsRaw == 0.80
- DTO 乘数：score 应约为无乘数时的 55%
- churnNormalized 传值：mock VcsFacade 返回 15，验证归一化后 ≈ 0.50

### 单元测试：VcsFacade

- 正常路径：mock Git API 返回 3 行输出 → commitCountFor == 3
- 命令失败路径：mock Git API success=false → 返回 0
- 非 git 仓库：repository == null → 返回 0

### 性能基线

- 对 1000 行多重嵌套文件，CC Visitor 单次遍历应在 50ms 以内（在 FAST 模式 800ms debounce 窗口内有足够余量）
- 通过现有 `ReadAction.nonBlocking` 机制运行，不阻塞 EDT

### 集成验证（手动）

选取 3 类典型文件，确认分数变化方向符合预期：
1. 深层嵌套的 Activity → 分数应上升
2. 简单 DTO 数据类 → 分数应仍为 GREEN（乘数 0.55 足够低）
3. 含空 catch 的工具类 → 分数应明显上升

---

## 九、不在本次范围内

- Maintainability Index 副指标
- `radar.yaml` 暴露 churn 窗口天数配置项（当前硬编码 90 天）
- UI 层变更（雷达图、Issues 面板）
- Halstead 指标采集
- 递归检测的完整引用解析（当前用方法名字符串匹配，接受已知误差）
