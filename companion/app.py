"""Wheel for PC, as a tray app: no terminal, a window of its own, starts with Windows.

Run:  python app.py            or Wheel.exe
      python app.py --hidden   waits in the tray (how Start with Windows runs it)

The first run of Wheel.exe from anywhere else (Downloads, say) copies it to
%LOCALAPPDATA%\\Programs\\Wheel, adds it to the Start menu, and runs that copy.
"""

import subprocess
import sys
import threading
import time
import urllib.request

import pystray
from PIL import Image, ImageDraw

import config
import desktop
import ui
from wheel_companion import PAIR_PORT, UI_PORT, Companion, PortBusy, keep_responsive, load_config


def icon_image(size: int = 64) -> Image.Image:
    """A white wheel on a dark tile, so it shows on light and dark taskbars alike."""
    scale = 4  # drawn large and shrunk, for smooth edges
    s = size * scale
    image = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    draw.rounded_rectangle([0, 0, s - 1, s - 1], radius=s * 0.22, fill=(17, 17, 19, 255))
    rim = s * 0.085
    draw.ellipse([s * 0.15, s * 0.15, s * 0.85, s * 0.85], outline="white", width=round(rim))
    draw.line([s * 0.2, s * 0.53, s * 0.8, s * 0.53], fill="white", width=round(rim * 0.85))
    draw.line([s * 0.5, s * 0.53, s * 0.5, s * 0.8], fill="white", width=round(rim * 0.85))
    draw.ellipse([s * 0.41, s * 0.44, s * 0.59, s * 0.62], fill="white")
    return image.resize((size, size), Image.LANCZOS)


def log_to_file() -> None:
    """A tray app has no console: what the companion says goes to %APPDATA%\\Wheel\\wheel.log."""
    path = config.data_dir() / "wheel.log"
    if path.exists() and path.stat().st_size > 2_000_000:
        path.replace(path.with_name("wheel.log.1"))
    stream = open(path, "a", encoding="utf-8", buffering=1)
    sys.stdout = sys.stderr = stream


def first_run_checks() -> bool:
    """The driver it can't work without, and the firewall rule phones need. False to give up."""
    if not desktop.driver_installed():
        if not desktop.ask("Wheel", "Wheel needs the ViGEmBus driver so your phone can show up as an "
                                    "Xbox controller.\n\nInstall it now? Windows will ask for permission."):
            return False
        if not desktop.install_driver():
            desktop.tell("Wheel", "The driver didn't install, so Wheel can't start. Open Wheel again to retry.")
            return False
    if not desktop.firewall_ready():
        if desktop.ask("Wheel", "Allow phones on your Wi-Fi to connect to Wheel?\n\n"
                                "Windows will ask for permission. Without it, phones can't reach this PC."):
            desktop.allow_firewall()
    return True


def quit_running() -> None:
    """Windows won't replace an exe that's running: a newer Wheel asks the old one to close first."""
    request = urllib.request.Request(f"http://127.0.0.1:{UI_PORT}/quit", method="POST", headers={"X-Wheel": "1"})
    try:
        urllib.request.urlopen(request, timeout=2).close()
    except OSError:
        pass  # nothing running, or too old to be asked


def main() -> None:
    hidden = "--hidden" in sys.argv
    if desktop.needs_install():
        quit_running()
        try:
            exe = desktop.install()
        except PermissionError:
            desktop.tell("Wheel", "Wheel is still running, so it can't be updated. "
                                  "Quit it from its icon by the clock, then open this again.")
            return
        subprocess.Popen([str(exe), *sys.argv[1:]])
        return

    log_to_file()
    keep_responsive()
    if not first_run_checks():
        return
    try:
        companion = Companion(load_config(), config.load(), quiet=True, tray=True)
    except PortBusy:
        # Already running: show that one instead of starting a second.
        desktop.open_window(f"http://127.0.0.1:{UI_PORT}/")
        return
    except SystemExit as stop:
        desktop.tell("Wheel", str(stop))  # a mapping.json that doesn't make sense, say
        return

    if not ui.serve_pairing(companion):
        print(f"port {PAIR_PORT} is busy: the code to scan won't open on phones", flush=True)
    url = ui.serve(companion, UI_PORT, open_browser=False, on_quit=lambda: quit_app())
    if url is None:
        companion.stop()
        companion.close()
        desktop.tell("Wheel", f"Something else on this PC is using port {UI_PORT}, which Wheel's window needs.")
        return
    icon = pystray.Icon("Wheel", icon_image(), "Wheel")

    def notify(message):
        try:
            icon.notify(message, "Wheel")
        except Exception:
            pass  # notifications are a nicety

    companion.on_join = lambda player: notify(f"{player.name} connected")
    # Not the window itself: it could come up over a game. The notification says where to go.
    companion.on_ask = lambda name: notify(f"{name} wants to join. Open Wheel to allow it.")
    worker = threading.Thread(target=run, args=(companion,), name="controllers", daemon=True)
    worker.start()

    def open_window(*_):
        desktop.open_window(url)

    def toggle_autostart(*_):
        desktop.set_autostart(not desktop.autostart_enabled())

    def quit_app(*_):
        companion.stop()
        worker.join(3)
        icon.stop()

    icon.menu = pystray.Menu(
        pystray.MenuItem("Open Wheel", open_window, default=True),
        pystray.MenuItem("Start with Windows", toggle_autostart, checked=lambda _: desktop.autostart_enabled()),
        pystray.Menu.SEPARATOR,
        pystray.MenuItem("Quit Wheel", quit_app),
    )

    def tooltip():
        # The tray icon says who's playing when you hover it. From the page's picture, which
        # is swapped in whole: the players themselves change on the controllers' thread.
        while companion.running:
            live = [p for p in companion.snapshot.get("players", []) if p["live"]]
            icon.title = "Wheel" if not live else f"Wheel · {len(live)} playing"
            time.sleep(2)

    threading.Thread(target=tooltip, name="tooltip", daemon=True).start()
    if not hidden:
        open_window()
    icon.run()


def run(companion: Companion) -> None:
    try:
        companion.run()
    finally:
        companion.close()


if __name__ == "__main__":
    main()
