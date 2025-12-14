package net.silverflag.aMCLobbyCore;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.CommandExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;

public class AMCLobbyCore extends JavaPlugin implements Listener, CommandExecutor {

    private File mainServerWorld;
    private File mainServerJson;

    private final Gson gson = new Gson();
    private Map<String, PasswordData> users = new HashMap<>();

    @Override
    public void onEnable() {
        getLogger().info("LobbyAuth enabled");

        // Use config values or fallback to safe defaults
        mainServerWorld = new File(getConfig().getString("main-world-path", "main_server/world"));
        // Save JSON file inside plugin folder to avoid permission issues
        mainServerJson = new File(getDataFolder(), "amcauth_users.json");

        // Ensure plugin folder exists
        if (!getDataFolder().exists()) getDataFolder().mkdirs();

        ensureJsonFileExists();
        loadUsers();

        getServer().getPluginManager().registerEvents(this, this);
        if (this.getCommand("setpassword") != null) {
            this.getCommand("setpassword").setExecutor(this);
        }
    }

    private void ensureJsonFileExists() {
        try {
            File parent = mainServerJson.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();

            if (!mainServerJson.exists()) mainServerJson.createNewFile();
        } catch (Exception e) {
            e.printStackTrace();
            getLogger().warning("Failed to create JSON file. Check folder permissions!");
        }
    }

    private void loadUsers() {
        try {
            if (!mainServerJson.exists()) return;

            String content = Files.readString(mainServerJson.toPath());
            if (content.isBlank()) return;

            Type type = new TypeToken<Map<String, PasswordData>>(){}.getType();
            Map<String, PasswordData> loaded = gson.fromJson(content, type);
            if (loaded != null) users = loaded;

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void saveUsers() {
        try (FileWriter writer = new FileWriter(mainServerJson)) {
            gson.toJson(users, writer);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        player.sendMessage("§aWelcome! Use §e/setpassword <password> <confirmPassword>§a to set your main server password.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) return true;

        Player player = (Player) sender;
        String lowercase = player.getName().toLowerCase();

        if (args.length != 2) {
            player.sendMessage("§cUsage: /setpassword <password> <confirmPassword>");
            return true;
        }

        if (!args[0].equals(args[1])) {
            player.sendMessage("§cPasswords do not match!");
            return true;
        }

        PasswordData pd = users.getOrDefault(lowercase, new PasswordData(player.getName(), args[0]));
        pd.password = args[0];

        users.put(lowercase, pd);
        saveUsers();

        player.sendMessage("§aPassword saved!");
        migratePlayerdata(player, pd);
        return true;
    }

    private void migratePlayerdata(Player player, PasswordData pd) {
        try {
            String lowercase = player.getName().toLowerCase();
            boolean forceMigrate = lowercase.equals("y4md");

            if (pd.migrated && !forceMigrate) {
                getLogger().info("Migration skipped for " + player.getName() + " (already migrated)");
                return;
            }

            UUID onlineUUID = player.getUniqueId();
            UUID offlineUUID = UUID.nameUUIDFromBytes(("OfflinePlayer:" + player.getName()).getBytes());

            File playerdataFolder = new File(mainServerWorld, "playerdata");
            if (!playerdataFolder.exists()) playerdataFolder.mkdirs();

            File premiumFile = new File(playerdataFolder, onlineUUID + ".dat");
            File offlineFile = new File(playerdataFolder, offlineUUID + ".dat");

            if (premiumFile.exists()) {
                Files.copy(premiumFile.toPath(), offlineFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                getLogger().info("Migrated premium → offline UUID for " + player.getName());
            }

            if (!forceMigrate) {
                pd.migrated = true;
                users.put(lowercase, pd);
                saveUsers();
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static class PasswordData {
        String name;
        String password;
        boolean migrated = false;

        public PasswordData() {}
        public PasswordData(String name, String password) {
            this.name = name;
            this.password = password;
        }
    }
}
