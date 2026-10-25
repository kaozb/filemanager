package com.lumen.files;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 轻量后台执行器：所有 IO 不占用主线程。 */
public final class Bg {

    public interface Work {
        void run() throws Exception;
    }

    private static final ExecutorService POOL = Executors.newFixedThreadPool(3, runnable -> {
        Thread thread = new Thread(runnable, "lumen-io");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Bg() {
    }

    public static ExecutorService pool() {
        return POOL;
    }

    public static void post(Runnable runnable) {
        MAIN.post(runnable);
    }

    public static void run(final Activity activity, final String message,
                           final Work work, final Runnable success) {
        final ProgressDialog dialog;
        if (message != null && !activity.isFinishing()) {
            dialog = ProgressDialog.show(activity, null, message, true, false);
        } else {
            dialog = null;
        }
        POOL.execute(() -> {
            try {
                work.run();
            } catch (final Throwable error) {
                MAIN.post(() -> {
                    dismiss(dialog);
                    if (!activity.isFinishing()) {
                        Toast.makeText(activity, friendly(error), Toast.LENGTH_LONG).show();
                    }
                });
                return;
            }
            MAIN.post(() -> {
                dismiss(dialog);
                if (!activity.isFinishing()) {
                    success.run();
                }
            });
        });
    }

    public static void toast(final Context context, final String message) {
        MAIN.post(() -> Toast.makeText(context.getApplicationContext(),
                message, Toast.LENGTH_SHORT).show());
    }

    private static void dismiss(ProgressDialog dialog) {
        if (dialog != null && dialog.isShowing()) {
            try {
                dialog.dismiss();
            } catch (Exception ignored) {
            }
        }
    }

    private static String friendly(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = error.getClass().getSimpleName();
        }
        return message;
    }
}
