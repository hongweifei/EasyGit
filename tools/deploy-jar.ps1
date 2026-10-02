<#
    打包并部署桌面 jar。

    为什么需要这个脚本(而不是直接 Copy-Item):
      实例 jar 在**运行中被替换**会让正在跑的 JVM 出 NoClassDefFoundError ——
      JVM 是懒加载类的,它按 jar 中央目录里的**旧偏移**去读**已被换掉的文件**,
      于是任何"以后才会用到的类"都可能加载失败(实测踩到:用户只看到
      「界面更新失败: javafx/scene/control/Alert$1」,而且真正的报错被一起吞掉)。
      所以:检测到 EasyGit 正在跑就**拒绝覆盖**,让人先关掉它。

    用法:
      powershell -File tools\deploy-jar.ps1              # 用现有 target/*.jar 部署
      powershell -File tools\deploy-jar.ps1 -Build       # 先 mvn -o -DskipTests package 再部署
      powershell -File tools\deploy-jar.ps1 -Force       # 明知有实例在跑也覆盖(不推荐)

    注意:本文件是 UTF-8 **带 BOM**(Windows PowerShell 5.1 读 .ps1 时若无 BOM 会按 ANSI 解,
    中文会乱码并直接把语法搞坏)。改这个文件请保持带 BOM。
#>
param(
    [switch]$Build,
    [switch]$Force,
    [string]$Desktop = [Environment]::GetFolderPath('Desktop')
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$name = 'easygit-0.2.0-SNAPSHOT.jar'
$src  = Join-Path $root "target\$name"
$dst  = Join-Path $Desktop $name

if ($Build) {
    Write-Host "构建中(mvn -o -DskipTests package)…"
    Push-Location $root
    try { & mvn -o -q '-DskipTests' package } finally { Pop-Location }
    if ($LASTEXITCODE -ne 0) { throw "mvn package 失败(exit $LASTEXITCODE)" }
}
if (-not (Test-Path $src)) { throw "找不到构建产物:$src(先跑 -Build 或 mvn package)" }

$running = @(Get-CimInstance Win32_Process -Filter "name='java.exe' or name='javaw.exe'" |
    Where-Object { $_.CommandLine -and $_.CommandLine -like "*$name*" })

if ($running.Count -gt 0 -and -not $Force) {
    $pids = ($running | ForEach-Object { $_.ProcessId }) -join ', '
    Write-Host ""
    Write-Warning "EasyGit 正在运行(PID $pids),它们正用着这个 jar。"
    Write-Host "  覆盖会让运行中的实例懒加载类失败(NoClassDefFoundError)——请先关闭 EasyGit,再跑一次本脚本。"
    Write-Host "  要冒险覆盖可加 -Force(不推荐)。"
    exit 2
}

Copy-Item $src $dst -Force
Write-Host "已部署:$dst"
Write-Host ("大小 {0:N0} 字节,时间 {1}" -f (Get-Item $dst).Length, (Get-Item $dst).LastWriteTime)