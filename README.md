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
| 提交 | 提交说明:勾选待提交清单后一次提交(暂存 + commit);改写历史提交走「修改提交消息」 |
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

## 跨平台支持

Windows / macOS / Linux 三平台可用,核心不依赖任何平台专有 API:

| 能力 | Windows | macOS | Linux |
| --- | --- | --- | --- |
| 运行 / 开发 | `mvn javafx:run` | 同左 | 同左 |
| git 探测 | 注册表 + `C:\Program Files\Git`(Git Bash 优先)+ PATH | `/opt/homebrew/bin`、`/usr/local/bin`、`/opt/local/bin`、`/usr/bin` + PATH | 同 macOS |
| 网络操作凭据 | Git Credential Manager 弹窗 | 系统钥匙串/`git-credential-osxkeychain` | `git-credential-libsecret`/缓存 |
| 在文件管理器中显示 | 资源管理器 | Finder(`open -R`) | `xdg-open`(优先用 Java Desktop API) |
| 打包 | `.\package.ps1` → `.exe` | `./package.sh` → app-image / `TYPE=dmg` | `./package.sh` → app-image / `TYPE=deb`、`TYPE=rpm` |
| 界面字体 | 微软雅黑 / Segoe UI | PingFang SC | Noto Sans CJK / 文泉驿 |

说明:

- 设置文件统一在 `~/.easygit/settings.json`,不区分平台
- 仓库内换行符由 `.gitattributes` 统一(`* text=auto eol=lf`,Windows 脚本保持 CRLF)
- macOS/Linux 打包需 JDK 自带的 `jpackage`;生成 `dmg`/`deb`/`rpm` 时分别需要系统自带工具(hdiutil / dpkg-deb / rpmbuild),`app-image` 无额外依赖

## 运行

```bash
mvn javafx:run          # 开发模式直接启动
```

或构建 fat jar 后运行:

```bash
mvn package -DskipTests
java -jar target/easygit-<版本>.jar     # Windows 用 target\easygit-<版本>.jar
```

## 打包桌面应用

```powershell
.\package.ps1           # Windows:生成 dist\EasyGit\EasyGit.exe(自包含运行时)
```

```bash
./package.sh            # macOS / Linux:生成 dist/ 应用镜像
TYPE=dmg ./package.sh   # macOS 也支持 dmg;Linux 可用 TYPE=deb / TYPE=rpm
```

打包脚本会自动从 `pom.xml` 读取版本号;需要 JDK 自带的 `jpackage`(JDK 21 默认包含)。

## 测试

```powershell
mvn test
```

- `ParsersTest`:porcelain v2 / log 机器格式 / unified diff / 冲突标记 纯解析单测
- `GraphBuilderTest`:提交泳道图不变量(逐行"上行底边 == 下行顶边"、泳道槽位复用、无关泳道贯穿)
- `PullPipelineTest`:临时真实仓库跑通 上游探测→快进/分叉合并/变基/冲突/暂存重试 与中文报错归类
- `CoreSmokeTest`:临时真实仓库跑通 提交→历史→分支→stash→合并冲突→blame→重命名检测 全链路

## 文档

项目文档统一放在 `docs/`(本文件是入口,新增文档在下面登记一行):

- [docs/DESIGN.md](docs/DESIGN.md):设计语言与界面规范(布局语言、颜色令牌、控件约定、对齐基准、JavaFX 样式坑)
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md):代码组织约定(包职责、分层规则、新增代码放哪里、测试策略)

与代码强相关的说明优先写在代码注释里(改代码时不容易漏),`docs/` 只放需要通读的规范。

## 架构

```
org.easygit
├── core/                  # 与 UI 无关的 git 核心(可无头测试,不依赖 JavaFX)
│   ├── GitProcess         # CLI 子进程封装(UTF-8/超时/非交互网络操作)
│   ├── NativeGit          # status/log/diff/blame/for-each-ref/网络操作/拉取流程
│   ├── JGitService        # JGit:暂存/提交/检出/分支/合并/stash/tag
│   ├── LfsService         # Git LFS:状态/跟踪规则/fetch/pull/prune
│   ├── StatusParser       # porcelain v2 -z 解析(XY 定长,处理重命名/冲突)
│   ├── LogParser          # %x01 分隔的 log 机器格式解析
│   ├── DiffParser         # unified diff → 文件/hunk/行,含统计
│   ├── BlameParser        # blame --porcelain 解析
│   ├── ConflictParser     # 冲突标记解析(diff3 兼容)与按策略解决
│   ├── GraphBuilder       # 提交 DAG 分道(gitk 风格泳道图)
│   ├── RepoManager        # 当前仓库、最近仓库、变更通知(含 epoch)
│   ├── AppSettings        # ~/.easygit/settings.json(延迟合并写盘)
│   ├── model/             # 只放数据结构:CommitEntry/BranchInfo/FileChange/DiffModels/PullPlan…
│   └── GitLocator / AppVersion
├── ui/
│   ├── MainWindow         # 只做装配与刷新编排(命令栏/欢迎页/拉取流程都已外包)
│   ├── base/              # 跨面板基础件:Fx(后台调度) / StatusBar / UiLog / RepoGuard
│   │                      #   HeaderBar(命令栏+LFS 菜单) / WelcomeView
│   ├── panels/            # 业务面板:ChangesPanel / HistoryPanel / BranchPanel / RepoPanel
│   │                      #   / StashPanel / OutputPanel / CommitDetailPanel / PullFlow
│   ├── views/             # 纯展示组件(给定数据即渲染):DiffView / BlameView
│   └── dialogs/           # 对话框:Dialogs / SettingsDialog / ConflictDialog
└── resources/css/theme.css # 亮/暗主题(looked-up colors)
```

```

性能要点:UI 全程虚拟化(`ListView.setFixedCellSize` + 自绘 Canvas 泳道),
git 读取全部在后台线程池执行,`GIT_OPTIONAL_LOCKS=0` 避免与 CLI 相互锁,
状态 5 秒轻量轮询 + 操作后即时刷新。
连续切换仓库时:切换通知合并成一次刷新,旧仓库仍在跑/排队的只读任务会被中断,
过期结果一律丢弃(详见 `ui/RepoGuard` 与 `Fx.dropStaleTasks`)。
