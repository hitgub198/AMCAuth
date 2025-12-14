package net.silverflag.aMCAuth;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.CommandExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class AMCAuth extends JavaPlugin implements Listener, CommandExecutor {

    private File passwordFile;
    private final Gson gson = new Gson();

    private Map<String, PasswordData> users = new HashMap<>();
    private Map<String, Boolean> loggedIn = new ConcurrentHashMap<>();
    private Map<String, Location> storedLocations = new ConcurrentHashMap<>();
    private Map<String, Long> loginTimestamp = new ConcurrentHashMap<>();

    // Join-rate limiter
    private final AtomicInteger joinCounter = new AtomicInteger(0);
    private long lastSecond = System.currentTimeMillis() / 1000;


    @Override
    public void onEnable() {
        getLogger().info("AMCAuth enabled");

        String home = System.getProperty("user.home");
        passwordFile = new File(home + "/anarchymc/amcauth_users.json");

        try {
            if (!passwordFile.exists()) {
                passwordFile.getParentFile().mkdirs();
                passwordFile.createNewFile();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        loadUsers();

        getServer().getPluginManager().registerEvents(this, this);

        Objects.requireNonNull(getCommand("login")).setExecutor(this);
        Objects.requireNonNull(getCommand("register")).setExecutor(this);

        // Timer to kick idle players
        Bukkit.getScheduler().runTaskTimer(this, this::checkLoginTimeouts, 20, 20);
    }


    // JSON LOAD / SAVE
    private void loadUsers() {
        try {
            if (!passwordFile.exists()) return;

            String json = Files.readString(passwordFile.toPath());
            if (json.isBlank()) return;

            Type type = new TypeToken<Map<String, PasswordData>>() {}.getType();
            Map<String, PasswordData> loaded = gson.fromJson(json, type);

            if (loaded != null) users = loaded;

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void saveUsers() {
        try (FileWriter writer = new FileWriter(passwordFile)) {
            gson.toJson(users, writer);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    // JOIN RATE LIMIT (3 per sec)
    private boolean allowJoin() {
        long currentSec = System.currentTimeMillis() / 1000;
        if (currentSec != lastSecond) {
            lastSecond = currentSec;
            joinCounter.set(0);
        }
        return joinCounter.incrementAndGet() <= 3;
    }


    // PLAYER JOIN
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        String name = p.getName().toLowerCase();

        // JOIN LIMITER
        if (!allowJoin()) {
            p.kickPlayer("§cToo many players joining at once. Try again.");
            return;
        }

        // Prevent username stealing
        Player existing = Bukkit.getPlayerExact(p.getName());
        if (existing != null && existing != p) {
            // If the existing player is NOT logged in → kick THEM.
            if (!loggedIn.getOrDefault(name, false)) {
                existing.kickPlayer("§cYou took too long to login.");
            } else {
                // If existing is logged in → kick the NEW connection
                p.kickPlayer("§cThat username is already online.");
                return;
            }
        }

        // Mark as not logged in
        loggedIn.put(name, false);

        // Save their real location (to return after login)
        storedLocations.put(name, p.getLocation().clone());

        // Start login timeout
        loginTimestamp.put(name, System.currentTimeMillis());

        // Move to auth island
        p.teleport(new Location(p.getWorld(), 0, 1000, 0));

        // Disable movement damage etc.
        p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, Integer.MAX_VALUE, 5));

        if (users.containsKey(name)) {
            p.sendMessage("§ePlease login: §f/login <password>");
        } else {
            p.sendMessage("§eRegister: §f/register <password>");
        }
    }


    // AUTO-KICK IF NOT LOGGED IN AFTER 60s
    private void checkLoginTimeouts() {
        long now = System.currentTimeMillis();

        for (Player p : Bukkit.getOnlinePlayers()) {
            String name = p.getName().toLowerCase();

            if (loggedIn.getOrDefault(name, false)) continue;

            long since = now - loginTimestamp.getOrDefault(name, 0L);

            if (since > 60_000) {
                p.kickPlayer("§cYou took too long to login.");
            }
        }
    }


    // BLOCK DAMAGE BEFORE LOGIN
    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) return;

        Player p = (Player) event.getEntity();
        String name = p.getName().toLowerCase();

        if (!loggedIn.getOrDefault(name, false))
            event.setCancelled(true);
    }


    // BLOCK MOVEMENT BEFORE LOGIN
    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player p = event.getPlayer();
        String name = p.getName().toLowerCase();

        if (!loggedIn.getOrDefault(name, false)) {
            if (!event.getFrom().toVector().equals(event.getTo().toVector())) {
                event.setTo(event.getFrom());
            }
        }
    }


    // BLOCK COMMANDS BEFORE LOGIN
    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player p = event.getPlayer();
        String name = p.getName().toLowerCase();

        if (loggedIn.getOrDefault(name, false))
            return;

        String msg = event.getMessage().toLowerCase();

        if (msg.startsWith("/login") || msg.startsWith("/register"))
            return;

        event.setCancelled(true);
        p.sendMessage("§cLogin first: §f/login <password>");
    }


    // SAVE LOCATION ONLY IF LOGGED IN
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        String name = p.getName().toLowerCase();

        if (loggedIn.getOrDefault(name, false)) {
            storedLocations.put(name, p.getLocation().clone());
        }
    }


    // COMMAND HANDLER
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {

        if (!(sender instanceof Player)) return true;
        Player p = (Player) sender;
        String name = p.getName().toLowerCase();

        // REGISTER
        if (cmd.getName().equalsIgnoreCase("register")) {

            if (users.containsKey(name)) {
                p.sendMessage("§cAlready registered. Use /login.");
                return true;
            }

            if (args.length != 1) {
                p.sendMessage("§cUsage: /register <password>");
                return true;
            }

            users.put(name, new PasswordData(p.getName(), args[0]));
            saveUsers();

            loggedIn.put(name, true);
            loginTimestamp.remove(name);

            Location loc = storedLocations.get(name);
            if (loc != null) p.teleport(loc);

            p.removePotionEffect(PotionEffectType.RESISTANCE);
            p.sendMessage("§aRegistration complete!");
            return true;
        }

        // LOGIN
        if (cmd.getName().equalsIgnoreCase("login")) {

            if (!users.containsKey(name)) {
                p.sendMessage("§cNot registered. Use /register.");
                return true;
            }

            if (args.length != 1) {
                p.sendMessage("§cUsage: /login <password>");
                return true;
            }

            if (users.get(name).password.equals(args[0])) {

                loggedIn.put(name, true);
                loginTimestamp.remove(name);

                Location loc = storedLocations.get(name);
                if (loc != null) p.teleport(loc);

                p.removePotionEffect(PotionEffectType.RESISTANCE);
                p.sendMessage("§aLogin successful!");

            } else {
                p.sendMessage("§cIncorrect password!");
            }

            return true;
        }

        return false;
    }


    // PASSWORD DATA CLASS
    private static class PasswordData {
        String name;
        String password;

        public PasswordData() {}
        public PasswordData(String name, String password) {
            this.name = name;
            this.password = password;
        }
    }
}
