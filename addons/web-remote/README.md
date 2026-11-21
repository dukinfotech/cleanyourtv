# Web Remote

Controls an Android TV from a phone or a computer through a web page the TV serves itself: a touchpad that moves a
mouse pointer on the TV, a typing box (Vietnamese Telex included), the d-pad and navigation keys, volume and media
keys, a list of the TV's apps and a box to send a link to its browser. Built for browsing the web, which is painful with
a remote.

No cloud, no account, no relay: the TV runs the server, and the page is one file in the APK.

## How it works

- **A server on the TV** (`HttpServer`, plain sockets, port 6789) serves the page and takes commands over one
  WebSocket per paired device.
- **An accessibility service** (`RemoteService`) draws the pointer over any app and acts for the device. On this TV
  (Xiaomi MiTV-MOEU0, Android 14) the system cancels touch gestures from accessibility services, so clicks and scrolls
  go through the accessibility tree (`NodeTouch`): the element under the pointer is asked to click or scroll. That works
  in a browser's page too, because a WebView exposes its links and fields. If a TV accepts gestures, those are used first.

## Set up

JDK 17 and Android SDK platform 34. From the repo root, with ADB on:

```bash
TV_HOST=<tv ip> addons/web-remote/install.sh
```

It builds, installs, adds the service to the TV's accessibility list (without dropping the services already there),
and turns the server on. Then, on the TV, open **Web Remote**, press **Ghép đôi thiết bị mới**, and on the phone or
computer open the address the TV shows, for example `http://192.168.1.50:6789`, and type the six-digit PIN.

Away from home: install [Tailscale](https://tailscale.com) on the TV and the phone and open the TV's `100.x.y.z`
address instead. Never forward the port on the router.

## Using the page

| Tab | What it does |
|---|---|
| Chuột | One finger moves the pointer, a tap clicks, two fingers tap = Back, two fingers drag = scroll, long press then drag = drag. On a PC: mouse, wheel, right click = Back |
| Phím | D-pad and OK, Back, Home, Recents, notifications, quick settings, volume, media keys. Arrow keys, Enter and Esc work on a keyboard |
| Bàn phím | Whatever you type is put into the text box the TV is on. Enter or search with the button |
| Ứng dụng | Every app the TV can launch, with a search box |
| Liên kết | Opens a link in the TV's browser |

## Watching the TV's screen

The **Màn hình** tab shows the TV's screen live, and a tap on the picture moves the pointer there and clicks. It is off
until someone presses **Cho phép chia sẻ màn hình** in the app on the TV; Android then asks for its own screen-capture
permission and shows a notification and a cast icon for as long as it is on. **Dừng chia sẻ màn hình** ends it.

The screen is taken through MediaProjection, scaled to 960 pixels wide and sent as JPEG frames over the page's WebSocket,
at most about 60 a second (what the TV's CPU can encode is the real limit). Nothing is captured while no page is watching, and a slow link gets only the newest frame.
Protected video (DRM, some streaming apps) and HDMI inputs show black: Android hides them from screen capture. The picture
is plain JPEG, not H.264, so that it works in any browser over plain HTTP.

## Security

- **Pairing:** a six-digit PIN shown on the TV for one minute after you ask for it. Five wrong tries lock pairing for five minutes; the lock outlives the window, so
  opening a new one does not lift it. A paired device gets a 256-bit token that the TV keeps only as a hash. Each device can be
  revoked from the TV's screen.
- **Network:** only the home network, this device and Tailscale's range can connect; any other address is dropped before
  a byte is read. The `Host` header must be an address (not a name an attacker could point at the TV) and an `Origin`, if
  sent, must be that same address, which blocks other web pages from driving the TV.
- **Typed text** is never logged or stored.
- It is plain HTTP on the local network: anyone who can watch your Wi-Fi traffic can read what you type. Over Tailscale
  the path is encrypted.
- The server is off until you turn it on in the app, and the accessibility service has to be enabled for any of it to
  work. Turning the service off in Settings stops everything.

## Limits

- Dragging needs touch injection, which this TV cancels: a drag does nothing, and holding still is a long press.
- A scroll step moves about a screen at a time in a browser (the accessibility scroll action's size).
- Some apps keep their own focus and ignore a pointer, so the d-pad keys still matter there.
- Keys the d-pad sends need Android 13 or later. Volume and media keys work on any version.
- The TV's own volume may be controlled by the TV or a sound bar, in which case the volume keys change only the Android
  volume.

## Tests

`./gradlew testReleaseUnitTest` runs 51 tests: the WebSocket framing against the RFC's examples, the network policy,
pairing and its rate limits, command parsing and bounds, and the real server on localhost (a foreign `Host`, a foreign
`Origin`, a wrong PIN, a bad token, a revoked device, garbage on the wire).

Tried on a Xiaomi MiTV-MOEU0 (Android 14) with a browser, from a Windows PC: pairing, pointer, click on a link, scroll,
typing Vietnamese into a search field, Enter, d-pad, launching an app, Back and Home. Not tried: a phone's touchscreen
(the page was driven in a desktop Chrome with touch emulation), Tailscale, or more than one device at once.
