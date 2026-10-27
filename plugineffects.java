package com.example.pvpeffects;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.util.*;

public final class PvpEffectsPlugin extends JavaPlugin implements Listener, CommandExecutor {

    private Economy econ = null;
    private File dataFile;
    private FileConfiguration dataConfig;

    private final List<PotionEffectType> positivePool = new ArrayList<>();
    private final List<PotionEffectType> negativePool = new ArrayList<>();

    // Dynamic state trackers
    private final Map<UUID, Set<String>> permPositives = new HashMap<>();
    private final Map<UUID, Set<String>> permNegatives = new HashMap<>();
    private final Set<UUID> pendingTempDebuff = new HashSet<>();
    private final Random random = new Random();

    @Override
    public void onEnable() {
        // Save default config.yml if it doesn't exist
        saveDefaultConfig();
        loadConfigValues();

        if (!setupEconomy()) {
            getLogger().severe("Vault economy dependency not found! Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        loadDataFile();
        getServer().getPluginManager().registerEvents(this, this);

        Objects.requireNonNull(getCommand("buyeffect")).setExecutor(this);
        Objects.requireNonNull(getCommand("cureone")).setExecutor(this);
        Objects.requireNonNull(getCommand("cureall")).setExecutor(this);

        getLogger().info("PvPEffects v2.0 enabled with config support!");
    }

    @Override
    public void onDisable() {
        saveDataFile();
    }

    private void loadConfigValues() {
        positivePool.clear();
        negativePool.clear();

        for (String effectName : getConfig().getStringList("positive-effects")) {
            PotionEffectType type = PotionEffectType.getByName(effectName.toUpperCase());
            if (type != null) {
                positivePool.add(type);
            } else {
                getLogger().warning("Invalid positive effect in config.yml: " + effectName);
            }
        }

        for (String effectName : getConfig().getStringList("negative-effects")) {
            PotionEffectType type = PotionEffectType.getByName(effectName.toUpperCase());
            if (type != null) {
                negativePool.add(type);
            } else {
                getLogger().warning("Invalid negative effect in config.yml: " + effectName);
            }
        }
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        econ = rsp.getProvider();
        return econ != null;
    }

    // --- Death & Respawn Listeners ---
    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();

        if (killer != null && !killer.equals(victim)) {
            // Player vs Player Death
            PotionEffectType reward = getRandomUnownedEffect(killer, positivePool, getPermSet(killer, permPositives));
            if (reward != null) {
                addPermEffect(killer, permPositives, reward);
                killer.sendMessage("§a[PvP] You killed " + victim.getName() + " and gained permanent " + formatName(reward) + "!");
            }

            PotionEffectType penalty = getRandomUnownedEffect(victim, negativePool, getPermSet(victim, permNegatives));
            if (penalty != null) {
                addPermEffect(victim, permNegatives, penalty);
                victim.sendMessage("§c[PvP] You were slain by " + killer.getName() + " and received permanent " + formatName(penalty) + "!");
            }
        } else {
            // Natural / Environmental Death
            pendingTempDebuff.add(victim.getUniqueId());
            victim.sendMessage("§e[PvP] You died of non-PvP causes. You will receive a temporary debuff on respawn.");
        }
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        Bukkit.getScheduler().runTaskLater(this, () -> {
            applyPermanentEffects(player);

            if (pendingTempDebuff.remove(uuid) && !negativePool.isEmpty()) {
                PotionEffectType tempEffect = negativePool.get(random.nextInt(negativePool.size()));
                int durationTicks = getConfig().getInt("natural-death.duration-seconds", 300) * 20;
                player.addPotionEffect(new PotionEffect(tempEffect, durationTicks, 0));
                player.sendMessage("§c[PvP] Temporary penalty applied: " + formatName(tempEffect) + " for " + (durationTicks / 20) + " seconds.");
            }
        }, 2L);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        loadPlayerData(event.getPlayer().getUniqueId());
        applyPermanentEffects(event.getPlayer());
    }

    // --- Commands ---
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can execute this command.");
            return true;
        }

        double balance = econ.getBalance(player);

        if (command.getName().equalsIgnoreCase("buyeffect")) {
            double pct = getConfig().getDouble("pricing.buy-effect.percentage", 20.0) / 100.0;
            double minCost = getConfig().getDouble("pricing.buy-effect.min-cost", 50000.0);
            double cost = Math.max(minCost, balance * pct);

            if (balance < cost) {
                player.sendMessage("§cYou need §e$" + String.format("%.2f", cost) + " §cto buy a permanent positive effect.");
                return true;
            }

            PotionEffectType newEffect = getRandomUnownedEffect(player, positivePool, getPermSet(player, permPositives));
            if (newEffect == null) {
                player.sendMessage("§eYou already possess all available positive effects!");
                return true;
            }

            econ.withdrawPlayer(player, cost);
            addPermEffect(player, permPositives, newEffect);
            applyPermanentEffects(player);
            player.sendMessage("§a[PvP] Purchased permanent " + formatName(newEffect) + " for $" + String.format("%.2f", cost) + "!");
            return true;
        }

        if (command.getName().equalsIgnoreCase("cureone")) {
            double pct = getConfig().getDouble("pricing.cure-one.percentage", 10.0) / 100.0;
            double minCost = getConfig().getDouble("pricing.cure-one.min-cost", 25000.0);
            double cost = Math.max(minCost, balance * pct);
            Set<String> negs = getPermSet(player, permNegatives);

            if (negs.isEmpty()) {
                player.sendMessage("§aYou have no permanent negative effects to cure.");
                return true;
            }
            if (balance < cost) {
                player.sendMessage("§cYou need §e$" + String.format("%.2f", cost) + " §cto cure one negative effect.");
                return true;
            }

            econ.withdrawPlayer(player, cost);
            String removedName = negs.iterator().next();
            negs.remove(removedName);

            PotionEffectType type = PotionEffectType.getByName(removedName);
            if (type != null) player.removePotionEffect(type);

            savePlayerData(player.getUniqueId());
            player.sendMessage("§a[PvP] Cured " + formatName(type) + " for $" + String.format("%.2f", cost) + "!");
            return true;
        }

        if (command.getName().equalsIgnoreCase("cureall")) {
            double pct = getConfig().getDouble("pricing.cure-all.percentage", 30.0) / 100.0;
            double minCost = getConfig().getDouble("pricing.cure-all.min-cost", 75000.0);
            double cost = Math.max(minCost, balance * pct);
            Set<String> negs = getPermSet(player, permNegatives);

            if (negs.isEmpty()) {
                player.sendMessage("§aYou have no permanent negative effects to cure.");
                return true;
            }
            if (balance < cost) {
                player.sendMessage("§cYou need §e$" + String.format("%.2f", cost) + " §cto cure all negative effects.");
                return true;
            }

            econ.withdrawPlayer(player, cost);
            for (String effectName : new HashSet<>(negs)) {
                PotionEffectType type = PotionEffectType.getByName(effectName);
                if (type != null) player.removePotionEffect(type);
            }
            negs.clear();

            savePlayerData(player.getUniqueId());
            player.sendMessage("§a[PvP] Cleared all permanent debuffs for $" + String.format("%.2f", cost) + "!");
            return true;
        }

        return false;
    }

    // --- Helper Methods ---
    private Set<String> getPermSet(Player player, Map<UUID, Set<String>> map) {
        return map.computeIfAbsent(player.getUniqueId(), k -> new HashSet<>());
    }

    private void addPermEffect(Player player, Map<UUID, Set<String>> map, PotionEffectType type) {
        getPermSet(player, map).add(type.getName());
        savePlayerData(player.getUniqueId());
        applyPermanentEffects(player);
    }

    private PotionEffectType getRandomUnownedEffect(Player player, List<PotionEffectType> pool, Set<String> current) {
        List<PotionEffectType> unowned = new ArrayList<>();
        for (PotionEffectType type : pool) {
            if (!current.contains(type.getName())) {
                unowned.add(type);
            }
        }
        if (unowned.isEmpty()) return null;
        return unowned.get(random.nextInt(unowned.size()));
    }

    private void applyPermanentEffects(Player player) {
        UUID uuid = player.getUniqueId();
        Set<String> pos = permPositives.getOrDefault(uuid, Collections.emptySet());
        Set<String> neg = permNegatives.getOrDefault(uuid, Collections.emptySet());

        for (String name : pos) {
            PotionEffectType type = PotionEffectType.getByName(name);
            if (type != null) {
                player.addPotionEffect(new PotionEffect(type, PotionEffect.INFINITE_DURATION, 0, false, false));
            }
        }
        for (String name : neg) {
            PotionEffectType type = PotionEffectType.getByName(name);
            if (type != null) {
                player.addPotionEffect(new PotionEffect(type, PotionEffect.INFINITE_DURATION, 0, false, false));
            }
        }
    }

    // --- Persistence Management ---
    private void loadDataFile() {
        dataFile = new File(getDataFolder(), "players.yml");
        if (!dataFile.exists()) {
            getDataFolder().mkdirs();
            try {
                dataFile.createNewFile();
            } catch (IOException e) {
                getLogger().severe("Could not create players.yml!");
            }
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
    }

    private void loadPlayerData(UUID uuid) {
        List<String> pos = dataConfig.getStringList(uuid + ".positives");
        List<String> neg = dataConfig.getStringList(uuid + ".negatives");
        permPositives.put(uuid, new HashSet<>(pos));
        permNegatives.put(uuid, new HashSet<>(neg));
    }

    private void savePlayerData(UUID uuid) {
        dataConfig.set(uuid + ".positives", new ArrayList<>(permPositives.getOrDefault(uuid, Collections.emptySet())));
        dataConfig.set(uuid + ".negatives", new ArrayList<>(permNegatives.getOrDefault(uuid, Collections.emptySet())));
        try {
            dataConfig.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("Could not save player data to players.yml!");
        }
    }

    private void saveDataFile() {
        for (UUID uuid : permPositives.keySet()) {
            dataConfig.set(uuid + ".positives", new ArrayList<>(permPositives.get(uuid)));
        }
        for (UUID uuid : permNegatives.keySet()) {
            dataConfig.set(uuid + ".negatives", new ArrayList<>(permNegatives.get(uuid)));
        }
        try {
            dataConfig.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("Could not save players.yml!");
        }
    }

    private String formatName(PotionEffectType type) {
        if (type == null) return "Unknown";
        String name = type.getKey().getKey().replace('_', ' ');
        return name.substring(0, 1).toUpperCase() + name.substring(1);
    }
}
