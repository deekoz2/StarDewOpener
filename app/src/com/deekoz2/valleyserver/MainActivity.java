package com.deekoz2.valleyserver;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 星露谷开服器 v2.0 —— 完全独立，不再依赖 Termux / tmoe / 安装脚本。
 *
 * 内置 proot 容器管理：
 *  ① 初始化：下载 Ubuntu arm64 rootfs（gzip）+ ValleyServer（zip），
 *     纯 Java 解包，proot 及依赖库由 APK 自带；
 *  ② 启动/停止服务器：前台服务托管 proot 进程，控制台实时回显；
 *  ③ 反馈问题：一键提交 GitHub Issue。
 */
public class MainActivity extends Activity {

    static final String ISSUES_URL = "https://github.com/deekoz2/StarDewOpener/issues/new";

    // rootfs: Ubuntu 22.04.5 base arm64（含 bash/glibc/libstdc++，约 26MB）
    // 国内镜像优先（阿里云/华为云/清华/北外），官方与 ghfast 兜底
    static final String[] ROOTFS_URLS = {
            "https://mirrors.aliyun.com/ubuntu-cdimage/ubuntu-base/releases/22.04/release/ubuntu-base-22.04.5-base-arm64.tar.gz",
            "https://mirrors.huaweicloud.com/ubuntu-cdimage/ubuntu-base/releases/22.04/release/ubuntu-base-22.04.5-base-arm64.tar.gz",
            "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/22.04/release/ubuntu-base-22.04.5-base-arm64.tar.gz",
            "https://mirrors.bfsu.edu.cn/ubuntu-cdimage/ubuntu-base/releases/22.04/release/ubuntu-base-22.04.5-base-arm64.tar.gz",
            "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04.5-base-arm64.tar.gz",
            "https://ghfast.top/https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04.5-base-arm64.tar.gz",
    };
    static final long ROOTFS_SIZE = 27671306L;

    // 服务器：ValleyServer v3.0.3 linux-arm64（DSH 修复版，约 448MB）
    static final String SERVER_ASSET =
            "https://github.com/deekoz2/ValleyServer-arm64/releases/download/v3.0.3-arm64-dsh/ValleyServer-linux-arm64.zip";
    static final String[] SERVER_URLS = {
            "https://ghfast.top/" + SERVER_ASSET,
            "https://gh-proxy.com/" + SERVER_ASSET,
            SERVER_ASSET,
    };
    static final long SERVER_SIZE = 469458959L;

    static final String[] BIN_ASSETS = { "proot", "loader", "libtalloc.so.2", "libandroid-shmem.so" };

    Handler ui;
    TextView containerStatus;
    TextView serverStatus;
    Button btnInit;
    Button btnStart;
    Button btnStop;
    TextView logView;
    ScrollView logScroll;
    final List<String> history = new ArrayList<String>();
    volatile boolean initBusy;

    File baseDir;
    File binDir;
    File dlDir;
    File rootfsDir;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ui = new Handler(Looper.getMainLooper());
        baseDir = getDir("valley", MODE_PRIVATE);
        binDir = new File(baseDir, "bin");
        dlDir = new File(baseDir, "dl");
        rootfsDir = new File(baseDir, "rootfs");
        binDir.mkdirs();
        dlDir.mkdirs();
        setContentView(buildUi());
        refreshStatus();
        ValleyService.ui = serviceListener();
        for (String line : ValleyService.logHistory) {
            append(line);
        }
        if (ValleyService.proc != null) {
            append("（服务器正在运行中）");
        }
    }

    @Override
    protected void onDestroy() {
        ValleyService.ui = null;
        super.onDestroy();
    }

    ValleyService.Listener serviceListener() {
        return new ValleyService.Listener() {
            @Override public void onServerLog(String line) { append(line); }
            @Override public void onServerExit(int code) {
                append("── 服务器已退出（code=" + code + "）──");
                refreshStatus();
            }
        };
    }

    /* ---------------- 界面 ---------------- */

    View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0B1220);
        root.setPadding(dp(20), dp(24), dp(20), dp(16));

        root.addView(text("星露谷开服器", 22, 0xFFF8FAFC, Typeface.BOLD));
        root.addView(text("v" + versionOf(getPackageName()) + " · 内置 proot 容器 · 无需 Termux/tmoe",
                12, 0xFF94A3B8, Typeface.NORMAL));
        root.addView(gap(14));

        LinearLayout card = card();
        containerStatus = text("容器：检测中…", 14, 0xFFE2E8F0, Typeface.NORMAL);
        serverStatus = text("服务器：未运行", 14, 0xFFE2E8F0, Typeface.NORMAL);
        card.addView(containerStatus);
        card.addView(gap(4));
        card.addView(serverStatus);
        root.addView(card);
        root.addView(gap(12));

        btnInit = button("① 初始化容器与服务器（首次约 500MB）", 0xFF9A3412, new View.OnClickListener() {
            @Override public void onClick(View v) { startInit(); }
        });
        root.addView(btnInit);
        root.addView(gap(10));
        btnStart = button("② 启动服务器（前台托管，UDP 24642）", 0xFF166534, new View.OnClickListener() {
            @Override public void onClick(View v) { startServer(); }
        });
        root.addView(btnStart);
        root.addView(gap(10));
        btnStop = button("③ 停止服务器", 0xFF7F1D1D, new View.OnClickListener() {
            @Override public void onClick(View v) { ValleyService.stopServer(); }
        });
        root.addView(btnStop);
        root.addView(gap(10));
        root.addView(button("反馈问题（提交 GitHub Issue）", 0xFF0F766E, new View.OnClickListener() {
            @Override public void onClick(View v) { feedbackDialog(); }
        }));
        root.addView(gap(10));
        root.addView(button("使用说明", 0xFF334155, new View.OnClickListener() {
            @Override public void onClick(View v) { showHelp(); }
        }));

        root.addView(gap(14));
        root.addView(text("运行日志 / 服务器控制台", 13, 0xFFCBD5E1, Typeface.BOLD));
        root.addView(gap(6));
        logScroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(11);
        logView.setTextColor(0xFFA7F3D0);
        logView.setText("就绪。\n");
        logScroll.addView(logView);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        logScroll.setLayoutParams(lp);
        root.addView(logScroll);
        return root;
    }

    TextView text(String s, int sp, int color, int style) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.DEFAULT, style);
        return t;
    }

    LinearLayout gap(int h) {
        LinearLayout v = new LinearLayout(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(h)));
        return v;
    }

    LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF121B2C);
        bg.setCornerRadius(dp(14));
        c.setBackground(bg);
        return c;
    }

    Button button(String label, int color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(14);
        b.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(12));
        b.setBackground(bg);
        b.setOnClickListener(l);
        return b;
    }

    int dp(int d) {
        return (int) (d * getResources().getDisplayMetrics().density);
    }

    /* ---------------- 状态 ---------------- */

    boolean isInitialized() {
        return new File(rootfsDir, "usr/lib/aarch64-linux-gnu/libc.so.6").exists()
                && new File(rootfsDir, "opt/valleyserver/ValleyServer").exists();
    }

    boolean serverRunning() {
        return ValleyService.proc != null;
    }

    void refreshStatus() {
        if (isInitialized()) {
            containerStatus.setText("容器：就绪（rootfs + 服务器已安装）");
        } else {
            containerStatus.setText("容器：未初始化（点 ① 开始下载安装）");
        }
        if (serverRunning()) {
            serverStatus.setText("服务器：运行中（UDP 24642）");
        } else {
            serverStatus.setText("服务器：未运行");
        }
    }

    /* ---------------- ① 初始化 ---------------- */

    void startInit() {
        if (initBusy) {
            log("初始化正在进行中，请稍候…");
            return;
        }
        if (serverRunning()) {
            log("✗ 请先停止服务器再初始化");
            return;
        }
        initBusy = true;
        btnInit.setEnabled(false);
        new Thread() {
            @Override
            public void run() {
                try {
                    doInit();
                } catch (final Throwable t) {
                    log("✗ 初始化失败：" + t);
                    log("  可点「反馈问题」把日志发给我们");
                } finally {
                    initBusy = false;
                    ui.post(new Runnable() {
                        @Override public void run() {
                            btnInit.setEnabled(true);
                            refreshStatus();
                        }
                    });
                }
            }
        }.start();
    }

    void doInit() throws Exception {
        log("── 初始化开始 ──");
        copyBinaries();
        log("✓ 内置 proot 就绪");

        // 网络自检（每个探测硬超时 8 秒，DNS 卡死也不会拖住）
        log("网络自检中…");
        String b = probe("https://www.baidu.com");
        String a = probe("https://mirrors.aliyun.com");
        String g = probe("https://github.com");
        log("百度=" + b + "  阿里云=" + a + "  GitHub=" + g);
        if (!b.equals("200") && !b.equals("301") && !b.equals("302")) {
            log("✗ 设备无法联网：这台平板很可能限制了本 APP 的网络（学生模式/家长控制）");
            log("  请到 平板设置 → 应用管理 或 学生模式 → 星露谷开服器 → 允许联网/关闭限制，");
            log("  然后完全退出本 APP 重新打开再点 ①");
            throw new Exception("设备网络受限（百度不通=" + b + "）");
        }
        if (!a.equals("200")) {
            log("⚠ 阿里云镜像不通（" + a + "），将自动尝试其它镜像");
        }

        File rootfsGz = new File(dlDir, "ubuntu-base-arm64.tar.gz");
        download("rootfs", ROOTFS_URLS, ROOTFS_SIZE, rootfsGz);
        log("解压 rootfs（1-2 分钟）…");
        if (rootfsDir.exists()) {
            deleteRecur(rootfsDir);
        }
        rootfsDir.mkdirs();
        FileInputStream fis = new FileInputStream(rootfsGz);
        try {
            Untar.extractTarGz(fis, rootfsDir, new Untar.Progress() {
                @Override public void onEntry(int count, String name) {
                    logThrottle("解压中… " + count + " 项");
                }
            });
        } finally {
            fis.close();
        }
        log("✓ rootfs 解压完成");

        File serverZip = new File(dlDir, "valleyserver.zip");
        download("服务器", SERVER_URLS, SERVER_SIZE, serverZip);
        log("解压服务器（约 1-2 分钟）…");
        File appDir = new File(rootfsDir, "opt/valleyserver");
        deleteRecur(appDir);
        appDir.mkdirs();
        unzip(serverZip, appDir);
        // zip 内有一层 ValleyServer-linux-arm64/ 目录，把内容上移
        File inner = new File(appDir, "ValleyServer-linux-arm64");
        if (inner.isDirectory()) {
            File[] kids = inner.listFiles();
            if (kids != null) {
                for (int i = 0; i < kids.length; i++) {
                    File dst = new File(appDir, kids[i].getName());
                    if (!kids[i].renameTo(dst)) {
                        copyTree(kids[i], dst);
                        deleteRecur(kids[i]);
                    }
                }
            }
            inner.delete();
        }
        File elf = new File(appDir, "ValleyServer");
        if (!elf.isFile()) {
            throw new Exception("解压后未找到 ValleyServer 可执行文件");
        }
        chmodExec(elf);
        File patch = new File(appDir, "patch-arm64.sh");
        if (patch.isFile()) {
            chmodExec(patch);
        }
        // 注：patch-arm64.sh 面向源码树（找 publish/ 目录），release 包无需执行，故跳过
        log("✓ 服务器解压完成");

        log("── 初始化完成！点 ② 启动服务器 ──");
    }

    void copyBinaries() throws Exception {
        for (int i = 0; i < BIN_ASSETS.length; i++) {
            File out = new File(binDir, BIN_ASSETS[i]);
            if (!out.exists()) {
                InputStream in = getAssets().open("bin/" + BIN_ASSETS[i]);
                FileOutputStream fos = new FileOutputStream(out);
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                }
                in.close();
                fos.close();
            }
            chmodExec(out);
        }
    }

    /* ---------------- 下载（镜像 + 断点续传 + 进度） ---------------- */

    void download(final String tag, final String[] urls, final long size, final File out) throws Exception {
        if (out.isFile() && out.length() == size) {
            log(tag + " 已存在完整下载，跳过");
            return;
        }
        for (int i = 0; i < urls.length; i++) {
            String u = urls[i];
            log(tag + " 下载源 " + (i + 1) + "/" + urls.length + ": "
                    + u.replaceFirst("https?://", "").substring(0, Math.min(40, u.replaceFirst("https?://", "").length())) + "…");
            try {
                downloadOne(u, size, out);
                if (out.length() == size) {
                    log("✓ " + tag + " 下载校验通过（" + size + " 字节）");
                    return;
                }
                log("✗ " + tag + " 大小不符（" + out.length() + " != " + size + "），换源重试");
                out.delete();
            } catch (Exception e) {
                log("✗ " + tag + " 该源失败[" + e.getClass().getSimpleName() + "]: "
                        + e.getMessage() + "，换源重试");
            }
        }
        throw new Exception(tag + " 所有下载源均失败。网络自检: " + probeNet());
    }

    /** 全部源失败时，探测基础连通性，帮助区分「设备断网/被限制」与「镜像问题」 */
    String probeNet() {
        StringBuilder sb = new StringBuilder();
        String[][] tests = {
                {"百度", "https://www.baidu.com"},
                {"阿里云", "https://mirrors.aliyun.com"},
                {"GitHub", "https://github.com"},
        };
        for (int i = 0; i < tests.length; i++) {
            sb.append(tests[i][0]).append("=").append(probe(tests[i][1]));
            if (i < tests.length - 1) {
                sb.append(" ");
            }
        }
        sb.append("（若百度也不通=设备网络受限，请检查平板网络/家长控制权限）");
        return sb.toString();
    }

    String probe(final String url) {
        // 独立线程 + join 硬超时：DNS 被拦时也能 8 秒内返回
        final String[] result = new String[] { null };
        Thread t = new Thread() {
            @Override public void run() {
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(5000);
                    c.setReadTimeout(5000);
                    c.setRequestMethod("HEAD");
                    result[0] = String.valueOf(c.getResponseCode());
                } catch (Throwable tt) {
                    result[0] = tt.getClass().getSimpleName();
                } finally {
                    if (c != null) {
                        c.disconnect();
                    }
                }
            }
        };
        t.setDaemon(true);
        t.start();
        try {
            t.join(8000);
        } catch (InterruptedException ignore) {
        }
        return result[0] == null ? "超时" : result[0];
    }

    void downloadOne(String url, long total, File out) throws Exception {
        long have = out.isFile() ? out.length() : 0;
        if (have > total) {
            out.delete();
            have = 0;
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(20000);
        if (have > 0) {
            conn.setRequestProperty("Range", "bytes=" + have + "-");
        }
        conn.setInstanceFollowRedirects(true);
        int code = conn.getResponseCode();
        if (!(code == 200 || code == 206)) {
            conn.disconnect();
            throw new Exception("HTTP " + code);
        }
        InputStream in = new BufferedInputStream(conn.getInputStream(), 65536);
        FileOutputStream fos = new FileOutputStream(out, have > 0 && code == 206);
        byte[] buf = new byte[65536];
        long got = have;
        long lastLog = 0;
        try {
            int n;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
                got += n;
                if (got - lastLog > Math.max(total / 50, 1048576)) {
                    lastLog = got;
                    progressLog(tagOf(url), got, total);
                }
            }
        } finally {
            fos.close();
            in.close();
            conn.disconnect();
        }
    }

    String tagOf(String url) {
        String s = url.replaceFirst("https?://", "");
        return s.substring(0, Math.min(28, s.length()));
    }

    void progressLog(final String tag, final long done, final long total) {
        logThrottle(tag + " " + (done * 100 / total) + "% (" + (done >> 20) + "MB/" + (total >> 20) + "MB)");
    }

    /* ---------------- 工具 ---------------- */

    void unzip(File zip, File destDir) throws Exception {
        ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip), 65536));
        try {
            byte[] buf = new byte[65536];
            ZipEntry e;
            int count = 0;
            while ((e = zis.getNextEntry()) != null) {
                File out = new File(destDir, e.getName());
                if (!out.getCanonicalPath().startsWith(destDir.getCanonicalPath())) {
                    continue;
                }
                if (e.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                File pf = out.getParentFile();
                if (pf != null) {
                    pf.mkdirs();
                }
                FileOutputStream fos = new FileOutputStream(out);
                int n;
                while ((n = zis.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                }
                fos.close();
                count++;
                if (count % 500 == 0) {
                    logThrottle("解压服务器… " + count + " 个文件");
                }
            }
        } finally {
            zis.close();
        }
    }

    int runInContainer(String... guestCmd) throws Exception {
        new File(baseDir, "tmp").mkdirs();
        List<String> cmd = new ArrayList<String>();
        cmd.add(new File(binDir, "proot").getAbsolutePath());
        cmd.add("--kill-on-exit");
        cmd.add("-0");
        cmd.add("-R");
        cmd.add(rootfsDir.getAbsolutePath());
        cmd.add("-b");
        cmd.add("/dev");
        cmd.add("-b");
        cmd.add("/proc");
        cmd.add("-b");
        cmd.add("/sys");
        for (int i = 0; i < guestCmd.length; i++) {
            cmd.add(guestCmd[i]);
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        applyEnv(pb);
        Process p = pb.start();
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
        String line;
        int lines = 0;
        while ((line = r.readLine()) != null) {
            if (lines++ < 15) {
                log("  | " + line);
            }
        }
        return p.waitFor();
    }

    void applyEnv(ProcessBuilder pb) {
        Map<String, String> env = pb.environment();
        env.put("PROOT_LOADER", new File(binDir, "loader").getAbsolutePath());
        env.put("PROOT_TMP_DIR", new File(baseDir, "tmp").getAbsolutePath());
        env.put("LD_LIBRARY_PATH", binDir.getAbsolutePath());
        env.put("HOME", "/root");
        env.put("TMPDIR", "/tmp");
        env.put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        // ubuntu-base 无 libicu：开启 .NET 不变文化模式（对游戏服务器无影响）
        env.put("DOTNET_SYSTEM_GLOBALIZATION_INVARIANT", "1");
    }

    void chmodExec(File f) {
        try {
            android.system.Os.chmod(f.getAbsolutePath(), 0755);
        } catch (Throwable ignore) {
        }
    }

    void deleteRecur(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        File[] kids = f.listFiles();
        if (kids != null) {
            for (int i = 0; i < kids.length; i++) {
                deleteRecur(kids[i]);
            }
        }
        f.delete();
    }

    void copyTree(File src, File dst) throws Exception {
        if (src.isDirectory()) {
            dst.mkdirs();
            File[] kids = src.listFiles();
            if (kids != null) {
                for (int i = 0; i < kids.length; i++) {
                    copyTree(kids[i], new File(dst, kids[i].getName()));
                }
            }
        } else {
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
    }

    /* ---------------- ② 启动 / ③ 停止 ---------------- */

    void startServer() {
        if (!isInitialized()) {
            log("✗ 容器未初始化，请先点 ①");
            return;
        }
        if (serverRunning()) {
            log("服务器已在运行中");
            return;
        }
        Intent i = new Intent(this, ValleyService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(i);
        } else {
            startService(i);
        }
        ui.postDelayed(new Runnable() {
            @Override public void run() { refreshStatus(); }
        }, 1500);
    }

    /* ---------------- 反馈问题 ---------------- */

    void feedbackDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(text("描述你遇到的问题，提交后会作为 Issue 发到\nGitHub 仓库 deekoz2/StarDewOpener（需浏览器已登录 GitHub）",
                12, 0xFF94A3B8, Typeface.NORMAL));
        box.addView(gap(8));
        final EditText input = new EditText(this);
        input.setHint("例如：初始化卡住 / 服务器启动报错…");
        input.setMinLines(4);
        input.setGravity(Gravity.TOP);
        input.setTextSize(13);
        box.addView(input);
        box.addView(gap(4));
        final CheckBox attach = new CheckBox(this);
        attach.setText("附带环境信息与运行日志");
        attach.setChecked(true);
        attach.setTextSize(13);
        box.addView(attach);

        new AlertDialog.Builder(this)
                .setTitle("反馈问题")
                .setView(box)
                .setPositiveButton("提交到 GitHub", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        submitIssue(input.getText().toString(), attach.isChecked());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    void submitIssue(String desc, boolean attach) {
        if (desc == null || desc.trim().length() == 0) {
            log("✗ 请先填写问题描述再提交");
            return;
        }
        String d = desc.trim();
        if (d.length() > 3000) {
            d = d.substring(0, 3000) + "…（已截断）";
        }
        String title = "[APP反馈] " + d.split("\n")[0];
        if (title.length() > 80) {
            title = title.substring(0, 80);
        }
        StringBuilder b = new StringBuilder();
        b.append(d).append("\n\n---\n").append(envInfo());
        if (attach) {
            b.append("\n\n<details><summary>运行日志</summary>\n\n```\n")
             .append(recentLog(40)).append("\n```\n\n</details>");
        }
        String body = b.toString();
        if (body.length() > 6000) {
            body = body.substring(0, 6000);
        }
        String url = ISSUES_URL + "?title=" + Uri.encode(title) + "&body=" + Uri.encode(body);
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            log("→ 已打开浏览器填写 Issue，确认后点「Submit new issue」即提交；");
            log("  若浏览器未登录 GitHub，请先登录再回来重新提交一次。");
        } catch (Throwable t) {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("issue", "标题: " + title + "\n\n" + body));
                log("✗ 无法打开浏览器，Issue 内容已复制到剪贴板，");
                log("  请手动到 " + ISSUES_URL + " 粘贴提交");
            } catch (Throwable t2) {
                log("✗ 无法打开浏览器且复制失败：" + t.getMessage());
            }
        }
    }

    String versionOf(String pkg) {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(pkg, 0);
            return pi.versionName == null ? "?" : pi.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    String envInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("- APP: 星露谷开服器 v").append(versionOf(getPackageName()));
        sb.append("\n- Android: ").append(Build.VERSION.RELEASE)
          .append(" (API ").append(Build.VERSION.SDK_INT).append(")");
        sb.append("\n- 设备: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL);
        sb.append("\n- 容器: ").append(isInitialized() ? "已初始化" : "未初始化");
        sb.append("\n- 服务器: ").append(serverRunning() ? "运行中" : "未运行");
        sb.append("\n- rootfs: Ubuntu 22.04.5 base arm64（proot 内置容器）");
        return sb.toString();
    }

    String recentLog(int n) {
        int from = history.size() > n ? history.size() - n : 0;
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < history.size(); i++) {
            sb.append(history.get(i)).append("\n");
        }
        return sb.toString();
    }

    void showHelp() {
        log("── 使用说明 ──────────────────────────");
        log("v2.0 起完全独立，无需 Termux / tmoe / 安装脚本：");
        log("1. 点 ① 初始化：下载 Ubuntu rootfs（26MB）+ ValleyServer");
        log("   （448MB，镜像加速 + 断点续传），全部解压到 APP 私有目录；");
        log("2. 点 ② 启动：内置 proot 拉起无头服务器，通知栏保持运行；");
        log("3. 好友联机：同一 Wi-Fi 填 你的IP:24642，外网需内网穿透；");
        log("4. 首次启动生成地图较慢，属正常现象；config.json 可改端口；");
        log("5. 停止：点 ③（直接结束进程）。");
        log("内置 proot 来自 Termux 官方仓库（GPL-3.0），源码链接见");
        log("APP assets 内 LICENSE-binaries.txt。");
        log("──────────────────────────────────────");
    }

    /* ---------------- 日志 ---------------- */

    void append(final String s) {
        ui.post(new Runnable() {
            @Override public void run() {
                history.add(s);
                if (history.size() > 300) {
                    history.remove(0);
                }
                logView.append(s + "\n");
                logScroll.post(new Runnable() {
                    @Override public void run() {
                        logScroll.fullScroll(View.FOCUS_DOWN);
                    }
                });
            }
        });
    }

    long lastThrottle = 0;

    void logThrottle(final String s) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastThrottle < 700) {
            return;
        }
        lastThrottle = now;
        append(s);
    }

    void log(final String s) {
        append(s);
    }
}
