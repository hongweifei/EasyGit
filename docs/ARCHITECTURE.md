# EasyGit 代码组织约定

面向后续开发(人和 AI 都一样):**新增代码放哪里、什么不能做**。设计语言与界面规范见
[DESIGN.md](DESIGN.md)。

## 一、分层与依赖方向

```
ui/*  ──→  core/*        (只允许单向:界面依赖核心,核心永不依赖界面)
core/* ──→  core/model/*  (数据结构独立,谁都可以用)
```

`core/` **不允许出现 `import javafx.*`**。这条规则让整层可以无头跑 JUnit,也是
`GraphBuilderTest` / `PullPipelineTest` 能落地的前提。写新功能时如果发现"必须拿到界面才能算",
那就是职责放错了——把**计算**留在 core,把**弹框/更新界面**留给 ui。

判断标准很简单:

| 这段代码在做什么 | 放哪 |
| --- | --- |
| 调 git / 解析输出 / 计算布局 / 归类报错 | `core/` |
| 决定"弹哪个框、状态栏说什么、要不要刷新" | `ui/` |
| 只放字段、record、枚举 | `core/model/` |

## 二、包职责(新增文件按下表落位)

| 包 | 放什么 | 现状 |
| --- | --- | --- |
| `core/` | git 调用、解析器、算法、设置、仓库管理 | `NativeGit` `JGitService` `GitProcess` `LfsService` `GraphBuilder` `RepoManager` `AppSettings` `*Parser` |
| `core/model/` | 纯数据结构,无行为或只有极轻的格式化 | `CommitEntry` `BranchInfo` `FileChange` `DiffModels` `PullPlan` |
| `ui/base/` | 跨面板的基础件(所有面板都会用到) | `Fx`(后台任务/提示) `StatusBar` `UiLog` `RepoGuard` `HeaderBar` `WelcomeView` |
| `ui/panels/` | 有业务语义的面板与流程编排 | `ChangesPanel` `HistoryPanel` `BranchPanel` `RepoPanel` `StashPanel` `OutputPanel` `CommitDetailPanel` `PullFlow` |
| `ui/views/` | **纯展示**组件:给数据就渲染,不自己取数 | `DiffView` `BlameView` |
| `ui/dialogs/` | 对话框 | `Dialogs` `SettingsDialog` `ConflictDialog` |
| `ui/MainWindow` | 只做**装配 + 刷新编排** | 445 行,已外包命令栏/欢迎页/拉取流程 |

`MainWindow` 的硬约束:**不要再往里加业务方法**。它现在的职责只有三件——
装配各面板、编排 `refreshAll()`/`lightRefresh()`、把动作回调路由出去。
新动作要么进对应面板,要么像 `PullFlow` 那样单独成类,通过 `Host`/`Actions` 接口回调。

## 三、两条实现路径的边界(容易放错)

项目同时用 CLI 与 JGit,分工是明确的:

- **`NativeGit`(git 命令行)**:所有**读**操作(status/log/diff/blame/for-each-ref)、
  所有**网络**操作(fetch/pull/push)、以及需要精确控制参数的写操作。
  原因:大仓库性能好,且直接复用系统 git 的凭据管理器与 `~/.gitconfig`。
- **`JGitService`**:仅**本地对象操作**(暂存/提交/检出/分支/合并/stash/tag)。
  原因:这些操作 JGit 的对象模型更直接,不必拼命令字符串。

新加功能时先问"要不要联网、要不要读仓库状态":要 → `NativeGit`;纯本地对象改动 → `JGitService`。
两边都别重复实现同一个能力。

## 四、网络操作的硬性不变量(不要回退)

所有可能弹进度/凭据提示的命令(走 `execNet`):**必须非交互、必须关闭子进程 stdin**。
GUI 没有终端,git 一旦等 stdin 输入就**永久挂起**——界面表现为"卡住不动"而不是失败。

- `GIT_TERMINAL_PROMPT=0`(凭据管理器弹窗照常工作)
- 子进程 stdin 立即 EOF
- SSH `BatchMode=yes`(用户设过 `GIT_SSH_COMMAND`/`GIT_SSH` 或 `core.sshCommand` 则不覆盖)
- 超时:普通网络 5 分钟,LFS 15 分钟,clone 30 分钟

新增网络命令一律用 `GitProcess.execNet(...)`,不要用 `exec(...)`。

## 五、异步与仓库切换

界面取数**只能**通过 `Fx.bg(...)` 家族:

- 只读取数的刷新 → 用带 `RepoGuard` 的重载(切仓库时自动作废/丢弃结果)
- **会改仓库状态的操作**(提交/检出/推送/拉取)→ 用不带 guard 的 `Fx.bg`,必须跑完并如实上报

仓库切换的护栏见 `RepoManager.epoch()` + `RepoGuard`;界面在 `refreshAll()` 里统一清场
(`changesPanel.onRepoSwitched()` / `historyPanel.onRepoSwitched()` 等)。新增面板如果有
"属于某个仓库的缓存状态",必须在这条清场链上挂一个 `onRepoSwitched()`。

## 六、数据加载路径:必须收敛到单一入口

**同一个列表有两条加载路径时,后处理(构图/排序/标记)必须放在唯一的汇合点**,
否则必漏一条——历史上 `GraphBuilder.build()` 就漏在 `HistoryPanel.refresh()` 那条路径上,
表现是"勾选所有分支后所有提交塌成一条道、merge 不画弯出、行间断开",而且下次全量刷新又自愈,
极难复现。规则:把后处理放进 `setCommits(...)` 这类唯一的注入点,而不是写在调用方。

## 七、测试策略

- **能进 `src/test/java` 的就别只留在探针里**。纯逻辑(git 调用、解析、算法、报错归类)
  一律写成 JUnit:快、可重复、CI 能跑。现有:
  - `ParsersTest` — 各解析器
  - `GraphBuilderTest` — 泳道图不变量(逐行"上行底边 == 下行顶边"、槽位复用)
  - `PullPipelineTest` — 真实临时仓库跑拉取分流/冲突/暂存重试/中文报错
  - `CoreSmokeTest` — 全链路冒烟
- **界面/像素/布局**确实需要真实 JavaFX 的,才用探针(`target/harness/`,已被 gitignore)。
- **每个新修复都要有会红的测试**:先写/改测试证明当前是坏的,再修。必要时做**负对照**
  (把机制主动废掉跑一遍,确认测试 FAIL),否则无法区分"测试通过"与"测试没测到"。

```powershell
mvn test                                  # 全部单测
mvn test "-Dtest=GraphBuilderTest"        # 单个测试类
```

## 八、提交与注释

- 提交消息用**中文**,`feat:` / `fix:` / `refactor:` + 一句话说明"改了什么、为什么"。
- **基础设施改动与业务改动分开提交**(如"网络操作不交互"与"重做拉取流程"),便于单独回退。
- 注释写"为什么"(尤其是反直觉的取舍与踩过的坑),不写"是什么"。踩坑结论直接写在出问题的
  代码旁边——比放进文档更容易在改代码时看到。
