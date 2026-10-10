"""Windows desktop pieces: where the app lives, its window, starting with Windows, the
controller driver and the firewall. Another platform needs a sibling of this file, plus
its own take on the few Windows calls in wheel_companion.py (keep_responsive, XInput).
"""

import ctypes
import os
import shutil
import subprocess
import sys
import time
import webbrowser
import winreg
from ctypes import wintypes
from pathlib import Path

APP = "Wheel"
RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
FIREWALL_RULE = "Wheel"
INSTALL_DIR = Path(os.environ.get("LOCALAPPDATA") or Path.home() / "AppData" / "Local") / "Programs" / APP
NO_WINDOW = 0x08000000  # CREATE_NO_WINDOW: helpers run without flashing a console


def frozen() -> bool:
    """Running as Wheel.exe rather than from the Python source."""
    return getattr(sys, "frozen", False)


def command(*extra: str) -> list[str]:
    """How to start this app again, for Start with Windows and the Start menu."""
    if frozen():
        return [sys.executable, *extra]
    pythonw = Path(sys.executable).with_name("pythonw.exe")
    return [str(pythonw if pythonw.exists() else sys.executable), str(Path(__file__).with_name("app.py")), *extra]


# ---------- installing itself ----------

def installed_exe() -> Path:
    return INSTALL_DIR / f"{APP}.exe"


def needs_install() -> bool:
    """Wheel.exe run from Downloads or a USB stick copies itself somewhere permanent first."""
    return frozen() and Path(sys.executable).resolve() != installed_exe().resolve()


def install() -> Path:
    """Copies this exe to %LOCALAPPDATA%\\Programs\\Wheel and adds it to the Start menu. No admin.
    Raises PermissionError if the copy there is still running and holding the file."""
    INSTALL_DIR.mkdir(parents=True, exist_ok=True)
    # A Wheel that was just asked to quit takes a moment to let go of its file.
    for attempt in range(40):
        try:
            shutil.copy2(sys.executable, installed_exe())
            break
        except PermissionError:
            if attempt == 39:
                raise
            time.sleep(0.25)
    shortcut(installed_exe())
    if autostart_enabled():
        set_autostart(True)  # point it at the new copy
    return installed_exe()


def shortcut(target: Path) -> None:
    programs = Path(os.environ["APPDATA"]) / "Microsoft" / "Windows" / "Start Menu" / "Programs"
    link = programs / f"{APP}.lnk"

    def quoted(path):  # a PowerShell string: an apostrophe in a user name is doubled
        return "'" + str(path).replace("'", "''") + "'"

    script = (f"$s=(New-Object -ComObject WScript.Shell).CreateShortcut({quoted(link)});"
              f"$s.TargetPath={quoted(target)};$s.WorkingDirectory={quoted(target.parent)};"
              f"$s.Description='Your phone as a racing wheel';$s.Save()")
    subprocess.run(["powershell", "-NoProfile", "-Command", script], creationflags=NO_WINDOW, check=False)


# ---------- starting with Windows ----------

def autostart_enabled() -> bool:
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as key:
            winreg.QueryValueEx(key, APP)
            return True
    except OSError:
        return False


def set_autostart(on: bool) -> None:
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as key:
        if on:
            # Started with Windows, it waits in the tray instead of opening its window.
            winreg.SetValueEx(key, APP, 0, winreg.REG_SZ, subprocess.list2cmdline(command("--hidden")))
        else:
            try:
                winreg.DeleteValue(key, APP)
            except OSError:
                pass


# ---------- the window ----------

def edge() -> str | None:
    for base in (os.environ.get("ProgramFiles(x86)"), os.environ.get("ProgramFiles"), os.environ.get("LOCALAPPDATA")):
        if base:
            path = Path(base) / "Microsoft" / "Edge" / "Application" / "msedge.exe"
            if path.exists():
                return str(path)
    return None


def open_window(url: str) -> None:
    """The dashboard in a window of its own (Edge's app mode), or a browser tab without Edge."""
    browser = edge()
    if browser:
        subprocess.Popen([browser, f"--app={url}", "--window-size=1120,780"], creationflags=NO_WINDOW)
    else:
        webbrowser.open(url)


# ---------- the driver and the firewall ----------

def driver_installed() -> bool:
    try:
        winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, r"SYSTEM\CurrentControlSet\Services\ViGEmBus").Close()
        return True
    except OSError:
        return False


def driver_installer() -> Path | None:
    """The ViGEmBus installer that ships inside vgamepad."""
    try:
        import vgamepad
    except ImportError:
        return None
    path = Path(vgamepad.__file__).parent / "win" / "vigem" / "install" / "x64" / "ViGEmBusSetup_x64.msi"
    return path if path.exists() else None


class _ShellExecuteInfo(ctypes.Structure):
    _fields_ = [("cbSize", wintypes.DWORD), ("fMask", ctypes.c_ulong), ("hwnd", wintypes.HWND),
                ("lpVerb", wintypes.LPCWSTR), ("lpFile", wintypes.LPCWSTR), ("lpParameters", wintypes.LPCWSTR),
                ("lpDirectory", wintypes.LPCWSTR), ("nShow", ctypes.c_int), ("hInstApp", wintypes.HINSTANCE),
                ("lpIDList", ctypes.c_void_p), ("lpClass", wintypes.LPCWSTR), ("hkeyClass", wintypes.HKEY),
                ("dwHotKey", wintypes.DWORD), ("hIconOrMonitor", wintypes.HANDLE), ("hProcess", wintypes.HANDLE)]


def as_admin(program: str, arguments: str) -> int | None:
    """Runs a program as administrator after Windows' own prompt, and waits for it.
    Its exit code, or None if the prompt was declined."""
    info = _ShellExecuteInfo(cbSize=ctypes.sizeof(_ShellExecuteInfo), fMask=0x40,  # SEE_MASK_NOCLOSEPROCESS
                             lpVerb="runas", lpFile=program, lpParameters=arguments, nShow=0)
    if not ctypes.windll.shell32.ShellExecuteExW(ctypes.byref(info)):
        return None
    kernel32 = ctypes.windll.kernel32
    kernel32.WaitForSingleObject(info.hProcess, 0xFFFFFFFF)
    code = wintypes.DWORD()
    kernel32.GetExitCodeProcess(info.hProcess, ctypes.byref(code))
    kernel32.CloseHandle(info.hProcess)
    return code.value


def install_driver() -> bool:
    msi = driver_installer()
    if msi is None:
        return False
    as_admin("msiexec.exe", f'/i "{msi}" /passive /norestart')
    return driver_installed()


def firewall_ready() -> bool:
    """Phones reach this app over Wi-Fi; Windows blocks that until it's allowed."""
    if not frozen():
        return True  # Python itself is what's allowed when run from source
    result = subprocess.run(["netsh", "advfirewall", "firewall", "show", "rule", f"name={FIREWALL_RULE}"],
                            capture_output=True, creationflags=NO_WINDOW, check=False)
    return result.returncode == 0


def allow_firewall() -> bool:
    as_admin("netsh.exe", f'advfirewall firewall add rule name="{FIREWALL_RULE}" dir=in action=allow '
                          f'program="{sys.executable}" enable=yes profile=private,domain')
    return firewall_ready()


# ---------- talking to the person ----------

def ask(title: str, text: str) -> bool:
    MB_YESNO, MB_ICONQUESTION, IDYES = 0x04, 0x20, 6
    return ctypes.windll.user32.MessageBoxW(None, text, title, MB_YESNO | MB_ICONQUESTION) == IDYES


def tell(title: str, text: str) -> None:
    ctypes.windll.user32.MessageBoxW(None, text, title, 0x40)  # MB_ICONINFORMATION
