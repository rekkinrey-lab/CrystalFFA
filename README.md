# CrystalFFA

A Paper (1.21.4+) plugin for a 100x100 crystal and respawn anchor PvP free-for-all.

- Flat grass arena, solid down to bedrock (grass, dirt, stone, deepslate), fully diggable
- Crystals, respawn anchors and TNT all blow craters in the terrain
- Auto-regen: every N seconds all changed blocks are restored to their originals
- Indestructible floating spawn platform with timed spawn protection
- Kit on join and respawn, no fall damage, no hunger, inventory restored on leave

## Build

Requires Java 21 and Maven.

```
mvn package
```

Drop `target/CrystalFFA.jar` into your server's `plugins` folder.

## Use

Stand where the arena should be centered, then:

```
/cffa create    # build the arena (admin, wipes that 100x100 column)
/cffa join      # spawn on the platform and drop in
/cffa leave
/cffa info
/cffa regen     # admin: restore all changed blocks now
/cffa rebuild   # admin: rebuild everything from scratch
/cffa delete    # admin: unregister the arena
```

Permissions: `crystalffa.play` (default: everyone), `crystalffa.admin` (default: ops).
Everything else is in `config.yml`.
