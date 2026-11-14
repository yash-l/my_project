package io.openlens.nativevr;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.content.pm.ActivityInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Build;
import android.os.PowerManager;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;
import com.google.ar.core.Config;
import com.google.ar.core.Session;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity implements SensorEventListener {
    private static final int CAMERA_REQUEST = 47;
    private StereoView display;
    private SensorManager sensors;
    private Sensor rotationSensor;
    private PowerManager power;
    private Session arSession;
    private HandPipeline hands;
    private boolean started = false;
    private long volumeDownAt;
    private boolean holdingVolume;
    private final AtomicBoolean warned = new AtomicBoolean(false);
    private long lastThermalPoll;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        sensors = (SensorManager)getSystemService(SENSOR_SERVICE);
        rotationSensor = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        if (rotationSensor == null) rotationSensor = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        power = (PowerManager)getSystemService(POWER_SERVICE);
        hands = new HandPipeline(getApplicationContext());
        display = new StereoView(this, hands);
        setContentView(display);
        toast("Good lighting + textured surfaces help tracking. Volume: click; hold: timewarp toggle.");
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_REQUEST);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == CAMERA_REQUEST && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startAr();
        else toast("Camera permission denied: gyro-only mode.");
    }

    private void startAr() {
        if (arSession != null || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        try {
            Session session = new Session(this);
            Config config = new Config(session);
            config.setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL);
            config.setLightEstimationMode(Config.LightEstimationMode.DISABLED);
            session.configure(config);
            arSession = session;
            display.setSession(session);
            if (started) session.resume();
            toast("ARCore 6DoF enabled; keep room well lit.");
        } catch (Exception e) {
            android.util.Log.w("OpenLens", "ARCore unavailable, using gyro mode", e);
            toast("ARCore unavailable — 3DoF fallback.");
        }
    }
    @Override protected void onResume() {
        super.onResume();
        started = true;
        if (rotationSensor != null) sensors.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_FASTEST);
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startAr();
        else display.setSession(null);
        if (arSession != null) {
            try { arSession.resume(); } catch (Exception e) { android.util.Log.w("OpenLens","AR resume",e); }
        }
        display.onResume();
    }
    @Override protected void onPause() {
        started = false;
        sensors.unregisterListener(this);
        display.onPause();
        if (arSession != null) { try { arSession.pause(); } catch (Exception ignored) { } }
        super.onPause();
    }
    @Override protected void onDestroy() {
        if (arSession != null) { arSession.close(); arSession = null; }
        hands.close();
        super.onDestroy();
    }
    @Override public void onSensorChanged(SensorEvent ev) {
        if (ev.sensor.getType() == Sensor.TYPE_GAME_ROTATION_VECTOR || ev.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
            float[] q = new float[4]; // Android gives w,x,y,z.
            SensorManager.getQuaternionFromVector(q, ev.values);
            display.updateImu(q[1], q[2], q[3], q[0]);
        }
        if (System.currentTimeMillis() - lastThermalPoll > 1500) {
            lastThermalPoll = System.currentTimeMillis();
            if (Build.VERSION.SDK_INT >= 29) display.setThermal(power.getCurrentThermalStatus());
        }
    }
    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.getRepeatCount() == 0) { volumeDownAt = System.currentTimeMillis(); holdingVolume = false; }
            if (event.getRepeatCount() > 0 && !holdingVolume && System.currentTimeMillis() - volumeDownAt > 550) {
                holdingVolume = true;
                display.toggleTimewarp();
                toast("Timewarp toggled (experimental).");
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
    @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (!holdingVolume) { display.activateGaze(); toast("Gaze select"); }
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }
    private void toast(String text) { runOnUiThread(() -> Toast.makeText(this, text, Toast.LENGTH_SHORT).show()); }
}
