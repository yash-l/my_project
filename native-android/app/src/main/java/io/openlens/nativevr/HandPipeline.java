package io.openlens.nativevr;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.Image;
import android.os.SystemClock;
import android.util.Log;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Native single-hand inference. ARCore owns the camera, so no conflicting Camera2 session. */
public final class HandPipeline {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private volatile float[] coordinates;
    private volatile long seenAt;
    private volatile boolean pinch;
    private volatile boolean ready;
    private volatile boolean stopped;
    private HandLandmarker detector;
    private int closeVotes;
    private int openVotes;

    public HandPipeline(Context app) {
        worker.execute(() -> {
            try {
                BaseOptions base = BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build();
                HandLandmarker.HandLandmarkerOptions opts = HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(base).setRunningMode(RunningMode.IMAGE).setNumHands(1)
                    .setMinHandDetectionConfidence(0.55f).setMinTrackingConfidence(0.55f)
                    .setMinHandPresenceConfidence(0.55f).build();
                detector = HandLandmarker.createFromOptions(app, opts);
                ready = true;
                Log.i("OpenLensHand", "MediaPipe single-hand model loaded");
            } catch (Exception e) { Log.e("OpenLensHand", "Unable to initialize hand detector", e); }
        });
    }
    public void submit(Image image) {
        if (!ready || stopped || !busy.compareAndSet(false,true)) { image.close(); return; }
        worker.execute(() -> {
            try {
                Bitmap b = fromYuv(image, 256, 256);
                HandLandmarkerResult result = detector.detect(new BitmapImageBuilder(b).build());
                if (result.landmarks().isEmpty()) {
                    coordinates = null;
                    closeVotes = 0; openVotes = 0; pinch = false;
                } else {
                    List<NormalizedLandmark> lm = result.landmarks().get(0);
                    float[] next = new float[42];
                    for (int i=0;i<21;i++) { next[i*2] = lm.get(i).x(); next[i*2+1] = lm.get(i).y(); }
                    coordinates = next; seenAt = SystemClock.uptimeMillis();
                    float gap = d(next, 4, 8);
                    float palm = Math.max(0.03f, d(next, 5, 17));
                    boolean edge = false;
                    for(int i=0;i<21;i++) {
                        float x=next[2*i],y=next[2*i+1];
                        if(x<0.025f || x>0.975f || y<0.025f || y>0.975f) edge=true;
                    }
                    if (!pinch) { closeVotes = (!edge && gap/palm<0.35f) ? closeVotes+1 : 0;
                        if (closeVotes>=2) { pinch = true; openVotes=0; }
                    } else {
                        openVotes = gap/palm>0.55f ? openVotes+1 : 0;
                        if(openVotes>=2) { pinch=false; closeVotes=0; }
                    }
                }
                b.recycle();
            } catch(Exception e) { Log.w("OpenLensHand","Frame inference failure",e); }
            finally { image.close(); busy.set(false); }
        });
    }
    private static float d(float[] xy, int a, int b) {
        return (float)Math.hypot(xy[2*a]-xy[2*b], xy[2*a+1]-xy[2*b+1]);
    }
    public float[] landmarks() { return SystemClock.uptimeMillis()-seenAt < 350 ? coordinates : null; }
    public boolean isPinching() { return landmarks()!=null && pinch; }
    public boolean isReady() { return ready; }
    public void close() {
        stopped=true;
        worker.execute(() -> { if(detector != null) detector.close(); detector=null; ready=false; });
        worker.shutdown();
    }
    private static int unsigned(ByteBuffer b, int i){ return b.get(i)&255; }
    private static int clamp(int x){return x<0?0:x>255?255:x;}
    private static Bitmap fromYuv(Image image,int width,int height){
        Image.Plane[] ps=image.getPlanes();
        ByteBuffer yb=ps[0].getBuffer(),ub=ps[1].getBuffer(),vb=ps[2].getBuffer();
        int iw=image.getWidth(),ih=image.getHeight();
        int[] px=new int[width*height];
        for(int y=0;y<height;y++){
            int sy=y*ih/height, uvY=sy/2;
            for(int x=0;x<width;x++){
                int sx=x*iw/width,uvX=sx/2;
                int Y=unsigned(yb,sy*ps[0].getRowStride()+sx*ps[0].getPixelStride());
                int U=unsigned(ub,uvY*ps[1].getRowStride()+uvX*ps[1].getPixelStride());
                int V=unsigned(vb,uvY*ps[2].getRowStride()+uvX*ps[2].getPixelStride());
                int c=Math.max(0,Y-16),d=U-128,e=V-128;
                int r=clamp((298*c+409*e+128)>>8),g=clamp((298*c-100*d-208*e+128)>>8),b=clamp((298*c+516*d+128)>>8);
                px[y*width+x]=0xff000000|(r<<16)|(g<<8)|b;
            }
        }
        Bitmap bmp=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);bmp.setPixels(px,0,width,0,0,width,height);
        return bmp;
    }
}
