# frp Automatic Reconnect Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Android frpc tunnel recover automatically from startup without networking, phone network changes, home-router restarts, and DDNS address changes.

**Architecture:** Generate a private TOML frpc configuration with `loginFailExit = false` and keep the configured hostname instead of resolving it in the app. Extract pure Java configuration and reconnect-policy helpers for unit tests, then let `FrpClient` coordinate Android network callbacks, debounced restarts, process backoff, and transient-log rate limiting.

**Tech Stack:** Java 8, Android SDK 23+, frpc 0.69.1, JUnit 4, Gradle Android plugin.

## Global Constraints

- Never cache or persist the resolved frps public IP; always pass the configured hostname to frpc.
- Store generated TOML only under the app-private files directory.
- Do not log or commit frp tokens, SMTP credentials, or real endpoint configuration.
- Preserve the existing runtime settings UI and configuration keys.
- Keep non-network frpc errors visible in the web log.

---

### Task 1: Testable frpc Configuration And Reconnect Policy

**Files:**
- Create: `android/app/src/main/java/dev/dbevil/remotesms/FrpRuntimeSupport.java`
- Create: `android/app/src/test/java/dev/dbevil/remotesms/FrpRuntimeSupportTest.java`
- Modify: `android/app/build.gradle`

**Interfaces:**
- Produces: `FrpRuntimeSupport.toml(...)`, `FrpRuntimeSupport.isTransientNetworkError(String)`, `FrpRuntimeSupport.retryDelayMs(int)`, and `FrpRuntimeSupport.shouldRestartForNetwork(String, String, boolean)`.

- [ ] **Step 1: Add JUnit and write failing tests**

```java
@Test public void tomlKeepsHostnameAndRetriesInitialLogin() {
    String text = FrpRuntimeSupport.toml("frp.example.test", 7000, 65439,
            "token-value", "192.0.2.53", "remote_sms_test", 8787);
    assertTrue(text.contains("serverAddr = \"frp.example.test\""));
    assertTrue(text.contains("loginFailExit = false"));
    assertFalse(text.contains("resolvedServerIp"));
}

@Test public void transientErrorsAreClassifiedWithoutHidingAuthErrors() {
    assertTrue(FrpRuntimeSupport.isTransientNetworkError("connect: network is unreachable"));
    assertTrue(FrpRuntimeSupport.isTransientNetworkError("no such host"));
    assertFalse(FrpRuntimeSupport.isTransientNetworkError("token in login doesn't match token from configuration"));
}

@Test public void retryDelayIsBounded() {
    assertEquals(5_000L, FrpRuntimeSupport.retryDelayMs(0));
    assertEquals(60_000L, FrpRuntimeSupport.retryDelayMs(20));
}

@Test public void networkRecoveryOrDnsChangeRequiresRestart() {
    assertTrue(FrpRuntimeSupport.shouldRestartForNetwork("", "192.0.2.53", true));
    assertTrue(FrpRuntimeSupport.shouldRestartForNetwork("192.0.2.53", "198.51.100.53", true));
    assertFalse(FrpRuntimeSupport.shouldRestartForNetwork("192.0.2.53", "", false));
}
```

Add `testImplementation "junit:junit:4.13.2"` to `android/app/build.gradle`.

- [ ] **Step 2: Run the focused test and verify RED**

Run: `cd android && ANDROID_HOME=/home/dbevil/apps/remote/.tools/android-sdk /tmp/gradle-8.7-download/gradle-8.7/bin/gradle testDebugUnitTest --tests dev.dbevil.remotesms.FrpRuntimeSupportTest`

Expected: FAIL because `FrpRuntimeSupport` does not exist.

- [ ] **Step 3: Implement the pure Java helper**

Implement TOML escaping for quotes, backslashes, CR, and LF. Generate this shape without resolving `serverAddr`:

```toml
serverAddr = "frp.example.test"
serverPort = 7000
loginFailExit = false
dnsServer = "192.0.2.53"
auth.method = "token"
auth.token = "token-value"

[[proxies]]
name = "remote_sms_test"
type = "tcp"
localIP = "127.0.0.1"
localPort = 8787
remotePort = 65439
```

Classify DNS resolution failures, unreachable routes, refused connections, and timeouts as transient. Use exponential process-exit delays of 5, 10, 20, 40, then 60 seconds maximum.

- [ ] **Step 4: Run focused and full unit tests and verify GREEN**

Run: `cd android && ANDROID_HOME=/home/dbevil/apps/remote/.tools/android-sdk /tmp/gradle-8.7-download/gradle-8.7/bin/gradle testDebugUnitTest`

Expected: BUILD SUCCESSFUL and all tests pass.

### Task 2: Integrate Durable Process And Network Recovery

**Files:**
- Modify: `android/app/src/main/java/dev/dbevil/remotesms/FrpClient.java`
- Test: `android/app/src/test/java/dev/dbevil/remotesms/FrpRuntimeSupportTest.java`

**Interfaces:**
- Consumes: all `FrpRuntimeSupport` methods from Task 1.
- Produces: a single app-scoped network callback, private `frpc.toml`, deduplicated restart scheduling, and rate-limited transient logs.

- [ ] **Step 1: Extend tests for TOML escaping and log throttling decisions**

```java
@Test public void tomlEscapesSecretsAndNames() {
    String text = FrpRuntimeSupport.toml("host", 7000, 65439,
            "a\"b\\c\n", "192.0.2.53", "proxy\"name", 8787);
    assertTrue(text.contains("auth.token = \"a\\\"b\\\\c\\n\""));
    assertTrue(text.contains("name = \"proxy\\\"name\""));
}

@Test public void repeatedTransientLogIsRateLimited() {
    assertTrue(FrpRuntimeSupport.shouldLogTransient("network is unreachable", "", 0, 1_000));
    assertFalse(FrpRuntimeSupport.shouldLogTransient("network is unreachable",
            "network is unreachable", 1_000, 2_000));
    assertTrue(FrpRuntimeSupport.shouldLogTransient("network is unreachable",
            "network is unreachable", 1_000, 301_000));
}
```

- [ ] **Step 2: Run focused test and verify RED**

Run the Task 1 focused test command.

Expected: FAIL because `shouldLogTransient(...)` is missing and escaping assertions are not yet satisfied.

- [ ] **Step 3: Replace CLI proxy launch with private TOML launch**

Write `frpc.toml` atomically in `files/frp`, set owner-only readability where supported, and launch:

```java
List<String> command = new ArrayList<>();
command.add(binary.getAbsolutePath());
command.add("-c");
command.add(configFile.getAbsolutePath());
```

Keep `runtime.serverAddr` as a hostname in TOML. Never place the resolved IP into configuration or `activeSignature`.

- [ ] **Step 4: Add debounced Android network monitoring**

Register one `ConnectivityManager.NetworkCallback` from the singleton constructor. On `onAvailable` and `onLinkPropertiesChanged`, schedule one refresh after 1 second. Refresh only when a connected network exists; restart when the current DNS differs from `activeDnsServer` or frpc is not running.

Use `registerDefaultNetworkCallback` on API 24+ and an INTERNET-capable `NetworkRequest` on API 23. Log `网络已恢复，刷新 frp 域名解析` once per debounced event.

- [ ] **Step 5: Replace sleeping exit watcher with scheduled backoff**

Use one `ScheduledExecutorService` and one pending `ScheduledFuture`. Increment the failure count after unexpected exits, reset it on `start proxy success`, and schedule using `FrpRuntimeSupport.retryDelayMs(failureCount)`. Cancel stale retries during manual stop or configuration restart.

- [ ] **Step 6: Rate-limit transient frpc lines**

Always update `lastError` and `lastInfo`, but write identical transient errors to `AppLog` at most once every 5 minutes. Never rate-limit authentication, configuration, duplicate-proxy, successful-login, or successful-proxy lines.

- [ ] **Step 7: Run tests and build APK**

Run: `cd android && ANDROID_HOME=/home/dbevil/apps/remote/.tools/android-sdk /tmp/gradle-8.7-download/gradle-8.7/bin/gradle testDebugUnitTest assembleDebug`

Expected: BUILD SUCCESSFUL.

### Task 3: Real Device Recovery Verification

**Files:**
- Verify: `android/app/build/outputs/apk/debug/app-debug.apk`
- Verify: device app-private `files/remote-sms.log`

**Interfaces:**
- Consumes: APK produced by Task 2 and the already connected Android device.
- Produces: evidence that offline startup and network restoration recover without manual frp restart.

- [ ] **Step 1: Install the APK without clearing app data**

Run: `/home/dbevil/apps/remote/.tools/android-sdk/platform-tools/adb install -r android/app/build/outputs/apk/debug/app-debug.apk`

Expected: `Success`; existing runtime credentials remain device-local.

- [ ] **Step 2: Verify generated config without exposing credentials**

Run an app-private check that reports only whether `loginFailExit = false`, the configured server value is not an IPv4 literal, and the proxy local/remote ports exist. Do not print the TOML file because it contains the token.

Expected: all checks report true.

- [ ] **Step 3: Reproduce offline startup, then recover**

Disable Wi-Fi and mobile data, force-stop/start the app, and confirm the log reports waiting/retry without a permanent exit loop. Restore the original network settings and wait up to 30 seconds.

Expected: logs show `login to server success` and `start proxy success` without pressing the manual frp restart button.

- [ ] **Step 4: Verify public health and final state**

Query the configured public `/health` URL without printing any query credentials, and inspect the app's `/api/device` tunnel snapshot locally.

Expected: HTTP 2xx, `running=true`, a non-null recent `lastConnectedAt`, and no unresolved current `lastError`.

- [ ] **Step 5: Run repository safety checks**

Run: `git diff --check`, `git status --short`, and a repository search for the known real SMTP credential and addresses.

Expected: no whitespace errors, only intended source/test/plan changes, and no credential matches in tracked changes.
