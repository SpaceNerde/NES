# NES — Configuration Specification (v1)

> This is a design specification, not an implementation. It defines the keys, defaults,
> validation rules and reload semantics that the plugin should support in v1.

## 1. File

| Item | Value |
|---|---|
| Location | `<plugin data directory>/config.toml` |
| Format | TOML |
| Created | Automatically on first start if missing |
| Reload | `/nes reload` for templates and routing; restart for provider settings |

Paths are resolved relative to the plugin data directory unless absolute.

## 2. Top-level keys

```toml
owner-id = "proxy-1"
public-host = "mc.example.com"
default-template = "lobby"
shutdown-behavior = "leave-running"
```

| Key | Type | Default | Validation |
|---|---|---|---|
| `owner-id` | string | `proxy-1` | non-blank, safe label value (`[A-Za-z0-9_.-]`) |
| `public-host` | string | `127.0.0.1` | non-blank; hostname or IP the proxy can reach |
| `default-template` | string | none | must exist in `[templates]` if set; empty disables initial routing |
| `shutdown-behavior` | enum | `leave-running` | `leave-running` or `stop-all` |

`default-template` is used only by `PlayerChooseInitialServerEvent`. It does not create
instances and does not imply a minimum pool.

## 3. Provider

```toml
[provider]
type = "docker"

[provider.docker]
host = "unix:///var/run/docker.sock"
network = ""
port-range = [25565, 25665]
```

| Key | Type | Default | Validation |
|---|---|---|---|
| `provider.type` | enum | `docker` | only `docker` in v1 |
| `provider.docker.host` | string | `unix:///var/run/docker.sock` | non-blank; `unix://` or `tcp://` URI |
| `provider.docker.network` | string | empty | optional Docker network name |
| `provider.docker.port-range` | int[2] | `[25565, 25665]` | exactly two values, 1–65535, start ≤ end |

Provider settings require a proxy restart. `public-host` and `owner-id` are also
restart-only because the provider is constructed with them.

## 4. Root directories

```toml
templates-root = "templates"
instances-root = "instances"
```

| Key | Type | Default | Validation |
|---|---|---|---|
| `templates-root` | path | `templates` | must exist and be readable; created empty if missing |
| `instances-root` | path | `instances` | must be writable; created if missing; must not equal `templates-root` |

`templates-root` contains one directory per template. `instances-root` contains one
directory per instance and is disposable.

## 5. Templates

```toml
[templates.lobby]
image = "itzg/minecraft-server:java25"
container-port = 25565
data-volume = "/data"
template-dir = "lobby"
memory = "2G"
cpus = 2.0
ready-timeout = "180s"
stop-grace = "30s"
command = []
env = { TYPE = "PAPER", VERSION = "26.3", EULA = "TRUE", ONLINE_MODE = "FALSE" }
```

| Key | Type | Default | Validation |
|---|---|---|---|
| `image` | string | required | non-blank; pin a tag or digest |
| `container-port` | int | `25565` | 1–65535 |
| `data-volume` | string | `/data` | absolute path inside the container |
| `template-dir` | string | template id | resolved under `templates-root`; must exist |
| `memory` | size | `1G` | `B`, `K`, `M`, `G`, `T` (binary), optional `iB` suffix |
| `cpus` | number | `0` (unlimited) | `>= 0`; fractional allowed |
| `ready-timeout` | duration | `180s` | `ms`, `s`, `m`, `h`, `d`; must be > 0 |
| `stop-grace` | duration | `30s` | `ms`, `s`, `m`, `h`, `d`; must be > 0 |
| `command` | string[] | `[]` | non-empty entries; overrides the image command only if non-empty |
| `env` | table<string,string> | `{}` | keys must be valid environment names |

Template ids must match `[a-z0-9-]{1,63}`. Duplicate ids are impossible in TOML but a
case-insensitive duplicate should still be rejected.

### 5.1 Template directory contract

The directory referenced by `template-dir` must contain the server files that should be
copied into every instance. It must **not** contain a `world/`, `world_nether/` or
`world_the_end/` directory if worlds should be generated on first boot.

Recommended contents:

```
templates/lobby/
├── eula.txt
├── server.properties
├── config/paper-global.yml
├── plugins/                  (optional)
└── (no world directories)
```

Minecraft-specific settings are template concerns, not plugin concerns. The plugin
does not rewrite `server.properties` or `paper-global.yml` in v1.

### 5.2 Environment variables

`env` is passed through unchanged. Typical values for a Paper template include the
server type, Minecraft version, EULA acceptance and online-mode setting. The exact
Velocity-forwarding variable names depend on the image; the safe approach is to bake
`paper-global.yml` into the template or document the image-specific variables in the
template README.

## 6. Unknown keys and forward compatibility

v1 should:

- reject unknown top-level keys or log a warning (implementation choice);
- never silently ignore a misspelled key that would change lifecycle behavior;
- reserve `[groups]`, `[scaling]` and `[retention]` for future versions rather than
  accepting them now.

## 7. Reload semantics

| Setting | `/nes reload` | Restart required |
|---|---|---|
| `[templates.*]` | yes, for future creates | no |
| `default-template` | yes | no |
| `shutdown-behavior` | yes | no |
| `provider.*` | no | yes |
| `owner-id` | no | yes |
| `public-host` | no | yes |
| `templates-root`, `instances-root` | no | yes |

Running instances keep their original template. Reload never restarts or modifies
running containers.

## 8. Example: two templates

```toml
owner-id = "proxy-1"
public-host = "mc.example.com"
default-template = "lobby"
shutdown-behavior = "leave-running"

[provider]
type = "docker"

[provider.docker]
host = "unix:///var/run/docker.sock"
network = "mc-net"
port-range = [25565, 25665]

templates-root = "templates"
instances-root = "instances"

[templates.lobby]
image = "registry.example.com/nes/paper:26.3-r1"
memory = "2G"
cpus = 2.0
ready-timeout = "240s"
stop-grace = "30s"
env = { TYPE = "PAPER", VERSION = "26.3", EULA = "TRUE", ONLINE_MODE = "FALSE" }

[templates.arena]
image = "registry.example.com/nes/paper:26.3-r1"
memory = "4G"
cpus = 4.0
ready-timeout = "300s"
stop-grace = "45s"
command = ["--nogui"]
env = { TYPE = "PAPER", VERSION = "26.3", EULA = "TRUE", ONLINE_MODE = "FALSE" }
```

## 9. Validation error examples

The plugin should fail fast with messages that name the key:

- `templates.lobby.image is required`
- `templates.lobby.template-dir does not exist: /srv/nes/templates/lobby`
- `provider.docker.port-range must contain exactly two ports`
- `provider.docker.port-range start must be <= end`
- `default-template 'lobby' is not defined under [templates]`
- `instances-root must differ from templates-root`
- `shutdown-behavior must be 'leave-running' or 'stop-all'`

## 10. Not in v1

- Per-group min/max/scaling settings.
- Retention/archive settings.
- Multiple nodes or providers.
- A database connection string.
- Per-instance overrides beyond `name` (the command argument).

## 11. Template base image — recommendation

For v1, the `itzg/minecraft-server` image is a good default base:

- Paper is supported via `TYPE=PAPER`, `VERSION=26.3` and `PAPER_BUILD`.
- Paper 26.3 requires Java 25; the `java25` image variant matches.
- Its `/data` bind-mount model maps directly to the per-instance directory.
- It ships an `mc-monitor` healthcheck, which is the same server-list ping used for
  readiness. That is a useful extra signal, but NES should keep its own ping as the
  source of truth so the provider stays image-independent.

Suggested template settings:

```
image = "itzg/minecraft-server:java25"   # pin a release tag or digest in production
TYPE=PAPER
VERSION=26.3
PAPER_BUILD=<the build you tested>
MEMORY=<match the container memory limit>
EULA=TRUE
ONLINE_MODE=FALSE
SKIP_DOWNLOAD_DEFAULTS=TRUE
USE_AIKAR_FLAGS=TRUE                       # or Paper's recommended JVM flags
```

Caveats:

- There is no documented Velocity-forwarding environment variable. Bake
  `config/paper-global.yml` into the template, or manage it through
  `PAPER_CONFIG_REPO`. Keep `online-mode=false` in `server.properties` and
  `online-mode: true` in Paper's Velocity proxy settings.
- The entrypoint resolves/downloads Paper and applies defaults on every start. For
  admin-created instances this is acceptable; if create latency becomes important,
  pre-seed `/data` with the tested Paper jar or build a pre-baked image. The provider
  contract does not change.
- Do not use `latest` or the compose example's `pull_policy: daily` in production.
- Disable `AUTOSTOP`/`AUTOPAUSE`; NES owns lifecycle. Use restart policy `no`.
- The image runs as UID/GID 1000 by default. Ensure the copied instance directory is
  writable by that user, or set `UID`/`GID`.
- Keep the Docker stop grace period longer than the image's shutdown announcement delay,
  or disable the announcement for fast deletes.

