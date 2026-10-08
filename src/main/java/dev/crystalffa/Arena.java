package dev.crystalffa;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BlockVector;
import org.bukkit.util.BoundingBox;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A size x size flat grass arena with solid terrain all the way down to bedrock
 * (grass, dirt, stone, deepslate), boundary walls and an indestructible spawn platform
 * high above the middle.
 *
 * Players can dig, place and blow up the terrain (crystals, respawn anchors, TNT). The first time
 * any block changes, its original state is remembered; "regen" puts every remembered block back.
 * Only the platform, the boundary walls and the bottom bedrock layer are protected.
 */
public final class Arena {

    private final CrystalFFA plugin;
    private final World world;
    private final int centerX, centerZ, floorY;
    private final int size, platformHeight, platformRadius, wallHeight, maxBuildHeight;
    private final int minX, minZ, maxX, maxZ, platformY, bottomY;

    /** Original block data of every position players have changed (first change wins). */
    private final Map<BlockVector, BlockData> changed = new HashMap<>();
    /** Originals queued for restoration by a running regen. Edits to these get overwritten anyway. */
    private Map<BlockVector, BlockData> pending = new HashMap<>();

    private boolean rebuilding;
    private boolean regenerating;

    public Arena(CrystalFFA plugin, World world, int centerX, int centerZ, int floorY,
                 int size, int platformHeight, int platformRadius, int wallHeight, int maxBuildHeight) {
        this.plugin = plugin;
        this.world = world;
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.floorY = floorY;
        this.size = size;
        this.platformHeight = platformHeight;
        this.platformRadius = platformRadius;
        this.wallHeight = wallHeight;
        this.maxBuildHeight = maxBuildHeight;
        this.minX = centerX - size / 2;
        this.maxX = minX + size - 1;
        this.minZ = centerZ - size / 2;
        this.maxZ = minZ + size - 1;
        this.platformY = floorY + platformHeight;
        this.bottomY = world.getMinHeight();
    }

    public static Arena fromConfig(CrystalFFA plugin, World world, int cx, int cz, int floorY) {
        FileConfiguration c = plugin.getConfig();
        return new Arena(plugin, world, cx, cz, floorY,
                c.getInt("arena.size", 100),
                c.getInt("platform.height", 20),
                c.getInt("platform.radius", 5),
                c.getInt("arena.wall-height", 40),
                c.getInt("arena.max-build-height", 30));
    }

    public static Arena load(CrystalFFA plugin, YamlConfiguration d) {
        World w = plugin.getServer().getWorld(d.getString("world", ""));
        if (w == null) {
            return null;
        }
        return new Arena(plugin, w, d.getInt("center-x"), d.getInt("center-z"), d.getInt("floor-y"),
                d.getInt("size", 100), d.getInt("platform-height", 20), d.getInt("platform-radius", 5),
                d.getInt("wall-height", 40), d.getInt("max-build-height", 30));
    }

    public void save(YamlConfiguration d) {
        d.set("world", world.getName());
        d.set("center-x", centerX);
        d.set("center-z", centerZ);
        d.set("floor-y", floorY);
        d.set("size", size);
        d.set("platform-height", platformHeight);
        d.set("platform-radius", platformRadius);
        d.set("wall-height", wallHeight);
        d.set("max-build-height", maxBuildHeight);
    }

    /** @return an error message if this geometry can't be built, otherwise null. */
    public String validate() {
        if (size < 20 || size > 400) return "arena.size must be between 20 and 400.";
        if (platformRadius < 1 || platformRadius * 2 + 1 >= size) return "platform.radius is too large for the arena.";
        if (maxBuildHeight >= wallHeight) return "arena.max-build-height must be lower than arena.wall-height.";
        if (platformHeight + 6 > wallHeight) return "platform.height + 6 must fit under arena.wall-height.";
        if (floorY - bottomY < 12) return "Too close to the bottom of the world - stand higher up.";
        if (floorY + wallHeight >= world.getMaxHeight()) return "Not enough vertical room here - stand lower.";
        return null;
    }

    // ---- geometry ---------------------------------------------------------------------

    public World world() { return world; }
    public int floorY() { return floorY; }
    public int size() { return size; }
    public int maxBuildHeight() { return maxBuildHeight; }
    public boolean isBusy() { return rebuilding; }
    public int changedCount() { return changed.size(); }

    /** True for any block that belongs to the arena column (terrain, air volume, walls). */
    public boolean containsBlock(Block b) {
        return b.getWorld().equals(world) && containsBlock(b.getX(), b.getY(), b.getZ());
    }

    public boolean containsBlock(int x, int y, int z) {
        return x >= minX - 1 && x <= maxX + 1 && z >= minZ - 1 && z <= maxZ + 1
                && y >= bottomY && y <= floorY + wallHeight;
    }

    /** Blocks nobody may ever change: boundary walls, the bottom bedrock layer and the platform. */
    public boolean isProtected(Block b) {
        return isProtected(b.getX(), b.getY(), b.getZ());
    }

    public boolean isProtected(int x, int y, int z) {
        if (y <= bottomY) return true;
        if (x <= minX - 1 || x >= maxX + 1 || z <= minZ - 1 || z >= maxZ + 1) return true;
        return y == platformY
                && Math.abs(x - centerX) <= platformRadius
                && Math.abs(z - centerZ) <= platformRadius;
    }

    /** True while a location is somewhere a participant is allowed to be. */
    public boolean insidePlayArea(Location l) {
        if (l.getWorld() == null || !l.getWorld().equals(world)) return false;
        int x = l.getBlockX(), z = l.getBlockZ();
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ
                && l.getY() >= bottomY - 2 && l.getY() <= floorY + wallHeight + 2;
    }

    /** No placing blocks, anchors or crystals in or right around the spawn platform. */
    public boolean inNoBuildZone(int x, int y, int z) {
        return Math.abs(x - centerX) <= platformRadius + 2
                && Math.abs(z - centerZ) <= platformRadius + 2
                && y >= platformY - 2 && y <= platformY + 5;
    }

    public boolean onPlatform(Location l) {
        if (l.getWorld() == null || !l.getWorld().equals(world)) return false;
        int x = l.getBlockX(), y = l.getBlockY(), z = l.getBlockZ();
        return Math.abs(x - centerX) <= platformRadius + 1
                && Math.abs(z - centerZ) <= platformRadius + 1
                && y >= platformY && y <= platformY + 4;
    }

    public Location spawn() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int span = Math.max(0, platformRadius - 1);
        double x = centerX + 0.5 + (span == 0 ? 0 : r.nextInt(-span, span + 1));
        double z = centerZ + 0.5 + (span == 0 ? 0 : r.nextInt(-span, span + 1));
        return new Location(world, x, platformY + 1, z, r.nextFloat() * 360f - 180f, 0f);
    }

    // ---- change tracking --------------------------------------------------------------

    /** Remember what a block looked like before its first change. Call BEFORE it changes. */
    public void record(int x, int y, int z, BlockData original) {
        BlockVector k = new BlockVector(x, y, z);
        if (pending.containsKey(k)) return; // a running regen will overwrite it anyway
        changed.putIfAbsent(k, original);
    }

    public void record(Block b) {
        record(b.getX(), b.getY(), b.getZ(), b.getBlockData());
    }

    // ---- building & regenerating (batched across ticks so the server doesn't lag) -----

    private Material mat(String path, Material def) {
        Material m = Material.matchMaterial(plugin.getConfig().getString(path, def.name()));
        return m == null || !m.isBlock() ? def : m;
    }

    private void set(int x, int y, int z, Material m) {
        world.getBlockAt(x, y, z).setType(m, false);
    }

    private Material terrainAt(int y, Material surface) {
        if (y <= bottomY) return Material.BEDROCK;
        if (y == floorY) return surface;
        if (y >= floorY - 3) return Material.DIRT;
        return y < 0 ? Material.DEEPSLATE : Material.STONE;
    }

    private void drain(Deque<Runnable> queue, int rowCost, Runnable done) {
        int perTick = Math.max(1, plugin.getConfig().getInt("regen.blocks-per-tick", 6000) / Math.max(1, rowCost));
        new BukkitRunnable() {
            @Override
            public void run() {
                for (int i = 0; i < perTick && !queue.isEmpty(); i++) {
                    queue.poll().run();
                }
                if (queue.isEmpty()) {
                    cancel();
                    done.run();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void clearEntities() {
        BoundingBox box = new BoundingBox(minX, bottomY, minZ, maxX + 1, floorY + wallHeight + 1, maxZ + 1);
        for (Entity e : world.getNearbyEntities(box, en ->
                en instanceof EnderCrystal || en instanceof Item
                        || en instanceof AbstractArrow || en instanceof ExperienceOrb)) {
            e.remove();
        }
    }

    /** Fully (re)builds terrain, walls, optional pillars and the spawn platform. */
    public boolean rebuild(Runnable done) {
        if (rebuilding || regenerating) return false;
        rebuilding = true;
        changed.clear();
        pending = new HashMap<>();
        clearEntities();

        final Material surface = mat("arena.surface-material", Material.GRASS_BLOCK);
        final Material wall = mat("arena.wall-material", Material.BEDROCK);
        final Material base = mat("platform.material", Material.QUARTZ_BLOCK);
        final Material accent = mat("platform.accent-material", Material.SEA_LANTERN);
        final Material pillarMat = mat("arena.pillar-material", Material.OBSIDIAN);
        final int rowCost = size + 2;
        Deque<Runnable> q = new ArrayDeque<>();

        // 1) solid terrain from bedrock up to the grass surface (one z-row per task)
        for (int y = bottomY; y <= floorY; y++) {
            final int yy = y;
            final Material m = terrainAt(y, surface);
            for (int x = minX; x <= maxX; x++) {
                final int xx = x;
                q.add(() -> {
                    for (int z = minZ; z <= maxZ; z++) set(xx, yy, z, m);
                });
            }
        }

        // 2) wipe the air volume above the surface
        for (int y = floorY + 1; y <= floorY + wallHeight; y++) {
            final int yy = y;
            for (int x = minX; x <= maxX; x++) {
                final int xx = x;
                q.add(() -> {
                    for (int z = minZ; z <= maxZ; z++) set(xx, yy, z, Material.AIR);
                });
            }
        }

        // 3) boundary walls, one ring per layer, from bedrock to the top
        for (int y = bottomY; y <= floorY + wallHeight; y++) {
            final int yy = y;
            q.add(() -> {
                for (int x = minX - 1; x <= maxX + 1; x++) {
                    set(x, yy, minZ - 1, wall);
                    set(x, yy, maxZ + 1, wall);
                }
                for (int z = minZ; z <= maxZ; z++) {
                    set(minX - 1, yy, z, wall);
                    set(maxX + 1, yy, z, wall);
                }
            });
        }

        // 4) optional 2x2 cover pillars (deterministic from the seed)
        Random rnd = new Random(plugin.getConfig().getLong("arena.pillar-seed", 12345L));
        int pillars = plugin.getConfig().getInt("arena.pillars", 0);
        for (int i = 0; i < pillars; i++) {
            final int px = minX + 4 + rnd.nextInt(Math.max(1, size - 9));
            final int pz = minZ + 4 + rnd.nextInt(Math.max(1, size - 9));
            final int h = 3 + rnd.nextInt(8);
            q.add(() -> {
                for (int dx = 0; dx < 2; dx++)
                    for (int dz = 0; dz < 2; dz++)
                        for (int dy = 1; dy <= h; dy++)
                            set(px + dx, floorY + dy, pz + dz, pillarMat);
            });
        }

        // 5) the spawn platform
        final int r = platformRadius;
        for (int dx = -r; dx <= r; dx++) {
            final int ddx = dx;
            q.add(() -> {
                for (int dz = -r; dz <= r; dz++) {
                    boolean edge = Math.abs(ddx) == r || Math.abs(dz) == r;
                    boolean lit = edge && ((ddx + dz) & 1) == 0;
                    set(centerX + ddx, platformY, centerZ + dz, lit ? accent : base);
                }
            });
        }

        drain(q, rowCost, () -> {
            changed.clear();
            pending = new HashMap<>();
            rebuilding = false;
            if (done != null) done.run();
        });
        return true;
    }

    /**
     * Restores every block players changed (digging, placing, explosions) and removes loose
     * crystals/items/arrows.
     * @return number of blocks queued for restoration, or -1 if a rebuild/regen is already running.
     */
    public int regen() {
        if (rebuilding || regenerating) return -1;
        clearEntities();
        if (changed.isEmpty()) return 0;

        pending = new HashMap<>(changed);
        changed.clear();
        regenerating = true;

        final List<BlockVector> keys = new ArrayList<>(pending.keySet());
        Deque<Runnable> q = new ArrayDeque<>();
        final int chunk = 200;
        for (int i = 0; i < keys.size(); i += chunk) {
            final List<BlockVector> part = keys.subList(i, Math.min(keys.size(), i + chunk));
            q.add(() -> {
                for (BlockVector v : part) {
                    BlockData original = pending.remove(v);
                    if (original != null) {
                        world.getBlockAt(v.getBlockX(), v.getBlockY(), v.getBlockZ()).setBlockData(original, false);
                    }
                }
            });
        }
        drain(q, chunk, () -> {
            pending = new HashMap<>();
            regenerating = false;
        });
        return keys.size();
    }
}
