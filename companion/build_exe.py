"""Builds Wheel.exe: the companion as a tray app, with its page, the Android app and the
controller driver's installer inside, so a PC needs nothing else.

Run:  python build_exe.py             (after ./gradlew assembleRelease, so the newest app goes in)
      python build_exe.py --publish   also puts it on the website, as site/download/Wheel.exe
Out:  companion/dist/Wheel.exe
"""

import os
import shutil
import subprocess
import sys
from pathlib import Path

import config

HERE = Path(__file__).resolve().parent


def main() -> None:
    work = HERE / "build"
    work.mkdir(exist_ok=True)
    apk = next((p for p in config.APP_BUILDS if p.is_file()), None)
    if apk is None:
        sys.exit("Build the Android app first: ./gradlew assembleRelease")
    if "release" not in apk.name:
        print("No release build of the app: shipping the debug one, which a Play install can't update.")
    shutil.copy2(apk, work / "Wheel.apk")

    from app import icon_image
    icon_image(256).save(work / "wheel.ico", sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])

    data = lambda path: f"{path}{os.pathsep}."
    subprocess.run([
        sys.executable, "-m", "PyInstaller", "--noconfirm", "--clean", "--onefile", "--noconsole", "--noupx",
        "--name", "Wheel", "--icon", str(work / "wheel.ico"),
        "--add-data", data(HERE / "page.html"),
        "--add-data", data(work / "Wheel.apk"),
        # vgamepad's ViGEm client DLL and the driver installer.
        "--collect-all", "vgamepad",
        "--hidden-import", "pystray._win32",
        "--distpath", str(HERE / "dist"), "--workpath", str(work / "pyinstaller"), "--specpath", str(work),
        str(HERE / "app.py"),
    ], check=True, cwd=HERE)
    exe = HERE / "dist" / "Wheel.exe"
    print(f"\nbuilt {exe} ({exe.stat().st_size / 1e6:.1f} MB) with {apk.name}")
    if "--publish" in sys.argv:
        # Kept in Git LFS (see .gitattributes); Vercel serves it once it's pushed.
        site = HERE.parent / "site" / "download"
        site.mkdir(exist_ok=True)
        shutil.copy2(exe, site / "Wheel.exe")
        print(f"copied to {site / 'Wheel.exe'}: commit and push to publish it")


if __name__ == "__main__":
    main()
