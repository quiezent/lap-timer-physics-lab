package app.tracklog;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import java.util.concurrent.CopyOnWriteArrayList;

/** Fake SensorManager. deliver() is called by the feeder thread; the callback then runs on the registered Handler's thread. */
final class FakeSensorManager extends SensorManager {
    static final class Reg {
        final SensorEventListener listener; final Sensor sensor; final Handler handler; final int periodUs;
        Reg(SensorEventListener l, Sensor s, Handler h, int p) { listener = l; sensor = s; handler = h; periodUs = p; }
    }

    // commas in name/vendor on purpose: Recorder.describe() must sanitise them for the CSV header
    final Sensor accel = new Sensor(Sensor.TYPE_ACCELEROMETER, "FakeAccel, LSM6 rev2", "Fake, Vendor", 78.4532f, 0.0023956299f, 5000);
    final Sensor gyro = new Sensor(Sensor.TYPE_GYROSCOPE, "FakeGyro, LSM6 rev2", "Fake, Vendor", 34.906586f, 0.0010652644f, 5000);
    final CopyOnWriteArrayList<Reg> regs = new CopyOnWriteArrayList<>();
    volatile int registerCalls, unregisterCalls;

    @Override public Sensor getDefaultSensor(int type) {
        return type == Sensor.TYPE_ACCELEROMETER ? accel : type == Sensor.TYPE_GYROSCOPE ? gyro : null;
    }

    @Override public boolean registerListener(SensorEventListener l, Sensor s, int periodUs, int latencyUs, Handler h) {
        regs.add(new Reg(l, s, h, periodUs));
        registerCalls++;
        return true;
    }

    @Override public void unregisterListener(SensorEventListener l) {
        regs.removeIf(r -> r.listener == l);
        unregisterCalls++;
    }

    void deliver(Sensor s, float x, float y, float z, long tsNs) {
        for (final Reg r : regs) {
            if (r.sensor != s) continue;
            final SensorEvent ev = new SensorEvent(3);      // fresh object per event: it crosses threads here
            ev.sensor = s; ev.values[0] = x; ev.values[1] = y; ev.values[2] = z; ev.timestamp = tsNs; ev.accuracy = 3;
            if (r.handler != null) r.handler.post(() -> r.listener.onSensorChanged(ev));
            else r.listener.onSensorChanged(ev);
        }
    }
}
