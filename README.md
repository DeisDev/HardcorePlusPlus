<div align="center">

# HardcorePlus+

[![Build](https://img.shields.io/github/actions/workflow/status/DeisDev/HardcorePlusPlus/build.yml?branch=main&logo=github&label=CI)](https://github.com/DeisDev/HardcorePlusPlus/actions)
[![License](https://img.shields.io/github/license/DeisDev/HardcorePlusPlus)](LICENSE)
![Java](https://img.shields.io/badge/Java-21-orange)
![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-00aa00)
![Loaders](https://img.shields.io/badge/Loaders-Fabric%20%7C%20NeoForge-5b8c)

Server-side Hardcore mod that makes dying more punishing: if anyone dies, the whole server wipes and rotates to a fresh world.

</div>

## ✨ Features

- Server-only: drop-in on the server; clients don’t need the mod
- Hardcore rotation: on death (or command), stop server and swap to a new world
- Backups: move/copy the old world to a backup folder (or delete, configurable)
- Multiloader: Fabric 1.21.1 (Fabric API) and NeoForge 21.1.x

## 📥 Installation

Download from [Modrinth](https://modrinth.com/mod/hardcore-plus+) and place the jar in your server's `mods` folder.

- Fabric (1.21.1):
  - Requires Fabric Loader ≥ 0.17.3 and Fabric API compatible with 1.21.1
- NeoForge (1.21.1):
  - Requires NeoForge 21.1.x

This is a dedicated server-only mod. Clients do not need to install anything.

## 🔧 Configuration

Generated at `config/hardcoreplus.properties` on first run. Options cover backups vs delete, naming format, seed policy, and restart delay.

## ⌨️ Commands

All commands are under `/hcp`.

Key commands:
- `/hcp preview` — show the next world name and seed policy
- `/hcp time` — show MC day/time, ticks, and real uptime
- `/hcp reset` + `confirm` — rotate to a new world
- `/hcp masskill` + `confirm` — kill all players and schedule a reset
- `/hcp reload` — reload config

## 🚀 Admin wrapper (recommended)

Use a simple restart wrapper so the server comes back up after rotation (loop your `java -jar server.jar nogui`).

## 🧱 Building from source

Prereqs: JDK 21.

Windows:
```powershell
./gradlew.bat build
```

Linux/macOS:
```bash
./gradlew build
```

Artifacts:
- Fabric: `fabric/build/libs/`
- NeoForge: `neoforge/build/libs/`

## 📦 Support matrix

| Loader   | Minecraft | Status      |
|----------|-----------|-------------|
| Fabric   | 1.21.1    | Supported   |
| NeoForge | 21.1.x    | Supported   |

## 🤝 Contributing

PRs and issues welcome.

## 📜 License

MIT — see [LICENSE](LICENSE).
