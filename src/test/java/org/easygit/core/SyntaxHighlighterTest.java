package org.easygit.core;

import org.easygit.core.SyntaxHighlighter.Kind;
import org.easygit.core.SyntaxHighlighter.Language;
import org.easygit.core.SyntaxHighlighter.Span;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 语法着色的切词。
 *
 * 最要紧的一条是**无损**:着色只改颜色,绝不能改代码内容 ——
 * 把 span 依次拼起来必须逐字符等于输入行。diff 是给人核对代码的,
 * 少一个字符都可能让人做出错误判断。
 */
class SyntaxHighlighterTest {

    /** 拼回去必须与原文完全一致(所有用例共用的不变式)。 */
    private static void assertLossless(Language lang, String line) {
        StringBuilder sb = new StringBuilder();
        for (Span s : SyntaxHighlighter.highlight(lang, line)) sb.append(s.text());
        assertEquals(line, sb.toString(), "着色不能改动内容: " + line);
    }

    private static Kind kindAt(Language lang, String line, String needle) {
        int at = line.indexOf(needle);
        assertTrue(at >= 0, "用例写错了,行里没有 " + needle);
        int pos = 0;
        for (Span s : SyntaxHighlighter.highlight(lang, line)) {
            if (pos <= at && at < pos + s.text().length()) return s.kind();
            pos += s.text().length();
        }
        return null;
    }

    // ---------- 语言识别 ----------

    @Test
    @DisplayName("按后缀识别语言;认不出返回 NONE(不着色)")
    void detectsLanguage() {
        assertEquals(Language.JAVA, SyntaxHighlighter.detect("src/main/java/A.java"));
        assertEquals(Language.KOTLIN, SyntaxHighlighter.detect("a/b/Main.kt"));
        assertEquals(Language.MOONBIT, SyntaxHighlighter.detect("pkg/x.mbt"));
        assertEquals(Language.RUST, SyntaxHighlighter.detect("src/lib.rs"));
        assertEquals(Language.PYTHON, SyntaxHighlighter.detect("tool.py"));
        assertEquals(Language.YAML, SyntaxHighlighter.detect("ci.yml"));
        assertEquals(Language.CMAKE, SyntaxHighlighter.detect("CMakeLists.txt"));
        assertEquals(Language.SHELL, SyntaxHighlighter.detect("Dockerfile"));
        assertEquals(Language.CSS, SyntaxHighlighter.detect("css/theme.css"));
        // 大小写与路径分隔符都要能吃下
        assertEquals(Language.JAVA, SyntaxHighlighter.detect("A\\B\\C.JAVA"));
        // 认不出来就是不着色,而不是猜一个
        assertEquals(Language.NONE, SyntaxHighlighter.detect("data.bin"));
        assertEquals(Language.NONE, SyntaxHighlighter.detect("noext"));
        assertEquals(Language.NONE, SyntaxHighlighter.detect(null));
    }

    // ---------- 无损 ----------

    @Test
    @DisplayName("无损:各种刁钻行拼回去都与原文一致")
    void losslessOnTrickyLines() {
        String[] javaLines = {
                "public final class A { // 注释里也有 \"引号\" 和 中文",
                "    String s = \"a\\\"b\\\\c\";   // 转义",
                "    /* 块注释 */ int x = 0x1F_2A + 1.5e-3f;",
                "    /* 未闭合的块注释吃到行尾",
                "    @Override public void run() { if (x > 1 && y < 2) return; }",
                "    中文标识符 = 1;   // 中文变量名",
                "}",
                "",
                "   ",
        };
        for (String l : javaLines) assertLossless(Language.JAVA, l);

        String[] others = {
                "echo \"hi $USER\" # 注释",
                "SELECT * FROM t WHERE a = 'x' -- 注释",
                "key: value # yaml 注释",
                "fn main() { let x: i32 = 42; }",
                "<div class=\"a\"><!-- 注释 --></div>",
                "let s = `tpl ${x}`;",
        };
        for (String l : others) {
            for (Language lang : Language.values()) assertLossless(lang, l);
        }
    }

    @Test
    @DisplayName("空行/空白行不产生 span,也不会抛异常")
    void emptyLines() {
        assertTrue(SyntaxHighlighter.highlight(Language.JAVA, "").isEmpty());
        assertTrue(SyntaxHighlighter.highlight(Language.JAVA, null).isEmpty());
        // 纯空白也要无损
        assertLossless(Language.JAVA, "      ");
    }

    // ---------- 分类 ----------

    @Test
    @DisplayName("Java:关键字/字符串/注释/数字/注解/类型各自归类")
    void javaKinds() {
        String line = "    @Override public String name = \"x\"; // 说明";
        assertEquals(Kind.ANNOTATION, kindAt(Language.JAVA, line, "@Override"));
        assertEquals(Kind.KEYWORD, kindAt(Language.JAVA, line, "public"));
        assertEquals(Kind.TYPE, kindAt(Language.JAVA, line, "String"));
        assertEquals(Kind.STRING, kindAt(Language.JAVA, line, "\"x\""));
        assertEquals(Kind.COMMENT, kindAt(Language.JAVA, line, "// 说明"));

        String num = "int n = 0x1F_2A + 3.14e-2;";
        assertEquals(Kind.NUMBER, kindAt(Language.JAVA, num, "0x1F_2A"));
        assertEquals(Kind.NUMBER, kindAt(Language.JAVA, num, "3.14e-2"));
        assertEquals(Kind.KEYWORD, kindAt(Language.JAVA, num, "int"));
    }

    @Test
    @DisplayName("方法调用(标识符后紧跟左括号)归为 FUNCTION")
    void functionCall() {
        String line = "    refreshAll();";
        assertEquals(Kind.FUNCTION, kindAt(Language.JAVA, line, "refreshAll"));
        // 只是声明里的类型不该被当成函数
        assertEquals(Kind.TYPE, kindAt(Language.JAVA, "    String s = \"\";", "String"));
    }

    @Test
    @DisplayName("块注释在行内闭合时只吃到 */,后面的代码照常着色")
    void blockCommentClosesInLine() {
        String line = "/* c */ int x = 1;";
        assertEquals(Kind.COMMENT, kindAt(Language.JAVA, line, "/* c */"));
        assertEquals(Kind.KEYWORD, kindAt(Language.JAVA, line, "int"));
        assertEquals(Kind.NUMBER, kindAt(Language.JAVA, line, "1"));
    }

    @Test
    @DisplayName("未闭合的块注释吃到行尾(逐行切词的边界,不吞下一行)")
    void blockCommentRunsToEndOfLine() {
        List<Span> spans = SyntaxHighlighter.highlight(Language.JAVA, "   /* 还没闭合");
        // 前导空白是 PLAIN,注释从 /* 起到行尾
        Span comment = spans.stream().filter(s -> s.kind() == Kind.COMMENT).findFirst().orElseThrow();
        assertEquals("/* 还没闭合", comment.text());
        assertLossless(Language.JAVA, "   /* 还没闭合");
    }

    @Test
    @DisplayName("字符串里的注释符号不算注释")
    void commentSymbolsInsideString() {
        String line = "String url = \"http://x/y\";";
        assertEquals(Kind.STRING, kindAt(Language.JAVA, line, "\"http://x/y\""));
    }

    @Test
    @DisplayName("Shell/INI 的 # 只在行首或空白后才算注释")
    void hashCommentOnlyAtStart() {
        String url = "echo a#b";                 // 词中的 # 不是注释
        assertEquals(Kind.PLAIN, kindAt(Language.SHELL, url, "#b"));
        String real = "echo x  # 说明";
        assertEquals(Kind.COMMENT, kindAt(Language.SHELL, real, "# 说明"));
    }

    @Test
    @DisplayName("未知语言/无扩展名:整行 PLAIN,不着色也不报错")
    void noneLanguageIsPlain() {
        List<Span> spans = SyntaxHighlighter.highlight(Language.NONE, "class A { }");
        assertEquals(1, spans.size());
        assertEquals(Kind.PLAIN, spans.get(0).kind());
        assertFalse(SyntaxHighlighter.hasHighlight(spans));
    }

    @Test
    @DisplayName("hasHighlight 能区分'有色彩'与'整行普通代码'(调用方据此省掉 TextFlow)")
    void hasHighlightFlag() {
        assertFalse(SyntaxHighlighter.hasHighlight(
                SyntaxHighlighter.highlight(Language.JAVA, "x = y + z;")));
        assertTrue(SyntaxHighlighter.hasHighlight(
                SyntaxHighlighter.highlight(Language.JAVA, "int x = 1;")));
    }

    @Test
    @DisplayName("缓存不会把不同语言/不同行串味")
    void cacheIsKeyedProperly() {
        SyntaxHighlighter.clearCache();
        String line = "if (x) return 1;";
        // 同一行在 Java 与 Python 下的分类不同(Java 的 if 是关键字,Python 也是;换个更像的对比)
        String shell = "# comment";
        assertEquals(Kind.COMMENT, SyntaxHighlighter.highlight(Language.SHELL, shell).get(0).kind());
        assertEquals(Kind.PLAIN, SyntaxHighlighter.highlight(Language.JAVA, shell).get(0).kind());
        // 再取一次(命中缓存)结果必须一致
        assertEquals(Kind.COMMENT, SyntaxHighlighter.highlight(Language.SHELL, shell).get(0).kind());
        assertLossless(Language.JAVA, line);
    }

    @Test
    @DisplayName("相邻同类记号会被合并(减少每行 Text 节点数)")
    void adjacentSameKindMerged() {
        // 真正相邻(中间无空白)的同类记号合并成一个 span
        List<Span> spans = SyntaxHighlighter.highlight(Language.JAVA, "a.b.c");
        assertEquals(1, spans.size(), "整行都是 PLAIN 时应合并成一个 span: " + spans);
        assertEquals(Kind.PLAIN, spans.get(0).kind());

        // 空白会把关键字隔开 —— 这是刻意的:空格并入 PLAIN 后不再产生"只有一个空格的 span",
        // 但也不会为了合并而跨越空格,否则着色会跨过真实内容
        List<Span> spaced = SyntaxHighlighter.highlight(Language.JAVA, "int long x;");
        assertEquals(2, spaced.stream().filter(s -> s.kind() == Kind.KEYWORD).count(),
                "被空白隔开的关键字各自成 span: " + spaced);
        assertLossless(Language.JAVA, "int long x;");
    }

    @Test
    @DisplayName("中文与 emoji 不会被吃掉")
    void unicodePreserved() {
        String line = "// 中文注释 🎉 与符号 →";
        assertLossless(Language.JAVA, line);
        assertEquals(Kind.COMMENT, kindAt(Language.JAVA, line, "// 中文注释 🎉 与符号 →"));
    }

    /**
     * 切词必须**对任何输入都终止**。
     *
     * 这条是踩出来的:identStart 认 '$' 而 identChar(Java) 不认,标识符分支取到空词后 i 原地不动
     * → 死循环(测试挂住 10 分钟才发现)。所有"扫描 + 前进"的循环都要有这种兜底用例。
     */
    @Test
    @DisplayName("任意输入都必须终止,且无损(含 $、孤立引号、超长符号串)")
    void alwaysTerminatesAndIsLossless() {
        String[] nasty = {
                "$", "$USER", "a$b", "${x}", "$1", "$$", "$ ",
                "\"", "'", "`", "\\", "/*", "*/", "//", "#", "--", "<!--",
                "a\"b'c`d", "0x", "1e", "..", "...", "λ", "中文$中文",
                "\t\t$x\t", "))))", "((((", "@", "@@", "@(", "::", "->>",
        };
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(20), () -> {
            for (Language lang : Language.values()) {
                for (String line : nasty) {
                    List<Span> spans = SyntaxHighlighter.highlight(lang, line);
                    StringBuilder sb = new StringBuilder();
                    for (Span s : spans) sb.append(s.text());
                    assertEquals(line, sb.toString(), lang + " 下着色改动了内容: " + line);
                }
            }
        }, "切词没有终止 —— 某个分支没有前进");
    }

    @Test
    @DisplayName("$ 在 JS/Shell/PHP 里是标识符字符,在 Java 里不是(两个判据必须一致,否则空词死循环)")
    void dollarIsLanguageDependent() {
        // JS 里 $ 与 $x 都该是标识符,不能被拆成碎片
        assertEquals(Kind.PLAIN, kindAt(Language.JS, "var $ = 1;", "$"));
        // Java 里 $ 是普通字符:照样无损,但不能取到空词(终止性由上面的用例守)
        assertLossless(Language.JAVA, "int a$b = 1;");
        assertLossless(Language.SHELL, "echo \"$USER\"");
        assertLossless(Language.PHP, "$x = 1;");
    }
}
