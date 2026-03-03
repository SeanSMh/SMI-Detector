# Complexity Radar 插件实现技术方案

本文是在现有设计文档 [code_an.md](./code_an.md) 基础上的工程化落地方案，目标是把“复杂度雷达”实现为可在 Android Studio 和 IntelliJ IDEA 中运行、可发布、可迭代的 IntelliJ Platform 插件。

## 1. 实施目标

- 输出稳定的文件级复杂度评分（0-100）、维度贡献、热点方法与规则证据
- 在 IDE 内提供四个主入口：Project View、Editor Banner、Gutter、ToolWindow
- 支持团队配置 `radar.yaml`，并在修改后自动热更新
- 支持本地导出（JSON / Markdown / HTML）与 AI Prompt 组装
- 首版优先保证“分析准确 + UI 不打扰 + 编辑不卡顿”，不在首版引入高风险跨文件全量分析

## 2. 平台与兼容策略

### 2.1 目标平台

- 插件类型：标准 IntelliJ Platform 插件
- 适配 IDE：IntelliJ IDEA、Android Studio
- 语言覆盖：Java、Kotlin
- 首版兼容策略：先锁定一个 IntelliJ Platform 基线做稳定交付，再通过插件校验逐步扩大兼容范围

建议基线策略：

- 以“你们必须支持的最低 Android Studio 版本”对应的 IntelliJ Platform 版本作为基线
- 如果基线是 2024.2 及以上，构建采用 IntelliJ Platform Gradle Plugin 2.x，JDK 使用 Java 21
- 如果必须兼容更早平台，再单独回退基线，不要首版就同时跨多代平台

这样做的原因是 Android Studio 通常滞后于 IntelliJ IDEA，先确定最低支持基线，才能避免插件 API 和字节码版本反复返工。

### 2.2 插件依赖边界

首版只依赖 IntelliJ 平台通用 API 和 Java/Kotlin 语言插件能力，避免绑定 Android Studio 私有 API。这样可以使用同一个插件产物覆盖 IDEA 和 Android Studio。

`plugin.xml` 的硬依赖建议：

- `com.intellij.modules.platform`
- `com.intellij.modules.java`
- `org.jetbrains.kotlin`

不建议首版硬依赖 Android 插件或 Git 插件。Git 相关能力做成“有则增强、无则降级”的可选扩展。

### 2.3 工程形态

首版建议采用“单插件模块 + 包分层”，不要一开始做 Gradle 多模块。

原因：

- IntelliJ 插件开发本身已经有类加载、代码插桩、沙箱运行的复杂度
- 多模块会增加调试、测试、打包和类路径问题
- 当前项目尚未有代码资产，先用包分层就足够支撑演进

推荐目录：

```text
code_analy_plugin/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── src/
│   ├── main/
│   │   ├── kotlin/com/yourorg/complexityradar/
│   │   │   ├── core/
│   │   │   ├── adapters/
│   │   │   ├── ide/
│   │   │   ├── integration/
│   │   │   └── settings/
│   │   └── resources/
│   │       ├── META-INF/plugin.xml
│   │       └── icons/
│   └── test/
│       ├── kotlin/
│       └── resources/
└── docs/
```

后续如果需要把分析逻辑复用到 CLI/CI，再把 `core` 抽成独立 JVM 模块。

## 3. 构建与发布方案

### 3.1 Gradle 基线

使用 IntelliJ Platform Gradle Plugin 2.x。

需要覆盖的核心任务：

- `runIde`：本地启动开发沙箱
- `buildPlugin`：生成可安装 ZIP
- `verifyPlugin`：做二进制兼容校验
- `test`：单元与平台测试

CI 至少要有两组运行矩阵：

- IntelliJ IDEA 基线版本
- Android Studio 对应版本

验收标准：

- 构建通过
- 平台测试通过
- Plugin Verifier 无阻断级错误

### 3.2 plugin.xml 扩展点

首版需要注册的核心扩展：

- `ToolWindowFactory`
- `ProjectViewNodeDecorator`
- `LineMarkerProvider`
- `EditorNotificationProvider`
- `AnAction`（打开面板、导出、复制 Prompt、切换分析模式）
- `Configurable`（设置页）
- 项目级监听器（VFS、PSI、文件编辑器事件）

注册原则：

- 所有 UI 扩展都只做“读取缓存 + 请求刷新”，不在扩展点回调里做重分析
- 分析任务统一交给项目服务调度

## 4. 核心运行时架构

### 4.1 总体分层

#### `core`

纯 Kotlin 业务层，不依赖 IntelliJ UI API。

职责：

- 领域模型
- 归一化函数
- 权重聚合
- 乘子规则
- 热点选择
- 导出 DTO

#### `adapters`

面向 PSI 的语言适配层。

职责：

- Java / Kotlin 文件遍历
- 原始统计提取
- 热点级统计
- Domain Coupling 证据提取

#### `ide`

IDE 集成层。

职责：

- Project View 展示
- Editor Banner
- Gutter 标记
- ToolWindow 数据与交互
- 导航、刷新、异步任务调度

#### `integration`

外部能力集成层。

职责：

- Changed Files
- 可选 Git churn
- AI Prompt 组装
- 报告导出

#### `settings`

配置层。

职责：

- `radar.yaml` 解析
- 项目级缓存
- 用户 UI 偏好
- 配置变更监听

### 4.2 关键服务

首版建议以“项目级服务 + 少量轻量对象”为主，不要过度引入事件总线抽象。

#### `ComplexityRadarProjectService`

总调度入口，项目级单例。

职责：

- 接收“需要分析某文件”的请求
- 做去重、限流、模式选择
- 读取配置
- 调用 `LanguageAdapter`
- 调用 `ComplexityScorer`
- 写入缓存
- 广播结果更新事件

#### `RadarConfigService`

负责配置解析与缓存。

职责：

- 解析项目根 `radar.yaml`
- 合并模块级覆盖
- 暴露最终 `RadarConfig`
- 监听配置文件变化并触发缓存失效

#### `AnalysisScheduler`

负责异步调度。

职责：

- 将高频编辑事件合并
- 按优先级队列执行
- 区分前台文件与后台预热文件

实现建议：

- 使用 `MergingUpdateQueue` 聚合重复请求
- 分析执行放入 `ReadAction.nonBlocking`
- 用 `NonBlockingReadAction` 结束后切回 EDT 更新 UI

#### `ComplexityResultStore`

统一的结果存储门面。

职责：

- 管理 L1 / L2 / L3 缓存
- 提供 `ScoreDigest` 快速读取接口
- 提供完整 `ComplexityResult` 读取接口
- 根据文件指纹与配置版本决定是否命中缓存

#### `RadarUiRefreshService`

隔离 UI 刷新逻辑，防止业务层直接操作各个组件。

职责：

- 刷新 Project View
- 刷新 Editor notifications
- 刷新 ToolWindow 数据模型
- 触发 Gutter 重算

#### `ExportService`

负责 JSON / Markdown / HTML 导出，不参与分析。

#### `AiPromptService`

根据结果与热点拼装 Prompt。

## 5. 领域模型设计

建议先把模型固定，后续 UI 和导出才能稳定开发。

核心模型：

- `AnalyzeMode`
- `Severity`
- `DomainTag`
- `FactorType`
- `FactorContribution`
- `RuleEvidence`
- `Hotspot`
- `FileAstSummary`
- `ComplexityResult`
- `ScoreDigest`
- `RadarConfig`

关键约束：

- `FileAstSummary` 只放语言无关统计，不携带 PSI 引用，避免缓存中残留重对象
- `ComplexityResult` 必须可序列化，方便落盘
- `ScoreDigest` 只保留 Project View 所需最小信息，保证读取 O(1)

推荐结构：

```kotlin
data class ComplexityResult(
  val fileUrl: String,
  val mode: AnalyzeMode,
  val score: Int,
  val severity: Severity,
  val contributions: List<FactorContribution>,
  val hotspots: List<Hotspot>,
  val evidences: List<RuleEvidence>,
  val computedAt: Long,
  val contentHash: String,
  val configHash: String
)
```

## 6. 分析管线

### 6.1 单文件分析流程

```text
File Trigger
  -> Config Resolve
  -> Exclusion Check
  -> Adapter Select
  -> AST Summary
  -> Score Compute
  -> Multiplier Apply
  -> Hotspot Compute
  -> Cache Persist
  -> UI Refresh
```

具体步骤：

1. 将 `VirtualFile` 转为 `PsiFile`
2. 过滤非 Java/Kotlin 文件、二进制文件、超大文件、排除路径
3. 依据当前上下文选择 `fast` 或 `accurate`
4. 用适配器产出 `FileAstSummary`
5. 用 `ComplexityScorer` 计算分数与因子贡献
6. 生成 `ComplexityResult`
7. 写入 L1/L2/L3 缓存
8. 发布刷新事件

### 6.2 模式选择策略

默认策略：

- 打开文件：优先 `accurate`
- Project View 后台批量：默认 `fast`
- Top 红色文件预热：按配置选择是否升级为 `accurate`

切换原则：

- `accurate` 只做局部、可超时中断的类型解析
- 任一阶段超时后立即回退为 `fast` 结果，不阻塞 UI

建议给 `accurate` 增加单文件时间预算，例如 100-200ms 级别。超过预算直接降级。

## 7. 语言适配器设计

### 7.1 抽象接口

保留原始设计文档中的统一抽象：

```kotlin
interface LanguageAdapter {
  fun supports(file: PsiFile): Boolean
  fun summarize(file: PsiFile, mode: AnalyzeMode): FileAstSummary
  fun hotspots(file: PsiFile, mode: AnalyzeMode): List<Hotspot>
}
```

首版实现两个适配器：

- `KotlinLanguageAdapter`
- `JavaLanguageAdapter`

### 7.2 Kotlin 适配器

基于 Kotlin PSI。

统计内容：

- `KtIfExpression`
- `KtWhenExpression`
- `KtForExpression`
- `KtWhileExpression`
- `KtTryExpression`
- `KtLambdaExpression`
- `KtBinaryExpression`（`&&` / `||`）
- `KtNamedFunction`
- `KtClass` / `KtObjectDeclaration`

关键实现点：

- 用递归 visitor 维护当前 block depth 与 lambda depth
- `when` 简化判定单独做工具函数，避免和主遍历耦合
- `!!`、TODO/FIXME、空 catch 等“味道指标”放在同一次遍历里收集，减少重复扫描

### 7.3 Java 适配器

基于 Java PSI。

统计内容：

- `PsiIfStatement`
- `PsiSwitchStatement`
- `PsiForStatement`
- `PsiWhileStatement`
- `PsiDoWhileStatement`
- `PsiTryStatement`
- `PsiConditionalExpression`
- `PsiPolyadicExpression` / `PsiBinaryExpression`
- `PsiMethod`
- `PsiClass`

### 7.4 Domain Coupling 实现

首版采用两段式：

#### Fast

- 扫描 import
- 扫描显式类型名
- 扫描常见命名模式（如 `ApiService`、`Dao`、`Repository`）

#### Accurate

- 读取字段类型、构造参数类型、继承/实现列表
- 仅对当前文件做局部 resolve
- 禁止递归跨文件深追踪

输出必须保留证据链：

- 命中的 `DomainTag`
- 触发来源（import / type / annotation / naming）
- 命中文本

这样 ToolWindow 和导出报告才有解释性。

## 8. 评分与规则实现

### 8.1 核心组件

建议拆成以下纯业务类：

- `NormalizeFunctions`
- `FactorNormalizer`
- `ComplexityScorer`
- `MultiplierEngine`
- `HotspotSelector`

职责边界：

- `FactorNormalizer` 只负责 raw -> normalized
- `ComplexityScorer` 负责权重聚合与 `FactorContribution`
- `MultiplierEngine` 负责根据文件特征修正分数
- `HotspotSelector` 负责排序、阈值筛选、截断数量

### 8.2 稳定性策略

为保证分数跨文件可比，首版需要固定三件事：

- 默认权重
- 默认归一化点位
- 默认乘子规则

这些都由 `radar.yaml` 覆盖，但插件内必须内置一份版本化默认值。

建议：

- 内置 `RadarConfigDefaults.V2_1`
- `radar.version` 不匹配时给出非阻断警告，并回退兼容字段

## 9. 缓存与性能方案

### 9.1 三层缓存

#### L1：内存缓存

- 类型：`LinkedHashMap` 或基于 Caffeine 的 LRU
- Key：`VirtualFile.url + mode + contentStamp + configHash`
- Value：完整 `ComplexityResult`

用途：

- ToolWindow 明细
- Banner
- Gutter

#### L2：`VirtualFile` 轻摘要

存储：

- `score`
- `severity`
- `mode`
- `stamp`
- `topContribHash`

用途：

- Project View 快速装饰

#### L3：磁盘缓存

建议目录：

- `.idea/complexity-radar/cache-v2.1/`

存储格式：

- 单文件 JSON 摘要

用途：

- IDE 重启后快速恢复
- 避免全量重新扫描

### 9.2 失效策略

以下任一变化都触发失效：

- 文件内容变化
- `radar.yaml` 变化
- 插件版本变化
- 分析模式变化

不要做整仓强制失效。采用“按文件重算 + 后台渐进预热”。

### 9.3 事件来源

首版监听：

- `PsiTreeChangeListener`
- `BulkFileListener`
- `FileEditorManagerListener`

行为原则：

- 编辑时 debounce
- 批量文件变化入队
- 切换编辑器时优先刷新当前文件

## 10. IDE 集成实现

### 10.1 Project View

实现：`ProjectViewNodeDecorator`

策略：

- 仅读取 `ScoreDigest`
- 若摘要不存在，显示空状态，不同步触发分析
- 由后台分析完成后调用 `ProjectView.refresh()`

展示建议：

- 文本尾部追加 `● 62`
- 用 severity 决定前景色
- Tooltip 显示 Top3 因子贡献和关键指标

### 10.2 Editor Banner

实现：`EditorNotificationProvider` + `EditorNotificationPanel`

展示内容：

- `Complexity 62 (High)`
- Top3 因子贡献
- 动作按钮：Open Radar / Copy Refactor Prompt / Export Report

要求：

- Banner 不要在分析中闪烁
- 若结果尚未就绪，显示轻量 loading 或直接不展示

### 10.3 Gutter

实现：`LineMarkerProvider`

策略：

- 只对热点 Top 3 标记
- 只在方法声明起始元素挂 marker
- 标记数据来自缓存，不临时重算

点击行为：

- 弹出方法分数
- 展示主要贡献项
- 提供跳转 ToolWindow 明细动作

### 10.4 ToolWindow

实现：`ToolWindowFactory`

建议 UI：

- 顶部过滤栏
- 中部 `JBTable` / `TreeTable`
- 右侧明细面板或底部详情区

首版先做三页签：

- `Top Files`
- `By Module / Package`
- `Changed Files`

表格模型建议与业务数据分离：

- `TopFilesTableModel`
- `ModuleAggregateTableModel`
- `ChangedFilesTableModel`

点击行后：

- 跳转文件
- 定位热点
- 刷新明细

## 11. 配置体系

### 11.1 配置来源

解析优先级严格遵守设计文档：

1. 项目根 `radar.yaml`
2. 模块级 `module/radar.yaml`
3. 用户本机 UI 偏好

用户本机配置不允许覆盖团队评分规则，只允许控制：

- 是否展示 Project View
- 是否展示 Banner
- 是否展示 Gutter
- 默认排序方式

### 11.2 解析策略

建议用 SnakeYAML 解析为 DTO，再映射为强类型 `RadarConfig`。

解析要求：

- 未知字段告警但不报错
- 缺省字段自动填默认值
- 非法权重、非法区间、非法乘子表达式要回退默认值并提示

### 11.3 热更新

实现流程：

1. 监听 `radar.yaml` 文件变更
2. 重新解析配置
3. 更新 `configHash`
4. 清除相关缓存
5. 先重算当前打开文件和可见节点，再后台扩散

## 12. VCS 与 AI 集成

### 12.1 Changed Files

首版只依赖平台 VCS API 获取当前变更，不绑定具体 Git 插件实现。

目标：

- ToolWindow 显示当前变更文件
- 优先把这些文件放入分析队列

### 12.2 Churn

建议放到第二阶段做成可选增强。

原因：

- 需要依赖 Git 能力
- 会引入更多兼容性和性能问题
- 对首版“让插件可用”不是阻断项

落地方式：

- 无 churn 时，`priority = totalScore`
- 有 churn 且可用时，`priority = totalScore * (1 + alpha * churnNormalize)`

### 12.3 AI Prompt

`AiPromptService` 只负责“组装文本”，不直接联网。

输出结构：

- 文件总体评分与主要贡献项
- Top 1-3 热点代码片段
- 约束条件
- 期望输出格式

动作：

- 复制到剪贴板
- 保存为项目内文件
- 调用外部命令

## 13. 导出方案

首版导出目录建议：

- `<project>/complexity-radar-report/`

支持三种格式：

- `report.json`
- `report.md`
- `report.html`

实现方式：

- JSON：直接序列化模型
- Markdown：模板渲染
- HTML：内嵌简单 CSS 的静态模板

导出边界：

- 支持全项目
- 支持仅 changed files
- 支持单文件导出

## 14. 测试策略

### 14.1 单元测试

覆盖 `core` 纯逻辑：

- 归一化函数
- 权重聚合
- 乘子规则
- 热点排序
- 配置合并

这些测试不依赖 IDE 沙箱，执行速度快，应该成为主力测试集。

### 14.2 平台测试

覆盖 IntelliJ 集成行为：

- Java/Kotlin PSI 统计是否符合预期
- Project View 装饰是否读取摘要而非触发分析
- Banner / Gutter 是否根据缓存展示
- `radar.yaml` 变更后是否触发重算

建议准备一组固定测试样例文件：

- 深嵌套 Kotlin 文件
- 大型 `when` 文件
- 纯 DTO / constants 文件
- 混合 UI + Network + Storage 文件

### 14.3 性能测试

至少验证三件事：

- 打开 1k+ Java/Kotlin 文件的工程后不会卡主线程
- 连续编辑当前文件时不会频繁重复分析
- ToolWindow 打开后不会触发全量 resolve

## 15. 分阶段实施计划

### 阶段 1：最小可用内核

- 建立插件工程
- 完成 `RadarConfig` 默认值与 `radar.yaml` 解析
- 完成 Java/Kotlin `FileAstSummary`
- 完成评分计算与缓存

交付结果：

- 可在动作里手动触发分析并看到结果

### 阶段 2：可见化 MVP

- Project View 气泡
- ToolWindow `Top Files`
- Editor Banner

交付结果：

- 用户在 IDE 内能看到文件分数并查看明细

### 阶段 3：交互增强

- Gutter hotspot
- `Changed Files`
- 导出 JSON / Markdown / HTML
- Copy Prompt

交付结果：

- 有基本协作与重构辅助能力

### 阶段 4：高级能力

- Accurate 模式优化
- 可选 churn
- 更细粒度聚合视图
- 外部命令集成

## 16. 关键风险与规避

### 风险 1：编辑卡顿

规避：

- 所有分析异步化
- 高频事件合并
- `accurate` 设超时并可降级

### 风险 2：Kotlin PSI 遍历成本过高

规避：

- 单次遍历尽量收集所有指标
- 避免多轮重复 visitor
- 只在必要时做局部 resolve

### 风险 3：Project View 装饰导致抖动

规避：

- 装饰器只读摘要
- 刷新走批量触发

### 风险 4：Android Studio 兼容问题

规避：

- 不依赖 Android 私有 API
- 用 Plugin Verifier 对 IntelliJ IDEA 和 Android Studio 分别做校验
- 将可选能力放到可降级扩展中

## 17. 推荐的首批代码清单

如果按这个方案正式开工，第一批应该优先实现这些类：

- `ComplexityRadarProjectService`
- `RadarConfigService`
- `ComplexityScorer`
- `MultiplierEngine`
- `ComplexityResultStore`
- `KotlinLanguageAdapter`
- `JavaLanguageAdapter`
- `ProjectViewScoreDecorator`
- `ComplexityEditorNotificationProvider`
- `ComplexityLineMarkerProvider`
- `ComplexityRadarToolWindowFactory`

这批类完成后，插件就会具备“能算、能存、能展示”的闭环。

## 18. 外部参考

以下官方资料用于校准本方案的插件实现边界：

- JetBrains Project View 文档：<https://plugins.jetbrains.com/docs/intellij/project-view.html>
- JetBrains UAST 文档：<https://plugins.jetbrains.com/docs/intellij/uast.html>
- JetBrains Listeners 文档：<https://plugins.jetbrains.com/docs/intellij/plugin-listeners.html>
- JetBrains PSI Files 文档：<https://plugins.jetbrains.com/docs/intellij/psi-files.html>
- JetBrains Plugins Targeting IntelliJ Platform-Based IDEs：<https://plugins.jetbrains.com/docs/intellij/dev-alternate-products.html>
- JetBrains Incompatible Changes 2025.*：<https://plugins.jetbrains.com/docs/intellij/api-changes-list-2025.html>
- JetBrains Gradle Plugin 文档：<https://plugins.jetbrains.com/docs/intellij/developing-plugins.html>
