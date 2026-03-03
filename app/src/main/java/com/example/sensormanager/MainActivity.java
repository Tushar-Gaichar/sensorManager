package com.example.sensormanager;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements SensorEventListener {

    // --------------------------------------------------------
    // UI
    // --------------------------------------------------------
    private EditText  ipInput;
    private EditText  portInput;
    private Button    connectButton;
    private TextView  statusText;
    private TextView  angleText;

    // --------------------------------------------------------
    // SENSORS
    // --------------------------------------------------------
    private SensorManager sensorManager;
    private Sensor gyroscope;
    private Sensor rotationVector;

    private float gyroZ     = 0f;
    private float orientYaw = 0f;

    private static final float RAD_TO_DEG = 57.2957795f;

    // --------------------------------------------------------
    // NETWORKING
    // --------------------------------------------------------
    private Socket      socket;
    private PrintWriter out;
    private boolean     isConnected = false;
    private Thread      networkThread;

    // Dedicated executor to handle high-frequency sensor packets without lag
    private ExecutorService networkExecutor = Executors.newSingleThreadExecutor();

    // For posting UI updates from background thread
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    // --------------------------------------------------------
    // PREFS
    // --------------------------------------------------------
    private static final String PREFS_NAME  = "SteeringWheelPrefs";
    private static final String PREF_IP     = "last_ip";
    private static final String PREF_PORT   = "last_port";
    private static final String DEFAULT_PORT = "5555";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ipInput       = findViewById(R.id.ipInput);
        portInput     = findViewById(R.id.portInput);
        connectButton = findViewById(R.id.connectButton);
        statusText    = findViewById(R.id.statusText);
        angleText     = findViewById(R.id.angleText);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        ipInput.setText(prefs.getString(PREF_IP, ""));
        portInput.setText(prefs.getString(PREF_PORT, DEFAULT_PORT));

        sensorManager  = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        gyroscope      = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);

        if (gyroscope == null) {
            statusText.setText("ERROR: No gyroscope found!");
            connectButton.setEnabled(false);
        }

        connectButton.setOnClickListener(v -> {
            if (!isConnected) {
                startConnection();
            } else {
                stopConnection();
            }
        });
    }

    private void startConnection() {
        String ip   = ipInput.getText().toString().trim();
        String port = portInput.getText().toString().trim();

        if (ip.isEmpty()) {
            statusText.setText("Please enter the PC IP address");
            return;
        }

        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        editor.putString(PREF_IP, ip);
        editor.putString(PREF_PORT, port);
        editor.apply();

        setUIConnecting();

        final String finalIp   = ip;
        final int    finalPort = Integer.parseInt(port.isEmpty() ? DEFAULT_PORT : port);

        networkThread = new Thread(() -> {
            try {
                socket = new Socket(finalIp, finalPort);
                socket.setTcpNoDelay(true); // Crucial for low latency
                socket.setSendBufferSize(16384);
                out = new PrintWriter(socket.getOutputStream(), false); // Manual flush for control
                isConnected = true;

                uiHandler.post(() -> {
                    setUIConnected(finalIp, finalPort);
                    registerSensors();
                });

            } catch (Exception e) {
                uiHandler.post(() -> setUIDisconnected("Failed: " + e.getMessage()));
            }
        });
        networkThread.start();
    }

    private void stopConnection() {
        unregisterSensors();
        isConnected = false;
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {}
        socket = null;
        out    = null;
        setUIDisconnected("Disconnected");
    }

    private void registerSensors() {
        // Changed to SENSOR_DELAY_FASTEST for minimum hardware latency
        sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_FASTEST);
        sensorManager.registerListener(this, rotationVector, SensorManager.SENSOR_DELAY_FASTEST);
    }

    private void unregisterSensors() {
        sensorManager.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!isConnected) return;

        if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            gyroZ = event.values[2] * RAD_TO_DEG;
        }

        if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
            float[] rotMatrix   = new float[9];
            float[] orientation = new float[3];
            SensorManager.getRotationMatrixFromVector(rotMatrix, event.values);
            SensorManager.getOrientation(rotMatrix, orientation);

            orientYaw = (float) Math.toDegrees(orientation[0]);

            // High-frequency transmission
            sendPacket(event.timestamp);

            // Update UI
            angleText.setText(String.format("Yaw: %.1f°   Gyro Z: %.1f°/s", orientYaw, gyroZ));
        }
    }

    private void sendPacket(long nanoTimestamp) {
        if (out == null || !isConnected) return;

        final long ts = nanoTimestamp / 1_000_000L;
        final float currentYaw = orientYaw;
        final float currentGyro = gyroZ;

        // Efficient String concatenation for high-frequency usage
        final String packet = "{\"orient\":" + currentYaw + ",\"gyro_z\":" + currentGyro + ",\"ts\":" + ts + "}\n";

        // Execute on persistent background thread to prevent thread-churn lag
        networkExecutor.execute(() -> {
            try {
                if (out != null) {
                    out.print(packet);
                    out.flush(); // Force immediate dispatch to PC
                }
            } catch (Exception e) {
                uiHandler.post(this::stopConnection);
            }
        });
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private void setUIConnecting() {
        connectButton.setEnabled(false);
        connectButton.setText("Connecting...");
        statusText.setText("Connecting...");
        ipInput.setEnabled(false);
        portInput.setEnabled(false);
    }

    private void setUIConnected(String ip, int port) {
        connectButton.setEnabled(true);
        connectButton.setText("Disconnect");
        statusText.setText("Connected to " + ip + ":" + port);
    }

    private void setUIDisconnected(String reason) {
        connectButton.setEnabled(true);
        connectButton.setText("Connect");
        statusText.setText(reason);
        angleText.setText("Yaw: --   Gyro Z: --");
        ipInput.setEnabled(true);
        portInput.setEnabled(true);
        isConnected = false;
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopConnection();
        networkExecutor.shutdown();
    }
}