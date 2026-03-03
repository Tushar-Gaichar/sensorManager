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
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements SensorEventListener {

    // --------------------------------------------------------
    // UI — Connection Screen
    // --------------------------------------------------------
    private LinearLayout connectionScreen;
    private EditText     ipInput, portInput;
    private Button       connectButton;
    private TextView     statusText;

    // --------------------------------------------------------
    // UI — Controller Screen
    // --------------------------------------------------------
    private LinearLayout controllerScreen;
    private TextView     angleDisplay;
    private Button       setCentreButton;
    private TextView     centreStatusText;
    private Button       btnCruise;
    private Button       btnLeftIndicator;
    private Button       btnRightIndicator;
    private Button       btnLights;

    // Button state tracking (toggle)
    private boolean stateCruise        = false;
    private boolean stateLeftIndicator = false;
    private boolean stateRightIndicator= false;
    private boolean stateLights        = false;

    // --------------------------------------------------------
    // SENSORS
    // --------------------------------------------------------
    private SensorManager sensorManager;
    private Sensor        gyroscope, rotationVector;

    private float   gyroZ      = 0f;
    private float   orientYaw  = 0f;
    private float   centreOffset = 0f;   // subtracted from orientYaw before sending
    private static final float RAD_TO_DEG = 57.2957795f;

    // --------------------------------------------------------
    // NETWORKING
    // --------------------------------------------------------
    private Socket      socket;
    private PrintWriter out;
    private volatile boolean isConnected = false;

    private final BlockingQueue<String> sendQueue   = new ArrayBlockingQueue<>(1, false);
    private final ExecutorService       connectExecutor = Executors.newSingleThreadExecutor();
    private Thread sendThread;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    // --------------------------------------------------------
    // PREFS
    // --------------------------------------------------------
    private static final String PREFS_NAME   = "SteeringWheelPrefs";
    private static final String DEFAULT_PORT = "5555";

    // --------------------------------------------------------
    // LIFECYCLE
    // --------------------------------------------------------
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Keep screen on always — replaces wakelock approach
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // True fullscreen — hide status bar and navigation bar
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN          |
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION     |
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY    |
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN   |
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        );

        setContentView(R.layout.activity_main);

        // Connection screen
        connectionScreen = findViewById(R.id.connectionScreen);
        ipInput          = findViewById(R.id.ipInput);
        portInput        = findViewById(R.id.portInput);
        connectButton    = findViewById(R.id.connectButton);
        statusText       = findViewById(R.id.statusText);

        // Controller screen
        controllerScreen  = findViewById(R.id.controllerScreen);
        angleDisplay      = findViewById(R.id.angleDisplay);
        setCentreButton   = findViewById(R.id.setCentreButton);
        centreStatusText  = findViewById(R.id.centreStatusText);
        btnCruise         = findViewById(R.id.btnCruise);
        btnLeftIndicator  = findViewById(R.id.btnLeftIndicator);
        btnRightIndicator = findViewById(R.id.btnRightIndicator);
        btnLights         = findViewById(R.id.btnLights);

        // Restore saved IP/port
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        ipInput.setText(prefs.getString("last_ip", ""));
        portInput.setText(prefs.getString("last_port", DEFAULT_PORT));

        // Sensors
        sensorManager  = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        gyroscope      = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);

        if (gyroscope == null) {
            statusText.setText(R.string.error_no_gyroscope);
            connectButton.setEnabled(false);
            return;
        }

        // Start on connection screen
        showConnectionScreen();

        // ---- Button listeners ----

        connectButton.setOnClickListener(v -> {
            if (!isConnected) startConnection();
            else              stopConnection();
        });

        // Set Centre — capture current yaw as the zero point
        setCentreButton.setOnClickListener(v -> {
            centreOffset = orientYaw;
            centreStatusText.setText(getString(R.string.centre_set_at, centreOffset));
        });

        // Toggle buttons — each sends its state as a vJoy button press
        btnCruise.setOnClickListener(v -> {
            stateCruise = !stateCruise;
            btnCruise.setAlpha(stateCruise ? 1.0f : 0.5f);
            sendButtonPacket();
        });

        btnLeftIndicator.setOnClickListener(v -> {
            stateLeftIndicator = !stateLeftIndicator;
            // Indicators are mutually exclusive
            if (stateLeftIndicator) stateRightIndicator = false;
            refreshIndicatorUI();
            sendButtonPacket();
        });

        btnRightIndicator.setOnClickListener(v -> {
            stateRightIndicator = !stateRightIndicator;
            if (stateRightIndicator) stateLeftIndicator = false;
            refreshIndicatorUI();
            sendButtonPacket();
        });

        btnLights.setOnClickListener(v -> {
            stateLights = !stateLights;
            btnLights.setAlpha(stateLights ? 1.0f : 0.5f);
            sendButtonPacket();
        });
    }

    // --------------------------------------------------------
    // SCREEN SWITCHING
    // --------------------------------------------------------
    private void showConnectionScreen() {
        connectionScreen.setVisibility(View.VISIBLE);
        controllerScreen.setVisibility(View.GONE);
    }

    private void showControllerScreen() {
        connectionScreen.setVisibility(View.GONE);
        controllerScreen.setVisibility(View.VISIBLE);
        // Reset button states visually on each new connection
        resetButtonStates();
    }

    private void resetButtonStates() {
        stateCruise         = false;
        stateLeftIndicator  = false;
        stateRightIndicator = false;
        stateLights         = false;
        btnCruise.setAlpha(0.5f);
        btnLights.setAlpha(0.5f);
        refreshIndicatorUI();
        centreOffset = orientYaw;   // auto-set centre on connect
        centreStatusText.setText(getString(R.string.centre_auto_set));
    }

    private void refreshIndicatorUI() {
        btnLeftIndicator.setAlpha(stateLeftIndicator  ? 1.0f : 0.5f);
        btnRightIndicator.setAlpha(stateRightIndicator ? 1.0f : 0.5f);
    }

    // --------------------------------------------------------
    // CONNECTION
    // --------------------------------------------------------
    private void startConnection() {
        final String ip      = ipInput.getText().toString().trim();
        final String portStr = portInput.getText().toString().trim();

        if (ip.isEmpty()) { statusText.setText(R.string.enter_ip); return; }

        final int port;
        try {
            port = Integer.parseInt(portStr.isEmpty() ? DEFAULT_PORT : portStr);
        } catch (NumberFormatException e) {
            statusText.setText(R.string.invalid_port);
            return;
        }

        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString("last_ip", ip)
                .putString("last_port", String.valueOf(port))
                .apply();

        connectButton.setEnabled(false);
        connectButton.setText(R.string.connecting);
        statusText.setText(R.string.connecting);

        connectExecutor.execute(() -> {
            try {
                socket = new Socket();
                socket.connect(new InetSocketAddress(ip, port), 3000);
                socket.setTcpNoDelay(true);
                out = new PrintWriter(socket.getOutputStream(), false);
                isConnected = true;

                uiHandler.post(() -> {
                    showControllerScreen();
                    registerSensors();
                    startSendThread();
                });

            } catch (Exception e) {
                final String msg = e.getMessage();
                uiHandler.post(() -> {
                    connectButton.setEnabled(true);
                    connectButton.setText(R.string.connect);
                    statusText.setText(getString(R.string.error_msg, msg));
                });
            }
        });
    }

    private void stopConnection() {
        isConnected = false;
        unregisterSensors();

        if (sendThread != null) {
            sendThread.interrupt();
            sendThread = null;
        }

        connectExecutor.execute(() -> {
            try {
                if (out    != null) out.close();
                if (socket != null) socket.close();
            } catch (Exception ignored) {}

            uiHandler.post(() -> {
                showConnectionScreen();
                connectButton.setEnabled(true);
                connectButton.setText(R.string.connect);
                statusText.setText(R.string.disconnected);
            });
        });
    }

    // --------------------------------------------------------
    // SEND THREAD
    // --------------------------------------------------------
    private void startSendThread() {
        sendQueue.clear();
        sendThread = new Thread(() -> {
            while (isConnected && !Thread.currentThread().isInterrupted()) {
                try {
                    String packet = sendQueue.take();
                    if (out != null) {
                        out.print(packet);
                        out.flush();
                        if (out.checkError()) {
                            uiHandler.post(this::stopConnection);
                            break;
                        }
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
        sendThread.setDaemon(true);
        sendThread.setName("SteeringWheelSendThread");
        sendThread.start();
    }

    // --------------------------------------------------------
    // SENSORS
    // --------------------------------------------------------
    private void registerSensors() {
        sensorManager.registerListener(this, gyroscope,      SensorManager.SENSOR_DELAY_GAME);
        sensorManager.registerListener(this, rotationVector, SensorManager.SENSOR_DELAY_GAME);
    }

    private void unregisterSensors() {
        if (sensorManager != null) sensorManager.unregisterListener(this);
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

            sendSensorPacket(event.timestamp);

            uiHandler.post(() ->
                    angleDisplay.setText(getString(R.string.yaw_display,
                            orientYaw - centreOffset))
            );
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    // --------------------------------------------------------
    // PACKET BUILDERS
    // Sensor packet includes orient adjusted by centre offset
    // Button packet sends all 4 button states as 0/1
    // PC receiver handles both by checking packet type field
    // --------------------------------------------------------
    private void sendSensorPacket(long nanoTimestamp) {
        if (!isConnected) return;

        float adjustedYaw = orientYaw - centreOffset;

        // Wrap adjusted yaw back to -180..+180 range after offset
        if (adjustedYaw >  180) adjustedYaw -= 360;
        if (adjustedYaw < -180) adjustedYaw += 360;

        final String packet = String.format(Locale.US,
                "{\"type\":\"sensor\",\"orient\":%.4f,\"gyro_z\":%.4f,\"ts\":%d}\n",
                adjustedYaw, gyroZ, (nanoTimestamp / 1_000_000L));

        boolean ignored = sendQueue.offer(packet);
    }

    private void sendButtonPacket() {
        if (!isConnected) return;

        final String packet = String.format(Locale.US,
                "{\"type\":\"buttons\",\"cruise\":%d,\"left_ind\":%d,\"right_ind\":%d,\"lights\":%d}\n",
                stateCruise         ? 1 : 0,
                stateLeftIndicator  ? 1 : 0,
                stateRightIndicator ? 1 : 0,
                stateLights         ? 1 : 0);

        // Button packets bypass the single-slot queue — sent directly on bg thread
        // so they are never dropped by a queued sensor packet
        connectExecutor.execute(() -> {
            if (out != null) {
                out.print(packet);
                out.flush();
            }
        });
    }

    // --------------------------------------------------------
    // LIFECYCLE
    // --------------------------------------------------------
    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopConnection();
        connectExecutor.shutdownNow();
    }
}