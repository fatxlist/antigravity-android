# Network (`com.agy.net`)

## Overview
Provides Android network discovery and connectivity observation utilities for the runtime environment. `DnsCollector` extracts and orders active and system DNS server addresses from `ConnectivityManager` with public fallbacks, and `NetworkMonitor` observes default network availability and link property changes on API 24+ and notifies a registered listener.

## Interface
- `public final class DnsCollector` — non-instantiable DNS discovery utility `[DnsCollector.java:DnsCollector]`
- `public static List<String> collectDnsServers(Context context)` — returns ordered DNS server IPs across the active network and all networks, IPv4 before IPv6, falling back to `["1.1.1.1", "8.8.8.8", "9.9.9.9"]` `[DnsCollector.java:collectDnsServers]`
- `public final class NetworkMonitor` — lifecycle-managed wrapper over `ConnectivityManager.registerDefaultNetworkCallback` `[NetworkMonitor.java:NetworkMonitor]`
- `public interface NetworkMonitor.Listener` with `void onNetworkChanged()` — callback triggered on default network availability, loss, or link property updates `[NetworkMonitor.java:Listener]` `[NetworkMonitor.java:onNetworkChanged]`
- `public NetworkMonitor(Context context, Listener listener)` — retains `context.getApplicationContext()` and the listener `[NetworkMonitor.java:NetworkMonitor]`
- `public synchronized void register()` — idempotently registers a default `ConnectivityManager.NetworkCallback` (`onAvailable`, `onLost`, `onLinkPropertiesChanged`) on API `>= Build.VERSION_CODES.N` `[NetworkMonitor.java:register]`
- `public synchronized void unregister()` — idempotently unregisters the active network callback and clears stored references `[NetworkMonitor.java:unregister]`

## Invariants
- `DnsCollector` is `final` with a private no-arg constructor `[DnsCollector.java:DnsCollector]`
- `collectDnsServers(Context)` always returns a non-null, non-empty `List<String>`, defaulting to `1.1.1.1`, `8.8.8.8`, `9.9.9.9` when `ConnectivityManager` is unavailable, throws, or yields no valid servers `[DnsCollector.java:collectDnsServers]`
- Active network DNS servers (`getActiveNetwork()`) are scanned before `getAllNetworks()`, all `Inet4Address` entries precede all `Inet6Address` entries, and addresses are deduplicated per family `[DnsCollector.java:collectDnsServers]` `[DnsCollector.java:addDns]`
- Null `LinkProperties`, null `InetAddress`, empty host strings, scoped IPv6 addresses containing `%`, and non-IPv4/IPv6 addresses are skipped `[DnsCollector.java:addDns]`
- `collectDnsServers(Context)` catches `SecurityException` and `Exception` rather than propagating them `[DnsCollector.java:collectDnsServers]`
- `NetworkMonitor` stores `context.getApplicationContext()` instead of the caller's `Context` `[NetworkMonitor.java:NetworkMonitor]`
- `NetworkMonitor.register()` and `unregister()` are `synchronized`, idempotent, and catch `SecurityException` / `Exception`; `register()` is a no-op if already registered, if `Build.VERSION.SDK_INT < Build.VERSION_CODES.N`, or if `ConnectivityManager` is null `[NetworkMonitor.java:register]` `[NetworkMonitor.java:unregister]`
- `registered` becomes `true` only after `registerDefaultNetworkCallback` succeeds; `unregister()` resets `registered = false`, `callback = null`, and clears `connectivityManager = null` in `finally` `[NetworkMonitor.java:register]` `[NetworkMonitor.java:unregister]`
- Exceptions thrown by `listener.onNetworkChanged()` are caught inside `notifyChanged()` and never escape the `NetworkCallback` `[NetworkMonitor.java:notifyChanged]`

## Side Effects
- Queries `Context.CONNECTIVITY_SERVICE` (`ConnectivityManager`) via `getActiveNetwork()`, `getAllNetworks()`, and `getLinkProperties(Network)` `[DnsCollector.java:collectDnsServers]`
- Registers and unregisters a system `ConnectivityManager.NetworkCallback` on `ConnectivityManager` and invokes `listener.onNetworkChanged()` on network changes `[NetworkMonitor.java:register]` `[NetworkMonitor.java:unregister]` `[NetworkMonitor.java:notifyChanged]`
- Emits Android `Log.i` / `Log.w` messages under tags `"DnsCollector"` and `"NetworkMonitor"` `[DnsCollector.java:collectDnsServers]` `[NetworkMonitor.java:register]` `[NetworkMonitor.java:unregister]` `[NetworkMonitor.java:notifyChanged]`

## Verification
- Run: `bash app/src/test/run_java_tests.sh`
