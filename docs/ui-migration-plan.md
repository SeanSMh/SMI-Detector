# Complexity Radar UI 迁移落地方案

本文档用于指导 `Complexity Radar` 插件的 UI 迁移。迁移目标不是替换现有业务逻辑，而是在保留当前分析能力、数据结构、缓存机制和事件流的前提下，将 ToolWindow 的界面结构、主题色和按钮体系迁移为设计参考项目 `/Users/sqb/Downloads/shit-mountain-detector-ui` 的风格。

## 1. 迁移目标

- 迁移设计参考项目中的右侧插件面板 UI 结构
- 迁移主题色、视觉层级、按钮样式和状态栏风格
- 压缩当前界面的冗余层级，降低“调试面板感”
- 保持现有分析逻辑、服务调用、数据模型和导出逻辑不变

## 2. 非目标

- 不引入 React、JCEF 或 WebView
- 不替换 IntelliJ 原生 Swing / JB UI 实现
- 不修改复杂度分析逻辑
- 不修改 `ComplexityResult`、评分公式、缓存格式、导出结构
- 不迁移设计参考项目中的“假编辑器区域”

## 3. 设计参考范围

参考项目：`/Users/sqb/Downloads/shit-mountain-detector-ui`

仅迁移以下内容：

- 右侧插件面板整体结构
- Header 视觉形式
- 主按钮 / 次按钮 / 图标按钮样式
- `Overview / Issues` tab 结构
- `Project / Current File` scope toggle 形式
- 深色主题色板
- Footer 状态栏样式

不迁移以下内容：

- 左侧编辑器 mock 区域
- React 动画实现方式
- Tailwind / Recharts / Lucide 的技术实现
- 与当前插件实际数据模型不匹配的演示内容

## 4. 当前项目 UI 问题

当前 ToolWindow 已具备完整功能，但视觉结构偏重，主要问题如下：

- 外层使用 `Current File / Project` 双页签，页面切换成本较高
- 顶部操作区按钮分散，缺少单一主操作
- 卡片、边框、说明文案较多，信息密度偏高
- 一部分状态信息在多个区域重复表达
- 按钮样式仍偏传统 Swing 风格，与参考设计的产品化面板风格差距明显

## 5. 核心迁移原则

### 5.1 逻辑不变，界面重组

保留当前逻辑层：

- `ComplexityRadarProjectService`
- `ComplexityResult`
- `FocusedViewSnapshot`
- 当前分析、刷新、导出、导航行为

只改 UI 的组织方式、视觉样式和交互呈现。

### 5.2 从“工程面板”改为“产品面板”

将当前偏工具化、偏调试型的卡片布局，改造为更聚焦的单栏仪表盘结构：

- 顶部统一 Header
- 主工具栏只保留一个主操作
- 主体内容改为 `Overview / Issues`
- 范围切换内收为 `Project / Current File`
- 状态集中到底部 Footer

### 5.3 不做假 1:1 还原

参考设计中存在一些 demo 数据表达，当前插件无法完全对应：

- 设计稿雷达图是 6 轴
- 当前插件真实雷达图是 5 轴
- 设计稿 Issues 树中每条问题都具备完整 line / severity / type 展示
- 当前插件只有 `hotspots` 和 `evidences` 可直接支撑问题项展示

因此本次迁移采用“视觉还原 + 数据适配”，而不是“结构字段完全一致”。

## 6. UI 信息架构改造方案

## 6.1 现状

当前 ToolWindow：

- 顶部北区操作栏
- 外层 tab：`Current File / Project`
- 内部多个卡片和双栏布局

## 6.2 目标结构

迁移后 ToolWindow 建议结构：

1. Header
2. Toolbar
3. Tab：`Overview / Issues`
4. Scope Toggle：`Project / Current File`
5. 主内容区
6. Footer Status

### 6.2.1 Header

目标形式：

- 左侧：插件图标 + 标题
- 右侧：`Settings` 图标按钮 + `More` 图标按钮

当前功能映射：

- `Settings`：打开设置页
- `More`：承载 `Export`、`Toggle Gutter` 等次要动作

### 6.2.2 Toolbar

目标形式：

- 一个主按钮：`Scan Project`
- 一个次按钮：`Refresh`

当前行为映射：

- `Scan Project` -> 现有 `Analyze Project`
- `Refresh` -> 现有当前文件刷新逻辑

原则：

- 主按钮只保留一个，突出主要任务
- 次要操作不与主按钮并列堆叠

### 6.2.3 主体 Tab

目标形式：

- `Overview`
- `Issues`

说明：

- 替换当前最外层 `Current File / Project`
- 避免页面层级嵌套过深

### 6.2.4 Scope Toggle

目标形式：

- `Project`
- `Current File`

说明：

- 由内容范围切换替代当前外层 tab 切换
- 不改变底层数据来源，只改变展示切面

### 6.2.5 Overview 页面

保留当前功能，但用参考设计的布局表达：

- Hero score card
- Radar chart card
- 核心摘要文案
- 必要时展示简化后的关键指标

需要压缩的内容：

- 冗余标题
- 重复状态文案
- 过多信息块

### 6.2.6 Issues 页面

使用现有数据做 UI 适配，不扩展逻辑能力：

- 一级节点：文件
- 二级节点：热点 / 证据项
- 支持 severity 颜色区分
- 点击节点时跳转文件或热点位置

数据来源建议：

- `topFiles`
- `hotspots`
- `evidences`

说明：

- 该页面重点是迁移“树状问题视图”的视觉结构
- 首版不要求与参考设计的 demo 数据完全一致

### 6.2.7 Footer

目标形式：

- 左侧：扫描状态 / 完成状态
- 右侧：版本号或轻量信息

原则：

- 将运行状态统一收口到底部
- 避免状态同时出现在头部、卡片内和中间区域

## 7. 按钮体系迁移方案

按钮是本次迁移的重点之一。当前项目的按钮样式偏传统，需要统一重构为三类按钮。

### 7.1 Primary Button

用途：

- `Scan Project`

视觉要求：

- 实心强调色背景
- 白色或高对比文本
- Hover 加深
- Disabled 状态弱化

### 7.2 Secondary Button

用途：

- `Refresh`

视觉要求：

- 深底或透明底
- 描边
- Hover 时轻微背景提升
- 与主按钮形成明显层级差

### 7.3 Icon Button

用途：

- `Settings`
- `More`

视觉要求：

- 无边框或弱边框
- Hover 背景高亮
- 图标尺寸一致
- 点击区域足够但不显臃肿

### 7.4 收口策略

以下动作不再以主面板大按钮直接暴露：

- `Export`
- `Toggle Gutter`

建议收纳到 `More` 菜单中。

## 8. 主题色迁移方案

参考设计项目的主色板如下：

- 主背景：`#1E1F22`
- 次背景：`#2B2D30`
- 边框：`#393B40`
- 主文本：`#DFE1E5`
- 次文本：`#A9B7C6`
- 弱文本：`#6F737A`
- 主强调色：`#A87B44`
- 高危色：`#8B4513`
- 警告色：`#CD853F`
- 安全色：`#57965C`

落地要求：

- 不直接硬编码为单一暗色模式
- 必须通过 `JBColor(light, dark)` 封装
- 统一抽取为一个主题 token 文件，避免颜色分散在多个组件中

建议新增：

- `ide/ui/UiThemeTokens.kt`

负责集中管理：

- 背景色
- 文本色
- 边框色
- 按钮色
- 状态色
- 列表 hover / selected 色

## 9. 现有代码映射策略

## 9.1 保持不变的层

- `core/`
- `adapters/`
- `integration/`
- `settings/` 中的数据读写逻辑
- `ide/services/` 中的调度和刷新逻辑

## 9.2 主要改造层

- `src/main/kotlin/com/sqb/complexityradar/ide/toolwindow/ComplexityRadarToolWindowFactory.kt`
- `src/main/kotlin/com/sqb/complexityradar/ide/toolwindow/RadarChartPanel.kt`
- `src/main/kotlin/com/sqb/complexityradar/ide/toolwindow/LoadingOverlayPanel.kt`
- `src/main/kotlin/com/sqb/complexityradar/ide/ui/PoopScoreVisuals.kt`

## 9.3 可选同步改造层

- `src/main/kotlin/com/sqb/complexityradar/ide/settings/ComplexityRadarConfigurable.kt`
- `src/main/kotlin/com/sqb/complexityradar/ide/editor/ComplexityEditorNotificationProvider.kt`

说明：

- 设置页和 Editor Banner 不是本次主路径，但建议在 ToolWindow 改造稳定后做视觉统一

## 10. 推荐代码拆分方式

当前 `ComplexityRadarToolWindowFactory.kt` 体量较大，建议在迁移前先做 UI 结构拆分。

建议拆分为：

- `ComplexityRadarToolWindowFactory`
- `ComplexityRadarDashboardPanel`
- `RadarHeaderPanel`
- `RadarToolbarPanel`
- `RadarOverviewPanel`
- `RadarIssuesPanel`
- `RadarFooterPanel`
- `RadarActionFactory`
- `UiThemeTokens`

目标：

- 将状态逻辑和布局逻辑解耦
- 降低后续样式调整成本
- 避免一个文件承载全部 UI 实现

## 11. 分阶段实施方案

### Phase 1：结构收缩

目标：

- 拆 ToolWindow 大文件
- 抽主题 token
- 重构按钮工厂

产出：

- 新的公共 UI 基础层
- 不改变行为的前提下，完成视觉改造基础设施

### Phase 2：顶部结构和按钮体系迁移

目标：

- 落地 Header
- 落地 Toolbar
- 完成主按钮 / 次按钮 / 图标按钮替换

产出：

- 操作区从“分散工具条”改为“主次明确的产品面板”

### Phase 3：主体结构迁移

目标：

- 将当前外层 `Current File / Project` 改为 `Overview / Issues`
- 增加 `Project / Current File` scope toggle
- Overview 页面完成主题色和布局对齐

产出：

- 与参考设计一致的主体结构

### Phase 4：Issues 视图迁移

目标：

- 用现有数据组装树状问题视图
- 完成 severity chip、节点层级和交互跳转

产出：

- 视觉上接近参考设计的 `Issues` 页面

### Phase 5：统一收尾

目标：

- Footer 状态栏统一
- Loading 样式统一
- Settings / Banner 可选统一

产出：

- 整体视觉风格一致

## 12. 验收标准

迁移完成后，应满足以下标准：

- 分析逻辑和输出结果未发生行为变化
- 主按钮只保留一个，操作层级清晰
- ToolWindow 结构收敛为单面板仪表盘风格
- 页面视觉明显接近参考设计项目右侧面板
- 冗余卡片、重复说明和重复状态信息显著减少
- 在 IntelliJ 亮色 / 暗色主题下均能正常显示
- 现有项目分析、导出、刷新、跳转功能不回归

## 13. 风险与注意事项

- 不能因为追求参考设计还原度而硬改数据模型
- 不能把暗色值直接写死，必须兼容 IDE 主题系统
- 不能把按钮视觉迁移理解为简单换背景色，必须同时重做按钮层级
- `Issues` 页面首版应优先保证结构和可用性，不强求与参考设计的 demo 数据完全一致

## 14. 最终结论

本次迁移应定义为：

`基于 IntelliJ 原生 UI 的界面重构项目`

而不是：

`将 React 设计稿技术栈迁入插件`

落地重点有三项：

- 结构收缩
- 按钮重做
- 主题统一

只要严格遵守“逻辑不变、界面重组”的原则，就可以在不触碰核心分析能力的前提下，把当前插件从“功能完成但偏冗余”的界面，升级为更接近设计稿的产品化面板。
