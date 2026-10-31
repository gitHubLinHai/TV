package com.seagull.tv.tests;
import android.app.*;import android.content.*;import android.os.Bundle;import android.content.Intent;import android.view.*;

/** 播放器手势真机验证（爱奇艺式）：双击暂停/播放、横滑快退、长按 2 倍速、捏合缩放、单击控制层。 */
public class PlayerGestureInstrumentation extends NetdiskInstrumentation {
    private static final String URL = "https://www.w3school.com.cn/i/movie.mp4";
    private static MotionEvent.PointerProperties pp(int id){
        MotionEvent.PointerProperties p=new MotionEvent.PointerProperties();p.id=id;p.toolType=MotionEvent.TOOL_TYPE_FINGER;return p;}
    private static MotionEvent.PointerCoords pc(float x,float y){
        MotionEvent.PointerCoords c=new MotionEvent.PointerCoords();c.x=x;c.y=y;c.pressure=1;c.size=1;return c;}
    private static MotionEvent simple(int action,float x,float y){
        return MotionEvent.obtain(android.os.SystemClock.uptimeMillis(),android.os.SystemClock.uptimeMillis()+4,action,x,y,0);}
    private static MotionEvent at(long t,long et,int action,float x,float y){
        return MotionEvent.obtain(t,et,action,x,y,0);}
    private float speedOf(Object video){try{
        Object pp=Class.forName("androidx.media3.common.Player").getMethod("getPlaybackParameters").invoke(video);
        return pp.getClass().getField("speed").getFloat(pp);}catch(Throwable e){throw new RuntimeException(e);}}
    private long posOf(Object video){try{
        return ((Number)Class.forName("androidx.media3.common.Player").getMethod("getCurrentPosition").invoke(video)).longValue();}catch(Throwable e){throw new RuntimeException(e);}}
    private boolean playingOf(Object video){try{
        return (Boolean)Class.forName("androidx.media3.common.Player").getMethod("isPlaying").invoke(video);}catch(Throwable e){throw new RuntimeException(e);}}
    @Override public void onStart(){Bundle result=new Bundle();try{
        Intent i=new Intent();i.setClassName("com.seagull.tv","com.seagull.tv.VideoActivity");
        i.putExtra("url",URL);i.putExtra("title","gesture-test");i.putExtra("key","gesture-test");i.putExtra("publicSource",true);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity player=startActivitySync(i);
        Object video=field(player,"video");
        long end=System.currentTimeMillis()+90000;final long[] started={0};
        while(System.currentTimeMillis()<end){runOnMainSync(()->{try{started[0]=posOf(video);}catch(Throwable e){throw new RuntimeException(e);}});if(started[0]>800)break;Thread.sleep(300);}
        require(started[0]>800,"playback never advanced: "+started[0]+" ms");
        android.view.View frame=(android.view.View)field(player,"frame"),surface=(android.view.View)field(player,"surface");
        final float fx=frame.getWidth()/2f,fy=frame.getHeight()/2f;
        // ===== 1. 长按临时 2 倍速（不弹控制层）=====
        final float[] speed={1,1,1};
        runOnMainSync(()->{speed[0]=speedOf(video);});
        // 长按：按下后真实保持 800ms（跨过 500ms 长按阈值），期间速度应已翻倍，抬起恢复
        runOnMainSync(()->{
            long t0=android.os.SystemClock.uptimeMillis();
            MotionEvent d=at(t0,t0,MotionEvent.ACTION_DOWN,fx,fy);frame.dispatchTouchEvent(d);d.recycle();
        });
        Thread.sleep(800);
        runOnMainSync(()->{speed[1]=speedOf(video);});
        runOnMainSync(()->{
            long et=android.os.SystemClock.uptimeMillis();
            MotionEvent u=at(et-800,et,MotionEvent.ACTION_UP,fx,fy);frame.dispatchTouchEvent(u);u.recycle();
        });
        Thread.sleep(200);
        runOnMainSync(()->{try{speed[2]=speedOf(video);}catch(Throwable e){throw new RuntimeException(e);}});
        require(speed[1]>=speed[0]*1.9f&&Math.abs(speed[2]-speed[0])<0.01f,"Long-press boost failed: "+speed[0]+" -> "+speed[1]+" -> "+speed[2]);
        // ===== 2. 双击=暂停 =====
        doubleTap(frame,fx,fy);
        Thread.sleep(500);
        final boolean[] playing={true};
        runOnMainSync(()->{try{playing[0]=playingOf(video);}catch(Throwable e){throw new RuntimeException(e);}});
        require(!playing[0],"Double tap did not pause");
        // ===== 3. 暂停态横滑快退（未缩放）：向左拖 60px，钳制回 0 附近 =====
        runOnMainSync(()->{
            long t0=android.os.SystemClock.uptimeMillis();
            MotionEvent ev=simple(MotionEvent.ACTION_DOWN,fx-40,fy);frame.dispatchTouchEvent(ev);ev.recycle();
            for(int step=1;step<=6;step++){
                MotionEvent me=simple(MotionEvent.ACTION_MOVE,fx-40-step*10,fy);frame.dispatchTouchEvent(me);me.recycle();
            }
            MotionEvent up=simple(MotionEvent.ACTION_UP,fx-100,fy);frame.dispatchTouchEvent(up);up.recycle();
        });
        Thread.sleep(400);
        final long[] posAfterSeek={-1};
        runOnMainSync(()->{posAfterSeek[0]=posOf(video);});
        require(posAfterSeek[0]<600,"Horizontal swipe did not seek back: pos="+posAfterSeek[0]);
        // ===== 4. 双击=继续播放（seek 后重新缓冲，轮询等待恢复播放）=====
        doubleTap(frame,fx,fy);
        long resumeEnd=System.currentTimeMillis()+5000;
        final boolean[] playWhenReady={false};
        while(System.currentTimeMillis()<resumeEnd){
            runOnMainSync(()->{try{playWhenReady[0]=(Boolean)Class.forName("androidx.media3.common.Player").getMethod("getPlayWhenReady").invoke(video);}catch(Throwable e){throw new RuntimeException(e);}});
            if(playWhenReady[0])break;
            Thread.sleep(250);
        }
        require(playWhenReady[0],"Double tap did not resume playback intent");
        // ===== 5. 捏合缩放 =====
        final float[] zoom={1,1};
        runOnMainSync(()->{zoom[0]=surface.getScaleX();});
        pinchSequence(frame,fx,fy);
        Thread.sleep(300);
        runOnMainSync(()->{zoom[1]=surface.getScaleX();});
        require(zoom[1]>1.05f,"Pinch did not zoom: "+zoom[0]+" -> "+zoom[1]);
        // ===== 6. 放大态拖动平移 =====
        runOnMainSync(()->{try{
            java.lang.reflect.Field zf=player.getClass().getDeclaredField("zoom");zf.setAccessible(true);zf.setFloat(player,3f);
            surface.setScaleX(3f);surface.setScaleY(3f);}catch(Exception e){throw new RuntimeException(e);}});
        final float[] before={0,0},after={0,0};
        runOnMainSync(()->{before[0]=surface.getTranslationX();before[1]=surface.getTranslationY();});
        final float px0=frame.getWidth()/2f-160,py0=frame.getHeight()/2f+170;
        runOnMainSync(()->{
            long t0=android.os.SystemClock.uptimeMillis();
            MotionEvent ev=simple(MotionEvent.ACTION_DOWN,px0,py0);frame.dispatchTouchEvent(ev);ev.recycle();
            for(int step=1;step<=10;step++){
                MotionEvent me=simple(MotionEvent.ACTION_MOVE,px0-step*11,py0-step*6);frame.dispatchTouchEvent(me);me.recycle();
            }
            MotionEvent up=simple(MotionEvent.ACTION_UP,px0-110,py0-60);frame.dispatchTouchEvent(up);up.recycle();
        });
        Thread.sleep(250);
        runOnMainSync(()->{after[0]=surface.getTranslationX();after[1]=surface.getTranslationY();});
        require(Math.abs(after[0]-before[0])>2||Math.abs(after[1]-before[1])>2,"Drag did not pan when zoomed: "+before[0]+","+before[1]+" -> "+after[0]+","+after[1]);
        // ===== 7. 单击开关控制层 =====
        final boolean[] wasHidden={false},nowHidden={false};
        singleTap(frame,fx,fy);
        Thread.sleep(900);
        runOnMainSync(()->{try{wasHidden[0]=(Boolean)field(player,"controlsHidden");}catch(Exception e){throw new RuntimeException(e);}});
        singleTap(frame,fx,fy);
        Thread.sleep(900);
        runOnMainSync(()->{try{nowHidden[0]=(Boolean)field(player,"controlsHidden");}catch(Exception e){throw new RuntimeException(e);}});
        require(nowHidden[0]!=wasHidden[0],"Single tap did not toggle controls: "+wasHidden[0]+" -> "+nowHidden[0]);
        // ===== 8. 字幕偏移 =====
        final int[] subVis={-1};final float[] frac={0,0};
        runOnMainSync(()->{try{
            android.view.View sb=(android.view.View)field(player,"subButtons");
            subVis[0]=sb.getVisibility();
            java.lang.reflect.Field sf=player.getClass().getDeclaredField("subOffset");sf.setAccessible(true);
            frac[0]=sf.getFloat(player);
            java.lang.reflect.Method m=player.getClass().getDeclaredMethod("moveSub",float.class);m.setAccessible(true);m.invoke(player,0.04f);
            frac[1]=sf.getFloat(player);
        }catch(Throwable e){throw new RuntimeException(e);}});
        require(subVis[0]==View.GONE,"Subtitle buttons visible without cues");
        require(Math.abs((frac[1]-frac[0])-0.04f)<0.001&&frac[0]>=0.02f&&frac[1]<=0.45f,"Subtitle offset adjust failed: "+frac[0]+" -> "+frac[1]);
        runOnMainSync(()->player.finish());
        result.putString("stream","PASS: long-press boost "+speed[0]+"x->"+speed[1]+"x, double-tap pause/resume, swipe seeked back to "+posAfterSeek[0]+" ms, pinch "+zoom[0]+"x->"+zoom[1]+"x, zoomed pan, single tap toggles controls, subtitle offset "+frac[0]+"->"+frac[1]+".\n");
        finish(Activity.RESULT_OK,result);
    }catch(Throwable e){result.putString("stream","FAIL: "+e+" STACK "+android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,result);}}

    private String runOnMainSyncWith(java.util.function.Supplier<String> supplier){
        final String[] out={null};
        runOnMainSync(()->out[0]=supplier.get());
        return out[0];
    }
    private void doubleTap(android.view.View frame,float x,float y)throws Exception{
        long t0=android.os.SystemClock.uptimeMillis();
        runOnMainSync(()->{
            MotionEvent d1=at(t0,t0,MotionEvent.ACTION_DOWN,x,y);frame.dispatchTouchEvent(d1);d1.recycle();
            MotionEvent u1=at(t0,t0+50,MotionEvent.ACTION_UP,x,y);frame.dispatchTouchEvent(u1);u1.recycle();
            MotionEvent d2=at(t0,t0+140,MotionEvent.ACTION_DOWN,x,y);frame.dispatchTouchEvent(d2);d2.recycle();
            MotionEvent u2=at(t0,t0+190,MotionEvent.ACTION_UP,x,y);frame.dispatchTouchEvent(u2);u2.recycle();
        });
    }
    private void singleTap(android.view.View frame,float x,float y)throws Exception{
        runOnMainSync(()->{
            long t0=android.os.SystemClock.uptimeMillis();
            MotionEvent d=at(t0,t0,MotionEvent.ACTION_DOWN,x,y);frame.dispatchTouchEvent(d);d.recycle();
            MotionEvent u=at(t0,t0+50,MotionEvent.ACTION_UP,x,y);frame.dispatchTouchEvent(u);u.recycle();
        });
    }
    private void pinchSequence(android.view.View frame,float fx,float fy)throws Exception{
        runOnMainSync(()->{
            long t0=android.os.SystemClock.uptimeMillis();
            MotionEvent.PointerProperties[] p2=new MotionEvent.PointerProperties[]{pp(0),pp(1)};
            MotionEvent.PointerCoords[] cSpread=new MotionEvent.PointerCoords[2];
            MotionEvent ev=simple(MotionEvent.ACTION_DOWN,fx,fy);frame.dispatchTouchEvent(ev);ev.recycle();
            float spread=0;
            for(int step=0;step<=10;step++){
                spread=40+step*30;
                cSpread[0]=pc(fx-spread,fy);cSpread[1]=pc(fx+spread,fy);
                int action=step==0?MotionEvent.ACTION_POINTER_DOWN|((1&0xff)<<MotionEvent.ACTION_POINTER_INDEX_SHIFT):MotionEvent.ACTION_MOVE;
                MotionEvent me=MotionEvent.obtain(t0,t0+16L*step,action,2,p2,cSpread,0,0,1f,1f,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);
                frame.dispatchTouchEvent(me);me.recycle();
            }
            MotionEvent up=MotionEvent.obtain(t0,t0+16L*11,MotionEvent.ACTION_POINTER_UP|((1&0xff)<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),2,p2,cSpread,0,0,1f,1f,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);
            frame.dispatchTouchEvent(up);up.recycle();
            MotionEvent up0=MotionEvent.obtain(t0,t0+16L*12,MotionEvent.ACTION_UP,fx-spread,fy,0);
            frame.dispatchTouchEvent(up0);up0.recycle();
        });
    }
}
