# 复杂度雷达设计文档（认知负担 2.1）

## 1. 目标与边界

### 1.1 核心目标
- 对 Java / Kotlin 源码文件计算认知负担复杂度评分（0–100）
- 输出可解释信息：文件总分、维度贡献、热点列表、命中规则证据
- 在 IDE（Android Studio / IntelliJ）中提供无缝、非侵入式可视化
- 支持项目级配置文件 radar.yaml（可提交 Git），确保团队标准一致
- 联动 AI 工具提供重构能力入口（不强制联网）

**输出内容**
- 文件总分 + 维度贡献（FactorContribution）
- 热点列表（Hotspots：方法/函数级）
- 命中规则与证据（imports / 注解 / 语法结构）

**IDE 可视化**
- Project View 热力气泡（● 62）
- Editor 顶部 Banner
- Gutter 热点警告（⚠️）
- ToolWindow 多视图 + 过滤 + 跳转

### 1.2 非目标
- 不做运行时 profiling，仅本地 PSI 静态分析
- 不做重量级跨文件 CFG / call graph 全量分析
- 不承诺与 Sonar 的 Cognitive Complexity 分数一致

## 2. 用户体验与能力清单（完整形态）

### 2.1 多维可视化标记
**Project View Decorator**
- 文件名右侧显示：● 62
- 颜色随 severity（GREEN / YELLOW / ORANGE / RED）
- Tooltip：Top3 贡献项 + 关键指标（loc、nesting、domains、hotspots count）

**Editor Banner**
- 顶部提示条：Complexity 62 (High) + Top3 因子贡献 + 入口按钮
- Open Radar
- Copy Refactor Prompt
- Export Report (JSON/MD/HTML)

**Gutter Icon（热点级）**
- Top N 热点方法起始行显示 ⚠️
- 点击弹出热点分数、贡献项与建议
- 每文件最多显示 3 个

**ToolWindow：Complexity Radar**
- Tab 1：Top Files（排序：score / priority / lastModified）
- Tab 2：By Module / Package（聚合：平均分、最大分、红文件占比）
- Tab 3：Changed Files（VCS 联动：只看当前变更树）

**过滤器**
- language（Java/Kotlin）
- severity
- score range
- module/package
- exclude/whitelist 命中状态
- mode（Fast/Accurate）

### 2.2 报告与协作
- Export JSON / HTML / Markdown
- 支持生成 complexity-radar-report/ 目录
- 支持 CI 用：只输出 changed files 报告

### 2.3 AI 重构辅助（Next-Gen，默认不联网）
- ToolWindow 详情页：Optimize with AI
- 组装 Prompt（hotspots 代码片段 + factor 解释 + 约束）

**动作**
- Copy Prompt
- Save Prompt to file
- Open External Command

## 3. 复杂度模型设计（认知负担 2.1）

### 3.1 关键原则
- 可比性：0–100 分数跨文件稳定可比
- 可解释：每个 factor 必须能说明扣分原因
- 抗误杀：大 when / 纯 data / constants / 生成代码要降噪
- 性能优先：默认不 resolve，必要时按需 Accurate

### 3.2 总公式
采用“因子归一化 + 权重聚合 + 乘子调整”的管道。

```text
baseScore = 100 * sum(Weight_i * normalize_i(raw_i))
totalScore = clamp(0, 100, baseScore * prod(multipliers))
```

### 3.3 Factors（维度与 raw 指标）
**默认权重（可由 radar.yaml 覆盖）**
| 维度 | 权重 |
| --- | --- |
| Size | 0.20 |
| Control Flow | 0.25 |
| Nesting | 0.30 |
| Domain Coupling | 0.15 |
| Readability | 0.10 |

**A. Size（体量）**
- effectiveLOC（有效行：排除空行/注释）
- statementCount（有效语句/顶层表达式计数）
- functionCount（方法/函数数量）
- typeCount（同文件定义的类/对象数）

**B. Control Flow（控制流）**
- branchCount：if/when/switch 条目数
- loopCount：for/while/do
- tryCatchCount：catch 数
- ternaryCount：?:（Java conditional、Kotlin elvis 可选）
- logicalOpCount：&& / ||

**降权特例**
- Kotlin when 穷举且分支单行时，branchCount 以 when_simple_weight 计入

**C. Nesting（嵌套深度）**
- maxBlockDepth：控制结构块深度
- maxLambdaDepth：lambda 嵌套深度
- nestingPenalty：累进惩罚值

**D. Domain Coupling（领域耦合）**
- domainTagsHit：命中标签集合（UI/Network/Storage/Serialization/DI/Concurrency/System）
- domainCount = |domainTagsHit|
- Fast：import + 类型名/包前缀启发式
- Accurate：字段/构造参数类型分析（必要时局部 resolve）
- domainCount >= 3：God Class Risk 加罚
- domainCount >= 4：大幅加罚

**E. Readability（可读性）**
- maxFunctionLOC（最长函数有效行）
- maxParamCount（最大参数个数）
- todoCount（TODO/FIXME）
- emptyCatchCount（空 catch）
- kotlinBangBangCount（!! 次数）
- magicNumberCount（可选）

## 4. 归一化函数（保证 0–100 可比、可调）

### 4.1 统一映射策略
- raw → [0..1]
- 分段线性 + 极端值压缩，避免大文件一片红

**分段线性**
```text
normalizePiecewise(x, points)
points = [(x0,y0), (x1,y1), ...]
```

**对大值压缩（可选）**
```text
normalize = min(1, log(1+x/k) / log(1+max/k))
```

### 4.2 默认归一化（可在 radar.yaml 覆盖）
**A. Size**
- effectiveLOC points：[(0,0), (200,0.15), (400,0.35), (800,0.70), (1400,1.0)]
- statementCount points：[(0,0), (80,0.2), (200,0.55), (400,0.85), (700,1.0)]
- functionCount points：[(0,0), (10,0.1), (25,0.45), (45,0.8), (70,1.0)]
- typeCount points：[(1,0), (2,0.2), (4,0.6), (6,1.0)]
- SizeNormalize = avg(LOC, statement, functions, types)

**B. Control Flow**
- controlFlowRaw = branch + loop*1.2 + catch*1.0 + ternary*0.7 + logicalOps*0.4
- points：[(0,0), (20,0.2), (60,0.6), (120,0.9), (200,1.0)]

**C. Nesting（指数级痛感）**
- nestingPenalty = Σ_{depth=3..maxDepth} (2^(depth-3))
- points：[(0,0), (1,0.15), (3,0.35), (7,0.65), (15,0.9), (31,1.0)]
- maxDepth = max(maxBlockDepth, maxLambdaDepth)

**D. Domain Coupling**
- domainCount points：[(0,0), (1,0.05), (2,0.25), (3,0.55), (4,0.8), (5,1.0)]
- GodClassBonus：domainCount >= 3 时 +0.08（封顶 1.0）

**E. Readability**
- maxFunctionLOC points：[(0,0), (40,0.15), (80,0.45), (140,0.8), (220,1.0)]
- maxParamCount points：[(0,0), (4,0.1), (7,0.45), (10,0.8), (14,1.0)]
- smellsRaw = todo*0.03 + emptyCatch*0.12 + bangbang*0.02 + magicNumber*0.01
- points（smellsRaw）：[(0,0), (0.5,0.2), (1.5,0.55), (3.0,0.85), (5.0,1.0)]
- ReadabilityNormalize = avg(maxFuncLOC, maxParamCount, smellsRaw)

## 5. Multipliers（Android 特化乘子 + 降权白名单）

### 5.1 作用点
- 乘子在 baseScore 后施加，用于类型/用途整体调节，降低误杀

### 5.2 默认乘子
- Activity / Fragment：×1.15
- Application / ContentProvider：×1.25
- ViewModel：×1.10
- Composable 主体：×0.80
- data/entity/dto/model/vo/po：×0.30
- constants/keys：×0.40
- generated：直接排除

### 5.3 乘子细化（可选）
```text
multiplierOnFactor: Nesting for Composable = 0.7
```

## 6. Hotspots 与 Gutter 规则

### 6.1 Hotspot 定义（方法/函数级）
- methodNestingNormalize
- methodControlFlowNormalize
- methodLengthNormalize
- methodDomainCount（可选）

```text
methodScore = 100 * (0.45*nesting + 0.35*controlFlow + 0.20*length)
```

### 6.2 Hotspot 选择与展示
- 每文件选 Top 5 hotspots 存入结果
- Gutter 仅展示 Top 3（methodScore >= hotspot.gutterThreshold，默认 75）
- 点击 ⚠️：展示贡献项与快速建议
- 提供按钮：Extract to function（仅导航）

## 7. 团队配置 radar.yaml（Schema、优先级、热更新）

### 7.1 配置优先级
- 项目根 radar.yaml（团队基线）
- 模块级 module/radar.yaml（可选覆盖）
- 用户本机 Settings（默认只允许 UI 偏好）

### 7.2 radar.yaml Schema
```yaml
radar:
  version: 2.1

  thresholds:
    green: [0, 25]
    yellow: [26, 50]
    orange: [51, 75]
    red: [76, 100]

  mode:
    default: fast
    accurate_on_open_file: true
    accurate_on_top_red_files: 20

  weights:
    size: 0.20
    control_flow: 0.25
    nesting: 0.30
    domain_coupling: 0.15
    readability: 0.10

  normalization:
    size:
      loc_points: [[0,0],[200,0.15],[400,0.35],[800,0.70],[1400,1.0]]
    nesting:
      penalty_points: [[0,0],[1,0.15],[3,0.35],[7,0.65],[15,0.9],[31,1.0]]

  rules:
    kotlin_when_simple_weight: 0.25

  multipliers:
    - match: "extends:android.app.Activity"
      value: 1.15
    - match: "extends:androidx.fragment.app.Fragment"
      value: 1.15
    - match: "extends:android.app.Application"
      value: 1.25
    - match: "extends:android.content.ContentProvider"
      value: 1.25
    - match: "annotation:Composable"
      value: 0.80
      on_factors: ["nesting"]
    - match: "name:*Dto*|*Entity*|*Model*|*VO*|*PO*"
      value: 0.30
    - match: "name:*Constants*|*Keys*|*Const*"
      value: 0.40

  exclusions:
    - "**/build/**"
    - "**/generated/**"
    - "**/kapt/**"
    - "**/ksp/**"
    - "**/*Test.*"
    - "**/*AndroidTest.*"

  hotspot:
    gutter_threshold: 75
    max_gutter_per_file: 3
    max_hotspots_per_file: 5
```

### 7.3 热更新
- 监听 radar.yaml 变更（VFS listener）
- 更新配置缓存
- 清理结果缓存（按 version/hash）
- 触发增量重算（优先打开文件与 Project View 可见文件）

## 8. 核心技术实现（JetBrains 平台 + PSI）

### 8.1 架构分层
- core：纯 Kotlin（无 IDE 依赖）
- adapters：语言适配（依赖 PSI 类型）
- ide：ProjectView / ToolWindow / Banner / Gutter / Listeners / Caches
- integration：VCS + AI prompt
- settings：yaml 解析 + state

### 8.2 统一抽象
```kotlin
interface LanguageAdapter {
  fun supports(file: PsiFile): Boolean
  fun summarize(file: PsiFile, mode: AnalyzeMode): FileAstSummary
  fun hotspots(file: PsiFile, mode: AnalyzeMode): List<Hotspot>
}
```

**FileAstSummary（语言无关）**
- effectiveLOC, statementCount, functionCount, typeCount
- controlFlow metrics（branch/loop/catch/ternary/logicalOps）
- maxBlockDepth, maxLambdaDepth, nestingPenalty
- domainTagsHit + evidence
- readability metrics
- androidKind + composableHit
- smell hits

### 8.3 PSI 遍历要点
**Kotlin（KtFile）**
- 控制流：KtIfExpression / KtWhenExpression / KtForExpression / KtWhileExpression / KtTryExpression / KtBinaryExpression(&&||)
- lambda：递归处理 KtCallExpression 的 lambda 参数（KtLambdaExpression）
- when 简化：whenEntry 表达式是否属于简单表达式集合

**Java（PsiJavaFile）**
- PsiIfStatement / PsiSwitchStatement / PsiForStatement / PsiWhileStatement / PsiDoWhileStatement / PsiTryStatement / PsiConditionalExpression
- 逻辑运算：PsiPolyadicExpression / PsiBinaryExpression

### 8.4 Domain Coupling（Fast/Accurate）
- Fast：import 匹配 + 关键类型名启发式（ApiService/Dao/Repository）
- Accurate：字段/构造参数类型抽取，必要时局部 resolve

## 9. 性能与缓存（避免卡顿）

### 9.1 三层缓存
**L1 Memory Cache**
- ConcurrentHashMap<VirtualFile, CachedResult>
- 仅存最近使用 / 打开文件 / ToolWindow 可见列表
- LRU 控制容量

**L2 VirtualFile UserData**
- 只存 ScoreDigest：score、severity、stamp、topContribHash、mode
- Project View 装饰 O(1) 读取

**L3 Disk Cache**
- .idea/complexity-radar/cache-v2.1/ JSON 摘要
- 可选进阶：FileBasedIndex

### 9.2 增量触发
- PsiTreeChangeListener：编辑触发，debounce 800ms
- BulkFileListener/VFS：批量变更进入队列
- 计算必须在 ReadAction.nonBlocking 中执行
- 批量分析禁止全量 resolve

### 9.3 渐进式展示
- 首次仅分析：打开文件 + Project View 可见文件 + Changed Files
- 后台低优先级扩展（可配置）

## 10. UI 集成点
- ProjectViewDecorator
- ToolWindowFactory + UI（Table + filter）
- EditorNotifications Provider（Banner）
- LineMarkerProvider（Gutter Hotspot）
- Actions（Export/Copy Prompt/Open ToolWindow/Toggle mode）
- Settings Configurable（UI 配置 + yaml 状态提示）

## 11. VCS 联动（Changed Files + Churn 优先级）

### 11.1 Changed Files
- 使用 IDE VCS API 获取当前变更列表
- ToolWindow Tab3 展示并优先分析

### 11.2 Churn（变更频率）
- 最近 N 次提交（默认 50）中出现次数 → churnCount
- churnNormalize points：[(0,0),(2,0.2),(5,0.5),(10,0.8),(20,1.0)]

```text
priority = totalScore * (1 + α * churnNormalize)
```

- 默认 α = 0.5

## 12. AI Prompt 组装规范

**Prompt 内容结构**
- 背景：本文件评分、主要贡献项
- 热点：Top 1~3 hotspot 代码片段
- 约束：行为不变、public API 不改签名、建议增加单元测试、优先降低嵌套/拆分职责/提炼函数
- 输出要求：重构步骤、重构后代码或 patch、风险点说明

**动作支持**
- Copy Prompt
- Save as complexity-radar-prompts/<file>.md
- Run External Command

## 13. 工程结构（最终落地版）
```text
complexity-radar/
├── core/
│   ├── model/
│   ├── scoring/
│   ├── rules/
│   └── export/
├── adapters/
│   ├── common/
│   ├── java/
│   ├── kotlin/
│   └── domain/
├── ide/
│   ├── ui/
│   ├── actions/
│   ├── listeners/
│   ├── cache/
│   └── vcs/
├── settings/
│   ├── yaml/
│   └── state/
└── integration/
    └── ai/
```

## 14. 可测试性策略

### 14.1 Core 引擎测试（JUnit）
- normalize 输出稳定
- weights/multipliers 应用正确
- radar.yaml 覆盖规则生效
- when_simple/whitelist 降权生效

### 14.2 PSI 适配器测试（IntelliJ Test Framework）
- Kotlin：验证 let { run { } } lambda depth
- Java：验证 switch/try/catch 计数
- Domain Fast/Accurate 对比

### 14.3 IDE 集成测试
- Project decorator 是否展示 digest
- ToolWindow 列表跳转
- Gutter 只出现 Top N
- yaml 热更新触发刷新

## 15. 发布与兼容性策略（Android Studio）
- 支持 Android Studio（近期 2–3 个大版本）与 IntelliJ（可选）
- Kotlin PSI API 使用稳定类型，避免内部 API
- 避免强依赖 FileBasedIndex（先用 disk cache）
