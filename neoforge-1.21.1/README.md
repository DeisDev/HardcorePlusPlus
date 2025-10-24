# HardcorePlus+ (NeoForge 1.21.1)

This is a NeoForge 1.21.1 port of HardcorePlus+.

Highlights:
- Server-only hardcore world rotation with backups or deletion
- Human-readable world names from a stable base (no timestamp chaining)
- New seed per rotation (random or custom)
- Admin commands: /hcp status, preview, reset (confirm), reload, masskill (confirm), time
- Styled restart announcement with player name and world lifetime (HH:mm:ss)
- Non-blocking shutdown delay (configurable, default 10s)

## Build

Windows PowerShell:

```powershell
cd neoforge-1.21.1
./gradlew.bat build
```

The built JAR will be at `neoforge-1.21.1/build/libs/hardcoreplus-1.1.2-neoforge.jar`.

## Run (dev server)

```powershell
cd neoforge-1.21.1
./gradlew.bat runServer
```

On first run, accept EULA in `runs/server/eula.txt` and consider setting `online-mode=false` for local testing.

## Config

A `hardcoreplus.properties` file is created in the run directory with annotated keys:
- backup_old_worlds, delete_instead_of_backup, backup_folder_name, backup_name_format
- new_level_name_format, time_format
- force_new_seed, seed_mode, custom_seed
- restart_delay_seconds, auto_restart

## Notes
- Processes `hc_reset.flag` on server about-to-start to back up/delete the previous world before it loads.
- Tracks the current world’s start time in `hc_world_start.flag` to compute lifetime.
- Uses `server.properties` to set the next `level-name` and optional `level-seed`.