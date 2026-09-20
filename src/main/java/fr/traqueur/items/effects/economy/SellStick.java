package fr.traqueur.items.effects.economy;

import fr.traqueur.items.api.ItemsPlugin;
import fr.traqueur.items.api.annotations.AutoEffect;
import fr.traqueur.items.api.effects.EffectContext;
import fr.traqueur.items.api.effects.EffectHandler;
import fr.traqueur.items.api.interactions.InteractionAction;
import fr.traqueur.items.api.managers.DurabilityManager;
import fr.traqueur.items.api.shop.ShopProvider;
import fr.traqueur.items.effects.economy.SellStickSettings;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

@AutoEffect(value = "SELL_STICK")
public record SellStick(
        ItemsPlugin plugin) implements EffectHandler.SingleEventEffectHandler<SellStickSettings, PlayerInteractEvent> {

    @Override
    public void handle(EffectContext effectContext, SellStickSettings settings) {
        PlayerInteractEvent event = effectContext.getEventAs(this.eventType());
        Player player = effectContext.executor();

        if (settings.hand() != null && event.getHand() != settings.hand()) {
            return;
        }

        InteractionAction action = getActionFromEvent(event, player);
        if (settings.action() != null && settings.action() != InteractionAction.CLICK && settings.action() != action) {
            return;
        }

        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }

        if (!(block.getState() instanceof Container container)) {
            return;
        }

        ShopProvider provider;
        try {
            provider = ShopProvider.get();
        } catch (IllegalStateException e) {
            return; // no provider registered: the stick is inert, the interaction stays vanilla
        }
        event.setCancelled(true);

        List<ItemStack> itemStacks = new ArrayList<>();
        for (ItemStack itemStack : container.getInventory().getContents()) {
            if (itemStack == null || itemStack.getType() == Material.AIR) {
                itemStacks.add(new ItemStack(Material.AIR));
            } else {
                boolean sold = provider.sell(plugin, itemStack, itemStack.getAmount(), settings.multiplier(), player);
                itemStacks.add(!sold ? itemStack : new ItemStack(Material.AIR));
            }
        }
        container.getInventory().setContents(itemStacks.toArray(ItemStack[]::new));

        if (settings.damage()) {
            JavaPlugin.getPlugin(ItemsPlugin.class).getManager(DurabilityManager.class).applyDamage(event.getItem(), 1, player);
        }
    }

    private InteractionAction getActionFromEvent(PlayerInteractEvent event, Player player) {
        return switch (event.getAction()) {
            case RIGHT_CLICK_BLOCK, RIGHT_CLICK_AIR ->
                    player.isSneaking() ? InteractionAction.SHIFT_RIGHT_CLICK : InteractionAction.RIGHT_CLICK;
            case LEFT_CLICK_BLOCK, LEFT_CLICK_AIR ->
                    player.isSneaking() ? InteractionAction.SHIFT_LEFT_CLICK : InteractionAction.LEFT_CLICK;
            default -> null;
        };
    }

    @Override
    public int priority() {
        return 0;
    }
}