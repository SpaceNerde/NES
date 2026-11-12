# NES design documentation

Planning artifacts for the Not Enough Servers Velocity plugin.

| Document | Purpose |
|---|---|
| [`architecture.md`](./architecture.md) | System concept, boundaries, domain model, state machine, provider seam, risks |
| [`diagrams.md`](./diagrams.md) | Service map, create/delete/adoption/routing sequences, state diagram |
| [`v1-scope.md`](./v1-scope.md) | What v1 does and does not do, user stories, acceptance criteria, definition of done |
| [`config-spec.md`](./config-spec.md) | Concrete `config.toml` schema, defaults, validation and reload semantics |
| [`operations.md`](./operations.md) | Host/template prerequisites, runbook, maintenance, troubleshooting, security checklist |

## Reading order

1. `architecture.md` — why the system is shaped this way.
2. `diagrams.md` — how the pieces interact over time.
3. `v1-scope.md` — what is actually being promised.
4. `config-spec.md` — the operator-facing contract.
5. `operations.md` — how to run and debug it.

## Current design baseline

- One Velocity proxy, one Docker host.
- Admin-command lifecycle only; no autoscaling.
- Paper 26.3 templates; worlds generated on first boot and discarded on delete.
- Docker labels are the durable state; no database in v1.
- A backend is registered with Velocity only after a successful status ping.
- The `InstanceProvider` seam is the path to a node agent, Kubernetes or another panel
  later.
