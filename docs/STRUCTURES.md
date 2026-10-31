# Structure detection reference

Servers never tell the client which structures exist. Lumen Atlas recognizes them from data the client really has:
the blocks of loaded chunks and the entities near the player. This page lists every rule, how reliable it is, and
what can go wrong. The rules are in
[`StructureDetector.java`](../common/src/main/java/com/lumenatlas/structure/StructureDetector.java).

Reliability:

* **Unique** – the block cannot be crafted or obtained in survival and only generates in this structure.
  A false marker needs creative mode or commands.
* **Strong** – a combination of blocks that, as far as vanilla generation goes, only occurs in this structure.
  A player build that copies the combination can create a false marker.
* **Heuristic** – a typical but not exclusive signal. Works well in practice; false markers are possible.

Detection happens per chunk, once, when the chunk arrives. Several detections of the same structure kind closer
than the kind's *merge radius* become one marker (its position is the weighted average of all detections).

| Structure | Icon | Rule | Reliability | Merge radius |
|---|---|---|---|---|
| Village | villager head | a **bell**; or 3+ villagers within one 64-block cell | Strong / Heuristic | 112 |
| Stronghold | eye of ender | **end portal frame**; or 3+ infested stone bricks (any variant) | Unique / Strong | 160 |
| Ancient City | sculk face | **reinforced deepslate**; or 40+ deepslate tiles/bricks below y = 0 | Unique / Strong | 128 |
| Trial Chambers | trial spawner | **trial spawner** or **vault**; or 30+ tuff bricks with chiseled tuff | Unique / Strong | 96 |
| Woodland Mansion | mansion | an evoker or vindicator in a dark forest or pale garden | Heuristic | 96 |
| Pillager Outpost | pillager head | 24+ birch planks, 6+ dark oak logs and 8+ cobblestone in one chunk | Strong | 64 |
| Desert Pyramid | pyramid | blue terracotta + 4 orange terracotta + chiseled sandstone | Strong | 48 |
| Jungle Temple | temple | chiseled stone bricks + 15 mossy cobblestone + sticky piston or tripwire hook | Strong | 48 |
| Ocean Monument | guardian | 8+ dark prismarine, or prismarine bricks with sea lanterns; or an elder guardian | Strong | 96 |
| Shipwreck | ship | 40+ planks/stairs/slabs/trapdoors between 40 blocks below and 3 above sea level in an ocean or beach biome, without rails or cobwebs (which point to a mineshaft) | Heuristic | 40 |
| Nether Fortress | blaze head | 3+ nether brick fences or 64+ nether bricks; or a blaze | Strong | 176 |
| Bastion Remnant | piglin head | **gilded blackstone**; or 60+ polished blackstone bricks; or a piglin brute | Unique / Strong | 96 |
| End City | shulker | 12+ purpur blocks/pillars in the End; or a shulker | Strong | 96 |
| Mineshaft | minecart | 4+ rails, a cobweb and 2+ fences below sea level; or a chest minecart deep underground | Strong / Heuristic | 96 |
| Ruined Portal | portal | crying obsidian with obsidian (and netherrack/magma in the Overworld) | Strong | 32 |
| Igloo | igloo | 20+ snow blocks with white carpet, a red bed or ice | Heuristic | 32 |
| Witch Hut | witch hat | a potted red mushroom with a cauldron or spruce fences | Strong | 32 |
| Ocean Ruins | broken column | suspicious sand/gravel in an ocean; or 12+ cracked/mossy stone bricks under water | Strong | 48 |
| Trail Ruins | decorated pot | suspicious gravel outside of oceans | Strong | 64 |
| Amethyst Geode | crystal | **budding amethyst** | Unique | 24 |
| Monster Dungeon | spawner | a spawner with 8+ mossy cobblestone | Strong | 16 |
| Nether Fossil | bone | 12+ bone blocks in the Nether | Heuristic | 32 |
| Buried Treasure | chest | – (only from the seed map) | – | – |
| Abandoned Camp | tent | – (only from the seed map; new in 26.3) | – | – |

## Known limitations

* **Only what was loaded is detected.** A structure is found when its chunks are within your render distance (and
  the server's view distance). Underground structures (trial chambers, ancient cities, strongholds) are found from
  above too, because the client receives whole chunk columns.
* **Player builds can trigger markers** for the *Strong* and *Heuristic* rules (for example a desert-pyramid replica
  or a nether-brick house in the Nether). Right-click the marker on the world map and choose *Hide this marker*.
* **Big structures can produce two markers** when they are larger than the merge radius (long mineshafts, very large
  villages). This is intentional: merging too eagerly would swallow two real structures that are close together.
* **Raids**: illagers spawned by a raid in a dark forest could be taken for a mansion. Raids rarely happen there,
  because dark forests have no villages.
* **Server-side anti-xray / ore obfuscation** plugins replace hidden blocks with stone for the client. Underground
  structures may then only be detected once you can see them.
* **Mods and data packs** that add structures are not detected (the seed map can still show them, see the README).
