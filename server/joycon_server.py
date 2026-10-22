#!/usr/bin/env python3
"""
Servidor companion para "Joy-Con Phone" (modo Wi-Fi).

  Teléfono --UDP:26800--> este servidor --+--> servidor DSU/Cemuhook (UDP:26760)  -> Citron/Yuzu: movimiento y botones
                                          +--> mando Xbox 360 virtual (opcional, Windows con ViGEmBus + vgamepad)

Uso:
    python joycon_server.py              # solo DSU (botones + stick + movimiento), sin drivers extra
    python joycon_server.py --vpad       # además crea un mando Xbox 360 virtual (Windows)
    python joycon_server.py -v           # muestra el estado recibido (depuración)
"""
import argparse
import random
import socket
import struct
import sys
import threading
import time
import zlib

PHONE_PORT = 26800
DSU_PORT = 26760
PHONE_FMT = "<4sB3BhhI6f"           # 40 bytes, ver WifiSender.kt
PHONE_SIZE = struct.calcsize(PHONE_FMT)
FAKE_MAC = bytes([0x7C, 0xBB, 0x8A, 0x12, 0x34, 0x56])

# Byte del reporte del Joy-Con -> botón "estilo Xbox" (posicional: A de Nintendo = B de Xbox, etc.)
JC_MAP = {
    3: [(0x01, "X"), (0x02, "Y"), (0x04, "A"), (0x08, "B"), (0x10, "RB"), (0x20, "LB"), (0x40, "RB"), (0x80, "RT")],
    4: [(0x01, "BACK"), (0x02, "START"), (0x04, "RS"), (0x08, "LS"), (0x10, "HOME"), (0x20, "HOME")],
    5: [(0x01, "DOWN"), (0x02, "UP"), (0x04, "RIGHT"), (0x08, "LEFT"), (0x10, "RB"), (0x20, "LB"), (0x40, "LB"), (0x80, "LT")],
}


class Phone:
    """Último estado recibido del teléfono."""

    def __init__(self):
        self.lock = threading.Lock()
        self.reset()
        self.last_rx = 0.0
        self.active = False

    def reset(self):
        self.side = 1
        self.b = (0, 0, 0)
        self.sx = self.sy = 0.0
        self.acc = (0.0, 0.0, 0.0)
        self.gyro = (0.0, 0.0, 0.0)

    def snapshot(self):
        with self.lock:
            return self.side, self.b, self.sx, self.sy, self.acc, self.gyro


def pad_from(snap, stick_side):
    side, b, sx, sy, _, _ = snap
    names = set()
    for idx, table in JC_MAP.items():
        for mask, name in table:
            if b[idx - 3] & mask:
                names.add(name)
    s = stick_side if stick_side != "auto" else ("left" if side == 0 else "right")
    lx = ly = rx = ry = 0.0
    if s == "left":
        lx, ly = sx, sy
    else:
        rx, ry = sx, sy
    return names, (lx, ly, rx, ry)


def map_motion(acc, gyro, flip_acc, flip_gyro):
    """Ejes Android (x derecha, y arriba, z hacia ti) -> ejes DSU. Ajusta con --flip-acc / --flip-gyro."""
    ax, ay, az = acc
    gx, gy, gz = gyro
    a = [ax, -az, ay]
    g = [gx, -gz, gy]          # pitch, yaw, roll
    for ch in flip_acc:
        a["xyz".index(ch)] *= -1
    for ch in flip_gyro:
        g["pyr".index(ch)] *= -1
    return a, g


# ---------------------------------------------------------------- DSU / Cemuhook
class DsuServer:
    def __init__(self, port, args):
        self.args = args
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind(("0.0.0.0", port))
        if sys.platform == "win32":
            try:
                self.sock.ioctl(socket.SIO_UDP_CONNRESET, False)
            except Exception:
                pass
        self.clients = {}
        self.lock = threading.Lock()
        self.packet = 0
        self.server_id = random.getrandbits(32)
        self.closed = False

    def wrap(self, event, payload):
        body = struct.pack("<I", event) + payload
        pkt = struct.pack("<4sHHII", b"DSUS", 1001, len(body), 0, self.server_id) + body
        crc = zlib.crc32(pkt) & 0xFFFFFFFF
        return pkt[:8] + struct.pack("<I", crc) + pkt[12:]

    @staticmethod
    def shared(slot):
        if slot == 0:   # slot, estado=conectado, modelo=giro completo, conexión=bluetooth, MAC, batería llena
            return struct.pack("<BBBB6sB", 0, 2, 2, 2, FAKE_MAC, 5)
        return struct.pack("<BBBB6sB", slot, 0, 0, 0, bytes(6), 0)

    def serve(self):
        while not self.closed:
            try:
                data, addr = self.sock.recvfrom(1024)
            except OSError:
                continue
            if len(data) < 20 or data[:4] != b"DSUC":
                continue
            ev = struct.unpack_from("<I", data, 16)[0]
            if ev == 0x100000:
                self.sock.sendto(self.wrap(0x100000, struct.pack("<H", 1001)), addr)
            elif ev == 0x100001 and len(data) >= 24:
                count = struct.unpack_from("<i", data, 20)[0]
                for slot in list(data[24:24 + max(0, min(count, 4))]):
                    self.sock.sendto(self.wrap(0x100001, self.shared(slot) + b"\x00"), addr)
            elif ev == 0x100002:
                with self.lock:
                    if addr not in self.clients:
                        print(f"[DSU] cliente registrado: {addr[0]}:{addr[1]}")
                    self.clients[addr] = time.time()

    def build_data(self, snap):
        names, (lx, ly, rx, ry) = pad_from(snap, self.args.stick)
        b1 = 0
        for n, m in (("LEFT", 0x80), ("DOWN", 0x40), ("RIGHT", 0x20), ("UP", 0x10),
                     ("START", 0x08), ("RS", 0x04), ("LS", 0x02), ("BACK", 0x01)):
            if n in names:
                b1 |= m
        b2 = 0
        for n, m in (("Y", 0x80), ("B", 0x40), ("A", 0x20), ("X", 0x10),
                     ("RB", 0x08), ("LB", 0x04), ("RT", 0x02), ("LT", 0x01)):
            if n in names:
                b2 |= m
        home = 255 if "HOME" in names else 0

        def ax(v):
            return max(0, min(255, int(round((v + 1) / 2 * 255))))
        sgn = -1 if self.args.invert_y else 1
        sticks = bytes([ax(lx), ax(sgn * ly), ax(rx), ax(sgn * ry)])
        analog = bytes(255 if n in names else 0 for n in
                       ("LEFT", "DOWN", "RIGHT", "UP", "Y", "B", "A", "X", "RB", "LB", "RT", "LT"))
        a, g = map_motion(snap[4], snap[5], self.args.flip_acc, self.args.flip_gyro)
        payload = self.shared(0) + struct.pack("<BI", 1, self.packet)
        payload += struct.pack("<BBBB", b1, b2, home, 0) + sticks + analog + bytes(12)
        payload += struct.pack("<Q", time.monotonic_ns() // 1000)
        payload += struct.pack("<6f", a[0], a[1], a[2], g[0], g[1], g[2])
        self.packet = (self.packet + 1) & 0xFFFFFFFF
        return self.wrap(0x100002, payload)

    def push(self, snap):
        now = time.time()
        with self.lock:
            targets = [a for a, t in self.clients.items() if now - t < 30]
            self.clients = {a: t for a, t in self.clients.items() if now - t < 30}
        if not targets:
            return
        pkt = self.build_data(snap)
        for a in targets:
            try:
                self.sock.sendto(pkt, a)
            except OSError:
                pass


# ---------------------------------------------------------------- Mando Xbox 360 virtual (opcional, Windows)
class VirtualPad:
    def __init__(self, args):
        import vgamepad as vg   # pip install vgamepad  (requiere ViGEmBus instalado)
        self.vg = vg
        self.pad = vg.VX360Gamepad()
        B = vg.XUSB_BUTTON
        self.flags = {
            "A": B.XUSB_GAMEPAD_A, "B": B.XUSB_GAMEPAD_B, "X": B.XUSB_GAMEPAD_X, "Y": B.XUSB_GAMEPAD_Y,
            "LB": B.XUSB_GAMEPAD_LEFT_SHOULDER, "RB": B.XUSB_GAMEPAD_RIGHT_SHOULDER,
            "UP": B.XUSB_GAMEPAD_DPAD_UP, "DOWN": B.XUSB_GAMEPAD_DPAD_DOWN,
            "LEFT": B.XUSB_GAMEPAD_DPAD_LEFT, "RIGHT": B.XUSB_GAMEPAD_DPAD_RIGHT,
            "START": B.XUSB_GAMEPAD_START, "BACK": B.XUSB_GAMEPAD_BACK,
            "LS": B.XUSB_GAMEPAD_LEFT_THUMB, "RS": B.XUSB_GAMEPAD_RIGHT_THUMB, "HOME": B.XUSB_GAMEPAD_GUIDE,
        }
        self.args = args

    def update(self, snap):
        names, (lx, ly, rx, ry) = pad_from(snap, self.args.stick)
        for n, f in self.flags.items():
            (self.pad.press_button if n in names else self.pad.release_button)(button=f)
        sgn = -1 if self.args.invert_y else 1
        self.pad.left_joystick_float(x_value_float=lx, y_value_float=sgn * ly)
        self.pad.right_joystick_float(x_value_float=rx, y_value_float=sgn * ry)
        self.pad.left_trigger(value=255 if "LT" in names else 0)
        self.pad.right_trigger(value=255 if "RT" in names else 0)
        self.pad.update()


# ---------------------------------------------------------------- Programa principal
def local_ips():
    ips = set()
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))   # no envía nada, solo elige la interfaz
        ips.add(s.getsockname()[0])
        s.close()
    except Exception:
        pass
    try:
        ips.update(socket.gethostbyname_ex(socket.gethostname())[2])
    except Exception:
        pass
    return sorted(i for i in ips if not i.startswith("127."))


def main():
    ap = argparse.ArgumentParser(description="Servidor companion de Joy-Con Phone")
    ap.add_argument("--phone-port", type=int, default=PHONE_PORT)
    ap.add_argument("--dsu-port", type=int, default=DSU_PORT)
    ap.add_argument("--vpad", action="store_true", help="crear mando Xbox 360 virtual (Windows + ViGEmBus + vgamepad)")
    ap.add_argument("--stick", choices=["auto", "left", "right"], default="auto",
                    help="a qué stick del mando virtual va el stick del teléfono (auto: izq para Joy-Con L, der para R)")
    ap.add_argument("--invert-y", action="store_true", help="invertir el eje Y del stick")
    ap.add_argument("--flip-acc", default="", help="ejes del acelerómetro a invertir, p. ej. xz")
    ap.add_argument("--flip-gyro", default="", help="ejes del giroscopio a invertir: p (pitch), y (yaw), r (roll), p. ej. yr")
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args()

    phone = Phone()
    dsu = DsuServer(args.dsu_port, args)
    vpad = None
    if args.vpad:
        try:
            vpad = VirtualPad(args)
            print("[VPAD] Mando Xbox 360 virtual creado.")
        except Exception as e:
            print(f"[VPAD] No se pudo crear el mando virtual ({e}). Continúo solo con DSU.")

    def apply():
        snap = phone.snapshot()
        dsu.push(snap)
        if vpad:
            vpad.update(snap)

    def phone_listener():
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind(("0.0.0.0", args.phone_port))
        if sys.platform == "win32":
            try:
                sock.ioctl(socket.SIO_UDP_CONNRESET, False)
            except Exception:
                pass
        while True:
            try:
                data, addr = sock.recvfrom(2048)
            except OSError:
                continue
            if len(data) != PHONE_SIZE:
                continue
            magic, side, b0, b1, b2, sx, sy, seq, *m = struct.unpack(PHONE_FMT, data)
            if magic != b"JCP1":
                continue
            with phone.lock:
                if not phone.active:
                    print(f"[TELÉFONO] conectado desde {addr[0]}")
                phone.active = True
                phone.last_rx = time.time()
                phone.side = side
                phone.b = (b0, b1, b2)
                phone.sx, phone.sy = sx / 32767.0, sy / 32767.0
                phone.acc = tuple(m[0:3])
                phone.gyro = tuple(m[3:6])
            apply()

    threading.Thread(target=dsu.serve, daemon=True).start()
    threading.Thread(target=phone_listener, daemon=True).start()

    print("=" * 60)
    print(" Servidor Joy-Con Phone en marcha")
    for ip in local_ips():
        print(f"   IP para escribir en la app:  {ip}")
    print(f"   Teléfono -> PC : UDP {args.phone_port}   (permítelo en el firewall)")
    print(f"   DSU (Cemuhook)  : 127.0.0.1:{args.dsu_port}  (configúralo en Citron/Yuzu)")
    print("=" * 60)

    try:
        while True:
            time.sleep(0.25)
            with phone.lock:
                stale = phone.active and time.time() - phone.last_rx > 1.0
                if stale:
                    phone.active = False
                    phone.reset()
            if stale:
                print("[TELÉFONO] sin datos; botones a cero")
                apply()
            if args.verbose and phone.active:
                snap = phone.snapshot()
                names, _ = pad_from(snap, args.stick)
                print(f"b={snap[1]} stick=({snap[2]:+.2f},{snap[3]:+.2f}) "
                      f"acc=({snap[4][0]:+.2f},{snap[4][1]:+.2f},{snap[4][2]:+.2f})g "
                      f"gyro=({snap[5][0]:+.0f},{snap[5][1]:+.0f},{snap[5][2]:+.0f})°/s {sorted(names)}")
    except KeyboardInterrupt:
        print("\nCerrando.")


if __name__ == "__main__":
    main()
