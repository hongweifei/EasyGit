import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.QuadCurve2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * EasyGit 图标生成器:蓝色圆角底 + 白色分支图形。
 * 产物:src/main/resources/icons/{icon_*.png, easygit.ico}
 * 运行:java tools/IconGen.java(在仓库根目录)
 */
public class IconGen {
    public static void main(String[] args) throws Exception {
        int S = 1024;
        BufferedImage master = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = master.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        // 底:蓝色渐变圆角方块
        g.setPaint(new GradientPaint(0, 0, new Color(0x4A9EFF), 0, S, new Color(0x0B4EA8)));
        g.fillRoundRect(0, 0, S, S, 232, 232);

        // 白色分支图形:主干 + 分支曲线 + 四个提交节点
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(58, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(368, 220, 368, 804);
        QuadCurve2D branch = new QuadCurve2D.Double(368, 536, 656, 536, 656, 804);
        g.draw(branch);
        Color mid = new Color(0x2A7DE1);
        for (int[] n : new int[][]{{368, 220}, {368, 536}, {368, 804}, {656, 804}}) {
            g.setColor(Color.WHITE);
            g.fillOval(n[0] - 70, n[1] - 70, 140, 140);
            g.setColor(mid);
            g.fillOval(n[0] - 28, n[1] - 28, 56, 56);
        }
        g.dispose();

        File dir = new File("src/main/resources/icons");
        dir.mkdirs();

        int[] sizes = {256, 128, 64, 48, 32, 16};
        List<BufferedImage> imgs = new ArrayList<>();
        for (int size : sizes) {
            BufferedImage scaled = resize(master, size);
            File png = new File(dir, "icon_" + size + ".png");
            ImageIO.write(scaled, "png", png);
            imgs.add(scaled);
            System.out.println("ok " + png);
        }
        writeIco(new File(dir, "easygit.ico"), sizes, imgs);
        System.out.println("ok " + new File(dir, "easygit.ico"));
    }

    static BufferedImage resize(BufferedImage src, int w) {
        BufferedImage out = new BufferedImage(w, w, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(src, 0, 0, w, w, null);
        g.dispose();
        return out;
    }

    static byte[] pngBytes(BufferedImage img) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bo);
        return bo.toByteArray();
    }

    /** ICO 组装:小端序!(DataOutputStream 是大端,之前全错)。条目用未压缩 BMP(DIB)。 */
    static void writeIco(File out, int[] sizes, List<BufferedImage> imgs) throws Exception {
        List<byte[]> dibs = new ArrayList<>();
        for (BufferedImage img : imgs) dibs.add(dibBytes(img));
        int total = 6 + 16 * sizes.length;
        for (byte[] dib : dibs) total += dib.length;
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(total)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) 0);   // reserved
        buf.putShort((short) 1);   // type: icon
        buf.putShort((short) sizes.length);
        int offset = 6 + 16 * sizes.length;
        for (int i = 0; i < sizes.length; i++) {
            int s = sizes[i];
            buf.put((byte) (s == 256 ? 0 : s)); // width
            buf.put((byte) (s == 256 ? 0 : s)); // height
            buf.put((byte) 0);                  // 调色板数
            buf.put((byte) 0);                  // reserved
            buf.putShort((short) 1);            // planes
            buf.putShort((short) 32);           // bpp
            buf.putInt(dibs.get(i).length);
            buf.putInt(offset);
            offset += dibs.get(i).length;
        }
        for (byte[] dib : dibs) buf.put(dib);
        java.nio.file.Files.write(out.toPath(), buf.array());
    }

    /** 32 位 BGRA DIB(BITMAPINFOHEADER + 自底向上像素 + 全 0 AND 掩码)。 */
    static byte[] dibBytes(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        java.nio.ByteBuffer hdr = java.nio.ByteBuffer.allocate(40).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        hdr.putInt(40);            // biSize
        hdr.putInt(w);             // biWidth
        hdr.putInt(h * 2);         // biHeight(XOR+AND)
        hdr.putShort((short) 1);   // biPlanes
        hdr.putShort((short) 32);  // biBitCount
        hdr.putInt(0);             // biCompression = BI_RGB
        hdr.putInt(w * h * 4);     // biSizeImage
        hdr.putInt(0); hdr.putInt(0); hdr.putInt(0); hdr.putInt(0);
        byte[] pixels = new byte[w * h * 4];
        int i = 0;
        for (int y = h - 1; y >= 0; y--) {           // 自底向上
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, y);
                pixels[i++] = (byte) (argb & 0xFF);        // B
                pixels[i++] = (byte) ((argb >> 8) & 0xFF); // G
                pixels[i++] = (byte) ((argb >> 16) & 0xFF);// R
                pixels[i++] = (byte) ((argb >> 24) & 0xFF);// A
            }
        }
        int maskRow = ((w + 31) / 32) * 4;
        byte[] mask = new byte[maskRow * h]; // 全 0 = 不透明
        byte[] out = new byte[40 + pixels.length + mask.length];
        System.arraycopy(hdr.array(), 0, out, 0, 40);
        System.arraycopy(pixels, 0, out, 40, pixels.length);
        System.arraycopy(mask, 0, out, 40 + pixels.length, mask.length);
        return out;
    }
}
