package io.openlens.nativevr;

import android.content.Context;
import android.media.Image;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import com.google.ar.core.Frame;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingState;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Own custom stereo pipeline: per-eye textures, radial lens warp, and optional late rotational warp. */
public final class StereoView extends GLSurfaceView implements GLSurfaceView.Renderer {
    private static final String TAG = "OpenLensStereo";
    private final HandPipeline hands;
    private volatile Session session;
    private Session boundSession;
    private volatile float[] imu = {0f,0f,0f,1f};
    private volatile int thermal = 0;
    private volatile boolean timewarp = true;
    private int width, height, eyeW, eyeH;
    private int program, compositor, handProgram, texOes, fbo;
    private final int[] tex = new int[2];
    private FloatBuffer quad, joints;
    private float[] lastArPosition={0f,0f,0f};
    private long lastHandNs, lastRenderNs;
    private int frameCount;
    private boolean arTracking;

    public StereoView(Context context, HandPipeline pipeline) {
        super(context); hands=pipeline;
        setEGLContextClientVersion(2);
        setEGLConfigChooser(8,8,8,8,16,0);
        setRenderer(this);
        setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
    }
    public void setSession(Session s) { session=s; }
    public void updateImu(float x, float y, float z, float w) { imu=new float[]{x,y,z,w}; }
    public void setThermal(int value) { thermal=value; }
    public void toggleTimewarp() { timewarp = !timewarp; Log.i(TAG,"Timewarp enabled="+timewarp); }
    public void activateGaze() { Log.i(TAG,"Gaze trigger, pinch="+hands.isPinching()); }

    private static final String VERT = """
        attribute vec2 aP;
        varying vec2 vUV;
        void main(){vUV=aP*0.5+0.5;gl_Position=vec4(aP,0.0,1.0);}
        """;
    private static final String SCENE = """
        precision highp float;
        varying vec2 vUV;
        uniform mat3 uRotation;
        uniform vec3 uEye;
        uniform vec3 uHead;
        float hitSphere(vec3 ro,vec3 rd,vec3 p,float radius) {
           vec3 oc=ro-p;float b=dot(oc,rd);float c=dot(oc,oc)-radius*radius;
           float h=b*b-c;if(h<0.0)return 1e5;
           float t=-b-sqrt(h);return t>0.02?t:1e5;
        }
        void main(){
          vec2 xy=(vUV*2.0-1.0)*vec2(0.85,0.83);
          vec3 dir=normalize(uRotation*vec3(xy,-1.0));
          vec3 ro=uHead+uRotation*uEye;
          vec3 col=mix(vec3(0.028,0.044,0.085),vec3(0.10,0.13,0.23),clamp(0.5+dir.y*1.2,0.0,1.0));
          float floorT=(-1.5-ro.y)/dir.y;
          if(floorT>0.01 && floorT<100.0){
             vec3 p=ro+floorT*dir;vec2 grid=abs(fract(p.xz*1.4+0.5)-0.5);
             float line=step(min(grid.x,grid.y),0.016);
             col=mix(vec3(0.025,0.035,0.065),vec3(0.06,0.17,0.24),line);
             float fade=clamp(floorT/22.0,0.0,1.0);
             col=mix(col,vec3(0.028,0.044,0.085),fade);
          }
          float t=1e5;vec3 center=vec3(0.0,0.0,-2.4);vec3 surface=vec3(0.0);
          float s=hitSphere(ro,dir,vec3(0.0,0.0,-2.4),0.40);
          if(s<t){t=s;center=vec3(0.0,0.0,-2.4);surface=vec3(0.10,0.72,0.97);}
          s=hitSphere(ro,dir,vec3(1.25,-0.35,-3.4),0.45);
          if(s<t){t=s;center=vec3(1.25,-0.35,-3.4);surface=vec3(0.87,0.28,0.88);}
          s=hitSphere(ro,dir,vec3(-1.55,0.42,-3.0),0.34);
          if(s<t){t=s;center=vec3(-1.55,0.42,-3.0);surface=vec3(0.99,0.60,0.14);}
          if(t<1e4) {
            vec3 n=normalize(ro+dir*t-center);
            float light=max(0.18,dot(n,normalize(vec3(0.4,0.8,0.3))));
            col=surface*light+surface*0.08;
          }
          gl_FragColor=vec4(col,1.0);
        }
        """;
    // Screen-to-optics ray and later head ray -> corresponding direction in the older render.
    // Outside the old render is black, never stretched/smeared edge content.
    private static final String COMPOSITE = """
        precision highp float;
        varying vec2 vUV;
        uniform sampler2D uEyeTexture;
        uniform mat3 uDelta;
        uniform float uDistortion;
        void main(){
          vec2 p=vUV*2.0-1.0;
          float r2=dot(p,p);
          p*=1.0+uDistortion*(0.24*r2+0.05*r2*r2);
          vec3 ray=normalize(vec3(p*vec2(0.85,0.83),-1.0));
          vec3 oldRay=uDelta*ray;
          if(oldRay.z>=-0.001){gl_FragColor=vec4(0.0,0.0,0.0,1.0);return;}
          vec2 uv=vec2(oldRay.x/(-oldRay.z)/0.85,oldRay.y/(-oldRay.z)/0.83)*0.5+0.5;
          if(uv.x<0.0||uv.x>1.0||uv.y<0.0||uv.y>1.0)
            gl_FragColor=vec4(0.0,0.0,0.0,1.0);
          else gl_FragColor=texture2D(uEyeTexture,uv);
        }
        """;
    private static final String POINT_VERT = """
        attribute vec2 aJoint;
        uniform float uShift;
        void main(){gl_Position=vec4(aJoint.x+uShift,aJoint.y,0.0,1.0);gl_PointSize=13.0;}
        """;
    private static final String POINT_FRAG = """
        precision mediump float;
        uniform float uPinch;
        void main(){
          if(length(gl_PointCoord-vec2(0.5))>0.5) discard;
          gl_FragColor=vec4(mix(vec3(0.18,0.92,0.83),vec3(1.0,0.64,0.13),uPinch),0.95);
        }
        """;
    private static int shader(int type,String src){
        int s=GLES20.glCreateShader(type);GLES20.glShaderSource(s,src);GLES20.glCompileShader(s);
        int[] ok=new int[1];GLES20.glGetShaderiv(s,GLES20.GL_COMPILE_STATUS,ok,0);
        if(ok[0]==0)throw new IllegalStateException("GL shader: "+GLES20.glGetShaderInfoLog(s));
        return s;
    }
    private static int link(String vertex,String fragment){
        int p=GLES20.glCreateProgram();
        int v=shader(GLES20.GL_VERTEX_SHADER,vertex),f=shader(GLES20.GL_FRAGMENT_SHADER,fragment);
        GLES20.glAttachShader(p,v);GLES20.glAttachShader(p,f);GLES20.glLinkProgram(p);
        int[] ok=new int[1];GLES20.glGetProgramiv(p,GLES20.GL_LINK_STATUS,ok,0);
        if(ok[0]==0)throw new IllegalStateException("GL link: "+GLES20.glGetProgramInfoLog(p));
        GLES20.glDeleteShader(v);GLES20.glDeleteShader(f);return p;
    }
    private static FloatBuffer buffer(float[] data){
        FloatBuffer b=ByteBuffer.allocateDirect(data.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(data).position(0);return b;
    }
    @Override public void onSurfaceCreated(GL10 unused,EGLConfig config) {
        program=link(VERT,SCENE);
        compositor=link(VERT,COMPOSITE);
        handProgram=link(POINT_VERT,POINT_FRAG);
        quad=buffer(new float[]{-1,-1,1,-1,-1,1,1,1});
        int[] ids=new int[1];GLES20.glGenTextures(1,ids,0);texOes=ids[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,texOes);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
        GLES20.glGenFramebuffers(1,ids,0);fbo=ids[0];
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        boundSession=null;
    }
    @Override public void onSurfaceChanged(GL10 unused,int w,int h) {
        width=w;height=h;
        allocateEyes(thermal>=PowerManager.THERMAL_STATUS_MODERATE?0.55f:0.75f);
    }
    private float currentScale=0.75f;
    private void allocateEyes(float scale) {
        if(width<2 || height<2)return;
        currentScale=scale;
        eyeW=Math.max(128,Math.round(width*0.5f*scale));
        eyeH=Math.max(128,Math.round(height*scale));
        if(tex[0]!=0)GLES20.glDeleteTextures(2,tex,0);
        GLES20.glGenTextures(2,tex,0);
        for(int t:tex){
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,t);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,eyeW,eyeH,0,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,null);
        }
    }
    private static void quad(int program,int attr,FloatBuffer data){
        GLES20.glEnableVertexAttribArray(attr);
        data.position(0);GLES20.glVertexAttribPointer(attr,2,GLES20.GL_FLOAT,false,0,data);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
        GLES20.glDisableVertexAttribArray(attr);
    }
    @Override public void onDrawFrame(GL10 unused) {
        long now=SystemClock.elapsedRealtimeNanos();
        long budget=16_666_667L;
        if(now-lastRenderNs<budget-800_000L) {
            long ms=(budget-(now-lastRenderNs)-500_000L)/1_000_000L;
            if(ms>0)SystemClock.sleep(ms);
        }
        lastRenderNs=SystemClock.elapsedRealtimeNanos();
        if(width<2 || height<2) return;
        float targetScale=thermal>=PowerManager.THERMAL_STATUS_MODERATE?0.55f:0.75f;
        if(Math.abs(currentScale-targetScale)>0.1f)allocateEyes(targetScale);
        float[] early=imu.clone(), q=early.clone();
        float[] position={0f,0f,0f};
        arTracking=false;
        Session active=session;
        if(active!=null && thermal<PowerManager.THERMAL_STATUS_SEVERE){
            try {
                if(boundSession!=active){active.setCameraTextureName(texOes);boundSession=active;}
                active.setDisplayGeometry(0,width,height);
                Frame frame=active.update();
                if(frame.getCamera().getTrackingState()==TrackingState.TRACKING){
                    Pose pose=frame.getCamera().getPose();
                    q=pose.getRotationQuaternion();
                    position=pose.getTranslation();
                    lastArPosition=position.clone();
                    arTracking=true;
                }
                if(hands.isReady() && lastRenderNs-lastHandNs>95_000_000L){
                    lastHandNs=lastRenderNs;
                    try {Image im=frame.acquireCameraImage();hands.submit(im);}
                    catch(Exception ignored){ }
                }
            } catch(Exception e) {
                if(frameCount%300==0)Log.w(TAG,"AR camera not ready",e);
            }
        }
        for(int eye=0;eye<2;eye++){
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,fbo);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,GLES20.GL_COLOR_ATTACHMENT0,GLES20.GL_TEXTURE_2D,tex[eye],0);
            GLES20.glViewport(0,0,eyeW,eyeH);
            GLES20.glClearColor(0,0,0,1);GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glUseProgram(program);
            GLES20.glUniformMatrix3fv(GLES20.glGetUniformLocation(program,"uRotation"),1,false,quatMat(q),0);
            GLES20.glUniform3f(GLES20.glGetUniformLocation(program,"uEye"),eye==0?-0.0315f:0.0315f,0,0);
            GLES20.glUniform3fv(GLES20.glGetUniformLocation(program,"uHead"),1,position,0);
            quad(program,GLES20.glGetAttribLocation(program,"aP"),quad);
            renderHand();
        }
        // Latest sample is read AFTER the scene draws, before lens compositor passes.
        float[] late=imu.clone();
        float[] delta=timewarp?multiply(inverse(early),late):new float[]{0,0,0,1};
        float[] warp=quatMat(delta);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,0);
        GLES20.glClearColor(0,0,0,1);GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(compositor);
        GLES20.glUniformMatrix3fv(GLES20.glGetUniformLocation(compositor,"uDelta"),1,false,warp,0);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compositor,"uDistortion"),1f);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(compositor,"uEyeTexture"),0);
        for(int eye=0;eye<2;eye++){
            GLES20.glViewport(eye*width/2,0,width/2,height);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,tex[eye]);
            quad(compositor,GLES20.glGetAttribLocation(compositor,"aP"),quad);
        }
        frameCount++;
        if(frameCount%300==0) Log.i(TAG,"6DoF="+arTracking+" timewarp="+timewarp+" eye="+eyeW+"x"+eyeH+" thermal="+thermal+" hand="+(hands.landmarks()!=null));
    }
    private void renderHand(){
        float[] landmarks=hands.landmarks();
        if(landmarks==null)return;
        float[] points=new float[42];
        for(int i=0;i<21;i++){
            // ARCore image has sensor axes; explicit device calibration remains required.
            points[2*i]=landmarks[2*i]*2f-1f;
            points[2*i+1]=1f-landmarks[2*i+1]*2f;
        }
        joints=buffer(points);
        GLES20.glUseProgram(handProgram);
        int attr=GLES20.glGetAttribLocation(handProgram,"aJoint");
        GLES20.glUniform1f(GLES20.glGetUniformLocation(handProgram,"uPinch"),hands.isPinching()?1f:0f);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(handProgram,"uShift"),0f);
        GLES20.glEnableVertexAttribArray(attr);
        GLES20.glVertexAttribPointer(attr,2,GLES20.GL_FLOAT,false,0,joints);
        GLES20.glDrawArrays(GLES20.GL_POINTS,0,21);
        GLES20.glDisableVertexAttribArray(attr);
    }
    // Internal quaternion order is x,y,z,w. Column-major matrix as expected by GLSL.
    private static float[] quatMat(float[] q) {
        float x=q[0],y=q[1],z=q[2],w=q[3];
        float xx=x*x,yy=y*y,zz=z*z,xy=x*y,xz=x*z,yz=y*z,xw=x*w,yw=y*w,zw=z*w;
        return new float[]{1-2*(yy+zz),2*(xy+zw),2*(xz-yw),
            2*(xy-zw),1-2*(xx+zz),2*(yz+xw),
            2*(xz+yw),2*(yz-xw),1-2*(xx+yy)};
    }
    private static float[] inverse(float[] q) {return new float[]{-q[0],-q[1],-q[2],q[3]};}
    private static float[] multiply(float[] a,float[] b) {
        float ax=a[0],ay=a[1],az=a[2],aw=a[3],bx=b[0],by=b[1],bz=b[2],bw=b[3];
        return new float[]{aw*bx+ax*bw+ay*bz-az*by,
            aw*by-ax*bz+ay*bw+az*bx, aw*bz+ax*by-ay*bx+az*bw,
            aw*bw-ax*bx-ay*by-az*bz};
    }
}
