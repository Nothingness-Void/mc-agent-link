package world.agentlink.spigot;

import com.google.gson.JsonObject;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Publishes the base agent-link event topics; no in-game agent request topic is registered. */
public final class SpigotEventListener implements Listener {
    private final EventBuffer events;

    public SpigotEventListener(EventBuffer events) {
        this.events = events;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        JsonObject data = new JsonObject();
        data.addProperty("player", event.getPlayer().getName());
        data.addProperty("uuid", event.getPlayer().getUniqueId().toString());
        data.addProperty("message", event.getMessage());
        events.publish("chat", data);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        JsonObject data = playerData(event.getPlayer());
        events.publish("player_join", data);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        JsonObject data = playerData(event.getPlayer());
        events.publish("player_leave", data);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        JsonObject data = playerData(event.getEntity());
        if (event.getDeathMessage() != null) data.addProperty("death_message", event.getDeathMessage());
        events.publish("player_death", data);
    }

    private static JsonObject playerData(org.bukkit.entity.Player player) {
        JsonObject data = new JsonObject();
        data.addProperty("player", player.getName());
        data.addProperty("uuid", player.getUniqueId().toString());
        data.addProperty("dim", player.getWorld().getKey().toString());
        data.addProperty("x", player.getLocation().getX());
        data.addProperty("y", player.getLocation().getY());
        data.addProperty("z", player.getLocation().getZ());
        return data;
    }
}
