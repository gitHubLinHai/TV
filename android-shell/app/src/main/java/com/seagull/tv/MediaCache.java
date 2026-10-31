package com.seagull.tv;

import android.content.Context;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import java.io.File;
import java.util.ArrayList;

/** 播放器磁盘缓存：进程内单例，容量上限 512MiB；换片或退出播放器时清空，只保留当前播放的视频。 */
public final class MediaCache {
    private static SimpleCache cache;
    public static synchronized SimpleCache get(Context context) {
        if (cache == null) cache = new SimpleCache(new File(context.getApplicationContext().getCacheDir(), "media"),
            new LeastRecentlyUsedCacheEvictor(512L * 1024 * 1024), new StandaloneDatabaseProvider(context.getApplicationContext()));
        return cache;
    }
    public static synchronized void clear() {
        if (cache == null) return;
        for (String key : new ArrayList<>(cache.getKeys())) cache.removeResource(key);
    }
    private MediaCache() {}
}
