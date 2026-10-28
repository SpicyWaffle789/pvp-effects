package com.example.pvpeffects;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public final class PvpEffectsPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    /** Pre-1.20.3 effect names -> current registry keys, so old configs / players.yml keep working. */
    private static final Map<String, String> LEGACY_NAMES = Map.ofEntries(
            Map.entry("increase_damage", "strength"),
            Map.entry("fast_digging", "haste"),
            Map.entry("damage_resistance", "resistance"),
            Map.entry("slow", "slowness"),
            Map.entry("slow_digging", "mining_fatigue"),
            Map.entry("jump", "jump_boost"),
            Map.entry("confusion", "nausea"),
            Map.entry("harm", "instant_damage"),
            Map.entry("heal", "instant_health")
    );

    private File dataFile;
    private FileConfiguration dataConfig;
    private boolean dirty = false;

    private final List<PotionEffectType> positivePool = new ArrayList<>();
    private final List<PotionEffectType> negativePool = new ArrayList<>();

    // Effects are stored by registry key (e.g. "strength"), never by deprecated legacy name.
    private final Map<UUID, Set<String>> permPositives = new HashMap<>();
    private final Map<UUID, Set<String>> permNegatives = new HashMap<>();
    private final Map<UUID, Map<String, Integer>> claimedKills = new HashMap<>();
    private final Set<UUID> pendingTempDebuff = new HashSet<>();
    private final Random random = new Random();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigValues();
        loadDataFile();

        getServer().getPluginManager().registerEvents(this, this);

        Objects.requireNonNull(getCommand("buyeffect")).setExecutor(this);
        Objects.requireNonNull(getCommand("cureone")).setExecutor(this);
        Objects.requireNonNull(getCommand("cureall")).setExecutor(this);
        Objects.requireNonNull(getCommand("pvpeffects")).setExecutor(this);
        Objects.requireNonNull(getCommand("pvpeffects")).setTabCompleter(this);

        // Players already online (e.g. after /reload)
        for (Player p : Bukkit.getOnlinePlayers()) {
            loadPlayerData(p.getUniqueId());
            applyPermanentEffects(p);
        }

        // Batch disk writes instead of rewriting players.yml on every change
        getServer().getScheduler().runTaskTimer(this, this::flushIfDirty, 600L, 600L);

        getLogger().info("PvPEffects enabled.");
    }

    @Override
    public void onDisable() {
        if (dataConfig == null || dataFile == null) return; // enable failed early

        Set<UUID> all = new HashSet<>();
        all.addAll(permPositives.keySet());
        all.addAll(permNegatives.keySet());
        all.addAll(claimedKills.keySet());
        for (UUID uuid : all) syncPlayer(uuid);
        writeFile();
    }

    // --- Effect lookup (registry based; getByName/getName are deprecated and break on legacy names) ---
    private PotionEffectType lookupEffect(String raw) {
        if (raw == null) return null;
        String key = raw.trim().toLowerCase(Locale.ROOT);
        key = LEGACY_NAMES.getOrDefault(key, key);
        NamespacedKey nk = NamespacedKey.fromString(key);
        return nk == null ? null : Registry.EFFECT.get(nk);
    }

    private String keyOf(PotionEffectType type) {
        return type.key().value();
    }

    private void loadConfigValues() {
        loadPool("positive-effects", positivePool);
        loadPool("negative-effects", negativePool);
    }

    private void loadPool(String path, List<PotionEffectType> pool) {
        pool.clear();
        for (String name : getConfig().getStringList(path)) {
            PotionEffectType type = lookupEffect(name);
            if (type == null) {
                getLogger().warning("Invalid effect '" + name + "' in " + path + " (config.yml)");
            } else if (!pool.contains(type)) {
                pool.add(type);
            }
        }
    }

    // Cap semantics: 0 = category disabled, -1 (or any negative) = unlimited, N > 0 = at most N.
    private int maxPositive() { return getConfig().getInt("max-positive-effects", 3); }
    private int maxNegative() { return getConfig().getInt("max-negative-effects", 3); }
    private boolean positivesEnabled() { return maxPositive() != 0; }
    private boolean negativesEnabled() { return maxNegative() != 0; }
    private boolean isFull(int max, int size) { return max > 0 && size >= max; }

    private Economy getEconomy() {
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        return rsp == null ? null : rsp.getProvider();
    }

    // --- Listeners ---
    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();

        if (killer == null || killer.equals(victim)) {
            if (!negativesEnabled()) return; // negative effects are disabled
            pendingTempDebuff.add(victim.getUniqueId());
            savePlayerData(victim.getUniqueId()); // persisted, so quitting on the death screen can't dodge it
            victim.sendMessage("§e[PvP] You died of non-PvP causes. You will receive a temporary debuff on respawn.");
            return;
        }

        rewardKiller(killer, victim);
        penaliseVictim(killer, victim);
    }

    private void rewardKiller(Player killer, Player victim) {
        UUID killerId = killer.getUniqueId();
        String victimId = victim.getUniqueId().toString();

        Map<String, Integer> counts = claimedKills.computeIfAbsent(killerId, k -> new HashMap<>());
        int current = counts.getOrDefault(victimId, 0);
        int maxKills = getConfig().getInt("max-kills-per-victim", 1);

        if (maxKills > 0 && current >= maxKills) {
            killer.sendMessage("§c[PvP] You have already reached the maximum limit (" + maxKills
                    + ") for getting effects from " + victim.getName() + "!");
            return;
        }

        if (!positivesEnabled()) return; // positive effects are disabled
        int maxPos = maxPositive();
        Set<String> positives = getPermSet(killerId, permPositives);
        if (isFull(maxPos, positives.size())) {
            killer.sendMessage("§e[PvP] You killed " + victim.getName()
                    + ", but you already hold the maximum allowed positive effects (" + maxPos + ")!");
            return;
        }

        PotionEffectType reward = getRandomUnownedEffect(positivePool, positives);
        if (reward == null) {
            killer.sendMessage("§e[PvP] You killed " + victim.getName()
                    + ", but you already own every positive effect in the pool!");
            return;
        }

        // Only consume a kill "slot" when a reward is actually granted
        counts.put(victimId, current + 1);
        addPermEffect(killer, permPositives, reward);
        killer.sendMessage("§a[PvP] You killed " + victim.getName() + " (" + (current + 1) + "/"
                + (maxKills > 0 ? String.valueOf(maxKills) : "∞") + ") and gained permanent " + formatName(reward) + "!");
    }

    private void penaliseVictim(Player killer, Player victim) {
        UUID victimId = victim.getUniqueId();
        Set<String> positives = getPermSet(victimId, permPositives);

        if (positivesEnabled() && !positives.isEmpty()) {
            List<String> list = new ArrayList<>(positives);
            String lost = list.get(random.nextInt(list.size()));
            positives.remove(lost);
            PotionEffectType lostType = lookupEffect(lost);
            if (lostType != null) {
                // No removePotionEffect needed: the player is dead and respawns with no effects;
                // applyPermanentEffects() on respawn only re-adds what is still in the set.
                victim.sendMessage("§c[PvP] You were slain by " + killer.getName() + " and lost permanent " + formatName(lostType) + "!");
            }
            savePlayerData(victimId);
            return;
        }

        if (!negativesEnabled()) return; // negative effects are disabled
        int maxNeg = maxNegative();
        Set<String> negatives = getPermSet(victimId, permNegatives);
        if (isFull(maxNeg, negatives.size())) {
            victim.sendMessage("§e[PvP] You were slain by " + killer.getName()
                    + ", but you already hold the maximum allowed negative effects (" + maxNeg + ")!");
            return;
        }
        PotionEffectType penalty = getRandomUnownedEffect(negativePool, negatives);
        if (penalty != null) {
            addPermEffect(victim, permNegatives, penalty);
            victim.sendMessage("§c[PvP] You were slain by " + killer.getName() + " and received permanent " + formatName(penalty) + "!");
        } else {
            victim.sendMessage("§e[PvP] You were slain by " + killer.getName()
                    + ", but you already have every negative effect in the pool!");
        }
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline()) return;
            applyPermanentEffects(player);

            boolean owed = pendingTempDebuff.remove(uuid);
            if (owed) savePlayerData(uuid);
            if (owed && negativesEnabled() && !negativePool.isEmpty()) {
                PotionEffectType temp = negativePool.get(random.nextInt(negativePool.size()));
                int ticks = getConfig().getInt("natural-death.duration-seconds", 300) * 20;
                player.addPotionEffect(new PotionEffect(temp, ticks, 0));
                player.sendMessage("§c[PvP] Temporary penalty applied: " + formatName(temp) + " for " + (ticks / 20) + " seconds.");
            }
        }, 2L);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        loadPlayerData(event.getPlayer().getUniqueId());
        applyPermanentEffects(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        syncPlayer(uuid);
        flushIfDirty();
        permPositives.remove(uuid);
        permNegatives.remove(uuid);
        claimedKills.remove(uuid);
        pendingTempDebuff.remove(uuid);
    }

    /** Milk and Totem of Undying wipe every effect, including our infinite ones - put them back. */
    @EventHandler(ignoreCancelled = true)
    public void onPotionEffect(EntityPotionEffectEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        EntityPotionEffectEvent.Cause cause = event.getCause();
        if (cause != EntityPotionEffectEvent.Cause.MILK && cause != EntityPotionEffectEvent.Cause.TOTEM) return;

        EntityPotionEffectEvent.Action action = event.getAction();
        if (action != EntityPotionEffectEvent.Action.CLEARED && action != EntityPotionEffectEvent.Action.REMOVED) return;

        Bukkit.getScheduler().runTask(this, () -> {
            if (player.isOnline() && !player.isDead()) applyPermanentEffects(player);
        });
    }

    // --- Commands ---
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("pvpeffects")) {
            return handleAdminCommand(sender, args);
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can execute this command.");
            return true;
        }

        Economy econ = getEconomy();
        if (econ == null) {
            player.sendMessage("§cNo economy provider is available right now. Tell an administrator.");
            return true;
        }

        double balance = econ.getBalance(player);
        UUID uuid = player.getUniqueId();

        switch (name) {
            case "buyeffect" -> {
                if (!positivesEnabled()) {
                    player.sendMessage("§cPositive effects are disabled on this server.");
                    return true;
                }
                int maxPos = maxPositive();
                Set<String> positives = getPermSet(uuid, permPositives);
                if (isFull(maxPos, positives.size())) {
                    player.sendMessage("§cYou already have the maximum limit of " + maxPos + " permanent positive effects!");
                    return true;
                }
                double cost = computeCost("buy-effect", balance, 20.0, 50000.0);
                if (balance < cost) {
                    player.sendMessage("§cYou need §e$" + String.format("%.2f", cost) + " §cto buy a permanent positive effect.");
                    return true;
                }
                PotionEffectType effect = getRandomUnownedEffect(positivePool, positives);
                if (effect == null) {
                    player.sendMessage("§eYou already possess all available positive effects!");
                    return true;
                }
                if (!charge(econ, player, cost)) return true;
                addPermEffect(player, permPositives, effect);
                player.sendMessage("§a[PvP] Purchased permanent " + formatName(effect) + " for $" + String.format("%.2f", cost) + "!");
                return true;
            }
            case "cureone" -> {
                if (!negativesEnabled()) {
                    player.sendMessage("§cNegative effects are disabled on this server.");
                    return true;
                }
                Set<String> negatives = getPermSet(uuid, permNegatives);
                if (negatives.isEmpty()) {
                    player.sendMessage("§aYou have no permanent negative effects to cure.");
                    return true;
                }
                double cost = computeCost("cure-one", balance, 10.0, 25000.0);
                if (balance < cost) {
                    player.sendMessage("§cYou need §e$" + String.format("%.2f", cost) + " §cto cure one negative effect.");
                    return true;
                }
                if (!charge(econ, player, cost)) return true;
                String removed = negatives.iterator().next();
                negatives.remove(removed);
                PotionEffectType type = lookupEffect(removed);
                if (type != null) player.removePotionEffect(type);
                savePlayerData(uuid);
                player.sendMessage("§a[PvP] Cured " + formatName(type) + " for $" + String.format("%.2f", cost) + "!");
                return true;
            }
            case "cureall" -> {
                if (!negativesEnabled()) {
                    player.sendMessage("§cNegative effects are disabled on this server.");
                    return true;
                }
                Set<String> negatives = getPermSet(uuid, permNegatives);
                if (negatives.isEmpty()) {
                    player.sendMessage("§aYou have no permanent negative effects to cure.");
                    return true;
                }
                double cost = computeCost("cure-all", balance, 30.0, 75000.0);
                if (balance < cost) {
                    player.sendMessage("§cYou need §e$" + String.format("%.2f", cost) + " §cto cure all negative effects.");
                    return true;
                }
                if (!charge(econ, player, cost)) return true;
                for (String effectName : new HashSet<>(negatives)) {
                    PotionEffectType type = lookupEffect(effectName);
                    if (type != null) player.removePotionEffect(type);
                }
                negatives.clear();
                savePlayerData(uuid);
                player.sendMessage("§a[PvP] Cleared all permanent debuffs for $" + String.format("%.2f", cost) + "!");
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private double computeCost(String section, double balance, double defPct, double defMin) {
        double pct = getConfig().getDouble("pricing." + section + ".percentage", defPct) / 100.0;
        double min = getConfig().getDouble("pricing." + section + ".min-cost", defMin);
        return Math.max(min, balance * pct);
    }

    /** Withdraws and verifies the transaction actually succeeded. */
    private boolean charge(Economy econ, Player player, double cost) {
        EconomyResponse response = econ.withdrawPlayer(player, cost);
        if (!response.transactionSuccess()) {
            player.sendMessage("§cPayment failed: " + response.errorMessage);
            return false;
        }
        return true;
    }

    // --- Admin Commands ---
    private boolean handleAdminCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("pvpeffects.admin")) {
            sender.sendMessage("§cYou do not have permission to execute this command.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("§e--- PvPEffects Admin Commands ---");
            sender.sendMessage("§e/pvpeffects reload §7- Reload configuration");
            sender.sendMessage("§e/pvpeffects add <player> <positive|negative> <effect> §7- Add permanent effect");
            sender.sendMessage("§e/pvpeffects remove <player> <positive|negative> <effect> §7- Remove permanent effect");
            sender.sendMessage("§e/pvpeffects reset <player> §7- Reset permanent effects & kill counts");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("reload")) {
            reloadConfig();
            loadConfigValues();
            for (Player online : Bukkit.getOnlinePlayers()) applyPermanentEffects(online);
            sender.sendMessage("§a[PvP] Configuration reloaded successfully.");
            return true;
        }

        if (sub.equals("reset")) {
            if (args.length < 2) {
                sender.sendMessage("§cUsage: /pvpeffects reset <player>");
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage("§cPlayer not found (must be online).");
                return true;
            }

            UUID uuid = target.getUniqueId();
            for (String eff : permPositives.getOrDefault(uuid, Collections.emptySet())) removeEffectByKey(target, eff);
            for (String eff : permNegatives.getOrDefault(uuid, Collections.emptySet())) removeEffectByKey(target, eff);

            permPositives.put(uuid, new HashSet<>());
            permNegatives.put(uuid, new HashSet<>());
            claimedKills.put(uuid, new HashMap<>());
            savePlayerData(uuid);

            sender.sendMessage("§a[PvP] Reset all permanent effects and kill count history for " + target.getName() + ".");
            target.sendMessage("§e[PvP] Your permanent effects and unique kill history were reset by an administrator.");
            return true;
        }

        if (sub.equals("add") || sub.equals("remove")) {
            if (args.length < 4) {
                sender.sendMessage("§cUsage: /pvpeffects " + sub + " <player> <positive|negative> <effect>");
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage("§cPlayer not found (must be online).");
                return true;
            }

            PotionEffectType effectType = lookupEffect(args[3]);
            if (effectType == null) {
                sender.sendMessage("§cInvalid effect type: " + args[3]);
                return true;
            }

            String category = args[2].toLowerCase(Locale.ROOT);
            Map<UUID, Set<String>> targetMap;
            if (category.equals("positive")) targetMap = permPositives;
            else if (category.equals("negative")) targetMap = permNegatives;
            else {
                sender.sendMessage("§cEffect category must be either 'positive' or 'negative'.");
                return true;
            }

            UUID uuid = target.getUniqueId();
            Set<String> set = getPermSet(uuid, targetMap);

            if (sub.equals("add")) {
                boolean enabled = category.equals("positive") ? positivesEnabled() : negativesEnabled();
                if (!enabled) {
                    sender.sendMessage("§c[PvP] " + category + " effects are disabled in config.yml (max-" + category + "-effects: 0).");
                    return true;
                }
                set.add(keyOf(effectType));
                applyPermanentEffects(target);
                sender.sendMessage("§a[PvP] Added permanent " + formatName(effectType) + " to " + target.getName() + ".");
            } else {
                if (!set.remove(keyOf(effectType))) {
                    sender.sendMessage("§e[PvP] " + target.getName() + " does not have permanent " + formatName(effectType) + ".");
                    return true;
                }
                target.removePotionEffect(effectType);
                sender.sendMessage("§a[PvP] Removed permanent " + formatName(effectType) + " from " + target.getName() + ".");
            }
            savePlayerData(uuid);
            return true;
        }

        sender.sendMessage("§cUnknown subcommand. Type /pvpeffects for help.");
        return true;
    }

    // --- Tab Completer ---
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("pvpeffects") || !sender.hasPermission("pvpeffects.admin")) {
            return Collections.emptyList();
        }

        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        boolean addRemove = sub.equals("add") || sub.equals("remove");

        if (args.length == 1) {
            return Stream4.of("reload", "add", "remove", "reset", args[0]);
        }
        if (args.length == 2 && (addRemove || sub.equals("reset"))) {
            return null; // default: online player names
        }
        if (args.length == 3 && addRemove) {
            return Stream4.of("positive", "negative", args[2]);
        }
        if (args.length == 4 && addRemove) {
            String prefix = args[3].toLowerCase(Locale.ROOT);
            List<String> names;
            Player target = Bukkit.getPlayerExact(args[1]);
            if (sub.equals("remove") && target != null) {
                Map<UUID, Set<String>> map = args[2].equalsIgnoreCase("positive") ? permPositives : permNegatives;
                names = new ArrayList<>(map.getOrDefault(target.getUniqueId(), Collections.emptySet()));
            } else {
                List<PotionEffectType> source = args[2].equalsIgnoreCase("positive") ? positivePool : negativePool;
                names = source.stream().map(this::keyOf).collect(Collectors.toList());
            }
            return names.stream().filter(s -> s.startsWith(prefix)).collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    /** Tiny prefix-filter helper for tab completion. */
    private static final class Stream4 {
        static List<String> of(String... valuesThenPrefix) {
            String prefix = valuesThenPrefix[valuesThenPrefix.length - 1].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (int i = 0; i < valuesThenPrefix.length - 1; i++) {
                if (valuesThenPrefix[i].startsWith(prefix)) out.add(valuesThenPrefix[i]);
            }
            return out;
        }
    }

    // --- Helpers ---
    private Set<String> getPermSet(UUID uuid, Map<UUID, Set<String>> map) {
        return map.computeIfAbsent(uuid, k -> new HashSet<>());
    }

    private void addPermEffect(Player player, Map<UUID, Set<String>> map, PotionEffectType type) {
        getPermSet(player.getUniqueId(), map).add(keyOf(type));
        savePlayerData(player.getUniqueId());
        applyPermanentEffects(player);
    }

    private PotionEffectType getRandomUnownedEffect(List<PotionEffectType> pool, Set<String> current) {
        List<PotionEffectType> unowned = new ArrayList<>();
        for (PotionEffectType type : pool) {
            if (!current.contains(keyOf(type))) unowned.add(type);
        }
        return unowned.isEmpty() ? null : unowned.get(random.nextInt(unowned.size()));
    }

    private void removeEffectByKey(Player player, String key) {
        PotionEffectType type = lookupEffect(key);
        if (type != null) player.removePotionEffect(type);
    }

    private void applyPermanentEffects(Player player) {
        UUID uuid = player.getUniqueId();
        boolean pos = positivesEnabled();
        boolean neg = negativesEnabled();
        // A disabled category is inactive: its stored effects are kept in players.yml but not applied.
        for (String name : permPositives.getOrDefault(uuid, Collections.emptySet())) {
            if (pos) applyInfinite(player, name); else removeEffectByKey(player, name);
        }
        for (String name : permNegatives.getOrDefault(uuid, Collections.emptySet())) {
            if (neg) applyInfinite(player, name); else removeEffectByKey(player, name);
        }
    }

    private void applyInfinite(Player player, String name) {
        PotionEffectType type = lookupEffect(name);
        if (type != null) {
            player.addPotionEffect(new PotionEffect(type, PotionEffect.INFINITE_DURATION, 0, false, false));
        }
    }

    // --- Persistence ---
    private void loadDataFile() {
        dataFile = new File(getDataFolder(), "players.yml");
        if (!dataFile.exists()) {
            getDataFolder().mkdirs();
            try {
                dataFile.createNewFile();
            } catch (IOException e) {
                getLogger().severe("Could not create players.yml: " + e.getMessage());
            }
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
    }

    private Set<String> normalise(List<String> raw) {
        Set<String> out = new HashSet<>();
        for (String s : raw) {
            PotionEffectType type = lookupEffect(s);
            if (type != null) out.add(keyOf(type));
            else getLogger().warning("Dropping unknown effect '" + s + "' from players.yml");
        }
        return out;
    }

    private void loadPlayerData(UUID uuid) {
        permPositives.put(uuid, normalise(dataConfig.getStringList(uuid + ".positives")));
        permNegatives.put(uuid, normalise(dataConfig.getStringList(uuid + ".negatives")));

        Map<String, Integer> kills = new HashMap<>();
        ConfigurationSection sec = dataConfig.getConfigurationSection(uuid + ".claimed-kills");
        if (sec != null) {
            for (String victimKey : sec.getKeys(false)) kills.put(victimKey, sec.getInt(victimKey));
        }
        claimedKills.put(uuid, kills);

        if (dataConfig.getBoolean(uuid + ".pending-debuff", false)) pendingTempDebuff.add(uuid);
        else pendingTempDebuff.remove(uuid);
    }

    /** Copies one player's in-memory state into the YAML tree and marks it for writing. */
    private void syncPlayer(UUID uuid) {
        dataConfig.set(uuid + ".positives", new ArrayList<>(permPositives.getOrDefault(uuid, Collections.emptySet())));
        dataConfig.set(uuid + ".negatives", new ArrayList<>(permNegatives.getOrDefault(uuid, Collections.emptySet())));

        dataConfig.set(uuid + ".pending-debuff", pendingTempDebuff.contains(uuid) ? Boolean.TRUE : null);

        dataConfig.set(uuid + ".claimed-kills", null);
        for (Map.Entry<String, Integer> e : claimedKills.getOrDefault(uuid, Collections.emptyMap()).entrySet()) {
            dataConfig.set(uuid + ".claimed-kills." + e.getKey(), e.getValue());
        }
        dirty = true;
    }

    private void savePlayerData(UUID uuid) {
        syncPlayer(uuid); // actual disk write happens in flushIfDirty()
    }

    private void flushIfDirty() {
        if (dirty) writeFile();
    }

    private void writeFile() {
        try {
            dataConfig.save(dataFile);
            dirty = false;
        } catch (IOException e) {
            getLogger().severe("Could not save players.yml: " + e.getMessage());
        }
    }

    private String formatName(PotionEffectType type) {
        if (type == null) return "Unknown";
        String name = keyOf(type).replace('_', ' ');
        return name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
    }
}
