package se.nils.skymning;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;

/** No Android or third-party dependencies: java -ea se.nils.skymning.GameTest. */
public final class GameTest {
    private static int checks;
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    private static void advance(Game g,float seconds){for(int i=0;i<Math.ceil(seconds*60);i++)g.update(1f/60);}
    private static Game human(int map){Game g=new Game(map,42);for(Game.Player p:g.players)p.human=true;return g;}
    public static void main(String[] args)throws Exception{
        phasesAndCapture(); controlsAndAbilities(); geometry(); traversability(); snapshots(); botsAndDeterminism(); benchmarkAndBalance();
        System.out.println("PASS GameTest: "+checks+" assertions");
    }
    private static void phasesAndCapture(){
        Game g=human(0);float x=g.players[0].x;g.input(0,1,0,true);advance(g,19);
        check(g.phase==0,"19 seconds must still be hiding");check(g.players[0].x==x,"seeker frozen during hiding");
        check(!g.visible(0,1),"hiders not leaked during hiding");advance(g,1.1f);
        check(g.phase==1,"seeking begins after 20 seconds");
        g.input(0,0,0,false);for(int i=1;i<6;i++){g.players[i].x=g.players[0].x;g.players[i].y=g.players[0].y;}
        g.update(1f/60);check(g.phase==2&&g.seekerWon,"all caught produces seeker victory");
        float doneTime=g.time;g.update(0.25f);check(g.time==doneTime,"results stop simulation");
        g=human(0);g.phase=1;g.time=.05f;g.players[0].x=40;g.players[0].y=40;advance(g,.1f);
        check(g.phase==2&&!g.seekerWon,"timer expiry produces hider victory");
        g=human(0);g.phase=1;g.players[1].x=g.players[0].x+30;g.players[1].y=g.players[0].y;
        g.update(1f/60);check(g.players[1].caught,"close hider caught");check(!g.players[2].caught,"distant hider not caught");
        g=human(0);g.phase=1;g.time=.05f;g.players[1].x=830;g.players[1].y=500;advance(g,.1f);
        check(g.players[1].caught&&!g.seekerWon,"caught hider still loses when teammates survive");
        check(!(!g.players[1].caught&&!g.seekerWon),"local captured-hider result must be defeat");
        g=human(0);g.phase=1;for(int i=2;i<6;i++)g.players[i].caught=true;
        g.input(0,-1,-.5f,false);advance(g,1);
        check(g.phase==2&&g.seekerWon,"human seeker can win through movement input");
        g=human(0);g.phase=1;g.time=1;g.players[0].x=40;g.players[0].y=40;
        g.input(1,-1,-1,true);advance(g,1.1f);
        check(g.phase==2&&!g.seekerWon&&!g.players[1].caught,"moving human hider survives and wins");
    }
    private static void controlsAndAbilities(){
        Game g=human(0);Game.Player p=g.players[1];p.x=800;p.y=400;
        g.input(1,Float.NaN,1,false);g.input(1,Float.POSITIVE_INFINITY,0,true);g.update(.1f);
        check(Float.isFinite(p.x)&&p.cooldown==0,"invalid input ignored");
        g.input(1,1,1,false);advance(g,.4f);check(Math.hypot(p.vx,p.vy)<=150.01,"diagonal input normalized");
        g.input(1,1,0,true);g.update(1f/60);check(p.boost>0&&p.cooldown>8,"dash starts and cooldown set");
        advance(g,.5f);float cooldown=p.cooldown;g.input(1,1,0,true);g.update(1f/60);
        check(p.cooldown<cooldown,"repeated ability cannot reset cooldown");check(p.trail>0,"dash leaves trace timer");
        g.phase=1;g.input(0,0,0,true);g.update(1f/60);check(g.pulse>2&&g.players[0].cooldown>11,"seeker pulse");
        p.caught=true;float px=p.x;g.input(1,-1,0,true);advance(g,.1f);check(p.x==px,"caught player cannot move");
        g=human(0);p=g.players[1];p.x=30;p.y=40;g.input(1,-1,0,false);advance(g,1);
        check(p.x>=Game.RADIUS,"world boundary respected");
    }
    private static void geometry(){
        for(int map=0;map<2;map++){
            Game g=human(map);check(g.blocks.size()>=12,"real map cover");
            for(Game.Player p:g.players)for(Game.Block b:g.blocks)check(!(p.x>b.x-18&&p.x<b.x+b.w+18&&p.y>b.y-18&&p.y<b.y+b.h+18),"spawn outside cover");
            Game.Block b=g.blocks.get(0);
            check(!g.lineOfSight(b.x-30,b.y+b.h/2,b.x+b.w+30,b.y+b.h/2),"cover blocks horizontal sight");
            check(!g.lineOfSight(b.x+b.w/2,b.y-30,b.x+b.w/2,b.y+b.h+30),"cover blocks vertical sight");
            check(g.lineOfSight(30,30,1500,30),"open sight");
            Game.Player p=g.players[1];p.x=b.x-30;p.y=b.y+b.h/2;g.input(1,1,0,false);advance(g,2);
            check(p.x<=b.x-Game.RADIUS+.01,"no tunnelling through wall");
            check(!g.lineOfSight(Float.NaN,0,1,1),"invalid sight rejected");
            g.phase=1;g.players[0].x=b.x-20;g.players[0].y=b.y+b.h/2;
            p.x=b.x+b.w+20;p.y=b.y+b.h/2;
            check(!g.visible(0,1),"hider is not visible through wall even inside sight radius");
        }
    }
    private static void traversability(){
        for(int map=0;map<2;map++){
            Game g=new Game(map,1);boolean[] free=new boolean[1000];int total=0;
            for(int n=0;n<1000;n++){
                float x=(n%40)*40+20,y=(n/40)*40+20;boolean solid=false;
                for(Game.Block b:g.blocks){float nx=Math.max(b.x,Math.min(x,b.x+b.w)),ny=Math.max(b.y,Math.min(y,b.y+b.h));if(Math.hypot(x-nx,y-ny)<Game.RADIUS)solid=true;}
                free[n]=!solid;if(!solid)total++;
            }
            boolean[] reached=new boolean[1000];ArrayDeque<Integer> q=new ArrayDeque<>();q.add(0);reached[0]=true;int count=0;
            while(!q.isEmpty()){
                int n=q.remove();count++;int x=n%40,y=n/40;
                for(int next:new int[]{x>0?n-1:-1,x<39?n+1:-1,y>0?n-40:-1,y<24?n+40:-1})if(next>=0&&free[next]&&!reached[next]){reached[next]=true;q.add(next);}
            }
            check(count==total,"all walkable map cells reachable: map "+map+" "+count+"/"+total);
            check(total>650,"sufficient navigable space on map "+map);
        }
    }
    private static void snapshots()throws Exception{
        Game g=new Game(1,99);advance(g,22);byte[] data=g.snapshot();Game copy=new Game(0,0);copy.restore(data);
        check(Arrays.equals(data,copy.snapshot()),"snapshot roundtrip exact");check(copy.map==1&&copy.blocks.get(0).kind==2,"snapshot selects map");
        byte[] old=copy.snapshot();byte[] bad=data.clone();bad[0]=0;
        try{copy.restore(bad);throw new AssertionError("bad magic accepted");}catch(IOException expected){checks++;}
        check(Arrays.equals(old,copy.snapshot()),"invalid snapshot atomic");
        for(int len:new int[]{0,1,200,202,20000})try{copy.restore(new byte[len]);throw new AssertionError("bad size accepted");}catch(IOException expected){checks++;}
        bad=data.clone();bad[21]=0x7f;bad[22]=(byte)0xc0;bad[23]=0;bad[24]=0;
        try{copy.restore(bad);throw new AssertionError("NaN position accepted");}catch(IOException expected){checks++;}
        check(Arrays.equals(old,copy.snapshot()),"NaN snapshot atomic");
        ArrayList<Integer> floatOffsets=new ArrayList<>(Arrays.asList(12,16));
        for(int p=0;p<6;p++)for(int field=0;field<7;field++)floatOffsets.add(21+p*30+field*4);
        for(int offset:floatOffsets)for(float value:new float[]{Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY}){
            bad=data.clone();ByteBuffer.wrap(bad).putFloat(offset,value);
            try{copy.restore(bad);throw new AssertionError("non-finite field accepted at "+offset);}catch(IOException expected){checks++;}
            check(Arrays.equals(old,copy.snapshot()),"malicious snapshot preserves state");
        }
    }
    private static void botsAndDeterminism(){
        for(int map=0;map<2;map++){
            Game a=new Game(map,123),b=new Game(map,123);advance(a,20);advance(b,20);
            check(Arrays.equals(a.snapshot(),b.snapshot()),"seeded deterministic bots");
            int distant=0;for(int i=1;i<6;i++)if(Math.hypot(a.players[i].x-820,a.players[i].y-500)>350)distant++;
            check(distant>=4,"bots reach distant hiding spots: map "+map+" count "+distant);
            advance(a,101);check(a.phase==2,"bot match finishes");int caught=0;
            for(int i=1;i<6;i++)if(a.players[i].caught)caught++;
            check(caught>=1,"seeker searches and catches on map "+map);
            System.out.println("Map "+map+": "+caught+" / 5 bots caught, seekerWon="+a.seekerWon);
            for(Game.Player p:a.players){check(Float.isFinite(p.x)&&Float.isFinite(p.y),"finite bot position");for(Game.Block block:a.blocks){float nx=Math.max(block.x,Math.min(p.x,block.x+block.w)),ny=Math.max(block.y,Math.min(p.y,block.y+block.h));check(Math.hypot(p.x-nx,p.y-ny)>=Game.RADIUS-.01,"bot outside collision");}}
        }
    }
    private static void benchmarkAndBalance(){
        for(int map=0;map<2;map++){
            float seconds=0;int captures=0,wins=0;
            for(int seed=0;seed<8;seed++){
                Game g=new Game(map,seed);int ticks=0;
                while(g.phase!=2&&ticks<7300){g.update(1f/60);ticks++;}
                check(g.phase==2,"seeded match completes "+seed);seconds+=ticks/60f;
                if(g.seekerWon)wins++;
                for(int i=1;i<6;i++)if(g.players[i].caught)captures++;
            }
            System.out.printf(Locale.ROOT,"Balance map %d: mean %.1fs including hide, %.2f/5 captured, seeker wins %d/8%n",map,seconds/8,captures/8f,wins);
        }
        long start=System.nanoTime();Game g=new Game(1,100);
        for(int i=0;i<10000;i++){if(g.phase==2)g=new Game(1,i);g.update(1f/60);}
        double elapsed=(System.nanoTime()-start)/1e6;
        System.out.printf(Locale.ROOT,"Desktop benchmark: 10,000 ticks %.1fms, mean %.4fms/tick (not phone FPS)%n",elapsed,elapsed/10000);
    }
}
