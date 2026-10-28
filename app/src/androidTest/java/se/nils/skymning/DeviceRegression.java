package se.nils.skymning;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import java.lang.reflect.Field;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Device regression without JUnit/AndroidX. Input tests inject real touchscreen events.
 * Result fixtures deliberately shorten rounds; they are NOT full-round playthroughs.
 * Run with: adb shell am instrument -w se.nils.skymning.test/se.nils.skymning.DeviceRegression
 */
public final class DeviceRegression extends Instrumentation {
    private static final String TAG="SkymningTest";
    private Activity activity;
    private View view;
    private int checks,tests,failures;
    private final StringBuilder report=new StringBuilder();
    private int originalRounds,originalWins,originalXp,originalMap;
    private boolean savedProgress;
    private long gestureStart;

    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);start();}
    @Override public void onStart(){
        try{
            Intent intent=new Intent(getTargetContext(),MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
            activity=startActivitySync(intent);
            SystemClock.sleep(500);
            onMain(()->{
                view=(View)field(activity,"game");
                SharedPreferences prefs=activity.getSharedPreferences("skymning",Context.MODE_PRIVATE);
                originalRounds=prefs.getInt("rounds",0);originalWins=prefs.getInt("wins",0);
                originalXp=prefs.getInt("xp",0);originalMap=prefs.getInt("map",0);savedProgress=true;
            });
            check(onMainValue(()->view.hasWindowFocus()&&view.getWidth()>0),"activity owns a laid-out foreground window");
            runCase("real input: simultaneous joystick + ability, release",this::multiTouch);
            runCase("real input: offline pause and resume",this::pauseResume);
            runCase("real input: town selection and seeker hide-phase freeze",this::townSeeker);
            runCase("CONTROLLED FIXTURE: capture, seeker victory, once-only reward and replay",this::seekerResultFixture);
            runCase("CONTROLLED FIXTURE: caught hider loses despite teammate survival",this::caughtHiderFixture);
            runCase("CONTROLLED FIXTURE: surviving hider victory",this::survivingHiderFixture);
            runCase("OFFSCREEN LAYOUT: menus and maps at three aspect ratios",this::layoutFixtures);
        }catch(Throwable failure){failures++;append("SETUP FAILURE: "+Log.getStackTraceString(failure));}
        finally{
            if(view!=null){
                try{cancelGesture();goMenu();}catch(Throwable failure){failures++;append("CLEANUP FAILURE: "+failure);}
                if(savedProgress)try{onMain(()->{
                    // Synthetic result tests must not manufacture persistent player progress.
                    setField(view,"rounds",originalRounds);setField(view,"wins",originalWins);
                    setField(view,"xp",originalXp);setField(view,"map",originalMap);
                    activity.getSharedPreferences("skymning",Context.MODE_PRIVATE).edit()
                        .putInt("rounds",originalRounds).putInt("wins",originalWins)
                        .putInt("xp",originalXp).putInt("map",originalMap).commit();
                });}catch(Throwable failure){failures++;append("PROGRESS RESTORE FAILURE: "+failure);}
            }
            String summary=(failures==0?"PASS":"FAIL")+" DeviceRegression: "+tests+" cases, "+checks+" assertions, "+failures+" failures";
            append(summary);
            Bundle results=new Bundle();results.putInt("tests",tests);results.putInt("assertions",checks);
            results.putInt("failures",failures);results.putString("stream","\n"+report);
            if(failures>0)results.putString("shortMsg",summary);
            finish(failures==0?Activity.RESULT_OK:Activity.RESULT_CANCELED,results);
        }
    }

    private void multiTouch()throws Exception{
        goMenu();tap(740,400);tap(285,332);
        check(onMainValue(()->game()!=null&&number("localId")==1&&game().phase==0),"hider training starts from menu");
        float before=onMainValue(()->game().players[1].x);
        gestureStart=SystemClock.uptimeMillis();touch(MotionEvent.ACTION_DOWN,new int[]{7},new float[][]{{126,583}});
        touch(MotionEvent.ACTION_MOVE,new int[]{7},new float[][]{{191,583}});SystemClock.sleep(300);
        float moved=onMainValue(()->game().players[1].x);
        check(moved>before+20,"sustained joystick moves local hider");
        touch(MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),new int[]{7,11},new float[][]{{191,583},{1148,583}});
        touch(MotionEvent.ACTION_MOVE,new int[]{7,11},new float[][]{{191,583},{1148,583}});SystemClock.sleep(140);
        check(onMainValue(()->number("joystick")==7&&number("other")==11),"two distinct pointer IDs remain held");
        check(onMainValue(()->game().players[1].boost>.5f&&game().players[1].cooldown>8),"ability activates while movement remains held");
        check(onMainValue(()->game().players[1].x>moved+15),"movement continues during ability");
        touch(MotionEvent.ACTION_POINTER_UP|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),new int[]{7,11},new float[][]{{191,583},{1148,583}});
        float abilityRelease=onMainValue(()->game().players[1].x);SystemClock.sleep(130);
        check(onMainValue(()->number("joystick")==7&&number("other")==-1&&game().players[1].x>abilityRelease+15),"lifting ability finger preserves joystick movement");
        touch(MotionEvent.ACTION_UP,new int[]{7},new float[][]{{191,583}});SystemClock.sleep(350);
        check(onMainValue(()->number("joystick")==-1&&Math.abs(decimal("moveX"))<.001f&&Math.abs(game().players[1].vx)<2),"lifting joystick clears input and slows velocity");
        float settled=onMainValue(()->game().players[1].x);SystemClock.sleep(300);
        check(onMainValue(()->Math.abs(game().players[1].x-settled)<1),"released character stays stopped");
    }
    private void pauseResume()throws Exception{
        goMenu();tap(285,332);tap(1195,48);
        check(onMainValue(()->flag("paused")),"pause button opens pause overlay");
        float pausedAt=onMainValue(()->game().time);SystemClock.sleep(450);
        check(onMainValue(()->Math.abs(game().time-pausedAt)<.001f),"offline timer remains frozen during pause");
        tap(640,335);float resumedAt=onMainValue(()->game().time);SystemClock.sleep(350);
        check(onMainValue(()->!flag("paused")&&game().time<resumedAt-.15f),"continue button resumes simulation");
    }
    private void townSeeker()throws Exception{
        goMenu();tap(1040,400);tap(285,410);
        check(onMainValue(()->game().map==1&&number("localId")==0),"town card and seeker training select correct map and role");
        float x=onMainValue(()->game().players[0].x),y=onMainValue(()->game().players[0].y);
        gestureStart=SystemClock.uptimeMillis();touch(MotionEvent.ACTION_DOWN,new int[]{7},new float[][]{{191,583}});
        SystemClock.sleep(350);touch(MotionEvent.ACTION_UP,new int[]{7},new float[][]{{191,583}});
        check(onMainValue(()->game().phase==0&&game().players[0].x==x&&game().players[0].y==y),"seeker cannot move during hiding countdown");
        check(onMainValue(()->!game().visible(0,1)),"seeker cannot see hiders during countdown");
    }
    private void seekerResultFixture()throws Exception{
        goMenu();tap(285,410);
        int rounds=onMainValue(()->number("rounds")),wins=onMainValue(()->number("wins")),xp=onMainValue(()->number("xp"));
        onMain(()->{
            Game g=game();freezeBots(g);g.phase=1;g.time=30;
            for(Game.Player p:g.players){p.x=820;p.y=500;p.vx=p.vy=0;}
        });SystemClock.sleep(350);
        check(onMainValue(()->game().phase==2&&game().seekerWon),"controlled close-range capture reaches seeker victory");
        check(onMainValue(()->number("rounds")==rounds+1&&number("wins")==wins+1&&number("xp")==xp+100),"victory awards one round, one win and 100 XP");
        SystemClock.sleep(450);
        check(onMainValue(()->number("rounds")==rounds+1&&number("xp")==xp+100),"result screen does not award twice across frames");
        tap(640,415);
        check(onMainValue(()->game().phase==0&&!flag("awarded")&&number("localId")==0),"replay button starts fresh round with same role");
    }
    private void caughtHiderFixture()throws Exception{
        goMenu();tap(285,332);
        int rounds=onMainValue(()->number("rounds")),wins=onMainValue(()->number("wins")),xp=onMainValue(()->number("xp"));
        onMain(()->{
            Game g=game();freezeBots(g);g.phase=1;g.time=.12f;
            g.players[0].x=820;g.players[0].y=500;g.players[1].x=830;g.players[1].y=500;
            for(int i=2;i<6;i++){g.players[i].x=40+i*40;g.players[i].y=40;}
        });SystemClock.sleep(350);
        check(onMainValue(()->game().phase==2&&game().players[1].caught&&!game().seekerWon&&!game().players[2].caught),"captured local hider loses while another hider survives");
        check(onMainValue(()->number("rounds")==rounds+1&&number("wins")==wins&&number("xp")==xp+35),"captured hider receives participation XP without a win");
        SystemClock.sleep(300);
        check(onMainValue(()->number("xp")==xp+35),"defeat reward is also once-only");
    }
    private void survivingHiderFixture()throws Exception{
        goMenu();tap(285,332);
        int rounds=onMainValue(()->number("rounds")),wins=onMainValue(()->number("wins")),xp=onMainValue(()->number("xp"));
        onMain(()->{Game g=game();freezeBots(g);g.phase=1;g.time=.1f;g.players[0].x=40;g.players[0].y=40;});
        SystemClock.sleep(350);
        check(onMainValue(()->game().phase==2&&!game().players[1].caught&&!game().seekerWon),"surviving hider reaches victory at timer expiry");
        check(onMainValue(()->number("rounds")==rounds+1&&number("wins")==wins+1&&number("xp")==xp+100),"hider victory awards correct persistent counters");
        tap(640,499);check(onMainValue(()->"menu".equals(field(view,"screen"))&&game()==null),"result menu button clears match");
    }
    private static void freezeBots(Game g){for(Game.Player p:g.players){p.human=true;p.vx=p.vy=0;g.input(p.id,0,0,false);}}

    private void layoutFixtures()throws Exception{
        onMain(()->{
            GameView preview=new GameView((MainActivity)activity);
            java.io.File dir=new java.io.File(activity.getExternalFilesDir(null),"layout-checks");
            if(!dir.exists()&&!dir.mkdirs())throw new java.io.IOException("Cannot create layout output");
            try{
                int[][] sizes={{1280,720},{1600,720},{1024,768}};
                String[] screens={"menu","rules","settings","wifi","forest","town"};
                for(int[] size:sizes)for(String screen:screens){
                    setField(preview,"screen",screen.equals("forest")||screen.equals("town")?"game":screen);
                    if(screen.equals("forest")||screen.equals("town")){setField(preview,"game",new Game(screen.equals("town")?1:0,1));setField(preview,"localId",1);}
                    preview.layout(0,0,size[0],size[1]);
                    android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(size[0],size[1],android.graphics.Bitmap.Config.ARGB_8888);
                    preview.draw(new android.graphics.Canvas(bitmap));
                    java.util.List<?> buttons=(java.util.List<?>)field(preview,"buttons");
                    for(Object button:buttons){android.graphics.RectF bounds=(android.graphics.RectF)field(button,"r");check(bounds.left>=0&&bounds.top>=0&&bounds.right<=1280&&bounds.bottom<=720,"button inside design space: "+screen);}
                    try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(dir,screen+"-"+size[0]+"x"+size[1]+".png"))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}
                    bitmap.recycle();
                }
            }finally{preview.dispose();}
        });
    }

    private void goMenu()throws Exception{
        String screen=onMainValue(()->(String)field(view,"screen"));
        if("game".equals(screen)){
            if(onMainValue(()->game().phase==2))tap(640,499);
            else{if(!onMainValue(()->flag("paused")))tap(1195,48);tap(640,490);}
        }else if(!"menu".equals(screen))tap(140,65);
        check(onMainValue(()->"menu".equals(field(view,"screen"))),"main menu reachable");
    }
    private void tap(float x,float y)throws Exception{
        gestureStart=SystemClock.uptimeMillis();touch(MotionEvent.ACTION_DOWN,new int[]{0},new float[][]{{x,y}});
        SystemClock.sleep(35);touch(MotionEvent.ACTION_UP,new int[]{0},new float[][]{{x,y}});SystemClock.sleep(180);
    }
    private void touch(int action,int[] ids,float[][] coordinates)throws Exception{
        float[] transform=onMainValue(()->{int[] location=new int[2];view.getLocationOnScreen(location);return new float[]{decimal("uiScale"),decimal("offsetX")+location[0],decimal("offsetY")+location[1]};});
        MotionEvent.PointerProperties[] properties=new MotionEvent.PointerProperties[ids.length];
        MotionEvent.PointerCoords[] points=new MotionEvent.PointerCoords[ids.length];
        for(int i=0;i<ids.length;i++){
            properties[i]=new MotionEvent.PointerProperties();properties[i].id=ids[i];properties[i].toolType=MotionEvent.TOOL_TYPE_FINGER;
            points[i]=new MotionEvent.PointerCoords();points[i].x=transform[1]+coordinates[i][0]*transform[0];points[i].y=transform[2]+coordinates[i][1]*transform[0];points[i].pressure=1;points[i].size=.08f;
        }
        MotionEvent event=MotionEvent.obtain(gestureStart,SystemClock.uptimeMillis(),action,ids.length,properties,points,0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);
        try{sendPointerSync(event);}finally{event.recycle();}
    }
    private void cancelGesture()throws Exception{gestureStart=SystemClock.uptimeMillis();touch(MotionEvent.ACTION_CANCEL,new int[]{0},new float[][]{{126,583}});}
    private Game game()throws Exception{return(Game)field(view,"game");}
    private int number(String name)throws Exception{return((Number)field(view,name)).intValue();}
    private float decimal(String name)throws Exception{return((Number)field(view,name)).floatValue();}
    private boolean flag(String name)throws Exception{return(Boolean)field(view,name);}
    private static Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private static void setField(Object target,String name,Object value)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    private void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);Log.i(TAG,"PASS: "+message);}
    private void append(String message){report.append(message).append('\n');Log.i(TAG,message);}
    private void runCase(String name,CheckedRunnable test){tests++;try{test.run();append("PASS "+name);}catch(Throwable failure){failures++;append("FAIL "+name+"\n"+Log.getStackTraceString(failure));try{cancelGesture();}catch(Exception ignored){}}Bundle status=new Bundle();status.putString("stream",name+": "+failures+" cumulative failures\n");sendStatus(0,status);}
    private interface CheckedRunnable{void run()throws Exception;}
    private void onMain(CheckedRunnable action)throws Exception{onMainValue(()->{action.run();return null;});}
    private <T>T onMainValue(Callable<T> action)throws Exception{
        AtomicReference<T> result=new AtomicReference<>();AtomicReference<Throwable> error=new AtomicReference<>();
        runOnMainSync(()->{try{result.set(action.call());}catch(Throwable failure){error.set(failure);}});
        if(error.get()!=null){Throwable t=error.get();if(t instanceof Exception)throw(Exception)t;throw new AssertionError(t);}
        return result.get();
    }
}
