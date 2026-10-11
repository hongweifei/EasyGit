package org.easygit.core;

import org.easygit.core.model.DiffModels.DiffFile;
import org.easygit.core.model.DiffModels.DiffLine;
import org.easygit.core.model.DiffModels.Eol;
import org.easygit.core.model.DiffModels.LineType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * diff 里的**行尾区分**:git diff 的原始输出逐行保留行尾,所以"整份 LF→CRLF"与"改了内容"
 * 在数据上是可以分开的 —— 关键是不能像旧实现那样一句 {@code endsWith("\r") ? 裁掉} 把信息丢掉。
 *
 * 用例跑**真实 git**(不是手搓 diff 字符串):手搓的字符串容易和 git 的实际输出不一致,
 * 而这里要证明的正是"git 给的信息我们没丢"。
 */
@Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class DiffEolTest {

    private Path base, repo;

    @BeforeEach
    void setUp() throws Exception {
        base = Files.createTempDirectory("easygit-eol-");
        repo = base.resolve("repo");
        Files.createDirectories(repo);
        git(repo, "init", "-q", "-b", "main");
        git(repo, "config", "user.email", "t@t");
        git(repo, "config", "user.name", "T");
        git(repo, "config", "commit.gpgsign", "false");
        // 关掉自动转换:这里要的是"字节上就是 CRLF/LF",而不是让 git 替我们转
        git(repo, "config", "core.autocrlf", "false");
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
    @DisplayName("LF 文件的普通改动:新增行被认出是 LF")
    void lfChangeLines() throws Exception {
        write("a.txt", "one\ntwo\n");
        commit("base");
        write("a.txt", "one\nCHANGED\n");

        DiffFile f = onlyFile(NativeGit.diffUnstaged(repo));
        assertEquals(Eol.LF, eolOf(f, LineType.ADD, "CHANGED"), "新增行应带 LF");
        assertFalse(f.eolOnly, "这是内容改动,不是纯行尾改动");
    }

    @Test
    @DisplayName("CRLF 文件的改动:新增行被认出是 CRLF(旧实现会当成 LF)")
    void crlfChangeLines() throws Exception {
        write("b.txt", "one\r\ntwo\r\n");
        commit("base");
        write("b.txt", "one\r\nCHANGED\r\n");

        DiffFile f = onlyFile(NativeGit.diffUnstaged(repo));
        assertEquals(Eol.CRLF, eolOf(f, LineType.ADD, "CHANGED"), "CRLF 文件的新增行必须认出 CRLF");
        assertEquals(Eol.CRLF, eolOf(f, LineType.DEL, "two"), "删除行也要带它原本的行尾");
    }

    @Test
    @DisplayName("整份 LF → CRLF(内容一字未变):必须识别为**纯行尾改动**,不能显示成内容全改")
    void eolOnlyChangeDetected() throws Exception {
        write("c.txt", "alpha\nbeta\ngamma\n");
        commit("base");
        write("c.txt", "alpha\r\nbeta\r\ngamma\r\n");   // 内容完全相同,只换行尾

        DiffFile f = onlyFile(NativeGit.diffUnstaged(repo));
        assertTrue(f.eolOnly, "内容一字未变、只有行尾不同 ⇒ 必须标记为纯行尾改动");
        assertEquals("LF", f.eolFrom);
        assertEquals("CRLF", f.eolTo);
    }

    @Test
    @DisplayName("反向 CRLF → LF 同样识别为纯行尾改动")
    void eolOnlyReverse() throws Exception {
        write("d.txt", "alpha\r\nbeta\r\n");
        commit("base");
        write("d.txt", "alpha\nbeta\n");

        DiffFile f = onlyFile(NativeGit.diffUnstaged(repo));
        assertTrue(f.eolOnly);
        assertEquals("CRLF", f.eolFrom);
        assertEquals("LF", f.eolTo);
    }

    @Test
    @DisplayName("内容与行尾同时改 → 不是纯行尾改动(不能误报)")
    void contentAndEolChangeIsNotEolOnly() throws Exception {
        write("e.txt", "alpha\nbeta\n");
        commit("base");
        write("e.txt", "alpha\r\nBETA\r\n");

        DiffFile f = onlyFile(NativeGit.diffUnstaged(repo));
        assertFalse(f.eolOnly, "文本也变了,不能报成纯行尾改动");
    }

    @Test
    @DisplayName("混合行尾的文件:每一行各带自己的行尾,不能被统一")
    void mixedEolsPerLine() throws Exception {
        write("f.txt", "a\nb\nc\n");
        commit("base");
        // 只把第 2 行改成 CRLF:同一次改动里 LF 与 CRLF 并存
        write("f.txt", "a\nb\r\nc\n");

        DiffFile f = onlyFile(NativeGit.diffUnstaged(repo));
        List<DiffLine> adds = linesOf(f, LineType.ADD);
        assertFalse(adds.isEmpty(), "应至少有一处新增");
        // 逐行核对:属于 CRLF 的那行要认出 CRLF,其余 LF
        boolean sawCrlf = adds.stream().anyMatch(l -> l.eol() == Eol.CRLF);
        assertTrue(sawCrlf, "混入的 CRLF 行必须被认出: " + adds);
    }

    @Test
    @DisplayName("文件末尾少一个换行(\\ No newline):该行行尾记作 NONE")
    void noNewlineAtEof() throws Exception {
        write("g.txt", "one\ntwo\n");
        commit("base");
        write("g.txt", "one\ntwo");   // 去掉末尾换行

        DiffFile f = onlyFile(NativeGit.diffUnstaged(repo));
        List<DiffLine> adds = linesOf(f, LineType.ADD);
        assertTrue(adds.stream().anyMatch(l -> l.eol() == Eol.NONE),
                "末尾缺换行的行要记成 NONE,否则会被当成内容改动: " + adds);
    }

    @Test
    @DisplayName("行尾枚举的文本与标签自洽(写回/显示两用)")
    void eolEnumHelpers() {
        assertEquals("\r\n", Eol.CRLF.text());
        assertEquals("\n", Eol.LF.text());
        assertEquals("", Eol.NONE.text());
        assertEquals(Eol.CRLF, Eol.of("\r\n"));
        assertEquals(Eol.LF, Eol.of("\n"));
        assertEquals(Eol.CR, Eol.of("\r"));
        assertEquals(Eol.NONE, Eol.of(""));
        assertEquals(Eol.NONE, Eol.of(null));
        assertEquals("CRLF", Eol.CRLF.label());
    }

    // ---------- 辅助 ----------

    private static DiffFile onlyFile(List<DiffFile> files) {
        assertEquals(1, files.size(), "夹具只有一个文件: " + files.size());
        return files.get(0);
    }

    private static List<DiffLine> linesOf(DiffFile f, LineType t) {
        List<DiffLine> out = new ArrayList<>();
        f.hunks.forEach(h -> h.lines.stream().filter(l -> l.type() == t).forEach(out::add));
        return out;
    }

    private static Eol eolOf(DiffFile f, LineType t, String text) {
        return linesOf(f, t).stream()
                .filter(l -> l.text().equals(text))
                .map(DiffLine::eol)
                .findFirst()
                .orElseThrow(() -> new AssertionError("diff 里没有 " + t + " 行 [" + text + "]: " + f.hunks));
    }

    private void write(String rel, String content) throws Exception {
        Files.write(repo.resolve(rel), content.getBytes(StandardCharsets.UTF_8));
    }

    private void commit(String msg) throws Exception {
        git(repo, "add", "-A");
        git(repo, "commit", "-q", "-m", msg);
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        if (code != 0) throw new IllegalStateException("git " + String.join(" ", args) + " → " + code + "\n" + out);
    }
}
