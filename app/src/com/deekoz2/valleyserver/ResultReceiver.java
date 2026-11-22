package com.deekoz2.valleyserver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/**
 * 接收 Termux / ZeroTermux RUN_COMMAND 的执行结果回执。
 *
 * 官方格式（termux-app v0.118.0 ResultSender / TermuxConstants）：
 * 回执广播的 intent 只带一个 Bundle extra，键名 "result"，Bundle 内字段：
 *   err      : int    Errno 码，-1 = 成功（ERRNO_SUCCESS），失败为 1001 等
 *   exitCode : int    命令退出码（可能缺省）
 *   stdout   : String 命令标准输出
 *   stderr   : String 命令标准错误
 *   errmsg   : String 失败信息（仅失败时存在，如 allow-external-apps 未放行）
 */
public class ResultReceiver extends BroadcastReceiver {

    public interface Callback {
        void onResult(int idx, int err, int exitCode, String stdout, String stderr, String errmsg);
    }

    public static Callback cb;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (cb == null) {
            return;
        }
        int idx = intent.getIntExtra("idx", -1);
        Bundle b = intent.getBundleExtra("result");
        if (b == null) {
            // 没带结果包：可能是 fork 版不回传结果，或其它来源误触发了广播
            cb.onResult(idx, -999, Integer.MIN_VALUE, null, null, null);
            return;
        }
        cb.onResult(idx,
                b.getInt("err", -999),
                b.getInt("exitCode", Integer.MIN_VALUE),
                b.getString("stdout"),
                b.getString("stderr"),
                b.getString("errmsg"));
    }
}
