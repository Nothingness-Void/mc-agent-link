# mc-agent-link v0.1.1-alpha

> ⚠️ **Alpha**: hotfix on top of v0.1.0-alpha. Wire protocol (`v=0`) and tool surface unchanged.

This is a single-bug-fix release for `spark_stats`.

## What's fixed

`spark_stats` was returning `NoSuchMethodException` for every window in real-world testing against `spark-1.10.53-forge`:

```
NoSuchMethodException: me.lucko.spark.common.api.SparkApi$4.poll(me.lucko.spark.api.statistic.StatisticWindow)
```

**Root cause**: `SparkBridge` looked up methods on `stat.getClass()`, which returns spark's anonymous inner-class implementations (e.g. `SparkApi$4`). After type erasure, those classes only expose `poll(TicksPerSecond)` plus a synthetic bridge `poll(Object)` — neither matches `getMethod("poll", StatisticWindow.class)`.

**Fix**: look the method up on the public `DoubleStatistic` / `GenericStatistic` / `GarbageCollector` interfaces instead, whose erasures are exactly what we want. Same change applied to `DoubleAverageInfo` accessors. Each interface is loaded through the receiver's classloader to survive Forge's per-mod classloader isolation, and reflection exceptions are unwrapped to their root cause so error messages no longer say `InvocationTargetException`.

After this fix, `spark_stats` returns multi-window TPS / MSPT / CPU (process+system) / GC stats as designed — verified against spark 1.10.53 in production.

Commit: [`795d265`](https://github.com/Nothingness-Void/mc-agent-link/commit/795d265).

## Nothing else changed

- Wire protocol: same `v=0`.
- Tool surface: same 20 tools.
- Configuration shape: same `agent-link.toml` (`write_allow` / `write_deny` etc.).
- INSTRUCTIONS string: unchanged.
- Skills: unchanged.

If you weren't using `spark_stats`, this release is purely cosmetic for you.

## Artifacts

| File | Size | Goes into |
|---|---|---|
| `agent-link-forge-1.20.1-0.1.1-alpha.jar` | ~228 KB | `<server>/mods/` (Forge 1.20.1, Java 17) |
| `agent-link-mcp-server-0.1.1-alpha.tgz` | ~13 KB | `npm install -g <tgz>` or unpack and point your MCP host at `dist/index.js` |
| `SHA256SUMS.txt` | — | Verify the above |

## Upgrade path from v0.1.0-alpha

Drop in the new jar, restart the server. The `agent-link.toml` from v0.1.0-alpha works as-is.

If you packaged the tarball into your MCP host config, swap to the new tgz and restart the host.

## Verify the artifacts

```sh
sha256sum agent-link-forge-1.20.1-0.1.1-alpha.jar agent-link-mcp-server-0.1.1-alpha.tgz
```

Expected hashes are in `SHA256SUMS.txt`.

## License

[Apache-2.0](https://github.com/Nothingness-Void/mc-agent-link/blob/main/LICENSE).
