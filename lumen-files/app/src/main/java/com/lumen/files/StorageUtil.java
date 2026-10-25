package com.lumen.files;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.provider.Settings;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 存储卷、权限与空间统计。 */
public final class StorageUtil {

    private StorageUtil() {
    }

    public static boolean hasAccess(Context context) {
        if (Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager();
        }
        if (Build.VERSION.SDK_INT >= 23) {
            return context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    /** 华为 / 荣耀机型（含鸿蒙 HarmonyOS）使用独立的权限设置入口。 */
    public static boolean isHuaweiLike() {
        String brand = Build.BRAND == null ? "" : Build.BRAND.toLowerCase(Locale.ROOT);
        String manufacturer = Build.MANUFACTURER == null
                ? "" : Build.MANUFACTURER.toLowerCase(Locale.ROOT);
        return brand.contains("huawei") || brand.contains("honor")
                || manufacturer.contains("huawei") || manufacturer.contains("honor");
    }

    public static String permissionHint() {
        if (isHuaweiLike()) {
            return "鸿蒙/荣耀：设置 → 应用 → 权限 → 所有文件访问";
        }
        return "开启后才能浏览和管理本机全部文件";
    }

    public static void requestAccess(Activity activity) {
        if (Build.VERSION.SDK_INT >= 30) {
            Intent appAllFiles = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            appAllFiles.setData(Uri.parse("package:" + activity.getPackageName()));
            if (start(activity, appAllFiles)) {
                return;
            }
            if (start(activity, new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))) {
                return;
            }
            if (openAppDetails(activity)) {
                return;
            }
            Bg.toast(activity, "无法打开权限设置，请手动到系统设置中授权");
        } else if (Build.VERSION.SDK_INT >= 23) {
            activity.requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, 77);
        }
    }

    /** 打开应用详情页，作为鸿蒙等定制系统的最后兜底入口。 */
    public static boolean openAppDetails(Activity activity) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + activity.getPackageName()));
        return start(activity, intent);
    }

    private static boolean start(Activity activity, Intent intent) {
        try {
            activity.startActivity(intent);
            return true;
        } catch (Exception error) {
            return false;
        }
    }

    public static List<File> roots() {
        ArrayList<File> out = new ArrayList<>();
        File primary = Environment.getExternalStorageDirectory();
        if (primary != null && primary.isDirectory()) {
            out.add(primary);
        }
        File storage = new File("/storage");
        File[] children = storage.listFiles();
        if (children != null) {
            for (File child : children) {
                String name = child.getName();
                if ("emulated".equals(name) || "self".equals(name)) {
                    continue;
                }
                if (child.isDirectory() && child.canRead() && !same(child, primary)) {
                    out.add(child);
                }
            }
        }
        return out;
    }

    public static String label(File file) {
        File primary = Environment.getExternalStorageDirectory();
        if (same(file, primary)) {
            return "内部存储";
        }
        try {
            if (Environment.isExternalStorageRemovable(file)) {
                return "存储卡 " + file.getName();
            }
        } catch (Exception ignored) {
        }
        return "存储设备 " + file.getName();
    }

    /** 找到路径所属的存储卷根目录，用于限制“上一级”导航边界。 */
    public static String volumeRoot(String path) {
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return "/";
        }
        String value = path;
        while (value.length() > 1 && value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        String internal = Environment.getExternalStorageDirectory().getAbsolutePath();
        if (value.equals(internal) || value.startsWith(internal + "/")) {
            return internal;
        }
        if (value.startsWith("/storage/")) {
            String rest = value.substring("/storage/".length());
            int slash = rest.indexOf('/');
            String volume = slash < 0 ? rest : rest.substring(0, slash);
            if (!volume.isEmpty()) {
                return "/storage/" + volume;
            }
        }
        return "/";
    }

    /** 返回 {总空间, 可用空间}，失败时返回 {0, 0}。 */
    public static long[] usage(File directory) {
        try {
            StatFs stat = new StatFs(directory.getAbsolutePath());
            long total = stat.getBlockCountLong() * stat.getBlockSizeLong();
            long free = stat.getAvailableBlocksLong() * stat.getBlockSizeLong();
            return new long[]{total, free};
        } catch (Exception error) {
            return new long[]{0, 0};
        }
    }

    public static String[][] quickShortcuts() {
        File base = Environment.getExternalStorageDirectory();
        return new String[][]{
                {"下载", new File(base, "Download").getAbsolutePath()},
                {"图片", new File(base, "Pictures").getAbsolutePath()},
                {"相机", new File(base, "DCIM").getAbsolutePath()},
                {"音乐", new File(base, "Music").getAbsolutePath()},
                {"视频", new File(base, "Movies").getAbsolutePath()},
                {"文档", new File(base, "Documents").getAbsolutePath()}
        };
    }

    private static boolean same(File a, File b) {
        if (a == null || b == null) {
            return false;
        }
        try {
            return a.getCanonicalPath().equals(b.getCanonicalPath());
        } catch (Exception error) {
            return a.getAbsolutePath().equals(b.getAbsolutePath());
        }
    }
}
