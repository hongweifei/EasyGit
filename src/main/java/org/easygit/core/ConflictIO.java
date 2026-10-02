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
     * @param eol             换行风格(\r\n 或 \n),按原文件里出现的第一种判定
     * @param trailingNewline 原文件是否以换行结尾(不然写回会凭空多/少一处差异)
     */
    public record Content(List<String> lines, Charset charset, boolean bom, String eol,
                          boolean trailingNewline) {

        /** 行列表还原成文本(含结尾换行,如原文件有)。 */
        public String text() {
            if (lines.isEmpty()) return "";
            String s = String.join(eol, lines);
            return trailingNewline ? s + eol : s;
        }
    }

    /** 读文件,保留编码/BOM/换行信息。 */
    public static Content read(Path file) throws IOException {
        byte[] raw = Files.readAllBytes(file);
        boolean bom = raw.length >= 3 && (raw[0] & 0xFF) == 0xEF
                && (raw[1] & 0xFF) == 0xBB && (raw[2] & 0xFF) == 0xBF;
        int off = bom ? 3 : 0;
        Charset cs = detectCharset(raw, off);
        String text = new String(raw, off, raw.length - off, cs);
        String eol = text.contains("\r\n") ? "\r\n" : "\n";
        boolean trailing = text.endsWith("\n") || text.endsWith("\r");
        return new Content(splitLines(text), cs, bom, eol, trailing);
    }

    /** 按原文件的编码/BOM/换行写回。 */
    public static void write(Path file, Content proto, List<String> lines) throws IOException {
        String text = lines.isEmpty() ? "" : String.join(proto.eol(), lines);
        if (!lines.isEmpty() && proto.trailingNewline()) text += proto.eol();
        byte[] body = text.getBytes(proto.charset());
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

    /** 按 \r\n / \n / \r 切行;结尾换行不产生幽灵空行(是否结尾换行见 trailingNewline)。 */
    private static List<String> splitLines(String text) {
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\n' && c != '\r') continue;
            int end = i;
            if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
            out.add(text.substring(start, end));
            start = i + 1;
        }
        if (start < text.length()) out.add(text.substring(start));
        return out;
    }
}
