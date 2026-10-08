package dev.crystalffa;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.List;

public final class ArenaListener implements Listener {

    private final CrystalFFA plugin;

    public ArenaListener(CrystalFFA plugin) {
        this.plugin = plugin;
    }

    // ---- terrain: players can dig, build and blow things up; regen puts it all back ---

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Arena a = plugin.arena();
        if (a == null) return;
        Block b = e.getBlockPlaced();
        if (!a.containsBlock(b)) return;

        Player p = e.getPlayer();
        if (!plugin.isInArena(p)) {
            if (!plugin.isAdminBuilder(p)) e.setCancelled(true);
            return;
        }
        if (a.isProtected(b)
                || a.inNoBuildZone(b.getX(), b.getY(), b.getZ())
                || b.getY() > a.floorY() + a.maxBuildHeight()
                || b.getType().hasGravity()) { // falling blocks would escape regen tracking
            e.setCancelled(true);
            plugin.msg(p, "&cYou can't build there.");
            return;
        }
        a.record(b.getX(), b.getY(), b.getZ(), e.getBlockReplacedState().getBlockData());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Arena a = plugin.arena();
        if (a == null) return;
        Block b = e.getBlock();
        if (!a.containsBlock(b)) return;

        Player p = e.getPlayer();
        if (!plugin.isInArena(p)) {
            if (!plugin.isAdminBuilder(p)) e.setCancelled(true);
            return;
        }
        if (a.isProtected(b)) {
            e.setCancelled(true); // walls, bottom bedrock and the spawn platform are indestructible
            return;
        }
        a.record(b); // still the original block at this point
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) {
        Arena a = plugin.arena();
        if (a != null && a.containsBlock(e.getBlock()) && !plugin.isAdminBuilder(e.getPlayer())) {
            e.setCancelled(true); // flowing liquid can't be tracked for regen
        }
    }

    // ---- explosions (end crystals, respawn anchors, TNT): craters are tracked, protected blocks survive

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        filterExplosion(e.blockList());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        filterExplosion(e.blockList());
    }

    private void filterExplosion(List<Block> blocks) {
        Arena a = plugin.arena();
        if (a == null) return;
        blocks.removeIf(b -> a.containsBlock(b) && a.isProtected(b));
        for (Block b : blocks) {
            if (a.containsBlock(b)) a.record(b); // blocks are still intact while the event runs
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCrystalPlace(EntityPlaceEvent e) {
        Arena a = plugin.arena();
        if (a == null || !(e.getEntity() instanceof EnderCrystal)) return;
        Block at = e.getEntity().getLocation().getBlock();
        Block on = e.getBlock();
        if (a.inNoBuildZone(at.getX(), at.getY(), at.getZ())
                || a.inNoBuildZone(on.getX(), on.getY(), on.getZ())) {
            e.setCancelled(true);
            Player p = e.getPlayer();
            if (p != null) plugin.msg(p, "&cNo crystals near the spawn platform.");
        }
    }

    // ---- spawn protection & rules -----------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || !plugin.isInArena(p)) return;
        if (plugin.isProtected(p)) {
            e.setCancelled(true);
            return;
        }
        if (e.getCause() == EntityDamageEvent.DamageCause.FALL
                && plugin.getConfig().getBoolean("rules.no-fall-damage", true)) {
            e.setCancelled(true);
        }
    }

    /** Protected players can't hurt anyone either, so the platform can't be used as a sniper nest. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent e) {
        Player attacker = attackerOf(e.getDamager());
        if (attacker != null && plugin.isInArena(attacker) && plugin.isProtected(attacker)) {
            e.setCancelled(true);
        }
    }

    private Player attackerOf(Entity damager) {
        if (damager instanceof Player direct) return direct;
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Player shooter) return shooter;
        return null;
    }

    @EventHandler(ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && plugin.isInArena(p)
                && plugin.getConfig().getBoolean("rules.no-hunger", true)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (plugin.isInArena(e.getPlayer())) e.setCancelled(true);
    }

    // ---- death, respawn, quit ---------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (!plugin.isInArena(p)) return;

        e.getDrops().clear();
        e.setDroppedExp(0);
        e.setKeepInventory(false);

        Player killer = p.getKiller();
        if (killer != null && killer != p && plugin.isInArena(killer)) plugin.addKill(killer);

        // keep death messages inside the arena instead of spamming the whole server
        Component msg = e.deathMessage();
        e.deathMessage(null);
        if (msg != null) plugin.tellArena(msg);

        // skip the death screen
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline() && p.isDead() && plugin.isInArena(p)) p.spigot().respawn();
        });
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Arena a = plugin.arena();
        if (a == null || !plugin.isInArena(p)) return;
        e.setRespawnLocation(a.spawn());
        plugin.rearm(p);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (plugin.isInArena(e.getPlayer())) plugin.leave(e.getPlayer(), false);
    }
}
