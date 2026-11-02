package com.seagull.tv;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import androidx.media3.common.*;
import androidx.media3.datasource.*;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.exoplayer.*;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.ui.SubtitleView;
import androidx.media3.common.text.Cue;
import java.util.HashMap;
import java.util.List;

/** Media3：控制层悬浮在画面上层（不挤压视频）、双指缩放/拖动、字幕上下偏移；音乐播放页居中滚动歌词；无痕播放不读写进度。 */
public final class VideoActivity extends Activity {
    /** 音乐时长回调：网页据此识别试听片段并自动换源。 */
    public interface DurationListener { void onDuration(String key, long ms); }
    public static volatile DurationListener durationListener;
    /** 音乐播完回调：网页推进队列；当前播放页原地换曲，不经后台启动 Activity。 */
    public interface EndListener { void onEnded(String key); }
    public static volatile EndListener endListener;
    private static VideoActivity musicCurrent;
    private int swapGen;
    private static final Object END_TOKEN = new Object();
    private ExoPlayer video;
    private String key;
    private boolean finished, privateMode, seeking, endedOnce, controlsHidden, musicMode, boosting, seekGesture, resumed;
    private long scrollLastT;
    private float baseSpeed = 1f, seekDeltaMs;
    private long seekBaseMs;
    private TextView gestureHint;
    private TextView status, time, title;
    private Button play;
    private SeekBar seek;
    private FrameLayout frame;
    private TextureView surface;
    private SubtitleView subtitles;
    private LinearLayout topBar, bottomBar, subButtons, lyricButtons;
    private float aspect = 16f/9f;
    private int scaleMode;
    private float zoom = 1f, panX, panY, subOffset = 0.08f;
    private long lyricOffsetMs;
    private long[] lyricTimes; private String[] lyricTexts; private TextView[] lyricRows; private int lyricIndex = -2;
    private android.app.AlertDialog optionsDialog, errorDialog;
    private void resizeVideo() {
        if(frame==null || frame.getWidth()==0 || frame.getHeight()==0)return;
        int width=frame.getWidth(), height=frame.getHeight();
        if(scaleMode!=1){
            float factor=scaleMode==2 ? Math.max(width/aspect,height) : Math.min(width/aspect,height);
            width=Math.round(factor*aspect);height=Math.round(factor);
        }
        surface.setLayoutParams(new FrameLayout.LayoutParams(width,height,Gravity.CENTER));
        applyTransform();
    }
    private void applyTransform(){
        if(surface==null)return;
        surface.setScaleX(zoom);surface.setScaleY(zoom);
        float maxTx=surface.getWidth()*(zoom-1)/2f, maxTy=surface.getHeight()*(zoom-1)/2f;
        surface.setTranslationX(Math.max(-maxTx,Math.min(maxTx,panX)));
        surface.setTranslationY(Math.max(-maxTy,Math.min(maxTy,panY)));
    }
    private void resetZoom(){zoom=1f;panX=0;panY=0;applyTransform();}
    private void showGestureHint(String text,long autoHideMs){
        handler.removeCallbacks(hideHintRun);
        gestureHint.setText(text);gestureHint.setVisibility(View.VISIBLE);
        if(autoHideMs>0)handler.postDelayed(hideHintRun,autoHideMs);
    }
    private final Runnable hideHintRun=()->gestureHint.setVisibility(View.GONE);
    private void hideGestureHint(){handler.removeCallbacks(hideHintRun);gestureHint.setVisibility(View.GONE);}
    // ===== 歌词：LRC 解析 + 居中五行滚动（当前行放大高亮）=====
    private boolean parseLyrics(String raw){
        try{
            java.util.ArrayList<Long> times=new java.util.ArrayList<>();java.util.ArrayList<String> texts=new java.util.ArrayList<>();
            java.util.regex.Pattern p=java.util.regex.Pattern.compile("\\[(\\d{1,2}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");
            java.util.regex.Pattern offsetTag=java.util.regex.Pattern.compile("^\\[offset:\\s*([+-]?\\d+)\\s*]", java.util.regex.Pattern.CASE_INSENSITIVE);
            long offset=0;
            for(String line:raw.split("\n")){
                java.util.regex.Matcher om=offsetTag.matcher(line);
                if(om.find()){ // LRC 偏移标签：正值=歌词整体提前（此前被忽略导致部分歌词系统性错位）
                    try{offset=Long.parseLong(om.group(1));}catch(NumberFormatException ignored){ }
                    continue;
                }
                java.util.ArrayList<Long> stamps=new java.util.ArrayList<>();
                java.util.regex.Matcher tag=p.matcher(line);
                int last=0;
                while(last<line.length()&&tag.find(last)&&tag.start()==last){
                    long t=(Long.parseLong(tag.group(1))*60+Long.parseLong(tag.group(2)))*1000;
                    String frac=tag.group(3);
                    if(frac!=null){if(frac.length()==1)t+=Long.parseLong(frac)*100;else if(frac.length()==2)t+=Long.parseLong(frac)*10;else t+=Long.parseLong(frac);}
                    stamps.add(t);last=tag.end();
                }
                if(stamps.isEmpty())continue;
                String text=line.substring(last).replace("\r","").trim();
                if(text.isEmpty())continue;
                for(long t:stamps){times.add(t-offset);texts.add(text);}
            }
            if(times.isEmpty())return false;
            Long[] order=new Long[times.size()];
            for(int i=0;i<order.length;i++)order[i]=(long)i;
            java.util.Arrays.sort(order,(a,b)->Long.compare(times.get(a.intValue()),times.get(b.intValue())));
            lyricTimes=new long[times.size()];lyricTexts=new String[times.size()];
            for(int i=0;i<order.length;i++){int k=order[i].intValue();lyricTimes[i]=times.get(k);lyricTexts[i]=texts.get(k);}
            lyricIndex=-2;
            return true;
        }catch(Exception e){lyricTimes=null;return false;}
    }
    private void buildLyrics(FrameLayout root,String raw){
        if(!parseLyrics(raw))return;
        lyricButtons.setVisibility(View.VISIBLE);
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setGravity(Gravity.CENTER);
        lyricRows=new TextView[5];
        for(int r=0;r<5;r++){
            TextView row=new TextView(this);row.setGravity(Gravity.CENTER);row.setSaveEnabled(false);
            row.setShadowLayer(6,0,2,0xff000000);row.setPadding(0,(int)(4*getResources().getDisplayMetrics().density),0,(int)(4*getResources().getDisplayMetrics().density));
            lyricRows[r]=row;box.addView(row,new LinearLayout.LayoutParams(-2,-2));
        }
        root.addView(box,new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER));
    }
    private void updateLyrics(long pos){
        if(lyricTimes==null||lyricRows==null)return;
        pos+=lyricOffsetMs; // 歌词同步偏移：正值=歌词整体提前
        int idx=lyricIndex;
        if(idx<0)idx=0;
        while(idx+1<lyricTimes.length&&lyricTimes[idx+1]<=pos)idx++;
        while(idx>0&&lyricTimes[idx]>pos)idx--;
        if(idx==lyricIndex)return;
        lyricIndex=idx;
        for(int r=0;r<5;r++){
            int line=idx+r-2;TextView row=lyricRows[r];
            if(line<0||line>=lyricTexts.length){row.setText("");continue;}
            row.setText(lyricTexts[line]);
            boolean cur=line==idx;
            row.setTextSize(cur?20:14);
            row.setTextColor(cur?0xffffffff:(Math.abs(line-idx)==1?0xb3ffffff:0x66ffffff));
        }
    }
    private void nudgeLyrics(long delta){
        lyricOffsetMs=Math.max(-10000,Math.min(10000,lyricOffsetMs+delta));
        getSharedPreferences("tv_player",0).edit().putLong("lyricOffset",lyricOffsetMs).apply();
        lyricIndex=-2;
        showGestureHint("歌词同步 "+(lyricOffsetMs>=0?"+":"")+(lyricOffsetMs/1000f)+" 秒",900);
    }
    private void moveSub(float delta){
        subOffset=Math.max(0.02f,Math.min(0.45f,subOffset+delta));
        subtitles.setBottomPaddingFraction(subOffset);
        getSharedPreferences("tv_player",0).edit().putFloat("subOffset",subOffset).apply();
        status.setText("字幕位置 "+Math.round(subOffset*100)+"%");scheduleHide();
    }
    private void showControls(){controlsHidden=false;topBar.setVisibility(View.VISIBLE);bottomBar.setVisibility(View.VISIBLE);}
    private void hideControls(){controlsHidden=true;topBar.setVisibility(View.GONE);bottomBar.setVisibility(View.GONE);}
    private void toggleControls(){if(controlsHidden){showControls();scheduleHide();}else hideControls();}
    private final Runnable hideRun=()->{if(video!=null&&video.isPlaying()&&!seeking)hideControls();};
    private void scheduleHide(){handler.removeCallbacks(hideRun);handler.postDelayed(hideRun,3500);}
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    public static boolean allowed(String value) {
        if (value == null) return false;
        Uri uri = Uri.parse(value); String host = uri.getHost();
        return "https".equalsIgnoreCase(uri.getScheme()) && host != null
            && (host.equals("quark.cn") || host.endsWith(".quark.cn")) && uri.getUserInfo() == null
            && (uri.getPort() == -1 || uri.getPort() == 443);
    }
    private static boolean isProgressiveVideo(String url){
        String path=Uri.parse(url).getPath();
        return path!=null&&path.toLowerCase(java.util.Locale.ROOT).matches(".*\\.(mp4|m4v|mkv|webm)");
    }
    public static boolean allowedPublic(String value){
        if(value==null || value.length()>8192)return false;
        Uri u=Uri.parse(value);return "https".equalsIgnoreCase(u.getScheme())&&u.getHost()!=null&&u.getUserInfo()==null;
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String url = getIntent().getStringExtra("url");
        boolean publicSource=getIntent().getBooleanExtra("publicSource",false);
        if (!(publicSource?allowedPublic(url):allowed(url))) { finish(); return; }
        privateMode = PrivacyState.enabled;
        key = getIntent().getStringExtra("key"); if (key == null || key.length() > 200) key = "current";
        boolean music = key.startsWith("music:");
        musicMode = music;
        if (music) {
            musicCurrent = this;
            try {
                Intent keep = new Intent(this, MusicKeepService.class);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(keep); else startService(keep);
            } catch (Exception e) {
                // 后台新开播放页时系统可能拒绝启动服务（xflg=0x4 场景）：保活失败不阻断播放本身。
                android.util.Log.w("TVMusic", "keep service start denied", e);
            }
        }
        subOffset=getSharedPreferences("tv_player",0).getFloat("subOffset",0.08f);
        lyricOffsetMs=getSharedPreferences("tv_player",0).getLong("lyricOffset",0);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (privateMode) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().getDecorView().setSystemUiVisibility(5894);
        // 全屏画面 + 悬浮控制层（爱奇艺式）：上下渐变横幅、扁平图标按钮、纤细进度条。
        FrameLayout root=new FrameLayout(this);root.setBackgroundColor(0xff000000);
        frame=new FrameLayout(this);frame.setClipChildren(true);
        surface=new TextureView(this);frame.addView(surface,new FrameLayout.LayoutParams(-1,-1));
        subtitles=new SubtitleView(this);subtitles.setBottomPaddingFraction(subOffset);
        frame.addView(subtitles,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
        root.addView(frame,new FrameLayout.LayoutParams(-1,-1));
        int pad=(int)(12*getResources().getDisplayMetrics().density);
        android.graphics.drawable.GradientDrawable topGrad=new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0xb3000000,0x00000000});
        topBar=new LinearLayout(this);topBar.setOrientation(LinearLayout.VERTICAL);topBar.setPadding(pad,pad,pad,pad);topBar.setBackground(topGrad);
        title = label("‹  " + getIntent().getStringExtra("title") + (privateMode ? " · 无痕" : ""));
        title.setTextSize(15);title.setTextColor(0xffffffff);title.setTypeface(title.getTypeface(),android.graphics.Typeface.BOLD);
        title.setPadding(0,0,0,pad/3);
        title.setOnClickListener(v -> finish()); topBar.addView(title);
        status = label("正在连接，预缓冲后播放…");status.setPadding(0,0,0,pad/2);
        topBar.addView(status);
        root.addView(topBar,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));
        android.graphics.drawable.GradientDrawable bottomGrad=new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0x00000000,0xd9000000});
        bottomBar=new LinearLayout(this);bottomBar.setOrientation(LinearLayout.VERTICAL);bottomBar.setBackground(bottomGrad);bottomBar.setPadding(pad,pad/2,pad,pad*3/4);
        LinearLayout controls=new LinearLayout(this);controls.setGravity(Gravity.CENTER_VERTICAL);
        play = flat("⏸",18); controls.addView(play);
        controls.addView(space(pad/2));
        seek = new SeekBar(this); seek.setMax(1000);
        seek.setProgressTintList(android.content.res.ColorStateList.valueOf(0xffa3e635));
        seek.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x66ffffff));
        seek.setThumbTintList(android.content.res.ColorStateList.valueOf(0xffffffff));
        seek.setPadding(0,0,0,0);
        controls.addView(seek,new LinearLayout.LayoutParams(0,-2,1));
        controls.addView(space(pad/2));
        time = label("00:00 / 00:00");time.setTypeface(android.graphics.Typeface.MONOSPACE); controls.addView(time);
        bottomBar.addView(controls);
        LinearLayout options=new LinearLayout(this);options.setGravity(Gravity.CENTER_VERTICAL);
        Button scale=flat("适应",12);options.addView(scale,new LinearLayout.LayoutParams(0,-2,1));
        scale.setOnClickListener(v->{resetZoom();scaleMode=(scaleMode+1)%3;scale.setText(new String[]{"适应","拉伸","裁剪"}[scaleMode]);resizeVideo();});
        Button speed=flat("1.0×",12);options.addView(speed,new LinearLayout.LayoutParams(0,-2,1));
        speed.setOnClickListener(v->{final String[] labels={"0.5×","0.75×","1.0×","1.25×","1.5×","2.0×","3.0×"};final float[] rates={.5f,.75f,1f,1.25f,1.5f,2f,3f};
            optionsDialog=new android.app.AlertDialog.Builder(this).setTitle("播放速度").setItems(labels,(d,w)->{video.setPlaybackSpeed(rates[w]);speed.setText(labels[w]);}).show();});
        // 方向锁（爱奇艺式）：在什么方向点按就锁定当前方向，再点解锁跟随系统。
        final boolean[] dirLocked={false};
        Button orientation=flat("🔓",14);options.addView(orientation,new LinearLayout.LayoutParams(0,-2,1));
        orientation.setOnClickListener(v->{
            if(dirLocked[0]){dirLocked[0]=false;setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);orientation.setText("🔓");showGestureHint("已解锁，方向跟随系统",800);}
            else{dirLocked[0]=true;
                boolean landscape=getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE;
                setRequestedOrientation(landscape?android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE:android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
                orientation.setText("🔒");showGestureHint("已锁定"+(landscape?"横屏":"竖屏"),800);}
        });
        // 字幕偏移：检测到字幕轨才出现，调整字幕距底部的位置（记忆偏好）。
        subButtons=new LinearLayout(this);subButtons.setGravity(Gravity.CENTER_VERTICAL);subButtons.setVisibility(View.GONE);
        Button subUp=flat("字幕↑",12);subButtons.addView(subUp,new LinearLayout.LayoutParams(0,-2,1));
        Button subDown=flat("字幕↓",12);subButtons.addView(subDown,new LinearLayout.LayoutParams(0,-2,1));
        subUp.setOnClickListener(v->moveSub(0.04f));subDown.setOnClickListener(v->moveSub(-0.04f));
        options.addView(subButtons,new LinearLayout.LayoutParams(0,-2,2));
        // 歌词同步微调：跨音源版本时长有差异（酷狗版/现场版配网易歌词），词快词慢各调 0.3 秒，偏好记忆。
        lyricButtons=new LinearLayout(this);lyricButtons.setGravity(Gravity.CENTER_VERTICAL);lyricButtons.setVisibility(View.GONE);
        Button lyricEarly=flat("词早",12);lyricButtons.addView(lyricEarly,new LinearLayout.LayoutParams(0,-2,1));
        Button lyricLate=flat("词晚",12);lyricButtons.addView(lyricLate,new LinearLayout.LayoutParams(0,-2,1));
        lyricEarly.setOnClickListener(v->nudgeLyrics(300));lyricLate.setOnClickListener(v->nudgeLyrics(-300));
        options.addView(lyricButtons,new LinearLayout.LayoutParams(0,-2,2));
        Button fullscreen=flat("⛶",16);options.addView(fullscreen,new LinearLayout.LayoutParams(0,-2,1));
        fullscreen.setOnClickListener(v->{hideControls();scheduleHide();});
        bottomBar.addView(options);
        root.addView(bottomBar,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
        // 音乐播放：解析歌词并在画面中央滚动显示（当前行高亮，上下各两行渐隐）。
        gestureHint=new TextView(this);gestureHint.setTextColor(0xffffffff);gestureHint.setTextSize(18);gestureHint.setShadowLayer(6,0,2,0xff000000);gestureHint.setGravity(Gravity.CENTER);gestureHint.setVisibility(View.GONE);gestureHint.setSaveEnabled(false);
        root.addView(gestureHint,new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL));
        ((FrameLayout.LayoutParams)gestureHint.getLayoutParams()).topMargin=(int)(96*getResources().getDisplayMetrics().density);
        String lyrics=getIntent().getStringExtra("lyrics");
        if(key.startsWith("music:")&&lyrics!=null&&lyrics.length()>4)buildLyrics(root,lyrics);
        setContentView(root);
        frame.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->resizeVideo());
        // 手势：单击开/关控制层，双击复位缩放，双指捏合缩放，放大后单指拖动平移。
        ScaleGestureDetector pinch=new ScaleGestureDetector(this,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScaleBegin(ScaleGestureDetector d){return true;}
            @Override public boolean onScale(ScaleGestureDetector d){
                zoom=Math.max(1f,Math.min(4f,zoom*d.getScaleFactor()));
                if(zoom<=1.001f){panX=0;panY=0;}
                applyTransform();return true;}
        });
        GestureDetector tap=new GestureDetector(this,new GestureDetector.SimpleOnGestureListener(){
            @Override public boolean onSingleTapConfirmed(MotionEvent e){toggleControls();return true;}
            // 双击：暂停/播放（爱奇艺式）
            @Override public boolean onDoubleTap(MotionEvent e){
                if(video==null||boosting||seekGesture)return true;
                if(video.isPlaying()){video.pause();showGestureHint("已暂停",700);}
                else{video.play();showGestureHint("播放中",700);}
                return true;}
            // 长按：临时 2 倍速（当前倍率翻倍，上限 3 倍），松手恢复；不弹控制层。
            @Override public void onLongPress(MotionEvent e){
                if(video==null||boosting||seekGesture)return;
                boosting=true;baseSpeed=video.getPlaybackParameters().speed;
                float boosted=Math.min(3f,baseSpeed*2f);
                video.setPlaybackSpeed(boosted);
                showGestureHint(String.format(java.util.Locale.ROOT,"%.1f× 快进中",boosted),0);
            }
            @Override public boolean onScroll(MotionEvent e1,MotionEvent e2,float dx,float dy){
                if(zoom>1.001f||pinch.isInProgress()||e2.getPointerCount()>1){panX-=dx;panY-=dy;applyTransform();return true;}
                if(video==null||boosting)return true;
                long duration=video.getDuration();
                if(duration<=0||Math.abs(dx)<=Math.abs(dy))return true;
                if(!seekGesture){seekGesture=true;seekBaseMs=video.getCurrentPosition();seekDeltaMs=0;scrollLastT=e2.getEventTime();}
                long sdt=e2.getEventTime()-scrollLastT;scrollLastT=e2.getEventTime();
                float speed=sdt>0?Math.abs(dx)*1000f/sdt:0; // px/s
                float factor=1f+Math.min(5f,speed/1200f);   // 慢滑=原速精调，快滑最多 6 倍步进
                seekDeltaMs-=dx*(90000f/Math.max(1,frame.getWidth()))*factor;
                long target=Math.max(0,Math.min(duration,seekBaseMs+(long)seekDeltaMs));
                long offset=target-seekBaseMs;
                showGestureHint((offset>=0?"快进 +":"快退 -")+clock(Math.abs(offset)),0);
                return true;}
        });
        frame.setOnTouchListener((v,ev)->{
            pinch.onTouchEvent(ev);tap.onTouchEvent(ev);
            int action=ev.getActionMasked();
            if(video!=null&&(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)){
                if(boosting){boosting=false;video.setPlaybackSpeed(baseSpeed);hideGestureHint();}
                if(seekGesture){seekGesture=false;video.seekTo(Math.max(0,Math.min(video.getDuration(),seekBaseMs+(long)seekDeltaMs)));hideGestureHint();}
            }
            return true;});
        HashMap<String,String> headers = new HashMap<>();
        if(publicSource && getIntent().getBooleanExtra("vodSource",false)){
            // 影视直链带来源站点 Referer（多片源：跟随条目所属站点）；接口源直链 Referer 留空。
            String refererHost=getIntent().getStringExtra("refererHost");
            if(refererHost!=null&&!refererHost.isEmpty())headers.put("Referer","https://"+refererHost+"/");
        }
        if(!publicSource){headers.put("Referer","https://pan.quark.cn/");String cookie=NetdiskHttp.cookies(url);if(!cookie.isEmpty())headers.put("Cookie",cookie);}
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory().setUserAgent(NetdiskHttp.UA)
            .setDefaultRequestProperties(headers).setConnectTimeoutMs(10000).setReadTimeoutMs(15000).setAllowCrossProtocolRedirects(false);
        // 直链视频（mp4/mkv/webm）：3 连接并行区间加载（不落盘）；HLS 等流媒体：磁盘缓存只保留当前视频（进入清旧、512MiB 上限），出错直接走网络。
        DefaultMediaSourceFactory media;
        if (isProgressiveVideo(url)) {
            DataSource.Factory parallel = new ParallelDataSource.Factory(headers);
            media = new DefaultMediaSourceFactory(parallel).setLoadErrorHandlingPolicy(new DefaultLoadErrorHandlingPolicy(3));
        } else {
            MediaCache.clear();
            CacheDataSource.Factory cache = new CacheDataSource.Factory().setCache(MediaCache.get(this))
                .setUpstreamDataSourceFactory(new DefaultDataSource.Factory(this, http))
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
            media = new DefaultMediaSourceFactory(cache).setLoadErrorHandlingPolicy(new DefaultLoadErrorHandlingPolicy(3));
        }
        // 音乐为纯音频流：用小缓冲即可显著降低常驻内存（低内存机型后台播歌被系统杀掉的主因之一）。
        DefaultLoadControl buffer = new DefaultLoadControl.Builder()
            .setBufferDurationsMs(music?8000:20000, music?30000:60000, 1000, 5000)
            .setTargetBufferBytes(music?6*1024*1024:48*1024*1024).setPrioritizeTimeOverSizeThresholds(false).build();
        video = new ExoPlayer.Builder(this).setMediaSourceFactory(media).setLoadControl(buffer).build();
        video.setVideoTextureView(surface);
        video.setWakeMode(C.WAKE_MODE_NETWORK);
        video.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),true);
        video.setHandleAudioBecomingNoisy(true);
        video.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_BUFFERING){status.setText("正在缓冲… 网络恢复后自动续播");showControls();scheduleHide();}
                if (state == Player.STATE_READY) {
                    status.setText(isProgressiveVideo(url)?"已就绪 · 直链多线程加载":"已启用本片缓存 · 只保留当前播放的视频");
                    if (key.startsWith("music:") && durationListener != null) {
                        final long d = video.getDuration();
                        final String k = key;
                        if (d > 0) handler.post(() -> { DurationListener l = durationListener; if (l != null) l.onDuration(k, d); });
                    }
                }
                if (state == Player.STATE_ENDED) {
                    if (music) {
                        // 音乐：通知网页推进队列；若换源/切歌没在超时内到来则静默收尾。
                        status.setText("歌曲播放完毕");
                        EndListener l = endListener;
                        if (l != null) l.onEnded(key);
                        final int gen = swapGen;
                        handler.removeCallbacksAndMessages(END_TOKEN);
                        // 前台 12 秒收尾避免僵尸页；后台等更久（下一首解析慢时保持原地换曲链路，
                        // 后台新开播放页会触发前台服务限制——正是音乐闪退的根因）。
                        handler.postAtTime(new Runnable() { public void run() {
                            if (video != null && swapGen == gen) {
                                if (resumed) finish();
                                else handler.postDelayed(this, 15000);
                            }
                        } }, END_TOKEN, android.os.SystemClock.uptimeMillis() + 12000);
                    } else {
                        finished=true; if(!privateMode)getSharedPreferences("tv_playback",0).edit().remove(key).apply();
                        if(!endedOnce){endedOnce=true;status.setText("本集播放完毕…");handler.postDelayed(()->{
                            android.content.Intent result=new android.content.Intent();result.putExtra("ended",true);result.putExtra("key",key);
                            setResult(RESULT_OK,result);finish();},700);}
                    }
                }
            }
            @Override public void onIsPlayingChanged(boolean value) {
                play.setText(value ? "⏸" : "▶");
                if(value)scheduleHide();else{handler.removeCallbacks(hideRun);showControls();}
            }
            @Override public void onVideoSizeChanged(VideoSize size) {
                if(size.width>0&&size.height>0){aspect=size.width*size.pixelWidthHeightRatio/size.height;frame.post(()->resizeVideo());}
            }
            // 内嵌/外挂字幕轨：出现第一帧字幕时显示偏移按钮。
            @Override public void onCues(List<Cue> cues) {
                subtitles.setCues(cues);
                if(cues!=null&&!cues.isEmpty()&&subButtons.getVisibility()!=View.VISIBLE){
                    subButtons.setVisibility(View.VISIBLE);status.setText("已加载字幕，可用“字幕↑/字幕↓”调整位置");scheduleHide();
                }
            }
            @Override public void onPlayerError(PlaybackException error) {
                status.setText("加载失败，可重试或返回选择更低画质");
                // 后台/收尾中的 Activity 弹窗会 BadTokenException 崩溃（音乐后台换源失败时高发），只更新状态栏。
                if (isFinishing()) return;
                showControls();
                errorDialog=new android.app.AlertDialog.Builder(VideoActivity.this).setTitle("视频暂时无法加载")
                    .setMessage("可能是网络中断、地址过期或格式不支持。重试会从当前位置继续加载。")
                    .setPositiveButton("重试",(d,w)->{video.prepare();video.play();}).setNegativeButton("返回",(d,w)->finish()).show();
            }
        });
        play.setOnClickListener(v -> { if(video.isPlaying())video.pause();else video.play(); });
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar bar){seeking=true;}
            public void onProgressChanged(SeekBar bar,int position,boolean user){}
            public void onStopTrackingTouch(SeekBar bar){ if(video.getDuration()>0)video.seekTo(video.getDuration()*bar.getProgress()/1000);seeking=false;}
        });
        video.setMediaItem(MediaItem.fromUri(url));
        if (!privateMode && !music) video.seekTo(getSharedPreferences("tv_playback",0).getInt(key,0));
        video.prepare(); video.play();
        if(privateMode){ getIntent().removeExtra("url"); getIntent().removeExtra("title"); getIntent().removeExtra("key"); }
        scheduleHide();
        handler.post(new Runnable(){public void run(){
            if(video==null)return; long duration=video.getDuration(),position=video.getCurrentPosition();
            if(duration>0){if(!seeking)seek.setProgress((int)(position*1000/duration));seek.setSecondaryProgress((int)(video.getBufferedPosition()*1000/duration));time.setText(clock(position)+" / "+clock(duration));}
            updateLyrics(position);
            handler.postDelayed(this,500);
        }});
    }
    private TextView label(String text){TextView view=new TextView(this);view.setText(text);view.setTextColor(0xccdddddd);view.setTextSize(12);int p=(int)(12*getResources().getDisplayMetrics().density);view.setPadding(0,p/4,0,p/4);view.setSaveEnabled(false);return view;}
    /** 爱奇艺式扁平按钮：无系统按钮底色，纯白文字/图标。 */
    private Button flat(String text,float sizeSp){
        Button b=new Button(this);
        b.setText(text);b.setTextColor(0xffffffff);b.setTextSize(sizeSp);
        b.setAllCaps(false);b.setPadding(0,0,0,0);
        b.setBackgroundColor(0x00000000);b.setMinimumHeight(0);b.setMinimumWidth(0);
        return b;
    }
    private android.view.View space(int px){android.view.View v=new android.view.View(this);v.setLayoutParams(new LinearLayout.LayoutParams(px,1));return v;}
    private String clock(long ms){long sec=Math.max(0,ms/1000);return String.format(java.util.Locale.ROOT,"%02d:%02d",sec/60,sec%60);}
    @Override protected void onResume() { resumed = true; super.onResume(); }
    @Override protected void onPause() {
        resumed = false;
        if (boosting) { boosting = false; if (video != null) video.setPlaybackSpeed(baseSpeed); }
        if (video != null) {
            // 音乐键退到后台继续播放（前台服务保活）；视频仍随退后台暂停。
            if (!musicMode) {
                if (!privateMode && !finished && video.getCurrentPosition()>0)getSharedPreferences("tv_playback",0).edit().putInt(key,(int)video.getCurrentPosition()).apply();
                video.pause();
            }
        } super.onPause();
    }
    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if(optionsDialog!=null)optionsDialog.dismiss();if(errorDialog!=null)errorDialog.dismiss();
        if(video!=null){video.release();video=null;}
        if(musicMode&&musicCurrent==this){
            musicCurrent=null;
            stopService(new Intent(this,MusicKeepService.class));
        }
        MediaCache.clear();super.onDestroy();
    }
    /** 音乐原地换曲：同一播放页切换 MediaItem（队列切歌/试听换源复用，后台不触发启动限制）。 */
    public boolean swapMusic(String url,String songTitle,String newKey,String lyrics){
        if(video==null||isFinishing())return false;
        getSharedPreferences("tv_playback",0).edit().remove(newKey).apply();
        key=newKey;finished=false;endedOnce=false;seeking=false;swapGen++;
        handler.removeCallbacksAndMessages(END_TOKEN);
        title.setText("‹  "+songTitle+(privateMode?" · 无痕":""));
        if(lyrics!=null&&lyrics.length()>4&&parseLyrics(lyrics)){
            lyricIndex=-2;
            if(lyricButtons!=null)lyricButtons.setVisibility(View.VISIBLE);
            if(lyricRows!=null)for(TextView r:lyricRows)r.setText("");
        } else if(lyricButtons!=null) lyricButtons.setVisibility(View.GONE);
        status.setText("正在播放…");
        video.setMediaItem(MediaItem.fromUri(url));
        video.prepare();video.play();
        return true;
    }
    static VideoActivity liveMusic(){return musicCurrent;}
}
