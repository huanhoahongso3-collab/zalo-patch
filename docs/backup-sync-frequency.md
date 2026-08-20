# Backup sync frequency: what's shipping

Short version of `BackupFrequencyFeature.java`. Addresses GitHub issue #5.

## What was found

Zalo's local message-database backup (`com.zing.zalo.db.backup.BackupRestorableDbWorker`,
a `androidx.work.Worker`) is scheduled once, at app startup, as a WorkManager
`PeriodicWorkRequest` with a hardcoded **24-hour** interval and unique work name
`"BackupRestorableDb"` / `ExistingPeriodicWorkPolicy.KEEP`. Confirmed by decompiling the
live installed APK (Zalo 26.08.01, versionCode 260801903):

```
new PeriodicWorkRequest.Builder(BackupRestorableDbWorker.class, 24, TimeUnit.HOURS)
    .setConstraints(...)
    .build();
WorkManager.getInstance(context)
    .enqueueUniquePeriodicWork("BackupRestorableDb", ExistingPeriodicWorkPolicy.KEEP, request);
```

This confirms the premise of the issue: by default, a device failure or DB corruption can
lose up to a day of message history before the next scheduled backup.

## Shipped

| Setting | What it does |
|---|---|
| `messages.increase_backup_frequency` | Reschedules the same worker to run every **4 hours** instead of 24. |

The hook targets the WorkManager `PeriodicWorkRequest.Builder` constructor
(`Builder(Class, long, TimeUnit)`) rather than Zalo's own scheduler class. This is
deliberate: the constructor's signature is fixed by the androidx.work API contract, so it
doesn't drift between Zalo builds the way Zalo's own (R8-renamed) wrapper class does.
`beforeHookedMethod` checks the constructor's first argument (`Class<?>` being scheduled) —
only when it's exactly `BackupRestorableDbWorker.class` does it overwrite the interval
(`args[1]`) and unit (`args[2]`) in place before the constructor runs. Every other periodic
work request in the app (there may be several) passes through untouched.

4 hours was picked as a balance: well above WorkManager's own 15-minute periodic-work floor,
and per the issue's own reasoning, unlikely to add meaningfully to battery/network use since
Zalo already keeps a background keep-alive service running regardless.

## Not shipped (from the original issue)

The issue also asked to disentangle media backup (synced to the user's own Google Drive)
from text/message backup (synced through Zalo's own server), since they have very different
size/latency/reliability characteristics. This branch does not touch that — the worker hooked
here only handles the local SQLite `zalo_restorable.db` export/schedule, not the Drive media
upload path, which appeared to be a separate, more entangled subsystem (`backuprestore/woker/`,
Drive account/token classes) that wasn't investigated in this pass.

## Symbol drift

The obfuscated builder class name (`c7.l0` in the 26.08.01 build; androidx.work's
`PeriodicWorkRequest$Builder` gets renamed by R8 like everything else) is only mapped in the
schema profile for versionCode 260801903. Older/newer profiles have no entry for it, so on an
unmapped version the feature reports itself stale (`symbols.chat` missing) instead of guessing
at a class name — consistent with how the rest of this module handles version drift.

## Testing status

Implemented and committed without a build/install/on-device test pass (per this session's
request to skip building). Not yet confirmed live. Before relying on it: install, enable the
toggle, and confirm (via `adb logcat` with `debug.zalopatch=1`, watching for the
`messages.backup_frequency` self-check line) that the worker actually re-runs on a ~4-hour
cadence rather than 24.
