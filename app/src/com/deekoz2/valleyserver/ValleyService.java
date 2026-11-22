package com.deekoz2.valleyserver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 服务器进程托管服务：
 *  - 以 proot 拉起 rootfs 内的 /opt/valleyserver/ValleyServer；
 *  - 前台通知保持存活；
 *  - 控制台输出回传给界面（缓存最近 300 行）。
 */
public class ValleyService extends Service {

    public interface Listener {
        void onServerLog(String line);
        void onServerExit(int code);
    }

    static final String CHANNEL = "valley_server";
    static Process proc;
    static volatile Listener ui;
    static final List<String> logHistory = new ArrayList<String>();
    static Handler h;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        h = new Handler(Looper.getMainLooper());
        startFg();
        if (proc == null) {
            startProc();
        } else {
            append("服务器已在运行中");
        }
        return START_NOT_STICKY;
    }

    void startFg() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "星露谷服务器",
                    NotificationManager.IMPORTANCE_LOW));
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("星露谷服务器运行中")
                .setContentText("UDP 24642 · 停止请回 APP 点「停止服务器」")
                .setOngoing(true);
        startForeground(2001, b.build());
    }

    void startProc() {
        try {
            File base = getDir("valley", MODE_PRIVATE);
            File bin = new File(base, "bin");
            File rootfs = new File(base, "rootfs");
            File tmp = new File(base, "tmp");
            tmp.mkdirs();

            List<String> cmd = new ArrayList<String>();
            cmd.add(new File(bin, "proot").getAbsolutePath());
            cmd.add("--kill-on-exit");
            cmd.add("-0");
            cmd.add("-R");
            cmd.add(rootfs.getAbsolutePath());
            cmd.add("-b");
            cmd.add("/dev");
            cmd.add("-b");
            cmd.add("/proc");
            cmd.add("-b");
            cmd.add("/sys");
            cmd.add("-w");
            cmd.add("/opt/valleyserver");
            cmd.add("/opt/valleyserver/ValleyServer");

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Map<String, String> env = pb.environment();
            env.put("PROOT_LOADER", new File(bin, "loader").getAbsolutePath());
            env.put("PROOT_TMP_DIR", tmp.getAbsolutePath());
            env.put("LD_LIBRARY_PATH", bin.getAbsolutePath());
            env.put("HOME", "/root");
            env.put("TMPDIR", "/tmp");
            env.put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
            // ubuntu-base 无 libicu：开启 .NET 不变文化模式（对游戏服务器无影响）
            env.put("DOTNET_SYSTEM_GLOBALIZATION_INVARIANT", "1");
            // 帮助游戏 dlopen 找到 /opt/valleyserver 下的原生库
            env.put("LD_LIBRARY_PATH", bin.getAbsolutePath() + ":/opt/valleyserver");

            proc = pb.start();
            append("✓ 服务器进程已启动（UDP 24642，控制台输出见下）");

            final BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            new Thread() {
                @Override
                public void run() {
                    try {
                        String line;
                        while ((line = r.readLine()) != null) {
                            append(line);
                        }
                    } catch (Exception ignore) {
                    }
                    int code = -999;
                    try {
                        code = proc.waitFor();
                    } catch (Exception ignore) {
                    }
                    final int c = code;
                    proc = null;
                    stopForeground(true);
                    stopSelf();
                    if (ui != null && h != null) {
                        h.post(new Runnable() {
                            @Override
                            public void run() {
                                if (ui != null) {
                                    ui.onServerExit(c);
                                }
                            }
                        });
                    }
                }
            }.start();
        } catch (Throwable t) {
            append("✗ 启动失败：" + t);
            proc = null;
            stopForeground(true);
            stopSelf();
        }
    }

    static void append(String line) {
        synchronized (logHistory) {
            logHistory.add(line);
            if (logHistory.size() > 300) {
                logHistory.remove(0);
            }
        }
        final String s = line;
        if (ui != null && h != null) {
            h.post(new Runnable() {
                @Override
                public void run() {
                    if (ui != null) {
                        ui.onServerLog(s);
                    }
                }
            });
        }
    }

    /** 停止服务器（SIGTERM） */
    public static void stopServer() {
        Process p = proc;
        if (p != null) {
            p.destroy();
        } else {
            append("没有在运行的服务器");
        }
    }
}
