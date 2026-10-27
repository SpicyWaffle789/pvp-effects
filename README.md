# PvPEffects

A Paper 1.20+ Minecraft plugin that introduces custom death/kill potion effect mechanics and Vault economy integration for purchasing positive effects and curing debuffs.

---

## Features

* **PvP Kill Rewards:** Defeating another player grants a permanent positive potion effect.
* **PvP Death Penalties:** Dying to another player applies a permanent negative potion effect.
* **Natural Death Penalties:** Dying from environmental or non-player causes applies a temporary 5-minute negative debuff upon respawning.
* **Economy Integration:** Integrates with Vault to allow players to purchase new positive effects or cure active debuffs.
* **Data Persistence:** Player effect profiles automatically save to `players.yml` across server restarts and rejoin sessions.

---

## Requirements

* **Paper, Spigot, or Purpur Server** (1.20 or newer)
* **Java 17** or higher
* **Vault** (Plugin)
* **An Economy Provider Plugin** (e.g., EssentialsX, CraftConomy)

---

## Commands & Pricing

| Command | Aliases | Description | Cost Formula |
| :--- | :--- | :--- | :--- |
| `/buyeffect` | `/buygood` | Purchases a random unowned permanent positive effect | 20% of current balance (Minimum: $50,000) |
| `/cureone` | *None* | Removes one active permanent negative effect | 10% of current balance (Minimum: $25,000) |
| `/cureall` | *None* | Clears all active permanent negative effects | 30% of current balance (Minimum: $75,000) |

---

## Permissions

* `pvpeffects.use` - Allows players to use `/buyeffect`, `/cureone`, and `/cureall`. Default: `true`.

---

## Installation

1. Download or compile the `PvPEffects.jar` file.
2. Ensure **Vault** and a compatible economy plugin are installed in your server's `plugins/` directory.
3. Place `PvPEffects.jar` into the `plugins/` directory.
4. Restart your server.

---

## Storage & Configuration

Player states are stored in `plugins/PvPEffects/players.yml`. 

```yaml
# Example player data entry
00000000-0000-0000-0000-000000000000:
  positives:
  - SPEED
  - INCREASE_DAMAGE
  negatives:
  - SLOW
