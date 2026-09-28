# EasyGit 桌面打包脚本:生成自包含的 dist\EasyGit\EasyGit.exe
# 需要:JDK 21(含 jpackage)、Maven、系统 git
$ErrorActionPreference = "Stop"

Push-Location $PSScriptRoot
try {
    Write-Host "==> Maven 打包 fat jar" -ForegroundColor Cyan
    mvn -q package -DskipTests
    if ($LASTEXITCODE -ne 0) { throw "Maven 构建失败" }

    Write-Host "==> jpackage 生成应用镜像" -ForegroundColor Cyan
    if (Test-Path dist) { Remove-Item dist -Recurse -Force }
    jpackage `
        --name EasyGit `
        --app-version 0.1.0 `
        --input target `
        --main-jar easygit-0.1.0.jar `
        --type app-image `
        --dest dist `
        --java-options "-Dfile.encoding=UTF-8"
    if ($LASTEXITCODE -ne 0) { throw "jpackage 失败(需要 JDK 21 自带的 jpackage)" }

    Write-Host "==> 完成: dist\EasyGit\EasyGit.exe" -ForegroundColor Green
} finally {
    Pop-Location
}
