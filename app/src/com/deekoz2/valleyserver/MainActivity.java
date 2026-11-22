package com.deekoz2.valleyserver;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 星露谷开服器 —— ValleyServer-arm64 的 Android 前端。
 *
 * 功能：
 *  1. 按 RUN_COMMAND action 动态枚举 Termux 系终端（官方 Termux / ZeroTermux 等 fork）；
 *  2. 通过 RUN_COMMAND + PendingIntent 回执验证 APP ↔ 终端连通性；
 *  3. 一键安装：在前台新会话执行服务器仓库 install.sh（可见、可交互，tmoe 菜单可操作）；
 *  4. 放行助手：复制 allow-external-apps 放行命令 / 直接打开目标终端。
 */
public class MainActivity extends Activity implements ResultReceiver.Callback {

    static final String ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND";
    static final String EXTRA_PATH = "com.termux.RUN_COMMAND_PATH";
    static final String EXTRA_ARGS = "com.termux.RUN_COMMAND_ARGUMENTS";
    static final String EXTRA_BG = "com.termux.RUN_COMMAND_BACKGROUND";
    static final String EXTRA_PI = "com.termux.RUN_COMMAND_PENDING_INTENT";
    static final String EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION";
    /** PendingIntent.FLAG_MUTABLE 的数值（编译用 API 30 jar，故用字面量） */
    static final int FLAG_MUTABLE = 0x02000000;

    static final long TEST_TIMEOUT_MS = 12000;

    static final String INSTALL_URL =
            "https://raw.githubusercontent.com/deekoz2/ValleyServer-arm64/main/install.sh";
    static final String INSTALL_CMD =
            "apt update -y; "
                    + "DEBIAN_FRONTEND=noninteractive apt upgrade -y -o Dpkg::Options::=--force-confold; "
                    + "pkg install -y curl openssl; "
                    + "U1=https://raw.githubusercontent.com/deekoz2/ValleyServer-arm64/main/install.sh; "
                    + "U2=https://cdn.jsdelivr.net/gh/deekoz2/ValleyServer-arm64@main/install.sh; "
                    + "U3=https://ghfast.top/https://raw.githubusercontent.com/deekoz2/ValleyServer-arm64/main/install.sh; "
                    + "if curl -fsSL --connect-timeout 8 $U1 -o ~/valley-install.sh "
                    + "|| curl -fsSL --connect-timeout 8 $U2 -o ~/valley-install.sh "
                    + "|| curl -fsSL --connect-timeout 15 $U3 -o ~/valley-install.sh; "
                    + "then bash ~/valley-install.sh; else echo 'install.sh 三个下载源都失败，请检查网络'; fi";

    static final String ALLOW_CMD =
            "mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties";

    Handler ui;
    TextView logView;
    ScrollView logScroll;
    LinearLayout cardsBox;
    final List<TextView> cardStatus = new ArrayList<TextView>();
    final List<LinearLayout> cardViews = new ArrayList<LinearLayout>();
    final List<String> pkgs = new ArrayList<String>();
    final List<String> names = new ArrayList<String>();
    final List<Boolean> waiting = new ArrayList<Boolean>();
    int selected = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ui = new Handler(Looper.getMainLooper());
        ResultReceiver.cb = this;
        discoverTerminals();
        setContentView(buildUi());
        refreshCards();
    }

    @Override
    protected void onDestroy() {
        ResultReceiver.cb = null;
        super.onDestroy();
    }

    /* ---------------- 终端枚举 ---------------- */

    /** 按 RUN_COMMAND action 枚举所有支持的终端（Termux 及 fork），兜底再加常见包名 */
    void discoverTerminals() {
        pkgs.clear();
        names.clear();
        PackageManager pm = getPackageManager();
        List<ResolveInfo> svcs = pm.queryIntentServices(new Intent(ACTION_RUN_COMMAND), 0);
        for (int i = 0; i < svcs.size(); i++) {
            String p = svcs.get(i).serviceInfo.packageName;
            if (pkgs.indexOf(p) < 0 && installed(p)) {
                pkgs.add(p);
                names.add(labelOf(p));
            }
        }
        String[] known = { "com.termux", "com.zerotermux" };
        for (int i = 0; i < known.length; i++) {
            if (pkgs.indexOf(known[i]) < 0 && installed(known[i])) {
                pkgs.add(known[i]);
                names.add(labelOf(known[i]));
            }
        }
        waiting.clear();
        for (int i = 0; i < pkgs.size(); i++) {
            waiting.add(Boolean.FALSE);
        }
        if (selected >= pkgs.size()) {
            selected = 0;
        }
    }

    String labelOf(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            CharSequence cs = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
            return cs == null ? pkg : cs.toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    boolean installed(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
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

    /* ---------------- 界面 ---------------- */

    View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0B1220);
        root.setPadding(dp(20), dp(24), dp(20), dp(16));

        root.addView(text("星露谷开服器", 22, 0xFFF8FAFC, Typeface.BOLD));
        root.addView(text("ValleyServer-arm64 · 无头专用服务器（无需游戏本体）", 12, 0xFF94A3B8, Typeface.NORMAL));
        root.addView(gap(14));

        cardsBox = new LinearLayout(this);
        cardsBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(cardsBox);

        root.addView(gap(4));
        root.addView(text("点终端卡片可切换「一键安装」目标", 11, 0xFF64748B, Typeface.NORMAL));
        root.addView(gap(10));

        root.addView(button("1. 检测能否连接终端", 0xFF166534, new View.OnClickListener() {
            @Override public void onClick(View v) { testConnection(); }
        }));
        root.addView(gap(10));
        root.addView(button("2. 一键安装开服环境（前台会话，可见）", 0xFF9A3412, new View.OnClickListener() {
            @Override public void onClick(View v) { oneClickInstall(); }
        }));
        root.addView(gap(10));
        root.addView(button("复制放行命令", 0xFF4C1D95, new View.OnClickListener() {
            @Override public void onClick(View v) { copyAllowCommand(); }
        }));
        root.addView(gap(10));
        root.addView(button("打开终端", 0xFF1E3A5F, new View.OnClickListener() {
            @Override public void onClick(View v) { openTerminal(); }
        }));
        root.addView(gap(10));
        root.addView(button("使用说明", 0xFF334155, new View.OnClickListener() {
            @Override public void onClick(View v) { showHelp(); }
        }));

        root.addView(gap(14));
        root.addView(text("运行日志", 13, 0xFFCBD5E1, Typeface.BOLD));
        root.addView(gap(6));
        logScroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(11);
        logView.setTextColor(0xFFA7F3D0);
        logView.setText("就绪。先检测连接，再一键安装。\n");
        logScroll.addView(logView);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        logScroll.setLayoutParams(lp);
        root.addView(logScroll);
        return root;
    }

    LinearLayout makeCard(final int idx) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        c.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                selected = idx;
                styleCards();
                log("→ 一键安装目标已选为 " + names.get(idx) + "（" + pkgs.get(idx) + "）");
            }
        });
        c.addView(text(names.get(idx) + "  ·  " + pkgs.get(idx), 15, 0xFFE2E8F0, Typeface.BOLD));
        TextView st = text("检测中…", 12, 0xFF94A3B8, Typeface.NORMAL);
        cardStatus.add(st);
        c.addView(st);
        return c;
    }

    void styleCards() {
        for (int i = 0; i < cardViews.size(); i++) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFF121B2C);
            bg.setCornerRadius(dp(14));
            if (i == selected) {
                bg.setStroke(dp(2), 0xFFF59E0B);
            } else {
                bg.setStroke(dp(1), 0xFF1E293B);
            }
            cardViews.get(i).setBackground(bg);
        }
    }

    void refreshCards() {
        cardStatus.clear();
        cardViews.clear();
        cardsBox.removeAllViews();
        for (int i = 0; i < pkgs.size(); i++) {
            LinearLayout card = makeCard(i);
            cardViews.add(card);
            cardsBox.addView(card);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) {
                lp.topMargin = dp(10);
            }
            card.setLayoutParams(lp);
        }
        styleCards();
        for (int i = 0; i < pkgs.size(); i++) {
            cardStatus.get(i).setText("已安装 · v" + versionOf(pkgs.get(i))
                    + (i == selected ? "  ★ 安装目标" : ""));
        }
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

    /* ---------------- 连接测试 ---------------- */

    void testConnection() {
        if (pkgs.isEmpty()) {
            log("✗ 未检测到 Termux 系终端，请先安装 Termux 或 ZeroTermux。");
            return;
        }
        for (int i = 0; i < pkgs.size(); i++) {
            waiting.set(i, Boolean.TRUE);
            boolean ok = sendCommand(pkgs.get(i),
                    new String[] { "-c", "echo valley-ok" }, getPi(i), true);
            if (ok) {
                log("→ 已向 " + names.get(i) + " 发送测试命令（后台执行，不弹窗口），等待回执…");
                armTimeout(i);
            } else {
                waiting.set(i, Boolean.FALSE);
            }
        }
    }

    void armTimeout(final int i) {
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (!waiting.get(i).booleanValue()) {
                    return;
                }
                waiting.set(i, Boolean.FALSE);
                log("✗ " + names.get(i) + " " + (TEST_TIMEOUT_MS / 1000) + " 秒无回执。排查：");
                log("   1) 终端侧需放行外部应用：点「复制放行命令」去终端执行后重开终端");
                log("   2) 保持终端可后台运行（关闭电池优化 / 加白名单）");
            }
        }, TEST_TIMEOUT_MS);
    }

    @Override
    public void onResult(final int idx, final int err, final int exitCode,
                         final String stdout, final String stderr, final String errmsg) {
        ui.post(new Runnable() {
            @Override public void run() {
                if (idx < 0 || idx >= pkgs.size() || !waiting.get(idx).booleanValue()) {
                    return;
                }
                waiting.set(idx, Boolean.FALSE);
                if (err == -1) { // Errno.ERRNO_SUCCESS
                    log("✓ 连接成功：" + names.get(idx) + " 可正常执行命令，可以一键安装了！");
                    if (exitCode != Integer.MIN_VALUE && exitCode != 0) {
                        log("   （命令退出码 " + exitCode + " 非 0，一般不影响）");
                    }
                } else if (err == -999) {
                    log("⚠ " + names.get(idx) + " 有回执但没带结果数据。");
                    log("   常见原因：终端未放行「外部应用」（命令被拒绝执行，");
                    log("   终端通知栏应该有报错通知）。请点「复制放行命令」，");
                    log("   在终端里粘贴执行，完全退出并重开终端后重新检测。");
                } else {
                    log("⚠ " + names.get(idx) + " 执行失败：err=" + err);
                    if (errmsg != null && errmsg.trim().length() > 0) {
                        log("   " + errmsg.trim().replace("\n", " ⏎ "));
                    }
                    if (errmsg != null && errmsg.contains("allow-external-apps")) {
                        log("   → 终端未放行外部应用。点「复制放行命令」，去终端");
                        log("     粘贴执行，然后完全退出并重开终端，再回来重新检测。");
                    }
                }
            }
        });
    }

    /* ---------------- 一键安装（前台新会话） ---------------- */

    void oneClickInstall() {
        if (pkgs.isEmpty()) {
            log("✗ 未检测到 Termux 系终端，无法一键安装。");
            return;
        }
        final int i = selected;
        // 前台 + 会话动作 0 = 新建会话并切到终端界面，脚本与 tmoe 菜单可见可交互
        boolean ok = sendCommandForeground(pkgs.get(i), new String[] { "-c", INSTALL_CMD });
        if (ok) {
            log("→ 已让 " + names.get(i) + " 在【前台新会话】运行安装脚本，");
            log("  现在切到 " + names.get(i) + " 就能看到进度和 tmoe 菜单。");
            log("  在菜单里选：新建容器 → Debian → arm64 → proot。");
            log("  装完在终端里输入 valley 启动服务器（端口 24642/udp）。");
        }
    }

    void showHelp() {
        log("── 使用说明 ──────────────────────────");
        log("1. 手机安装 Termux 或 ZeroTermux，打开一次并允许存储权限；");
        log("2. 点「检测能否连接」：APP 会通过系统 intent 调起终端执行");
        log("   测试命令并等待回执（后台执行，不弹窗口），验证链路；");
        log("3. 点「一键安装」：终端会在【前台新会话】里运行 install.sh");
        log("   （tmoe 创建容器：Debian → arm64 → proot，全程可见可操作）；");
        log("4. 启动：终端里输入 valley（前台）或 valley start（后台）；");
        log("5. 联机：同一 Wi-Fi 下好友填 你的IP:24642 ；外网联机需内网穿透");
        log("   （frp / Tailscale 等）。");
        log("注：fork 版 ZeroTermux 可能沿用 com.termux 包名，APP 会显示");
        log("其真实应用名；多终端时点卡片切换安装目标。");
        log("服务器本体: Lixeer/ValleyServer v3.0.3 (Apache-2.0)");
        log("──────────────────────────────────────");
    }

    /* ---------------- 放行辅助 ---------------- */

    void copyAllowCommand() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("allow-external-apps", ALLOW_CMD));
            log("✓ 放行命令已复制到剪贴板：");
            log("   " + ALLOW_CMD);
            log("   请点「打开终端」，长按粘贴并回车执行，然后完全退出");
            log("   （通知栏滑掉/最近任务清掉）重开终端，再回来重新检测。");
        } catch (Throwable t) {
            log("✗ 复制失败，请手动在终端里执行上面的命令。");
        }
    }

    void openTerminal() {
        if (pkgs.isEmpty()) {
            log("✗ 未检测到 Termux 系终端。");
            return;
        }
        Intent li = getPackageManager().getLaunchIntentForPackage(pkgs.get(selected));
        if (li != null) {
            li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(li);
            log("→ 已打开 " + names.get(selected) + "，粘贴放行命令执行后重开它。");
        }
    }

    /* ---------------- RUN_COMMAND 发送 ---------------- */

    PendingIntent getPi(int idx) {
        Intent i = new Intent("com.deekoz2.valleyserver.RESULT");
        i.setPackage(getPackageName());
        i.setComponent(new ComponentName(this, ResultReceiver.class));
        i.putExtra("idx", idx);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) {
            flags |= FLAG_MUTABLE;
        }
        return PendingIntent.getBroadcast(this, idx + 1, i, flags);
    }

    boolean sendCommand(String pkg, String[] args, PendingIntent pi, boolean bg) {
        boolean ok = tryStart(buildIntent(pkg, args, pi, bg, true));
        if (!ok) {
            log("… 尝试不指定组件再发一次（兼容 fork 版）");
            ok = tryStart(buildIntent(pkg, args, pi, bg, false));
        }
        return ok;
    }

    boolean sendCommandForeground(String pkg, String[] args) {
        boolean ok = tryStart(buildIntent(pkg, args, null, false, true));
        if (!ok) {
            ok = tryStart(buildIntent(pkg, args, null, false, false));
        }
        return ok;
    }

    Intent buildIntent(String pkg, String[] args, PendingIntent pi, boolean bg, boolean withComponent) {
        Intent i = new Intent(ACTION_RUN_COMMAND);
        i.setPackage(pkg);
        if (withComponent) {
            i.setComponent(new ComponentName(pkg, pkg + ".app.RunCommandService"));
        }
        i.putExtra(EXTRA_PATH, "/data/data/" + pkg + "/files/usr/bin/bash");
        i.putExtra(EXTRA_ARGS, args);
        i.putExtra(EXTRA_BG, bg);
        if (!bg) {
            // 0 = 新建会话并切换到终端界面（SWITCH_TO_NEW_SESSION_AND_OPEN_ACTIVITY）
            i.putExtra(EXTRA_SESSION_ACTION, "0");
        }
        if (pi != null) {
            i.putExtra(EXTRA_PI, pi);
        }
        return i;
    }

    boolean tryStart(Intent i) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }
            return true;
        } catch (Throwable t) {
            log("✗ 无法调起 " + i.getPackage() + "：" + t.getClass().getSimpleName());
            return false;
        }
    }

    /* ---------------- 日志 ---------------- */

    void log(final String s) {
        ui.post(new Runnable() {
            @Override public void run() {
                logView.append(s + "\n");
                logScroll.post(new Runnable() {
                    @Override public void run() {
                        logScroll.fullScroll(View.FOCUS_DOWN);
                    }
                });
            }
        });
    }
}
