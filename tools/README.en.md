# Preserving a Server Exit Incident

`agent-link-watchdog.ps1` covers the case where the JVM has already exited. The mod runs inside the server JVM and cannot answer after a JVM crash, so the watchdog preserves these files after the process exits:

- `logs/latest.log`
- `logs/debug.log` when present
- the newest `crash-reports/*.txt`
- `hs_err_pid*.log`, `replay_pid*.log`, and `java_error_in_*.log` when present
- `incident-ledger.json` when the mod has written one
- `incident.json` with process start information, PID-reuse detection, timestamps, and artifact records

It only watches and copies files. It does not restart the server or execute Minecraft commands.

## Usage

Start the server, find the Java process id, then run this in another PowerShell window:

```powershell
.\tools\agent-link-watchdog.ps1 `
  -ProcessId 12345 `
  -ServerDirectory 'C:\Minecraft\server'
```

The default output is `diagnostics/incident-timestamp/` under the server directory. Give the whole bundle to the agent after the server exits. `incident.json` records metadata for heap dumps larger than the copy limit instead of copying multi-GB `.hprof`/`.dmp` files. The watchdog never restarts the server or executes commands.

To change the polling interval:

```powershell
.\tools\agent-link-watchdog.ps1 -ProcessId 12345 -ServerDirectory . -PollMilliseconds 250
```

Before a release, run the offline contract check to catch drift between the Java tool schema, the
Node bridge, the diagnostic timeout field, and the watchdog parser:

```powershell
.\tools\validate-contract.ps1 -CheckBuiltArtifacts
```
