package org.easygit.core;

import org.easygit.core.model.ConflictModels.ConflictFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 冲突文件读写的编码/换行保真。
 *
 * 这些用例都是"字节级"的:解决冲突只该改冲突块那几行,文件其余部分一个字节都不能变
 * (CRLF 被改成 LF、GBK 文件被按 UTF-8 存回、BOM 丢失,都会让整个文件变成一处莫名其妙的改动)。
 */
class ConflictIOTest {

    private static Path tmp(String name) throws Exception {
        Path dir = Files.createTempDirectory("easygit-cio-");
        return dir.resolve(name);
    }

    private static byte[] bytes(Path p) throws Exception {
        return Files.readAllBytes(p);
    }

    @Test
    @DisplayName("LF + UTF-8:读回写回字节完全一致")
    void lfUtf8RoundTrip() throws Exception {
        Path f = tmp("a.txt");
        byte[] raw = "第一行\nsecond\n".getBytes(StandardCharsets.UTF_8);
        Files.write(f, raw);

        ConflictIO.Content c = ConflictIO.read(f);
        assertEquals(List.of("第一行", "second"), c.lines());
        assertEquals("LF", c.eolStyle());
        assertTrue(c.trailingNewline());
        assertEquals(StandardCharsets.UTF_8, c.charset());
        assertFalse(c.bom());

        ConflictIO.write(f, c, c.lines());
        assertArrayEquals(raw, bytes(f));
    }

    @Test
    @DisplayName("CRLF:解决冲突不能把整份文件改成 LF")
    void crlfPreserved() throws Exception {
        Path f = tmp("crlf.txt");
        byte[] raw = "line1\r\nline2\r\nline3\r\n".getBytes(StandardCharsets.UTF_8);
        Files.write(f, raw);

        ConflictIO.Content c = ConflictIO.read(f);
        assertEquals("CRLF", c.eolStyle());
        assertEquals(List.of("line1", "line2", "line3"), c.lines());

        ConflictIO.write(f, c, c.lines());
        assertArrayEquals(raw, bytes(f), "CRLF 文件必须原样写回,否则整文件都成了改动");
    }

    @Test
    @DisplayName("结尾没有换行的文件:写回也不能凭空加一行")
    void noTrailingNewlinePreserved() throws Exception {
        Path f = tmp("nonl.txt");
        byte[] raw = "a\nb".getBytes(StandardCharsets.UTF_8);
        Files.write(f, raw);

        ConflictIO.Content c = ConflictIO.read(f);
        assertFalse(c.trailingNewline());
        ConflictIO.write(f, c, c.lines());
        assertArrayEquals(raw, bytes(f));
    }

    @Test
    @DisplayName("UTF-8 BOM:读得出来也要写得回去")
    void bomPreserved() throws Exception {
        Path f = tmp("bom.txt");
        byte[] body = "中文\n".getBytes(StandardCharsets.UTF_8);
        byte[] raw = new byte[body.length + 3];
        raw[0] = (byte) 0xEF;
        raw[1] = (byte) 0xBB;
        raw[2] = (byte) 0xBF;
        System.arraycopy(body, 0, raw, 3, body.length);
        Files.write(f, raw);

        ConflictIO.Content c = ConflictIO.read(f);
        assertTrue(c.bom());
        assertEquals(List.of("中文"), c.lines(), "BOM 不能被当成正文");

        ConflictIO.write(f, c, c.lines());
        assertArrayEquals(raw, bytes(f));
    }

    @Test
    @DisplayName("GB18030 文件:不能按 UTF-8 读成乱码,写回仍是原编码")
    void gbkPreserved() throws Exception {
        Path f = tmp("gbk.txt");
        Charset gbk = Charset.forName("GB18030");
        byte[] raw = "中文注释\n冲突处理\n".getBytes(gbk);
        Files.write(f, raw);

        ConflictIO.Content c = ConflictIO.read(f);
        assertEquals(gbk, c.charset(), "非 UTF-8 文件必须识别为 GB18030");
        assertEquals(List.of("中文注释", "冲突处理"), c.lines());

        ConflictIO.write(f, c, c.lines());
        assertArrayEquals(raw, bytes(f), "GBK 文件被按 UTF-8 写回就毁了");
    }

    @Test
    @DisplayName("解决冲突:标记消失、CRLF 保留、其余行原样")
    void resolveConflictKeepsFormatting() throws Exception {
        Path f = tmp("conflict.txt");
        String content = "header\r\n"
                + "<<<<<<< HEAD\r\n"
                + "ours\r\n"
                + "=======\r\n"
                + "theirs\r\n"
                + ">>>>>>> origin/main\r\n"
                + "footer\r\n";
        Files.write(f, content.getBytes(StandardCharsets.UTF_8));

        ConflictIO.Content c = ConflictIO.read(f);
        ConflictFile cf = ConflictParser.parse("conflict.txt", c.lines());
        assertEquals(1, cf.regions.size());
        List<String> resolved = ConflictParser.resolve(cf, List.of(0), "theirs");
        ConflictIO.write(f, c, resolved);

        byte[] out = bytes(f);
        assertEquals("header\r\ntheirs\r\nfooter\r\n", new String(out, StandardCharsets.UTF_8));
        assertFalse(ConflictParser.hasMarkers(ConflictIO.read(f).lines()));
    }

    @Test
    @DisplayName("混合行尾的文件:每一行各自的行尾都要保住,不能被统一成一种")
    void mixedLineEndingsPreserved() throws Exception {
        Path f = tmp("mixed.txt");
        // 第 1 行 LF、第 2 行 CRLF、第 3 行 LF —— 真实仓库里很常见(部分行被编辑器改过)
        byte[] raw = "a\nb\r\nc\n".getBytes(StandardCharsets.UTF_8);
        Files.write(f, raw);

        ConflictIO.Content c = ConflictIO.read(f);
        assertEquals(List.of("a", "b", "c"), c.lines());
        assertEquals("\n", c.eol(0), "第 1 行是 LF");
        assertEquals("\r\n", c.eol(1), "第 2 行是 CRLF");
        assertEquals("\n", c.eol(2), "第 3 行是 LF");
        assertEquals("MIXED", c.eolStyle(), "混合行尾要被识别成 MIXED");

        // 原样写回:一个字节都不能变
        ConflictIO.write(f, c, c.lines());
        assertArrayEquals(raw, bytes(f),
                "混合行尾文件被统一成一种行尾 —— 整份文件都会变成改动");
    }

    @Test
    @DisplayName("混合行尾 + 解决冲突:只动冲突块,其余行的原行尾不变")
    void mixedLineEndingsSurviveConflictResolution() throws Exception {
        Path f = tmp("mixed-conflict.txt");
        byte[] raw = ("head\n"
                + "<<<<<<< HEAD\r\n"
                + "ours\r\n"
                + "=======\n"
                + "theirs\n"
                + ">>>>>>> origin/main\r\n"
                + "tail\n").getBytes(StandardCharsets.UTF_8);
        Files.write(f, raw);

        ConflictIO.Content c = ConflictIO.read(f);
        ConflictFile cf = ConflictParser.parse("mixed-conflict.txt", c.lines());
        List<String> resolved = ConflictParser.resolve(cf, List.of(0), "theirs");
        ConflictIO.write(f, c, resolved);

        String out = new String(bytes(f), StandardCharsets.UTF_8);
        assertEquals("head\ntheirs\ntail\n", out,
                "head/tail 是 LF,必须保持 LF(不能被冲突块里的 CRLF 带跑)");
    }

    @Test
    @DisplayName("行尾风格可查询:单一 LF / 单一 CRLF / 混合 各有明确结论")
    void eolStyleReported() throws Exception {
        Path lf = tmp("only-lf.txt");
        Files.write(lf, "a\nb\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("LF", ConflictIO.read(lf).eolStyle());

        Path crlf = tmp("only-crlf.txt");
        Files.write(crlf, "a\r\nb\r\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("CRLF", ConflictIO.read(crlf).eolStyle());

        Path mixed = tmp("mix.txt");
        Files.write(mixed, "a\r\nb\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("MIXED", ConflictIO.read(mixed).eolStyle());

        Path cr = tmp("only-cr.txt");
        Files.write(cr, "a\rb\r".getBytes(StandardCharsets.UTF_8));
        assertEquals("CR", ConflictIO.read(cr).eolStyle(), "老 Mac 风格也要认出来");
    }
}
