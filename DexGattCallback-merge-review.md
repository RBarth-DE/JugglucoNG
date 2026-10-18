# `DexGattCallback.java` — merge resolution note (review request)

We merged `upstream/main` into my fork. For `DexGattCallback.java` we kept our
version and ported one thing from yours. There's also a bug we found in our own
G7 recovery work that you should know about before considering any of it.

## Bug we found (please read first)

A fresh sensor could bind to **the wrong physical device** and then never
recover.

- `searchforDeviceAddress()` only nulled the address string. The
  `BluetoothDevice` handle and the native store survived, and
  `resolveActiveDevice()` treats any one of those three as authoritative — so it
  re-dialled the very device we'd just rejected. Every failed attempt re-dialled
  the same wrong MAC.
- The `DexGattCallback` constructor restored `getDeviceAddress(dataptr, false)`
  unconditionally. For a record that has never authenticated, that address is
  just whichever G7 advertised first.

Net effect: a complete, *valid* cert exchange against the wrong device, a key
derived from the correct PIN, and an auth echo that can never match. `validate3`
passes because it checks the certificates, not the PIN — so the failure looks
like a crypto problem and isn't.

Fixed by clearing all three forms of identification in
`searchforDeviceAddress()`, and restoring a stored address only once
`DexDeviceName` is set (i.e. after first successful auth). Before then we clear
it and scan — which is what your code does, and it verified on first try against
the right device.

**Check:** with a sensor that has never been authenticated on the device, does
the first connection scan rather than dial a stored MAC?

## Kept from ours

### 1. Reconnects are scheduled with `AlarmManager`, not the executor (`scheduleDexReconnect`)

`connectToActiveDevice(cb, delay)` goes through a `ScheduledExecutorService`,
which does not run while the CPU is suspended. We use `setAlarmClock` (falling
back to `setExactAndAllowWhileIdle`) and only fall back to `connectToActiveDevice`
if `setalarm` returns null.

*Check:* with the phone locked, does the next connect land within ~5 min of the
sample?

*Caveat:* on your build we also saw the 5-second reconnect after a status-19 fail
auth (`dex8AES =true not verified` right after a successful `verified`). That
looks like a separate bug in the shared reconnect path — worth a look
independently of this.

### 2. `unbond()` is gated on `phase == GetData` (three sites)

A status-19 drop after a live link is the G7 going quiet until its next slot, not
a pairing failure. Unbonding there removes the bond and the following
`autoConnect` never completes (2026-10-09: `dex8AES verified` → status 19 →
`BOND_NONE` → 4 minutes dark). Key reset via `resetCerts()` is enough recovery
during the auth phases.

*Check:* does this suppress a bond-recovery path you still want? Our test asserts
unbond fires only from the GetData-phase empty-session loop, never from a
pre-data drop.

### 3. `attemptEverConnected` distinguishes "reached the sensor" from `connectedAttempt`

`close()` without a `DISCONNECTED` callback left a stale GATT that looked like a
reached sensor. This flag drives (a) status-133/147 → fall back to background
connect and (b) which unbond branch runs. Reset in `close()`.

### 4. `directConnectUnreachable` persists across process death (`SharedPreferences("dex_connect")`)

Without it every cold start burned another 30 s direct connect before falling
back to `autoConnect`. Paired with a quiet-reconnect floor so we don't re-dial at
0 ms into the post-19 silence.

**Please pair it with the bug fix above if you take it.** On its own, persisting
"prefer background connect to this address" across process death is what pinned
us to the wrong sensor's MAC.

## Ported from yours

- `connected = false` in `close()` — ours reset `attemptEverConnected` but left
  `connected` set after a close with no `DISCONNECTED` callback.

## Also fixed while diagnosing

- `equalpart()` could throw `IllegalArgumentException` on TIRAMISU+
  (`Arrays.equals` on an out-of-range slice) when a notification was under 9
  bytes. The pre-TIRAMISU branch guarded this; the new one didn't. A short
  notification is "not a match", not an exception.
- The verify no longer conflates identity with challenge presence. Identity is
  the echo of `random8` at `value[1..8]`; a short packet can't be answered but
  must not make us scrap a sensor whose echo matched.

## Unchanged from before

- Strings and UI taken wholesale from you (Expressive,
  `collectAsStateWithLifecycle`, `LiveReadingLanes`).
- Your `test-dexcom-connect.py` harness is much better than ours (brace-masking
  extractor, `dex8AES` bounds check, roster snapshot tests); we took it and
  retargeted the extraction list at our method names. The one assertion we
  changed is the `ChallengeReply` unbond expectation in
  `preservesBondBeforeFirstReading`, to match item 2.
- `test-dexcom-native.py`, `test-dexcom-connect.py`, `test-libre2/3-connect.py`,
  `test-air-driver.py`, and the three unit test classes all pass.
