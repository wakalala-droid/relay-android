# Relay

Relay shows the alerts from your main phone on a second phone. Install it on both, pick a role on each and pair them with one code.

On the second phone you get:

- **Calls** that ring while your main phone rings, with the caller's saved contact name and number type ("Mum · Mobile · +260 97…"), then a missed or declined alert with a Call back button
- **Texts** from Samsung Messages or Google Messages
- **WhatsApp** messages and WhatsApp call alerts, plus any other app you tick
- Alerts you read or swipe away on the main phone disappear on the second phone too (optional)

You can't answer a call on the second phone. It tells you who is calling so you know whether to grab the main phone.

## How it works

```
Main phone                        Relay server (ntfy)              Second phone
Notification access ──encrypt──▶  stores only scrambled bytes ──▶  decrypt ──▶ notification
Call state + contacts                                              (rings for calls)
```

The pairing code never leaves your phones. Both phones turn it into the same private channel name and the same AES-256 key, so the server only ever handles encrypted messages. Anyone who has your code could read your alerts, so keep it private and use **New code** in the app if it leaks.

By default Relay uses the free public server at ntfy.sh. It has fair-use rate limits, which are plenty for personal use. If you ever outgrow them you can run your own ntfy server and enter its address under **Relay server** on both phones.

## 1. Get the APK

### Option A: let GitHub build it (no computer setup needed)

1. Create a new empty repository on GitHub, for example `relay`.
2. Upload this whole folder to it (GitHub Desktop or `git push` both work; the `.github` folder must be included).
3. Open the **Actions** tab. The "Build Relay APK" job runs automatically and takes about 4 minutes.
4. When it finishes, open **Releases** on the repository page from each phone and tap **Relay.apk**.

Every later push builds a new release. No signing key is stored in the project, so each build is signed with a temporary key. To install a newer build, uninstall the old Relay first, then set it up again (about a minute per phone).

### Option B: Android Studio

Open the folder in Android Studio, let it sync, then choose **Build › Build App Bundle(s) / APK(s) › Build APK(s)**. The file lands in `app/build/outputs/apk/`.

## 2. Install on both phones

Open the APK and allow installing from your browser or Files app when asked. On newer Samsung phones with **Auto Blocker** switched on (Settings › Security and privacy) you may need to turn it off for the install.

## 3. Set up the main phone

1. Open Relay and choose **Main phone**.
2. **Notification access**: tap Turn on and allow Relay.
   If the switch is greyed out (Android 13 and later does this for apps installed outside the Play Store), open **App info**, tap the **⋮** menu, choose **Allow restricted settings**, then try again.
3. **Calls and contacts**: tap Allow and accept Phone, Call logs and Contacts. This is what puts saved names on call alerts.
4. **Battery**: tap Fix and allow. On Samsung also check Settings › Battery › Background usage limits and make sure Relay is not in Sleeping or Deep sleeping apps.
5. Tap **Share** under Pairing code and send it to your second phone (WhatsApp, email or anything else).
6. Under **What to send**, tap Choose to tick the apps you want. WhatsApp, WhatsApp Business and the common SMS apps are on by default.

## 4. Set up the second phone (your S20)

1. Open Relay and choose **Second phone**.
2. Copy the code you sent, tap **Paste**, then **Connect**.
3. Allow notifications and tap **Fix** for battery.
4. The status card turns green and says Connected.

Back on the main phone, tap **Send a test alert**. It should pop up on the second phone within a second or two.

## Tips

- **Double WhatsApp alerts?** If the second phone also runs WhatsApp as a linked device, untick WhatsApp in Relay on the main phone.
- **Tap to open:** if the app behind an alert is installed on the second phone, tapping the alert opens it there.
- **Both phones need internet** (Wi-Fi or mobile data). If the second phone goes offline, it catches up on anything missed in the last few hours when it reconnects, without ringing for old calls.
- **Call ringtone and sounds** are normal Android notification channels. Long-press any Relay alert on the second phone to change them.

## Project layout

| File | What it does |
|---|---|
| `MirrorListenerService.kt` | Main phone: reads alerts from the chosen apps and sends them |
| `CallWatcher.kt` | Main phone: ringing, answered, missed and declined calls with contact lookup |
| `Relay.kt` | Main phone: sends encrypted events to the server with retries |
| `ReceiverService.kt` | Second phone: keeps one live connection open and reconnects on its own |
| `Alerts.kt` | Second phone: turns events into notifications, rings for calls |
| `Secure.kt` | Pairing codes, channel name and AES-256-GCM encryption |
| `MainActivity.kt`, `Ui.kt` | The app screens |

Requires Android 8.0 or newer on both phones. No Google account, Firebase project or sign-up needed.
