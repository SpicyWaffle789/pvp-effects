# PvPEffects

A Paper 26.3 Minecraft plugin that introduces custom death/kill potion effect mechanics, anti-farming kill limits, configurable effect caps, and Vault economy integration for purchasing positive effects and curing debuffs.

---

## Features

- **PvP Kill Rewards:** Defeating another player grants a permanent positive potion effect (up to the positive effect cap).
- **Anti-Farming Kill Limits:** Prevent farming by setting a configurable limit (`max-kills-per-victim`) on how many times killing the same victim grants rewards.
- **Dynamic PvP Death Penalties:** Dying in PvP strips a random positive permanent effect first. If the player has no positive effects left, a permanent negative effect is applied (up to the negative effect cap).
- **Natural Death Penalties:** Dying from environmental or non-player causes applies a temporary negative debuff upon respawning (duration configurable).
- **Configurable Effect Caps:** Set maximum limits for active positive (`max-positive-effects`) and negative (`max-negative-effects`) permanent effects.
- **Economy Integration:** Integrates with Vault to allow players to buy positive effects (`/buyeffect`) or cure debuffs using balance percentage formulas.
- **Admin Controls:** Comprehensive admin commands with tab completion to add, remove, or reset player effects and kill histories on the fly.
- **Data Persistence:** Player effect profiles and unique kill histories automatically save to `players.yml` across server restarts and rejoining sessions.

---

## Requirements

- **Paper, Spigot, or Purpur Server** (Version 26.3)
- **Java 25** or higher
- **Vault** (Plugin)
- **An Economy Provider Plugin** (e.g., EssentialsX, CraftConomy)

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
| `/pvpeffects reset <player>` | Clears all permanent effects and kill count history for a player |

- **Aliases:** `/pvpadmin`, `/peadmin`

### Permissions
- `pvpeffects.use` - Allows players to use economy commands (`/buyeffect`, `/cureone`, `/cureall`). Default: `true`.
- `pvpeffects.admin` - Grants access to `/pvpeffects` admin commands. Default: `op`.

---

## Configuration (`config.yml`)

```yaml
# ==========================================
#             PvPEffects Config
# ==========================================
# Effect names use current Minecraft names (speed, strength, haste, ...).
# Old names (INCREASE_DAMAGE, FAST_DIGGING, ...) are still accepted and auto-converted.

# Maximum number of permanent positive effects a player can hold simultaneously.
# Set to 0 for unlimited.
max-positive-effects: 3

# Maximum number of permanent negative effects a player can hold simultaneously.
# Set to 0 for unlimited.
max-negative-effects: 3

# Maximum times a player can earn effects from killing the same victim.
# 1 = unique kills only. 0 = unlimited.
max-kills-per-victim: 1

# Pool of positive permanent effects awarded on kills or bought via /buyeffect
positive-effects:
  - "speed"
  - "strength"
  - "regeneration"
  - "haste"
  - "resistance"
  - "fire_resistance"
  - "night_vision"

# Pool of negative permanent effects applied on PvP death or temporarily on non-PvP death
negative-effects:
  - "slowness"
  - "weakness"
  - "poison"
  - "mining_fatigue"
  - "blindness"

# Deaths caused by environment, mobs, or suicide
natural-death:
  duration-seconds: 300

# Economy settings (requires Vault + an economy plugin)
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
    min-cost: 25000.0   # Minimum cost floor
  cure-all:
    percentage: 30.0    # Takes 30% of player's balance
    min-cost: 75000.0   # Minimum cost floor
