# mc-agent-link v0.1.3-alpha

> ⚠️ **Alpha**: hotfix on top of v0.1.2-alpha. WebSocket wire protocol (`v=0`), MCP HTTP transport, and tool surface unchanged.

This is a single-bug-fix release for `spark_profiler_stop` / `spark_profiler_cancel` / `spark_health_report`.

## What's fixed

In v0.1.2-alpha those three tools always returned `output: ""` and `url_present: false`, even though spark itself completed the upload and printed the viewer URL to the server console.

**Root cause** (the v0.1.2 fix was based on a wrong premise). spark routes most of its output, including the asynchronous viewer URL after a profiler upload, through `Logger.info(...)` on its own worker threads (`spark-worker-pool-*` / `ForkJoinPool.commonPool-worker-*`) — never through the `CommandSource.sendSystemMessage` callback we'd been listening on. The `MinecraftServer#managedBlock` wait introduced in `bc2aa69` was therefore parking on the server thread waiting for a callback that would never arrive on that thread, and falling out by timeout with nothing captured.

```
[spark-worker-pool-1-thread-1/INFO] MinecraftServer: [⚡] Stopping the profiler & uploading results, please wait...
[ForkJoinPool.commonPool-worker-2/INFO] MinecraftServer: [⚡] Profiler stopped & upload complete!
[ForkJoinPool.commonPool-worker-2/INFO] MinecraftServer: https://spark.lucko.me/eYtgK3GSLT
```

**Fix**: install a temporary log4j appender for the duration of each `runSpark` call, in addition to the existing `CommandSource` sink. The appender accepts log records that either contain spark's `⚡` marker character or match the viewer URL regex, appends them to the captured output, and completes a `CompletableFuture<String>` the moment a URL lands. The wait now uses `future.get(wait_url_ms, ...)` — no managed blocking, no server-thread coupling — and the appender is removed in `finally` whether the URL arrived or we timed out. spark's synchronous "uploading…" lines also get captured this way, so `output` is now a usable summary instead of an empty string.

`spark_stats`, `spark_status`, and `spark_profiler_start` were already correct and are unchanged.

Commit: see the release tag for the diff.

## Wire protocol / transports

Both transports unchanged:

- WebSocket on `:25580` — same `v=0`, same `hello` / `welcome` / `request` / `response` / `event` frames.
- MCP Streamable HTTP on `:25581/mcp` — same 20-tool surface, same auth/origin/accept/body checks, same `2025-06-18` protocol with `2025-03-26` fallback.

## Tool surface

Same 22 wire-level tools (20 over MCP HTTP). No schemas, descriptions, or instructions changed.

## Configuration

`config/agent-link.toml` is unchanged. Drop in the new jar, restart, you're done.

## Artifacts

| File | Size | Goes into |
|---|---|---|
| `agent-link-forge-1.20.1-0.1.3-alpha.jar` | ~245 KB | `<server>/mods/` (Forge 1.20.1, Java 17) |
| `agent-link-mcp-server-0.1.3-alpha.tgz` | ~13 KB | Unpack and point your MCP host at `dist/index.js` (only needed for hosts that don't speak HTTP transport) |
| `SHA256SUMS.txt` | — | Verify the above |

## Upgrade path from v0.1.2-alpha (or earlier)

Drop in the new jar, restart the server. No config changes required.

If you packaged the bridge tarball into your MCP host config, swap to the new tgz and restart the host.

## Verify the artifacts

```sh
sha256sum agent-link-forge-1.20.1-0.1.3-alpha.jar agent-link-mcp-server-0.1.3-alpha.tgz
```

Expected hashes are in `SHA256SUMS.txt`.

## License

[Apache-2.0](https://github.com/Nothingness-Void/mc-agent-link/blob/main/LICENSE).
