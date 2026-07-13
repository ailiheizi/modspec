# ModSpec Protocol (draft v0.1)

## Transport

| Channel | Port | Purpose |
|---------|------|---------|
| HTTP | 8764 | Pairing, `/health` |
| WebSocket | 8765 | JSON-RPC 2.0 `/rpc` |

Pairing: QR / 6-digit code + user confirm on phone (see PlainApp / LocalShare patterns).

## RPC Methods

| Method | Params | Result |
|--------|--------|--------|
| `ping` | — | `{ "pong": true }` |
| `get_status` | — | `DeviceStatus` |
| `apply_profile` | `ApplyProfileParams` | `{ job_id }` |
| `toggle_mod` | `ToggleModParams` | `{ ok }` |
| `verify` | `{ profile_id? }` | drift list |
| `reapply` | `{ only_failed? }` | `{ job_id }` |
| `collect_logs` | `{ verbose?, since? }` | log lines |

## Events (notifications)

- `apply_progress`, `apply_completed`, `apply_failed`
- `state_changed`, `boot_completed`

## Agent storage (on device)

```text
/data/data/com.modspec.agent/files/
  profiles/
  rules/
  state.json
```

## LSPosed integration

Agent shell-out to `lsposed-cli` when available ([LSPosed_mod](https://github.com/mywalkb/LSPosed_mod/wiki/CLI)):

- `modules set -e/-d <pkg>`
- `scope set -s/-a/-d <module> <scopes...>`
- `backup` / `restore`
- `log -v`

Rule hooks compiled in-agent via libxposed API 102 interceptor chain.
