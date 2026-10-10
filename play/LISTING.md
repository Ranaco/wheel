# Wheel on Google Play

Everything to paste into Play Console. Graphics come from `python play/make_assets.py` (screenshots from `play/raw/`). The bundle is `app/build/outputs/bundle/release/app-release.aab` from `./gradlew bundleRelease`.

## App details

| Field | Value |
|---|---|
| App name (30) | `Wheel: Phone Racing Wheel` |
| Package | `com.ranaco.wheel` |
| Default language | English (United States) |
| App or game | App |
| Free or paid | Free |
| Category | Tools |
| Contact email | ranasatyamraj@gmail.com |
| Website | https://wheel.ranax.co |
| Privacy policy | https://wheel.ranax.co/privacy |

### Short description (80)

```
Your phone is the racing wheel: steer and pedal by motion in PC racing games.
```

### Full description

```
Wheel turns your phone into a racing wheel and pedals for games on your PC. Turn the phone to steer, tilt it for gas and brake, and your PC sees an ordinary Xbox controller, so it works in any racing game that takes one.

DRIVE BY FEEL
• Turn the phone to steer. Tilt the top away for gas, toward you to brake.
• Set your neutral by tapping the dial, wherever you like to hold it.
• Tune the steering lock, deadzones, response curve and smoothing, with a live view of exactly what the game gets.

EVERY BUTTON, MARKED
• Handbrake and boost to hold, paddles to shift (or the volume keys), and horn, lights, camera and look back.
• Each button shows the Xbox button it presses, so "press A" is easy to find.
• Menu controls for the game's menus: a D-pad and A, B, X and Y. Swipe or tap the middle, or just lay the phone flat.

FEEL THE ROAD
• The game's rumble comes through the phone: kerbs, slides and crashes, lifted so the faint ones can be felt.
• Optional clicks at the steering's centre and full lock.

PLAY TOGETHER
• Up to four phones on one PC, each its own controller, for split-screen games.
• Make anyone Player 1 in one tap, for games that only listen to Player 1.
• A phone that drops out keeps its place, so the game doesn't drop you.

MADE FOR FLIP PHONES
• Fold the phone to pause the game.
• Half-fold it into a cockpit: the dashboard on the top half, the buttons under your thumbs.

HOW TO START
1. On your Windows PC, get Wheel for PC, free, from wheel.ranax.co.
2. Scan the code in its window with your phone's camera.
3. Hold the phone like a wheel and start a race.

PRIVATE BY DESIGN
Your phone talks only to your own PC, over your own Wi-Fi. No accounts, no ads, no analytics. A new phone can't join your PC until you allow it.

Wheel needs Android 13 or newer, and a Windows PC on the same Wi-Fi. It isn't affiliated with Microsoft or any game maker.
```

### What's new (1.0)

```
First release.
```

### Graphics

| Asset | File |
|---|---|
| App icon 512×512 | `icon-512.png` |
| Feature graphic 1024×500 | `feature-graphic.png` |
| Phone screenshots (2400×1200, 2:1) | `screenshot-1.png` … `screenshot-4.png` |

## Store settings

- **Countries:** all.
- **Devices:** phones. The app works with any accelerometer; a gyroscope makes it smoother. No need to limit the device catalog.

## App content

- **Privacy policy:** https://wheel.ranax.co/privacy
- **Ads:** No ads.
- **App access:** "All or some functionality is restricted". Instructions for the reviewer:
  > Wheel controls PC games, so it needs Wheel for PC (free, https://wheel.ranax.co) running on a Windows PC on the same Wi-Fi. Without it, the app opens on "Looking for your PC"; the drive screen, the menu controls (tap D-PAD) and Settings (the gear) with its live steering and pedal view all still work. With it: run Wheel.exe, scan the code in its window with the phone's camera, then hold the phone sideways like a steering wheel.
- **Content rating (IARC):** category "All other app types". Answer **No** to every content question. Users interact or share content: **No** (phones only talk to the user's own PC). Shares location: **No**. Digital purchases: **No**.
- **Target audience:** 13 and over. Not designed for children.
- **News app:** No. **COVID-19 apps:** No. **Government app:** No. **Financial features:** none. **Health:** none.

### Data safety

Google counts any data sent off the phone as "collected", even to the user's own PC, so declare it, with the user's PC as where it goes:

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **Yes** |
| Is all of the user data collected by your app encrypted in transit? | **No** (plain UDP on the user's own Wi-Fi) |
| Do you provide a way for users to request that their data is deleted? | **Yes**: uninstalling clears the phone, and **Forget all** in Wheel for PC clears the PC. Nothing is ever on the developer's side. |

Data types:

| Type | Collected | Shared | Ephemeral | Required | Purpose |
|---|---|---|---|---|---|
| App activity → **App interactions** (the controls: steering, pedals, buttons) | Yes | No | **Yes**: applied to the controller at once and never stored | Required | App functionality |
| Device or other IDs (a random id Wheel makes, and the phone's name) | Yes | No | No: the PC remembers phones the user allowed | Required | App functionality |

Nothing goes to the developer or any third party, so nothing is "shared".

## Signing

- Upload key: `~/.android-keys/wheel-upload.jks`, alias `wheel`, SHA-256 `F6:F0:A8:AC:D6:83:34:79:CE:A5:40:F8:99:BB:56:88:B7:D7:01:EB:62:A1:69:50:07:2B:6A:5F:8C:6A:16:6F`. The password is in the untracked `keystore.properties`. **Back both up**: losing the key means no more updates under this package.
- When setting up Play App Signing, choose to use **your own key**, and upload this one with the PEPK tool Play offers (as for zInk). Then the app that Wheel for PC hands phones and the Play Store copy are signed alike and update each other.

## Testing track

A personal developer account needs a closed test with at least 12 testers for 14 days before production. The zInk tester group (zinkbeta@googlegroups.com) can be reused, or make a `wheelbeta` group with the same welcome message and opt-in link.
