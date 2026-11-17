# spectral-java

Java client for the Spectral sidecar.

Every service instance runs next to a Spectral sidecar. Your service uses this client to talk to its own sidecar over HTTP: register itself, keep its lease alive, and look up other services. The sidecar handles everything else (gossip, failure detection, keeping the directory in sync across machines).

## Install

Releases are published through [JitPack](https://jitpack.io/#cap-theorem/spectral-java).

Maven:

```xml
<repositories>
  <repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
  </repository>
</repositories>

<dependency>
  <groupId>com.github.cap-theorem</groupId>
  <artifactId>spectral-java</artifactId>
  <version>v0.1.0</version>
</dependency>
```

Gradle (Kotlin):

```kotlin
repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.github.cap-theorem:spectral-java:v0.1.0")
}
```

Use `main-SNAPSHOT` as the version to track the latest `main`.

Java 17+. No runtime dependencies (uses `java.net.http`). The package to import is `io.github.captheorem.spectral`.

## Quickstart

```java
SpectralClient client = SpectralClient.create();

Lease lease = client.register(new Registration(
        "payments", "payments-1", "http://100.64.1.3:9007", Duration.ofSeconds(10)));

try (KeepAlive keeper = client.keepAlive(lease)) {
    Provider inventory = client.lookup("inventory");
    httpClient.send(HttpRequest.newBuilder(URI.create(inventory.endpoint() + "/stock")).build(), ofString());
} catch (ServiceNotFoundException e) {
    // nobody is serving inventory right now
}
```

A runnable version is in [`examples/Quickstart.java`](examples/Quickstart.java).

## Connecting to the sidecar

`SpectralClient.create()` uses the defaults. `SpectralClient.builder().addr(...).timeout(...).build()` sets them explicitly. The address is picked in this order:

1. `addr(...)`, if set
2. the `SPECTRAL_ADDR` environment variable
3. `http://127.0.0.1:8000`, the sidecar's default API port

`timeout` (default 10s) applies to each request. Proxies are not used, since the sidecar always runs next to your service. `SpectralClient` is thread-safe; create one and share it.

## API

| Method | What it does |
|---|---|
| `Lease register(Registration)` | Announce this instance. The sidecar spreads it to the whole network. |
| `void renew(String token)` | Extend the lease once. |
| `KeepAlive keepAlive(Lease)` | Renew every TTL/3 on a background daemon thread. Retries failed renewals; gives up with `LeaseExpiredException` if none succeed for a full TTL, or right away if the sidecar no longer knows the lease. |
| `Provider lookup(String service)` | Get one healthy instance of a service, answered from the sidecar's local copy of the directory. |
| `Status status()` | Whether the sidecar is ready, and its state. |

`Registration`, `Lease`, `Provider` and `Status` are records. `KeepAlive` is `AutoCloseable`: `stop()` or `close()` ends it, `isRunning()` tells you whether it is still renewing, and `failure()` holds the exception if it gave up.

If your instance stops renewing (crash, hang, keep-alive stopped), the lease expires and the instance disappears from every sidecar's directory. There is no explicit deregister: stop renewing, or shut the sidecar down.

## Errors

All exceptions are unchecked and extend `SpectralException`:

| Exception | Meaning |
|---|---|
| `InvalidRegistrationException` | The sidecar rejected the registration (missing fields, bad endpoint URL, non-positive TTL). |
| `AlreadyRegisteredException` | This registration already exists. |
| `ServiceNotFoundException` | No healthy instance of that service is known. |
| `LeaseExpiredException` | The lease is gone; register again. |
| `SidecarTimeoutException` | The sidecar accepted the request but did not answer in time. |
| `SidecarUnavailableException` | The sidecar could not be reached at all. |
| `ApiException` | Any other non-2xx response. Base class of the four HTTP exceptions above; has `op()`, `statusCode()` and `body()`. |

## Development

```sh
mvn verify
```

To use a local build from another project on the same machine, run `mvn install` and depend on `io.github.cap-theorem:spectral:0.1.0` (no extra repository needed).

## HTTP contract

This is the sidecar API the client speaks. Every Spectral SDK uses the same contract.

| Call | Request | Success | Errors |
|---|---|---|---|
| `POST /register` | `{"service", "instance_id", "endpoint", "ttl": "10s"}` | `201 {"token", "ttl", "expires_at"}` | `400` invalid, `409` exists, `504` timeout |
| `POST /renew` | `{"token"}` | `202` | `404` unknown or expired lease, `504` timeout |
| `GET /lookup?service=<name>` | | `200 {"service", "instance_id", "endpoint"}` | `404` no provider, `504` timeout |
| `GET /status` | | `200 {"ready", "state"}` | `504` timeout |

Durations are Go duration strings (`"10s"`, `"1m30s"`). Timestamps are RFC 3339. Error bodies are plain text.
