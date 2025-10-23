# HardcorePlus+ 

HardcorePlus+ is a **server-side only** mod for Minecraft that raises the stakes of multiplayer hardcore worlds. When any player dies, the **entire world is deleted and a new one is automatically generated**. The goal is for players to really think about their actions. 

## Features

- **Server-Side Only:** No client installation needed; simply add to your server mods folder.
- **Automatic World Reset:** When a player dies, the server wipes the world and generates a fresh one.
- **Multiplayer Focus:** Designed for groups seeking a truly hardcore experience with no second chances.

## Getting Started

### How to Create a Wrapper

To ensure smooth server restarts after a world reset, it’s recommended to use a wrapper script. Below is a simple example in Bash (Linux/macOS):

```bash
#!/bin/bash
while true; do
  java -Xmx4G -jar server.jar nogui
  echo "Server crashed or stopped. Restarting in 5 seconds..."
  sleep 5
done
```

- Place this script in your server directory.
- Update `server.jar` with your actual Minecraft server JAR name.
- Run the script instead of launching the server directly.

For Windows, a batch file example:

```batch
@echo off
:loop
java -Xmx4G -jar server.jar nogui
echo Server crashed or stopped. Restarting in 5 seconds...
timeout /t 5
goto loop
```

### Version Chart

| Mod Version | Minecraft Version | Notes           |
|-------------|------------------|-----------------|
| 1.1.2      | 1.21.8           | Supported  |
| ...         | ...              | ...             |



## About the Mod

HardcorePlusPlus automatically manages your world lifecycle for multiplayer hardcore servers:

- **World Deletion:** On player death, the mod triggers deletion of the current world folder.
- **World Creation:** A new world is generated without manual admin intervention.
- **Compatibility:** Works with vanilla and most server-side mods.

> **Note:** This mod is intended for server use only and does not require installation on client machines.

## Contributing

Contributions, bug reports, and feature requests are welcome! Please open an issue or pull request on [GitHub](https://github.com/DeisDev/HardcorePlusPlus).

---

Enjoy a truly unforgiving multiplayer experience with HardcorePlusPlus!
