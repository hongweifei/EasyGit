package org.easygit.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 改 `.gitattributes` 时的行尾保真。
 *
 * 这条路径曾经用 {@code Files.readAllLines} + {@code Files.write} —— 后者按**平台默认**行尾写
 * (Windows 上是 CRLF),于是删一条 LFS 规则会把整个文件的换行风格改掉,提交里多出一堆无关改动。
 * 这里按字节断言:只该少掉被删的那一行,其余一个字节都不能变。
 */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class LfsGitattributesEolTest {

    private Path base, repo;

    @BeforeEach
    void setUp() throws Exception {
        base = Files.createTempDirectory("easygit-ga-");
        repo = base.resolve("repo");
        Files.createDirectories(repo);
        git("init", "-q", "-b", "main");
        git("config", "user.email", "t@t");
        git("config", "user.name", "T");
        git("config", "commit.gpgsign", "false");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (base != null && Files.exists(base)) {
            try (var s = Files.walk(base)) {
                s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    @DisplayName("CRLF 的 .gitattributes:删一条规则不能把整份文件改成 LF")
    void crlfGitattributesPreserved() throws Exception {
        Path ga = repo.resolve(".gitattributes");
        byte[] raw = ("# 规则\r\n*.bin filter=lfs diff=lfs merge=lfs -text\r\n*.png binary\r\n")
                .getBytes(StandardCharsets.UTF_8);
        Files.write(ga, raw);

        var r = LfsService.untrack(repo, "*.bin");
        assertTrue(r.ok(), "删除规则应成功: " + r.message());

        byte[] after = Files.readAllBytes(ga);
        String text = new String(after, StandardCharsets.UTF_8);
        assertEquals("# 规则\r\n*.png binary\r\n", text,
                "只该少掉 *.bin 那一行,CRLF 必须原样保留");
    }

    @Test
    @DisplayName("LF 的 .gitattributes:也不能被改成平台默认行尾(Windows 上是 CRLF)")
    void lfGitattributesStaysLf() throws Exception {
        Path ga = repo.resolve(".gitattributes");
        byte[] raw = "# 规则\n*.bin filter=lfs -text\n*.png binary\n".getBytes(StandardCharsets.UTF_8);
        Files.write(ga, raw);

        var r = LfsService.untrack(repo, "*.bin");
        assertTrue(r.ok(), "删除规则应成功: " + r.message());

        byte[] after = Files.readAllBytes(ga);
        assertFalse(new String(after, StandardCharsets.UTF_8).contains("\r"),
                "LF 文件里不该出现任何 CR(那正是平台默认行尾造成的)");
        assertEquals("# 规则\n*.png binary\n", new String(after, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("带 BOM 的 .gitattributes:BOM 不能丢")
    void bomPreserved() throws Exception {
        Path ga = repo.resolve(".gitattributes");
        byte[] body = "*.bin filter=lfs -text\n".getBytes(StandardCharsets.UTF_8);
        byte[] raw = new byte[body.length + 3];
        raw[0] = (byte) 0xEF;
        raw[1] = (byte) 0xBB;
        raw[2] = (byte) 0xBF;
        System.arraycopy(body, 0, raw, 3, body.length);
        Files.write(ga, raw);

        // 删一条不存在的规则 → 不改文件;再删存在的 → 改完 BOM 还在
        var r = LfsService.untrack(repo, "*.bin");
        assertTrue(r.ok(), r.message());
        byte[] after = Files.readAllBytes(ga);
        assertEquals((byte) 0xEF, after[0], "BOM 第一个字节");
        assertEquals((byte) 0xBB, after[1]);
        assertEquals((byte) 0xBF, after[2]);
    }

    private void git(String... args) throws Exception {
        java.util.List<String> cmd = new java.util.ArrayList<>(java.util.List.of("git", "-C", repo.toString()));
        cmd.addAll(java.util.List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        if (code != 0) throw new IllegalStateException("git " + String.join(" ", args) + " → " + code + "\n" + out);
    }
}
