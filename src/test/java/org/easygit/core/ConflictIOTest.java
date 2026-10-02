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
        assertEquals("\n", c.eol());
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
        assertEquals("\r\n", c.eol());
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
}
