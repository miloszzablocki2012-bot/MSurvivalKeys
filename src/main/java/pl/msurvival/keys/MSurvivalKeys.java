package pl.msurvival.keys;

import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

@SuppressWarnings("deprecation")
public final class MSurvivalKeys extends JavaPlugin implements Listener {
    private NamespacedKey keyKey;
    private NamespacedKey actionKey;
    private File dataFile;
    private YamlConfiguration data;
    private final Random random = new Random();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadData();
        keyKey = new NamespacedKey(this, "key_type");
        actionKey = new NamespacedKey(this, "action");
        Bukkit.getPluginManager().registerEvents(this, this);
        commands();
    }

    private void commands() {
        getCommand("keysmenu").setExecutor((s, c, l, a) -> {
            if (s instanceof Player p) openKeysMenu(p);
            return true;
        });

        getCommand("kits").setExecutor((s, c, l, a) -> {
            if (s instanceof Player p) openKitsMenu(p);
            return true;
        });

        getCommand("kit").setExecutor((s, c, l, a) -> {
            if (s instanceof Player p && a.length >= 1) openKit(p, normalize(a[0]));
            return true;
        });

        getCommand("key").setExecutor((s, c, l, a) -> {
            if (s instanceof Player p && a.length >= 2 && a[0].equalsIgnoreCase("use")) openKitByKey(p, normalize(a[1]));
            return true;
        });

        getCommand("keyadmin").setExecutor((s, c, l, a) -> {
            if (!s.hasPermission("msurvivalkeys.admin")) {
                s.sendMessage(msg("no-permission"));
                return true;
            }

            if (a.length >= 1 && a[0].equalsIgnoreCase("reload")) {
                reloadConfig();
                s.sendMessage(msg("reload"));
                return true;
            }

            if (a.length < 3) {
                s.sendMessage(color("&c/keyadmin <give|item|reset> <gracz> <klucz> [ilosc]"));
                return true;
            }

            String mode = a[0].toLowerCase(Locale.ROOT);
            String player = a[1];
            String key = normalize(a[2]);
            int amount = a.length >= 4 ? parseInt(a[3]) : 1;

            if (mode.equals("reset")) {
                data.set(path(player) + ".lastWeekly", 0L);
                saveData();
                return true;
            }

            if (!getConfig().contains("keys." + key)) {
                s.sendMessage(color("&cNie ma takiego klucza."));
                return true;
            }

            if (mode.equals("give")) {
                setKeys(player, key, getKeys(player, key) + amount);
                s.sendMessage(msg("admin-give").replace("%key%", display(key)).replace("%player%", player));
                return true;
            }

            if (mode.equals("item")) {
                Player target = Bukkit.getPlayerExact(player);
                if (target == null) {
                    s.sendMessage(color("&cGracz jest offline."));
                    return true;
                }
                target.getInventory().addItem(keyItem(key, amount));
                s.sendMessage(msg("admin-item").replace("%key%", display(key)).replace("%player%", player));
                return true;
            }

            return true;
        });
    }

    @EventHandler
    public void onKeyRightClick(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        String key = keyFromItem(event.getItem());
        if (key == null) return;

        event.setCancelled(true);
        openKitByKey(event.getPlayer(), key);
    }

    @EventHandler
    public void onGuiClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return;

        String title = event.getView().getTitle();
        boolean keysGui = title.equals(color(getConfig().getString("gui.keys-title", "&6&lTWOJE KLUCZE")));
        boolean kitsGui = title.equals(color(getConfig().getString("gui.kits-title", "&b&lKITY ZA KLUCZE")));

        if (!keysGui && !kitsGui) return;

        event.setCancelled(true);

        ItemStack item = event.getCurrentItem();
        if (item == null || !item.hasItemMeta()) return;

        String action = item.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (action == null) return;

        p.closeInventory();

        if (action.equals("weekly")) {
            claimWeekly(p);
            return;
        }

        if (action.startsWith("withdraw:")) {
            withdrawKey(p, action.substring("withdraw:".length()));
            return;
        }

        if (action.startsWith("kit:")) {
            openKit(p, action.substring("kit:".length()));
        }
    }

    private void openKeysMenu(Player p) {
        Inventory inv = Bukkit.createInventory(null, 54, color(getConfig().getString("gui.keys-title", "&6&lTWOJE KLUCZE")));
        fill(inv);

        inv.setItem(getConfig().getInt("weekly.slot", 4), actionItem(
                parseMaterial(getConfig().getString("weekly.material", "CHEST")),
                getConfig().getString("weekly.name", "&a&lCotygodniowy klucz"),
                weeklyLore(p),
                "weekly"
        ));

        int slot = 10;
        ConfigurationSection section = getConfig().getConfigurationSection("keys");

        if (section != null) {
            for (String key : section.getKeys(false)) {
                if (slot >= 44) break;

                inv.setItem(slot, actionItem(Material.LIGHTNING_ROD, display(key), List.of(
                        "&7Wirtualne: &e" + getKeys(p.getName(), key),
                        "&7Fizyczne w EQ: &e" + countPhysical(p, key),
                        "",
                        "&eKliknij, aby wyjąć 1 klucz do EQ."
                ), "withdraw:" + key));

                slot++;
                if (slot == 17 || slot == 26 || slot == 35) slot += 2;
            }
        }

        p.openInventory(inv);
    }

    private void openKitsMenu(Player p) {
        Inventory inv = Bukkit.createInventory(null, 27, color(getConfig().getString("gui.kits-title", "&b&lKITY ZA KLUCZE")));
        fill(inv);

        ConfigurationSection section = getConfig().getConfigurationSection("kits");

        if (section != null) {
            for (String kit : section.getKeys(false)) {
                int slot = getConfig().getInt("kits." + kit + ".slot", 13);
                Material material = parseMaterial(getConfig().getString("kits." + kit + ".material", "CHEST"));
                String name = getConfig().getString("kits." + kit + ".name", kit);
                String required = getConfig().getString("kits." + kit + ".required-key", kit);

                inv.setItem(slot, actionItem(material, name, List.of(
                        "&7Wymagany klucz: " + display(required),
                        "&7Twoje klucze: &e" + totalKeys(p, required),
                        "",
                        "&eKliknij, aby otworzyć kit."
                ), "kit:" + kit));
            }
        }

        p.openInventory(inv);
    }

    private void claimWeekly(Player p) {
        long cooldown = getConfig().getLong("settings.cooldown-seconds", 604800L) * 1000L;
        long last = data.getLong(path(p.getName()) + ".lastWeekly", 0L);
        long left = cooldown - (System.currentTimeMillis() - last);

        if (last > 0 && left > 0) {
            p.sendMessage(msg("cooldown").replace("%time%", time(left)));
            return;
        }

        String key = roll("weekly-random");
        data.set(path(p.getName()) + ".lastWeekly", System.currentTimeMillis());
        saveData();

        setKeys(p.getName(), key, getKeys(p.getName(), key) + 1);
        p.sendMessage(msg("claimed").replace("%key%", display(key)));
    }

    private void withdrawKey(Player p, String key) {
        key = normalize(key);

        if (getKeys(p.getName(), key) <= 0) {
            p.sendMessage(msg("no-key").replace("%key%", display(key)));
            return;
        }

        setKeys(p.getName(), key, getKeys(p.getName(), key) - 1);
        p.getInventory().addItem(keyItem(key, 1));
        p.sendMessage(msg("withdrawn").replace("%key%", display(key)));
    }

    private void openKitByKey(Player p, String key) {
        key = normalize(key);

        ConfigurationSection kits = getConfig().getConfigurationSection("kits");
        if (kits == null) return;

        for (String kit : kits.getKeys(false)) {
            String required = normalize(getConfig().getString("kits." + kit + ".required-key", kit));
            if (required.equals(key)) {
                openKit(p, kit);
                return;
            }
        }

        p.sendMessage(color("&cNie ma kitu dla tego klucza."));
    }

    private void openKit(Player p, String kit) {
        kit = normalize(kit);

        if (isBlockedWorld(p)) {
            p.sendMessage(msg("blocked"));
            return;
        }

        if (!getConfig().contains("kits." + kit)) {
            p.sendMessage(color("&cNie ma takiego kitu."));
            return;
        }

        String required = normalize(getConfig().getString("kits." + kit + ".required-key", kit));

        if (!takeAnyKey(p, required)) {
            p.sendMessage(msg("no-key").replace("%key%", display(required)));
            return;
        }

        String kitName = color(getConfig().getString("kits." + kit + ".name", kit));
        p.sendTitle(color("&6&lOTWIERANIE KITU"), kitName, 5, 25, 5);
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1f);

        String finalKit = kit;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (p.isOnline()) giveKit(p, finalKit);
        }, 20L);
    }

    private void giveKit(Player p, String kit) {
        String rewardSet = kit;

        if (getConfig().contains("kits." + kit + ".random-rewards")) {
            rewardSet = roll("kits." + kit + ".random-rewards");
        }

        boolean owner = getConfig().getBoolean("kits." + kit + ".owner-set", false)
                || getConfig().getBoolean("reward-sets." + rewardSet + ".owner-set", false);

        if (owner) ownerSet(p);

        List<String> rewards = new ArrayList<>();
        rewards.addAll(getConfig().getStringList("kits." + kit + ".rewards"));
        rewards.addAll(getConfig().getStringList("reward-sets." + rewardSet + ".rewards"));

        for (String cmd : rewards) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", p.getName()));
        }

        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        p.sendMessage(msg("opened").replace("%kit%", rewardSet));
    }

    private boolean takeAnyKey(Player p, String key) {
        key = normalize(key);

        if (getKeys(p.getName(), key) > 0) {
            setKeys(p.getName(), key, getKeys(p.getName(), key) - 1);
            return true;
        }

        ItemStack[] contents = p.getInventory().getContents();

        for (int i = 0; i < contents.length; i++) {
            if (key.equals(keyFromItem(contents[i]))) {
                ItemStack item = contents[i];

                if (item.getAmount() <= 1) p.getInventory().setItem(i, null);
                else item.setAmount(item.getAmount() - 1);

                p.updateInventory();
                return true;
            }
        }

        return false;
    }

    private int totalKeys(Player p, String key) {
        return getKeys(p.getName(), key) + countPhysical(p, key);
    }

    private int countPhysical(Player p, String key) {
        int count = 0;

        for (ItemStack item : p.getInventory().getContents()) {
            if (normalize(key).equals(keyFromItem(item))) count += item.getAmount();
        }

        return count;
    }

    private String keyFromItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(keyKey, PersistentDataType.STRING);
    }

    private ItemStack keyItem(String key, int amount) {
        key = normalize(key);
        ItemStack item = new ItemStack(parseMaterial(getConfig().getString("key-item.material", "LIGHTNING_ROD")), Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();

        meta.setDisplayName(color(getConfig().getString("key-item.name", "&6&l%key_name%").replace("%key_name%", display(key))));

        List<String> lore = new ArrayList<>();
        for (String line : getConfig().getStringList("key-item.lore")) {
            lore.add(color(line.replace("%key_name%", display(key)).replace("%key%", key)));
        }

        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        meta.getPersistentDataContainer().set(keyKey, PersistentDataType.STRING, key);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack actionItem(Material mat, String name, List<String> lore, String action) {
        ItemStack item = named(mat, name);
        ItemMeta meta = item.getItemMeta();

        List<String> colored = new ArrayList<>();
        for (String line : lore) colored.add(color(line));

        meta.setLore(colored);
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        item.setItemMeta(meta);
        return item;
    }

    private void fill(Inventory inv) {
        ItemStack filler = named(parseMaterial(getConfig().getString("gui.filler", "BLACK_STAINED_GLASS_PANE")), " ");
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);
    }

    private List<String> weeklyLore(Player p) {
        long cooldown = getConfig().getLong("settings.cooldown-seconds", 604800L) * 1000L;
        long last = data.getLong(path(p.getName()) + ".lastWeekly", 0L);
        long left = cooldown - (System.currentTimeMillis() - last);
        String status = last <= 0 || left <= 0 ? "&aDostępny" : "&cZa " + time(left);

        List<String> out = new ArrayList<>();
        for (String line : getConfig().getStringList("weekly.lore")) out.add(line.replace("%status%", status));
        return out;
    }

    private boolean isBlockedWorld(Player p) {
        for (String world : getConfig().getStringList("blocked-worlds")) {
            if (p.getWorld().getName().equalsIgnoreCase(world)) return true;
        }
        return false;
    }

    private String roll(String path) {
        ConfigurationSection section = getConfig().getConfigurationSection(path);
        if (section == null || section.getKeys(false).isEmpty()) return "klasyczny";

        int total = 0;
        for (String key : section.getKeys(false)) total += Math.max(0, section.getInt(key, 0));

        int result = random.nextInt(Math.max(1, total)) + 1;
        int current = 0;

        for (String key : section.getKeys(false)) {
            current += Math.max(0, section.getInt(key, 0));
            if (result <= current) return normalize(key);
        }

        return "klasyczny";
    }

    private void ownerSet(Player p) {
        p.getInventory().addItem(enchant(Material.NETHERITE_HELMET, "&b&lHełm MILEKZ", new String[][]{{"protection","4"},{"respiration","3"},{"aqua_affinity","1"},{"thorns","3"},{"unbreaking","3"},{"mending","1"}}));
        p.getInventory().addItem(enchant(Material.NETHERITE_CHESTPLATE, "&b&lNapierśnik MILEKZ", new String[][]{{"protection","4"},{"thorns","3"},{"unbreaking","3"},{"mending","1"}}));
        p.getInventory().addItem(enchant(Material.NETHERITE_LEGGINGS, "&b&lSpodnie MILEKZ", new String[][]{{"protection","4"},{"thorns","3"},{"unbreaking","3"},{"mending","1"}}));
        p.getInventory().addItem(enchant(Material.NETHERITE_BOOTS, "&b&lButy MILEKZ", new String[][]{{"protection","4"},{"feather_falling","4"},{"depth_strider","3"},{"soul_speed","3"},{"thorns","3"},{"unbreaking","3"},{"mending","1"}}));
        p.getInventory().addItem(enchant(Material.NETHERITE_SWORD, "&c&lMieczyk", new String[][]{{"sharpness","5"},{"looting","3"},{"fire_aspect","2"},{"sweeping_edge","3"},{"knockback","2"},{"unbreaking","3"},{"mending","1"}}));
        p.getInventory().addItem(enchant(Material.BOW, "&e&lŁuk Boga", new String[][]{{"power","5"},{"punch","2"},{"flame","1"},{"infinity","1"},{"unbreaking","3"},{"mending","1"}}));
    }

    private ItemStack enchant(Material material, String name, String[][] enchants) {
        ItemStack item = named(material, name);
        for (String[] ench : enchants) {
            Enchantment e = Enchantment.getByKey(NamespacedKey.minecraft(ench[0]));
            if (e != null) item.addUnsafeEnchantment(e, Integer.parseInt(ench[1]));
        }
        return item;
    }

    private int getKeys(String player, String key) {
        return data.getInt(path(player) + ".keys." + normalize(key), 0);
    }

    private void setKeys(String player, String key, int amount) {
        data.set(path(player) + ".keys." + normalize(key), Math.max(0, amount));
        saveData();
    }

    private String display(String key) {
        return getConfig().getString("keys." + normalize(key) + ".display", key);
    }

    private String path(String player) {
        return "players." + player.toLowerCase(Locale.ROOT);
    }

    private String normalize(String raw) {
        return raw == null ? "" : raw.toLowerCase(Locale.ROOT);
    }

    private int parseInt(String raw) {
        try { return Math.max(1, Integer.parseInt(raw)); }
        catch (Exception e) { return 1; }
    }

    private ItemStack named(Material mat, String name) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(color(name));
        item.setItemMeta(meta);
        return item;
    }

    private Material parseMaterial(String raw) {
        try { return Material.valueOf(raw.toUpperCase(Locale.ROOT)); }
        catch (Exception e) { return Material.STONE; }
    }

    private String msg(String key) {
        return color(getConfig().getString("messages.prefix", "") + getConfig().getString("messages." + key, ""));
    }

    private String color(String text) {
        return text == null ? "" : ChatColor.translateAlternateColorCodes('&', text);
    }

    private String time(long ms) {
        long s = Math.max(0, ms / 1000L);
        long d = s / 86400L;
        s %= 86400L;
        long h = s / 3600L;
        s %= 3600L;
        long m = s / 60L;

        if (d > 0) return d + "d " + h + "h";
        if (h > 0) return h + "h " + m + "m";
        return Math.max(1, m) + "m";
    }

    private void loadData() {
        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            try {
                getDataFolder().mkdirs();
                dataFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        data = YamlConfiguration.loadConfiguration(dataFile);
    }

    private void saveData() {
        try { data.save(dataFile); }
        catch (IOException e) { e.printStackTrace(); }
    }
}
