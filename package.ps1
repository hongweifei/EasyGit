# EasyGit 桌面打包脚本:生成自包含的 dist\EasyGit\EasyGit.exe
# 需要:JDK 21(含 jpackage)、Maven、系统 git
$ErrorActionPreference = 'Stop'
try { $OutputEncoding = [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}

Push-Location $PSScriptRoot
try {
    Write-Host "==> Maven 打包 fat jar" -ForegroundColor Cyan
    mvn -q package -DskipTests
    if ($LASTEXITCODE -ne 0) { throw "Maven 构建失败" }

    # 版本号自动从 pom.xml 读取,避免脚本与 pom 脱节
    [xml]$pomXml = Get-Content pom.xml -Raw
    $version = [string]$pomXml.project.version
    if (-not $version) { throw "无法从 pom.xml 读取版本号" }
    Write-Host "==> 版本: $version" -ForegroundColor Cyan
    # 只把最终 jar 放进 jpackage 输入目录(target 里还有旧版本/中间产物,不能全拷)
    $stage = Join-Path $env:TEMP "easygit-jpackage-input"
    if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $stage | Out-Null
    Copy-Item "target\easygit-$version.jar" $stage

    Write-Host "==> jpackage 生成应用镜像" -ForegroundColor Cyan
    if (Test-Path dist) { Remove-Item dist -Recurse -Force }
    jpackage `
        --name EasyGit `
        --app-version $version `
        --icon src\main\resources\icons\easygit.ico `
        --input $stage `
        --main-jar "easygit-$version.jar" `
        --type app-image `
        --dest dist `
        --java-options "-Dfile.encoding=UTF-8"
    if ($LASTEXITCODE -ne 0) { throw "jpackage 失败(需要 JDK 21 自带的 jpackage)" }
    Remove-Item $stage -Recurse -Force -ErrorAction SilentlyContinue

    Write-Host "==> 完成: dist\EasyGit\EasyGit.exe" -ForegroundColor Green
} finally {
    Pop-Location
}
