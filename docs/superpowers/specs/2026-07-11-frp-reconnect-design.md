# frp Automatic Reconnect Design

## Problem

The Android service starts frpc immediately, including during boot when Android may not yet have a usable default network. The bundled frpc command exits after its first failed login, while the app's delayed restart thread can be suspended for minutes by Android power management. The client also selects one DNS server at process startup, so a Wi-Fi/mobile-data transition can leave it trying to reach a DNS server that is no longer routable.

The frps hostname points to a home broadband connection whose public IP can change after router reconnects. The client must therefore keep using the configured hostname and must not cache a resolved server IP.

## Goals

- Recover automatically when the phone boots before networking is ready.
- Recover promptly after Wi-Fi/mobile-data transitions.
- Recover when the home router restarts or the frps hostname resolves to a new public IP.
- Preserve all existing frp settings and avoid logging credentials.
- Keep the web log useful by distinguishing temporary network waiting from actual configuration or authentication failures.

## Design

`FrpClient` will launch frpc with initial-login failure set to retry instead of exit. frpc will continue reconnecting and resolving the configured hostname, so a changed home broadband IP is discovered without storing an IP in app configuration.

`FrpClient` will register one application-scoped Android network callback. When a default network becomes available or its link properties change, the callback will debounce events and reevaluate the runtime configuration. If the selected DNS server or network generation changed, the existing frpc process will be restarted so it no longer uses a stale DNS route. Losing all networks will update status/logging but will not create a rapid restart loop.

Process-exit recovery remains as a fallback for crashes and non-network failures. It will use a scheduled executor rather than a sleeping thread, deduplicate pending retries, and apply a bounded backoff. A successful `start proxy success` event resets the backoff.

## Error Handling And Logs

- A launch while offline is logged as waiting for network, not as a permanent frp configuration failure.
- Network recovery logs one reconnect reason and starts one debounced restart.
- frpc output continues to be recorded, but repeated identical transient network errors are rate-limited to avoid flooding the web log.
- Authentication, duplicate proxy, invalid port, and other non-transient errors remain visible unchanged.
- No token or other secret is added to command diagnostics or committed files.

## Verification

Automated tests will cover command construction, transient-error classification, retry/backoff decisions, and DNS/network-change restart decisions. Device verification will cover:

1. Start the app while networking is unavailable, then enable a network and confirm the tunnel connects without manual action.
2. Switch between Wi-Fi and mobile data and confirm the tunnel reconnects using the current network DNS.
3. Interrupt frps reachability, restore it, and confirm hostname resolution and proxy startup recover automatically.
4. Confirm the public `/health` endpoint and web frp logs report the final healthy state.

Testing a real home public-IP change requires the router/DDNS side to change; locally we will verify that no resolved IP is cached and that each reconnect continues to pass the hostname to frpc.
