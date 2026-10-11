package org.easygit.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 冲突文件的读写:解决冲突时**必须原样保留**编码、BOM 与换行风格。
 *
 * 旧实现是 {@code Files.readAllLines} 读、{@code Files.writeString(UTF-8)} 写,有三个实际后果:
 * <ul>
 *   <li>CRLF 文件被整份改写成 LF —— 只解决一个冲突块,整个文件却显示成"全部改动";</li>
 *   <li>GBK/GB18030 文件(中文 Windows 上的老仓库)读进来就是乱码,存回去直接毁掉文件;</li>
 *   <li>BOM 被丢掉,UTF-8 BOM 文件首行平白多出一处差异。</li>
 * </ul>
 * 这里把「字节 → 行 + 元信息」和「行 + 元信息 → 字节」配成一对,
 * 保证没有冲突的部分一个字节都不变。
 */
public final class ConflictIO {

    private ConflictIO() {}

    /**
     * 读取结果:文件行 + 还原所需的元信息。
     *
     * @param lines           文件行(不含换行符)
     * @param charset         原文件编码,写回时沿用
     * @param bom             是否带 UTF-8 BOM
     * @param eols            **每一行各自的行尾**("\r\n" / "\n" / "\r" / "" 表示最后一行没有行尾);
     *                        逐行记而不是记一个"文件级风格",因为混合行尾的文件真实存在
     * @param trailingNewline 原文件是否以换行结尾(不然写回会凭空多/少一处差异)
     */
    public record Content(List<String> lines, Charset charset, boolean bom, List<String> eols,
                          boolean trailingNewline) {

        /**
         * 文件级行尾风格:"LF" / "CRLF" / "CR" / "MIXED" / "NONE"(空文件)。
         * 用来**告诉用户**这个文件是哪一种 —— 混合行尾是个值得看见的事实,不该被悄悄统一。
         */
        public String eolStyle() {
            String only = null;
            for (String e : eols) {
                if (e.isEmpty()) continue;          // 末行无行尾,不参与风格判定
                if (only == null) only = e;
                else if (!only.equals(e)) return "MIXED";
            }
            if (only == null) return "NONE";
            return switch (only) {
                case "\r\n" -> "CRLF";
                case "\n" -> "LF";
                case "\r" -> "CR";
                default -> "OTHER";
            };
        }

        /** 第 i 行的行尾(越界或没有行尾时返回空串)。 */
        public String eol(int i) {
            if (i < 0 || i >= eols.size()) return "";
            return eols.get(i);
        }

        /** 写回时用的"文件级"行尾:单一风格用它的;混合时用最常见的(逐行写回仍以 {@link #eols} 为准)。 */
        public String dominantEol() {
            String only = null;
            int crlf = 0, lf = 0;
            for (String e : eols) {
                if (e.isEmpty()) continue;
                if (only == null) only = e;
                if ("\r\n".equals(e)) crlf++;
                else if ("\n".equals(e)) lf++;
            }
            if (only == null) return "\n";
            if (crlf == 0 && lf == 0) return only;
            return crlf >= lf ? "\r\n" : "\n";
        }

        /** 行列表还原成文本(逐行用各自的行尾)。 */
        public String text() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < lines.size(); i++) {
                sb.append(lines.get(i));
                String e = eol(i);
                if (!e.isEmpty()) sb.append(e);
            }
            return sb.toString();
        }
    }

    /** 读文件,保留编码/BOM/**逐行行尾**信息。 */
    public static Content read(Path file) throws IOException {
        byte[] raw = Files.readAllBytes(file);
        boolean bom = raw.length >= 3 && (raw[0] & 0xFF) == 0xEF
                && (raw[1] & 0xFF) == 0xBB && (raw[2] & 0xFF) == 0xBF;
        int off = bom ? 3 : 0;
        Charset cs = detectCharset(raw, off);
        String text = new String(raw, off, raw.length - off, cs);
        Split s = splitLines(text);
        return new Content(s.lines(), cs, bom, s.eols(), s.trailing());
    }

    /**
     * 按原文件的编码/BOM/换行写回。
     *
     * 行尾**按来源对齐**(见 {@link #alignEols}):每一行尽量用它原本那一行的行尾。
     * 这样"只解决一个冲突块"不会把其余任何一行的行尾改掉 —— 包括冲突块**之后**的行
     * (块被替换后行号会平移,单纯按下标取行尾会把后面的行全带错)。
     */
    public static void write(Path file, Content proto, List<String> lines) throws IOException {
        List<String> eols = alignEols(proto, lines);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            sb.append(lines.get(i));
            if (i == lines.size() - 1 && !proto.trailingNewline()) break;   // 原文件末行无行尾
            sb.append(eols.get(i));
        }
        byte[] body = sb.toString().getBytes(proto.charset());
        if (!proto.bom()) {
            Files.write(file, body);
            return;
        }
        byte[] all = new byte[body.length + 3];
        all[0] = (byte) 0xEF;
        all[1] = (byte) 0xBB;
        all[2] = (byte) 0xBF;
        System.arraycopy(body, 0, all, 3, body.length);
        Files.write(file, all);
    }

    /** 向后查找同文本行的窗口:冲突块一般只有几行,查太远没有意义还会把重复行对错。 */
    private static final int LOOKAHEAD = 64;

    /**
     * 为每一行输出对齐一个行尾。
     *
     * 规则(单调前进,不回头):
     * <ol>
     *   <li>源文件同下标那行文本相同 → 用它那一行的行尾(未改动的行,绝大多数走这条);</li>
     *   <li>否则从游标往后找 {@value #LOOKAHEAD} 行内第一个同文本的行 → 用它(冲突块被替换后,
     *       后面的行会平移,靠这条找回自己的行尾);</li>
     *   <li>都找不到(新增行 / 用户改过的行)→ 用文件的主导行尾,绝不用"同下标那行"的行尾
     *       (那可能正是被删掉的冲突标记行)。</li>
     * </ol>
     */
    static List<String> alignEols(Content proto, List<String> lines) {
        List<String> out = new ArrayList<>(lines.size());
        int cursor = 0;
        for (int i = 0; i < lines.size(); i++) {
            String text = lines.get(i);
            int hit = -1;
            if (i < proto.lines().size() && proto.lines().get(i).equals(text)) {
                hit = i;
            } else {
                int end = Math.min(proto.lines().size(), Math.max(cursor, i) + LOOKAHEAD);
                for (int k = Math.max(cursor, i); k < end; k++) {
                    if (proto.lines().get(k).equals(text)) { hit = k; break; }
                }
            }
            if (hit >= 0) {
                cursor = hit + 1;
                out.add(proto.eol(hit).isEmpty() ? proto.dominantEol() : proto.eol(hit));
            } else {
                out.add(proto.dominantEol());
            }
        }
        return out;
    }

    /**
     * 编码判定:合法 UTF-8 就用 UTF-8(纯 ASCII 也走这条);
     * 否则按 GB18030 —— 它是 GBK/GB2312 的超集,中文 Windows 上的老仓库大多是这一类。
     */
    private static Charset detectCharset(byte[] raw, int off) {
        if (isValidUtf8(raw, off)) return StandardCharsets.UTF_8;
        return Charset.forName("GB18030");
    }

    private static boolean isValidUtf8(byte[] raw, int off) {
        CharsetDecoder d = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            d.decode(ByteBuffer.wrap(raw, off, raw.length - off));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    /** 切行结果:行 + **每行各自的行尾** + 是否以换行结尾。 */
    private record Split(List<String> lines, List<String> eols, boolean trailing) {}

    /**
     * 按 \r\n / \n / \r 切行,**逐行记下它用的行尾**。
     *
     * 旧实现只记一个文件级风格({@code contains("\r\n") ? CRLF : LF}),于是混合行尾的文件
     * (部分行被编辑器改过、或某工具只改了新增行)写回时会被整份统一成一种 —— 正好是这个类
     * 要防的"只解决一处、整文件变红"。逐行记才能做到"没动的行一个字节都不变"。
     *
     * 结尾换行不产生幽灵空行(是否结尾换行见 {@link Content#trailingNewline})。
     */
    private static Split splitLines(String text) {
        List<String> out = new ArrayList<>();
        List<String> eols = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\n' && c != '\r') continue;
            String eol;
            if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                eol = "\r\n";
                i++;
            } else {
                eol = String.valueOf(c);
            }
            out.add(text.substring(start, i + 1 - eol.length()));
            eols.add(eol);
            start = i + 1;
        }
        boolean trailing = false;
        if (start < text.length()) {
            out.add(text.substring(start));   // 末行没有行尾
            eols.add("");
        } else if (!eols.isEmpty()) {
            trailing = true;                  // 文本以换行结尾
        }
        return new Split(out, eols, trailing);
    }
}
