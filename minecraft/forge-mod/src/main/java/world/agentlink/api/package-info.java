/**
 * Public API for mc-agent-link addon mods.
 *
 * <p>Everything in this package is part of the stable surface — addon mods can depend on it
 * across minor versions. Anything OUTSIDE this package (e.g. {@code world.agentlink.dispatch.*},
 * {@code world.agentlink.transport.*}, {@code world.agentlink.config.*}) is internal and may
 * change without notice.
 *
 * <p>The standard entry point is {@link world.agentlink.api.AgentLinkApi}. It exposes independent
 * facades for tools, requests, events, tasks, diagnostics, server-thread execution, roles, build zones, and
 * typed vanilla controls for items, players, entities, worlds, scoreboards, and server lifecycle.
 * From an addon's mod constructor:
 *
 * <pre>{@code
 * AgentLinkApi.registerTool(MOD_ID, new MyTool());
 * if (AgentLinkApi.isAdmin(playerUuid)) { ... }
 * }</pre>
 */
package world.agentlink.api;
