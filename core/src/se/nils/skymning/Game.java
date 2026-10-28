package se.nils.skymning;

import java.io.*;
import java.util.*;

/** Platform-independent, host-authoritative kurragomma simulation. Units are world pixels. */
public final class Game {
    public static final float WIDTH = 1600, HEIGHT = 1000, RADIUS = 18;
    private static final int COLS = 40, ROWS = 25, CELL = 40;
    private static final float SIGHT = 300;
    public final List<Block> blocks = new ArrayList<>();
    public final Player[] players = new Player[6];
    public int phase, map;
    public float time = 20, pulse;
    public boolean seekerWon;
    private final Random random;
    private final boolean[] walkable = new boolean[COLS * ROWS];
    private final float[] ix = new float[6], iy = new float[6];
    private final boolean[] ability = new boolean[6];
    private final int[] goals = new int[6];
    private final float[] think = new float[6];
    private final List<ArrayDeque<Integer>> routes = new ArrayList<>();
    private final float[] visited = new float[COLS * ROWS];
    private float age, visitClock;

    public static final class Block {
        public float x, y, w, h;
        /** 0: foliage, 1: rock, 2: building, 3: hedge. */
        public int kind;
        Block(float x, float y, float w, float h, int kind) {
            this.x=x; this.y=y; this.w=w; this.h=h; this.kind=kind;
        }
    }
    public static final class Player {
        public int id;
        public float x, y, vx, vy, cooldown, boost, trail;
        public boolean caught, human;
        Player(int id, float x, float y) { this.id=id; this.x=x; this.y=y; }
    }

    public Game(int map, long seed) {
        this.map = map == 1 ? 1 : 0;
        random = new Random(seed);
        makeMap();
        float[][] spawn={{820,500},{740,460},{900,460},{740,540},{900,540},{820,580}};
        for(int i=0;i<6;i++) {
            players[i]=new Player(i,spawn[i][0],spawn[i][1]);
            routes.add(new ArrayDeque<>());
            goals[i]=-1;
        }
    }

    private void block(int x,int y,int w,int h,int kind) { blocks.add(new Block(x,y,w,h,kind)); }
    private void makeMap() {
        blocks.clear();
        if(map==0) {
            block(160,120,120,120,0); block(400,80,160,80,0);
            block(680,120,120,160,0); block(960,80,120,120,0);
            block(1240,120,160,120,0); block(1440,360,80,120,0);
            block(120,400,120,160,0); block(360,320,160,80,1);
            block(560,480,80,160,0); block(1080,360,160,80,0);
            block(1200,560,160,80,1); block(960,640,80,160,0);
            block(200,720,160,120,0); block(440,840,160,80,0);
            block(680,760,120,120,1); block(1200,800,160,120,0);
            block(80,80,40,80,1); block(1480,760,40,80,1);
        } else {
            block(120,120,240,160,2); block(480,120,160,200,2);
            block(840,120,200,160,2); block(1200,120,240,200,2);
            block(120,440,200,160,2); block(440,440,160,200,2);
            block(1080,440,160,200,2); block(1360,440,120,160,2);
            block(120,760,240,120,2); block(520,800,200,120,2);
            block(880,800,200,120,2); block(1240,760,200,160,2);
            block(760,340,160,40,3); block(720,680,200,40,3);
        }
        for(int n=0;n<walkable.length;n++) walkable[n]=!blocked(cx(n),cy(n));
    }

    /** Input is normalized and rejected if non-finite. Ability is a one-shot request. */
    public void input(int id,float x,float y,boolean useAbility) {
        if(id<0 || id>=6 || !Float.isFinite(x) || !Float.isFinite(y)) return;
        double length=Math.hypot(x,y);
        if(length>1) { x/=length; y/=length; }
        ix[id]=x; iy[id]=y; ability[id]|=useAbility;
    }

    public void update(float dt) {
        if(!Float.isFinite(dt) || dt<=0 || phase==2) return;
        // Substeps keep collision and timer behavior consistent even after a slow frame.
        dt=Math.min(dt,0.25f);
        while(dt>0.000001f) { float step=Math.min(dt,1f/60f); tick(step); dt-=step; }
    }
    private void tick(float dt) {
        if(phase==2) return;
        age+=dt; time=Math.max(0,time-dt); pulse=Math.max(0,pulse-dt);
        if(time<=0) {
            if(phase==0) { phase=1; time=100; for(int i=0;i<6;i++) {think[i]=0; routes.get(i).clear();} }
            else { phase=2; seekerWon=false; stopAll(); return; }
        }
        for(Player p:players) {
            p.cooldown=Math.max(0,p.cooldown-dt);
            p.boost=Math.max(0,p.boost-dt); p.trail=Math.max(0,p.trail-dt);
            if(p.caught || (p.id==0 && phase==0)) { p.vx=p.vy=0; ability[p.id]=false; continue; }
            if(!p.human) bot(p,dt);
            if(ability[p.id] && p.cooldown<=0) {
                if(p.id==0) { pulse=2.2f; p.cooldown=12; }
                else { p.boost=1.05f; p.trail=2.3f; p.cooldown=9; }
            }
            ability[p.id]=false;
            float speed=p.id==0?180:150;
            if(p.boost>0) speed=260;
            float targetX=ix[p.id]*speed,targetY=iy[p.id]*speed;
            float smooth=Math.min(1,dt*20);
            p.vx+=(targetX-p.vx)*smooth; p.vy+=(targetY-p.vy)*smooth;
            move(p,p.vx*dt,p.vy*dt);
        }
        if(phase==1) {
            Player seeker=players[0];
            // Patrol memory needs only 5 Hz; collision/capture still run at 60 Hz.
            visitClock-=dt;
            if(visitClock<=0) {
                visitClock=.2f;
                for(int n=0;n<visited.length;n++) if(walkable[n] && dist2(seeker.x,seeker.y,cx(n),cy(n))<SIGHT*SIGHT && lineOfSight(seeker.x,seeker.y,cx(n),cy(n))) visited[n]=age;
            }
            boolean all=true;
            for(int i=1;i<6;i++) {
                Player p=players[i];
                if(!p.caught && dist2(seeker.x,seeker.y,p.x,p.y)<44*44 && lineOfSight(seeker.x,seeker.y,p.x,p.y)) {p.caught=true;p.vx=p.vy=0;}
                all &= p.caught;
            }
            if(all) {phase=2;seekerWon=true;stopAll();}
        }
    }
    private void stopAll() { for(Player p:players) p.vx=p.vy=0; }

    private void bot(Player p,float dt) {
        int id=p.id;
        think[id]-=dt;
        if(think[id]<=0) {
            think[id]=id==0?0.3f:0.65f;
            int goal=goals[id];
            if(id==0) {
                Player nearest=null; float d=Float.MAX_VALUE;
                for(int i=1;i<6;i++) if(!players[i].caught && visible(0,i)) {
                    float candidate=dist2(p.x,p.y,players[i].x,players[i].y);
                    if(candidate<d) {nearest=players[i];d=candidate;}
                }
                if(nearest!=null) goal=nearestCell(nearest.x,nearest.y);
                else if(goal<0 || routes.get(id).isEmpty() || dist2(p.x,p.y,cx(goal),cy(goal))<2500) goal=searchGoal(p);
                if(p.cooldown==0 && nearest==null) ability[id]=true;
            } else {
                Player s=players[0];
                boolean danger=phase==1 && dist2(p.x,p.y,s.x,s.y)<320*320 && lineOfSight(p.x,p.y,s.x,s.y);
                if(goal<0 || danger) goal=hideGoal(p,danger);
                if(danger && p.cooldown==0 && dist2(p.x,p.y,s.x,s.y)<200*200) ability[id]=true;
            }
            if(goal>=0 && (goal!=goals[id] || routes.get(id).isEmpty())) {
                goals[id]=goal; routes.set(id,path(nearestCell(p.x,p.y),goal));
            }
        }
        ArrayDeque<Integer> route=routes.get(id);
        while(!route.isEmpty() && dist2(p.x,p.y,cx(route.peek()),cy(route.peek()))<4) route.remove();
        if(route.isEmpty()) {ix[id]=iy[id]=0;return;}
        // String-pull the grid route only when the whole player can clear the segment.
        // This avoids both robot-like stops at every cell and corner-cutting into walls.
        int reachable=route.peek();
        for(int n:route){if(clearMovement(p.x,p.y,cx(n),cy(n)))reachable=n;else break;}
        while(route.peek()!=reachable)route.remove();
        float dx=cx(route.peek())-p.x,dy=cy(route.peek())-p.y;
        float length=(float)Math.hypot(dx,dy);
        float arrival=Math.min(1,length/16);
        ix[id]=dx/length*arrival;iy[id]=dy/length*arrival;
    }
    private int hideGoal(Player p,boolean danger) {
        Player s=players[0]; int best=nearestCell(p.x,p.y); float score=-Float.MAX_VALUE;
        // Deterministic candidates; varied personal destinations prevent a pile of bots.
        for(int tries=0;tries<110;tries++) {
            int n=random.nextInt(walkable.length);if(!walkable[n])continue;
            float distance=(float)Math.sqrt(dist2(s.x,s.y,cx(n),cy(n)));
            float travel=(float)Math.sqrt(dist2(p.x,p.y,cx(n),cy(n)));
            if(danger && travel>550)continue;
            float value=distance-(danger?0.4f:0.08f)*travel;
            if(!lineOfSight(s.x,s.y,cx(n),cy(n)))value+=danger?350:180;
            // Prefer spaces near cover, not exposed outermost corners.
            float cover=1000;
            for(Block b:blocks)cover=Math.min(cover,(float)Math.hypot(cx(n)-Math.max(b.x,Math.min(cx(n),b.x+b.w)),cy(n)-Math.max(b.y,Math.min(cy(n),b.y+b.h))));
            if(cover<100)value+=200;
            for(int i=1;i<6;i++)if(i!=p.id && goals[i]>=0 && dist2(cx(n),cy(n),cx(goals[i]),cy(goals[i]))<180*180)value-=300;
            if(value>score){score=value;best=n;}
        }
        return best;
    }
    private int searchGoal(Player p) {
        int best=nearestCell(p.x,p.y);float value=-Float.MAX_VALUE;
        for(int n=0;n<walkable.length;n++) {
            if(!walkable[n])continue;
            float distance=(float)Math.sqrt(dist2(p.x,p.y,cx(n),cy(n)));
            if(distance<120)continue;
            float score=(age-visited[n])*18-distance*0.20f;
            if(score>value){value=score;best=n;}
        }
        return best;
    }

    private ArrayDeque<Integer> path(int start,int goal) {
        ArrayDeque<Integer> result=new ArrayDeque<>();
        if(start==goal)return result;
        int[] previous=new int[walkable.length];Arrays.fill(previous,-1);
        int[] queue=new int[walkable.length];int read=0,write=0;queue[write++]=start;previous[start]=start;
        while(read<write && previous[goal]<0) {
            int n=queue[read++],x=n%COLS,y=n/COLS;
            int[] adjacent={x>0?n-1:-1,x<COLS-1?n+1:-1,y>0?n-COLS:-1,y<ROWS-1?n+COLS:-1};
            for(int next:adjacent) if(next>=0 && walkable[next] && previous[next]<0){previous[next]=n;queue[write++]=next;}
        }
        if(previous[goal]<0)return result;
        for(int n=goal;n!=start;n=previous[n])result.addFirst(n);
        return result;
    }
    private int nearestCell(float x,float y) {
        int best=0;float score=Float.MAX_VALUE;
        for(int n=0;n<walkable.length;n++)if(walkable[n]){
            float d=dist2(x,y,cx(n),cy(n));if(d<score){score=d;best=n;}
        }
        return best;
    }
    private static float cx(int n){return(n%COLS)*CELL+CELL/2f;}
    private static float cy(int n){return(n/COLS)*CELL+CELL/2f;}
    private static float dist2(float x,float y,float a,float b){float dx=x-a,dy=y-b;return dx*dx+dy*dy;}
    private boolean blocked(float x,float y) {
        if(x<RADIUS || y<RADIUS || x>WIDTH-RADIUS || y>HEIGHT-RADIUS)return true;
        for(Block b:blocks) {
            float nx=Math.max(b.x,Math.min(x,b.x+b.w)),ny=Math.max(b.y,Math.min(y,b.y+b.h));
            if(dist2(x,y,nx,ny)<RADIUS*RADIUS)return true;
        }
        return false;
    }
    private boolean clearMovement(float ax,float ay,float bx,float by) {
        int steps=Math.max(1,(int)(Math.hypot(bx-ax,by-ay)/5)+1);
        for(int i=1;i<=steps;i++)if(blocked(ax+(bx-ax)*i/steps,ay+(by-ay)*i/steps))return false;
        return true;
    }
    private void move(Player p,float dx,float dy) {
        // Axis separation permits smooth sliding along cover and prevents tunnelling.
        if(!blocked(p.x+dx,p.y))p.x+=dx;else p.vx=0;
        if(!blocked(p.x,p.y+dy))p.y+=dy;else p.vy=0;
    }
    public boolean visible(int viewerId,int targetId) {
        if(viewerId<0 || viewerId>=6 || targetId<0 || targetId>=6)return false;
        if(viewerId==targetId)return true;
        Player target=players[targetId];
        if(target.caught)return false;
        if(viewerId!=0)return true;
        if(phase==0)return false;
        Player viewer=players[viewerId];
        // A sprint is a tactical tradeoff: its bright trace can be spotted farther away.
        float sight=target.trail>0?SIGHT+120:SIGHT;
        return dist2(viewer.x,viewer.y,target.x,target.y)<=sight*sight && lineOfSight(viewer.x,viewer.y,target.x,target.y);
    }
    public boolean lineOfSight(float ax,float ay,float bx,float by) {
        if(!Float.isFinite(ax)||!Float.isFinite(ay)||!Float.isFinite(bx)||!Float.isFinite(by))return false;
        for(Block b:blocks) if(intersects(ax,ay,bx,by,b))return false;
        return true;
    }
    private static boolean intersects(float ax,float ay,float bx,float by,Block b) {
        float dx=bx-ax,dy=by-ay,tmin=0,tmax=1;
        if(Math.abs(dx)<0.0001f){if(ax<b.x || ax>b.x+b.w)return false;}
        else{float t1=(b.x-ax)/dx,t2=(b.x+b.w-ax)/dx;tmin=Math.max(tmin,Math.min(t1,t2));tmax=Math.min(tmax,Math.max(t1,t2));}
        if(Math.abs(dy)<0.0001f){if(ay<b.y || ay>b.y+b.h)return false;}
        else{float t1=(b.y-ay)/dy,t2=(b.y+b.h-ay)/dy;tmin=Math.max(tmin,Math.min(t1,t2));tmax=Math.min(tmax,Math.max(t1,t2));}
        return tmin<=tmax;
    }

    /** Complete rendering state; simulation remains authoritative on the host. */
    public byte[] snapshot() {
        try {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
            out.writeInt(0x534B5931);out.writeInt(map);out.writeInt(phase);out.writeFloat(time);out.writeFloat(pulse);out.writeBoolean(seekerWon);
            for(Player p:players){out.writeFloat(p.x);out.writeFloat(p.y);out.writeFloat(p.vx);out.writeFloat(p.vy);out.writeFloat(p.cooldown);out.writeFloat(p.boost);out.writeFloat(p.trail);out.writeBoolean(p.caught);out.writeBoolean(p.human);}
            out.flush();return bytes.toByteArray();
        }catch(IOException impossible){throw new AssertionError(impossible);}
    }
    public void restore(byte[] bytes) throws IOException {
        if(bytes==null || bytes.length!=201)throw new IOException("Felaktig matchdata");
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));
        if(in.readInt()!=0x534B5931)throw new IOException("Fel spelversion");
        int nextMap=in.readInt(),nextPhase=in.readInt();float nextTime=in.readFloat(),nextPulse=in.readFloat();boolean won=in.readBoolean();
        if(nextMap<0||nextMap>1||nextPhase<0||nextPhase>2||!range(nextTime,0,100)||!range(nextPulse,0,3))throw new IOException("Ogiltigt matchtillstand");
        Player[] next=new Player[6];
        for(int i=0;i<6;i++) {
            Player p=new Player(i,in.readFloat(),in.readFloat());p.vx=in.readFloat();p.vy=in.readFloat();p.cooldown=in.readFloat();p.boost=in.readFloat();p.trail=in.readFloat();p.caught=in.readBoolean();p.human=in.readBoolean();
            if(!range(p.x,RADIUS,WIDTH-RADIUS)||!range(p.y,RADIUS,HEIGHT-RADIUS)||!range(p.vx,-300,300)||!range(p.vy,-300,300)||!range(p.cooldown,0,12)||!range(p.boost,0,2)||!range(p.trail,0,3))throw new IOException("Ogiltig spelare");
            next[i]=p;
        }
        // Commit only after the entire packet passes validation.
        if(map!=nextMap){map=nextMap;makeMap();}
        phase=nextPhase;time=nextTime;pulse=nextPulse;seekerWon=won;
        for(int i=0;i<6;i++)players[i]=next[i];
    }
    private static boolean range(float value,float min,float max){return Float.isFinite(value)&&value>=min&&value<=max;}
}
