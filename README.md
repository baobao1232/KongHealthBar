# GamelifeHealthBar

Standalone Paper/Purpur plugin for Minecraft 1.21.x. It does not depend on HUDEngine or GamelifeHud.

## What is included
- Overhead pixel healthbar for mobs and players, shown when the viewer looks at the target or damages it.
- Dedicated generated resource pack (`plugins/GamelifeHealthBar/gamelifehealthbar.zip`) with a private font namespace and ten health frames.
- Resource pack delivery modes: `none`, `url`, and built-in `host`; join delivery, required pack, prompt, kick message, extra packs and pack order settings.
- `/ghb reload` to reload configuration.

## Build
Requires JDK 21 and Maven 3.9+ (or Maven Wrapper). Run from this directory:

```bat
mvn package
```

Output: `target/GamelifeHealthBar-1.0.0.jar`.

## Install
Copy the JAR to the server's `plugins` directory and restart. Edit `plugins/GamelifeHealthBar/config.yml`. If using `delivery: host`, open the configured TCP port and set `host.public-url` to an address reachable by clients. The default public URL is only an example and should be replaced for your server.

## Notes
This project contains only the healthbar plugin and its pack delivery/compiler code; HUD layouts, minimap, compass, placeholder system and HUDEngine modules are not included. Target API is Paper 1.21.8; verify on your exact Paper/Purpur version before production use.
