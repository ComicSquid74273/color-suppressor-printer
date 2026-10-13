# Color Suppressor Printer

A [Meteor Client](https://meteorclient.com) addon for Minecraft 1.21.11 that automates
two-bot colour-suppression map printing using Év's **step-checker** method (W→E).

## What it does

Two bots work together to print map art:

- **Master** — walks real checkpoint platforms holding the working map, recording
  pixels at measured distances. Fills the map, walks both sweeps per batch, signals
  the slave when to break, locks the finished map at a cartography table.
- **Slave** — builds each 4×128×4 batch window from chest stock, breaks the
  dominant layer (Y2/Y3) on the master's signal, deposits recycled blocks back
  into the correct chests, breaks the full window on the second signal, and
  sweeps any stragglers using spatial awareness.

## Quick start

1. Build: `./gradlew clean build` → jar in `build/libs/`
2. Deploy the jar to both Meteor client instances
3. On each instance: enable the module, set **bot-role** (master or slave)
4. Register stations on the master: map area → cartography table → finished-map
   chest → anvil → food chest → XP-bottle chest → material/map chests
5. Press **START ALL** on the master's module HUD

## Requirements

- Minecraft 1.21.11 (Fabric)
- Meteor Client
- Two accounts (one master, one slave)
- Empty maps + glass panes (in a registered chest)
- Netherite pickaxe (in a registered chest)
- Food (in a registered chest)

## Module settings

| Setting | Default | Description |
|---------|---------|-------------|
| bot-role | empty | master / slave / empty (must be set before starting) |
| interaction-range | 5 | Block placement/breaking range |
| place-delay | 10ms | Delay between block placements |
| sprint-mode | Always | Sprint behaviour |
| use-default-config-file | on | Auto-load/save config on activation |

## Building from source

```bash
git clone <this-repo>
cd <repo>
./gradlew clean build
# jar: build/libs/color-suppressor-printer-1.21.11.jar
```

## License

See [LICENSE](LICENSE).
