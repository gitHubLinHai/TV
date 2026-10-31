package com.seagull.tv;

import java.io.File;
import java.io.IOException;

/** 资源目录切换、试运行确认和故障恢复；浏览记录保存在独立的 WebView 数据目录。 */
public final class UpdateStorage {
    public static synchronized void recover(File root) throws IOException {
        File current = new File(root, "www");
        File old = new File(root, "www_old");
        if (!current.exists() && old.exists() && !old.renameTo(current))
            throw new IOException("恢复旧版资源失败，请重启应用");
        if (!old.exists()) remove(new File(root, "web_loading"));
        if (current.exists() && old.exists() && new File(root, "web_loading").exists()) {
            rollback(root);
        }
    }

    public static synchronized void install(File incoming, File current) throws IOException {
        File old = new File(current.getParentFile(), "www_old");
        remove(old);
        boolean hadCurrent = current.exists();
        if (hadCurrent && !current.renameTo(old)) throw new IOException("资源被占用，请重启应用后再试");
        if (!incoming.renameTo(current)) {
            if (hadCurrent && !old.renameTo(current))
                throw new IOException("替换资源失败，旧版已保留，下次启动自动恢复");
            throw new IOException("替换资源失败，旧版已恢复");
        }
        // 保留 old，直到新版首页确认启动完成。
    }

    public static synchronized void startLoad(File root) throws IOException {
        if (new File(root, "www_old").exists()) {
            File marker = new File(root, "web_loading");
            if (!marker.exists() && !marker.createNewFile()) throw new IOException("无法记录新版试运行状态");
        }
    }

    public static synchronized void confirm(File root) throws IOException {
        remove(new File(root, "www_old"));
        remove(new File(root, "web_loading"));
    }

    public static synchronized boolean rollback(File root) throws IOException {
        File old = new File(root, "www_old");
        if (!old.exists()) return false;
        File current = new File(root, "www");
        remove(current);
        if (!old.renameTo(current)) throw new IOException("旧版仍保留，重启后重试恢复");
        remove(new File(root, "web_loading"));
        return true;
    }

    private static void remove(File file) throws IOException {
        if (!file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) remove(child);
        if (!file.delete()) throw new IOException("无法清理旧版资源，请重启应用后再试");
    }
}

