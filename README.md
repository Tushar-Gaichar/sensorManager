# 📱 Steering Wheel — 900° Phone Steering Wheel for ETS2

Turn your Android phone into a full 900° steering wheel controller for Euro Truck Simulator 2 (and other games) — no hardware wheel required. Mount your phone on any surface at a steering wheel angle, connect over WiFi, and drive.

---

## ✨ Features

- **True 900° steering** — full lock-to-lock range just like a real wheel
- **Complementary filter** — fuses gyroscope + orientation sensor for fast, drift-free tracking
- **WiFi transport** — no USB cable or developer options required
- **In-app controls** — left/right indicators, lights, cruise control mapped to vJoy buttons
- **Set centre on connect** — calibrates your zero point when connected or PC script is restarted
- **Screen always on** — app keeps display active so phone never sleeps mid-drive
- **Persistent IP memory** — remembers your PC's IP between sessions

---

## 📁 Project Structure
```
sensormanager/
├── app/
│   └── src/main/
│       ├── java/com/example/sensormanager/
│       │   └── MainActivity.java       ← All app logic
│       └── res/
│           ├── layout/
│           │   └── activity_main.xml   ← Connection + controller UI
│           └── values/
│               └── strings.xml         ← All string resources
├── PC/
│   └── receiver.py                     ← Python vJoy bridge
└── README.md
```

---

## 🏗️ Architecture Overview
```
┌─────────────────────────┐      WiFi TCP       ┌─────────────────────────────┐
│      Android App        │ ──────────────────▶ │       PC Receiver           │
│                         │                      │                             │
│  Gyroscope sensor       │  {"type":"sensor",   │  Unwrap → Filter → Clamp   │
│  + Rotation Vector      │   "orient": 45.3,    │  → Deadzone → Curve        │
│  → JSON over TCP        │   "gyro_z": -12.4,   │  → vJoy X Axis             │
│                         │   "ts": 170912345}   │                             │
│  Button presses         │  {"type":"buttons",  │  → vJoy Buttons 1–4        │
│  → separate packet      │   "cruise":0, ...}   │                             │
└─────────────────────────┘                      └─────────────────────────────┘
                                                              │
                                                              ▼
                                                   ┌──────────────────┐
                                                   │   ETS2 / Game    │
                                                   └──────────────────┘
```

---

## 📱 Android App — `MainActivity.java`

### Two Screens

| Screen | Shown when | Contents |
|---|---|---|
| Connection | On launch / after disconnect | IP input, port, connect button |
| Controller | After successful connect | Live angle, sets centre, 4 buttons |

The controller screen is **landscape-locked** (`sensorLandscape`) — angle display on the left, all buttons on the right in a 2×2 grid.

---

### Sensors

Two sensors run simultaneously:
```
TYPE_GYROSCOPE
  → values[2] = Z axis = yaw rotation speed
  → Unit: rad/s → converted to deg/s (× 57.2957795)
  → Fast response, drifts over time

TYPE_ROTATION_VECTOR
  → Android's fused orientation (gyro + accel + magnetometer internally)
  → Converted to azimuth/yaw angle in degrees (−180 to +180)
  → Stable long-term, slightly slower than raw gyro
```

Both registered at `SENSOR_DELAY_GAME` (~50 Hz).

---

### Networking — BlockingQueue Design
```
Sensor Thread (50 Hz)          Send Thread
       │                            │
       │   sendQueue.offer(pkt) ──▶ │  ArrayBlockingQueue(size=1)
       │   (never blocks)           │  take() → socket.write() → flush()
       │                            │  checkError() → detect broken pipe
```

`ArrayBlockingQueue` with size 1 means if the PC can't keep up, old sensor packets are silently dropped and only the **latest** reading is sent. This prevents lag from accumulating over time.

Button packets bypass the queue and go directly via `connectExecutor` so they are never dropped.

---

### Centre Calibration
The PC receiver always sees 0° when the wheel is connected.

---

### Screen Always On
```java
getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
```

Combined with immersive sticky fullscreen mode — no WakeLock, no developer options needed.

---

## 💻 PC Receiver — `receiver.py`

### Requirements

- Python 3.8+
- [vJoy](https://github.com/jshafer817/vJoy/releases) installed
- vJoy device configured with X axis enabled

No pip packages needed — uses only the standard library.

---

### Configuration

| Constant | Default | What it controls |
|---|---|---|
| `MAX_LOCK` | `450.0` | Degrees per side (450 × 2 = 900° total) |
| `ALPHA` | `0.99` | Filter ratio. Higher = trust gyro more (responsive). Lower = trust orientation (stable) |
| `SMOOTHING_ALPHA` | `1.0` | `1.0` = disabled. Lower values smooth output but add lag |
| `DEADZONE` | `0.5` | Dead degrees at centre — prevents micro-jitter when holding still |
| `CURVE_FACTOR` | `0.1` | `0.0` = linear. `1.0` = cubic (fine near centre, coarse at extremes) |
| `VJOY_DEVICE_ID` | `3` | vJoy device number to acquire |
| `USB_PORT` | `5555` | TCP port — must match app entry |

---

### Processing Pipeline (per frame)
```
Raw orient + gyro_z + timestamp
    │
    ▼  ① dt = (ts - last_ts) / 1000.0
    │
    ▼  ② Yaw unwrapping — fixes ±180° sign flip
    │     delta > +180 → subtract 360
    │     delta < −180 → add 360
    │
    ▼  ③ Direction inversion
    │     gyro = -gyro  /  yaw_delta = -yaw_delta
    │     (Android clockwise = negative, we want right = positive)
    │
    ▼  ④ Complementary filter
    │     gyro_contribution = gyro × dt
    │     fused = ALPHA × (fused + gyro_contribution)
    │           + (1−ALPHA) × (fused + yaw_delta)
    │
    ▼  ⑤ Hard clamp → max(−MAX_LOCK, min(MAX_LOCK, fused))
    │
    ▼  ⑥ Deadzone → if |angle| < DEADZONE: output = 0
    │
    ▼  ⑦ Low-pass smoothing (disabled by default)
    │
    ▼  ⑧ Non-linear curve
    │     output = (1−CURVE)×linear + CURVE×cubic
    │
    ▼  ⑨ vJoy SetAxis
          Maps −1.0..+1.0 → 0x1..0x8000
```

---

### Overwind Problem

**The problem:** Rotating past 450° keeps accumulating internally. When you rotate back, the excess must "unwind" before the wheel moves — feels like centre drifted.

**The fix I had tried but didn't work - resume threshold:**
```python
# Phone crosses ±MAX_LOCK for the first time
overwind_threshold = fused_angle + total_delta   # record phone's real position
fused_angle = MAX_LOCK                            # freeze output

# Each frame while overwound:
if overwind_threshold > 0 and fused_angle + total_delta <= overwind_threshold:
    overwind_threshold = None   # phone returned — resume immediately
```
```
Phone at 430° → normal
Phone at 450° → freeze. Threshold recorded at 452°
Phone at 600° → still frozen
Phone returns to 452° → threshold cleared, tracking resumes from 450°
Phone at 300° → output = 300°. Centre is correct.
```

---

## 🔧 Setup Guide

### PC

1. Install [vJoy](https://github.com/jshafer817/vJoy/releases)
2. Open **Configure vJoy** → enable X Axis + 4 buttons on your device
3. Find your IP: `ipconfig` in Command Prompt → IPv4 under WiFi adapter
4. Run: `python receiver.py`

### Phone

1. Install the APK
2. Connect to same WiFi as PC
3. Place phone in mount **Current point is set as centre**
4. Enter PC's IP + port 5555 → tap Connect

### ETS2

1. **Options → Controls** → assign vJoy X axis to steering
2. Bind buttons 1–4 to Cruise, Left indicator, Right indicator, Lights
3. In `config.cfg`:
```
uset g_steering_autocenter "0"
uset g_steering_speed "0.0"
```

---

## 🔵 vJoy Button Map

| Button | App | ETS2 |
|---|---|---|
| 1 | CC Cruise | Toggle cruise control |
| 2 | ◀ Left | Left turn signal |
| 3 | ▶ Right | Right turn signal |
| 4 | 💡 Lights | Cycle headlights |

---

## 🐛 Troubleshooting

| Problem | Fix |
|---|---|
| "Failed to connect" | Same WiFi? Firewall allowing port 5555? |
| Steering inverted | Flip sign: `gyro = gyro` (remove negation) in receiver |
| Slow/drifting wheel | Lower `ALPHA` to 0.95–0.97 |
| Jittery output | Set `SMOOTHING_ALPHA` to 0.2–0.4 |
| vJoy error | Check device ID matches, X axis enabled in Configure vJoy |
| 0 Hz in console | App not connected or still on connection screen |
| Centre drifts after hard lock | Turn in the locked wheel direction till where you hit the limit and then start rotating back — tracking resumes there |

---

## 📄 License

MIT — free to use, modify, and distribute.
