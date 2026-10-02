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
| `core/` | git 调用、解析器、算法、设置、仓库管理 | `NativeGit` `JGitService` `GitProcess` `LfsService` `GraphBuilder` `RepoManager` `AppSettings` `ConflictIO` `*Parser` |
| `core/model/` | 纯数据结构,无行为或只有极轻的格式化 | `CommitEntry` `BranchInfo` `FileChange` `DiffModels` `PullPlan` `PushPlan` `MergeState` |
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

### 四之二、冲突处理的三条硬性不变量

1. **继续多步操作必须压制编辑器**。`git rebase --continue` / `cherry-pick --continue` /
   `revert --continue` 都会拉起 `core.editor` 让用户确认提交说明;GUI 没有终端,
   实际表现是 `error: there was a problem with the editor 'nano.exe'`(用户机器上配的是什么编辑器就报它)
   或干脆等到超时。统一写成 `-c core.editor=true <cmd> --continue`:
   ```java
   GitProcess.in(repo).exec("-c", "core.editor=true", "rebase", "--continue");
   ```
   合并例外:用 `git commit --no-edit`,git 已把默认说明写进 `.git/MERGE_MSG`。
   回归由 `MergeStateTest` 兜住(类级 `@Timeout(SEPARATE_THREAD)`,退化必红而不是挂死构建)。
2. **解决冲突时文件的编码/BOM/换行必须原样保留**。读写一律走 `ConflictIO`:
   `Files.readAllLines` + `Files.writeString(UTF-8)` 会把 CRLF 文件整份改成 LF、
   把 GBK 文件读成乱码再毁掉。没让用户改的部分,一个字节都不该变。
3. **冲突标记检查必须在 `git add` 之前**。`git add` 会把"还带着 `<<<<<<<` 的文件"
   也记成已解决,所以 add 之后再查 `status.unmerged` 永远为假(曾经就是一段死代码);
   要在 add 之前按文件内容扫(`ConflictParser.hasMarkers`)。

### 四之三、刷新成本:先数进程,再谈优化(2026-10-02)

实测本机 `git --version` 就要 **142ms** —— Windows 上 git 进程启动本身就是主要成本,
所以一次刷新/切仓的耗时几乎等于「起了几个 git 进程 × 进程单价」。用户报的"切仓卡顿、
加载缓慢"就是这么来的。规则:

1. **同一份数据不要起两个进程**。分支列表与引用指纹共用一次 `for-each-ref`
   (`NativeGit.refSnapshot`;指纹格式必须与 `refsFingerprint` 逐字节一致,否则轮询会每 5 秒
   白重载一次历史);未推送集合用一个 `rev-list @{upstream}..HEAD`(取不到上游即空集),
   不要先 `rev-parse` 再 `rev-list`。
2. **任何 git 调用都不许出现在 FX 线程上**。`refsFingerprint` 曾经写在 phase1 的**回调**里
   (回调是 FX 线程),于是每次刷新都同步等一个进程 ~150ms;回调里只做"把数据塞进控件"。
3. **慢的那一步别挡着界面可用**。JGit 首次打开仓库约 1s,暂存列表因此拆成独立任务
   (阶段一之二),状态/变更/分支先上屏。
4. **轮询节拍跟着窗口焦点走**:前台 5 秒,后台 15 秒,回到前台立刻刷一次 ——
   每轮轮询要起 2 个进程,没人看的时候纯属白烧磁盘并和前台应用抢 IO。
5. **用计数锁住它**:`RefreshCostTest` 拿 `GitProcess.execCount()` 的差值断言"未推送集合
   只起 1 个进程""分支+指纹只用 1 次 for-each-ref";探针 `SwitchPerfProbe` 量每次切仓的
   进程数/耗时/FX 卡顿,`StashFlowProbe` 守住拆出去的暂存任务仍能把数据送到页签。
6. **已知风险**:`git lfs ls-files` 在大工作树上极慢(实测 790MB 仓库、且**没有** LFS 文件时
   也要 64~72 秒)。它目前只在仓库确实用 LFS 时才调用(`repoUsesLfs`),所以上述仓库不受影响;
   但 LFS 仓库的刷新会被它拖住,需要时给 `LfsService.lsFiles` 加缓存/超时。

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
  - `PushPipelineTest` — 推送前置探测/步进计数/强制推送与拒绝识别
  - `MergeStateTest` — 合并/变基/拣选的识别与 继续/跳过/中止(真实临时仓库)
  - `ConflictParserTest` / `ConflictIOTest` — 冲突标记解析与编码/换行保真
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
