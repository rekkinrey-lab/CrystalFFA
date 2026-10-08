package dev.crystalffa;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class CrystalFFA extends JavaPlugin implements CommandExecutor, TabCompleter {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final String PREFIX = "&b[CrystalFFA] &r";

    private Arena arena;
    private File dataFile;
    private int countdown;

    private final Map<UUID, PlayerState> players = new HashMap<>();
    private final Map<UUID, Long> protectedUntil = new HashMap<>();
    private final Map<UUID, Integer> kills = new HashMap<>();

    // ---- lifecycle --------------------------------------------------------------------

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        loadArena();
        countdown = getConfig().getInt("regen.interval-seconds", 300);

        getServer().getPluginManager().registerEvents(new ArenaListener(this), this);
        var cmd = getCommand("crystalffa");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }

        Bukkit.getScheduler().runTaskTimer(this, this::secondTick, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, this::guardTick, 5L, 5L);
    }

    @Override
    public void onDisable() {
        for (UUID id : new ArrayList<>(players.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) leave(p, false);
        }
        players.clear();
    }

    private void loadArena() {
        if (!dataFile.exists()) return;
        arena = Arena.load(this, YamlConfiguration.loadConfiguration(dataFile));
        if (arena == null) {
            getLogger().warning("Saved arena world is not loaded; arena disabled until it is.");
        }
    }

    private void saveArena() {
        YamlConfiguration d = new YamlConfiguration();
        arena.save(d);
        try {
            getDataFolder().mkdirs();
            d.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("Could not save data.yml: " + ex.getMessage());
        }
    }

    // ---- accessors used by the listener -----------------------------------------------

    public Arena arena() { return arena; }

    public boolean isInArena(Player p) { return players.containsKey(p.getUniqueId()); }

    public boolean isProtected(Player p) {
        return protectedUntil.getOrDefault(p.getUniqueId(), 0L) > System.currentTimeMillis();
    }

    public boolean isAdminBuilder(Player p) {
        return p.hasPermission("crystalffa.admin") && p.getGameMode() == GameMode.CREATIVE;
    }

    public void addKill(Player p) { kills.merge(p.getUniqueId(), 1, Integer::sum); }

    public void msg(CommandSender s, String text) {
        s.sendMessage(LEGACY.deserialize(PREFIX + text));
    }

    public void tellArena(String text) { tellArena(LEGACY.deserialize(PREFIX + text)); }

    public void tellArena(Component c) {
        for (UUID id : players.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.sendMessage(c);
        }
    }

    // ---- joining / leaving ------------------------------------------------------------

    public void join(Player p) {
        if (arena == null) { msg(p, "&cNo arena has been created yet."); return; }
        if (arena.isBusy()) { msg(p, "&cThe arena is being built - try again in a moment."); return; }
        if (isInArena(p)) { msg(p, "&cYou're already in the arena."); return; }

        players.put(p.getUniqueId(), PlayerState.capture(p));
        p.setGameMode(GameMode.SURVIVAL);
        p.leaveVehicle();
        p.teleport(arena.spawn());
        equip(p);
        protect(p);
        msg(p, "&aYou're on the safe platform. Step off to drop into the arena - last one standing wins the glory!");
    }

    /** Removes a player from the arena and (optionally) restores their previous state. */
    public void leave(Player p, boolean notify) {
        PlayerState state = players.remove(p.getUniqueId());
        protectedUntil.remove(p.getUniqueId());
        if (state == null) return;
        state.restore(p);
        if (notify) msg(p, "&7You left the arena.");
    }

    private void leaveAll(String reason) {
        for (UUID id : new ArrayList<>(players.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                leave(p, false);
                msg(p, reason);
            } else {
                players.remove(id);
            }
        }
    }

    public void protect(Player p) {
        long secs = getConfig().getLong("platform.protection-seconds", 20);
        protectedUntil.put(p.getUniqueId(), System.currentTimeMillis() + secs * 1000L);
    }

    /** Called after a death: re-kit and re-protect on the next tick (after the respawn finished). */
    public void rearm(Player p) {
        Bukkit.getScheduler().runTask(this, () -> {
            if (p.isOnline() && isInArena(p)) {
                equip(p);
                protect(p);
            }
        });
    }

    // ---- kit ---------------------------------------------------------------------------

    private ItemStack enchanted(Material m, Object... enchantsAndLevels) {
        ItemStack item = new ItemStack(m);
        for (int i = 0; i < enchantsAndLevels.length; i += 2) {
            item.addUnsafeEnchantment((Enchantment) enchantsAndLevels[i], (Integer) enchantsAndLevels[i + 1]);
        }
        return item;
    }

    private ItemStack armor(Material m) {
        return enchanted(m, Enchantment.PROTECTION, 4, Enchantment.UNBREAKING, 3, Enchantment.MENDING, 1);
    }

    public void equip(Player p) {
        PlayerInventory inv = p.getInventory();
        inv.clear();
        for (var effect : p.getActivePotionEffects()) p.removePotionEffect(effect.getType());

        inv.setHelmet(armor(Material.NETHERITE_HELMET));
        inv.setChestplate(armor(Material.NETHERITE_CHESTPLATE));
        inv.setLeggings(armor(Material.NETHERITE_LEGGINGS));
        inv.setBoots(armor(Material.NETHERITE_BOOTS));
        inv.setItemInOffHand(new ItemStack(Material.TOTEM_OF_UNDYING));

        inv.setItem(0, enchanted(Material.NETHERITE_SWORD, Enchantment.SHARPNESS, 5, Enchantment.UNBREAKING, 3));
        inv.setItem(1, new ItemStack(Material.END_CRYSTAL, 64));
        inv.setItem(2, new ItemStack(Material.OBSIDIAN, 64));
        inv.setItem(3, enchanted(Material.NETHERITE_PICKAXE, Enchantment.EFFICIENCY, 5, Enchantment.UNBREAKING, 3));
        inv.setItem(4, new ItemStack(Material.GOLDEN_APPLE, Math.max(1, getConfig().getInt("kit.golden-apples", 16))));
        inv.setItem(5, new ItemStack(Material.RESPAWN_ANCHOR, 64));
        inv.setItem(6, new ItemStack(Material.GLOWSTONE, 64));
        inv.setItem(7, enchanted(Material.NETHERITE_SHOVEL, Enchantment.EFFICIENCY, 5, Enchantment.UNBREAKING, 3));

        for (int i = 1; i < getConfig().getInt("kit.crystal-stacks", 2); i++)
            inv.addItem(new ItemStack(Material.END_CRYSTAL, 64));
        for (int i = 1; i < getConfig().getInt("kit.obsidian-stacks", 2); i++)
            inv.addItem(new ItemStack(Material.OBSIDIAN, 64));
        for (int i = 1; i < getConfig().getInt("kit.anchor-stacks", 1); i++)
            inv.addItem(new ItemStack(Material.RESPAWN_ANCHOR, 64));
        for (int i = 1; i < getConfig().getInt("kit.glowstone-stacks", 2); i++)
            inv.addItem(new ItemStack(Material.GLOWSTONE, 64));
        for (int i = 0; i < getConfig().getInt("kit.extra-totems", 3); i++)
            inv.addItem(new ItemStack(Material.TOTEM_OF_UNDYING));

        inv.setHeldItemSlot(0);
        p.setGameMode(GameMode.SURVIVAL);
        p.setHealth(PlayerState.maxHealth(p));
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.setFireTicks(0);
        p.setFallDistance(0f);
    }

    // ---- periodic tasks ----------------------------------------------------------------

    private void secondTick() {
        if (arena == null || arena.isBusy()) return;
        int interval = getConfig().getInt("regen.interval-seconds", 300);
        if (interval <= 0) return;

        countdown--;
        if (getConfig().getBoolean("regen.announce", true) && !players.isEmpty()
                && (countdown == 60 || countdown == 30 || countdown == 10 || (countdown > 0 && countdown <= 5))) {
            tellArena("&eArena regenerates in &6" + countdown + "s&e - craters and builds will be reset!");
        }
        if (countdown <= 0) {
            runRegen();
            countdown = interval;
        }
    }

    private int runRegen() {
        int n = arena.regen();
        if (n >= 0 && getConfig().getBoolean("regen.announce", true) && !players.isEmpty()) {
            tellArena("&aArena regenerated.");
        }
        return n;
    }

    /** Keeps participants inside the arena and ends spawn protection once they leave the platform. */
    private void guardTick() {
        if (arena == null) return;
        long now = System.currentTimeMillis();
        long grace = getConfig().getLong("platform.grace-seconds", 3) * 1000L;

        for (UUID id : new ArrayList<>(players.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || p.isDead()) continue;

            Location loc = p.getLocation();
            if (!arena.insidePlayArea(loc)) {
                p.teleport(arena.spawn());
                p.setFallDistance(0f);
                protect(p);
                continue;
            }
            long until = protectedUntil.getOrDefault(id, 0L);
            if (until > now + grace && !arena.onPlatform(loc)) {
                protectedUntil.put(id, now + grace);
            }
        }
    }

    // ---- commands ----------------------------------------------------------------------

    private boolean admin(CommandSender s) {
        if (s.hasPermission("crystalffa.admin")) return true;
        msg(s, "&cYou don't have permission for that.");
        return false;
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        String sub = a.length == 0 ? "help" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "join" -> {
                if (!(s instanceof Player p)) { msg(s, "Players only."); return true; }
                if (!p.hasPermission("crystalffa.play")) { msg(s, "&cYou don't have permission for that."); return true; }
                join(p);
            }
            case "leave" -> {
                if (!(s instanceof Player p)) { msg(s, "Players only."); return true; }
                if (!isInArena(p)) { msg(s, "&cYou're not in the arena."); return true; }
                leave(p, true);
            }
            case "info" -> {
                if (arena == null) { msg(s, "No arena has been created yet."); return true; }
                msg(s, "&7Arena: &f" + arena.size() + "x" + arena.size() + " in &f" + arena.world().getName()
                        + "&7 | players: &f" + players.size()
                        + "&7 | changed blocks: &f" + arena.changedCount()
                        + "&7 | next regen: &f" + countdown + "s");
            }
            case "stats" -> {
                if (!(s instanceof Player p)) { msg(s, "Players only."); return true; }
                msg(s, "&7Your kills this session: &f" + kills.getOrDefault(p.getUniqueId(), 0));
            }
            case "create" -> {
                if (!admin(s)) return true;
                if (!(s instanceof Player p)) { msg(s, "Run this in-game, standing where the arena should be centered."); return true; }
                if (arena != null) { msg(s, "&cAn arena already exists. Use &f/cffa delete&c first."); return true; }

                Location l = p.getLocation();
                Arena built = Arena.fromConfig(this, l.getWorld(), l.getBlockX(), l.getBlockZ(), l.getBlockY() - 1);
                String err = built.validate();
                if (err != null) { msg(s, "&c" + err); return true; }

                arena = built;
                saveArena();
                countdown = getConfig().getInt("regen.interval-seconds", 300);
                msg(s, "&eBuilding a &f" + built.size() + "x" + built.size()
                        + "&e grass arena centered on you, solid down to bedrock. Everything in that column gets overwritten - this takes a little while...");
                UUID creator = p.getUniqueId();
                built.rebuild(() -> {
                    Player pl = Bukkit.getPlayer(creator);
                    if (pl != null) {
                        pl.teleport(built.spawn());
                        msg(pl, "&aArena ready! Players can now use &f/cffa join&a. You've been moved to the spawn platform.");
                    }
                });
            }
            case "rebuild" -> {
                if (!admin(s)) return true;
                if (arena == null) { msg(s, "&cNo arena exists. Use /cffa create."); return true; }
                leaveAll("&eThe arena is being rebuilt.");
                if (!arena.rebuild(() -> msg(s, "&aArena rebuilt."))) msg(s, "&cA rebuild or regen is already running.");
                else msg(s, "&eRebuilding arena...");
            }
            case "regen" -> {
                if (!admin(s)) return true;
                if (arena == null) { msg(s, "&cNo arena exists."); return true; }
                int n = runRegen();
                countdown = getConfig().getInt("regen.interval-seconds", 300);
                msg(s, n < 0 ? "&cA rebuild or regen is already running." : "&aRegenerating - restoring &f" + n + "&a changed blocks.");
            }
            case "delete" -> {
                if (!admin(s)) return true;
                if (arena == null) { msg(s, "&cNo arena exists."); return true; }
                leaveAll("&eThe arena was deleted.");
                arena = null;
                if (dataFile.exists()) dataFile.delete();
                msg(s, "&aArena unregistered. The blocks stay in the world - remove them with WorldEdit if you don't want them.");
            }
            default -> {
                msg(s, "&7Commands: &f/cffa join&7, &f/cffa leave&7, &f/cffa info&7, &f/cffa stats");
                if (s.hasPermission("crystalffa.admin")) {
                    msg(s, "&7Admin: &f/cffa create&7, &f/cffa rebuild&7, &f/cffa regen&7, &f/cffa delete");
                }
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
        if (a.length != 1) return List.of();
        List<String> opts = new ArrayList<>(List.of("join", "leave", "info", "stats"));
        if (s.hasPermission("crystalffa.admin")) opts.addAll(List.of("create", "rebuild", "regen", "delete"));
        String prefix = a[0].toLowerCase(Locale.ROOT);
        opts.removeIf(o -> !o.startsWith(prefix));
        return opts;
    }
}
