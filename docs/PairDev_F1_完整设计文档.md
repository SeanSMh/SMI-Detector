PairDev F1 完整设计文档
Claude + Codex 单终端自动对话编程系统
文档版本：F1 / 最小完整产品 目标：单终端、双 Agent、自动对话、可恢复、可审计

目录
1. 产品概述与定位
2. 目标、非目标与成功标准
3. 用户场景与核心工作流
4. 需求清单（功能 / 非功能）
5. 系统总体架构
6. 运行时架构与状态机
7. 自动对话协议与消息模型
8. 角色系统与策略引擎
9. 模块拆解
10. 工程目录结构
11. 运行时目录与工件体系
12. 命令设计与终端交互
13. 数据模型
14. 错误处理、恢复与回放
15. 测试策略与版本路线
1. 产品概述与定位
PairDev F1 是一个面向工程开发场景的单终端双 Agent 编程系统。用户只需要在一个终端中提交需求，系统会自动调度 Claude 与 Codex 在不同职责下完成规划、实现、评审、修补与报告输出，形成一个可审计、可恢复、可中断的人机协同开发闭环。
它不是一个“会聊天的代码助手”，而是一个“围绕任务对象运转的编程编排器”。其核心价值在于：同一条需求不再只由一个模型单线程处理，而是通过角色分工把“计划与评审”与“实现与修补”分离开来，提升复杂任务的稳定性与可控性。
维度
定位
产品类型
单终端 AI 编程编排系统
核心形态
Claude + Codex 双 Agent 自动对话
目标用户
独立开发者、工程师、工具链开发者、团队技术负责人
交互中心
CLI / TUI（一个终端入口）
核心能力
计划、执行、评审、修补、恢复、审计、回放

2. 目标、非目标与成功标准
2.1 目标
一个终端内完成完整任务生命周期：创建、执行、评审、修补、完成。
Claude 与 Codex 各司其职，并能按策略自动切换轮次。
所有关键步骤都以结构化工件与日志形式持久化。
支持 pause / resume / replay / human override。
系统以任务为中心，而不是以松散聊天为中心。
2.2 非目标
F1 不做云端多任务调度平台。
F1 不做 GUI 桌面应用或 Web 控制台。
F1 不做自动开 PR、自动发布或团队协作中心。
F1 不默认支持 3 个以上 agent 的复杂群聊式协作。
2.3 成功标准
指标
定义
闭环完成率
能够完成 plan → execute → review → patch → done 的任务比例
可恢复性
任意中断后可从上次有效状态恢复，不丢失工件
可审计性
每一轮输入输出、命令、diff、日志都可追踪
可控性
遇到 blocker、循环 review、危险命令时可自动停在 WAIT_USER

3. 用户场景与核心工作流
3.1 典型用户场景
实现一个功能：例如“实现 Android 设置页深色模式”。
修复一个 bug：例如“定位并修复启动页闪退”。
做一次重构：例如“拆分支付模块中的耦合逻辑”。
做 review & patch：对已有改动进行自动审查并修补。
中断后恢复：重启终端后继续上次任务。
3.2 标准工作流
用户提交需求，系统创建 task.json 与 session。
Planner 读取目标与约束，产出 PLAN.md 与 TASKS.json。
Executor 读取计划并在工作区执行改动，生成 patch.diff、changed-files.json、build.log。
Reviewer 基于 diff 与日志输出 REVIEW.json。
若需要修补，Patcher 继续执行；若通过，则生成 FINAL_REPORT.md。
4. 需求清单（功能 / 非功能）
4.1 功能需求
编号
描述
FR-001
系统必须支持单终端创建任务。
FR-002
系统必须支持双 Agent 自动对话。
FR-003
系统必须支持 plan / execute / review / patch 固定闭环。
FR-004
系统必须支持角色映射与角色交换。
FR-005
系统必须支持 pause / resume / abort。
FR-006
系统必须支持 replay 与日志查看。
FR-007
系统必须支持人工审批与强制介入。
FR-008
系统必须支持 artifacts 与任务状态持久化。

4.2 非功能需求
编号
描述
NFR-001
所有状态迁移必须可持久化。
NFR-002
所有 Agent 输出必须可结构化与可回放。
NFR-003
异常退出后必须支持恢复。
NFR-004
任何失败都必须落入明确状态，不允许 silent fail。
NFR-005
系统必须具有清晰的权限与策略边界。

5. 系统总体架构
5.1 架构分层
层级
职责
Terminal UI
唯一用户入口，展示状态、消息流与控制命令
Orchestrator Core
状态驱动、轮次控制、对话路由、停止条件判断
Agent Adapter
适配 Claude / Codex，不暴露底层差异到业务层
Artifact / Storage
持久化计划、diff、日志、回放、报告
Workspace / Git
工作区、worktree、diff、patch、变更摘要
Policy / Approval
权限边界、审批策略、人工介入

5.2 总体架构图（文本版）
Single Terminal UI
    ↓
Orchestrator Core
 ├─ Session Manager
 ├─ State Machine
 ├─ Conversation Router
 ├─ Artifact Manager
 ├─ Policy Engine
 └─ Audit Logger
    ↓                     ↓
Claude Adapter        Codex Adapter
    ↓                     ↓
Claude Workspace      Codex Workspace
            ↓
      Repo / Worktree Layer
6. 运行时架构与状态机
6.1 运行时对象
Task：任务对象，持有目标、约束、工件索引、状态与预算。
Session：当前运行态，持有 active role、round、agent run id 等临时信息。
Message：结构化消息单元，是自动对话的最小可审计单位。
Artifact：计划、diff、日志、review、最终报告等持久化文件。
6.2 状态机
状态
说明
典型出口
INTAKE
接收需求、创建任务
PLAN
PLAN
规划与任务拆解
EXECUTE / WAIT_USER
EXECUTE
执行改动与命令
REVIEW / WAIT_USER
REVIEW
审查结果与质量判断
DONE / PATCH / WAIT_USER
PATCH
根据 review 修补
REVIEW / WAIT_USER
DONE
任务完成并生成报告
结束
FAILED
任务失败
人工 retry 或终止
ABORTED
用户主动终止
结束

状态转移原则：任何非法转移都应被拒绝；重复无价值的 review-patch 循环必须触发 WAIT_USER。
7. 自动对话协议与消息模型
7.1 自动对话原则
自动对话不是自由聊天，而是结构化协商。
每一轮都必须由 Orchestrator 决定谁说话。
所有消息必须具有 type 与 next_action。
系统必须限制最大轮次、最大失败次数与重复阈值。
7.2 消息结构
{
  "id": "msg_001",
  "task_id": "task_20260319_001",
  "round_id": "round_03",
  "from": "claude",
  "role": "reviewer",
  "type": "review",
  "summary": "发现冷启动路径未恢复主题",
  "details": {
    "severity": "high",
    "files": ["App.kt"]
  },
  "next_action": "patch",
  "created_at": "2026-03-19T10:30:00Z"
}
7.3 允许的消息类型
type
用途
plan
规划输出
task_breakdown
任务拆解
execute_result
执行结果
review
审查结论
patch_result
修补结果
blocker
阻塞信息
approval_request
需要人工确认
done
任务完成
failed
任务失败

8. 角色系统与策略引擎
8.1 默认角色映射
阶段角色
默认 Agent
职责
Planner
Claude
理解需求、拆任务、设定验收标准
Executor
Codex
改代码、执行命令、生成 diff
Reviewer
Claude
评审变更、识别风险与遗漏
Patcher
Codex
根据 review 修补并重跑验证

8.2 策略边界
角色
读仓库
写仓库
执行命令
说明
Claude / Planner
是
否
否
默认只读
Claude / Reviewer
是
否
否
只读 review
Codex / Executor
是
是
是
有执行权限
Codex / Patcher
是
是
受限
通常限制 scope

策略引擎负责命令白名单/黑名单、高风险操作审批、循环检测、人工接管触发条件。
9. 模块拆解
模块
职责
CLI Shell
程序入口、参数解析、命令路由
Terminal Renderer
渲染 live view、消息流、状态条
Task Manager
任务创建、更新、查询、结束
Session Manager
运行态会话、round、恢复
Orchestrator
自动对话编排、轮次控制、切换
State Machine
合法转移校验
Message Bus
模块事件总线
Agent Registry
注册与映射 Claude/Codex 适配器
Prompt Builder
按 role 与 context 组织输入
Artifact Manager
工件写入、读取与索引
Workspace Manager
工作区与临时目录管理
Git / Diff Manager
worktree、diff、patch、变更摘要
Policy Engine
权限、审批、风险级别
Replay Manager
任务回放与历史重建
Recovery Manager
中断恢复与一致性检查

10. 工程目录结构
pairdev/
├── docs/
├── src/
│   ├── cli/
│   ├── tui/
│   ├── core/
│   ├── agents/
│   ├── workspace/
│   ├── git/
│   ├── artifacts/
│   ├── storage/
│   ├── logging/
│   ├── config/
│   ├── domain/
│   └── utils/
├── prompts/
├── playbooks/
├── tests/
├── examples/
├── scripts/
├── .pairdev/
└── bin/
目录设计原则：业务分层清晰；运行时数据落在 .pairdev/；prompt 与 playbook 独立；测试按 unit / integration / e2e 分层。
11. 运行时目录与工件体系
 .pairdev/
├── config/
├── sessions/
├── artifacts/
│   └── task_20260319_001/
│       ├── PLAN.md
│       ├── TASKS.json
│       ├── patch.diff
│       ├── changed-files.json
│       ├── REVIEW.json
│       └── FINAL_REPORT.md
├── logs/
│   └── task_20260319_001/
│       ├── orchestrator.jsonl
│       ├── claude.jsonl
│       ├── codex.jsonl
│       └── build.log
├── replays/
└── workspace/
关键原则：agent 协作依赖工件，不依赖共享一大坨聊天上下文；任何轮次都能通过工件与日志重建现场。
12. 命令设计与终端交互
12.1 核心命令
命令
用途
pairdev start "<goal>"
创建并启动任务
pairdev status
查看当前状态
pairdev pause
暂停自动对话
pairdev resume <taskId>
恢复任务
pairdev abort <taskId>
终止任务
pairdev diff <taskId>
查看变更摘要
pairdev logs <taskId>
查看日志
pairdev replay <taskId>
回放任务
pairdev swap <taskId>
交换角色映射
pairdev approve <taskId>
人工批准继续

12.2 终端交互示意
┌ Task: 实现 Android 设置页深色模式 ───────────────────────────────┐
│ Stage: REVIEW   Active Role: Claude / Reviewer   Mode: Auto       │
├────────────────────────────────────────────────────────────────────┤
│ Claude: 我已审查本轮 diff，发现冷启动路径未恢复主题。             │
│ Codex: 已收到，准备修复 Application 初始化逻辑。                 │
│ Codex: 修改 2 个文件，正在重跑单测...                           │
│ Claude: 本轮审查通过。                                           │
├────────────────────────────────────────────────────────────────────┤
│ Commands: /pause /resume /approve /swap /logs /diff              │
└────────────────────────────────────────────────────────────────────┘
13. 数据模型
13.1 Task 模型
{
  "task_id": "task_20260319_001",
  "title": "实现 Android 设置页深色模式",
  "goal": "为设置页增加深色模式切换，并在重启后保持状态",
  "mode": "auto",
  "role_mapping": {
    "planner": "claude",
    "executor": "codex",
    "reviewer": "claude",
    "patcher": "codex"
  },
  "stage": "review",
  "status": "running",
  "constraints": [
    "不修改支付主流程",
    "不影响首页主题逻辑"
  ],
  "acceptance_criteria": [
    "可即时切换主题",
    "应用重启后主题保持"
  ]
}
13.2 其他模型
Session：active role、agent run id、current round、resume point。
Artifact：类型、路径、版本、生成轮次、校验摘要。
Review Item：severity、file、issue、suggestion、decision。
Policy：角色权限、审批规则、预算与限制。
14. 错误处理、恢复与回放
14.1 常见错误路径
Agent 无响应或输出无法解析。
执行命令失败，build/test/lint 未通过。
Review 与 Patch 连续重复，形成低价值循环。
Artifact 缺失或损坏。
工作区冲突或 git worktree 创建失败。
14.2 恢复原则
启动时先做一致性检查，确认 task、session、artifacts 是否匹配。
若能定位最后一个有效状态，则从该状态恢复。
若输出工件损坏，则从上一个完整 round 重建。
任何不可自动处理的问题都进入 WAIT_USER，而不是 silent fail。
14.3 回放
Replay 不是简单日志查看，而是可重建任务时间线：看到每轮由谁发言、产生了哪些工件、做了哪些转移、为什么进入当前状态。
15. 测试策略与版本路线
15.1 测试策略
层级
目标
Unit
状态机、消息协议、策略校验、prompt builder
Integration
plan-execute-review-patch 闭环、resume、swap role
E2E
真实 CLI / TUI 交互、日志、回放、失败恢复
Fixture
假仓库、假 agent 输出、损坏工件恢复

15.2 版本路线
F1：单终端、单任务、双 Agent、完整闭环、可恢复、可审计。
F2：体验增强，加入 token/cost 面板、上下文压缩、更多策略规则。
F3：扩展 JetBrains 插件面板或 menubar 监控。
F4：接入 Android 专项技能、CI 与团队工作流。
结语
PairDev F1 的关键不是“把两个模型放在一起聊天”，而是建立一套可落地的任务编排秩序：一切围绕任务对象、工件、状态机与策略边界展开。这样，Claude 与 Codex 的协作才能从“演示效果”变成“真实生产力工具”。
