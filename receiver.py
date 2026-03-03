from importlib.resources import path
import socket
import json
import ctypes
import os
import time

# ------------------------------------------------------------
# TUNED FOR SPEED (ETS2)
# ------------------------------------------------------------
MAX_LOCK         = 450.0   
ALPHA            = 0.99    # Pure Gyro speed, Yaw used only for tiny drift corrections
SMOOTHING_ALPHA  = 1.0     # 1.0 = DISBABLED. New data is applied instantly.
DEADZONE         = 0.5     # Almost no deadzone for immediate response
CURVE_FACTOR     = 0.1     # Near-linear (1:1 feel)     
USB_PORT         = 5555
VJOY_DEVICE_ID   = 3

V_MIN, V_MAX = 0x1, 0x8000

class VJoy:
    def __init__(self, device_id=1):
        # Search common install locations
        candidates = [
        r"C:\Program Files\vJoy\x64\vJoyInterface.dll",
        r"C:\Program Files (x86)\vJoy\x86\vJoyInterface.dll",
        r"C:\Program Files\vJoy\x86\vJoyInterface.dll",
        os.path.join(os.environ.get("ProgramFiles",  ""), "vJoy", "x64", "vJoyInterface.dll"),
        os.path.join(os.environ.get("ProgramFiles(x86)", ""), "vJoy", "x86", "vJoyInterface.dll"),
        ]
        path = next((p for p in candidates if os.path.exists(p)), None)
        if path is None:
            raise RuntimeError(
            "vJoy not found. Please install vJoy from:\n"
            "https://github.com/jshafer817/vJoy/releases"
            )
        self.dll = ctypes.WinDLL(path)
        self.dev = device_id
        if not self.dll.AcquireVJD(self.dev):
            raise RuntimeError(
            f"Could not acquire vJoy Device {self.dev}.\n"
            "Make sure vJoy is installed and the device is configured\n"
            "in 'Configure vJoy' with at least the X axis enabled."
            )
        self.dll.ResetVJD(self.dev)

    def update(self, normalized_val):
        curved = (1 - CURVE_FACTOR) * normalized_val + CURVE_FACTOR * (normalized_val ** 3)
        clamped = max(-1.0, min(1.0, curved))
        raw = int((clamped + 1.0) / 2.0 * (V_MAX - V_MIN) + V_MIN)
        self.dll.SetAxis(raw, self.dev, 0x30)

# ------------------------------------------------------------
# STATE & PERF MONITORING
# ------------------------------------------------------------
vjoy = VJoy(VJOY_DEVICE_ID)
fused_angle = 0.0
last_yaw = None
last_ts = None
smoothed_out = 0.0

# Perf tracking
packet_count = 0
last_perf_check = time.time()
current_hz = 0

def process_frame(packet):
    global fused_angle, last_yaw, last_ts, smoothed_out
    global packet_count, last_perf_check, current_hz

    # ---- Button packet — handle separately ----
    if packet.get("type") == "buttons":
        vjoy.dll.SetBtn(packet["cruise"],    vjoy.dev, 1)  # Button 1 = Cruise
        vjoy.dll.SetBtn(packet["left_ind"],  vjoy.dev, 2)  # Button 2 = Left indicator
        vjoy.dll.SetBtn(packet["right_ind"], vjoy.dev, 3)  # Button 3 = Right indicator
        vjoy.dll.SetBtn(packet["lights"],    vjoy.dev, 4)  # Button 4 = Lights
        return

    # ---- Sensor packet — rest of existing logic continues below ----
    yaw  = packet["orient"]
    gyro = packet["gyro_z"]
    ts   = packet["ts"]

    # Calculate Hz (Packets per second)
    packet_count += 1
    now = time.time()
    if now - last_perf_check >= 1.0:
        current_hz = packet_count
        packet_count = 0
        last_perf_check = now

    if last_ts is None:
        last_ts, last_yaw = ts, yaw
        return

    dt = (ts - last_ts) / 1000.0
    if dt <= 0: return

    # 1. Unwrap Yaw
    yaw_delta = yaw - last_yaw
    if yaw_delta > 180: yaw_delta -= 360
    elif yaw_delta < -180: yaw_delta += 360

    # Invert direction
    yaw_delta = -yaw_delta
    gyro = -gyro

    # 2. Complementary Filter with overwind prevention
    gyro_contribution = gyro * dt
    yaw_contribution  = yaw_delta

    # Block accumulation if already at limit in the same direction
    if not (fused_angle >= MAX_LOCK  and (gyro_contribution + yaw_contribution) > 0):
        if not (fused_angle <= -MAX_LOCK and (gyro_contribution + yaw_contribution) < 0):
            fused_angle = ALPHA * (fused_angle + gyro_contribution) + (1 - ALPHA) * (fused_angle + yaw_contribution)

    fused_angle = max(-MAX_LOCK, min(MAX_LOCK, fused_angle))

    # 3. Apply Deadzone
    processed_angle = fused_angle
    if abs(processed_angle) < DEADZONE:
        processed_angle = 0.0

    # 4. Fast Smoothing
    target_norm = processed_angle / MAX_LOCK
    smoothed_out = (SMOOTHING_ALPHA * target_norm) + (1 - SMOOTHING_ALPHA) * smoothed_out

    # 5. Output
    vjoy.update(smoothed_out)
    last_ts, last_yaw = ts, yaw

    # Performance Visualizer
    print(f"[{current_hz} Hz] Angle: {fused_angle:+7.2f}° | Output: {smoothed_out:+.3f}", end='\r')

# ------------------------------------------------------------
# SERVER LOOP
# ------------------------------------------------------------
def run():
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("0.0.0.0", USB_PORT))
    server.listen(1)
    print(f"ETS2 Speed-Tuned Controller Active on {USB_PORT}...")

    while True:
        conn, _ = server.accept()
        global fused_angle, last_ts, last_yaw, smoothed_out
        fused_angle, last_ts, last_yaw, smoothed_out = 0.0, None, None, 0.0
        buffer = ""
        try:
            while True:
                data = conn.recv(1024).decode('utf-8')
                if not data: break
                buffer += data
                while "\n" in buffer:
                    line, buffer = buffer.split("\n", 1)
                    try: process_frame(json.loads(line))
                    except: pass
        except: pass
        finally: conn.close()

if __name__ == "__main__":
    run()