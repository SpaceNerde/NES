# NES — Dynamic Server Instance Manager for Velocity

> **Status:** concept / architecture
> **Baseline:** Velocity 4.3.x, Java 25, Docker on a single host
> **Scope:** v1 design for an admin-operated dynamic backend pool

## 1. Summary

NES is a Velocity plugin that manages Minecraft backend **instances** instead of only
routing to a static `velocity.toml` server list. An administrator can create an
instance from a template, watch it become ready, let Velocity route players to it, and
delete it when it is no longer needed.

The central idea is a clean split:

- the **plugin is the control plane** — it owns intent, lifecycle, routing and admin
  commands;
- a **provider abstraction is the seam** to whatever actually runs the backend;
- **Docker is the v1 data plane**, hidden behind that seam so a node agent or
  Kubernetes provider can be added later without rewriting the plugin.

The second central idea is that **an instance is not "available" when its container
starts**. It becomes available only when the Minecraft server inside it is ready. NES
registers a backend with Velocity only after a successful server-list ping.

## 2. Locked decisions

| Area | Decision |
|---|---|
| Substrate | Docker on one host for v1 |
| Creation trigger | Administrator commands only; no autoscaling in v1 |
| Minecraft version | 26.3 (template-configurable) |
| World data | Generated on first boot; no pre-generated world copy |
| Deletion | Discard the instance directory; no archive in v1 |
| Topology | Single proxy owns the cluster; multi-host later |
| Lifecycle abstraction | `InstanceProvider` SPI, Docker implementation first |
| Availability | Register with Velocity only after a successful readiness check |
| State store | Provider labels are the source of truth in v1; no database yet |
| Shutdown | Leave instances running; re-adopt them on proxy restart |

These choices deliberately make v1 small. Autoscaling, warm pools, world retention,
multi-host placement and multi-proxy coordination are designed for, but not built.

## 3. What v1 does and does not do

**v1 does**

- create a backend from a named template (`/nes create <template> [name]`),
- copy the template into a per-instance directory,
- start a Docker container with a published port and resource limits,
- poll the backend until it can answer a server-list ping,
- register it with Velocity and route players to it,
- list/inspect/log instances from the proxy console or in-game,
- drain players, stop the container, remove it and discard its directory,
- adopt still-running containers after a proxy restart,
- recover a failed create by cleaning up the partial container and directory.

**v1 does not**

- scale automatically based on player count,
- keep a warm pool beyond what the admin created,
- create per-match servers from another plugin,
- archive or retain worlds on delete,
- manage multiple Docker hosts,
- coordinate multiple Velocity proxies,
- migrate instances to another host.

Those are later phases; the architecture leaves room for them.

## 4. Architecture

```
                       ┌──────────────────────────────────────────┐
  admins / players ───▶│ Velocity                                 │
                       │  ┌────────────────────────────────────┐  │
                       │  │ NES plugin (control plane)         │  │
                       │  │ commands · lifecycle · routing     │  │
                       │  │ readiness · adoption · logs        │  │
                       │  └───────────────┬────────────────────┘  │
                       └──────────────────┼───────────────────────┘
                                          │ InstanceProvider SPI
                                   ┌──────▼────────┐
                                   │ DockerProvider │
                                   └──────┬────────┘
                                          │ Docker Engine API
                                   ┌──────▼────────┐
                                   │ containers     │
                                   │ Paper 26.3     │
                                   └───────────────┘
```

The provider boundary is the important part. In v1 it is an in-process Docker
implementation. In a later phase the same contract is implemented by an HTTP client
that talks to a small per-node agent, and the plugin does not change.

### Why not put Docker calls directly in command handlers

- A Velocity plugin should not block its event loop on Docker, disk copies or a
  90-second readiness wait.
- Container lifecycle is failure-prone; a bare `docker run` in a command handler has no
  recovery story.
- Host-level process management is hard to unit-test. The provider seam lets the
  lifecycle logic be tested with a fake provider.
- The security boundary changes over time. A proxy with `/var/run/docker.sock` is
  root-equivalent; a later agent can expose a narrow API instead.

## 5. Components

| Component | Responsibility |
|---|---|
| Plugin bootstrap | Load config, build the provider, wire the lifecycle service and commands |
| `InstanceManager` | Application service: create/delete/drain, in-memory registry, adoption, fallback routing |
| `InstanceProvider` | Create/inspect/list/stop/remove/log a backend resource |
| `DockerInstanceProvider` | Docker Engine calls, labels, port publishing, resource limits, logs |
| Template installer | Copy template directory to `instances/<name>`; delete it on removal |
| Readiness probe | Ping the backend via Velocity's raw `RegisteredServer.ping()` |
| Command surface | `/nes ...`, permissions, operator feedback |
| Player listener | Initial server choice, fallback on kick, no autoscaling |

A future **node agent** becomes a new provider implementation plus a small HTTP service.
A future **group/scaling policy** becomes a component above the lifecycle service that
asks it to create or delete instances.

## 6. Domain model

### Template

A named recipe for one instance, supplied by configuration.

```
Template {
  id
  image                    // e.g. an itzg/minecraft-server tag or a custom image
  containerPort            // usually 25565
  dataVolume               // usually /data
  templateDirectory        // source files copied into the instance directory
  memory, cpus             // resource limits
  readyTimeout             // how long to wait for the server-list ping
  stopGrace                // SIGTERM -> stop timeout
  command                  // optional image command override
  environment              // env vars (TYPE, VERSION, EULA, forwarding, ...)
}
```

Templates are configuration, not database rows. Reloading templates does not mutate
running instances; a running instance keeps the template it was created from.

### Instance

```
Instance {
  id: UUID
  name: "lobby-a3f91c"     // stable, unique, [a-z0-9-]
  templateId
  host, port               // reachable address registered with Velocity
  state
  providerRef              // Docker container id
  createdAt, readyAt
  lastError
}
```

There is no `Group` object in v1. The template is the unit of creation, and the set of
instances of a template is just a filtered view. Groups (with min/max/target players)
are a future autoscaling concept and should not be introduced until they are needed.

### State machine

```
                 create
                   │
             ┌─────▼──────┐
             │ PROVISIONING│  copy template, create container
             └─────┬──────┘
                   │ start
             ┌─────▼──────┐
             │  STARTING  │  poll server-list ping
             └─────┬──────┘
                   │ ping ok + registered with Velocity
             ┌─────▼──────┐
             │   READY    │  players may be routed here
             └─────┬──────┘
                   │ delete / drain
             ┌─────▼──────┐
             │  DRAINING  │  unregistered; players moved out
             └─────┬──────┘
                   │ empty
             ┌─────▼──────┐
             │  STOPPING  │  graceful stop, then force
             └─────┬──────┘
                   │
             ┌─────▼──────┐
             │  STOPPED   │
             └────────────┘

  any state can transition to FAILED; a failed create is cleaned up, not retried
  automatically in v1.
```

Rules:

- Only `READY` instances are registered with Velocity.
- Deletion always unregisters before moving players, so a redirect cannot send a
  player back into the instance being removed.
- The state is held in memory and reconstructed from provider labels on startup.

## 7. Provider contract

The provider interface is intentionally small:

```
create(request)      -> created instance (provider ref, reachable host/port)
inspect(ref)         -> current provider state
listManaged()        -> all resources carrying nes.managed=true
stop(ref, grace)     -> graceful stop, then force
remove(ref)          -> remove resource and its container volumes
logs(ref, lines)     -> recent log lines
```

Invariants:

- every call is asynchronous and bounded by a timeout;
- `stop`/`remove` on a missing resource complete normally;
- `create` is idempotent where the substrate allows it (deterministic name);
- resources carry enough metadata to be adopted after a restart;
- no Velocity types cross the boundary.

In v1 the only implementation is Docker. A fake provider is enough to test the
lifecycle service without Docker. Later implementations:

| Provider | When |
|---|---|
| Docker (in-process) | v1 |
| Agent over HTTP | when a second host or a security boundary is needed |
| Kubernetes | if the network already runs K8s |
| Panel API | if the network already uses Pterodactyl/Pelican |

## 8. Lifecycle flows

### 8.1 Create

```
/admin runs /nes create lobby
  → validate template and name; reserve the name in memory
  → copy templates/lobby/ to instances/<name>/
  → provider.create:
       allocate a free host port from the configured range
       create container with labels, env, bind mount, memory/CPU limits
       publish hostPort:25565
       start container
  → poll createRawRegisteredServer(...).ping() every second
  → on success: server.registerServer(new ServerInfo(name, host:port))
  → state = READY
  → on timeout: stop/remove container, delete instance directory, state = FAILED
```

The command returns immediately with "creating"; completion is reported asynchronously.
No player is routed to the instance before `READY`.

### 8.2 Delete / drain

```
/admin runs /nes delete <name>
  → state = DRAINING
  → unregister from Velocity (no new joins)
  → move connected players:
       to the least-loaded READY instance of the same template, if one exists
       otherwise disconnect with a clear message
  → wait briefly for connections to drain (or skip if --force)
  → provider.stop(ref, stopGrace)
  → provider.remove(ref)
  → delete instances/<name>/ (worlds are discarded in v1)
  → remove from the registry
```

Deleting the only instance of a template is allowed because v1 has no minimum pool.
That is an operator responsibility, not a plugin guarantee.

### 8.3 Proxy restart / adoption

On startup the plugin calls `provider.listManaged()` and adopts every running
container that carries a valid `nes.*` label. It re-registers them with Velocity and
runs the readiness check. A stopped/unknown container is logged and left alone.

This is why v1 does not need a database: the container labels contain the instance id,
name, template and owner, and the published port is recoverable from Docker. The proxy
can rebuild its registry from the data plane.

### 8.4 Crash recovery

- Container dies while `READY`: the next ping/inspect fails, the instance is
  unregistered and marked `FAILED`. v1 does not auto-replace it; the admin recreates it.
- Proxy dies mid-create: the orphaned container is still labelled `nes.managed=true`.
  On restart it is adopted if running, or left for the admin/cleanup command if exited.
- Create times out: the partial container and instance directory are removed.
- Delete fails halfway: the instance stays `FAILED`/`DRAINING` with an error; the admin
  can retry. Container removal is idempotent.

A small periodic supervisor is still useful in v1, but it only **observes** (health,
adoption, stale state). It does not scale.

## 9. Velocity integration

### Events

| Event | Use |
|---|---|
| `ProxyInitializeEvent` | load config, build provider, start adoption, register command |
| `ProxyShutdownEvent` | close provider; optionally stop all if configured |
| `PlayerChooseInitialServerEvent` | pick the least-loaded READY instance of the default template |
| `KickedFromServerEvent` | redirect to another READY instance of the same template |
| `ServerPostConnectEvent` / `DisconnectEvent` | update load views / future scaling signals |

In v1 the last two are mostly bookkeeping; there is no scale-up/scale-down reaction.
Using `RegisteredServer.getPlayersConnected()` as the live count avoids a separate
counter that can drift.

### Registration rules

- `ProxyServer.registerServer(new ServerInfo(name, address))` only at `READY`.
- `ProxyServer.unregisterServer(info)` first when draining.
- Keep the exact `ServerInfo` used for registration.
- Use `ProxyServer.createRawRegisteredServer(info)` as the readiness probe; it does not
  add the server to the proxy's routing table.
- Use the **published** host port for the address, not the container-internal port.

### Commands

```
/nes list [template]           nes.view
/nes info <name>               nes.view
/nes logs <name> [lines]       nes.view
/nes create <template> [name]  nes.admin
/nes delete <name> [--force]   nes.admin
/nes reload                    nes.admin
```

Creation and deletion are long-running; reply immediately and report completion.
`/nes reload` only reloads templates and routing settings; provider settings require a
restart.

## 10. Docker model

### One container per instance

- **Image**: from the template. Pin a digest or a specific tag for production.
- **Data**: the template directory is copied to `instances/<name>/` and bind-mounted at
  `/data` (configurable). No shared writable world directory.
- **World**: generated on first boot. The template must not contain a `world/`
  directory unless that is intentional. The first start is slower because of world
  generation; `readyTimeout` must account for it (180s is a reasonable starting point).
- **Ports**: choose a host port from a configured range (for example 25565–25665) and
  publish `hostPort:25565`. The range keeps firewall rules predictable. Docker-assigned
  ephemeral ports are possible, but less operator-friendly.
- **Resources**: memory and CPU quota per template so one backend cannot starve the
  host. No restart policy; NES owns lifecycle.
- **Labels**: `nes.managed=true`, `nes.instance-id`, `nes.name`, `nes.template`,
  `nes.owner`. These are the recovery contract.
- **Logs**: Docker log driver with a size cap; `/nes logs` reads the tail.

### Minecraft-specific template requirements

- `eula.txt` accepted by the server owner. Do not silently accept Mojang's EULA in a
  hosted product.
- `server.properties`: `server-port=25565`, `online-mode=false`, `max-players`,
  `view-distance`, `motd`.
- Paper `config/paper-global.yml`: Velocity forwarding enabled, `online-mode: true`,
  shared secret.
- Proxy `velocity.toml`: `player-info-forwarding-mode = "modern"` and the matching
  secret. Dynamic servers are registered at runtime and are not written back into
  `velocity.toml`.
- Version 26.3 comes from the template env or image. The plugin does not hardcode a
  Minecraft version.

### Networking

Single host, bridge networking, published port is the simplest and is what v1 assumes.
The proxy registers `publicHost:hostPort`. If the proxy and Docker are on the same
host, `127.0.0.1` or the private interface is enough. Host networking and macvlan are
possible later but add operator complexity.

## 11. State, persistence and recovery

v1 keeps the registry in memory and treats **Docker labels as the durable record**:

- name, instance id, template and owner are labels;
- the published port is recoverable from the container;
- the template file is in configuration;
- the instance directory is named after the instance.

That is enough to rebuild state after a proxy restart and enough for admin-driven
lifecycle. A database would mostly duplicate Docker's own state.

When the system grows, persistence becomes useful:

- **autoscaling**: desired counts, cooldowns, scaling history;
- **multi-proxy**: ownership/leases, leader election;
- **audit**: who created/deleted what;
- **world retention**: archive records.

At that point SQLite is fine for a single proxy; Postgres is the natural choice for
multiple proxies. The schema can store instances, port leases and operations, but the
provider labels remain the second source of truth so a lost database is recoverable.

## 12. Concurrency

- Velocity event handlers do cheap work and return; they never wait on Docker, disk or
  a readiness probe.
- Provider calls, template copies and readiness polling run on **virtual threads**
  (`Executors.newVirtualThreadPerTaskExecutor()`).
- State transitions are serialized. In v1 the `InstanceManager` guards the registry and
  uses atomic `replace`/`putIfAbsent` operations; a later reconciler can move all
  transitions onto one single-threaded executor.
- Proxy-facing mutations (`registerServer`, `unregisterServer`, player redirects) are
  scheduled through `ProxyServer.getScheduler()`.
- Every provider operation and readiness wait has a timeout.

## 13. Security

- `nes.admin` for mutations, `nes.view` for reads; console is allowed.
- Instance names are validated (`[a-z0-9-]`, bounded length) and resolved under the
  instances root; reject path traversal.
- Templates are fixed configuration. Never execute player-supplied commands or paths.
- Docker socket access is root-equivalent. For a single trusted host this is acceptable
  in v1; the agent in a later phase is the production answer.
- Keep the Velocity forwarding secret in an env/file with restricted permissions; do
  not log it.
- Resource limits per instance, and a log tail cap.

## 14. Failure modes

| Failure | v1 behavior |
|---|---|
| Proxy restarts | Running containers are adopted and re-registered |
| Container dies while READY | Unregistered, marked FAILED; admin recreates |
| Server never becomes ready | Timeout, container removed, instance directory deleted |
| Port range exhausted | Create fails with a clear error; no partial container |
| Name collision | Rejected before any work starts |
| Delete with players online | Unregister, redirect to same-template fallback or disconnect |
| Proxy dies mid-create | Labelled container is adopted on restart or cleaned up by admin |
| Delete fails halfway | Instance remains FAILED; retry is safe |
| Docker daemon unavailable | Create/delete/logs fail with a clear operator error |
| World generation is slow | `readyTimeout` accounts for it; admin can raise it per template |

## 15. Observability

- `/nes list` shows state, address, template and player count.
- `/nes info` shows provider ref, timestamps, last error and forwarding-relevant
  details (never the secret).
- `/nes logs` returns a bounded tail.
- Structured logs include instance name, instance id, template and provider ref.
- Useful metrics later: create latency, readiness failures, instances by state, Docker
  errors. Not required for v1.

## 16. Testing

- **Unit**: template installer, name validation, duration/memory parsing, state
  transitions, fallback selection, config validation.
- **Provider contract**: the same test suite against a fake provider and the Docker
  provider (Testcontainers).
- **Lifecycle**: fake provider + fake clock: create success, create timeout, cleanup,
  delete with players, delete without fallback, adoption.
- **End-to-end**: real Velocity + real Paper 26.3 container, create through the command,
  wait for READY, connect a client (or a headless protocol client), delete and verify
  cleanup. This is the only test that catches forwarding and port mistakes.

## 17. Roadmap

### Phase 1 — admin-operated Docker pool

- config + template model, validation, sample config;
- Docker provider: create/inspect/list/stop/remove/logs;
- template copy, port allocation, readiness ping, READY-only registration;
- `/nes list|info|logs|create|delete|reload`;
- adoption on startup, cleanup on failed create;
- in-memory registry, no database.

### Phase 2 — operator quality

- stale-instance supervisor, retry/backoff for failed operations;
- better delete semantics (`--drain-only`, `--force`);
- metrics/logging polish;
- optional archive-on-delete if retention becomes a requirement.

### Phase 3 — groups and autoscaling

- introduce `Group` (template, min/max, target players, cooldowns);
- desired-state reconciler, warm pool, scale-up before players arrive if needed;
- SQLite for desired state, leases and operation history.

### Phase 4 — multi-host

- extract the node agent over HTTP; add `AgentHttpProvider`;
- node inventory, capacity-aware placement, per-node port ranges;
- agent auth/TLS; remove Docker socket access from the proxy.

### Phase 5 — multi-proxy and integrations

- shared Postgres, ownership/leader leases;
- public API for game plugins to request per-match instances;
- waiting room/queue if instant capacity matters.

## 18. Open questions and risks

1. **Image and Java version for 26.3.** The template must pin a working Paper/Java
   combination. This should be validated with one real end-to-end run before relying
   on it.
2. **First-join latency.** With world generation on first boot, a newly created
   instance can take a while. v1 has no warm pool, so the admin should create before
   players need it. If that is unacceptable, a warm pool belongs in Phase 3.
3. **Docker socket exposure.** v1 assumes the proxy host is trusted. If the proxy runs
   in a container, mounting the socket is effectively root on the host; the agent is a
   better long-term boundary.
4. **Discard semantics.** "Discard" is simple but irreversible. Confirm that no world
   data needs to survive a delete; if it does, add an archive option before Phase 2.
5. **Delete while players are online.** v1 redirects to a same-template instance or
   disconnects. Confirm that is acceptable, or add a drain-only mode.
6. **Multiple templates of the same game.** v1 routes initial players only to the
   configured `default-template`; other templates are reached by admin/other means.
   This may be too limited once there is more than one lobby-like pool.

## 19. ADR summary

| Decision | Choice | Why |
|---|---|---|
| Control/data plane split | Plugin + `InstanceProvider` | Testability, security, multi-host path |
| v1 trigger | Admin commands | Smallest useful scope; no scaling policy yet |
| v1 substrate | Docker, single host | Isolation, cleanup, resource limits, port mapping |
| Availability | READY only after status ping | Never route players to a booting server |
| v1 state | Docker labels + memory | No database until desired state exists |
| World lifecycle | Generate on first boot; discard on delete | Simple and matches the chosen product behavior |
| Concurrency | Virtual threads + serialized transitions | No blocking the proxy, no coarse locks |
| Proxy restart | Leave running, adopt | Avoid restarting the fleet with the proxy |
| Future scaling | Groups + reconciler + SQLite | Add when the product needs it, not before |
| Future multi-host | Node agent + `AgentHttpProvider` | Narrow security boundary and placement |
