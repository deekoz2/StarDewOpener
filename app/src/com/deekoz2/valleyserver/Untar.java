package com.deekoz2.valleyserver;

import android.system.Os;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.zip.GZIPInputStream;

/**
 * 纯 Java 的 tar.gz 解包器（用于 rootfs，无需外部 busybox/tar）。
 * 支持 ustar + GNU 长文件名('L') + pax 头('x'，仅应用 path 覆盖)，
 * 普通文件 / 目录 / 符号链接 / 硬链接(以拷贝实现)。
 * 权限用 android.system.Os.chmod 还原（proot 里 guest 二进制需要真实的 exec 位）。
 */
public class Untar {

    public interface Progress {
        void onEntry(int count, String name);
    }

    public static void extractTarGz(InputStream gz, File destDir, Progress cb) throws Exception {
        InputStream in = new GZIPInputStream(gz, 1 << 16);
        byte[] hdr = new byte[512];
        int count = 0;
        String longName = null;
        HashMap<String, String> pax = new HashMap<String, String>();
        while (true) {
            if (!readFully(in, hdr)) {
                break;
            }
            boolean allZero = true;
            for (int i = 0; i < hdr.length; i++) {
                if (hdr[i] != 0) { allZero = false; break; }
            }
            if (allZero) {
                break;
            }
            String name = str(hdr, 0, 100);
            String prefix = str(hdr, 345, 155);
            long size = parseOctal(str(hdr, 124, 12));
            String type = str(hdr, 156, 1);
            String link = str(hdr, 157, 100);
            int mode = (int) parseOctal(str(hdr, 100, 8));
            String magic = str(hdr, 257, 6);
            if (prefix.length() > 0 && magic.startsWith("ustar")) {
                name = prefix + "/" + name;
            }
            if ("L".equals(type)) {                 // GNU 长文件名
                byte[] buf = new byte[(int) size];
                readFully(in, buf);
                longName = cstr(buf);
                continue;
            }
            if ("K".equals(type)) {                 // GNU 长链接名（忽略）
                skip(in, size);
                continue;
            }
            if ("x".equals(type) || "g".equals(type)) { // pax 头
                byte[] buf = new byte[(int) size];
                readFully(in, buf);
                if ("x".equals(type)) {
                    parsePax(buf, pax);
                }
                continue;
            }
            if (longName != null) {
                name = longName;
                longName = null;
            }
            if (pax.containsKey("path")) {
                name = pax.get("path");
            }
            pax.clear();

            while (name.startsWith("/")) {
                name = name.substring(1);
            }
            if (name.length() == 0 || name.contains("../")) {
                skip(in, size);
                continue;
            }

            File out = new File(destDir, name);
            if ("5".equals(type)) {                 // 目录
                out.mkdirs();
            } else if ("2".equals(type)) {          // 符号链接
                File pf = out.getParentFile();
                if (pf != null) {
                    pf.mkdirs();
                }
                try {
                    if (out.exists() && !out.isDirectory()) {
                        out.delete();
                    }
                    Os.symlink(link, out.getAbsolutePath());
                } catch (Exception e) {
                    // 已存在等场景忽略
                }
            } else if ("1".equals(type)) {          // 硬链接 -> 拷贝
                File pf = out.getParentFile();
                if (pf != null) {
                    pf.mkdirs();
                }
                String l = link.startsWith("/") ? link.substring(1) : link;
                File src = new File(destDir, l);
                if (src.isFile()) {
                    copyFile(src, out);
                }
            } else {                                // '0' 或 '\0' 普通文件
                File pf = out.getParentFile();
                if (pf != null) {
                    pf.mkdirs();
                }
                if (out.isDirectory()) {
                    out.delete();
                }
                writeFile(in, out, size);
            }
            if (!"2".equals(type)) {
                try {
                    Os.chmod(out.getAbsolutePath(), mode & 07777);
                } catch (Exception ignore) {
                }
            }
            count++;
            if (cb != null && (count % 500 == 0)) {
                cb.onEntry(count, name);
            }
        }
    }

    static boolean readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) {
                return false;
            }
            off += n;
        }
        return true;
    }

    static void skip(InputStream in, long n) throws IOException {
        long left = n;
        byte[] buf = new byte[65536];
        while (left > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (r < 0) {
                return;
            }
            left -= r;
        }
    }

    static void writeFile(InputStream in, File out, long size) throws Exception {
        FileOutputStream fos = new FileOutputStream(out);
        byte[] buf = new byte[65536];
        long left = size;
        while (left > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (n < 0) {
                break;
            }
            fos.write(buf, 0, n);
            left -= n;
        }
        fos.close();
        skip(in, (512 - (size % 512)) % 512);
    }

    static void copyFile(File src, File dst) throws IOException {
        FileInputStream fis = new FileInputStream(src);
        FileOutputStream fos = new FileOutputStream(dst);
        byte[] buf = new byte[65536];
        int n;
        while ((n = fis.read(buf)) > 0) {
            fos.write(buf, 0, n);
        }
        fis.close();
        fos.close();
    }

    static void parsePax(byte[] buf, HashMap<String, String> out) {
        String s = new String(buf, StandardCharsets.UTF_8);
        String[] lines = s.split("\n");
        for (int i = 0; i < lines.length; i++) {
            int eq = lines[i].indexOf('=');
            if (eq > 0) {
                int sp = lines[i].indexOf(' ');
                String key = lines[i].substring(sp + 1, eq);
                if (key.equals("path")) {
                    out.put("path", lines[i].substring(eq + 1));
                }
            }
        }
    }

    static String str(byte[] h, int off, int len) {
        int end = Math.min(h.length, off + len);
        int i = off;
        while (i < end && h[i] != 0) {
            i++;
        }
        return new String(h, off, i - off, StandardCharsets.UTF_8).trim();
    }

    static String cstr(byte[] b) {
        int i = 0;
        while (i < b.length && b[i] != 0) {
            i++;
        }
        return new String(b, 0, i, StandardCharsets.UTF_8).trim();
    }

    static long parseOctal(String s) {
        s = s.trim();
        if (s.length() == 0) {
            return 0;
        }
        try {
            return Long.parseLong(s.replace(" ", "").replace("\0", ""), 8);
        } catch (Exception e) {
            return 0;
        }
    }
}
