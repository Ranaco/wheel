"""A phone that joins while all four of Windows' controller places are taken.

Windows numbers its controller only once a place frees up, and ViGEm then reports a
number games don't see. The companion should find the real one by itself, without
unplugging anything. Fills the free places with pretend gamepads, so run it with no
game open.

Run:  python test_overflow.py
"""

import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

import vgamepad

import test_loopback as t

COMPANION = os.path.dirname(os.path.abspath(__file__))


def main():
    fillers = []
    while len(t.connected()) < 4:
        fillers.append(vgamepad.VX360Gamepad())
        time.sleep(0.4)
    print(f"Windows is full: {len(fillers)} pretend gamepads")

    data = tempfile.mkdtemp(prefix="wheel-overflow-")
    Path(data, "settings.json").write_text(json.dumps({"ask_new": False}))
    log = open(os.path.join(data, "log.txt"), "w")
    proc = subprocess.Popen([sys.executable, os.path.join(COMPANION, "wheel_companion.py"), "--port", str(t.PORT),
                             "--ui-port", str(t.UI_PORT), "--pair-port", str(t.PAIR_PORT), "--no-browser", "--quiet"],
                            stdout=log, env={**os.environ, "WHEEL_DATA": data})
    try:
        time.sleep(0.6)  # joins before the companion has counted the others, as a phone can
        phone = t.Phone(b"\x07" * 8, "Overflow")
        phone.discover()
        t.hold([(phone, dict(steer=12345))], 1.0)
        state = json.loads(t.page("/state")[1])
        t.check("a phone joining a full PC has no player number yet", state["players"] and state["players"][0]["slot"] == 0,
                [(p["name"], p["slot"]) for p in state["players"]])

        del fillers[-2:]  # two places free up
        end = time.monotonic() + 8
        while time.monotonic() < end and not state["players"][0]["slot"]:
            t.hold([(phone, dict(steer=12345))], 0.5)
            state = json.loads(t.page("/state")[1])
        t.hold([(phone, dict(steer=12345))], 0.3)
        found = next((i for i in t.connected() if t.pad(i).sThumbLX == 12345), None)
        slot = state["players"][0]["slot"]
        t.check("once a place frees, it has the number games see", found is not None and slot == found + 1,
                f"P{slot}, games see P{found + 1 if found is not None else '?'}")
    finally:
        proc.terminate()
        proc.wait(5)
        log.close()
        shutil.rmtree(data, ignore_errors=True)
    print()
    print("all passed" if not t.failures else f"{len(t.failures)} failed: {', '.join(t.failures)}")
    sys.exit(1 if t.failures else 0)


if __name__ == "__main__":
    main()
