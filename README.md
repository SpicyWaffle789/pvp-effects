# PvPEffects

A Paper 26.3 Minecraft plugin that introduces custom death/kill potion effect mechanics, full configuration customization, and Vault economy integration for purchasing positive effects and curing debuffs.

---

## Features

* **PvP Kill Rewards:** Defeating another player grants a permanent positive potion effect.
* **PvP Death Penalties:** Dying to another player applies a permanent negative potion effect.
* **Natural Death Penalties:** Dying from environmental or non-player causes applies a temporary negative debuff upon respawning (duration configurable).
* **Fully Customizable (`config.yml`):** Configure all positive/negative effect pools, economy cost percentages, minimum pricing, and debuff durations.
* **Economy Integration:** Integrates with Vault to allow players to buy positive effects or cure debuffs using customizable balance percentage formulas.
* **Admin Controls:** Comprehensive admin commands with tab completion to add, remove, or reset player effects and reload configs on the fly.
* **Data Persistence:** Player effect profiles automatically save to `players.yml` across server restarts and rejoin sessions.

---

## Requirements

* **Paper, Spigot, or Purpur Server** (Version 26.3)
* **Java 21** or higher
* **Vault** (Plugin)
* **An Economy Provider Plugin** (e.g., EssentialsX, CraftConomy)

---

## Commands & Permissions

### Player Commands
| Command | Aliases | Description | Default Cost Formula |
| :--- | :--- | :--- | :--- |
| `/buyeffect` | `/buygood` | Purchases a random unowned permanent positive effect | 20% of balance (Min: $50,000) |
| `/cureone` | *None* | Removes one active permanent negative effect | 10% of balance (Min: $25,000) |
| `/cureall` | *None* | Clears all active permanent negative effects | 30% of balance (Min: $75,000) |

### Admin Commands
| Command | Description |
| :--- | :--- |
| `/pvpeffects reload` | Reloads `config.yml` without restarting the server |
| `/pvpeffects add <player> <positive\|negative> <effect>` | Manually grants a permanent effect to a player |
| `/pvpeffects remove <player> <positive\|negative> <effect>` | Manually removes a permanent effect from a player |
| `/pvpeffects reset <player>` | Clears all permanent positive and negative effects from a player |

* **Aliases:** `/pvpadmin`, `/peadmin`

### Permissions
* `pvpeffects.use` - Allows players to use economy commands (`/buyeffect`, `/cureone`, `/cureall`). Default: `true`.
* `pvpeffects.admin` - Grants access to `/pvpeffects` admin commands. Default: `op`.

---

## Configuration (`config.yml`)

```yaml
# Pool of positive effects given on PvP kill or bought via /buyeffect
positive-effects:
  - SPEED
  - REGENERATION
  - STRENGTH
  - HASTE
  - RESISTANCE
  - FIRE_RESISTANCE
  - SATURATION

# Pool of negative effects given on PvP death or cured via /cureone / /cureall
negative-effects:
  - SLOWNESS
  - WEAKNESS
  - MINING_FATIGUE
  - BLINDNESS
  - HUNGER
  - NAUSEA

# Natural death settings
natural-death:
  duration-seconds: 300

# Economy formulas: Max(min-cost, player_balance * (percentage / 100))
pricing:
  buy-effect:
    percentage: 20.0
    min-cost: 50000.0
  cure-one:
    percentage: 10.0
    min-cost: 25000.0
  cure-all:
    percentage: 30.0
    min-cost: 75000.0
