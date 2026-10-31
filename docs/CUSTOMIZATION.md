# Customising BRAVIA Home

Use the remote for everyday changes. The checked-in theme supplies defaults for this BRAVIA setup; an advanced theme can override additional launcher settings.

## Apps and navigation

| Action | Result |
|---|---|
| Focus a drawer app | Header switches to that service's eligible titles, or a mixed selection if none are available |
| Hold OK on a drawer app | Open, Move, Remove from favourites, App info |
| Hold OK on an Apps-grid tile | Open, Add to favourites, App info |
| Up from the drawer | Focus the header |
| Left / Right in the header | Previous / next title |
| OK on a title | Open its published link, with app-home fallback |
| Hold OK on a title | Read more; Continue watching also offers local removal |
| Focus a service or genre tab | Change that row |
| Focus the insight chip, then OK | Portfolio/market details |
| Hold OK on Settings | Launcher options and module-card controls, as available |

The Feed layout has its own responsive, one-line drawer and six-column Apps grid. The header turns every eight seconds, pauses while focused and shows a new selection on content refresh. These are implementation choices, not currently exposed as individual TV settings.

## Feed and insight settings

Open **Apps → Feed**.

| Option | Values / behaviour |
|---|---|
| Refresh now | Refresh recommendation rows; also shows source/refresh diagnostics |
| TV listings permission | Requested when missing; needed for Watch Next and app-published rows |
| Insight chip | On / Off; hides the top-bar chip when off |
| Portfolio | On / Off; when off, omit portfolio data from Home and stop subsequent holdings requests |
| Chip rotation | 10, 20, 30 or 60 seconds; pauses while focused |

An INDmoney connection is optional. The indices use the same connection even when Portfolio is off. Settings do not revoke the provider grant or erase historical caches. If the provider requires sign-in again, rerun `my-tv/indmoney-signin.py` and `my-tv/indmoney-pair.sh` from the computer.

The chip is on Home; it is not a financial screensaver overlay. There are no separate per-index switches, amount-masking selector, watchlist editor or on-TV QR reconnect flow in this version.

## Ambient News settings

Open **Apps → Ambient News**. These switches do not change your streaming recommendations or financial connection.

| Option | Choices | Default |
|---|---|---|
| News in the screensaver | On / Off | On after the optional add-on is installed/opened |
| Google News | On / Off | On |
| TheNewsAPI | On / Off; requires own token | On when a token is supplied |
| India | On / Off | On |
| World | On / Off | Off |
| Business & markets | On / Off | On |
| Technology | On / Off | On |
| Sports | On / Off | Off |
| Entertainment | On / Off | Off |
| Science | On / Off | Off |
| Health | On / Off | Off |
| How often | Random gap: 30–90 s, 1–3 min, 3–6 min, 5–10 min | 1–3 min |
| Each story stays | 15, 20, 30 seconds | 20 seconds |
| Start the screensaver now | Preview with the next available story after a few seconds | Manual action |

Sources and topics are filtered independently. No eligible stories means Google Ambient continues on its own. The settings screen reports ready-story counts and the last update; it never displays the news token itself. There is no required NewsAPI.org account: the optional second connector is TheNewsAPI at `thenewsapi.com`.

The add-on uses Android's display-over-other-apps permission, a foreground service and a boot receiver. It listens for dream start/stop events and screen-off. The behaviour is device-dependent; verify preview, Back, standby and reboot on your own TV. Turning the news switch off stops its service.

## Current theme

The supplied [config.json](../my-tv/theme/config.json) enables the Feed home and icon-only glass controls. Edit a personal copy, then apply it with `TV_HOST=<tv ip> tools/theme.sh <your-theme-folder>`.

| Key | Purpose / current setting |
|---|---|
| `dock_apps` | Default favourites by Android package: Netflix, YouTube, Prime Video, JioHotstar, SonyLIV, ZEE5 |
| `feed_package` | `io.github.rohan9405.feed`; selects the content-led Home layout |
| `hidden_apps` | Optional additional list of packages omitted from the app list |
| `columns` | `6`; a general launcher default; the Feed Apps grid currently also uses a fixed six columns |
| `fill_dock` | `false`; classic-layout dock filling; Feed maintains its own favourites |
| `module_cards` | `true`; enables discovery/consent for compatible app shortcut cards |
| `background_color` | `#090C14`; dark base colour |
| `wallpaper` | `false`; no theme wallpaper behind the classic layout; Feed uses content artwork |
| `cinematic_home` | `false`; the separate classic cinematic mode is not the Feed layout |
| `ambient_glow` | `false`; disables the classic app-colour ambient glow |
| `dock_glass` | `true`; general glass styling; Feed also draws its own glass drawer |
| `icon_only_buttons` | `true`; compact top-bar controls |
| `icon_only_inputs` | `true`; compact input button when inputs are available |
| `focus_overshoot` | `false`; general focus animation setting |
| `advanced_effects` | `true`; general advanced effects setting |
| `show_greeting` | `false`; hides the greeting above the clock |

Remote-edited favourites are saved separately and take precedence over the theme's initial drawer list. An uninstalled app is omitted rather than creating a dead shortcut.

## General launcher settings

Hold OK on Settings, then choose **Launcher settings**. The underlying launcher provides column count (4–7), rows below the dock (0–3), dock opacity (100/70/50/30), wallpaper selection, animations, advanced effects, Follow theme, default Home and TV settings. An optional Open on start choice appears only when configured.

Some of these belong to the classic dock/grid mode. They do not resize the Feed drawer or change its six-column grid, replace its title backdrops or adjust its carousel timing. Keep that distinction when experimenting.

For every supported advanced key and its explanatory comments, see [config.xml](../launcher/app/src/main/res/values/config.xml) and the parsing/validation in [Config.java](../launcher/app/src/main/java/com/gathin/tvhome/Config.java). The source covers locales, clock/date, inputs and device-specific remote actions as well as classic-layout visual options. Not every inherited option affects Feed Home, and hardware-dependent actions require testing on the target model.

## Keys, connections and public screenshots

Use `my-tv/api-keys.sh` for TMDB, OMDb and optional TheNewsAPI keys. Use the INDmoney sign-in/pairing scripts for finance. Never put actual tokens in the theme, README, commands saved to Git, or screenshots.

The README screenshots were captured from the current Android interface on an emulator, with a selected public catalogue, sample viewing progress, fictional financial figures and an illustrative news story. Sample app banners can differ from installed streaming apps. The device was not connected to a real portfolio for those captures. Keep raw captures and credentials out of `docs/screens/`; inspect public images before adding them.
