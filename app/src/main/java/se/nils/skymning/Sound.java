package se.nils.skymning;

import android.media.AudioAttributes;
import android.media.SoundPool;
import android.content.Context;
import java.io.*;

/** Short original synthesized chimes, loaded once; never synthesize on render thread. */
final class Sound {
    private final SoundPool pool;
    private final int[] sounds = new int[4];
    volatile boolean enabled = true;
    Sound(Context context) {
        pool = new SoundPool.Builder().setMaxStreams(3).setAudioAttributes(new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        for (int i=0;i<4;i++) {
            try {
                File file = new File(context.getCacheDir(),"chime"+i+".wav");
                int n = i==3 ? 11025 : 4410;
                try (DataOutputStream out=new DataOutputStream(new FileOutputStream(file))) {
                    out.writeBytes("RIFF"); le32(out,36+n*2);out.writeBytes("WAVEfmt ");le32(out,16);le16(out,1);le16(out,1);le32(out,22050);le32(out,44100);le16(out,2);le16(out,16);out.writeBytes("data");le32(out,n*2);
                    for(int s=0;s<n;s++) { double t=s/22050.0; double f=new double[]{440,660,330,523.25}[i]; if(i==3 && s>n/2)f*=1.5; double env=Math.sin(Math.PI*s/n)*Math.exp(-4.0*s/n); le16(out,(int)(Math.sin(t*f*Math.PI*2)*env*6500)); }
                }
                sounds[i]=pool.load(file.getAbsolutePath(),1);
            } catch(IOException ignored) { /* Sound is optional; game remains playable. */ }
        }
    }
    private static void le16(DataOutputStream o,int n)throws IOException {o.writeByte(n);o.writeByte(n>>8);}
    private static void le32(DataOutputStream o,int n)throws IOException {le16(o,n);le16(o,n>>16);}
    void play(int id) { if(enabled && id>=0 && id<sounds.length)pool.play(sounds[id],.45f,.45f,1,0,1); }
    void close(){pool.release();}
}
