# Changelog

## 1.1.0
- Support for every Minecraft 26.x release: separate jars for 26.1 – 26.1.2, 26.2 and 26.3, on Fabric and NeoForge.
- Seed map: new **Read seed from server** button. Uses `/seed` where the server allows it (operators, LAN worlds
  with cheats); the button is only enabled when the server offers you the command.
- Seed map: manually entered seeds now load only the vanilla world generation data instead of all server resources.
  Much faster (about 0.1 s), and it fixes a freeze on NeoForge 26.3.

## 1.0.0
- First release for Minecraft 26.3 (Fabric and NeoForge): minimap, world map, automatic structure detection,
  waypoints and the offline seed map.
