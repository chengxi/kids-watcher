# Parent Events

The dashboard's **Events** feed includes noise alerts, movement, monitoring
start/stop, and monitor connections. It has no unread status or acknowledgement
control. The time and device name accompany every event; date headings use the
parent phone's local timezone.

Android event notifications also name the source device in their title, such as
“Noise in Pixel 6” or “Pixel 3 is offline,” including the top-of-screen banner.
The alert service loads names from `/monitors` on reconnect and caches them for
service restarts. A first event from an unknown device triggers another lookup
without blocking socket messages or live media. If that lookup fails, the title
uses “Monitor” plus a shortened device ID so sources remain distinguishable.

### Notifications say “the monitor” instead of a device name

An older Android parent can show device names on the dashboard while posting
notifications such as “Noise in the monitor.” That build keeps notification
names only in memory and waits for a `monitor_status` WebSocket message that
the hub does not emit. The current parent loads names through `/monitors` and
caches them per pairing, including across service restarts.

Update the parent APK in place (`adb -s <parent-serial> install -r <parent-apk>`)
and reopen the parent to resume its alert service. Preserve app data and pairing;
do not clear the app, rename devices, or re-pair monitors. A hub or monitor update
is unnecessary for this fix. Verify that the parent's name cache has populated
and that the next real alert identifies its source. An old, already-posted
notification keeps its previous title until replaced or dismissed.

## Long histories: decision

Use **automatic loading backed by pagination**, with a bounded in-memory window.

| Approach | Decision and reason |
| --- | --- |
| Numbered pages or Next/Previous buttons | Avoid in the UI: they interrupt browsing an overnight timeline. Pagination still underlies loading. |
| Load older events while scrolling | Use: newest first, fetching small pages near the end; filters stay available as a sticky heading. |
| Trim the client list | Trim loaded pages in memory; retain saved history on disk so old events remain reachable and usable offline. |

The parent uses Room and Android Paging 3: pages of 50, initial load of 100,
prefetch within 10 rows, and a best-effort maximum loaded window of 200 events.
Day headings are inserted between events. Scrolling back reloads dropped pages
from SQLite. The former newest-200-only display cutoff is removed. The small
recent-events state used elsewhere on the dashboard remains bounded as well.
This makes memory and rendering scale independently of the total saved rows.

Hub backfill already uses an increasing event-ID cursor with at most 200 rows
per request. Reconnect recovery drains it to completion; manual Refresh now
also drains full pages. Live arrivals are upserted by ID, invalidate Room pages,
and preserve the reader's current scroll position. They do not duplicate rows.
Older on-screen pages load from the local database, including offline; paging
errors offer Retry. End of saved events is explicit.

The hub retains events for 30 days under the current deployment configuration.
The parent retains previously downloaded event metadata without a new disk
pruning rule. Pagination is not a disk retention policy: if a future installation
needs one, choose and expose its retention period separately rather than quietly
hiding or deleting events after a row count. Events that expired on the hub before
this parent downloaded them cannot be recovered.

## Filters

Defaults are **All devices** and **All events**. Either can change independently,
and both combine. Types are All events, Noise alerts, Connection (offline and
back online), Movement, and Monitoring (started/stopped). Filters apply in SQLite
before paging across the entire cache, including old events beyond the first 200.
Parent schema version 4 adds device/type indexes without deleting history.

Device choices combine current monitors with monitor IDs found in saved events;
an unavailable device name falls back to a shortened ID. Empty matches show a
Clear filters action. Changing filters returns to the Events heading. Filtering
the feed does not change the noise graph, hub subscription, recording, or
notifications. Selections last for the current dashboard ViewModel session;
reopening the app starts with All.

## Back online

A first monitor connection or quick reconnect is not an attention event. After
more than the existing 45-second liveness window without monitor traffic, the
hub records MONITOR_LOST once for that last-seen time. The next authenticated
monitor connection records MONITOR_ONLINE once and clears the outage marker.
A later outage can produce a new pair. The event means the hub connection
recovered; camera/microphone health remains represented by capture and noise
history, rather than inferred from the connection event.

Hub schema version 3 persists outage markers across hub restarts. Migration
preserves already-reported pending losses; a monitor returning from a long hub
outage also records the loss/recovery pair if it arrives before the first watchdog
sweep. These connection events are generated by the hub, not by monitor messages.
Lost/online events use the same fanout and authenticated history as noise events.

The parent shows recovery in green as “DEVICE is back online.” Android posts a
quiet recovery notification on a low-importance channel and dismisses only that
device's still-active offline notifications. The offline alarm remains urgent.
Existing notifications created before monitor metadata was included cannot be
safely associated with one device and are left for the user to dismiss.

## Verification

Automated checks cover browsing 600 saved events, device plus category matching
beyond the former display cutoff, live Room invalidation, multi-page backfill,
filter changes/day keys, upgrades preserving histories, quiet recovery and
per-device notification dismissal, first/quick reconnects, outage/recovery fanout,
restart deduplication, long hub outages, and migration of pending outages.

On 2026-10-09, the parent update was installed on the paired Pixel 8 Pro and
hub support deployed on the hub host. On-device checks confirmed the Events
heading, both All defaults, the sticky filter bar, saved Connection results,
device selection, and the combined Pixel 6 + Noise alerts result. Defaults were
restored afterward. Both expected phones' last-seen timestamps and fresh monitor
noise samples advanced across two post-deployment checks; SQLite quick_check
passed, the persistent volume remained attached, and TLS fingerprint was unchanged.
Recovery event generation was exercised in integration tests; no synthetic events
were inserted into the deployed history for this UI check.

On 2026-10-12, inspection of the installed Pixel 8 Pro parent APK confirmed the
older in-memory notification-name path and its “the monitor” fallback. The
current Android parent was installed with `install -r`; the encrypted pairing
credential file was unchanged, the foreground alert service reconnected, and
the persistent name cache contained all three existing monitors. Build, lint,
192 parent tests and 126 shared tests passed (four optional shared live tests
skipped). A microphone-construction permission handler was added to clear a
pre-existing lint error before installation. No synthetic hub events were
created, and a fresh real alert after this update has not yet been observed.
