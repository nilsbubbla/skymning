package se.nils.skymning;

import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import java.io.*;
import java.net.*;
import java.util.*;

/** Canvas UI in a 1280x720 safe design space, with an independent world camera. */
final class GameView extends View implements Choreographer.FrameCallback {
    private static final int INK=Color.rgb(14,29,38), PANEL=Color.rgb(26,46,55), WHITE=Color.rgb(239,244,232), MUTED=Color.rgb(152,175,177), MINT=Color.rgb(157,231,198), PEACH=Color.rgb(255,183,140);
    private final MainActivity activity;
    private final Paint p=new Paint(3);
    private final RectF rect=new RectF();
    private final SharedPreferences prefs;
    private final Sound sound;
    private final ArrayList<Button> buttons=new ArrayList<>();
    private Game game;
    private Lan lan;
    private String screen="menu", notice="", address="";
    private int map,localId=1,joystick=-1,other=-1,previousPhase=-1,previousCaught,networkGeneration;
    private int rounds,wins,xp;
    private boolean paused,running,ability,awarded,soundOn,vibrate,busy;
    private float moveX,moveY,stickX,stickY,clock,accum,netClock,noticeTime,camX=800,camY=500;
    private float uiScale=1,offsetX,offsetY;
    private long previousFrame;
    private String stateMarker="";
    private final float[] renderX=new float[6],renderY=new float[6];
    private boolean renderReady;
    private final int[] colors={PEACH,MINT,0xffb4c9ff,0xffffd28c,0xffe0b0df,0xff9fdde6};
    private static class Button { final RectF r; final Runnable run; Button(float x,float y,float w,float h,Runnable run){r=new RectF(x,y,x+w,y+h);this.run=run;} }

    GameView(MainActivity activity) {
        super(activity);this.activity=activity;
        prefs=activity.getSharedPreferences("skymning",Context.MODE_PRIVATE);
        rounds=prefs.getInt("rounds",0);wins=prefs.getInt("wins",0);xp=prefs.getInt("xp",0);
        soundOn=prefs.getBoolean("sound",true);vibrate=prefs.getBoolean("vibrate",true);map=prefs.getInt("map",0);
        sound=new Sound(activity);sound.enabled=soundOn;
        setFocusable(true);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }
    void resume(){if(!running){running=true;previousFrame=0;Choreographer.getInstance().postFrameCallback(this);}}
    void suspend(){running=false;Choreographer.getInstance().removeFrameCallback(this);resetInput();if(lan!=null||busy){leave();game=null;screen="menu";toast("Wi-Fi-matchen avslutades när appen lämnades.");}else if(game!=null&&game.phase!=2)paused=true;}
    void dispose(){running=false;Choreographer.getInstance().removeFrameCallback(this);leave();sound.close();}
    void save(Bundle b){if(lan==null&&game!=null){b.putByteArray("game",game.snapshot());b.putInt("map",map);b.putInt("local",localId);b.putBoolean("awarded",awarded);}}
    void recover(Bundle b){try{byte[] data=b.getByteArray("game");if(data!=null){map=b.getInt("map");localId=b.getInt("local",1);game=new Game(map,1);game.restore(data);screen="game";paused=game.phase!=2;awarded=b.getBoolean("awarded");camX=game.players[localId].x;camY=game.players[localId].y;}}catch(IOException e){game=null;screen="menu";}}
    String lastAddress(){return prefs.getString("address","");}
    void back(){if(screen.equals("game")){if(game.phase==2){leave();screen="menu";game=null;}else{paused=!paused;resetInput();}}else if(!screen.equals("menu")){leave();screen="menu";}else activity.finish();}
    private void toast(String s){notice=s;noticeTime=7;}
    private void leave(){networkGeneration++;busy=false;if(lan!=null){lan.close();lan=null;}resetInput();}
    private void resetInput(){moveX=moveY=stickX=stickY=0;joystick=other=-1;ability=false;if(game!=null)game.input(localId,0,0,false);}
    private void buzz(){if(vibrate){Vibrator v=(Vibrator)activity.getSystemService(Context.VIBRATOR_SERVICE);if(v!=null&&v.hasVibrator())v.vibrate(VibrationEffect.createOneShot(22,VibrationEffect.DEFAULT_AMPLITUDE));}}
    private void start(int id){
        localId=id;game=new Game(map,System.nanoTime());game.players[id].human=true;
        if(lan!=null)for(int peer:lan.peers())game.players[peer].human=true;
        screen="game";paused=false;awarded=false;renderReady=false;previousPhase=0;previousCaught=0;accum=netClock=0;camX=game.players[id].x;camY=game.players[id].y;resetInput();sound.play(0);
    }
    private void host(){
        if(busy)return;busy=true;int generation=++networkGeneration;
        new Thread(()->{try{Lan ready=Lan.host();String ip=localAddresses();post(()->{if(generation!=networkGeneration){ready.close();return;}lan=ready;address=ip;localId=0;screen="lobby";busy=false;});}catch(IOException e){post(()->{if(generation==networkGeneration){busy=false;toast("Kunde inte skapa rum: "+e.getMessage());}});}},"create-room").start();
    }
    void join(String ip){
        if(busy)return;busy=true;int generation=++networkGeneration;
        new Thread(()->{try{Lan ready=Lan.join(ip);post(()->{if(generation!=networkGeneration){ready.close();return;}lan=ready;localId=ready.localId;screen="lobby";address=ip;busy=false;prefs.edit().putString("address",ip).apply();});}catch(IOException e){post(()->{if(generation==networkGeneration){busy=false;toast(e.getMessage()==null?"Anslutningen misslyckades.":e.getMessage());}});}},"join-room").start();
    }
    private String localAddresses(){try{ArrayList<String> list=new ArrayList<>();Enumeration<NetworkInterface> it=NetworkInterface.getNetworkInterfaces();while(it.hasMoreElements()){NetworkInterface n=it.nextElement();if(!n.isUp()||n.isLoopback())continue;Enumeration<InetAddress> a=n.getInetAddresses();while(a.hasMoreElements()){InetAddress ip=a.nextElement();if(ip instanceof Inet4Address&&!ip.isLoopbackAddress())list.add(ip.getHostAddress());}}return list.isEmpty()?"Anslut telefonen till Wi-Fi":String.join("  /  ",list);}catch(Exception e){return "Se IP-adress i Wi-Fi-inställningar";}}
    private void network(float dt){
        if(lan==null)return;
        Lan.Event event;int count=0;
        while(lan!=null&&(event=lan.events.poll())!=null&&count++<100){
            if(event.type==Lan.CONNECTED){if(lan.hosting&&game!=null&&screen.equals("game")){game.players[event.peer].human=true;game.input(event.peer,0,0,false);}sound.play(0);}
            else if(event.type==Lan.DISCONNECTED){if(lan.hosting){if(game!=null)game.players[event.peer].human=false;toast("En vän lämnade. En dator tar över.");}else{leave();screen="menu";game=null;toast("Värden avslutade rummet. Du kan spela träning eller ansluta igen.");return;}}
            else if(event.type==Lan.ERROR)toast(event.message());
            else if(event.type==Lan.DATA){try{DataInputStream in=new DataInputStream(new ByteArrayInputStream(event.data));int type=in.readUnsignedByte();if(type==1&&!lan.hosting){int m=in.readUnsignedByte();byte[] data=new byte[in.available()];in.readFully(data);boolean fresh=game==null||!screen.equals("game");int oldPhase=fresh?-1:game.phase;if(fresh)game=new Game(m,1);game.restore(data);if(fresh||(oldPhase==2&&game.phase==0)){awarded=false;paused=false;renderReady=false;previousCaught=0;previousPhase=game.phase;camX=game.players[localId].x;camY=game.players[localId].y;}map=game.map;screen="game";}
                else if(type==2&&lan.hosting&&game!=null&&event.data.length==10){game.input(event.peer,in.readFloat(),in.readFloat(),in.readBoolean());}
                else if(type==3&&!lan.hosting){map=in.readUnsignedByte();}
            }catch(IOException|RuntimeException e){toast("Ett ogiltigt nätverkspaket ignorerades.");}}
        }
        netClock+=dt;
        if(lan==null||netClock<.075f)return;netClock=0;
        try{ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
            if(lan.hosting){if(game!=null&&screen.equals("game")){out.writeByte(1);out.writeByte(map);out.write(game.snapshot());}else{out.writeByte(3);out.writeByte(map);}}
            else if(game!=null&&screen.equals("game")){out.writeByte(2);out.writeFloat(paused?0:moveX);out.writeFloat(paused?0:moveY);out.writeBoolean(ability&&!paused);ability=false;}
            if(bytes.size()>0)lan.send(bytes.toByteArray());
        }catch(IOException ignored){}
    }
    @Override public void doFrame(long ns){
        if(!running)return;
        float dt=previousFrame==0?0:Math.min(.1f,(ns-previousFrame)/1e9f);previousFrame=ns;clock+=dt;noticeTime=Math.max(0,noticeTime-dt);
        network(dt);
        if(game!=null&&screen.equals("game")){
            if((!paused||lan!=null)&&(lan==null||lan.hosting)){
                game.input(localId,paused?0:moveX,paused?0:moveY,ability&&!paused);ability=false;
                accum+=dt;while(accum>=1f/60f){game.update(1f/60f);accum-=1f/60f;}
            }
            int caught=0;for(Game.Player a:game.players)if(a.caught)caught++;
            if(game.phase!=previousPhase){sound.play(game.phase==2?3:1);buzz();previousPhase=game.phase;}
            if(caught>previousCaught){sound.play(2);buzz();previousCaught=caught;}
            if(game.phase==2&&!awarded){awarded=true;boolean won=localId==0?game.seekerWon:!game.players[localId].caught;rounds++;if(won)wins++;xp+=won?100:35;prefs.edit().putInt("rounds",rounds).putInt("wins",wins).putInt("xp",xp).apply();}
            Game.Player focus=game.players[game.players[localId].caught?0:localId];camX+=(focus.x-camX)*Math.min(1,dt*7);camY+=(focus.y-camY)*Math.min(1,dt*7);
            for(Game.Player a:game.players){float blend=lan!=null&&!lan.hosting&&renderReady?Math.min(1,dt*22):1;renderX[a.id]+=(a.x-renderX[a.id])*blend;renderY[a.id]+=(a.y-renderY[a.id])*blend;}renderReady=true;
        }
        String marker="screen="+(game!=null&&screen.equals("game")&&game.phase==2?"result":screen)+(game!=null?";phase="+game.phase+";role="+localId+";paused="+paused:"")+";uiX="+offsetX+";uiY="+offsetY+";uiScale="+uiScale;
        if(!marker.equals(stateMarker)){stateMarker=marker;setContentDescription(marker);android.util.Log.i("SkymningState",marker);}
        invalidate();Choreographer.getInstance().postFrameCallback(this);
    }
    @Override protected void onDraw(Canvas c){
        super.onDraw(c);c.drawColor(INK);buttons.clear();
        WindowInsets wi=getRootWindowInsets();int left=0,right=0,top=0,bottom=0;
        if(wi!=null&&Build.VERSION.SDK_INT>=28&&wi.getDisplayCutout()!=null){DisplayCutout d=wi.getDisplayCutout();left=d.getSafeInsetLeft();right=d.getSafeInsetRight();top=d.getSafeInsetTop();bottom=d.getSafeInsetBottom();}
        uiScale=Math.min((getWidth()-left-right)/1280f,(getHeight()-top-bottom)/720f);offsetX=left+(getWidth()-left-right-1280*uiScale)/2;offsetY=top+(getHeight()-top-bottom-720*uiScale)/2;
        c.save();c.translate(offsetX,offsetY);c.scale(uiScale,uiScale);c.clipRect(0,0,1280,720);
        if(screen.equals("game")&&game!=null)drawGame(c);
        else{drawBackdrop(c);switch(screen){case "wifi":drawWifi(c);break;case "lobby":drawLobby(c);break;case "rules":drawRules(c);break;case "settings":drawSettings(c);break;default:drawMenu(c);}}
        if(noticeTime>0){box(c,160,650,960,52,0xf0223943,16);text(c,notice,640,682,19,WHITE,true);}
        c.restore();
    }
    private void color(int color){p.setShader(null);p.setColor(color);p.setStyle(Paint.Style.FILL);}
    private void box(Canvas c,float x,float y,float w,float h,int col,float radius){color(col);rect.set(x,y,x+w,y+h);c.drawRoundRect(rect,radius,radius,p);}
    private void text(Canvas c,String s,float x,float y,float size,int col,boolean center){color(col);p.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));p.setTextSize(size);p.setTextAlign(center?Paint.Align.CENTER:Paint.Align.LEFT);c.drawText(s,x,y,p);}
    private void bold(Canvas c,String s,float x,float y,float size,int col){color(col);p.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));p.setTextSize(size);p.setTextAlign(Paint.Align.LEFT);c.drawText(s,x,y,p);}
    private void line(Canvas c,float x,float y,float x2,float y2,int col,float width){color(col);p.setStrokeWidth(width);c.drawLine(x,y,x2,y2,p);}
    private void circle(Canvas c,float x,float y,float r,int col){color(col);c.drawCircle(x,y,r,p);}
    private void button(Canvas c,String label,float x,float y,float w,float h,boolean primary,Runnable action){box(c,x,y,w,h,primary?MINT:PANEL,16);text(c,label,x+w/2,y+h/2+8,23,primary?INK:WHITE,true);buttons.add(new Button(x,y,w,h,()->{sound.play(0);action.run();}));}
    private void drawBackdrop(Canvas c){
        color(INK);p.setShader(new LinearGradient(0,0,1280,720,new int[]{0xff183b3d,INK,0xff19252f},null,Shader.TileMode.CLAMP));c.drawRect(0,0,1280,720,p);p.setShader(null);
        circle(c,1010,105,53,0xffdfdfb8);circle(c,1036,88,46,0xff193337);
        for(int i=0;i<21;i++){float x=(i*137)%1320,y=490+(i*73)%230;tree(c,x,y,65+(i%4)*20,0xff173c3c,0xff214b45);}
        for(int i=0;i<25;i++){float x=(i*197)%1280,y=80+(i*83)%550;circle(c,x+(float)Math.sin(clock*.4+i)*5,y+(float)Math.cos(clock*.6+i)*6,1.5f,0x668de5b7);}
    }
    private void tree(Canvas c,float x,float y,float r,int dark,int light){box(c,x-5,y,10,r*.55f,0xff735e49,3);circle(c,x+6,y+9,r,dark);circle(c,x-r*.22f,y-r*.12f,r*.76f,light);circle(c,x+r*.35f,y-r*.08f,r*.54f,light);}
    private void drawMenu(Canvas c){
        text(c,"ETT LITET ÄVENTYR I KURRAGÖMMA",65,92,18,MINT,false);
        bold(c,"Skymning",60,176,76,WHITE);text(c,"Hitta ditt gömställe.",65,222,28,WHITE,false);text(c,"Håll andan. Låt jakten börja.",65,259,25,MUTED,false);
        button(c,"Göm dig  ·  träning",65,300,440,64,true,()->start(1));
        button(c,"Leta  ·  träning",65,379,440,64,false,()->start(0));
        button(c,"Spela med vänner",65,458,440,64,false,()->screen="wifi");
        button(c,"Så spelar du",65,546,212,56,false,()->screen="rules");button(c,"Inställningar",291,546,214,56,false,()->screen="settings");
        text(c,rank()+"  ·  "+wins+" segrar  ·  "+rounds+" rundor",65,650,19,MUTED,false);
        box(c,575,230,640,372,0xe01a3038,24);bold(c,"Välj ditt gömställe",605,277,29,WHITE);
        mapCard(c,605,302,275,235,0,"Skogsgläntan","Dungar & slingrande stigar");mapCard(c,906,302,275,235,1,"Gamla stan","Gränder & tysta innergårdar");
        text(c,"20 sekunder att gömma sig  /  100 sekunder att leta",605,573,19,MUTED,false);
    }
    private String rank(){return xp<300?"Nyfiken stigfinnare":xp<1000?"Skuggspanare":xp<2500?"Skogens viskning":"Skymningsmästare";}
    private void mapCard(Canvas c,float x,float y,float w,float h,int id,String title,String subtitle){
        box(c,x-3,y-3,w+6,h+6,map==id?MINT:0xff30464e,18);box(c,x,y,w,h,INK,16);c.save();c.clipRect(x,y,x+w,y+142);box(c,x,y,w,142,id==0?0xff325d4c:0xff565b5c,0);
        if(id==0){line(c,x-10,y+130,x+w,y+20,0xff928564,26);for(int i=0;i<7;i++)tree(c,x+25+i*41,y+25+(i%3)*35,27,0xff244c40,0xff4d8061);}else{for(int i=0;i<4;i++){box(c,x+14+i*68,y+12,47,80,0xffb7a38c,5);box(c,x+18+i*68,y+17,39,48,i%2==0?0xff8d6258:0xff687f85,3);for(int j=0;j<2;j++)box(c,x+24+i*68+j*17,y+72,9,12,0xfff1dbaa,2);}line(c,x,y+116,x+w,y+116,0xff9c9d91,2);}c.restore();
        bold(c,title,x+15,y+177,25,WHITE);text(c,subtitle,x+15,y+207,16,MUTED,false);if(map==id)circle(c,x+w-23,y+20,7,MINT);
        buttons.add(new Button(x,y,w,h,()->{map=id;prefs.edit().putInt("map",map).apply();sound.play(0);}));
    }
    private void heading(Canvas c,String title,String subtitle){button(c,"‹  Tillbaka",55,40,170,54,false,()->{leave();screen="menu";});bold(c,title,90,170,46,WHITE);text(c,subtitle,92,215,23,MUTED,false);}
    private void drawWifi(Canvas c){heading(c,"Tillsammans i skymningen","2–6 spelare på samma Wi-Fi. Datorer fyller tomma platser.");box(c,90,265,1100,300,0xe01a3038,24);bold(c,"En skapar rummet. Resten ansluter.",125,318,29,WHITE);text(c,"Värden letar. Vännerna gömmer sig. Ingen internetanslutning behövs.",125,360,22,MUTED,false);button(c,busy?"Ansluter…":"Skapa rum",125,410,475,70,true,()->{if(!busy)host();});button(c,"Anslut till vän",625,410,530,70,false,()->{if(!busy)activity.askAddress();});text(c,"Gästnät kan blockera kontakt mellan telefoner. Prova då ett vanligt hemnät.",125,530,19,MUTED,false);}
    private void drawLobby(Canvas c){heading(c,lan!=null&&lan.hosting?"Ditt rum är öppet":"Du är med!",lan!=null&&lan.hosting?"Dela adressen nedan med dina vänner.":"Du gömmer dig. Värden startar rundan när alla är redo.");box(c,90,264,1100,320,0xe01a3038,24);text(c,"RUMMETS ADRESS",125,307,17,MINT,false);bold(c,address,125,352,32,WHITE);text(c,"Bana: "+(map==0?"Skogsgläntan":"Gamla stan"),125,404,23,WHITE,false);int total=lan==null?1:lan.peers().length+1;text(c,lan!=null&&lan.hosting?total+" av 6 spelare anslutna  ·  "+(6-total)+" datorer":"Ansluten som gömmare "+localId,125,443,23,MUTED,false);if(lan!=null&&lan.hosting)button(c,"Starta rundan",125,483,480,64,true,()->start(0));else text(c,"Väntar på start…",125,524,26,MINT,false);}
    private void drawRules(Canvas c){heading(c,"Ett gömställe. Ett försprång.","Lär dig grunderna på en minut.");String[] titles={"01   Göm dig","02   Läs terrängen","03   Vänd på jakten"};String[][] lines={{"Du har 20 sekunder innan sökaren släpps.","Dra styrspaken för att röra dig.","Överlev sökfasen för att vinna."},{"Träd, stenar och hus blockerar sikten.","Sökaren fångar genom att komma nära.","Dra dig undan när sökaren närmar sig."},{"Spurt ger fart men lämnar synliga spår.","Sökarens puls visar riktningar till gömmare.","Förmågorna behöver tid att laddas om."}};for(int i=0;i<3;i++){float x=80+i*410;box(c,x,270,385,265,0xe01a3038,22);bold(c,titles[i],x+22,318,26,i==0?MINT:WHITE);for(int j=0;j<3;j++)text(c,lines[i][j],x+22,369+j*41,17,MUTED,false);}text(c,"Fångad? Följ resten av jakten. I träning kan du pausa när som helst.",90,587,23,WHITE,false);button(c,"Jag är redo",90,620,300,60,true,()->screen="menu");}
    private void drawSettings(Canvas c){heading(c,"Gör dig hemmastadd","Inställningarna sparas på den här telefonen.");button(c,"Ljud: "+(soundOn?"på":"av"),90,280,510,72,soundOn,()->{soundOn=!soundOn;sound.enabled=soundOn;prefs.edit().putBoolean("sound",soundOn).apply();});button(c,"Vibration: "+(vibrate?"på":"av"),90,370,510,72,vibrate,()->{vibrate=!vibrate;prefs.edit().putBoolean("vibrate",vibrate).apply();});box(c,650,280,540,250,0xe01a3038,22);bold(c,rank(),680,331,27,MINT);text(c,xp+" erfarenhet  ·  "+wins+" segrar",680,382,24,WHITE,false);text(c,"Seger +100  /  Spelad runda +35",680,430,21,MUTED,false);text(c,"Ranger är kosmetiska. Alla spelar på lika villkor.",680,480,18,MUTED,false);text(c,"Skymning 1.0  ·  Egen grafik och syntetiserade ljud",90,600,21,MUTED,false);}
    private float worldScale(){return 0.92f;}
    private float cameraX(){return Math.max(640/worldScale(),Math.min(Game.WIDTH-640/worldScale(),camX));}
    private float cameraY(){return Math.max(300/worldScale(),Math.min(Game.HEIGHT-300/worldScale(),camY));}
    private void drawGame(Canvas c){
        c.save();c.clipRect(0,92,1280,720);float s=worldScale(),cx=cameraX(),cy=cameraY();c.translate(640-cx*s,405-cy*s);c.scale(s,s);drawWorld(c);
        int viewer=game.players[localId].caught?0:localId;
        for(Game.Player a:game.players){if(a.caught)continue;if(a.id==localId||game.visible(viewer,a.id)||game.phase==2)drawPlayer(c,a,a.id==localId);}
        if(localId==0&&game.phase==1){Game.Player me=game.players[0];color(0);p.setShader(new RadialGradient(me.x,me.y,390,new int[]{0x00101f29,0x00101f29,0xb0101f29},new float[]{0,.65f,1},Shader.TileMode.CLAMP));c.drawRect(0,0,1600,1000,p);p.setShader(null);drawPlayer(c,me,true);if(game.pulse>0){color(0x889fe7c8);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(3);c.drawCircle(me.x,me.y,(2.2f-game.pulse)*500,p);p.setStyle(Paint.Style.FILL);for(int i=1;i<6;i++)if(!game.players[i].caught){Game.Player a=game.players[i];double angle=Math.atan2(a.y-me.y,a.x-me.x);float xx=me.x+(float)Math.cos(angle)*130,yy=me.y+(float)Math.sin(angle)*130;circle(c,xx,yy,8,PEACH);}}}
        c.restore();
        box(c,0,0,1280,94,INK,0);text(c,"SKYMNING",32,38,16,MINT,false);bold(c,localId==0?"Du letar":"Du gömmer dig",30,72,27,WHITE);
        int time=(int)Math.ceil(game.time);String phase=game.phase==0?"GÖM DIG":game.phase==1?"JAKTEN PÅGÅR":"RUNDAN ÄR SLUT";text(c,phase,640,31,16,MUTED,true);text(c,String.format(Locale.ROOT,"%01d:%02d",time/60,time%60),640,75,38,game.time<15?PEACH:WHITE,true);
        int free=0;for(int i=1;i<6;i++)if(!game.players[i].caught)free++;
        text(c,free+" / 5 gömda",950,57,24,WHITE,true);button(c,"Ⅱ",1150,20,90,56,false,()->{paused=true;resetInput();});
        minimap(c);
        if(game.phase!=2&&!paused){
            if(game.players[localId].caught){box(c,330,605,620,65,0xe0101f29,18);text(c,"Hittad! Du följer sökaren tills rundan är slut.",640,645,23,PEACH,true);}
            else{controls(c);String hint=game.phase==0?(localId==0?"Ge dem ett försprång. Jakten börjar snart.":"Hitta skydd bakom träd, stenar eller hus."):(localId==0?"Följ pulsen. Kom nära för att fånga.":"Bryt sikten. Överlev tills tiden är ute.");box(c,300,105,680,42,0xd0101f29,14);text(c,hint,640,133,20,WHITE,true);if(game.phase==0&&localId==0){box(c,407,275,466,150,0xe0101f29,24);text(c,"Lyssna till skymningen",640,326,28,MINT,true);text(c,"Snart är det din tur att leta.",640,371,23,WHITE,true);}}
        }
        if(game.phase==2)drawResult(c);else if(paused)drawPause(c);
    }
    private void drawWorld(Canvas c){
        box(c,-1000,-1000,3600,3000,0xff10292d,0);box(c,0,0,1600,1000,game.map==0?0xff36594a:0xff777e78,8);
        if(game.map==0){for(int i=0;i<3;i++){color(0xff697456);p.setStrokeWidth(50);Path path=new Path();path.moveTo(-30,250+i*260);path.cubicTo(420,110+i*300,960,450+i*100,1640,190+i*340);p.setStyle(Paint.Style.STROKE);c.drawPath(path,p);p.setStyle(Paint.Style.FILL);}for(int i=0;i<200;i++){float x=(i*137+31)%1590,y=(i*223+13)%990;line(c,x,y,x+3,y-6,i%3==0?0xff739364:0xff426b50,2);}}
        else{for(int x=0;x<1600;x+=80)line(c,x,0,x,1000,0xff818982,1);for(int y=0;y<1000;y+=80)line(c,0,y,1600,y,0xff818982,1);for(int y:new int[]{360,700}){line(c,0,y,1600,y,0xffa6ad9f,5);line(c,0,y+40,1600,y+40,0xff5d6965,2);}circle(c,815,500,72,0xffa8b7a4);circle(c,815,500,49,0xff5a8c8e);circle(c,815,500,31,0xff88b7b0);circle(c,815,500,10,0xffd0d7bd);}
        for(Game.Block b:game.blocks){box(c,b.x+7,b.y+10,b.w,b.h,0x500b1c22,10);if(b.kind==2){box(c,b.x,b.y,b.w,b.h,0xffc4b498,7);box(c,b.x+7,b.y+7,b.w-14,b.h-28,((int)b.x/40)%2==0?0xff8e685f:0xff617d83,5);line(c,b.x+12,b.y+b.h*.45f,b.x+b.w-12,b.y+b.h*.45f,0x40502e2c,3);for(float x=b.x+20;x<b.x+b.w-15;x+=38)box(c,x,b.y+b.h-17,16,10,0xfff4dcac,2);box(c,b.x+b.w*.55f,b.y+15,15,22,0xffb2a08d,2);}
            else if(b.kind==1){box(c,b.x,b.y,b.w,b.h,0xff7a8b80,17);box(c,b.x+8,b.y+5,b.w-18,b.h-19,0xffa0aa91,16);line(c,b.x+20,b.y+10,b.x+b.w*.6f,b.y+b.h*.5f,0xff788d7e,4);}
            else{box(c,b.x,b.y,b.w,b.h,0xff23483b,14);for(float x=b.x+22;x<b.x+b.w;x+=38)for(float y=b.y+22;y<b.y+b.h;y+=36){circle(c,x+3,y+4,26,0xff23483b);circle(c,x,y,23,b.kind==3?0xff69895a:0xff4a7756);circle(c,x-6,y-7,12,0xff56835e);}}}
        color(0xff9caf81);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(7);c.drawRect(4,4,1596,996,p);p.setStyle(Paint.Style.FILL);
    }
    private void drawPlayer(Canvas c,Game.Player a,boolean own){
        c.save();if(renderReady)c.translate(renderX[a.id]-a.x,renderY[a.id]-a.y);
        float bob=(float)Math.sin(clock*15+a.id)*Math.min(2,(Math.abs(a.vx)+Math.abs(a.vy))/100);
        if(a.trail>0){for(int i=1;i<5;i++)circle(c,a.x-a.vx*.045f*i,a.y-a.vy*.045f*i,10-i,0x70ffcf9e);}
        circle(c,a.x,a.y+10,19,0x50081920);if(own){color(0xffeff4e8);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2.5f);c.drawCircle(a.x,a.y,26,p);p.setStyle(Paint.Style.FILL);}
        circle(c,a.x,a.y+bob,18,colors[a.id]);circle(c,a.x-5,a.y-6+bob,9,0x40ffffff);float dx=0,dy=1;float speed=(float)Math.hypot(a.vx,a.vy);if(speed>3){dx=a.vx/speed;dy=a.vy/speed;}circle(c,a.x+dx*10-dy*5,a.y+dy*10+dx*5+bob,2.6f,INK);circle(c,a.x+dx*10+dy*5,a.y+dy*10-dx*5+bob,2.6f,INK);
        if(a.id==0){box(c,a.x-15,a.y-24,30,8,0xfff7cf91,3);}else if(own){text(c,"DU",a.x,a.y-35,13,WHITE,true);}
        c.restore();
    }
    private void minimap(Canvas c){float x=1040,y=112,w=200,h=125;box(c,x-5,y-5,w+10,h+10,0xcc101f29,10);box(c,x,y,w,h,0xff344d46,5);for(Game.Block b:game.blocks)box(c,x+b.x/8,y+b.y/8,b.w/8,b.h/8,0xff698174,2);Game.Player me=game.players[localId];circle(c,x+me.x/8,y+me.y/8,4,colors[localId]);text(c,game.map==0?"SKOGSGLÄNTAN":"GAMLA STAN",1140,259,13,MUTED,true);}
    private void controls(Canvas c){circle(c,126,583,86,0x99101f29);circle(c,126,583,62,0x443e6969);circle(c,126+stickX*48,583+stickY*48,33,0xc09fe7c8);text(c,"RÖRELSE",126,699,13,MUTED,true);Game.Player me=game.players[localId];boolean ready=me.cooldown<=0&&(localId!=0||game.phase==1);circle(c,1148,583,71,ready?MINT:0xff304a52);text(c,ready?(localId==0?"PULS":"SPURT"):""+(int)Math.ceil(me.cooldown),1148,591,24,ready?INK:MUTED,true);text(c,localId==0?"AVSLÖJA RIKTNING":"FART MED SPÅR",1148,683,13,MUTED,true);}
    private void dim(Canvas c){box(c,0,0,1280,720,0xcc091720,0);}
    private void drawPause(Canvas c){dim(c);box(c,390,160,500,400,PANEL,26);text(c,"En stunds andrum",640,220,34,WHITE,true);text(c,lan==null?"Rundan är pausad.":"Wi-Fi-rundan fortsätter i bakgrunden.",640,266,21,MUTED,true);button(c,"Fortsätt",430,302,420,66,true,()->paused=false);button(c,"Så spelar du",430,384,420,60,false,()->{toast(localId==0?"Rör dig nära för att fånga. Puls visar riktningar.":"Håll dig bakom hinder. Spurt ger fart och synliga spår.");paused=false;});button(c,"Lämna rundan",430,460,420,60,false,()->{leave();game=null;screen="menu";paused=false;});}
    private void drawResult(Canvas c){dim(c);boolean won=localId==0?game.seekerWon:!game.players[localId].caught;box(c,340,115,600,490,PANEL,28);text(c,won?"VACKERT SPELAT":"EN NY CHANS VÄNTAR",640,169,17,MINT,true);text(c,won?(localId==0?"Alla hittade!":"Du förblev gömd!"):(localId==0?"Någon kom undan":"Du blev hittad"),640,230,40,WHITE,true);text(c,won?"Skymningen var på din sida.":"Nästa gömställe kan bli ditt bästa.",640,278,23,MUTED,true);text(c,(won?"+100":"+35")+" erfarenhet  ·  "+rank(),640,337,23,MINT,true);if(lan==null||lan.hosting)button(c,"En runda till",390,382,500,67,true,()->start(localId));else text(c,"Väntar på att värden startar nästa runda…",640,420,21,WHITE,true);button(c,"Till huvudmenyn",390,468,500,62,false,()->{leave();game=null;screen="menu";});}
    @Override public boolean onTouchEvent(android.view.MotionEvent e){
        float x=(e.getX(e.getActionIndex())-offsetX)/uiScale,y=(e.getY(e.getActionIndex())-offsetY)/uiScale;int id=e.getPointerId(e.getActionIndex()),action=e.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN||action==MotionEvent.ACTION_POINTER_DOWN){
            if(screen.equals("game")&&game!=null&&game.phase!=2&&!paused&&!game.players[localId].caught){if(x<280&&y>410&&joystick==-1){joystick=id;stick(x,y);return true;}if(x>1030&&y>480){other=id;if(game.players[localId].cooldown<=0){ability=true;sound.play(1);buzz();}return true;}}
            for(int i=buttons.size()-1;i>=0;i--)if(buttons.get(i).r.contains(x,y)){buttons.get(i).run.run();performClick();return true;}
        }else if(action==MotionEvent.ACTION_MOVE){int ix=e.findPointerIndex(joystick);if(ix>=0)stick((e.getX(ix)-offsetX)/uiScale,(e.getY(ix)-offsetY)/uiScale);}
        else if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_POINTER_UP){if(id==joystick){joystick=-1;moveX=moveY=stickX=stickY=0;}if(id==other)other=-1;}
        else if(action==MotionEvent.ACTION_CANCEL)resetInput();return true;
    }
    private void stick(float x,float y){stickX=(x-126)/65;stickY=(y-583)/65;float len=(float)Math.hypot(stickX,stickY);if(len>1){stickX/=len;stickY/=len;}moveX=Math.abs(stickX)<.08?0:stickX;moveY=Math.abs(stickY)<.08?0:stickY;}
    @Override public boolean performClick(){super.performClick();return true;}
}
