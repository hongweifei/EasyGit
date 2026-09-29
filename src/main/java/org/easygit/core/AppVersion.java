package org.easygit.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Properties;

/**
 * 应用自身版本号。单一事实来源是 pom.xml:构建期经资源过滤把
 * ${project.version} 与 ${maven.build.timestamp} 写入 app-version.properties,
 * 运行时从这里读取——避免"pom 一个版本、代码里又一个版本"的两处维护。
 *
 * <p>dev 版本管理:运行环境不是 jpackage 打包的应用(IDE 直接运行 /
 * mvn javafx:run / java -jar fat jar)即视为 dev 构建,展示为 "0.1.2-dev";
 * jpackage 启动器会设置 jpackage.app-version 系统属性,据此识别正式包,
 * 展示为 "0.1.2"。可用 -Deasygit.dev=false 强制按正式版展示(-Deasygit.dev
 * 或 =true 则强制按 dev 展示)。
 *
 * <p>注意:pom 的 version 必须保持 x.y.z 纯数字——jpackage 的 --app-version
 * 与打包脚本(package.ps1/package.sh)不接受 "-dev" 后缀,后缀只在展示层附加。
 */
public final class AppVersion {
    private static final String UNKNOWN = "0.0.0";
    private static final String VERSION;
    private static final String BUILD_TIME;

    static {
        Properties p = new Properties();
        // Properties.load(InputStream) 按 ISO-8859-1 解码,中文注释会乱码,这里显式 UTF-8
        try (InputStream in = AppVersion.class.getResourceAsStream("/app-version.properties")) {
            if (in != null) p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // 资源缺失(异常的类路径)时按未知版本处理,不影响启动
        }
        String v = p.getProperty("app.version", "").strip();
        VERSION = v.isEmpty() ? UNKNOWN : v;
        BUILD_TIME = prettify(p.getProperty("app.build.time", ""));
    }

    private AppVersion() {}

    /**
     * 构建时间统一显示为本地时区的 yyyy-MM-dd HH:mm。
     * 资源里注入的可能是 ISO/UTC 格式(如 2026-09-29T02:44:49Z,取决于 Maven
     * 是否套用 maven.build.timestamp.format),这里做归一;无法解析则原样返回。
     */
    private static String prettify(String raw) {
        String t = raw == null ? "" : raw.strip();
        if (t.isEmpty() || t.contains("${")) return "";
        try {
            return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .withZone(ZoneId.systemDefault())
                    .format(Instant.parse(t));
        } catch (DateTimeParseException ignored) {
            return t;
        }
    }

    /** pom.xml 里的版本号,如 0.1.2。 */
    public static String version() { return VERSION; }

    /** 构建时间(构建期注入),用于区分先后产生的 dev 构建;缺失时返回空串。 */
    public static String buildTime() { return BUILD_TIME; }

    /** 是否为 jpackage 打包的正式版(jpackage 启动器会设置该系统属性)。 */
    public static boolean isPackaged() {
        return System.getProperty("jpackage.app.version") != null;
    }

    /** 是否 dev 构建:非打包环境即 dev;可用 -Deasygit.dev=false 强制按正式版展示。 */
    public static boolean isDev() {
        String flag = System.getProperty("easygit.dev");
        if (flag == null) return !isPackaged();
        String v = flag.strip();
        return v.isEmpty() || Boolean.parseBoolean(v);
    }

    /** 展示用版本号:dev 构建带 "-dev" 后缀,如 0.1.2-dev。 */
    public static String display() { return isDev() ? VERSION + "-dev" : VERSION; }

    /** 完整描述:版本 + 构建时间(若有),用于欢迎页与关于信息。 */
    public static String full() {
        return BUILD_TIME.isEmpty() ? display() : display() + " (构建于 " + BUILD_TIME + ")";
    }
}
