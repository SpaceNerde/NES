# NES — Service and Sequence Diagrams

> Companion to [`architecture.md`](./architecture.md). This page is intentionally
> limited to the v1 shape: one Velocity proxy, one Docker host, admin-triggered
> lifecycle.

## 1. Services and boundaries

```mermaid
flowchart LR
    ADM["Admin"]
    PL["Players"]

    subgraph PROXY["Velocity proxy"]
        CMD["/nes command surface"]
        LISTEN["Player listener<br/>initial server · fallback"]
        MANAGER["InstanceManager<br/>control plane · registry"]
        REGISTRY["Velocity server registry"]
    end

    subgraph SEAM["Provider seam"]
        SPI["InstanceProvider<br/>create · inspect · listManaged<br/>stop · remove · logs"]
    end

    subgraph DATA["Data plane — v1: one Docker host"]
        DOCKER_PROVIDER["DockerInstanceProvider"]
        ENGINE["Docker Engine"]
        CONTAINER["Paper 26.3 container"]
        INSTANCE_DIR["instances/ name /<br/>template copy + world"]
    end

    subgraph CONFIG["Templates"]
        TEMPLATE["templates/ id /<br/>server files · configs"]
    end

    ADM -->|create · delete · list · logs| CMD
    PL -->|connect| LISTEN
    CMD --> MANAGER
    LISTEN --> MANAGER
    MANAGER -->|lifecycle intent| SPI
    SPI --> DOCKER_PROVIDER
    DOCKER_PROVIDER -->|Docker API| ENGINE
    ENGINE --> CONTAINER
    CONTAINER --- INSTANCE_DIR
    TEMPLATE -->|copy on create| INSTANCE_DIR
    MANAGER -->|register / unregister READY only| REGISTRY
```

**Boundary rules**

- `InstanceManager` never calls Docker. It talks to `InstanceProvider`.
- The provider never knows about Velocity, players or commands.
- `templates/` is configuration; `instances/` is disposable runtime data.
- Docker labels (`nes.managed`, `nes.instance-id`, `nes.name`, `nes.template`,
  `nes.owner`) are the recovery contract.

## 2. Create instance

```mermaid
sequenceDiagram
    autonumber
    actor A as Admin
    participant C as "/nes create"
    participant M as InstanceManager
    participant T as Template installer
    participant P as InstanceProvider
    participant D as Docker Engine
    participant S as Paper 26.3
    participant V as Velocity registry

    A->>C: /nes create lobby
    C->>M: create("lobby", optional name)
    M->>M: reserve unique name + instance id
    C-->>A: "creating lobby-a3f91c..."
    M->>T: copy templates/lobby -> instances/lobby-a3f91c
    T-->>M: copy complete
    M->>P: create(request)
    P->>P: allocate free host port from range
    P->>D: create container with labels, env, bind, limits
    D-->>P: container id
    P->>D: start container
    P-->>M: RUNNING (not READY yet)
    loop every 1s until readyTimeout
        M->>S: status ping via raw RegisteredServer
        S-->>M: no response / still loading
    end
    S-->>M: status response
    M->>V: registerServer(ServerInfo(name, host:port))
    V-->>M: RegisteredServer
    M->>M: state = READY
    M-->>C: instance ready
    C-->>A: "lobby-a3f91c ready at host:port"
```

Failure path: if the ping never succeeds, the manager stops/removes the container,
deletes `instances/<name>/`, and marks the instance `FAILED`.

## 3. Delete / drain

```mermaid
sequenceDiagram
    autonumber
    actor A as Admin
    participant C as "/nes delete"
    participant M as InstanceManager
    participant V as Velocity registry
    participant PL as Connected players
    participant P as InstanceProvider
    participant D as Docker Engine

    A->>C: /nes delete lobby-a3f91c
    C->>M: delete(name, force?)
    M->>M: state = DRAINING
    M->>V: unregisterServer(info)
    Note over V: no new players can be routed here
    M->>PL: redirect to least-loaded READY instance<br/>of the same template
    alt no fallback exists
        M->>PL: disconnect with a clear message
    end
    Note over M: wait briefly for connections to drain<br/>(skipped with --force)
    M->>P: stop(ref, stopGrace)
    P->>D: stop container (SIGTERM, then force)
    M->>P: remove(ref)
    P->>D: remove container + volumes
    M->>M: delete instances/lobby-a3f91c<br/>(world discarded in v1)
    M-->>C: deleted
    C-->>A: "lobby-a3f91c deleted"
```

Ordering matters: **unregister first, then move players**, otherwise a fallback redirect
can put a player back on the server being removed.

## 4. Proxy restart / adoption

```mermaid
sequenceDiagram
    autonumber
    participant B as Plugin bootstrap
    participant P as InstanceProvider
    participant D as Docker Engine
    participant M as InstanceManager
    participant S as Paper 26.3
    participant V as Velocity registry

    B->>P: listManaged()
    P->>D: list containers with nes.managed=true
    D-->>P: containers + labels + published ports
    P-->>B: instances (name, id, template, host, port)
    loop each RUNNING container
        B->>M: adopt(instance)
        M->>S: status ping until ready
        S-->>M: ready
        M->>V: registerServer(info)<br/>or reuse existing entry
        M->>M: state = READY
    end
    Note over B,M: exited/unknown containers are logged<br/>and left for the admin to inspect or remove
```

This is why v1 does not need a database: the proxy rebuilds its registry from Docker
labels and published ports.

## 5. Player routing

```mermaid
sequenceDiagram
    autonumber
    actor PL as Player
    participant V as Velocity
    participant L as Player listener
    participant M as InstanceManager
    participant S as Target instance

    PL->>V: connect
    V->>L: PlayerChooseInitialServerEvent
    L->>M: chooseInitialServer()
    M-->>L: least-loaded READY instance of default template
    L->>V: setInitialServer(instance)
    V->>S: connect player

    Note over V,S: later, if the instance kicks the player
    V->>L: KickedFromServerEvent
    L->>M: fallbackFor(kicked server)
    M-->>L: another READY instance of the same template, or empty
    alt fallback exists
        L->>V: RedirectPlayer(fallback)
    else no fallback
        L->>V: leave the kick result unchanged
    end
```

## 6. State at a glance

```mermaid
stateDiagram-v2
    [*] --> PROVISIONING: create
    PROVISIONING --> STARTING: container started
    STARTING --> READY: ping succeeds + registered
    STARTING --> FAILED: timeout / crash
    READY --> DRAINING: delete / drain
    DRAINING --> STOPPING: players moved, empty
    STOPPING --> STOPPED: container removed
    DRAINING --> FAILED: stop/remove error
    READY --> FAILED: container died / ping lost
    FAILED --> [*]: admin retries or removes
    STOPPED --> [*]
```
