# EasyGit

原生 **Java 21 + JavaFX** 的 Git 桌面客户端,目标是流畅、轻量:
历史列表 / diff 全部基于**虚拟化控件**渲染,万级提交也不卡顿。

引擎采用 **JGit + 原生 git CLI 混合**:

- 本地操作(暂存/提交/检出/分支/合并/stash/tag)→ JGit,结构化、可控
- 状态/日志/diff/blame/克隆/抓取/拉取/推送 → 原生 git CLI,大仓库性能好,并直接复用系统 Git 凭据管理器(Windows 下 GCM 自动弹窗登录)

git 可执行文件**优先使用 Git for Windows(Git Bash)**:自动按 注册表 InstallPath → 常见安装位置(`C:\Program Files\Git` 等)→ PATH 探测。注意:子进程**不**注入 Git Bash 的 PATH(`usr\bin` 等)—— 实测会让 `git-remote-https` 加载错误版本的 DLL 而报 `remote helper 'https' aborted session`;git 自身会通过 exec-path 定位远程助手/钩子/凭据助手。如需手动指定,在 `~/.easygit/settings.json` 设置 `"gitPath": "C:\\Program Files\\Git\\cmd\\git.exe"`(保存后立即生效)。

## 功能

| 模块 | 能力 |
| --- | --- |
| 仓库管理 | **多仓库管理 + 分组**:左侧仓库树单击即切换,右键改名/移动分组/移除;打开、克隆、初始化、最近仓库持久化 |
| 变更 | 已暂存 / 未暂存 / 未跟踪 / 冲突分组,暂存、取消暂存、丢弃、删除 |
| 提交 | 提交说明、修改上次提交(amend) |
| 历史 | Canvas 泳道提交图 + refs 徽标(HEAD/分支/tag/远程),检出提交、建分支、打标签 |
| 修改提交消息 | 任意历史提交右键修改;自动改写其后代提交链(树不变、作者保留),明确警示 SHA 变化与强制推送 |
| 提交详情 | 变更文件列表 + 逐文件 diff |
| Diff | 统一视图,虚拟化渲染,行号 + 增删着色,重命名/二进制识别 |
| 分支 | 检出(双击)、创建、重命名、删除(未合并时提示强制)、推送、删除远程分支 |
| 合并 | 合并任意本地/远程分支,冲突进入解决流程 |
| 冲突 | 标记解析(diff3 兼容),逐块 采用当前/传入/base/两者,可手工编辑,保存即暂存 |
| Stash | 创建(含未跟踪)、应用、pop、删除 |
| Blame | 行级追溯:提交、作者、日期着色分组 |
| 网络 | fetch --all --prune、pull、push(-u 自动设上游、--force-with-lease) |
| Git LFS | 状态面板(版本/钩子/文件与下载状态)、fetch/pull/prune、跟踪规则管理(.gitattributes)、克隆后自动拉取 LFS;LFS 仓库的暂存/提交/检出/合并/stash 自动改走 git CLI 保证过滤器正确执行 |
| 外观 | 亮/暗主题切换,状态栏(分支、领先落后、变更计数、忙碌指示) |

## 环境要求

- JDK 21+
- Maven 3.9+
- 系统安装 git CLI(需要 2.x,基本任何现代版本均可)

## 运行

```powershell
mvn javafx:run          # 开发模式直接启动
```

或构建 fat jar 后运行:

```powershell
mvn package -DskipTests
java -jar target\easygit-0.1.0.jar
```

## 打包桌面应用

```powershell
.\package.ps1           # 生成 dist\EasyGit\EasyGit.exe(自包含运行时)
```

需要安装 JDK 自带的 `jpackage`(JDK 21 默认包含)。

## 测试

```powershell
mvn test
```

- `ParsersTest`:porcelain v2 / log 机器格式 / unified diff / 冲突标记 / 图算法 纯解析单测
- `CoreSmokeTest`:临时真实仓库跑通 提交→历史→分支→stash→合并冲突→blame→重命名检测 全链路

## 架构

```
org.easygit
├── core/                  # 与 UI 无关的 git 核心
│   ├── GitProcess         # CLI 子进程封装(UTF-8/超时/并发读管道)
│   ├── NativeGit          # status/log/diff/blame/for-each-ref/网络操作
│   ├── JGitService        # JGit:暂存/提交/检出/分支/合并/stash/tag
│   ├── StatusParser       # porcelain v2 -z 解析(XY 定长,处理重命名/冲突)
│   ├── LogParser          # %x01 分隔的 log 机器格式解析
│   ├── DiffParser         # unified diff → 文件/hunk/行,含统计
│   ├── BlameParser        # blame --porcelain 解析
│   ├── ConflictParser     # 冲突标记解析(diff3 兼容)与按策略解决
│   ├── GraphBuilder       # 提交 DAG 分道(gitk 风格泳道图)
│   ├── RepoManager        # 当前仓库、最近仓库、变更通知
│   └── AppSettings        # ~/.easygit/settings.json
├── ui/                    # JavaFX 界面
│   ├── MainWindow         # 工具栏 + 左树 + 中部标签页 + 状态栏 + 欢迎页
│   ├── HistoryPanel       # 历史(虚拟化列表 + Canvas 泳道)+ 提交详情
│   ├── ChangesPanel       # 变更四分组 + 提交框
│   ├── DiffView           # 虚拟化 diff 渲染
│   ├── BranchPanel        # 分支/远程/标签树与右键操作
│   ├── StashPanel / BlameView / ConflictDialog / Dialogs
│   └── Fx / StatusBar     # 后台任务调度、忙碌与消息
└── resources/css/theme.css # 亮/暗主题(looked-up colors)
```

性能要点:UI 全程虚拟化(`ListView.setFixedCellSize` + 自绘 Canvas 泳道),
git 读取全部在后台线程池执行,`GIT_OPTIONAL_LOCKS=0` 避免与 CLI 相互锁,
状态 5 秒轻量轮询 + 操作后即时刷新。
