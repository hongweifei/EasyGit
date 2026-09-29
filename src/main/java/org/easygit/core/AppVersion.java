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
 * <p>版本周期(s semver 惯例):
 * <ul>
 *   <li>开发期:pom 写 "下一版本-SNAPSHOT"(如发布 0.1.3 后进入 0.1.4 开发,则写
 *       0.1.4-SNAPSHOT;大版本则写 0.2.0-SNAPSHOT),dev 构建展示为 "0.1.4-dev";</li>
 *   <li>发布:去掉 -SNAPSHOT 后缀即为正式版本号(打包脚本也会自动剥掉),
 *       正式包展示为 "0.1.4"。</li>
 * </ul>
 *
 * <p>dev 识别:运行环境不是 jpackage 打包的应用(IDE 直接运行 /
 * mvn javafx:run / java -jar fat jar)即视为 dev 构建;jpackage 启动器会设置
 * jpackage.app-version 系统属性,据此识别正式包。可用 -Deasygit.dev=false
 * 强制按正式版展示(-Deasygit.dev 或 =true 则强制按 dev 展示)。
 */
public final class AppVersion {
    private static final String UNKNOWN = "0.0.0";
    private static final String SNAPSHOT_SUFFIX = "-SNAPSHOT";
    private static final String RAW_VERSION;
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
        RAW_VERSION = v.isEmpty() ? UNKNOWN : v;
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

    /** pom.xml 里的原始版本号,开发期可能带 -SNAPSHOT 后缀(如 0.1.4-SNAPSHOT)。 */
    public static String rawVersion() { return RAW_VERSION; }

    /** 是否处于 SNAPSHOT 开发期(pom 版本带 -SNAPSHOT 后缀)。 */
    public static boolean snapshot() { return RAW_VERSION.endsWith(SNAPSHOT_SUFFIX); }

    /** 生效版本号:去掉 -SNAPSHOT 后缀,如 0.1.4。 */
    public static String version() { return stripSnapshot(RAW_VERSION); }

    static String stripSnapshot(String v) {
        return v.endsWith(SNAPSHOT_SUFFIX) ? v.substring(0, v.length() - SNAPSHOT_SUFFIX.length()) : v;
    }

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

    /** 展示用版本号:dev 构建带 "-dev" 后缀,如 0.1.4-dev(0.1.4 为生效版本号)。 */
    public static String display() { return isDev() ? version() + "-dev" : version(); }

    /** 完整描述:版本 + 构建时间(若有),用于欢迎页与关于信息。 */
    public static String full() {
        return BUILD_TIME.isEmpty() ? display() : display() + " (构建于 " + BUILD_TIME + ")";
    }
}
