package dev.crystalffa;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Snapshot of a player taken when they join the arena, restored when they leave. */
public record PlayerState(Location location, ItemStack[] contents, GameMode mode,
                          double health, int food, float saturation, int level, float exp) {

    public static PlayerState capture(Player p) {
        ItemStack[] raw = p.getInventory().getContents();
        ItemStack[] copy = new ItemStack[raw.length];
        for (int i = 0; i < raw.length; i++) {
            copy[i] = raw[i] == null ? null : raw[i].clone();
        }
        return new PlayerState(p.getLocation().clone(), copy, p.getGameMode(), p.getHealth(),
                p.getFoodLevel(), p.getSaturation(), p.getLevel(), p.getExp());
    }

    public void restore(Player p) {
        p.getInventory().clear();
        p.getInventory().setContents(contents);
        p.setGameMode(mode);
        p.setLevel(level);
        p.setExp(exp);
        p.setFoodLevel(food);
        p.setSaturation(saturation);
        for (var effect : p.getActivePotionEffects()) {
            p.removePotionEffect(effect.getType());
        }
        p.setFireTicks(0);
        p.setFallDistance(0f);
        p.setHealth(Math.max(1.0, Math.min(health, maxHealth(p))));
        p.teleport(location);
    }

    public static double maxHealth(Player p) {
        AttributeInstance attr = p.getAttribute(Attribute.MAX_HEALTH);
        return attr == null ? 20.0 : attr.getValue();
    }
}
