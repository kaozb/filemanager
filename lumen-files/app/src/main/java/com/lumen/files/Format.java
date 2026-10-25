package com.lumen.files;

import android.webkit.MimeTypeMap;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 文件大小、时间、类型与图标的统一格式化。 */
public final class Format {

    private static final String[] UNITS = {"B", "KB", "MB", "GB", "TB"};
    private static final SimpleDateFormat DATE =
            new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault());

    private Format() {
    }

    public static String size(long bytes) {
        if (bytes < 0) {
            return "--";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < UNITS.length - 1) {
            value /= 1024;
            unit++;
        }
        if (value >= 100) {
            return String.format(Locale.US, "%.0f %s", value, UNITS[unit]);
        }
        return String.format(Locale.US, "%.1f %s", value, UNITS[unit]);
    }

    public static String date(long time) {
        if (time <= 0) {
            return "--";
        }
        synchronized (DATE) {
            return DATE.format(new Date(time));
        }
    }

    public static String mime(Entry entry) {
        if (entry.dir) {
            return "resource/folder";
        }
        String ext = entry.ext();
        if ("apk".equals(ext) || "apks".equals(ext) || "xapk".equals(ext)) {
            return "application/vnd.android.package-archive";
        }
        if ("zip".equals(ext)) {
            return "application/zip";
        }
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return mime == null ? "application/octet-stream" : mime;
    }

    public static String type(Entry entry) {
        if (entry.dir) {
            return "文件夹";
        }
        String ext = entry.ext();
        if (isImage(ext)) return "图片";
        if (isVideo(ext)) return "视频";
        if (isAudio(ext)) return "音频";
        if (isApk(ext)) return "安装包";
        if (isArchive(ext)) return "压缩包";
        if (isCode(ext)) return "代码";
        if (isText(ext)) return "文本";
        if (isDocument(ext)) return "文档";
        return "文件";
    }

    public static int icon(Entry entry) {
        if (entry.dir) {
            return R.drawable.ic_folder;
        }
        String ext = entry.ext();
        if (isImage(ext)) return R.drawable.ic_image;
        if (isVideo(ext)) return R.drawable.ic_video;
        if (isAudio(ext)) return R.drawable.ic_audio;
        if (isApk(ext)) return R.drawable.ic_apk;
        if (isArchive(ext)) return R.drawable.ic_archive;
        if (isDocument(ext)) return R.drawable.ic_doc;
        if (isCode(ext)) return R.drawable.ic_code;
        if (isText(ext)) return R.drawable.ic_text;
        return R.drawable.ic_file;
    }

    public static boolean previewable(Entry entry) {
        if (entry.dir) {
            return false;
        }
        String ext = entry.ext();
        boolean image = isImage(ext);
        boolean text = isText(ext) || isCode(ext);
        if (!image && !text) {
            return false;
        }
        return entry.size <= 0 || entry.size < 32L * 1024 * 1024;
    }

    public static boolean isImage(Entry entry) {
        return !entry.dir && isImage(entry.ext());
    }

    public static boolean isText(Entry entry) {
        return !entry.dir && (isText(entry.ext()) || isCode(entry.ext()));
    }

    public static boolean isImage(String ext) {
        switch (ext) {
            case "jpg": case "jpeg": case "png": case "gif": case "webp":
            case "bmp": case "heic": case "heif": case "avif":
                return true;
            default:
                return false;
        }
    }

    public static boolean isVideo(String ext) {
        switch (ext) {
            case "mp4": case "mkv": case "avi": case "mov": case "3gp":
            case "webm": case "ts": case "flv": case "wmv": case "m4v":
                return true;
            default:
                return false;
        }
    }

    public static boolean isAudio(String ext) {
        switch (ext) {
            case "mp3": case "flac": case "wav": case "ogg": case "m4a":
            case "aac": case "wma": case "opus": case "amr":
                return true;
            default:
                return false;
        }
    }

    public static boolean isApk(String ext) {
        return "apk".equals(ext) || "apks".equals(ext) || "xapk".equals(ext);
    }

    public static boolean isArchive(String ext) {
        switch (ext) {
            case "zip": case "rar": case "7z": case "gz": case "tar":
            case "bz2": case "xz": case "tgz":
                return true;
            default:
                return false;
        }
    }

    public static boolean isDocument(String ext) {
        switch (ext) {
            case "pdf": case "doc": case "docx": case "xls": case "xlsx":
            case "ppt": case "pptx": case "rtf": case "odt": case "ods":
            case "odp": case "epub": case "csv":
                return true;
            default:
                return false;
        }
    }

    public static boolean isText(String ext) {
        switch (ext) {
            case "txt": case "md": case "markdown": case "log": case "ini":
            case "conf": case "properties": case "csv": case "srt": case "vtt":
                return true;
            default:
                return false;
        }
    }

    public static boolean isCode(String ext) {
        switch (ext) {
            case "json": case "xml": case "yml": case "yaml": case "html":
            case "htm": case "css": case "js": case "ts": case "java":
            case "kt": case "py": case "c": case "cpp": case "h": case "hpp":
            case "cs": case "go": case "rs": case "php": case "rb": case "swift":
            case "sh": case "bat": case "sql": case "gradle": case "toml":
                return true;
            default:
                return false;
        }
    }
}
