package fr.traqueur.items.packetevents;

import com.github.retrooper.packetevents.PacketEvents;
import fr.traqueur.items.api.ItemsPlugin;
import fr.traqueur.items.api.Logger;
import fr.traqueur.items.api.annotations.AutoHook;
import fr.traqueur.items.api.hooks.Hook;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Registers the dynamic durability lore listener on PacketEvents.
 *
 * <p>This hook is only activated when the standalone {@code packetevents} plugin is present
 * (see {@link AutoHook}), and that plugin has already published its API instance by the time
 * zItems loads. {@code PacketEvents.getAPI()} is a static shared by every plugin on the server,
 * so the hook must never overwrite, initialise or terminate an instance it does not own:
 * terminating the standalone plugin's instance would tear down the injector every other
 * PacketEvents consumer relies on. The hook only builds its own API when none exists, and
 * only then does it drive that instance's lifecycle.</p>
 */
@AutoHook("packetevents")
public class PacketEventsHook implements Hook {

    private boolean ownsApi;
    private DurabilityPacketListener listener;

    @Override
    public void onLoad() {
        if (PacketEvents.getAPI() != null) {
            Logger.info("PacketEvents already initialised by another plugin - reusing it.");
            return;
        }
        ItemsPlugin plugin = JavaPlugin.getPlugin(ItemsPlugin.class);
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(plugin));
        PacketEvents.getAPI().getSettings()
                .bStats(false)
                .checkForUpdates(false);
        PacketEvents.getAPI().load();
        ownsApi = true;
    }

    @Override
    public void onEnable() {
        if (ownsApi) {
            PacketEvents.getAPI().init();
        }
        listener = new DurabilityPacketListener();
        PacketEvents.getAPI().getEventManager().registerListener(listener);
        Logger.info("PacketEvents hook enabled - dynamic durability lore active.");
    }

    @Override
    public void onDisable() {
        if (listener != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(listener);
            listener = null;
        }
        if (ownsApi) {
            PacketEvents.getAPI().terminate();
            ownsApi = false;
        }
    }
}
