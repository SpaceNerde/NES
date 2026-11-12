# NES — Operator Runbook (v1)

> How to prepare, run and troubleshoot the admin-operated Docker setup described in
> `architecture.md`.

## 1. Host prerequisites

- Linux host with Docker Engine reachable from the proxy.
- The proxy user can access the Docker socket (or the configured endpoint).
- Free disk for templates, instance copies and worlds.
- A host port range reserved for backends, e.g. `25565–25665`.
- The proxy can reach `public-host:<port>` for every published port.
- A pinned Paper 26.3 image and the Java runtime that build requires.

> The Docker socket is root-equivalent. Treat access to it as host administrator
> access. A later node agent is the production answer for untrusted proxies.

## 2. Template preparation checklist

For each template under `templates/<id>/`:

- [ ] `eula.txt` contains `eula=true` and the server owner has accepted Mojang's EULA.
- [ ] `server.properties` sets `server-port=25565` and `online-mode=false`.
- [ ] Paper's `config/paper-global.yml` enables Velocity forwarding with the shared
      secret and `online-mode: true`.
- [ ] `velocity.toml` on the proxy uses `player-info-forwarding-mode = "modern"` and the
      same secret.
- [ ] No `world/`, `world_nether/` or `world_the_end/` directory is present if worlds
      should be generated on first boot.
- [ ] The template is small; large plugin packs make every create slower.
- [ ] The image is pinned by tag or digest.
- [ ] The template starts successfully on its own at least once.
- [ ] The server answers a status ping and accepts a player through Velocity.

## 3. First install

1. Start the proxy once to generate `config.toml`.
2. Set `owner-id`, `public-host`, `provider.docker.host`, `port-range` and roots.
3. Add at least one `[templates.<id>]` table.
4. Create `templates/<id>/` with the files from the checklist.
5. Grant permissions: `nes.admin` to operators, `nes.view` to read-only staff.
6. Restart the proxy.
7. Run `/nes list` to confirm the plugin loaded.
8. Run `/nes create <template>` and watch the log until it reports `READY`.

## 4. Daily operations

### Create

```
/nes create lobby
/nes create arena my-arena-01
```

The command returns immediately. Wait for the readiness message before advertising the
instance. Creation includes a template copy, container start and world generation.

### Inspect

```
/nes list
/nes info lobby-a3f91c
/nes logs lobby-a3f91c 200
```

`/nes info` should show state, address, template, provider reference, creation time,
ready time and last error.

### Delete

```
/nes delete lobby-a3f91c
/nes delete lobby-a3f91c --force
```

Deletion unregisters the instance, moves players to another instance of the same
template or disconnects them, stops the container, removes it and discards the instance
directory.

**Worlds are destroyed.** There is no archive in v1. Do not use delete on an instance
whose world matters.

### Reload

```
/nes reload
```

Applies template and routing changes to future creates. Provider settings, `owner-id`,
`public-host` and root directories require a restart. Running instances are never
modified.

## 5. Maintenance

### Proxy restart

Default behavior is to leave instances running. On startup the plugin adopts running
containers, re-registers the ready ones and resumes routing. Verify with `/nes list`
after the restart.

### Stop the whole fleet

Set `shutdown-behavior = "stop-all"` before shutting down, or stop containers manually.
Stopping does not delete data; a later start can adopt them if they are still running.

### Upgrade a template

1. Build and test the new image/template on a standalone server.
2. Update `[templates.<id>]` and/or the template directory.
3. `/nes reload`.
4. Create a new instance to validate.
5. Drain and delete old instances one by one.

Running instances keep the template they were created with.

### Disk cleanup

Instance directories are removed on delete. If a delete fails halfway, check for
orphaned `instances/<name>/` directories and `nes.managed=true` containers before
removing them manually.

## 6. Network and firewall

- Publish only the configured port range; do not expose the Docker socket.
- Allow the proxy to reach `public-host:<port>`.
- If players connect directly to backends, that is out of scope; v1 assumes all joins
  go through Velocity.
- Keep the forwarding secret out of shell history, logs and images.
- Consider binding published ports to a private interface instead of `0.0.0.0` if the
  proxy is on the same host.

## 7. Troubleshooting

| Symptom | Likely cause | Check |
|---|---|---|
| `/nes create` fails immediately | Unknown template or invalid name | `/nes list`; config validation message |
| Container exits during startup | Bad image, Java version, missing EULA, bad server files | `docker logs`, `/nes logs` |
| `STARTING` until timeout | World generation slow, port not reachable, server error | Raise `ready-timeout`; inspect backend log |
| Timeout but container is healthy | Proxy cannot reach the published address | `public-host`, firewall, port binding |
| Players cannot join a `READY` instance | Velocity forwarding secret mismatch or online-mode misconfiguration | Paper `paper-global.yml`, `velocity.toml`, backend log |
| `Invalid session` / auth errors | `online-mode` or forwarding mode is wrong | Template config |
| Port range exhausted | Too many running instances | `/nes list`; widen range or delete instances |
| Delete hangs | Players not leaving, backend not stopping | `--force`; inspect container |
| Delete leaves data | Stop/remove failed | Check `/nes info` last error; remove container and directory manually |
| Proxy restart lost instances | Containers stopped or labels missing | `docker ps -a --filter label=nes.managed=true` |
| Docker permission denied | Proxy user cannot access the socket | Socket permissions or group membership |

## 8. Security checklist

- [ ] Mutation commands are restricted to `nes.admin`.
- [ ] The Docker socket is accessible only to trusted administrators.
- [ ] The forwarding secret is stored with restricted permissions and never logged.
- [ ] Instance names and template ids cannot escape the instance root.
- [ ] Memory and CPU limits are set per template.
- [ ] Docker log rotation is configured.
- [ ] Backups exist for anything that is not disposable. v1 treats instance worlds as
      disposable by design.

## 9. What to monitor

Minimum:

- create failures and their stage;
- instances stuck in `STARTING` longer than `ready-timeout`;
- containers that exited while `READY`;
- port range utilization;
- free disk on the Docker host;
- Docker daemon reachability.

## 10. Known v1 limitations

- No warm pool: first join may wait for world generation.
- No autoscaling: an admin must create capacity ahead of demand.
- No world retention: delete is destructive.
- No multi-host placement: all instances share one Docker host.
- No automatic retry: failed creates are cleaned up, then left for the admin.
- No API for other plugins: only the command surface.
