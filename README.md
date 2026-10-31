# BRAVIA Home

An ad-free home screen for Android TV and Google TV. New releases from your streaming apps fill the header,
IMDb ratings sit on every title, and Home has Continue watching, a Top 10 per app, rows by genre and your
favourite apps in a drawer you arrange yourself. There are no ads, no sponsored rows and no tracking, and the
launcher itself never goes online.

Built and running on a Sony BRAVIA BF1 (Google TV 12), with India's streaming services.

## What's on Home, top to bottom

| | |
|---|---|
| ![The header carousel focused, with a Watch on Netflix button](docs/screens/header.jpg) | **Header.** New releases on your drawer apps: films and shows first out in the last 14 days (30 for Apple TV, which releases a few titles a month), and new seasons that premiered in that time. Newness is judged by each title's original release date, not the day it reached streaming. **It follows the drawer:** with an app focused, the header shows 15 of that app's new releases, its 5 best and 10 drawn from the rest, with a new draw each refresh. On YouTube it shows your new videos: Subscriptions and Recommended up to a week old (or live), then Trending. On an app with nothing new, it mixes 5 from each drawer app. Each title shows its app's logo beside the IMDb rating, then year · genres · season, over its full-screen art, with a soft shade behind the text only. It turns every 8 seconds. Press Up from the drawer to take it over: Left and Right turn it one title per press, OK watches, and **holding OK opens Read more**, a popup with the plot, tagline, cast, director or creators, runtime or seasons and episodes, India's age rating, language and release date (from TMDB, fetched when opened and kept a week). |
| ![Holding OK on Netflix in the drawer: Open, Move, Remove from favourites, App info](docs/screens/drawer-menu.jpg) | **App drawer.** Your favourite apps on one line; the tiles shrink so every app fits. Edit it the Google TV way: hold OK on an app for **Open, Move, Remove from favourites, App info**, and hold OK on any app under Apps to **Add to favourites**. |
| ![Top 10 tabs for each drawer app above the genre tabs](docs/screens/top10.jpg) | **Continue watching**, from the apps that publish it, with progress bars, ratings and the app's badge. Hold OK on a title to **remove it**: it stays gone across restarts and comes back only once you watch it again. Then **Top 10**: a tab per drawer app; Left and Right change the row. Under a row, the focused title's type, genres and year. |
| ![Top 10 by genre: Sci-Fi, with titles from Netflix, Prime Video, JioHotstar and Apple TV](docs/screens/genres.jpg) | **By genre.** Drama, Comedy, Action, Sci-Fi and more, each row taking your drawer apps in turn so no single app fills it. |
| ![The Apps grid with every other installed app](docs/screens/apps.jpg) | **Apps.** Every other installed app. |

The top bar has the clock and a short date (`THU, 29 OCT`), `Refreshing · 40%` under it while a refresh runs, and
icon buttons for Inputs, Wi-Fi, **Bluetooth** (it opens Remotes & Accessories) and Settings. Google's ambient
screensaver stays.

## Features

**Home**
- Header of new releases that follows the focused drawer app, with a new draw on every refresh, and a Read more
  popup with the plot, cast and crew.
- App drawer on one line, edited with a long press: move, remove, add, app info.
- Continue watching (removable with a long press), Top 10 per app, Top 10 by genre, and the full app grid.
- IMDb tag on every rated tile and in the header; type, genres and year in captions.
- Netflix's N as its small mark; poster backdrops behind the clock; focus lift, rings and glass.
- Bluetooth button straight to Remotes & Accessories; icon-only top bar.

**Ratings and data**
- IMDb ratings through the StreamRatings shared cache, then OMDb directly only while that cache is down.
- Ratings kept on the TV by release age: 3 days for titles from the last 3 months, 5 days up to a year and for
  series still airing, 15 days up to five years, 30 beyond; misses are retried after 5 days.
- New releases, popular titles and genre pools from TMDB, for India (`watch_region=IN`), for the drawer's apps only.
  When TMDB cannot be reached or has no token, the header's new releases come from JustWatch instead: what each
  app added in India, day by day.
- Genre and type from OMDb or TMDB; paired genres ("Action & Adventure") merged into one name.

**Ambient News** (screensaver)
- Now and then, a news story fills the screen between Google's screensaver wallpapers, full size: the photo, topic,
  publisher, headline, date and a QR code to read more on your phone. It never takes focus: Back closes the
  screensaver as usual, and the story goes with it.
- Sources: Google News (no account) and TheNewsAPI (free token), each on or off; stories without a photo at least
  1000 px wide, or older than a day, are skipped.
- Topics: India, World, Business & markets, Technology, Sports, Entertainment, Science and Health (the first, third
  and fourth start on); a story every 30 s to 10 min, for 15 to 30 s, set on the TV (Apps → Ambient News).
- Fetched on the TV every hour while its screen is on; TheNewsAPI at most 3 requests an hour (72 a day, inside its
  free 100). No computer needed once set up.

**TV clean-up**
- Samba TV (on-screen content tracking for ads), Sony promos and Sony Select, Google feedback, demo mode and four
  preloaded apps disabled, never uninstalled ([my-tv/debloat.txt](my-tv/debloat.txt)).
- AdGuard ad-blocking DNS (does not block YouTube ads).
- One command undoes all of it.

## How it works

```
 streaming apps ──(Watch Next, preview channels)──┐
 TMDB (new releases, popular, genres) ────────────┤
 JustWatch (new releases, when TMDB fails) ───────┤
 Google News / TheNewsAPI ─── Ambient News add-on → stories between the screensaver's wallpapers
 StreamRatings shared cache → OMDb (ratings) ─────┤
                                                  ▼
                                Feed add-on (io.github.rohan9405.feed)
                                  refreshes, ranks, caches posters
                                                  │  content provider, signature-checked
                                                  ▼
                                Launcher (offline) → Home
```

- The **launcher** ([launcher/](launcher)) never goes online. It reads the Feed add-on's provider, which answers
  only apps signed with the same key, because the rows show what you watch. It also tells the add-on which apps
  are in the drawer.
- The **Feed add-on** ([addons/tv-feed](addons/tv-feed)) holds the internet permission. It refreshes when Home
  opens and its data is older than 20 minutes, when an app joins the drawer, or when you press **Refresh now** on
  its screen (Apps → Feed). There is no background service.
- The **Ambient News** add-on ([addons/ambient-news](addons/ambient-news)) waits for Google's screensaver and shows
  its stories over it in a window that never takes focus (Android's overlay permission, granted once from the
  computer). It keeps the last day's stories and their photos on the TV.
- The **Original Home** add-on ([addons/original-home](addons/original-home)) is a tile that opens the TV's own
  home screen.

| Data | Refreshed |
|---|---|
| Continue watching, the apps' own rows | By each app, usually when you use it; Home re-reads them at most every 20 min |
| New releases, popular titles (TMDB) | Every 6 hours |
| Genre pools, new seasons (TMDB) | Every 24 and 12 hours; a film's first release date is kept for a month |
| IMDb ratings | 3 to 30 days by release age |
| Posters | Downloaded once, kept on the TV, loaded per tile as it scrolls into view |

A normal refresh takes a few seconds in the background. Home shows the last saved rows at once; Continue watching
and the apps' own rows update within a second or two, then TMDB is asked about every drawer app at the same time.

### Where recommendations come from

- **Personal to you:** Continue watching, the rows the apps publish (Prime Video, JioHotstar, ZEE5, Apple TV,
  Crunchyroll) and YouTube's Subscriptions. The apps build these from your account.
- **Not personal:** the header's films and shows, the Netflix and SonyLIV tabs (those apps publish no rows) and the
  genre pools come from TMDB, ranked by IMDb rating and popularity in India.

## Set it up

You need an Android TV or Google TV with network debugging on (Settings → Device Preferences → About → press OK on
Build 7 times, then Developer options → USB and network debugging), and a computer on the same Wi-Fi with JDK 17
and the Android SDK (platform 34, build-tools 34). [tools/docker-build.md](tools/docker-build.md) builds in Docker
instead.

```bash
git clone https://github.com/rohan9405/bravia-home && cd bravia-home
python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt
export TV_HOST=<tv ip>

# 1. Clean up the TV (back up first: tools/disable.sh records what it changes, tools/restore.sh undoes it).
tools/disable.sh my-tv/debloat.txt
# 2. Install the launcher, the add-ons, and the theme that turns this Home on:
STOCK_LAUNCHER=none launcher/build-and-install.sh
tools/install-addon.sh tv-feed
tools/install-addon.sh original-home
tools/install-addon.sh ambient-news
tools/venv/bin/python -I tools/tv.py 'appops set io.github.rohan9405.ambient SYSTEM_ALERT_WINDOW allow' 
tools/venv/bin/python -I tools/tv.py 'pm grant io.github.rohan9405.feed android.permission.READ_TV_LISTINGS'
tools/theme.sh my-tv/theme
# 3. API keys stay on your computer (~/.cleantv-private, never in the repo) and are sent over ADB:
my-tv/api-keys.sh save omdb      # free key from omdbapi.com
my-tv/api-keys.sh save tmdb      # TMDB API Read Access Token, free at themoviedb.org
my-tv/api-keys.sh save news      # optional: TheNewsAPI token, free at thenewsapi.com, for Ambient News
my-tv/api-keys.sh send
```

On Google TV, the stock launcher keeps the Home button while it is enabled. [my-tv/README.md](my-tv/README.md) has
the hand-over steps used on the BRAVIA and everything applied there. Turn network debugging off when you are done;
`python3 tools/adb-closed.py <tv ip>` confirms the port is closed. [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md)
covers what can go wrong on different TVs.

**Preview first:** an Android TV emulator runs the same builds. The Feed add-on's demo mode fills Home with sample
rows when the emulator has none of your apps:

```bash
adb shell am start -n io.github.rohan9405.feed/.FeedActivity --ez demo true
```

## Switching back

| What | How |
|---|---|
| The TV's own home screen as default | `TV_HOST=<tv ip> tools/switch-home.sh stock` (`clean` switches back) |
| Undo everything: apps, home, screensaver, DNS | `TV_HOST=<tv ip> my-tv/restore.sh` |
| Only the original launcher look | `TV_HOST=<tv ip> tools/theme.sh --reset` |

## Limits

- **Netflix and SonyLIV publish no rows**, so their tabs show TMDB's popular titles in India, and their titles open
  the app's home, not the title.
- **"New"** means new films, new shows and new seasons. TMDB does not record when an older catalogue title was added
  to an app, and a show airing weekly episodes of a season that began earlier is not new.
- **YouTube's in-app recommendations are not available** to other apps; Home uses the rows YouTube publishes to the
  TV.
- **No "add to wishlist" in the apps:** Netflix's My List and Prime's Watchlist live in their accounts, with no way
  for another app to add to them.

## Credits

Built on [CleanYourTV](https://github.com/Gathin23/cleanyourtv) by Gathin23 and its contributors: the launcher this
Home is built into, the debloat list and tools, and their safety checks. MIT licence, as the original. Ratings via
OMDb and the StreamRatings cache; titles, artwork and genres via TMDB, and via JustWatch when TMDB is unavailable. This product uses the TMDB API but is not
endorsed or certified by TMDB.
