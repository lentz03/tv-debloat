TV Debloat: Panasonic (SCBC R4, Android TV 11)
=============================================

WHAT WAS CHANGED (every change is logged in tv-backup-*/changes.log)
  Switched off (pm disable-user, NOT uninstalled; re-enable any time):
    Google: tvrecommendations, feedback, Play Games, YouTube Music, Ambient mode (backdrop),
            stock launcher (tvlauncher, the ad-filled home screen)
    SCBC/Panasonic: launcher customiser, partner auto-installer, GDPR/data consent app,
            E-Show / E-Sticker shop demo, Digital Signage, Meeting Mode (x2), World Clock
    Other: HbbTV red button, print spooler, live-wallpaper picker
  Settings: animations 0.5x (window, transition, animator), screensaver -> Gallery Home
  Installed: Gallery Home (tv.galleryhome) as the home screen

GALLERY HOME 5.6.1 (GalleryHome.apk, source in launcher-src.zip)
  Style "Logo Line": white app logos on a hairline, amber line on the focused app,
  Big Shoulders clock, rotating wallpapers: Space (NASA), Night cities, Dark landscapes.
  - Hold OK (or Menu) on an app: move left/right, remove, app info
  - APPS: every app; hold OK on one to add it to the dock
  - WALLPAPER: hides everything but the picture; press any button to come back
  - HDMI · TV: the TV's input picker
  - Wallpapers (Settings > Wallpapers, tick any mix): Space (NASA), Night cities, Dark landscapes,
    Museum paintings, and from Artvee (artvee.com, public domain): Landscapes, Japanese prints,
    Vintage posters, Illustrations & engravings, Amber nights (warm, dark night scenes picked to suit
    the room's orange lights). Bright prints and posters are dimmed a little extra so the clock stays readable.
  - SETTINGS: next wallpaper, which wallpapers, how often, brightness, captions,
    style (Logo Line / Glass Orbs / Noir HUD), weather on/off, mini player on/off,
    reset dock, Android TV settings

  WEATHER (Hong Kong Observatory open data, Happy Valley station, nearest to North Point)
    Now + 3-day forecast under the clock; HKO warnings and typhoon signals as amber badges.
    Checks every minute, fetches every 10 minutes. Also in wallpaper mode and the screensaver.

  COMMUTE (route-line icon in the dock, where Apple TV was): live departures near Kei Wah Court
    - To work: when to leave for Kowloon Tong. TKO Line from North Point (6 min walk to the platform),
      the Kwun Tong Line train it connects with at Yau Tong, and roughly when you reach Kowloon Tong.
    - Citybus A11 (North Point Ferry Pier), A12 and B13 (Healthy Gardens stop, King's Road), live ETAs.
      The nearest stop to home is found automatically. More routes can be added in Commute.java (BUSES).
    - Island Line at North Point, both directions, live.
    - Ferries from North Point Pier: Hung Hom and Kowloon City (Sun Ferry timetable + live sailings and
      cancellations), Kwun Tong / Kai Tak (Fortune Ferry timetable; it has no reliable live feed).
    - Departures you can't reach on foot are dimmed; the next one you can catch is amber.
    - OK opens the board's menu: refresh now; journey style (Journey line, or Steps with route);
      background (Ink, Sky by the hour, Weather, Contours, Light trails, Skyline, Sharp and dark,
      LED board). Choices are remembered. BACK goes home.
      Sky by the hour shifts dawn > day > dusk > night; Weather follows the HKO report (rain, cloud, stars). Data: Citybus & MTR open data (data.gov.hk), Sun Ferry, 1823 holidays.
    - Timetables in the app: Sun Ferry from 24 Aug 2026, Fortune Ferry from 3 Sep 2023 (Transport Department).
  Apple TV app: switched off (not uninstalled). Turn it back on with:
    ~/Documents/"TV Debloat"/tv.sh enable com.apple.atve.androidtv.appletv
    then add it to the dock from APPS (hold OK > Add to dock).

  NEST MINI MINI PLAYER (Google Cast, straight from the speaker on your Wi-Fi; no account needed)
    Appears under the forecast whenever the Nest Mini is playing (Spotify, YouTube Music, radio...):
    cover, song, artist, progress, and four buttons: previous, play/pause, next, volume.
    - From the dock press UP to reach the player; DOWN (or BACK) goes back to the dock
    - Volume button: press OK, then LEFT / RIGHT to change the speaker's volume, OK to finish
    - The remote's play/pause, next and previous keys control the speaker on the home screen
    - Wallpaper mode and the screensaver show one line: cover, song, artist and progress
    - Skip buttons look dimmer when the app on the speaker doesn't allow skipping
    It finds the speaker by itself (and again if its address changes). The TV only talks to
    the speaker while the home screen or the screensaver is showing.

  Fonts: Big Shoulders, Geist Mono, Outfit, Jura (SIL Open Font License, in the APK)
  Wallpapers credit their source on screen (NASA public domain; Wikimedia Commons, CC licences)

UNDO EVERYTHING
  Open Terminal and run:
    ~/Documents/"TV Debloat"/restore.sh                  (full restore, removes Gallery Home)
    ~/Documents/"TV Debloat"/restore.sh --keep-launcher  (restore apps/settings, keep Gallery Home)
    ~/Documents/"TV Debloat"/restore.sh --dry-run        (only show what it would do)
  Re-enable a single app:  ~/Documents/"TV Debloat"/tv.sh enable <package>
  If the TV's IP changes:  ~/Documents/"TV Debloat"/tv.sh connect <new-ip>  first.

OTHER COMMANDS (tv.sh)
  setup, find, connect, backup, shot <name>, disable, enable, setting, install, home,
  apply <plan.txt>, diag, restore
