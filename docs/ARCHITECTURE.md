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
4. **页签自己的数据按需加载**。提交历史(log + 未推送集合 + 首个提交的差异)只在
   「历史」页可见时取,暂存列表只在「Stash」页可见时取;不可见时只置脏(`historyDirty`/
   `stashDirty`),切回该页立即补一次。停在「变更」页时一次切仓只剩 2 个进程
   (status + for-each-ref)。**置脏后必须能补上**:引用一变就置脏,页面可见时立刻加载;
   两条路径都有探针守着(`LazyHistoryProbe` 验"隐藏不加载 / 可见即加载 / 置脏后切回重载到最新",
   `StashFlowProbe` 验暂存那一份)。
5. **轮询节拍跟着窗口焦点走**:前台 5 秒,后台 15 秒,回到前台立刻刷一次 ——
   每轮轮询要起 2 个进程,没人看的时候纯属白烧磁盘并和前台应用抢 IO。
6. **用计数锁住它**:`RefreshCostTest` 拿 `GitProcess.execCount()` 的差值断言"未推送集合
   只起 1 个进程""分支+指纹只用 1 次 for-each-ref";探针 `SwitchPerfProbe` 量每次切仓的
   进程数/耗时/FX 卡顿,`StashFlowProbe`/`LazyHistoryProbe` 守住按需加载。
7. **LFS 检测不许挡刷新**(2026-10-02 修)。`git lfs ls-files` 光 git-lfs 自身启动就要 **~1.6s**
   (连只有 1 个文件的仓库也一样;790MB 仓库**冷启**实测 14.9s —— 早先记的"64~72 秒"是机器被自己那批
   探针/JFR 压满时的离群值,warm 复测只有 1.6s,已更正)。做法:
   ① 只在仓库确实声明 LFS 时才跑(纯读根 `.gitattributes`,不用 LFS 的仓库零进程、零成本);
   ② 单独一个任务(阶段一之三),不串在阶段一里 —— 探针实测主数据 628ms 就绪、LFS 徽标 2714ms 才出;
      把调用放回阶段一,主数据要等到 1866ms(负对照);
   ③ `LfsService.lsFilesChecked` 带 10s 预算,超时按**数量未知**(-1,界面显示 `LFS ?`)而不是编造 0,
      并把这次结果缓存 10 分钟,免得每逢刷新都去撞慢调用;
   ④ 一次超时**不能**把 `jsonUnsupported` 置真 —— 那会把偶发抖动变成会话级的 JSON 路径退化。
   回归:`LfsCostTest`(零进程 / 超时按未知 / 结果缓存 / 不污染 JSON 路径);顺序:`LfsUiProbe`。
8. **切回看过的仓库先显示上次快照**(stale-while-revalidate,2026-10-02)。每个仓库最近一次的
   「阶段一」数据(状态 + 分支 + 引用指纹)按 LRU 记在 `MainWindow.snapshots`(上限 8 个),
   切回时**同步**套到界面上、后台刷新一到就替换 —— 切仓感知延迟从「等两个 git 进程
   (350~500ms)」降到「立刻可见」(探针实测切回 105~145ms,首次访问仍是 530ms)。
   两条纪律:①**每一条"拿到新数据的路径"都要记快照**(整仓刷新的回调 **和** 5 秒轮询的回调)
   —— 只在整仓刷新里记会漏:启动时仓库会被再选一次、旧 guard 作废,首屏数据其实来自轮询,
   于是切回来"没东西可先显示"(实测两次挂一次);②状态栏那句「先显示上次快照,正在刷新…」
   是这条路径的**唯一可观测信号**,`SwrProbe` 靠它判定缓存上屏(别拿标题的瞬时值当判据)。
   注意状态栏消息是**常驻到下一次消息**的:这类"临时提示"必须在数据到位后主动撤掉
   (`MainWindow.clearSwitchHint()`,在 phase1 回调开头调用;只撤自己留下的那条,靠
   `Fx.lastMessage()` 比对,别把期间别的新消息冲掉)。否则界面会一直挂着「正在刷新…」
   —— 这是用户实测反馈的缺陷,`SwrProbe` 已加断言守住。面向用户的中文提示一律用**全角**标点
   (`（），…`)。
   同一套思路也用在**提交历史**(`MainWindow.historySnapshots`,LRU 4,连当时「所有分支」勾选与
   文件筛选一起记):「历史」页是懒加载的,切仓后第一次点进去要等 ~300~500ms;有历史快照就先画上。
   *快照记的是"当时的筛选条件",条件不符不画*(否则会把筛选后的历史当完整历史显示)。
   顺手把 `GraphBuilder.build` 收进 `HistoryPanel.setCommits` 这个**唯一注入点**(以前两条加载路径
   各调一次,漏一处就"提交全塌成一条道"),并加 `GraphBuilderTest.buildIsIdempotent` 守住重复构图。
   **探针教训**:判"缓存上屏发生了"必须用**只属于该机制**的信号。`HistorySwrProbe` 第一版靠
   "列表已画出但 busy 还没结束"——无效,因为 busy 反映**任意**在途任务,新鲜数据到达时别的任务
   还在跑就会被误判(负对照没红)。最终改成**让缓存值与最新值不同**(离开期间给仓库加一个提交:
   缓存 20 条、最新 21 条),先看到 20 再变 21 才无歧义。另一条:观测前要等**上一仓库的界面状态清空**
   ——`RepoManager.open()` 是同步改 `current` 的,而清空要等下一拍,只看 `current` 会读到上一个仓库的残留。
9. **缓存有效就一次都不取**(2026-10-02;2026-10-04 把账算得更清)。有了历史快照之后,决定
   "要不要重取"的判据是**历史指纹 + 条数上限 + 「所有分支」+ 文件筛选**全一致
   (`MainWindow.historyCacheMatches`)—— 指纹一致就意味着这份列表就是最新的,于是
   **一个 git 进程都不用起**。实测(2026-10-04,三个仓库 + 2000 条提交的夹具):引用未变时切回仓库
   **3 个进程**(status + for-each-ref + 首个提交差异)。判定必须放在**拿到新指纹之后**
   (即 phase1 回调里),否则无从比较。
   **不要做成"只取增量"(2026-10-04 用户问过,量完不做)**:把整份重载拆开量 ——
   `log --max-count=2000` 进程(含并行的 rev-list)**157~174ms**,其中**解析 0~2ms、构图 0~1ms**;
   而增量(`log --all --not <旧尖端>`,1 个进程 + 并行的 rev-list)要 **146~158ms** ——
   **省的只是格式化旧提交那 ~15ms,进程一个不少**;改写历史(amend/rebase)还得加一个
   `merge-base --is-ancestor` 判定(+92ms)才保正确,引用被删还得回退到整份重载,净亏。
   让历史页"变快"的三件事,按收益排:**①历史快照的 LRU 必须装得下用户的仓库数**(见 11 之末);
   **②指纹按模式收窄**(见 12);**③并行取数**(见四之四之 1)。
10. **诊断基建**:`GitProcess.execCount()`(进程数)与 `GitProcess.recent()`(最近 64 条命令的
   环形缓冲,每条带**仓库路径 + 起止时刻**,`overlaps()` 用来判"两条命令是否真的并行")。
   "为什么这轮起了 4 个进程、第 4 个是谁"这类问题光靠计数答不了 ——
   本轮就是靠命令日志一眼看出第 4 个是 `git remote -v`:`BranchPanel` 在远程分组为空时
   **每次刷新**都要问一次"有没有配置远程"。现在按仓库缓存(`invalidateRemotesCache()` 在
   远程增删改后作废)。**凡是"答案几乎不变却要起进程"的判断,都该缓存或换个不需要进程的判据。**
11. **快照落盘:重开程序也能先把上次的样子画出来**(2026-10-02)。内存快照(SWR)只在一次运行内有效;
   把每个仓库的「阶段一」+ 历史快照落盘到 `~/.easygit/snapshots/<路径摘要>.json`
   (`SnapshotStore` + `model/RepoSnapshot`,都在 core、无 JavaFX),启动/切回时先播种内存缓存再照常刷新。
   五条纪律:①**缓存坏了只能当"没有缓存"** —— 损坏 / 版本不符 / 路径对不上 / 过期(>7 天) /
   超过 8MB 一律返回空,绝不抛给调用方;②写入必须**原子**(先写 .tmp 再 move),否则进程被杀会留半份 JSON;
   ③磁盘占用有上限(最多 8 个仓库,按修改时间淘汰);④**历史快照要带"自己加载时"的指纹**,
   不能与状态快照共用"最新的"指纹 —— 否则该重载时会被判成"仍然有效",界面会一直显示旧历史;
   ⑤提示里要说清这份快照是**多久之前**的(`MainWindow.ageText`),不然持久化后的旧值会被当成界面卡住。
   只在"离开仓库"与"关窗"时落盘,不做每次刷新都写(写盘本身在后台线程、内容没变还会跳过,见四之四之 4/5)。回归:`SnapshotStoreTest`(12)+
   `SnapshotPersistProbe`(两阶段:阶段一写盘、阶段二用**新进程**读盘,断言先显示旧值再被刷新纠正)。
   **历史快照的 LRU 必须装得下用户的仓库数**(2026-10-04):原来 4 个,而仓库列表里有 6~7 个,
   切来切去每次都把最早的挤出去 → 切回时历史必整份重载(实测 157~174ms,旧版串行要 340ms),
   这正是"历史页太慢"的一大来源;现在 8 个(与状态快照对齐),2000 条提交的历史快照 ~300KB/仓库,
   8 个 = 2.4MB,不值得省。**凡"缓存的容量"都要对着真实用法定,不要拍脑袋。**
12. **历史指纹按模式收窄**(2026-10-04)。历史(HEAD 的提交列表)与未推送集合,平时只由
   **HEAD + 上游**决定 —— `status --porcelain=v2 --branch` 自带这两样(branch.oid / branch.ab),
   所以 `NativeGit.headFingerprint(st)` 从 status 结果直接推导,**轮询从 2 个进程降到 1 个**;
   勾了「所有分支」才盯全部引用(`refsFingerprint`,侧支/远程分支的新提交不动 HEAD,只盯 HEAD 会让
   历史页永不刷新 —— 那是 2026-10-01 的真缺陷,别回退)。收益:别的工具建分支/打标签/抓远程
   不会再被当成"历史变了"触发整份重载(探针 `HistoryChurnProbe`:建分支+标签 → **0 个 log 进程**;
   负对照:改回全部引用的指纹 → 触发重载,立刻红)。**纪律:两条取数路径(轮询/阶段一)与快照必须用
   同一种格式的指纹**,否则会互相把对方当成"引用变了",每拍白重载一次历史 —— 上屏时的基线指纹
   只有**模式相同**的快照才能用(`Snapshot.allBranches`),模式对不上宁可没有基线;
   磁盘快照不记模式,播种时一律不带基线(整仓刷新窗口里轮询本来就压着,phase1 一到基线就是新的)。

### 四之四、连续切换仓库(连点)的处理(2026-10-02)

"连点几个仓库对比一下"是最常见的用法,也是一次刷新成本的放大器:每一次点击都**独立**发起一轮
取数,而前面几轮的结果注定被丢弃。规则(全部有探针/用例守着,见 `RapidSwitchProbe`):

1. **互不依赖的只读命令并行取,不是串着等**。一次刷新里 `status` 与 `for-each-ref` 读的东西
   毫不相干(索引/工作区 vs 引用表),`log` 与 `rev-list` 也是 —— 串行跑就是白等一整个进程的时间。
   收敛到 `NativeGit.readState` / `NativeGit.readHistory`,内部走 `core/Parallel.both`。
   实测(288 提交仓库,7 轮中位数):阶段一 **223ms → 128ms**、历史 **239ms → 147ms**,
   **进程数一个不多**(仍是 2 个),只是不再排队等对方 —— 所以并行不增加 IO 总量。
   `Parallel.both` 的中断语义:外层刷新被取消时立刻 `cancel(true)` 两路(否则 git 子进程会继续跑完),
   并把中断标记留给调用方;它只能在别的线程池里调用(自己阻塞等待,不许在 `easygit-read` 里嵌套)。
2. **换皮立刻做,取数等"停稳"**。`MainWindow.paintSwitch`(同步:清场 + 内存快照上屏 + 状态栏归零)
   与 `MainWindow.startFetch`(异步取数)必须分开:界面绝不能挂着上一个仓库的数据,**哪怕只挂 100ms**
   (那就是串仓);而取数要看用户是否还在连点 —— 上一轮还没回来就又切了仓库 ⇒ 顺延 `SETTLE_MS=120ms`
   再取,手停下来只取最后那一站。单次切换时没有在途取数,走"立刻取",**不为此付任何延迟**。
   实测(80ms 间隔连点 4 次):状态取数 **4 轮 → 2 轮**、路过的仓库 **2 次 → 0 次**(负对照:去掉
   合并窗口后这两项立刻变红)、总进程 16 → 8、FX 最大卡顿 151ms → 41ms。
3. **旧仓库的在途任务在切换的那一拍就收回**(`Fx.dropStaleTasks()`),不要等下一轮刷新顺手收。
   被取消的刷新**不是失败**:`Parallel.both` 抛 `CancellationException`,`Fx.submit` 对它不写"✖"日志
   (否则连点会在输出面板刷一串毫无意义的报错)。
4. **快照写盘不许占 FX 线程**。一份 2000 提交的快照序列化 + 写盘实测 **~63ms**(读回 ~35ms),
   而"离开仓库"正是每次切换都要走的路径 ⇒ 组装对象留在 FX 线程(便宜),写盘交给
   `SnapshotStore.saveAsync`(单线程队列,顺序有保证);关窗前 `SnapshotStore.flush(1500)`
   等它写完,别把快照丢了。诊断:`SnapshotStore.lastIoThread()` 必须是 `easygit-snapshot`
   (负对照:改回同步写 → 变成 `JavaFX Application Thread`,探针报红)。
   **读盘播种仍保持同步** —— 首帧就得有内容,那是持久化的意义所在(35ms 换"打开就有东西"值得)。
5. **内容没变的快照不重写**。连点时中途那些仓库往往还没等来自己的刷新就被切走了,内容与上次落盘
   的一模一样;靠 `savedAt` 比一下(`MainWindow.persistedStamps`)就能省掉整份 JSON 的重复写。
6. **"答案几乎不变却要起进程"的判断一律按仓库缓存**。`BranchPanel` 原来只有一个槽记"有没有配置远程",
   切来切去必然每次落空 → 每个仓库每次切都白起一个 `git remote -v`(连只是路过的仓库也起);
   改成按仓库的 LRU(上限 16)。另外**刚整仓取完数就让轮询让路**(`POLL_AFTER_REFRESH_MS=1500`):
   切仓后紧跟的那一拍轮询取的是同一份数据,纯粹重复(探针里那一拍正好撞上)。
7. **用确定性量验证,不要用墙钟**:连点的判据是"发起了几轮状态取数""哪几个仓库发起了""重复落盘几次"
   "落盘在哪个线程""有没有回退成别的仓库";`GitProcess.recent()` 里带**仓库路径 + 起止时刻**,
   所以"这两条命令到底并行了没有""这个进程是谁起的"都能直接判(而不是靠跑得快不快猜)。

## 五、异步与仓库切换

界面取数**只能**通过 `Fx.bg(...)` 家族:

- 只读取数的刷新 → 用带 `RepoGuard` 的重载(切仓库时自动作废/丢弃结果)
- **会改仓库状态的操作**(提交/检出/推送/拉取)→ 用不带 guard 的 `Fx.bg`,必须跑完并如实上报

仓库切换的护栏见 `RepoManager.epoch()` + `RepoGuard`;界面在 `refreshAll()` 里统一清场
(`changesPanel.onRepoSwitched()` / `historyPanel.onRepoSwitched()` 等)。新增面板如果有
"属于某个仓库的缓存状态",必须在这条清场链上挂一个 `onRepoSwitched()`。

### 五之二、错误提示必须 fail-safe(2026-10-02)

`Fx.error/info/confirm` 全部包了 try/catch,建不出对话框就走 `Fx.dialogFallback`:
把标题/消息/详情写进输出面板与状态栏,**绝不把真正的错误吞掉**;`confirm` 更必须 **fail-closed**
(弹不出确认框一律当"取消",破坏性操作不能放行);`dialogFallback` 自身也不许抛
(连 `Platform.runLater` 在无工具包时都会抛)。

由来:实例 **fat jar 在运行中被替换** → JVM 懒加载 `javafx/scene/control/Alert$1` 按旧偏移读到新文件
→ `NoClassDefFoundError` → 错误框建不起来,于是用户只看到「界面更新失败: javafx/scene/control/Alert$1」,
**原始报错全丢**。两条纪律:
1. 显示错误的代码路径本身也要有兜底,否则"报告失败"会顶掉"失败原因";
2. `reportUiFailure` 的输出**必须带异常类型**(`Fx.describe`),不然 `NoClassDefFoundError` 只剩一个类名,
   看日志根本猜不出发生了什么。
另外:部署脚本 `tools/deploy-jar.ps1` 会拒绝覆盖正在运行的 jar(见 README「运行」),
这类"运行中换 jar"的故障不是应用 bug,是部署姿势问题。

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
