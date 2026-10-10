"""Where Wheel for PC keeps its things, and what ships inside it.

Settings, the button mapping and the log live in %APPDATA%\\Wheel on Windows (and
~/.config/Wheel elsewhere), so they survive updates and don't depend on where the app
was run from. Files that ship with the app (the page and the Android app) are found
next to this file, or inside the packaged exe.
"""

import json
import os
import sys
from pathlib import Path

DEFAULTS = {
    # How long a phone that's gone keeps its controller, so a game doesn't drop the player
    # from the lobby over a dropout.
    "keep_minutes": 10,
    # Single-player games only listen to Player 1: hand it to whoever's still playing.
    "auto_promote": False,
    # Ask on this PC before a phone that hasn't been here before gets a controller.
    "ask_new": True,
    # Phones allowed on this PC, and phones turned down: id -> name.
    "trusted": {},
    "blocked": {},
    # In the code to scan, so a phone that scanned it is let in without asking. Made on first run.
    "token": "",
}

# The Android app's builds, newest kind first: the signed release, then a debug build.
APP_BUILDS = [Path(__file__).resolve().parent.parent / "app" / "build" / "outputs" / "apk" / kind / f"app-{kind}.apk"
              for kind in ("release", "debug")]


def data_dir() -> Path:
    if os.environ.get("WHEEL_DATA"):  # tests use a folder of their own
        folder = Path(os.environ["WHEEL_DATA"])
    elif sys.platform == "win32":
        folder = Path(os.environ.get("APPDATA") or Path.home() / "AppData" / "Roaming") / "Wheel"
    else:
        folder = Path(os.environ.get("XDG_CONFIG_HOME") or Path.home() / ".config") / "Wheel"
    folder.mkdir(parents=True, exist_ok=True)
    return folder


def resource(name: str) -> Path:
    """A file that ships with the app: inside the packaged exe, or next to the source."""
    base = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent))
    return base / name


def app_build() -> Path | None:
    """The Android app to offer phones: the copy shipped inside Wheel.exe, or from source,
    the newest build."""
    return next((p for p in [resource("Wheel.apk"), *APP_BUILDS] if p.is_file()), None)


def mapping_path() -> Path:
    return data_dir() / "mapping.json"


def settings_path() -> Path:
    return data_dir() / "settings.json"


def load() -> dict:
    try:
        saved = json.loads(settings_path().read_text(encoding="utf-8"))
    except (OSError, ValueError):
        saved = {}
    return {**DEFAULTS, **{k: v for k, v in saved.items() if k in DEFAULTS}}


def save(settings: dict) -> None:
    settings_path().write_text(json.dumps({k: settings[k] for k in DEFAULTS if k in settings}, indent=2) + "\n", encoding="utf-8")
