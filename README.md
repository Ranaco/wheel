# Wheel

Your phone is the steering wheel and the pedals for PC racing games. Turn it to steer and tilt it to accelerate and brake. Touch buttons cover the one-off actions: horn, lights, camera and so on, and a D-pad with A, B, X and Y covers the game's menus. The PC sees an ordinary Xbox 360 controller, so it works in any game with controller support. Up to four phones can play at once, each as its own controller.

```
phones (app) --UDP 47800, Wi-Fi--> Wheel for PC (tray app, or wheel_companion.py) --ViGEmBus--> a virtual Xbox 360 pad per phone --> game
             <--rumble, player no., button marks--        |
                                                          +--> its window, http://127.0.0.1:47801: the code to scan, the players, settings
                                                          +--> for phones, port 47802: the page the code opens, and the app's download
```

- `app/`: the Android app (Kotlin, plain Views, no libraries). Android 13 or newer.
- `companion/`: Wheel for PC (Python). Windows for now.

## Setup

**PC (Windows):** run `Wheel.exe`. The first time, it does the following:
- copies itself to `%LOCALAPPDATA%\Programs\Wheel` and adds itself to the Start menu (no admin needed);
- offers to install the ViGEmBus driver;
- asks to let phones through the firewall.

After that it runs in the tray by the clock. Its window holds the code to scan, the players and the settings. The tray menu has **Start with Windows** and **Quit Wheel**. Closing the window leaves Wheel running. To update, run a newer `Wheel.exe` from anywhere: it asks the running copy to quit, replaces it, and starts again. Settings, the button mapping and the log (`wheel.log`) live in `%APPDATA%\Wheel`.

**Phone:** scan the code in Wheel's window with the camera and tap the link. Wheel opens and connects. A phone without Wheel gets the download page instead. On the same Wi-Fi, just opening Wheel also finds the PC.

**Building:**
- The app: `./gradlew assembleRelease`, signed with the key named in an untracked `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`). Without that file, `./gradlew assembleDebug` builds a debug copy.
- `Wheel.exe`: `pip install vgamepad segno pystray pillow pyinstaller`, then `python companion/build_exe.py`. It ships the newest build of the app inside, the release one if there is one.
- From source instead of the exe: install the [ViGEmBus](https://github.com/ViGEm/ViGEmBus/releases/tag/setup-v1.17.333) driver (`pip install vgamepad` offers it), `pip install vgamepad segno`, then `python companion/wheel_companion.py`. Allow Python through the firewall on private networks. Options: `--no-browser` (serve the page without opening it), `--no-ui` (terminal only), `--max-players` (fewer than four phones), `--name` (what phones call this PC), `--port`, `--ui-port`, `--pair-port`, `--quiet` (no live status line).

## Players

The window shows each player in the place Windows gave its controller: their phone's name, live steering and pedals, held buttons, and link quality.

- **New phones ask first.** A phone that hasn't been on this PC before waits, with "Waiting for …" on its screen, until someone clicks **Allow** in the window (or **Block**). The tray says when one asks. A phone that scanned the code on screen is let straight in, since only someone in front of the PC can do that. Allowed and blocked phones are remembered; **Forget all** forgets them and makes a new code, so an old photo of it stops working. Asking can be turned off.
- **Phones come and go:** a phone that drops out keeps its controller, so a game doesn't drop the player from the lobby. It keeps it for 10 minutes by default; the window sets 1 to 30. The same phone gets the same controller back, even after the app or the PC restarts, because each phone has a permanent id. **Remove** unplugs a controller straight away.
- **Make Player 1:** most single-player games, Assetto Corsa among them, read only Player 1. Windows never renumbers a plugged-in controller, so this unplugs that phone and anyone ahead of it, then plugs it back in first.
- **Keep someone playing as Player 1:** a setting, off by default. Once Player 1's phone has been gone 15 seconds, the next phone still playing takes over. The window counts it down.
- **Other controllers:** a real gamepad or wheel takes one of Windows' four places. The window shows it as "Another controller", and phones take the places after it. Here "Player 1" means the first place a phone can have.
- **Full:** a phone past the last free place is turned away. Its screen says the PC is full, and the window names it.

## Driving

Hold the phone sideways in both hands, like a wheel, with the screen facing you.

| You do | Game gets |
|---|---|
| Turn the phone in the plane of the screen | Left stick X (steering) |
| Tilt the top away from you | Right trigger (gas) |
| Tilt the top toward you | Left trigger (brake) |
| Volume up / down | Shift up / down |
| Hold **HANDBRAKE** or **BOOST** | Held as long as your thumb is down |
| Tap **HORN**, **LIGHTS**, **CAMERA**, **LOOK BACK**, **RESET**, **MENU**, **VIEW** | Tapped buttons |
| Tap the dial | Your current pose becomes straight ahead with no pedal |

Each button shows the Xbox button it presses on this PC (A, LB, ≡ and so on), so a game's "press A" is easy to find.

- **Tap the dial once** in your driving position before you start. Gas and brake are measured from that pose.
- **Menu controls:** for a game's menus, the ends of the screen become a D-pad and A, B, X and Y, and the middle takes a swipe as an arrow and a tap as A. Steering and pedals stay centred while they're up. They come up with **D-PAD**, with **MENU** (which pauses most games), when the phone folds, and when it's laid flat; **DRIVE**, or picking the phone up again, puts them away.
- **Folding** a flip phone taps MENU, so the game pauses. **Half-folding** it switches to a cockpit: the dashboard on the top half, the buttons on the bottom one, held like a handheld console.
- **Leaving the app** (home, settings, folding the phone) releases everything right away. If Wi-Fi drops, the PC releases everything after 0.25 s. The car is never left floored.

## Tuning

On the phone, the gear (⚙) opens Settings:
- Live: steering and pedals as you hold the phone, and **Set neutral here**.
- Steering: full-lock angle (60° by default), centre deadzone, response curve and smoothing.
- Pedals: gas and brake tilt angles, pedal deadzone, and swapping gas and brake.
- Feel: the game's rumble and its strength, clicks at the steering's centre and lock, and as the pedals engage.
- Buttons: volume keys as paddles, fold to pause, the half-fold cockpit, and flipping the screen.
- Connection: a fixed PC address, for when discovery is blocked.

On the PC, `%APPDATA%\Wheel\mapping.json` decides which Xbox button each phone button presses. It's written with the defaults on the first run, and buttons it leaves out keep their defaults. The defaults follow Forza Horizon:

| Key in the file | Phone | Xbox |
|---|---|---|
| `handbrake` | HANDBRAKE | A |
| `shift_up` | SHIFT + | B |
| `shift_down` | SHIFT − | X |
| `reset` | RESET | Y |
| `boost` | BOOST | LB |
| `camera` | CAMERA | RB |
| `horn` | HORN | LS |
| `look_back` | LOOK BACK | RS |
| `lights` | LIGHTS | D-pad up (`DPAD_UP`) |
| `start` | MENU | Start (`START`) |
| `back` | VIEW | Back (`BACK`) |
| `menu_up`, `menu_down`, `menu_left`, `menu_right` | the menu controls' arrows | the D-pad |
| `menu_a`, `menu_b`, `menu_x`, `menu_y` | the menu controls' A, B, X, Y | A, B, X, Y |

`steer_anti_deadzone` (0 to 0.3) cancels out a game's own stick deadzone if small turns do nothing. Quit and reopen Wheel after editing the file.

## Testing without a game

The tests start their own companion on spare ports (47900 to 47902), with a settings folder of their own (`WHEEL_DATA`), play phones over UDP, and read the virtual pads back through XInput the way games do. Close Wheel on any phone that might find them first.

- `python companion/test_loopback.py` checks:
  - steering, buttons, the menu controls, and one controller per phone;
  - rumble going to the right phone, player numbers, and the button marks;
  - app restarts and dropouts;
  - Make Player 1, the hand-over, and the full limit;
  - settings, the pages, and the download.

  It first plugs in a pretend gamepad as Player 1, so the phones have to work around a real one; `--alone` runs with phones only.
- `python companion/test_pairing.py` checks asking before new phones: Allow, Block, scanning the code, that an overheard hello doesn't let a phone in, and what's remembered after a restart.
- `python companion/test_overflow.py` fills all four of Windows' places, has a phone join, then frees two. Windows numbers that controller late, and ViGEm then reports the wrong number, so the companion finds each controller's place the way games do: it nudges that controller's unused right stick by a hair and sees which XInput place moves.
- On Windows, `joy.cpl` shows the pad live. [slowroads.io](https://slowroads.io) is a free browser driving game that takes a controller.

## Game notes

**slowroads.io** (browser). Chrome only sees a controller that was plugged in before Chrome started, so restart Chrome (`chrome://restart`) after installing ViGEmBus. Slowroads' default button layout has A as *Toggle autodrive*, which means HANDBRAKE switches autodrive on and off. Its settings live in `localStorage.settings_Gamepad`. In Settings → Controls → Controller, keep **Wheel mode** on, then map:

| Slowroads action | Button | Phone button |
|---|---|---|
| Handbrake | 0 (A) | HANDBRAKE |
| Boost | 4 (LB) | BOOST |
| Change camera | 5 (RB) | CAMERA |
| Toggle headlights | 12 (D-pad up) | LIGHTS |
| Reset | 3 (Y) | RESET |
| Pause | 9 (Start) | MENU |
| Toggle autodrive | 8 (Back) | VIEW |

Also clear **Toggle handbrake**, which is LB by default and clashes with BOOST. The game has no horn, gears or look-back, so SHIFT and HORN do nothing there.

**Assetto Corsa.** Controls live in `Documents\Assetto Corsa\cfg\controls.ini`. Use input method **X360**. Its default Xbox layout clashes with Wheel's: A shifts up and B is the handbrake, so every upshift pulled the handbrake. Set these:

| Phone button | Xbox | Assetto Corsa |
|---|---|---|
| HANDBRAKE | A | `[HANDBRAKE] XBOXBUTTON=A` |
| SHIFT + / volume up | B | `[GEARUP] XBOXBUTTON=B` |
| SHIFT − / volume down | X | `[GEARDN] XBOXBUTTON=X` |
| BOOST | LB | `[KERS] XBOXBUTTON=LSHOULDER` |
| CAMERA | RB | `[ACTION_CHANGE_CAMERA] XBOXBUTTON=RSHOULDER` |
| HORN | LS | `[ACTION_HORN] XBOXBUTTON=LEFT_THUMB` |
| LOOK BACK | RS | `[GLANCEBACK] XBOXBUTTON=RIGHT_THUMB` |
| LIGHTS | D-pad up | `[ACTION_HEADLIGHTS] XBOXBUTTON=DPAD_UP` |
| VIEW | Back | `[DRS] XBOXBUTTON=BACK` |

Clear `ACTION_CELEBRATE` and `ACTION_CLAIM`, which use LB and RB by default. In `[X360]`, the defaults suit a thumbstick, so a phone wheel wants a linear, direct setup: `STEER_GAMMA=1.0`, `STEER_DEADZONE=0.0`, `STEER_FILTER=0.1`, `STEER_SPEED=0.9`, `SPEED_SENSITIVITY=0.3`. The game rumbles mostly through the light motor at a few percent, and the app lifts that so it can be felt. Close the Assetto Corsa launcher before editing the file, because its Controls page can write its own copy back. The game only reads Player 1: use **Make Player 1** for whoever's driving.

## Protocol

All messages are UDP, little-endian, and start with `"RW"`, then version 1, then a message type.

| Type | Direction | Body |
|---|---|---|
| `0x01` discover | phone → PC | 8-byte phone id, then the phone's name in UTF-8 (up to 64 bytes). From a phone that scanned this PC's code, sent to that PC only: the name padded to 64 bytes with zeros, then a 16-byte proof, HMAC-SHA256(key = the code's token, message = the phone id), cut to 16 bytes. Broadcast to 255.255.255.255 and the subnet while looking for a PC, and repeated to the PC every 5 s while connected, so a restarted PC learns each phone's name. |
| `0x02` state | phone → PC | `u32 seq, i16 steer (left stick X), u16 gas, u16 brake, u32 buttons, 8 bytes phone id, i16 left Y, i16 right X, i16 right Y`, sent at 125 Hz |
| `0x03` bye | phone → PC | none |
| `0x81` hello | PC → phone | the PC's name, in UTF-8 |
| `0x82` status | PC → phone | `u8 large motor, u8 small motor, u8 player`: 1 to 4, 0 until Windows numbers it, or for a phone without a controller, 255 (the PC is full), 254 (waiting to be allowed) or 253 (blocked). Sent when it changes, and every 0.25 s; to a phone without a controller, once a second. |
| `0x83` buttons | PC → phone | one byte per phone button bit: the Xbox button it presses, as 1 + its place in A, B, X, Y, LB, RB, LS, RS, Start, Back, Guide, D-pad up, down, left, right (0 for none). Sent with every hello, so the app can mark each button with the one the game sees. |

Older phones send the state without the id and the sticks (18 bytes); the PC still takes them, and tells them apart by address. New fields only ever go on the end of a message, as a whole: the id and the three sticks come together or not at all. Button bits follow `Button` in `Controls.kt`, `BUTTONS` in the companion, and `LABELS` in `page.html`; new buttons go on the end.

The code to scan holds `http://<PC address>:47802/pair?t=<token>`, because phone cameras only open web links. Port 47802 serves that page, `/get` (the download page) and `/wheel.apk`, and shows nothing but the PC's name and address. The pairing page sends the browser on to `intent://pair?h=<address>&p=<port>&n=<name>&t=<token>#Intent;scheme=wheel;package=com.ranaco.wheel;S.browser_fallback_url=<the /get page>;end`, which opens Wheel through its `wheel://pair` link, or the download page if Wheel isn't installed. The token is passed on only to a request that brought it, so another device on the Wi-Fi can open the page but doesn't get it. If the browser holds back, the page has an **Open Wheel** button. The app saves the PC's address and tries it first; discovery still finds the PC if the address changes.

## Known quirks

- The ViGEm rumble callback sometimes arrives late or out of order. If a game sets rumble once and holds it, the phone may buzz for less time than it should. Games that update rumble continuously aren't affected.
- A phone's state isn't signed: on the same Wi-Fi, someone who copies an allowed phone's id from its packets can pass for it. Asking first keeps out phones that just happen to have Wheel, not someone set on getting in.
