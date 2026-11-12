# NES v1 — Scope and Acceptance Criteria

> This document defines what "v1 done" means. It is deliberately stricter than the
> architecture document: if a behavior is not listed here, it is not required for v1.

## 1. Product statement

An administrator running a Velocity proxy on a single Docker host can create, inspect
and delete Paper 26.3 backend instances from the proxy, and Velocity routes players only
to instances that are actually ready.

## 2. v1 user stories

| # | As an admin I can... | So that... |
|---|---|---|
| U1 | create an instance from a template and get its address | I can open a new lobby/game server on demand |
| U2 | list instances with state, address, template and player count | I know what is running |
| U3 | inspect one instance, including its last error | I can diagnose a failed start |
| U4 | read the recent log tail of an instance | I can debug a backend without SSH |
| U5 | delete an instance safely | players are moved or disconnected, not silently dropped |
| U6 | restart the proxy without losing the running fleet | maintenance does not kill every backend |
| U7 | reload templates and routing settings | I can add a template without a proxy restart |
| U8 | see clear errors when Docker, a port or a template is broken | I can fix the problem without reading the source |

## 3. In scope

- Docker provider on one host.
- Template-based creation: image, env, bind mount, memory/CPU limits, command override.
- Template directory copied to a per-instance directory.
- World generated on first boot (template contains no world).
- Port allocation from a configured host range and publication to the proxy.
- Readiness via Minecraft server-list ping before registration.
- Admin command surface: `list`, `info`, `logs`, `create`, `delete`, `reload`.
- Drain/delete behavior with same-template fallback and bounded wait.
- Adoption of running containers after a proxy restart.
- Cleanup of partial creates.
- In-memory registry reconstructed from Docker labels.
- Permissions `nes.admin` and `nes.view`.
- Optional stop-all-on-shutdown; leave-running is the default.

## 4. Out of scope

- Autoscaling, groups, min/max pools, target players per instance.
- Warm pools and pre-created capacity.
- Per-match instances requested by another plugin.
- World archiving, retention or restore.
- Multiple Docker hosts, node placement, capacity scheduling.
- Multiple proxies, shared database, leader election.
- Kubernetes, Pterodactyl/Pelican or other providers.
- Migrating a running instance to another host.
- A web UI, REST API or queue/waiting room.
- Automatic retries of failed creates.

## 5. Functional acceptance criteria

### AC1 — Create success

Given a valid template and a free port in the configured range:

- the template directory is copied to `instances/<name>/`;
- a container is created with the expected labels, env, bind mount and limits;
- the container is started;
- NES polls the server-list ping;
- only after a successful ping is the server registered with Velocity;
- the instance becomes `READY`;
- the admin receives the instance name and `host:port`;
- `/nes list` shows it as `READY`.

### AC2 — Create failure

Given a broken template, an image pull failure, an exhausted port range, or a backend
that never answers the ping within `ready-timeout`:

- the admin receives an error naming the failed stage;
- the instance is not registered with Velocity;
- any partial container is removed;
- `instances/<name>/` is removed;
- the port is released;
- no duplicate name or port is left behind.

### AC3 — Ready gating

Given a container that is running but whose Minecraft server has not finished loading:

- Velocity must not expose the instance to player routing;
- `/nes list` may show `STARTING`;
- a status ping that succeeds is the only automatic transition to `READY`.

### AC4 — Delete with players

Given a `READY` instance with connected players:

- NES marks it `DRAINING` and unregisters it from Velocity before moving players;
- if another `READY` instance of the same template exists, players are redirected to
  the least-loaded one;
- otherwise players are disconnected with a clear message;
- after a bounded drain wait (or immediately with `--force`), the container is stopped
  with the configured grace period, then removed;
- the instance directory is deleted;
- the instance disappears from `/nes list` and its port is available again.

### AC5 — Delete without players

Given a `READY` instance with no players, deletion skips the drain wait and completes
the stop/remove/discard flow.

### AC6 — Proxy restart / adoption

Given running containers labelled `nes.managed=true`:

- after a proxy restart, NES lists them, re-registers the ready ones and does not create
  duplicates;
- an already-registered name with the same address is reused;
- exited or unlabelled containers are logged and left alone;
- the admin sees adopted instances in `/nes list`.

### AC7 — Reload

Given a valid config change:

- `/nes reload` applies template and routing changes to future creates;
- running instances keep the template they were created from;
- provider host, owner id, public host and root directory changes are rejected with a
  message saying a restart is required;
- invalid config is rejected and the old config remains active.

### AC8 — Shutdown

Given `shutdown-behavior = "leave-running"` (default):

- stopping the proxy does not stop or delete instances;
- they are adopted on the next start.

Given `shutdown-behavior = "stop-all"`:

- NES attempts to stop all instances within a bounded time;
- it does not delete containers or instance directories.

### AC9 — Validation and safety

- Instance names must match `[a-z0-9-]`, 1–63 chars; path traversal is rejected.
- Template ids are validated the same way.
- Unknown templates are rejected before any work starts.
- The forwarding secret never appears in command output or logs.
- Mutation commands require `nes.admin`; read commands require `nes.view`.

### AC10 — Concurrency

Given two simultaneous creates:

- they receive distinct names and distinct host ports;
- neither overwrites the other's directory;
- Velocity never contains duplicate server names;
- an in-flight name cannot be used by a second create.

## 6. Non-functional acceptance criteria

| Area | Requirement |
|---|---|
| Latency | A create/delete command acknowledges in under one second; the work continues asynchronously |
| Blocking | Velocity event handlers and command handlers never wait on Docker, disk copy or readiness |
| Timeouts | Every provider call and readiness wait is bounded |
| Recovery | A proxy crash at any point leaves no unrecoverable state; labels allow adoption or cleanup |
| Observability | Every lifecycle failure is logged with instance name, id, template and stage |
| Security | Docker socket access is documented as root-equivalent; commands are permission-gated |
| Portability | Docker connection, public host and port range are configuration, not code |

## 7. Definition of done

- A real Paper 26.3 backend can be created, registered, joined through Velocity, and
  deleted in an end-to-end test.
- The failure cases in AC2, AC3, AC4 and AC6 have automated or scripted evidence.
- The operator runbook (`operations.md`) matches the implemented commands and config.
- A fresh install produces a working sample config and a clear error if the template
  directory is missing.
- No blocking calls on Velocity's event loop.
- No secrets in output or logs.

## 8. Open product decisions

1. **Delete with no fallback:** disconnect immediately, or refuse unless `--force`?
   Current design disconnects with a message.
2. **Delete with players and `--force`:** skip the drain wait, or also skip the redirect?
   Current design still redirects, only skips the wait.
3. **`/nes delete` on the last instance of a template:** allowed in v1. Confirm this is
   intended.
4. **Initial routing:** only the configured `default-template`. Confirm that other
   templates are not expected to receive players automatically.
5. **Template update policy:** new instances use the new template; existing instances
   keep theirs. Confirm that `/nes reload` should never restart instances.
6. **World discard:** confirm there is no need for a "keep my world" escape hatch.
