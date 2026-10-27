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

    private final List<PotionEffectType> POSITIVE_POOL = Arrays.asList(
            PotionEffectType.SPEED,
            PotionEffectType.REGENERATION,
            PotionEffectType.STRENGTH,
            PotionEffectType.HASTE,
            PotionEffectType.RESISTANCE,
            PotionEffectType.FIRE_RESISTANCE,
            PotionEffectType.SATURATION
    );

    private final List<PotionEffectType> NEGATIVE_POOL = Arrays.asList(
            PotionEffectType.SLOWNESS,
            PotionEffectType.WEAKNESS,
            PotionEffectType.MINING_FATIGUE,
            PotionEffectType.BLINDNESS,
            PotionEffectType.HUNGER,
            PotionEffectType.NAUSEA
    );

    // Dynamic state trackers
    private final Map<UUID, Set<String>> permPositives = new HashMap<>();
    private final Map<UUID, Set<String>> permNegatives = new HashMap<>();
    private final Set<UUID> pendingTempDebuff = new HashSet<>();
    private final Random random = new Random();

    @Override
    public void onEnable() {
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

        getLogger().info("PvPEffects v2.0 enabled successfully!");
    }

    @Override
    public void onDisable() {
        saveDataFile();
    }

    // --- Vault Integration ---
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
            PotionEffectType reward = getRandomUnownedEffect(killer, POSITIVE_POOL, getPermSet(killer, permPositives));
            if (reward != null) {
                addPermEffect(killer, permPositives, reward);
                killer.sendMessage("§a[PvP] You killed " + victim.getName() + " and gained permanent " + formatName(reward) + "!");
            }

            PotionEffectType penalty = getRandomUnownedEffect(victim, NEGATIVE_POOL, getPermSet(victim, permNegatives));
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

            if (pendingTempDebuff.remove(uuid)) {
                PotionEffectType tempEffect = NEGATIVE_POOL.get(random.nextInt(NEGATIVE_POOL.size()));
                player.addPotionEffect(new PotionEffect(tempEffect, 20 * 300, 0)); // 5 minutes
                player.sendMessage("§c[PvP] Temporary penalty applied: " + formatName(tempEffect) + " for 5 minutes.");
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
            double cost = Math.max(50000.0, balance * 0.20);
            if (balance < cost) {
                player.sendMessage("§cYou need §e$" + String.format("%.2f", cost) + " §cto buy a permanent positive effect.");
                return true;
            }

            PotionEffectType newEffect = getRandomUnownedEffect(player, POSITIVE_POOL, getPermSet(player, permPositives));
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
            double cost = Math.max(25000.0, balance * 0.10);
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
            double cost = Math.max(75000.0, balance * 0.30);
            Set<String> negs = getPermSet(player, permNegatives);

            if (negs.isEmpty()) {
                player.sendMessage("§aYou have no permanent negative effects to cure.");
                return true;
            }
            if (balance < cost) {
                player.sendMessage("§
