"""Wheel companion.

Phones send their steering, pedals and buttons over UDP. Each phone becomes its own
virtual Xbox 360 controller (ViGEmBus via vgamepad), up to four, so several people can
play together. Game rumble goes back to the phone it belongs to. A phone that hasn't
been on this PC before waits until it's allowed, unless it scanned the code on screen.

A small page at http://127.0.0.1:47801 shows the code to scan and who's playing.
app.py runs all this as a tray app; this file runs it in a terminal.

Run:  python wheel_companion.py               opens the page in the browser
      python wheel_companion.py --no-browser  serves the page without opening it
      python wheel_companion.py --no-ui       terminal only
"""

import argparse
import ctypes
import gc
import hashlib
import hmac
import json
import secrets
import socket
import struct
import sys
import threading
import time
import traceback
from urllib.parse import quote

import vgamepad as vg

import config

try:  # Windows: which player number each controller has, ours and others', as games see it
    xinput = ctypes.windll.xinput1_4
except (AttributeError, OSError):
    xinput = None

PORT = 47800
UI_PORT = 47801
# What phones open: the pairing page the QR code points at, and the app's download.
PAIR_PORT = 47802
MAGIC = b"RW"
VERSION = 1

# Phone -> PC
T_DISCOVER = 0x01
T_STATE = 0x02
T_BYE = 0x03
# PC -> phone
T_HELLO = 0x81
T_STATUS = 0x82
T_BUTTONS = 0x83  # which Xbox button each phone button presses (see button_codes)

HEADER = struct.Struct("<2sBB")
# magic, version, type, seq, steer = left stick X (-32767..32767), throttle, brake (0..65535), buttons
STATE = struct.Struct("<2sBBIhHHI")
# Newer phones add who they are and the rest of the sticks: phone id, left Y, right X, right Y.
# Older ones stop at STATE and are told apart by address.
STATE_EXTRA = struct.Struct("<8shhh")
# magic, version, type, large motor, small motor, player (1-4; 0 while Windows hasn't
# numbered it; or FULL, WAITING or BLOCKED for a phone without a controller)
STATUS = struct.Struct("<2sBBBBB")
# A discover after its header: the phone's id, its name (up to NAME_SIZE bytes), and from
# a phone that scanned this PC's code, the name padded to NAME_SIZE and then its proof.
ID_SIZE = 8
NAME_SIZE = 64
PROOF_SIZE = 16

# No packets for this long and every input lets go: a dropped link must never
# leave the car floored or the wheel turned.
TIMEOUT = 0.25
STATUS_EVERY = 0.25
# Gaps longer than this get logged: at 125 packets a second they should be about 8 ms.
SLOW_GAP = 0.1
# Windows' XInput sees four controllers; a fifth would be invisible to games.
MAX_PLAYERS = 4
# Sent instead of a player number to a phone without a controller: the PC is full, the
# phone is waiting to be allowed on this PC, or it was turned down.
FULL = 0xFF
WAITING = 0xFE
BLOCKED = 0xFD
# A phone turned away is forgotten once it's been quiet this long.
REFUSED_FOR = 60
# With auto_promote: how long Player 1 can be gone before someone else takes over.
HANDOVER = 15
# Right-stick values find_slot nudges a controller to, to see which place is ours: far
# inside any deadzone, and two of them, so a real stick resting on one can't pass.
SLOT_MARKS = (37, -29)
# The terminal's live status line.
STATUS_WIDTH = 110

# The phone's buttons, by bit, as named in mapping.json. New ones only go on the end.
# "start" and "back" are the phone's MENU and VIEW, named after the Xbox buttons they press.
BUTTONS = [
    "handbrake", "boost", "shift_up", "shift_down", "horn", "lights",
    "camera", "look_back", "reset", "start", "back",
    # The phone's menu controls: its D-pad and face buttons (and DriveView.xboxOf's fallback).
    "menu_up", "menu_down", "menu_left", "menu_right", "menu_a", "menu_b", "menu_x", "menu_y",
]

XB = vg.XUSB_BUTTON
# In this order, counting from 1, they're the codes in the buttons message (Xbox.kt on the phone).
XBOX_NAMES = {
    "A": XB.XUSB_GAMEPAD_A,
    "B": XB.XUSB_GAMEPAD_B,
    "X": XB.XUSB_GAMEPAD_X,
    "Y": XB.XUSB_GAMEPAD_Y,
    "LB": XB.XUSB_GAMEPAD_LEFT_SHOULDER,
    "RB": XB.XUSB_GAMEPAD_RIGHT_SHOULDER,
    "LS": XB.XUSB_GAMEPAD_LEFT_THUMB,
    "RS": XB.XUSB_GAMEPAD_RIGHT_THUMB,
    "START": XB.XUSB_GAMEPAD_START,
    "BACK": XB.XUSB_GAMEPAD_BACK,
    "GUIDE": XB.XUSB_GAMEPAD_GUIDE,
    "DPAD_UP": XB.XUSB_GAMEPAD_DPAD_UP,
    "DPAD_DOWN": XB.XUSB_GAMEPAD_DPAD_DOWN,
    "DPAD_LEFT": XB.XUSB_GAMEPAD_DPAD_LEFT,
    "DPAD_RIGHT": XB.XUSB_GAMEPAD_DPAD_RIGHT,
}

# Forza Horizon's defaults where it has them; edit mapping.json for other games.
DEFAULT_CONFIG = {
    "buttons": {
        "handbrake": "A",
        "shift_up": "B",
        "shift_down": "X",
        "reset": "Y",
        "boost": "LB",
        "camera": "RB",
        "horn": "LS",
        "look_back": "RS",
        "lights": "DPAD_UP",
        "start": "START",
        "back": "BACK",
        "menu_up": "DPAD_UP",
        "menu_down": "DPAD_DOWN",
        "menu_left": "DPAD_LEFT",
        "menu_right": "DPAD_RIGHT",
        "menu_a": "A",
        "menu_b": "B",
        "menu_x": "X",
        "menu_y": "Y",
    },
    # Lifts small steering past the game's own stick deadzone (0 to 0.3).
    "steer_anti_deadzone": 0.0,
}


class PortBusy(SystemExit):
    """The UDP port is taken: most likely Wheel is already running."""


def load_config():
    """mapping.json from the data folder, written with the defaults the first time. Buttons
    it leaves out (new ones, after an update) keep their defaults."""
    path = config.mapping_path()
    if not path.exists():
        path.write_text(json.dumps(DEFAULT_CONFIG, indent=2) + "\n", encoding="utf-8")
        return DEFAULT_CONFIG
    try:
        mapping = json.loads(path.read_text(encoding="utf-8"))
    except ValueError as error:
        sys.exit(f"{path} isn't valid JSON ({error}). Fix it, or delete it to start from the defaults.")
    merged = {**DEFAULT_CONFIG, **mapping}
    merged["buttons"] = {**DEFAULT_CONFIG["buttons"], **mapping.get("buttons", {})}
    return merged


def resolve_buttons(mapping):
    """Per phone button bit, the Xbox button it presses (a name in XBOX_NAMES), or None."""
    resolved = []
    for name in BUTTONS:
        target = (mapping["buttons"].get(name) or "").upper()
        if target and target not in XBOX_NAMES:
            sys.exit(f"{config.mapping_path()}: '{name}' maps to unknown button '{target}'. "
                     f"Use one of {', '.join(XBOX_NAMES)}.")
        resolved.append(target or None)
    return resolved


def button_masks(resolved):
    """One Xbox button mask per phone button bit (0 when unmapped)."""
    return [int(XBOX_NAMES[t]) if t else 0 for t in resolved]


def button_codes(resolved):
    """For the phone, so it can mark its buttons: per button bit, 1 + the Xbox button's
    place in XBOX_NAMES, or 0 when it presses nothing."""
    names = list(XBOX_NAMES)
    return bytes(names.index(t) + 1 if t else 0 for t in resolved)


def anti_deadzone(value, amount):
    if value == 0 or amount <= 0:
        return value
    sign = 1 if value > 0 else -1
    return sign * (amount + (1 - amount) * abs(value))


def seq_newer(seq, last):
    return ((seq - last) & 0xFFFFFFFF) < 0x80000000 and seq != last


def lan_address():
    """The address phones reach this PC on: the default route's, not WSL's or a VPN's."""
    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        probe.connect(("8.8.8.8", 80))  # picks a route; sends nothing
        return probe.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        probe.close()


class XInputState(ctypes.Structure):
    """XINPUT_STATE: a packet number, then the pad (buttons, triggers, the four stick axes)."""
    _fields_ = [("packet", ctypes.c_uint), ("buttons", ctypes.c_ushort), ("lt", ctypes.c_ubyte), ("rt", ctypes.c_ubyte),
                ("lx", ctypes.c_short), ("ly", ctypes.c_short), ("rx", ctypes.c_short), ("ry", ctypes.c_short)]


def xinput_right_y():
    """Every controller Windows has, by place (0 to 3): its right stick's Y."""
    if xinput is None:
        return {}
    state = XInputState()
    return {index: state.ry for index in range(4) if xinput.XInputGetState(index, ctypes.byref(state)) == 0}


def xinput_slots():
    """The player numbers, 1 to 4, Windows has a controller in: ours, or a real gamepad."""
    return {index + 1 for index in xinput_right_y()}


def keep_responsive():
    """Windows 11 slows background processes down to save power; a controller can't wait."""
    if sys.platform != "win32":
        return
    kernel32 = ctypes.windll.kernel32
    kernel32.GetCurrentProcess.restype = ctypes.c_void_p
    process = ctypes.c_void_p(kernel32.GetCurrentProcess())

    class Throttling(ctypes.Structure):
        _fields_ = [("Version", ctypes.c_ulong), ("ControlMask", ctypes.c_ulong), ("StateMask", ctypes.c_ulong)]

    # ProcessPowerThrottling: turn off execution-speed throttling and timer-resolution throttling.
    state = Throttling(1, 0x1 | 0x4, 0)
    kernel32.SetProcessInformation(process, 4, ctypes.byref(state), ctypes.sizeof(state))
    ctypes.windll.winmm.timeBeginPeriod(1)
    kernel32.SetPriorityClass(process, 0x8000)  # ABOVE_NORMAL_PRIORITY_CLASS


class Player:
    """One phone and the virtual controller it drives. id is the phone's id in hex, or
    "address-<ip>" for an older build of the app, which has none."""

    def __init__(self, companion, pid, name):
        self.companion = companion
        self.id = pid
        self.name = name
        self.addr = None
        self.live = False
        self.last_seq = 0
        self.last_packet = time.monotonic()
        self.last_arrival = 0.0
        self.last_status = 0.0
        self.slot = 0
        self.large = 0
        self.small = 0
        self.state = (0.0, 0.0, 0.0, 0)
        self.packets = 0
        self.rate = 0
        self.lost = 0
        self.shown_lost = 0
        self.worst_gap = 0.0
        self.shown_gap = 0.0
        self.pad = None
        self.next_look = 0.0  # when find_slot may next ask Windows
        self.plug()

    def plug(self):
        """A new virtual controller. Windows gives it the lowest free player number."""
        self.companion.slots_changed = self.plugged_at = time.monotonic()
        self.pad = vg.VX360Gamepad()
        self.slot = 0
        self.neutral()
        # Last: ViGEm can call on_rumble as soon as it's registered.
        self.pad.register_notification(callback_function=self.on_rumble)

    def unplug(self):
        self.pad.unregister_notification()
        self.pad.reset()
        self.pad.update()
        self.pad = None  # its __del__ unplugs the controller
        gc.collect()
        self.slot = 0
        self.companion.slots_changed = time.monotonic()

    @property
    def label(self):
        return f"P{self.slot or '?'} {self.name}"

    # ViGEm calls this from its own thread when the game sets this controller's rumble.
    def on_rumble(self, client, target, large_motor, small_motor, led_number, user_data):
        changed = (large_motor, small_motor) != (self.large, self.small)
        self.large, self.small = large_motor, small_motor
        if changed:
            self.companion.send_status(self)

    def find_slot(self, now_please=False):
        """The player number Windows gave this controller: what the game calls it.

        Asked of XInput, the way games see it: this controller's right stick (which
        phones don't use) is nudged by a hair, twice, and the place that moves both times
        is this one. ViGEm can also say, but after Windows has been full it gives a number
        that isn't the one games see."""
        if self.slot or self.pad is None:
            return
        if xinput is not None:
            now = time.monotonic()
            if not now_please and now < self.next_look:
                return
            # Asking about an empty place is slow, so not on every packet: often while Windows
            # is still numbering a new one, then now and again in case a place frees up.
            self.next_look = now + (0.1 if now - self.plugged_at < 3 else 0.5)
            report = self.pad.report
            kept = report.sThumbRY
            places = None
            for mark in SLOT_MARKS:
                report.sThumbRY = mark
                self.pad.update()
                time.sleep(0.004)
                moved = {index for index, ry in xinput_right_y().items() if ry == mark}
                places = moved if places is None else places & moved
            report.sThumbRY = kept
            self.pad.update()
            if len(places) == 1:
                self.slot = places.pop() + 1
            return
        # Elsewhere, players are numbered in the order they join.
        taken = {p.slot for p in self.companion.players.values()}
        self.slot = next(n for n in range(1, MAX_PLAYERS + 2) if n not in taken)

    def neutral(self):
        self.pad.reset()
        self.pad.update()
        self.state = (0.0, 0.0, 0.0, 0)

    def apply(self, steer, throttle, brake, buttons, ly=0, rx=0, ry=0):
        steer_f = anti_deadzone(max(-1.0, min(1.0, steer / 32767)), self.companion.anti_deadzone)
        throttle_f = throttle / 65535
        brake_f = brake / 65535
        report = self.pad.report
        report.sThumbLX = int(round(steer_f * 32767))
        report.sThumbLY = ly
        report.sThumbRX = rx
        report.sThumbRY = ry
        report.bRightTrigger = int(round(throttle_f * 255))
        report.bLeftTrigger = int(round(brake_f * 255))
        mask = 0
        for bit, xbox in enumerate(self.companion.masks):
            if buttons & (1 << bit):
                mask |= xbox
        report.wButtons = mask
        self.pad.update()
        self.state = (steer_f, throttle_f, brake_f, buttons)

    def close(self):
        if self.pad is not None:
            self.unplug()


class Companion:
    def __init__(self, mapping, settings=None, port=PORT, max_players=MAX_PLAYERS, quiet=False, pair_port=PAIR_PORT,
                 tray=False, name=None):
        """settings: as config.load() gives them; changes from the page are saved back there."""
        settings = {**config.DEFAULTS, **(settings or {})}
        resolved = resolve_buttons(mapping)
        self.masks = button_masks(resolved)
        self.codes = button_codes(resolved)
        self.pair_port = pair_port
        self.anti_deadzone = float(mapping["steer_anti_deadzone"])
        self.forget_after = settings["keep_minutes"] * 60
        self.auto_promote = settings["auto_promote"]
        self.ask_new = settings["ask_new"]
        self.trusted = dict(settings["trusted"])  # phone id -> name: allowed on this PC
        self.blocked = dict(settings["blocked"])  # phone id -> name: turned down
        # In the code to scan: a phone that scanned it can prove so, and is let in without asking.
        self.token = settings["token"] or secrets.token_hex(8)
        # When Player 1 was last being played; with auto_promote, someone else takes over after HANDOVER.
        self.first_seen = time.monotonic()
        self.tray = tray
        self.running = True
        # Called with each phone that gets a controller, and with the name of each that asks
        # to, from the companion's thread.
        self.on_join = None
        self.on_ask = None
        self.port = port
        self.max_players = max_players
        self.quiet = quiet
        self.name = name or socket.gethostname()  # what phones call this PC
        self.address = lan_address()
        self.address_checked = time.monotonic()
        self.players = {}       # phone id -> Player
        self.names = {}         # phone id -> its name, from discovery
        # phone id -> (name, address, when told, why): phones without a controller, and why
        # not (FULL, WAITING or BLOCKED)
        self.refused = {}
        self.requests = []      # from the page, handled on this thread
        self.handover_in = None  # seconds until auto_promote hands Player 1 over, while it's counting
        self.handover_to = None  # and to whom
        self.snapshot = {}      # what the page shows; replaced whole, never edited
        self.rate_mark = time.monotonic()
        # Player numbers held by controllers that aren't ours (a real gamepad, another app's).
        # XInput only has four, so they take places phones could have had.
        self.others = set()
        self.slots_changed = 0.0  # when one of ours last came or went
        self.slots_seen = (0.0, set())  # the last look at which numbers are taken, and when
        self.handover_failed = None  # (top, slots_changed) of a hand-over that didn't land
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            self.sock.bind(("0.0.0.0", port))
        except OSError:
            raise PortBusy(f"UDP port {port} is busy. Is Wheel already running?")
        self.sock.settimeout(0.02)
        threading.Thread(target=self.watch_slots, name="slots", daemon=True).start()
        if not settings["token"]:
            self.save_settings()

    @property
    def pair_url(self):
        """What the QR code holds. Phone cameras only open web links, so it's this PC's
        pairing page, which hands the phone on to Wheel with deep_link."""
        return f"http://{self.address}:{self.pair_port}/pair?t={self.token}"

    @property
    def get_url(self):
        """Where a phone without Wheel gets it."""
        return f"http://{self.address}:{self.pair_port}/get"

    @property
    def top(self):
        """The first player number a phone can have: 1, unless another controller holds it."""
        return min(set(range(1, MAX_PLAYERS + 1)) - self.others, default=1)

    @property
    def room(self):
        """How many phones can have a controller, with the others' places taken out."""
        return max(0, min(self.max_players, MAX_PLAYERS - len(self.others)))

    def watch_slots(self):
        # On its own thread: asking XInput about an empty place can take a while.
        while self.running:
            when = time.monotonic()
            self.slots_seen = (when, xinput_slots())
            time.sleep(1.0)

    def count_others(self):
        """Every taken number that isn't ours. Only from a look taken after ours settled,
        or one of ours coming or going would pass for someone else's."""
        when, taken = self.slots_seen
        ours = {p.slot for p in self.players.values() if p.pad is not None}
        if when > self.slots_changed + 0.3 and 0 not in ours:
            self.others = taken - ours

    def deep_link(self, scanned):
        """What the pairing page opens Wheel with. Only a phone that came from the code on
        this screen (scanned) gets the token."""
        link = f"wheel://pair?h={self.address}&p={self.port}&n={quote(self.name)}"
        return link + f"&t={self.token}" if scanned else link

    def scanned(self, token):
        """Whether token, from the pairing page's link, is the one in the code on screen."""
        return hmac.compare_digest(token.encode("utf-8"), self.token.encode("utf-8"))

    def proves_scan(self, phone, proof):
        """A phone that scanned the code shows it by its token keyed with its own id, so the
        token itself is never sent where anyone on the Wi-Fi could pick it up."""
        expected = hmac.new(self.token.encode("utf-8"), phone, hashlib.sha256).digest()[:PROOF_SIZE]
        return len(proof) == PROOF_SIZE and hmac.compare_digest(proof, expected)

    def trust(self, pid, name, why):
        self.trusted[pid] = name
        self.save_settings()
        log(f"{name} {why}: allowed on this PC")

    def send(self, packet, addr):
        try:
            self.sock.sendto(packet, addr)
        except OSError:
            pass  # a phone that's gone; it'll be noticed by its silence

    def send_status(self, player):
        if player.addr is None:
            return
        self.send(STATUS.pack(MAGIC, VERSION, T_STATUS, player.large, player.small, player.slot), player.addr)
        player.last_status = time.monotonic()

    def handle(self, data, addr):
        if len(data) < HEADER.size:
            return
        magic, version, kind = HEADER.unpack_from(data)
        if magic != MAGIC or version != VERSION:
            return
        if kind == T_DISCOVER:
            return self.discovered(data, addr)
        if kind == T_BYE:
            for player in self.players.values():
                if player.addr == addr and player.live:
                    self.release(player, "left")
            return
        if kind != T_STATE or len(data) < STATE.size:
            return

        _, _, _, seq, steer, throttle, brake, buttons = STATE.unpack_from(data)
        ly = rx = ry = 0
        if len(data) >= STATE.size + STATE_EXTRA.size:
            phone, ly, rx, ry = STATE_EXTRA.unpack_from(data, STATE.size)
            pid = phone.hex()
        else:
            pid = f"address-{addr[0]}"  # an older build of the app: one phone per address

        player = self.players.get(pid)
        if player is None:
            if pid in self.blocked:
                return self.refuse(pid, addr, BLOCKED)
            if self.ask_new and pid not in self.trusted:
                return self.refuse(pid, addr, WAITING)
            if len(self.players) >= self.room:
                return self.refuse(pid, addr, FULL)
            self.refused.pop(pid, None)
            default = f"Phone at {addr[0]}" if pid.startswith("address-") else "Phone"
            player = Player(self, pid, self.names.get(pid, default))
            self.players[pid] = player
            if pid not in self.trusted:
                # Let in while nobody asks first: known from now on, if asking is turned on later.
                self.trust(pid, player.name, "joined")
            if self.on_join:
                self.on_join(player)

        now = time.monotonic()
        arrival = time.perf_counter()
        if addr != player.addr:
            # A new socket from this phone, or its app started over: count from here.
            again = player.addr is not None
            player.addr = addr
            player.last_seq = seq
            player.live = True
            log(f"{player.label} {'reconnected' if again else 'connected'} from {addr[0]}")
        elif not seq_newer(seq, player.last_seq):
            return  # late or duplicate packet
        else:
            if not player.live:
                player.live = True
                log(f"{player.label} connected from {addr[0]}")
            else:
                # perf_counter: monotonic() only ticks every ~16 ms on Windows.
                gap = arrival - player.last_arrival
                player.worst_gap = max(player.worst_gap, gap)
                if gap > SLOW_GAP:
                    log(f"{player.label} slow link: no packets for {gap * 1000:.0f} ms")
            # Sequence numbers the phone sent that never arrived.
            player.lost += ((seq - player.last_seq) & 0xFFFFFFFF) - 1
        player.last_seq = seq
        player.last_packet = now
        player.last_arrival = arrival
        player.packets += 1
        player.apply(steer, throttle, brake, buttons, ly, rx, ry)

    def discovered(self, data, addr):
        """A phone looking for PCs, or saying who it is again: answered with this PC's name
        and the Xbox button each of the phone's buttons presses here."""
        self.send(HEADER.pack(MAGIC, VERSION, T_HELLO) + self.name.encode("utf-8")[:NAME_SIZE], addr)
        self.send(HEADER.pack(MAGIC, VERSION, T_BUTTONS) + self.codes, addr)
        body = data[HEADER.size:]
        if len(body) < ID_SIZE:
            return  # an older build of the app says nothing about itself
        phone = bytes(body[:ID_SIZE])
        pid = phone.hex()
        name = body[ID_SIZE:ID_SIZE + NAME_SIZE].split(b"\0")[0].decode("utf-8", "replace").strip()
        if name:
            self.names[pid] = name
            if pid in self.players:
                self.players[pid].name = name
        proof = bytes(body[ID_SIZE + NAME_SIZE:ID_SIZE + NAME_SIZE + PROOF_SIZE])
        if proof and pid not in self.trusted and pid not in self.blocked and self.proves_scan(phone, proof):
            self.trust(pid, name or "Phone", "scanned the code")

    def refuse(self, pid, addr, why):
        """A phone that gets no controller: the PC is full (FULL), it hasn't been allowed
        yet (WAITING), or it was turned down (BLOCKED). The page lists it, and the phone is
        told about once a second, so it can say so instead of looking connected."""
        now = time.monotonic()
        before = self.refused.get(pid)
        name = self.names.get(pid, f"Phone at {addr[0]}")
        fresh = before is None or before[3] != why
        if fresh:
            if why == FULL:
                log(f"turned away {name}: no controller left for it")
            elif why == WAITING:
                log(f"{name} wants to join: allow it in Wheel's window")
                if self.on_ask:
                    self.on_ask(name)
        # Its packets come 125 times a second, so the time kept is the last telling, not the last packet.
        if fresh or now - before[2] > 1.0:
            self.refused[pid] = (name, addr[0], now, why)
            self.send(STATUS.pack(MAGIC, VERSION, T_STATUS, 0, 0, why), addr)

    def promote(self, target):
        """Makes target Player 1 (or the first after any controller that isn't a phone).
        Windows never renumbers a plugged-in controller, so target and everyone before it
        are unplugged, and target is plugged back in first, taking the lowest number."""
        if target.pad is None or not target.slot or target.slot == self.top:
            return
        others = [p for p in self.players.values() if p is not target and p.pad is not None and p.slot and p.slot < target.slot]
        for player in [target, *others]:
            player.unplug()
        target.plug()
        self.wait_for_slot(target)
        for player in others:
            player.plug()
            self.wait_for_slot(player)
        now = time.monotonic()
        for player in self.players.values():
            if player.live:
                # The swap held this loop up; that wasn't the phones going quiet.
                player.last_packet = now
        for player in [target, *others]:
            if player.live:
                self.send_status(player)
        self.first_seen = now
        log(f"{target.name} is Player {target.slot or '?'} now")

    @staticmethod
    def wait_for_slot(player, timeout=1.5):
        """Windows numbers a new controller a moment after it's plugged in."""
        end = time.monotonic() + timeout
        while player.slot == 0 and time.monotonic() < end:
            player.find_slot(now_please=True)
            if player.slot == 0:
                time.sleep(0.02)

    def handover(self, now):
        """With auto_promote on: once nobody has played as Player 1 for HANDOVER seconds,
        the lowest-numbered phone still playing becomes Player 1. Returns (seconds left, who)
        while it's counting down, so the page can say so; (None, None) otherwise.
        Player 1 here is self.top: a real gamepad that's Player 1 stays Player 1."""
        top = self.top
        holder = next((p for p in self.players.values() if p.pad is not None and p.slot == top), None)
        live = sorted((p for p in self.players.values() if p.live and p.slot), key=lambda p: p.slot)
        # A hand-over that didn't land isn't tried again until someone comes or goes.
        if not self.auto_promote or (holder is not None and holder.live) or not live \
                or self.handover_failed == (top, self.slots_changed):
            self.first_seen = now
            return None, None
        left = HANDOVER - (now - self.first_seen)
        if left <= 0:
            target = live[0]
            self.promote(target)
            if target.slot != self.top:
                self.handover_failed = (self.top, self.slots_changed)
                log(f"couldn't make {target.name} Player {self.top}: Windows gave it {target.slot or 'no number'}")
            return None, None
        return left, live[0]

    def release(self, player, reason):
        player.neutral()
        player.live = False
        log(f"{player.label} {reason}: inputs released")

    def forget(self, player, reason):
        if player.live:
            self.release(player, reason)
        label = player.label  # before unplugging, which gives up its number
        player.close()
        del self.players[player.id]
        log(f"{label} {reason}: controller unplugged")

    def handle_request(self, action, target):
        """One thing asked for on the page; target is a phone's id, or the settings changed."""
        if action == "settings":
            return self.apply_settings(target)
        if action == "forget_phones":
            # Everyone asks again, and the code on screen changes, so an old photo of it stops working.
            self.trusted.clear()
            self.token = secrets.token_hex(8)
            self.save_settings()
            return log("allowed phones forgotten; the code to scan is new")
        if action == "unblock_all":
            self.blocked.clear()
            self.save_settings()
            return log("blocked phones unblocked")
        if action in ("allow", "block"):
            name = self.refused.pop(target, (self.names.get(target, "Phone"),))[0]
            if action == "allow":
                self.trust(target, name, "was allowed")
            else:
                self.blocked[target] = name
                self.save_settings()
                log(f"{name} blocked")
            return
        player = self.players.get(target)
        if player and action == "forget":
            self.forget(player, "removed")
        elif player and action == "promote":
            self.promote(player)

    def run(self):
        log(f"listening on UDP {self.port} as '{self.name}' at {self.address}. Open Wheel on the phone.")
        last_print = 0.0
        while self.running:
            try:
                self.turn()
            except Exception:
                # One odd packet or request mustn't take every controller down with it.
                log("skipped a turn after an error:\n" + traceback.format_exc().rstrip())
            now = time.monotonic()
            if now - last_print > 0.1:
                last_print = now
                self.publish(now)
                if not self.quiet:
                    self.print_status()

    def turn(self):
        """One go round: a packet if one came, the page's requests, then the timers."""
        try:
            data, addr = self.sock.recvfrom(256)
            self.handle(data, addr)
        except socket.timeout:
            pass
        except ConnectionResetError:
            # Windows reports an ICMP "port unreachable" from an earlier send here.
            pass
        now = time.monotonic()
        while self.requests:
            self.handle_request(*self.requests.pop(0))
        self.handover_in, self.handover_to = self.handover(now)
        for player in list(self.players.values()):
            if player.live and now - player.last_packet > TIMEOUT:
                self.release(player, "went quiet")
            if player.live and now - player.last_status > STATUS_EVERY:
                self.send_status(player)
            if not player.live and now - player.last_packet > self.forget_after:
                self.forget(player, f"gone {self.forget_after / 60:g} minutes")
            if player.slot == 0:
                # Also catches one plugged in while all four places were taken: Windows
                # numbers it once a place frees up.
                player.find_slot()
        for pid, (_, _, when, _) in list(self.refused.items()):
            if now - when > REFUSED_FOR:
                del self.refused[pid]
        if now - self.address_checked > 5:
            # Started before the Wi-Fi was up, or moved networks: the code to scan follows.
            self.address_checked = now
            address = lan_address()
            if address != self.address:
                log(f"this PC is at {address} now")
                self.address = address
        if now - self.rate_mark >= 1.0:
            self.count_others()
            for player in self.players.values():
                player.rate, player.packets = player.packets, 0
                player.shown_gap, player.worst_gap = player.worst_gap, 0.0
                player.shown_lost, player.lost = player.lost, 0
            self.rate_mark = now

    def publish(self, now):
        """A fresh picture for the page, swapped in whole so its thread never sees half of one."""
        players = []
        for p in sorted(self.players.values(), key=lambda p: p.slot or 99):
            steer, throttle, brake, buttons = p.state
            players.append({
                "id": p.id,
                "slot": p.slot,
                "name": p.name,
                "live": p.live,
                "away": 0 if p.live else round(now - p.last_packet),
                "steer": round(steer, 3),
                "gas": round(throttle, 3),
                "brake": round(brake, 3),
                "held": [n for i, n in enumerate(BUTTONS) if buttons & (1 << i)],
                "rate": p.rate,
                "gap": round(p.shown_gap * 1000),
                "lost": p.shown_lost,
                "rumble": max(p.large, p.small),
            })
        self.snapshot = {
            "pc": self.name,
            "address": self.address,
            "port": self.port,
            "pair": self.pair_url,
            "get": self.get_url,
            "places": MAX_PLAYERS,
            "max": self.max_players,
            "room": self.room,
            "others": sorted(self.others),
            "top": self.top,
            "keepMinutes": int(self.forget_after / 60),
            "autoPromote": self.auto_promote,
            "handoverAfter": HANDOVER,
            "handoverIn": None if self.handover_in is None else round(self.handover_in),
            "handoverTo": None if self.handover_to is None else self.handover_to.id,
            "tray": self.tray,
            "players": players,
            "turnedAway": [{"name": n, "address": a} for n, a, _, why in self.refused.values() if why == FULL],
            "waiting": [{"id": pid, "name": n, "address": a}
                        for pid, (n, a, _, why) in self.refused.items() if why == WAITING],
            "askNew": self.ask_new,
            "allowed": len(self.trusted),
            "blocked": len(self.blocked),
        }

    def print_status(self):
        if not self.players:
            line = "waiting for phones..."
        else:
            parts = []
            for p in sorted(self.players.values(), key=lambda p: p.slot or 99):
                if not p.live:
                    parts.append(f"P{p.slot or '?'} away")
                    continue
                steer, throttle, brake, buttons = p.state
                held = ",".join(n for i, n in enumerate(BUTTONS) if buttons & (1 << i)) or "-"
                parts.append(f"P{p.slot or '?'} steer {steer:+.2f} gas {throttle:.2f} brake {brake:.2f} "
                             f"{p.rate:3d}/s gap {p.shown_gap * 1000:3.0f}ms lost {p.shown_lost:3d} "
                             f"rumble {p.large:3d}/{p.small:3d} {held}")
            line = " | ".join(parts)
        sys.stdout.write("\r" + line.ljust(STATUS_WIDTH))
        sys.stdout.flush()

    def apply_settings(self, changes):
        if "keepMinutes" in changes:
            self.forget_after = max(1, int(changes["keepMinutes"])) * 60
        if "autoPromote" in changes:
            self.auto_promote = bool(changes["autoPromote"])
            self.first_seen = time.monotonic()
        if "askNew" in changes:
            self.ask_new = bool(changes["askNew"])
        self.save_settings()
        log(f"settings: keep controllers {self.forget_after / 60:g} min, "
            f"hand Player 1 over {'on' if self.auto_promote else 'off'}, "
            f"ask before new phones {'on' if self.ask_new else 'off'}")

    def save_settings(self):
        config.save({"keep_minutes": int(self.forget_after / 60), "auto_promote": self.auto_promote,
                     "ask_new": self.ask_new, "trusted": self.trusted, "blocked": self.blocked, "token": self.token})

    def stop(self):
        """From another thread: the loop finishes its turn, and its caller then calls close()."""
        self.running = False

    def close(self):
        for player in list(self.players.values()):
            player.close()
        self.players.clear()
        self.sock.close()


def log(message):
    sys.stdout.write("\r" + " " * STATUS_WIDTH + "\r" + time.strftime("%H:%M:%S ") + message + "\n")
    sys.stdout.flush()


def main():
    parser = argparse.ArgumentParser(description="Turns phones running Wheel into Xbox controllers.")
    parser.add_argument("--port", type=int, default=PORT, help="UDP port the phones send to")
    parser.add_argument("--ui-port", type=int, default=UI_PORT, help="port for the page on this PC")
    parser.add_argument("--pair-port", type=int, default=PAIR_PORT, help="port for the page the QR code opens on the phone")
    parser.add_argument("--no-ui", action="store_true", help="don't serve the page")
    parser.add_argument("--no-browser", action="store_true", help="serve the page but don't open it")
    parser.add_argument("--max-players", type=int, default=MAX_PLAYERS, help="take fewer than four phones")
    parser.add_argument("--name", help="what phones call this PC (its computer name by default)")
    parser.add_argument("--quiet", action="store_true", help="no live status line")
    args = parser.parse_args()

    keep_responsive()
    companion = Companion(load_config(), config.load(), port=args.port, max_players=args.max_players,
                          quiet=args.quiet, pair_port=args.pair_port, name=args.name)
    if not args.no_ui:
        import ui
        if not ui.serve_pairing(companion):
            log(f"port {args.pair_port} is busy: the code to scan won't open on phones")
        url = ui.serve(companion, args.ui_port, open_browser=not args.no_browser)
        if url is None:
            log(f"port {args.ui_port} is busy: no page this time")
        else:
            log(f"page at {url}")
    try:
        companion.run()
    except KeyboardInterrupt:
        pass
    finally:
        companion.close()
        log("stopped")


if __name__ == "__main__":
    main()
