package org.easygit.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 轻量语法着色(逐行、无跨行状态)。
 *
 * 设计取舍:**只做行内切词**。diff 里看到的常常是一个 hunk 的片段,跨行状态(块注释/多行字符串)
 * 在片段里本来就不完整;而逐行切词没有状态机、没有回溯,任何一行都能独立算对 ——
 * 对"看得懂代码"这个目的足够,也不会因为某行截断而把后面整段染错。
 *
 * 不变式(有测试守):把返回的 span 依次拼起来,必须**逐字符等于**输入行 —— 着色绝不能
 * 增删或改动代码内容。
 *
 * 这里不碰 JavaFX:核心层不依赖界面,颜色由 CSS 令牌决定(见 theme.css 的 -syn-*)。
 */
public final class SyntaxHighlighter {

    private SyntaxHighlighter() {}

    /** 记号类别。PLAIN 表示普通代码/运算符,不着色。 */
    public enum Kind { PLAIN, KEYWORD, STRING, COMMENT, NUMBER, TYPE, FUNCTION, ANNOTATION }

    /** 一段同类的文本。 */
    public record Span(String text, Kind kind) {}

    /** 语言。NONE 表示不做任何着色(未知后缀)。 */
    public enum Language { NONE, JAVA, KOTLIN, MOONBIT, RUST, GO, C, CSHARP, JS, TS, PYTHON, RUBY, PHP, SWIFT, DART, LUA, SHELL, POWERSHELL, SQL, HTML, CSS, JSON, YAML, TOML, INI, MARKDOWN, GRADLE, CMAKE, R, PERL }

    // ---------- 语言识别 ----------

    /** 按文件名/路径后缀识别语言;认不出来返回 NONE。 */
    public static Language detect(String path) {
        if (path == null) return Language.NONE;
        String name = path.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        String lower = name.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        String ext = dot < 0 ? "" : lower.substring(dot + 1);
        return switch (ext) {
            case "java" -> Language.JAVA;
            case "kt", "kts" -> Language.KOTLIN;
            case "mbt" -> Language.MOONBIT;
            case "rs" -> Language.RUST;
            case "go" -> Language.GO;
            case "c", "h", "cc", "cpp", "cxx", "hpp", "hh" -> Language.C;
            case "cs" -> Language.CSHARP;
            case "js", "jsx", "mjs", "cjs" -> Language.JS;
            case "ts", "tsx" -> Language.TS;
            case "py", "pyi" -> Language.PYTHON;
            case "rb", "rake" -> Language.RUBY;
            case "php" -> Language.PHP;
            case "swift" -> Language.SWIFT;
            case "dart" -> Language.DART;
            case "lua" -> Language.LUA;
            case "sh", "bash", "zsh", "fish", "ksh" -> Language.SHELL;
            case "ps1", "psm1", "psd1" -> Language.POWERSHELL;
            case "sql" -> Language.SQL;
            case "html", "htm", "xml", "xhtml", "svg", "vue", "svelte" -> Language.HTML;
            case "css", "scss", "less" -> Language.CSS;
            case "json", "jsonc" -> Language.JSON;
            case "yml", "yaml" -> Language.YAML;
            case "toml" -> Language.TOML;
            case "ini", "conf", "cfg", "properties", "editorconfig" -> Language.INI;
            case "md", "markdown" -> Language.MARKDOWN;
            case "gradle" -> Language.GRADLE;
            case "cmake" -> Language.CMAKE;
            case "r" -> Language.R;
            case "pl", "pm" -> Language.PERL;
            // 无后缀但名字有含义的少数几个
            default -> switch (lower) {
                case "makefile", "cmakelists.txt" -> Language.CMAKE;
                case "dockerfile" -> Language.SHELL;
                default -> Language.NONE;
            };
        };
    }

    // ---------- 关键字表 ----------

    /**
     * 建关键字表并**去重**。
     *
     * 不用 {@code Set.of}:它对重复元素直接抛 IllegalArgumentException,而这些表是手写的长清单
     * (C/C++ 的 auto、PowerShell 的 function 都曾重复),一次笔误就是类初始化失败 ——
     * 表现为整个高亮 NoClassDefFoundError,而且只在第一次用到时才炸。去重是无害的。
     */
    private static Set<String> kw(String... words) {
        return Set.copyOf(java.util.List.of(words));
    }

    private static final Set<String> JAVA_KW = kw(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
            "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
            "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "var", "void",
            "volatile", "while", "record", "sealed", "permits", "yield", "non-sealed", "true", "false", "null");

    private static final Set<String> KOTLIN_KW = kw(
            "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface",
            "is", "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias",
            "typeof", "val", "var", "when", "while", "by", "catch", "constructor", "delegate", "dynamic",
            "field", "file", "finally", "get", "import", "init", "param", "property", "receiver", "set",
            "setparam", "where", "actual", "abstract", "annotation", "companion", "const", "crossinline",
            "data", "enum", "expect", "external", "final", "infix", "inline", "inner", "internal", "lateinit",
            "noinline", "open", "operator", "out", "override", "private", "protected", "public", "reified",
            "sealed", "suspend", "tailrec", "vararg");

    private static final Set<String> MOONBIT_KW = kw(
            "fn", "let", "var", "const", "type", "struct", "enum", "trait", "impl", "match", "if", "else",
            "while", "for", "in", "return", "break", "continue", "true", "false", "self", "Self", "mut",
            "pub", "priv", "readonly", "extern", "import", "package", "test", "async", "raise", "try",
            "catch", "guard", "is", "as", "with", "init", "derive", "where", "not", "and", "or", "noraise");

    private static final Set<String> RUST_KW = kw(
            "as", "async", "await", "break", "const", "continue", "crate", "dyn", "else", "enum", "extern",
            "false", "fn", "for", "if", "impl", "in", "let", "loop", "match", "mod", "move", "mut", "pub",
            "ref", "return", "self", "Self", "static", "struct", "super", "trait", "true", "type", "unsafe",
            "use", "where", "while", "union", "box", "macro", "yield");

    private static final Set<String> GO_KW = kw(
            "break", "case", "chan", "const", "continue", "default", "defer", "else", "fallthrough", "for",
            "func", "go", "goto", "if", "import", "interface", "map", "package", "range", "return", "select",
            "struct", "switch", "type", "var", "true", "false", "nil", "iota");

    private static final Set<String> C_KW = java.util.Set.copyOf(java.util.List.of(
            "auto", "break", "case", "char", "const", "continue", "default", "do", "double", "else", "enum",
            "extern", "float", "for", "goto", "if", "inline", "int", "long", "register", "restrict", "return",
            "short", "signed", "sizeof", "static", "struct", "switch", "typedef", "union", "unsigned", "void",
            "volatile", "while", "class", "namespace", "template", "typename", "public", "private",
            "protected", "virtual", "override", "new", "delete", "this", "nullptr", "true", "false", "using",
            "constexpr", "noexcept", "concept", "requires", "co_await", "co_return", "co_yield"));

    private static final Set<String> CSHARP_KW = kw(
            "abstract", "as", "base", "bool", "break", "byte", "case", "catch", "char", "checked", "class",
            "const", "continue", "decimal", "default", "delegate", "do", "double", "else", "enum", "event",
            "explicit", "extern", "false", "finally", "fixed", "float", "for", "foreach", "goto", "if",
            "implicit", "in", "int", "interface", "internal", "is", "lock", "long", "namespace", "new",
            "null", "object", "operator", "out", "override", "params", "private", "protected", "public",
            "readonly", "ref", "return", "sbyte", "sealed", "short", "sizeof", "stackalloc", "static",
            "string", "struct", "switch", "this", "throw", "true", "try", "typeof", "uint", "ulong",
            "unchecked", "unsafe", "ushort", "using", "var", "virtual", "void", "volatile", "while", "async",
            "await", "record", "init", "nameof", "when");

    private static final Set<String> JS_KW = kw(
            "async", "await", "break", "case", "catch", "class", "const", "continue", "debugger", "default",
            "delete", "do", "else", "export", "extends", "false", "finally", "for", "function", "if",
            "import", "in", "instanceof", "let", "new", "null", "of", "return", "static", "super", "switch",
            "this", "throw", "true", "try", "typeof", "undefined", "var", "void", "while", "with", "yield",
            "interface", "type", "enum", "implements", "declare", "readonly", "namespace", "abstract",
            "as", "satisfies", "keyof", "infer", "is", "asserts");

    private static final Set<String> PY_KW = kw(
            "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del", "elif",
            "else", "except", "False", "finally", "for", "from", "global", "if", "import", "in", "is",
            "lambda", "None", "nonlocal", "not", "or", "pass", "raise", "return", "True", "try", "while",
            "with", "yield", "match", "case", "self", "cls");

    private static final Set<String> RUBY_KW = kw(
            "alias", "and", "begin", "break", "case", "class", "def", "defined?", "do", "else", "elsif",
            "end", "ensure", "false", "for", "if", "in", "module", "next", "nil", "not", "or", "redo",
            "rescue", "retry", "return", "self", "super", "then", "true", "undef", "unless", "until",
            "when", "while", "yield", "require", "require_relative", "attr_accessor", "attr_reader",
            "attr_writer", "puts", "raise", "lambda", "proc", "new");

    private static final Set<String> PHP_KW = kw(
            "abstract", "and", "array", "as", "break", "callable", "case", "catch", "class", "clone",
            "const", "continue", "declare", "default", "do", "echo", "else", "elseif", "empty", "enddeclare",
            "endfor", "endforeach", "endif", "endswitch", "endwhile", "enum", "extends", "final", "finally",
            "fn", "for", "foreach", "function", "global", "goto", "if", "implements", "include", "include_once",
            "instanceof", "insteadof", "interface", "isset", "list", "match", "namespace", "new", "or",
            "print", "private", "protected", "public", "readonly", "require", "require_once", "return",
            "static", "switch", "throw", "trait", "try", "unset", "use", "var", "while", "xor", "yield",
            "true", "false", "null");

    private static final Set<String> SWIFT_KW = kw(
            "actor", "any", "as", "associatedtype", "async", "await", "break", "case", "catch", "class",
            "continue", "default", "defer", "deinit", "do", "else", "enum", "extension", "fallthrough",
            "false", "fileprivate", "for", "func", "guard", "if", "import", "in", "indirect", "init",
            "inout", "internal", "is", "lazy", "let", "nil", "open", "operator", "private", "protocol",
            "public", "repeat", "rethrows", "return", "self", "Self", "static", "struct", "subscript",
            "super", "switch", "throw", "throws", "true", "try", "typealias", "var", "where", "while",
            "some", "weak", "unowned", "mutating", "nonmutating", "convenience", "required", "final");

    private static final Set<String> DART_KW = kw(
            "abstract", "as", "assert", "async", "await", "break", "case", "catch", "class", "const",
            "continue", "covariant", "default", "deferred", "do", "dynamic", "else", "enum", "export",
            "extends", "extension", "external", "factory", "false", "final", "finally", "for", "Function",
            "get", "hide", "if", "implements", "import", "in", "interface", "is", "late", "library",
            "mixin", "new", "null", "on", "operator", "part", "required", "rethrow", "return", "sealed",
            "set", "show", "static", "super", "switch", "sync", "this", "throw", "true", "try", "typedef",
            "var", "void", "while", "with", "yield");

    private static final Set<String> LUA_KW = kw(
            "and", "break", "do", "else", "elseif", "end", "false", "for", "function", "goto", "if", "in",
            "local", "nil", "not", "or", "repeat", "return", "then", "true", "until", "while");

    private static final Set<String> SHELL_KW = kw(
            "if", "then", "else", "elif", "fi", "case", "esac", "for", "while", "until", "do", "done",
            "function", "in", "select", "time", "return", "exit", "break", "continue", "local", "export",
            "readonly", "declare", "typeset", "unset", "shift", "source", "alias", "echo", "printf",
            "read", "cd", "set", "trap", "eval", "exec", "test");

    private static final Set<String> PS_KW = kw(
            "begin", "break", "catch", "class", "continue", "data", "do", "dynamicparam", "else", "elseif",
            "end", "enum", "exit", "filter", "finally", "for", "foreach", "from", "function", "if", "in",
            "param", "process", "return", "switch", "throw", "trap", "try", "until", "using", "var", "while",
            "workflow", "true", "false", "null", "function", "filter", "echo", "write-host", "write-output");

    private static final Set<String> SQL_KW = kw(
            "add", "all", "alter", "and", "any", "as", "asc", "backup", "between", "by", "case", "check",
            "column", "constraint", "create", "database", "default", "delete", "desc", "distinct", "drop",
            "exec", "exists", "foreign", "from", "full", "group", "having", "in", "index", "inner", "insert",
            "into", "is", "join", "key", "left", "like", "limit", "not", "null", "offset", "on", "or",
            "order", "outer", "primary", "procedure", "references", "right", "rownum", "select", "set",
            "table", "top", "truncate", "union", "unique", "update", "values", "view", "where", "with");

    private static final Set<String> CSS_KW = kw(
            "important", "media", "supports", "keyframes", "import", "charset", "font-face", "from", "to",
            "and", "not", "only", "screen", "print", "root", "inherit", "initial", "unset", "auto", "none",
            "block", "flex", "grid", "absolute", "relative", "fixed", "sticky", "hidden", "solid", "dashed",
            "bold", "italic", "center", "transparent", "currentColor");

    private static final Set<String> YAML_KW = kw(
            "true", "false", "null", "yes", "no", "on", "off", "~");

    private static final Set<String> TOML_KW = kw("true", "false");

    private static final Set<String> CMAKE_KW = kw(
            "if", "else", "elseif", "endif", "foreach", "endforeach", "while", "endwhile", "function",
            "endfunction", "macro", "endmacro", "set", "unset", "project", "add_executable", "add_library",
            "target_link_libraries", "target_include_directories", "include", "find_package", "message",
            "option", "return", "break", "continue", "list", "string", "file", "install");

    private static final Set<String> R_KW = kw(
            "if", "else", "repeat", "while", "function", "for", "in", "next", "break", "TRUE", "FALSE",
            "NULL", "Inf", "NaN", "NA", "library", "require", "return", "print", "cat");

    private static final Set<String> PERL_KW = kw(
            "my", "our", "local", "sub", "if", "elsif", "else", "unless", "while", "until", "for", "foreach",
            "do", "return", "last", "next", "redo", "use", "require", "package", "print", "say", "die",
            "warn", "defined", "undef", "ref", "scalar", "bless", "qw", "and", "or", "not", "eq", "ne",
            "lt", "gt", "le", "ge");

    private static final Set<String> GRADLE_KW = kw(
            "apply", "plugins", "dependencies", "repositories", "buildscript", "allprojects", "subprojects",
            "task", "tasks", "def", "if", "else", "for", "while", "return", "new", "class", "interface",
            "extends", "implements", "import", "package", "void", "static", "final", "true", "false", "null",
            "implementation", "api", "testImplementation", "compileOnly", "runtimeOnly", "id", "version",
            "group", "sourceCompatibility", "targetCompatibility", "mavenCentral", "google", "jcenter");

    private static final Set<String> MARKDOWN_KW = kw("TODO", "FIXME", "NOTE", "WARNING");

    /** 语言 → 关键字表。 */
    private static Set<String> keywords(Language lang) {
        return switch (lang) {
            case JAVA -> JAVA_KW;
            case KOTLIN -> KOTLIN_KW;
            case GRADLE -> GRADLE_KW;
            case MOONBIT -> MOONBIT_KW;
            case RUST -> RUST_KW;
            case GO -> GO_KW;
            case C -> C_KW;
            case CSHARP -> CSHARP_KW;
            case JS, TS -> JS_KW;
            case PYTHON -> PY_KW;
            case RUBY -> RUBY_KW;
            case PHP -> PHP_KW;
            case SWIFT -> SWIFT_KW;
            case DART -> DART_KW;
            case LUA -> LUA_KW;
            case SHELL -> SHELL_KW;
            case POWERSHELL -> PS_KW;
            case SQL -> SQL_KW;
            case CSS -> CSS_KW;
            case YAML -> YAML_KW;
            case TOML -> TOML_KW;
            case CMAKE -> CMAKE_KW;
            case R -> R_KW;
            case PERL -> PERL_KW;
            case MARKDOWN -> MARKDOWN_KW;
            default -> kw();
        };
    }

    /** 行注释起始符(按语言)。空数组表示该语言没有行注释。 */
    private static String[] lineComment(Language lang) {
        return switch (lang) {
            case JAVA, KOTLIN, MOONBIT, RUST, GO, C, CSHARP, JS, TS, SWIFT, DART, GRADLE -> new String[]{"//"};
            case PYTHON, RUBY, SHELL, POWERSHELL, PERL, R, YAML, TOML, CMAKE -> new String[]{"#"};
            case LUA -> new String[]{"--"};
            case SQL -> new String[]{"--"};
            case PHP -> new String[]{"//", "#"};
            case INI -> new String[]{";", "#"};
            case HTML, CSS -> new String[]{};     // 交给块注释处理
            default -> new String[]{};
        };
    }

    /** 是否支持块注释 {@code /* … *}{@code /}。 */
    private static boolean blockComment(Language lang) {
        return switch (lang) {
            case JAVA, KOTLIN, MOONBIT, RUST, GO, C, CSHARP, JS, TS, SWIFT, DART, CSS, PHP, GRADLE -> true;
            default -> false;
        };
    }

    /** 字符串定界符。 */
    private static String[] quotes(Language lang) {
        return switch (lang) {
            case JS, TS -> new String[]{"\"", "'", "`"};
            case PYTHON -> new String[]{"\"", "'"};
            case SHELL, POWERSHELL, PERL, PHP -> new String[]{"\"", "'"};
            case SQL -> new String[]{"'", "\""};
            case LUA -> new String[]{"\"", "'"};
            case RUBY -> new String[]{"\"", "'"};
            case CSS, HTML, YAML, TOML, INI, JSON -> new String[]{"\"", "'"};
            case CMAKE -> new String[]{"\""};
            default -> new String[]{"\""};
        };
    }

    /** 该语言的标识符里允许出现的额外符号($ 在 JS/PHP/PowerShell/Perl/Shell 里合法)。 */
    private static boolean identChar(char c, Language lang) {
        if (Character.isLetterOrDigit(c) || c == '_') return true;
        if (c != '$') return false;
        return dollarIsIdent(lang);
    }

    /** $ 是否算标识符字符(与 {@link #identStart} 必须一致,否则会取到空词)。 */
    private static boolean dollarIsIdent(Language lang) {
        return lang == Language.JS || lang == Language.TS || lang == Language.PHP
                || lang == Language.POWERSHELL || lang == Language.PERL || lang == Language.SHELL;
    }

    private static boolean identStart(char c, Language lang) {
        if (Character.isLetter(c) || c == '_') return true;
        return c == '$' && dollarIsIdent(lang);
    }

    // ---------- 切词 ----------

    private static final int CACHE_MAX = 4096;
    private static final Map<String, List<Span>> CACHE =
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, List<Span>> e) {
                    return size() > CACHE_MAX;
                }
            };

    /** 清空缓存(测试用)。 */
    static void clearCache() {
        synchronized (CACHE) { CACHE.clear(); }
    }

    /** 切词(带缓存:同一行在虚拟化列表里会随滚动反复重建单元格)。 */
    public static List<Span> highlight(Language lang, String line) {
        if (line == null || line.isEmpty()) return List.of();
        if (lang == null || lang == Language.NONE) return List.of(new Span(line, Kind.PLAIN));
        String key = lang + "\u0000" + line;
        synchronized (CACHE) {
            List<Span> hit = CACHE.get(key);
            if (hit != null) return hit;
        }
        List<Span> spans = tokenize(lang, line);
        synchronized (CACHE) { CACHE.put(key, spans); }
        return spans;
    }

    static List<Span> tokenize(Language lang, String line) {
        List<Span> out = new ArrayList<>();
        String[] comments = lineComment(lang);
        boolean block = blockComment(lang);
        String[] quotes = quotes(lang);
        Set<String> kw = keywords(lang);
        int n = line.length();
        int i = 0;
        while (i < n) {
            char c = line.charAt(i);

            // 空白原样保留(并入前一个 PLAIN,避免产生大量只有一个空格的 span)
            if (Character.isWhitespace(c)) {
                int j = i;
                while (j < n && Character.isWhitespace(line.charAt(j))) j++;
                append(out, line.substring(i, j), Kind.PLAIN);
                i = j;
                continue;
            }

            // 块注释(行内闭合就只吃到 */,否则吃到行尾 —— 逐行切词的边界)
            if (block && c == '/' && i + 1 < n && line.charAt(i + 1) == '*') {
                int end = line.indexOf("*/", i + 2);
                int stop = end < 0 ? n : end + 2;
                append(out, line.substring(i, stop), Kind.COMMENT);
                i = stop;
                continue;
            }

            // 行注释
            String matched = null;
            for (String p : comments) {
                if (line.startsWith(p, i)) { matched = p; break; }
            }
            if (matched != null) {
                // Shell/INI 里 '#' 出现在词中(如 a#b)不算注释;简化:行首或前面是空白/行首才算
                boolean ok = i == 0 || Character.isWhitespace(line.charAt(i - 1))
                        || matched.equals("//") || matched.equals("--");
                if (ok) {
                    append(out, line.substring(i), Kind.COMMENT);
                    i = n;
                    continue;
                }
            }

            // HTML 注释 <!-- … --> 与 CDATA 粗处理
            if (lang == Language.HTML && line.startsWith("<!--", i)) {
                int end = line.indexOf("-->", i + 4);
                int stop = end < 0 ? n : end + 3;
                append(out, line.substring(i, stop), Kind.COMMENT);
                i = stop;
                continue;
            }

            // 字符串
            boolean quote = false;
            for (String q : quotes) {
                if (line.startsWith(q, i)) { quote = true; break; }
            }
            if (quote) {
                char q = c;
                int j = i + 1;
                while (j < n) {
                    char d = line.charAt(j);
                    if (d == '\\' && j + 1 < n) { j += 2; continue; }
                    if (d == q) { j++; break; }
                    j++;
                }
                append(out, line.substring(i, j), Kind.STRING);
                i = j;
                continue;
            }

            // 注解/装饰器:@Name(Java/Kotlin/Swift/Python 装饰器用 @)
            if (c == '@' && (lang == Language.JAVA || lang == Language.KOTLIN || lang == Language.SWIFT
                    || lang == Language.PYTHON || lang == Language.CSHARP)) {
                int j = i + 1;
                while (j < n && (Character.isLetterOrDigit(line.charAt(j)) || line.charAt(j) == '_'
                        || line.charAt(j) == '.')) j++;
                append(out, line.substring(i, j), Kind.ANNOTATION);
                i = j;
                continue;
            }

            // 数字(含 0x/0b/下划线/小数/科学计数/后缀)
            if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(line.charAt(i + 1)))) {
                int j = i;
                while (j < n && (Character.isLetterOrDigit(line.charAt(j)) || line.charAt(j) == '.'
                        || line.charAt(j) == '_')) j++;
                append(out, line.substring(i, j), Kind.NUMBER);
                i = j;
                continue;
            }

            // 标识符
            if (identStart(c, lang)) {
                int j = i;
                while (j < n && identChar(line.charAt(j), lang)) j++;
                // $ 在多数语言里不是标识符字符,而 identStart 认它(为 JS/PHP/Shell 留的)——
                // 两者不一致时这里会取到空词并让 i 原地不动 = 死循环(测试里 echo "$USER" 跑遍
                // 所有语言时踩到)。取不到字符就交给下面的"其他字符"分支。
                if (j > i) {
                    String word = line.substring(i, j);
                    Kind kind;
                    if (kw.contains(word)) {
                        kind = Kind.KEYWORD;
                    } else if (j < n && line.charAt(j) == '(') {
                        kind = Kind.FUNCTION;
                    } else if (!word.isEmpty() && Character.isUpperCase(word.charAt(0))) {
                        kind = Kind.TYPE;
                    } else {
                        kind = Kind.PLAIN;
                    }
                    append(out, word, kind);
                    i = j;
                    continue;
                }
            }

            // 其他(运算符/标点/中文):并入 PLAIN,连续取到下一个"有意思"的起点
            int j = i;
            while (j < n && !interestingStart(lang, line, j, comments, block, quotes)) j++;
            if (j == i) j = i + 1;   // 兜底:保证一定前进
            append(out, line.substring(i, j), Kind.PLAIN);
            i = j;
        }
        return out;
    }

    /** 该位置是否是某一类记号的起点。 */
    private static boolean interestingStart(Language lang, String line, int i, String[] comments,
                                            boolean block, String[] quotes) {
        char c = line.charAt(i);
        if (Character.isWhitespace(c) || Character.isDigit(c) || identStart(c, lang)) return true;
        if (c == '@' && (lang == Language.JAVA || lang == Language.KOTLIN || lang == Language.SWIFT
                || lang == Language.PYTHON || lang == Language.CSHARP)) return true;
        if (c == '.' && i + 1 < line.length() && Character.isDigit(line.charAt(i + 1))) return true;
        if (block && c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '*') return true;
        if (lang == Language.HTML && line.startsWith("<!--", i)) return true;
        for (String p : comments) if (line.startsWith(p, i)) return true;
        for (String q : quotes) if (line.startsWith(q, i)) return true;
        return false;
    }

    /** 合并相邻同类 span:减少 Text 节点数(每行节点越少,虚拟化列表越省)。 */
    private static void append(List<Span> out, String text, Kind kind) {
        if (text.isEmpty()) return;
        if (!out.isEmpty()) {
            Span last = out.get(out.size() - 1);
            if (last.kind() == kind) {
                out.set(out.size() - 1, new Span(last.text() + text, kind));
                return;
            }
        }
        out.add(new Span(text, kind));
    }

    /** 是否含任何非 PLAIN 记号(调用方据此跳过 TextFlow,直接用 Label 更快)。 */
    public static boolean hasHighlight(List<Span> spans) {
        for (Span s : spans) if (s.kind() != Kind.PLAIN) return true;
        return false;
    }
}
