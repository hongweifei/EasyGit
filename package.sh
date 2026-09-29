#!/usr/bin/env bash
# EasyGit macOS / Linux 打包脚本:生成自包含的 dist/EasyGit/ 应用镜像
# 需要:JDK 21(含 jpackage)、Maven、系统 git
#
# 用法:
#   ./package.sh                 # 默认 app-image(macOS/Linux 通用)
#   TYPE=dmg ./package.sh        # macOS 生成 .dmg(需 hdiutil,系统自带)
#   TYPE=deb ./package.sh        # Debian/Ubuntu 生成 .deb(需 dpkg-deb)
#   TYPE=rpm ./package.sh        # Fedora/RHEL 生成 .rpm(需 rpmbuild)
set -euo pipefail
cd "$(dirname "$0")"

echo "==> Maven 打包 fat jar"
mvn -q package -DskipTests

# 版本号从 pom.xml 读取
VERSION="$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' pom.xml | head -1)"
if [ -z "$VERSION" ]; then
    echo "无法从 pom.xml 读取版本号" >&2
    exit 1
fi
echo "==> 版本: $VERSION"
# 开发期 pom 版本带 -SNAPSHOT 后缀;jpackage 只接受纯数字点分,发布名去掉后缀
APP_VERSION="${VERSION%-SNAPSHOT}"
if [ "$APP_VERSION" != "$VERSION" ]; then
    echo "==> SNAPSHOT 构建:安装包按 $APP_VERSION 发布"
fi

# 只把最终 jar 交给 jpackage(target 里还有旧版本与中间产物)
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
cp "target/easygit-$VERSION.jar" "$STAGE/"

TYPE="${TYPE:-app-image}"
OS="$(uname -s)"
ICON="src/main/resources/icons/icon_256.png"
if [ "$OS" = "Darwin" ] && [ "$TYPE" != "app-image" ]; then
    ICON="src/main/resources/icons/easygit.icns"   # 如提供 .icns 则优先使用
    [ -f "$ICON" ] || ICON="src/main/resources/icons/icon_256.png"
fi

echo "==> jpackage 生成 $TYPE"
rm -rf dist
jpackage \
    --name EasyGit \
    --app-version "$APP_VERSION" \
    --icon "$ICON" \
    --input "$STAGE" \
    --main-jar "easygit-$VERSION.jar" \
    --type "$TYPE" \
    --dest dist \
    --java-options "-Dfile.encoding=UTF-8"

echo "==> 完成: dist/"
ls -1 dist
