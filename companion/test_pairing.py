"""Asking before a new phone joins: Allow, Block, the code on screen, and what's remembered.

Starts its own companion on spare ports with a settings folder of its own, plays phones
over UDP, and reads the virtual controllers back through XInput.

Run:  python test_pairing.py
"""

import hashlib
import hmac
import http.client
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
from urllib.parse import parse_qs, urlsplit

import test_loopback as t
from test_loopback import Phone, check, connected, hold, page
from wheel_companion import BLOCKED, HEADER, MAGIC, NAME_SIZE, PROOF_SIZE, T_DISCOVER, VERSION, WAITING

COMPANION = os.path.dirname(os.path.abspath(__file__))


class Scanner(Phone):
    """A phone that scanned the PC's code: its hello proves it has the token, without
    carrying the token itself."""

    def __init__(self, phone_id, name, token):
        super().__init__(phone_id, name)
        self.token = token

    def discover(self, proof=None):
        proof = proof or hmac.new(self.token.encode(), self.id, hashlib.sha256).digest()[:PROOF_SIZE]
        body = self.id + self.name.encode().ljust(NAME_SIZE, b"\0") + proof
        self.sock.sendto(HEADER.pack(MAGIC, VERSION, T_DISCOVER) + body, ("127.0.0.1", t.PORT))


def start(data):
    proc = subprocess.Popen([sys.executable, os.path.join(COMPANION, "wheel_companion.py"), "--port", str(t.PORT),
                             "--ui-port", str(t.UI_PORT), "--pair-port", str(t.PAIR_PORT), "--no-browser", "--quiet"],
                            stdout=subprocess.DEVNULL, env={**os.environ, "WHEEL_DATA": data})
    time.sleep(1.0)
    return proc


def state():
    return json.loads(page("/state")[1])


def post(path, body=None):
    return page(path, "POST", body=json.dumps(body or {}), headers={"X-Wheel": "1", "Content-Type": "application/json"})[0]


def pairing(query=""):
    """The link the pairing page opens Wheel with."""
    conn = http.client.HTTPConnection("127.0.0.1", t.PAIR_PORT, timeout=2)
    conn.request("GET", "/pair" + query)
    text = conn.getresponse().read().decode()
    conn.close()
    return text.split('href="intent://', 1)[1].split("#Intent", 1)[0]


def plays(phone, before, seconds=1.0):
    """Whether the phone gets a controller of its own while it keeps sending."""
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        hold([(phone, dict(steer=7777))], 0.1)
        if any(t.pad(i) is not None and t.pad(i).sThumbLX == 7777 for i in connected() - before):
            return True
    return False


def main():
    outside = connected()
    data = tempfile.mkdtemp(prefix="wheel-pairing-")
    proc = start(data)
    try:
        # A phone that hasn't been here before waits, and the page asks.
        a = Phone(b"\x11" * 8, "Asker")
        a.discover()
        check("a new phone gets no controller until it's allowed", not plays(a, outside))
        check("it's told it's waiting", any(s[2] == WAITING for s in a.statuses()))
        check("the page asks about it", [w["name"] for w in state()["waiting"]] == ["Asker"], state()["waiting"])

        post("/allow", {"id": "11" * 8})
        check("Allow gives it a controller", plays(a, outside, 2.0))
        hold([(a, dict())], 0.6)
        number = a.statuses()[-1][2]
        check("and a player number", 1 <= number <= 4, f"P{number}")
        check("the page counts it as allowed", state()["allowed"] == 1 and not state()["waiting"], state()["allowed"])

        # The code on screen: only a phone that came with its token gets it passed on.
        token = parse_qs(urlsplit(state()["pair"]).query)["t"][0]
        check("the pairing page keeps the token from phones that didn't scan", "t=" not in pairing())
        check("and from a wrong one", "t=" not in pairing("?t=0000"))
        check("and passes it on to a phone that scanned", f"t={token}" in pairing(f"?t={token}"))
        check("a link with an odd token doesn't trouble it", "t=" not in pairing("?t=%C3%A9%00"))

        # Overheard hellos don't help: the token itself, or noise, isn't the proof.
        before = connected()
        e = Scanner(b"\x55" * 8, "Eavesdropper", token)
        e.discover(proof=token.encode())
        hold([(e, dict())], 0.3)
        e.discover(proof=bytes(range(0x80, 0x90)))
        check("the token itself, or noise, doesn't let a phone in", not plays(e, before, 1.0))
        check("and the PC carries on after it", state()["waiting"] != [] and any(w["name"] == "Eavesdropper" for w in state()["waiting"]))

        before = connected()
        b = Scanner(b"\x22" * 8, "Scanner", token)
        b.discover()
        check("a phone that scanned the code joins without asking", plays(b, before, 2.0))

        # Block: no controller, the phone is told, and it stays that way.
        c = Phone(b"\x33" * 8, "Stranger")
        c.discover()
        hold([(c, dict())], 0.5)
        post("/block", {"id": "33" * 8})
        before = connected()
        check("a blocked phone gets no controller", not plays(c, before, 1.5))
        check("it's told so", any(s[2] == BLOCKED for s in c.statuses()))
        check("the page counts it as blocked", state()["blocked"] == 1, state()["blocked"])

        # Restarted, the PC still knows who's who.
        proc.terminate()
        proc.wait(5)
        proc = start(data)
        for phone in (a, c):
            phone.restart()
            phone.discover()
        check("an allowed phone is let straight back in after a restart", plays(a, outside, 2.0))
        hold([(c, dict())], 1.2)
        check("a blocked one is still blocked", any(s[2] == BLOCKED for s in c.statuses()))

        # Asking turned off: anyone joins, and is known from then on.
        post("/settings", {"askNew": False})
        time.sleep(0.3)
        before = connected()
        d = Phone(b"\x44" * 8, "Guest")
        d.discover()
        check("with asking off, a new phone joins straight away", plays(d, before, 2.0))
        time.sleep(0.3)  # the page's picture is redrawn every 0.1 s
        check("and is remembered", state()["allowed"] == 3, state()["allowed"])

        post("/forget-phones")
        post("/unblock-all")
        time.sleep(0.3)
        s = state()
        check("Forget all forgets them and makes a new code", s["allowed"] == 0 and f"t={token}" not in s["pair"], s["allowed"])
        check("Unblock all unblocks", s["blocked"] == 0, s["blocked"])
        saved = json.loads(open(os.path.join(data, "settings.json")).read())
        check("all of it is saved", saved["trusted"] == {} and saved["blocked"] == {} and saved["ask_new"] is False
              and saved["token"] != token, saved)
    finally:
        proc.terminate()
        proc.wait(5)
        shutil.rmtree(data, ignore_errors=True)
    print()
    print("all passed" if not t.failures else f"{len(t.failures)} failed: {', '.join(t.failures)}")
    sys.exit(1 if t.failures else 0)


if __name__ == "__main__":
    main()
