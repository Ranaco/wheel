"""End-to-end check of the companion without a phone.

Starts its own wheel_companion.py on spare ports (so a real phone can't wander in),
plays several phones' side over UDP on this machine, and reads the virtual
controllers back through XInput, the same way games see them.

Run:  python test_loopback.py           with a pretend gamepad as Player 1
      python test_loopback.py --alone   phones only
"""

import ctypes
import http.client
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import time
from pathlib import Path

from wheel_companion import (HEADER, MAGIC, STATE, STATE_EXTRA, T_BUTTONS, T_BYE, T_DISCOVER, T_HELLO,
                             T_STATE, T_STATUS, VERSION)

PORT = 47900
UI_PORT = 47901
PAIR_PORT = 47902
MAX_PLAYERS = 3


# XInput as games use it, written out here rather than borrowed from the companion, so
# the test reads the controllers the way a game would, not the way the companion does.
class XINPUT_GAMEPAD(ctypes.Structure):
    _fields_ = [("wButtons", ctypes.c_ushort), ("bLeftTrigger", ctypes.c_ubyte),
                ("bRightTrigger", ctypes.c_ubyte), ("sThumbLX", ctypes.c_short),
                ("sThumbLY", ctypes.c_short), ("sThumbRX", ctypes.c_short),
                ("sThumbRY", ctypes.c_short)]


class XINPUT_STATE(ctypes.Structure):
    _fields_ = [("dwPacketNumber", ctypes.c_uint), ("Gamepad", XINPUT_GAMEPAD)]


class XINPUT_VIBRATION(ctypes.Structure):
    _fields_ = [("wLeftMotorSpeed", ctypes.c_ushort), ("wRightMotorSpeed", ctypes.c_ushort)]


xinput = ctypes.windll.xinput1_4


def connected():
    found = set()
    for index in range(4):
        state = XINPUT_STATE()
        if xinput.XInputGetState(index, ctypes.byref(state)) == 0:
            found.add(index)
    return found


def pad(index):
    state = XINPUT_STATE()
    if xinput.XInputGetState(index, ctypes.byref(state)) != 0:
        return None
    return state.Gamepad


def wait_new(before, timeout=5.0):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        new = connected() - before
        if new:
            return min(new)
        time.sleep(0.05)
    return None


failures = []


def check(name, condition, detail=""):
    print(("PASS " if condition else "FAIL ") + name + (f"  ({detail})" if detail != "" else ""))
    if not condition:
        failures.append(name)


class Phone:
    """One simulated phone: its own socket, sequence numbers and (for newer builds) id."""

    def __init__(self, phone_id=None, name=None):
        self.id = phone_id
        self.name = name
        self.seq = 0
        self.open()

    def open(self):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.settimeout(0.01)

    def restart(self):
        """The app started over: a new socket, counting from one again."""
        self.sock.close()
        self.seq = 0
        self.open()

    def discover(self):
        body = b"" if self.id is None else self.id + self.name.encode()
        self.sock.sendto(HEADER.pack(MAGIC, VERSION, T_DISCOVER) + body, ("127.0.0.1", PORT))

    def send(self, steer=0, throttle=0, brake=0, buttons=0, seq=None):
        if seq is None:
            self.seq += 1
            seq = self.seq
        packet = STATE.pack(MAGIC, VERSION, T_STATE, seq, steer, throttle, brake, buttons)
        if self.id is not None:
            packet += STATE_EXTRA.pack(self.id, 0, 0, 0)
        self.sock.sendto(packet, ("127.0.0.1", PORT))

    def statuses(self):
        """Every status the companion sent since last asked: (large, small, player)."""
        got = []
        while True:
            try:
                data, _ = self.sock.recvfrom(256)
            except (socket.timeout, ConnectionResetError):
                return got
            if data[:4] == HEADER.pack(MAGIC, VERSION, T_STATUS) and len(data) >= 7:
                got.append((data[4], data[5], data[6]))

    def bye(self):
        self.sock.sendto(HEADER.pack(MAGIC, VERSION, T_BYE), ("127.0.0.1", PORT))


def hold(phones, seconds=0.2):
    """Keep every phone sending, as the real app does 125 times a second."""
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        for phone, values in phones:
            phone.send(**values)
        time.sleep(0.008)


def page(path, method="GET", body=None, host=None, headers=None):
    conn = http.client.HTTPConnection("127.0.0.1", UI_PORT, timeout=2)
    all_headers = {"Host": host or f"127.0.0.1:{UI_PORT}", **(headers or {})}
    conn.request(method, path, body=body, headers=all_headers)
    response = conn.getresponse()
    data = response.read()
    conn.close()
    return response.status, data


def steering(phones):
    """Which controller each phone drives, found by steering them apart: {phone: index}."""
    hold([(phone, dict(steer=steer)) for phone, steer in phones], 0.3)
    found = {}
    for index in connected() - outside:
        g = pad(index)
        for phone, steer in phones:
            if g is not None and abs(g.sThumbLX - steer) <= 1:
                found[phone] = index
    return found


outside = set()


def main():
    global outside
    script = Path(__file__).with_name("wheel_companion.py")
    # Its own settings folder, so the test never touches the real app's.
    data = tempfile.mkdtemp(prefix="wheel-test-")
    # Phones join without asking here; test_pairing.py covers asking.
    Path(data, "settings.json").write_text(json.dumps({"ask_new": False}))
    # A gamepad that isn't a phone, plugged in first so it's Player 1: phones have to work around it.
    extra = None
    if not connected() and "--alone" not in sys.argv:
        import vgamepad
        extra = vgamepad.VX360Gamepad()
        end = time.monotonic() + 3
        while not connected() and time.monotonic() < end:
            time.sleep(0.05)
    # Controllers already plugged in (that one, a real pad, a real Wheel's) aren't the test's.
    outside = connected()
    if len(outside) > 4 - MAX_PLAYERS:
        sys.exit(f"{len(outside)} controllers are plugged in already; the test needs {MAX_PLAYERS} free places.")
    proc = subprocess.Popen([sys.executable, str(script), "--port", str(PORT), "--ui-port", str(UI_PORT),
                             "--pair-port", str(PAIR_PORT), "--no-browser", "--quiet", "--max-players", str(MAX_PLAYERS)],
                            stdout=subprocess.DEVNULL, env={**os.environ, "WHEEL_DATA": data})
    try:
        time.sleep(1.0)

        # ---- An older build of the app: no id, one phone per address. ----
        old = Phone()
        old.discover()
        reply = None
        end = time.monotonic() + 2
        while reply is None and time.monotonic() < end:
            try:
                reply, _ = old.sock.recvfrom(256)
            except socket.timeout:
                pass
        check("discovery reply", reply is not None and reply[:4] == HEADER.pack(MAGIC, VERSION, T_HELLO), reply and reply[4:].decode())
        marks = None
        end = time.monotonic() + 1
        while marks is None and time.monotonic() < end:
            try:
                marks, _ = old.sock.recvfrom(256)
            except socket.timeout:
                pass
        # Which Xbox button each phone button presses, for the marks on its screen: the default mapping.
        check("the phone is told its buttons' Xbox marks", marks is not None and marks[:4] == HEADER.pack(MAGIC, VERSION, T_BUTTONS)
              and list(marks[4:]) == [1, 5, 2, 3, 7, 12, 6, 8, 4, 9, 10, 12, 13, 14, 15, 1, 2, 3, 4], marks and list(marks[4:]))

        before = connected()
        hold([(old, dict(steer=-16384, throttle=65535))])
        a = wait_new(before)
        check("first phone gets a controller", a is not None)
        if a is None:
            return
        hold([(old, dict(steer=-16384, throttle=65535))])
        g = pad(a)
        check("steer left half -> left stick X", abs(g.sThumbLX + 16384) <= 1, g.sThumbLX)
        check("full throttle -> RT 255", g.bRightTrigger == 255, g.bRightTrigger)
        hold([(old, dict(steer=32767, brake=32768, buttons=0b1))])
        g = pad(a)
        check("half brake -> LT ~128", 126 <= g.bLeftTrigger <= 129, g.bLeftTrigger)
        check("handbrake -> A", g.wButtons == 0x1000, hex(g.wButtons))
        hold([(old, dict(buttons=(1 << 2) | (1 << 4)))])
        check("shift_up + horn -> B + LS", pad(a).wButtons == (0x2000 | 0x0040), hex(pad(a).wButtons))
        # The menu controls: D-pad up and A, then left and right.
        hold([(old, dict(buttons=(1 << 11) | (1 << 15)))])
        check("menu up + menu A -> D-pad up + A", pad(a).wButtons == (0x0001 | 0x1000), hex(pad(a).wButtons))
        hold([(old, dict(buttons=(1 << 13) | (1 << 14)))])
        check("menu left + right -> D-pad left + right", pad(a).wButtons == (0x0004 | 0x0008), hex(pad(a).wButtons))
        hold([(old, dict(steer=1000))])
        old.send(steer=-30000, throttle=65535, seq=old.seq - 5)
        time.sleep(0.05)
        check("late packet ignored", pad(a).sThumbLX == 1000, pad(a).sThumbLX)

        # ---- Two newer phones at once: their own controllers, their own rumble. ----
        b = Phone(b"\x01" * 8, "Test B")
        c = Phone(b"\x02" * 8, "Test C")
        b.discover()
        c.discover()
        before = connected()
        hold([(b, dict(steer=16000)), (c, dict(steer=-16000))], 0.3)
        new = set()
        end = time.monotonic() + 5
        while len(new) < 2 and time.monotonic() < end:
            hold([(b, dict(steer=16000)), (c, dict(steer=-16000))], 0.1)
            new = connected() - before
        check("two more phones get two more controllers", len(new) == 2, sorted(new))
        if len(new) < 2:
            return
        readings = {i: pad(i).sThumbLX for i in new}
        pb = next((i for i, x in readings.items() if x > 15000), None)
        pc = next((i for i, x in readings.items() if x < -15000), None)
        check("each phone steers only its own controller", pb is not None and pc is not None and pb != pc, readings)
        hold([(b, dict(steer=16000)), (c, dict(steer=-16000))], 0.6)
        # 0 means Windows hasn't numbered the controller yet; the latest status has it.
        slot_b = b.statuses()[-1][2]
        slot_c = c.statuses()[-1][2]
        check("each phone is told its player number", slot_b and slot_c and slot_b != slot_c
              and {slot_b - 1, slot_c - 1} == {pb, pc}, f"B P{slot_b}, C P{slot_c}")

        xinput.XInputSetState(pc, ctypes.byref(XINPUT_VIBRATION(40000, 20000)))
        hold([(b, dict()), (c, dict())], 0.6)
        rumble_b = [s for s in b.statuses() if s[0] > 0]
        rumble_c = [s for s in c.statuses() if s[0] > 0]
        check("rumble reaches only the phone whose controller rumbles", rumble_c and not rumble_b, f"B {len(rumble_b)}, C {len(rumble_c)}")
        xinput.XInputSetState(pc, ctypes.byref(XINPUT_VIBRATION(0, 0)))

        # B's app starts over: new socket, sequence back to one.
        b.restart()
        hold([(b, dict(steer=-20000)), (c, dict())], 0.3)
        check("a restarted app picks its controller straight back up", pad(pb) is not None and pad(pb).sThumbLX < -19000, pad(pb) and pad(pb).sThumbLX)

        # B goes quiet: inputs let go, but the controller stays plugged in.
        hold([(b, dict(steer=20000, throttle=65535)), (c, dict())], 0.2)
        hold([(c, dict())], 0.5)
        g = pad(pb)
        check("silence -> neutral, controller kept", g is not None and g.sThumbLX == 0 and g.bRightTrigger == 0, g and (g.sThumbLX, g.bRightTrigger))
        hold([(b, dict(steer=5000)), (c, dict())], 0.2)
        check("back again on the same controller", pad(pb).sThumbLX > 4000, pad(pb).sThumbLX)

        # ---- Make Player 1: single-player games only listen to the first controller. ----
        first_free = min(set(range(4)) - outside)
        status, _ = page("/promote", "POST", body=json.dumps({"id": "02" * 8}), headers={"X-Wheel": "1", "Content-Type": "application/json"})
        hold([(b, dict()), (c, dict())], 1.0)
        found = steering([(b, 12000), (c, -12000)])
        check("Make Player 1 moves that phone to the first controller", status == 204 and found.get(c) == first_free, found and {("B" if k is b else "C"): v for k, v in found.items()})
        check("everyone keeps a controller through the swap", b in found and c in found and found[b] != found[c])
        pb, pc = found.get(b, pb), found.get(c, pc)
        hold([(b, dict()), (c, dict())], 0.6)
        slot_c = c.statuses()[-1][2]
        check("the promoted phone is told it's Player 1 now", slot_c == pc + 1, f"C P{slot_c}")

        # ---- A fourth phone, with three places: turned away, and the page says so. ----
        d = Phone(b"\x03" * 8, "Test D")
        d.discover()
        hold([(b, dict()), (c, dict()), (d, dict(steer=9000))], 2.5)
        # Again every second while it keeps trying, so the phone keeps saying so.
        told = sum(s[2] == 0xFF for s in d.statuses())
        check("the turned-away phone is told the PC is full, and again while it tries", told >= 2, f"{told} times")
        status, body = page("/state")
        state = json.loads(body)
        check("page lists the players", status == 200 and {p["name"] for p in state["players"]} >= {"Test B", "Test C"},
              [p["name"] for p in state.get("players", [])])
        check("a phone past the last place is turned away", len(state["players"]) == MAX_PLAYERS and state["turnedAway"]
              and state["turnedAway"][0]["name"] == "Test D", state.get("turnedAway"))
        check("the page shows the places other controllers hold", state["others"] == sorted(i + 1 for i in outside)
              and state["top"] == first_free + 1, (state["others"], state["top"]))
        # Phone cameras only open web links, so the code points at the pairing page.
        check("the code to scan opens this PC's pairing page", state["pair"].startswith("http://") and f":{PAIR_PORT}/pair?t=" in state["pair"], state.get("pair"))
        conn = http.client.HTTPConnection("127.0.0.1", PAIR_PORT, timeout=2)
        conn.request("GET", "/pair")
        pairing = conn.getresponse()
        text = pairing.read().decode()
        conn.close()
        check("pairing page hands the phone to Wheel", pairing.status == 200 and "intent://pair?h=" in text
              and "package=com.ranaco.wheel" in text and f"p={PORT}" in text, pairing.status)
        conn = http.client.HTTPConnection("127.0.0.1", PAIR_PORT, timeout=2)
        conn.request("GET", "/state")
        leak = conn.getresponse()
        leak.read()
        conn.close()
        check("pairing port serves nothing else", leak.status == 404, leak.status)
        check("pairing page falls back to the download when Wheel isn't installed", "S.browser_fallback_url=" in text and "%2Fget" in text)
        conn = http.client.HTTPConnection("127.0.0.1", PAIR_PORT, timeout=5)
        conn.request("GET", "/get")
        get = conn.getresponse()
        get_text = get.read().decode()
        conn.close()
        check("download page is served", get.status == 200 and "Get Wheel" in get_text, get.status)
        conn = http.client.HTTPConnection("127.0.0.1", PAIR_PORT, timeout=10)
        conn.request("GET", "/wheel.apk")
        apk = conn.getresponse()
        apk_body = apk.read()
        conn.close()
        check("the app downloads as an APK", apk.status == 200 and apk.getheader("Content-Type") == "application/vnd.android.package-archive"
              and apk_body[:2] == b"PK" and len(apk_body) > 50_000, f"{apk.status}, {len(apk_body)} bytes")
        status, svg = page("/qr.svg")
        check("QR code is served", status == 200 and svg.lstrip().startswith(b"<svg"), status)
        status, _ = page("/state", host="evil.example")
        check("another site can't read the page", status == 403, status)
        status, _ = page("/forget", "POST", body=json.dumps({"id": "02" * 8}), headers={"Content-Type": "application/json"})
        check("removing needs the page's own header", status == 403, status)

        # Settings from the page: saved, and in effect at once.
        status, _ = page("/settings", "POST", body=json.dumps({"keepMinutes": 2, "autoPromote": True}),
                         headers={"X-Wheel": "1", "Content-Type": "application/json"})
        hold([(b, dict()), (c, dict())], 0.4)
        state = json.loads(page("/state")[1])
        check("settings change from the page", status == 204 and state["keepMinutes"] == 2 and state["autoPromote"] is True,
              (state["keepMinutes"], state["autoPromote"]))
        saved = json.loads((Path(data) / "settings.json").read_text())
        check("settings are saved for next time", saved["keep_minutes"] == 2 and saved["auto_promote"] is True, saved)

        # Remove C from the page: its controller unplugs.
        status, _ = page("/forget", "POST", body=json.dumps({"id": "02" * 8}), headers={"X-Wheel": "1", "Content-Type": "application/json"})
        hold([(b, dict())], 0.5)  # D stays quiet, or it would take the freed place
        check("Remove unplugs that controller", status == 204 and pad(pc) is None, status)

        # Player 1 is gone and hand-over is on: the page counts down, then B takes over.
        state = json.loads(page("/state")[1])
        check("the page counts down to handing Player 1 over", state["handoverTo"] == "01" * 8
              and 0 < state["handoverIn"] <= 15, (state["handoverTo"], state["handoverIn"]))
        end = time.monotonic() + 17
        while time.monotonic() < end and json.loads(page("/state")[1])["handoverTo"] is not None:
            hold([(b, dict())], 0.5)
        hold([(b, dict())], 1.0)
        found = steering([(b, 14000)])
        check("Player 1 goes to whoever's still playing", found.get(b) == first_free, found.get(b))
        pb = found.get(b, pb)
        hold([(b, dict())], 1.5)
        state = json.loads(page("/state")[1])
        check("and that's the end of it: no hand-over after", state["handoverIn"] is None, state["handoverIn"])

        # A clean goodbye lets go at once.
        hold([(b, dict(steer=20000)), (old, dict(steer=20000))], 0.2)
        b.bye()
        time.sleep(0.05)
        check("bye -> neutral", pad(pb).sThumbLX == 0, pad(pb).sThumbLX)
    finally:
        proc.terminate()
        proc.wait(5)
        shutil.rmtree(data, ignore_errors=True)
        del extra
    print()
    print("all passed" if not failures else f"{len(failures)} failed: {', '.join(failures)}")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
