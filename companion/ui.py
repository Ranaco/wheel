"""The companion's pages.

The main page (the code to scan, and who's playing) is served on 127.0.0.1 only, so
nothing else on the network can open it, and it answers just this PC's own address so
another website can't reach it through the browser.

The pairing port is the only thing phones can open. /pair is what the QR code points
at: it hands the phone straight on to Wheel, or to /get when Wheel isn't installed,
which offers the app itself (/wheel.apk). They show nothing but this PC's name and address.
"""

import html
import io
import json
import sys
import threading
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, quote, urlsplit

import segno

import config

PAGE = config.resource("page.html")
ANDROID_PACKAGE = "com.ranaco.wheel"
# What the page can ask for, with a phone's id; and what it can ask for with nothing else.
ABOUT_A_PHONE = ("forget", "promote", "allow", "block")
ABOUT_ALL = ("forget-phones", "unblock-all")
SETTINGS = ("keepMinutes", "autoPromote", "askNew")


class Server(ThreadingHTTPServer):
    """Phones' browsers often drop a connection halfway; that's not worth a traceback."""

    def handle_error(self, request, client_address):
        if isinstance(sys.exc_info()[1], (ConnectionResetError, ConnectionAbortedError, BrokenPipeError)):
            return
        super().handle_error(request, client_address)


class Handler(BaseHTTPRequestHandler):
    def reply(self, code, kind, body):
        self.send_response(code)
        self.send_header("Content-Type", kind)
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


def start(handler, host, port, name):
    """Serves on its own thread; False if the port is taken."""
    try:
        server = Server((host, port), handler)
    except OSError:
        return False
    threading.Thread(target=server.serve_forever, name=name, daemon=True).start()
    return True


PAIRING = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Connect to {name}</title>
<style>
  body {{ margin: 0; min-height: 100vh; display: grid; place-items: center; background: #000; color: #f2f2f3;
    font: 16px/1.5 system-ui, -apple-system, "Segoe UI", sans-serif; }}
  main {{ width: min(360px, calc(100% - 48px)); display: grid; gap: 14px; text-align: center; }}
  .k {{ margin: 0; color: #6c6c73; font-size: 12px; font-weight: 600; letter-spacing: .14em; }}
  h1 {{ margin: 0; font-size: 26px; font-weight: 600; }}
  p {{ margin: 0; color: #b4b4ba; }}
  a {{ display: block; margin-top: 10px; padding: 16px; border-radius: 999px; background: #f2f2f3; color: #000;
    font-weight: 600; text-decoration: none; font-size: 17px; }}
  .small {{ color: #6c6c73; font-size: 13px; margin-top: 8px; }}
  a.plain {{ display: inline; margin: 0; padding: 0; background: none; color: #f2f2f3; font-size: 13px; font-weight: 500; text-decoration: underline; }}
</style></head>
<body><main>
  <p class="k">WHEEL</p>
  <h1>Connect to {name}</h1>
  <p>Opening Wheel on this phone.</p>
  <a id="open" href="{intent}">Open Wheel</a>
  <p class="small">Nothing happened? <a class="plain" href="/get">Get Wheel for this phone</a>, or type {address} into Wheel's Settings under PC address.</p>
</main>
<script>location.href = document.getElementById("open").href;</script>
</body></html>"""

GET = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Get Wheel</title>
<style>
  body {{ margin: 0; min-height: 100vh; display: grid; place-items: center; background: #000; color: #f2f2f3;
    font: 16px/1.5 system-ui, -apple-system, "Segoe UI", sans-serif; }}
  main {{ width: min(360px, calc(100% - 48px)); display: grid; gap: 14px; text-align: center; }}
  .k {{ margin: 0; color: #6c6c73; font-size: 12px; font-weight: 600; letter-spacing: .14em; }}
  h1 {{ margin: 0; font-size: 26px; font-weight: 600; }}
  p {{ margin: 0; color: #b4b4ba; }}
  a.button {{ display: block; margin-top: 6px; padding: 16px; border-radius: 999px; background: #f2f2f3; color: #000;
    font-weight: 600; text-decoration: none; font-size: 17px; }}
  a.plain {{ color: #f2f2f3; }}
  ol {{ margin: 6px 0 0; padding-left: 20px; color: #b4b4ba; text-align: left; display: grid; gap: 6px; }}
  .small {{ color: #6c6c73; font-size: 13px; }}
</style></head>
<body><main>
  <p class="k">WHEEL</p>
  <h1>Your phone is the controller</h1>
  <p>Steer by turning the phone, tilt it for gas and brake. {name} turns it into an Xbox controller.</p>
  {button}
  <ol>
    <li>Tap Download, then open the file.</li>
    <li>If Android asks, allow installing apps from this browser.</li>
    <li>Open Wheel. On this Wi-Fi it finds {name} by itself, or <a class="plain" href="/pair">connect now</a>.</li>
  </ol>
  <p class="small">Android only, for now.</p>
</main></body></html>"""


def serve(companion, port, open_browser=True, on_quit=None):
    """The main page, on this PC only. Its address, or None if the port is taken."""
    hosts = {f"127.0.0.1:{port}", f"localhost:{port}"}
    qr = {}

    def qr_svg():
        url = companion.pair_url
        if url not in qr:
            qr.clear()
            out = io.BytesIO()
            # Dark on white with a quiet border: what every phone camera reads first time.
            segno.make(url, error="m").save(out, kind="svg", scale=10, border=3, dark="#000", light="#fff", xmldecl=False)
            qr[url] = out.getvalue()
        return qr[url]

    class Page(Handler):
        def do_GET(self):
            if self.headers.get("Host") not in hosts:
                return self.reply(403, "text/plain", b"forbidden")
            path = urlsplit(self.path).path
            if path in ("/", "/index.html"):
                self.reply(200, "text/html; charset=utf-8", PAGE.read_bytes())
            elif path == "/state":
                self.reply(200, "application/json", json.dumps(companion.snapshot).encode())
            elif path == "/qr.svg":
                self.reply(200, "image/svg+xml", qr_svg())
            else:
                self.reply(404, "text/plain", b"not found")

        def do_POST(self):
            # The custom header makes a browser ask first, which other sites' pages can't pass.
            if self.headers.get("Host") not in hosts or self.headers.get("X-Wheel") != "1":
                return self.reply(403, "text/plain", b"forbidden")
            try:
                body = json.loads(self.rfile.read(int(self.headers.get("Content-Length") or 0)) or b"{}")
            except ValueError:
                return self.reply(400, "text/plain", b"bad request")
            action = self.path[1:]
            if action in ABOUT_A_PHONE:
                companion.requests.append((action, str(body.get("id", ""))))
            elif action in ABOUT_ALL:
                companion.requests.append((action.replace("-", "_"), None))
            elif action == "settings":
                companion.requests.append(("settings", {k: body[k] for k in SETTINGS if k in body}))
            elif action == "quit" and on_quit:
                # A newer Wheel.exe asking for the file, so it can update it: answered before going.
                self.reply(204, "text/plain", b"")
                threading.Thread(target=on_quit, name="quit", daemon=True).start()
                return
            else:
                return self.reply(404, "text/plain", b"not found")
            self.reply(204, "text/plain", b"")

    if not start(Page, "127.0.0.1", port, "page"):
        return None
    url = f"http://127.0.0.1:{port}/"
    if open_browser:
        webbrowser.open(url)
    return url


def serve_pairing(companion):
    """The pages a phone opens, on companion.pair_port. Chrome passes the intent link to
    Wheel. False if the port is taken: phones still find the PC, but scanning won't work."""

    class Pairing(Handler):
        def do_GET(self):
            parts = urlsplit(self.path)
            if parts.path == "/get":
                return self.get_page()
            if parts.path == "/wheel.apk":
                return self.apk()
            if parts.path != "/pair":
                return self.reply(404, "text/plain", b"not found")
            # The token goes on to Wheel only if the phone came with it, from the code on screen:
            # anyone on the Wi-Fi can open this page, but only someone in front of the PC has that.
            token = parse_qs(parts.query).get("t", [""])[0]
            deep = companion.deep_link(scanned=companion.scanned(token))
            # intent:// lets Chrome open the app directly, which a wheel:// link from a page can't always do.
            # Without the app installed, the browser goes to the fallback: the page to get it.
            fallback = quote(companion.get_url, safe="")
            intent = "intent://" + deep.split("://", 1)[1] + \
                f"#Intent;scheme=wheel;package={ANDROID_PACKAGE};S.browser_fallback_url={fallback};end"
            page = PAIRING.format(name=html.escape(companion.name), address=html.escape(companion.address),
                                  intent=html.escape(intent, quote=True))
            self.reply(200, "text/html; charset=utf-8", page.encode("utf-8"))

        def get_page(self):
            button = ('<a class="button" href="/wheel.apk">Download Wheel</a>' if config.app_build()
                      else '<p>The app isn\'t on this PC yet. Build it, or put Wheel.apk next to the companion.</p>')
            page = GET.format(name=html.escape(companion.name), button=button)
            self.reply(200, "text/html; charset=utf-8", page.encode("utf-8"))

        def apk(self):
            apk = config.app_build()
            if apk is None:
                return self.reply(404, "text/plain", b"no app here yet")
            body = apk.read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Content-Disposition", 'attachment; filename="Wheel.apk"')
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    return start(Pairing, "0.0.0.0", companion.pair_port, "pairing")
