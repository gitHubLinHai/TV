package com.seagull.tv.tests;
import android.app.*;import android.content.*;import android.os.Bundle;import android.content.Intent;import android.view.*;

/** 播放器手势真机验证：双指捏合缩放画面、放大后拖动平移、双击复位、单击开合控制层。 */
public class PlayerGestureInstrumentation extends NetdiskInstrumentation {
    private static final String URL = "https://www.w3school.com.cn/i/movie.mp4";
    private static MotionEvent.PointerProperties pp(int id){
        MotionEvent.PointerProperties p=new MotionEvent.PointerProperties();p.id=id;p.toolType=MotionEvent.TOOL_TYPE_FINGER;return p;}
    private static MotionEvent.PointerCoords pc(float x,float y){
        MotionEvent.PointerCoords c=new MotionEvent.PointerCoords();c.x=x;c.y=y;c.pressure=1;c.size=1;return c;}
    private static MotionEvent multi(int action,MotionEvent.PointerProperties[] props,MotionEvent.PointerCoords[] coords){
        return MotionEvent.obtain(android.os.SystemClock.uptimeMillis(),android.os.SystemClock.uptimeMillis()+4,action,props.length,props,coords,0,0,1f,1f,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);}
    private static MotionEvent simple(int action,float x,float y){
        return MotionEvent.obtain(android.os.SystemClock.uptimeMillis(),android.os.SystemClock.uptimeMillis()+4,action,x,y,0);}
    @Override public void onStart(){Bundle result=new Bundle();try{
        Intent i=new Intent();i.setClassName("com.seagull.tv","com.seagull.tv.VideoActivity");
        i.putExtra("url",URL);i.putExtra("title","gesture-test");i.putExtra("key","gesture-test");i.putExtra("publicSource",true);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity player=startActivitySync(i);
        Object video=field(player,"video");
        long end=System.currentTimeMillis()+45000;final long[] started={0};
        while(System.currentTimeMillis()<end){runOnMainSync(()->{try{started[0]=((Number)Class.forName("androidx.media3.common.Player").getMethod("getCurrentPosition").invoke(video)).longValue();}catch(Exception ignored){}});if(started[0]>800)break;Thread.sleep(300);}
        require(started[0]>800,"playback never advanced: "+started[0]+" ms");
        // 长按临时 2 倍速：按住翻倍、抬起恢复（放最前，测试视频仅 3 秒，播完页面会自毁）
        final float[] speed={1,1,1};
        android.view.View frame0=(android.view.View)field(player,"frame");
        float fx0=frame0.getWidth()/2f,fy0=frame0.getHeight()/2f;
        runOnMainSync(()->{try{Object v=field(player,"video");Object pp=Class.forName("androidx.media3.common.Player").getMethod("getPlaybackParameters").invoke(v);speed[0]=pp.getClass().getField("speed").getFloat(pp);}catch(Throwable e){throw new RuntimeException(e);}});
        runOnMainSync(()->{MotionEvent d=simple(MotionEvent.ACTION_DOWN,fx0,fy0);frame0.dispatchTouchEvent(d);d.recycle();});
        Thread.sleep(800);
        runOnMainSync(()->{try{Object v=field(player,"video");Object pp=Class.forName("androidx.media3.common.Player").getMethod("getPlaybackParameters").invoke(v);speed[1]=pp.getClass().getField("speed").getFloat(pp);}catch(Throwable e){throw new RuntimeException(e);}});
        runOnMainSync(()->{MotionEvent u=simple(MotionEvent.ACTION_UP,fx0,fy0);frame0.dispatchTouchEvent(u);u.recycle();});
        Thread.sleep(400);
        runOnMainSync(()->{try{Object v=field(player,"video");Object pp=Class.forName("androidx.media3.common.Player").getMethod("getPlaybackParameters").invoke(v);speed[2]=pp.getClass().getField("speed").getFloat(pp);}catch(Throwable e){throw new RuntimeException(e);}});
        require(speed[1]>=speed[0]*1.9f&&speed[2]<=speed[0]+0.01f,"Long-press speed boost failed: "+speed[0]+" -> "+speed[1]+" -> "+speed[2]);
        android.view.View frame=(android.view.View)field(player,"frame"),surface=(android.view.View)field(player,"surface");
        float cx=frame.getWidth()/2f,cy=frame.getHeight()/2f;
        final float[] zoom={1,1};
        runOnMainSync(()->{zoom[0]=surface.getScaleX();});
        // 双指捏合：单块派发完整序列；每步跨度增长 60px（远超 ScaleGestureDetector 启动斜差），逐 MOVE 累积缩放。
        final float fx=cx,fy=cy;
        final float[] fieldZoom={1,1};
        runOnMainSync(()->{try{fieldZoom[0]=(Float)field(player,"zoom");}catch(Exception e){throw new RuntimeException(e);}});
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
        Thread.sleep(300);
        runOnMainSync(()->{zoom[1]=surface.getScaleX();try{fieldZoom[1]=(Float)field(player,"zoom");}catch(Exception e){throw new RuntimeException(e);}});
        require(zoom[1]>1.05f,"Pinch did not zoom: surface "+zoom[0]+" -> "+zoom[1]+", field "+fieldZoom[0]+" -> "+fieldZoom[1]);
        // 模拟已放大到 3 倍（真机上由持续 onScale 累积到达），验证放大态手势分支
        runOnMainSync(()->{try{
            java.lang.reflect.Field zf=player.getClass().getDeclaredField("zoom");zf.setAccessible(true);zf.setFloat(player,3f);
            surface.setScaleX(3f);surface.setScaleY(3f);}catch(Exception e){throw new RuntimeException(e);}});
        // 放大后单指拖动平移
        final float[] before={0,0},after={0,0};
        runOnMainSync(()->{before[0]=surface.getTranslationX();before[1]=surface.getTranslationY();});
        final float px0=cx-160,py0=cy+170;
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
        // 双击复位缩放
        runOnMainSync(()->{
            long t0=android.os.SystemClock.uptimeMillis();
            MotionEvent d1=MotionEvent.obtain(t0,t0,MotionEvent.ACTION_DOWN,fx,fy,0);frame.dispatchTouchEvent(d1);d1.recycle();
            MotionEvent u1=MotionEvent.obtain(t0,t0+50,MotionEvent.ACTION_UP,fx,fy,0);frame.dispatchTouchEvent(u1);u1.recycle();
            MotionEvent d2=MotionEvent.obtain(t0,t0+140,MotionEvent.ACTION_DOWN,fx,fy,0);frame.dispatchTouchEvent(d2);d2.recycle();
            MotionEvent u2=MotionEvent.obtain(t0,t0+190,MotionEvent.ACTION_UP,fx,fy,0);frame.dispatchTouchEvent(u2);u2.recycle();
        });
        Thread.sleep(600);
        final float[] reset={9,9,9,9};
        runOnMainSync(()->{reset[0]=surface.getScaleX();reset[2]=surface.getTranslationX();reset[3]=surface.getTranslationY();});
        require(Math.abs(reset[0]-1f)<0.02&&Math.abs(reset[2])<2&&Math.abs(reset[3])<2,"Double tap did not reset zoom: scale "+reset[0]+" pan "+reset[2]+","+reset[3]);
        // 单击切换控制层：记录当前状态，单击后应取反（自动隐藏计时器可能先隐藏，两种初始状态都合法）
        final boolean[] wasHidden={false},nowHidden={false};
        runOnMainSync(()->{
            long t0=android.os.SystemClock.uptimeMillis();
            MotionEvent d=simple(MotionEvent.ACTION_DOWN,fx,fy);frame.dispatchTouchEvent(d);d.recycle();
            MotionEvent u=MotionEvent.obtain(t0,t0+50,MotionEvent.ACTION_UP,fx,fy,0);frame.dispatchTouchEvent(u);u.recycle();
        });
        Thread.sleep(900);
        runOnMainSync(()->{try{wasHidden[0]=(Boolean)field(player,"controlsHidden");}catch(Exception e){throw new RuntimeException(e);}});
        runOnMainSync(()->{
            long t0=android.os.SystemClock.uptimeMillis();
            MotionEvent d=simple(MotionEvent.ACTION_DOWN,fx,fy);frame.dispatchTouchEvent(d);d.recycle();
            MotionEvent u=MotionEvent.obtain(t0,t0+50,MotionEvent.ACTION_UP,fx,fy,0);frame.dispatchTouchEvent(u);u.recycle();
        });
        Thread.sleep(900);
        runOnMainSync(()->{try{nowHidden[0]=(Boolean)field(player,"controlsHidden");}catch(Exception e){throw new RuntimeException(e);}});
        require(nowHidden[0]!=wasHidden[0],"Single tap did not toggle controls: "+wasHidden[0]+" -> "+nowHidden[0]);
        // 字幕偏移：无字幕轨时按钮隐藏；moveSub 调整距底比例并持久化
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
        result.putString("stream","PASS: long-press speed "+speed[0]+"x -> "+speed[1]+"x -> "+speed[2]+"x, pinch zoomed surface "+zoom[0]+"x -> "+zoom[1]+"x, drag panned ("+before[0]+","+before[1]+")->("+after[0]+","+after[1]+"), double tap reset, single tap toggles controls, subtitle offset "+frac[0]+" -> "+frac[1]+" with buttons hidden until cues.\n");
        finish(Activity.RESULT_OK,result);
    }catch(Throwable e){result.putString("stream","FAIL: "+e+"\n");finish(Activity.RESULT_CANCELED,result);}}
}
