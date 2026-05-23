# Claude Code skills for mc-agent-link

These four skills wrap the `agent-link` MCP server's tools into common workflows. They're committed in-repo so anyone who clones can `cd` here and immediately get the right slash commands.

| skill | when it fires | what it does |
|---|---|---|
| `/mc-health-check` | after switching servers, "are you connected?" | 3-call check: ping + stats + mods. Confirms bridge is up. |
| `/mc-overview` | "what's going on?", "is the server ok?" | One-round snapshot: health + players + events + errors + mods. Suggests one next step. |
| `/mc-diagnose` | lag, low TPS, stutters, "feels slow" | tick_profile → thread_dump → optional spark → mod attribution → config recommendation. Asks before writing. |
| `/mc-crash` | "why did it crash?", server died | Reads newest crash-reports/*.txt, correlates with mods + recent error logs, proposes a fix. |

## Why also write these as skills

The MCP server already ships an `instructions` field that any compliant host (Claude Code, Cursor, Zed) reads on connect. That handles the **"what is this server, what tools exist"** layer for every agent on every host.

Skills are the **Claude Code-specific** convenience layer: they let the user type `/mc-diagnose` and get a deterministic procedure, and they encode safety rails (don't run `stop`, ask before `write_config_file`, etc.) in a way the model can fall back to even if the MCP `instructions` window has been pushed out by long context.

## Using these from other agents

For agents without skill support, the same procedures live in plain markdown — feed `SKILL.md` to your agent as a prompt template, or extract the procedure text into your own system prompt.

## Editing

Each skill is one file: `.claude/skills/<name>/SKILL.md`. The frontmatter `description` is what Claude Code matches against user intent, so keep it specific (verbs the user would actually say, not abstract nouns).
