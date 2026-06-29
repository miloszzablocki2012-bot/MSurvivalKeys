package pl.msurvival.keys;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MSurvivalKeys extends JavaPlugin implements Listener {

    private File dataFile;
    private FileConfiguration data;
    private NamespacedKey itemTypeKey;
    private NamespacedKey menuItemKey;
    private NamespacedKey menuActionKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadData();

        itemTypeKey = new NamespacedKey(this, "key_type");
        menuItemKey = new NamespacedKey(this, "menu_item");
        menuActionKey = new NamespacedKey(this, "menu_action");

        Bukkit.getPluginManager().registerEvents(this, this);
        registerCommands();

        getLogger().info("MSurvivalKeys v3 wlaczony!");
    }

    private void registerCommands() {
        getCommand("keysmenu").setExecutor((sender, command, label, args) -> {
            if (sender instanceof Player player) {
                openMainMenu(player);
            }
            return true;
        });

        getCommand("menuitem").setExecutor((sender, command, label, args) -> {
            if (sender instanceof Player player) {
                player.getInventory().addItem(createMenuCompass());
                player.sendMessage(msg("menuitem-given"));
            }
            return true;
        });

        getCommand("key").setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player player)) {
                return true;
            }

            if (args.length < 2 || !args[0].equalsIgnoreCase("use")) {
                player.sendMessage(msg("usage-key"));
                return true;
            }

            startKeyAnimation(player, normalize(args[1]));
            return true;
        });

        getCommand("keyadmin").setExecutor((sender, command, label, args) -> {
            if (!sender.hasPermission("msurvivalkeys.admin")) {
                sender.sendMessage(msg("no-permission"));
                return true;
            }

            if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
                reloadConfig();
                sender.sendMessage(msg("reload"));
                return true;
            }

            if (args.length < 2) {
                sender.sendMessage(msg("usage-admin"));
                return true;
            }

            String action = args[0].toLowerCase(Locale.ROOT);
            String name = args[1];

            if (action.equals("reset")) {
                data.set(path(name) + ".lastClaim", 0L);
                saveData();
                sender.sendMessage(msg("admin-reset").replace("%player%", name));
                return true;
            }

            if (action.equals("give")) {
                String key = args.length >= 3 ? normalize(args[2]) : getConfig().getString("settings.default-key", "klasyczny");
                int amount = 1;

                if (args.length >= 4) {
                    try {
                        amount = Math.max(1, Integer.parseInt(args[3]));
                    } catch (NumberFormatException ignored) {
                    }
                }

                if (!keyExists(key)) {
                    sender.sendMessage(msg("unknown-key"));
                    return true;
                }

                addKeys(name, key, amount);
                sender.sendMessage(msg("admin-give")
                        .replace("%player%", name)
                        .replace("%amount%", String.valueOf(amount))
                        .replace("%key_name%", color(getKeyDisplay(key))));
                return true;
            }

            if (action.equals("item")) {
                if (args.length < 3) {
                    sender.sendMessage(msg("usage-admin"));
                    return true;
                }

                Player target = Bukkit.getPlayerExact(name);
                String key = normalize(args[2]);
                int amount = 1;

                if (args.length >= 4) {
                    try {
                        amount = Math.max(1, Integer.parseInt(args[3]));
                    } catch (NumberFormatException ignored) {
                    }
                }

                if (target == null) {
                    sender.sendMessage(color("&cGracz musi być online."));
                    return true;
                }

                if (!keyExists(key)) {
                    sender.sendMessage(msg("unknown-key"));
                    return true;
                }

                target.getInventory().addItem(createKeyItem(key, amount));
                sender.sendMessage(msg("key-given-item").replace("%key_name%", color(getKeyDisplay(key))));
                return true;
            }

            sender.sendMessage(msg("usage-admin"));
            return true;
        });
    }

    @EventHandler
    public void onJoinGiveMenuItem(PlayerJoinEvent event) {
        if (!getConfig().getBoolean("menu-item.give-on-join", true)) {
            return;
        }

        Player player = event.getPlayer();

        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline()) {
                return;
            }

            if (hasMenuItem(player)) {
                return;
            }

            int slot = getConfig().getInt("menu-item.slot", 4);
            ItemStack item = createMenuCompass();

            if (slot >= 0 && slot <= 35) {
                ItemStack current = player.getInventory().getItem(slot);

                if (current == null || current.getType() == Material.AIR || isMenuCompass(current)) {
                    player.getInventory().setItem(slot, item);
                    return;
                }
            }

            player.getInventory().addItem(item);
        }, 20L);
    }

    private boolean hasMenuItem(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (isMenuCompass(item)) {
                return true;
            }
        }

        return false;
    }

    @EventHandler
    public void onItemClick(PlayerInteractEvent event) {
        Action action = event.getAction();

        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        ItemStack item = event.getItem();

        if (isMenuCompass(item)) {
            event.setCancelled(true);
            openMainMenu(event.getPlayer());
            return;
        }

        String key = getKeyFromItem(item);

        if (key != null) {
            event.setCancelled(true);
            startKeyAnimation(event.getPlayer(), key);
        }
    }

    @EventHandler
    public void onGuiClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (event.getCurrentItem() == null) {
            return;
        }

        String title = event.getView().getTitle();

        if (!title.equals(color(getConfig().getString("gui.main-title", "&6&lMSURVIVAL MENU")))
                && !title.equals(color(getConfig().getString("gui.keys-title", "&6&lKLUCZE SERWERA")))) {
            return;
        }

        event.setCancelled(true);

        ItemMeta meta = event.getCurrentItem().getItemMeta();

        if (meta == null) {
            return;
        }

        String action = meta.getPersistentDataContainer().get(menuActionKey, PersistentDataType.STRING);

        if (action == null) {
            return;
        }

        if (action.equals("keys")) {
            openKeysMenu(player);
            return;
        }

        if (action.startsWith("command:")) {
            player.closeInventory();
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), action.substring(8).replace("%player%", player.getName()));
            return;
        }

        if (action.equals("weekly")) {
            player.closeInventory();
            claimWeekly(player);
            return;
        }

        if (action.startsWith("usekey:")) {
            player.closeInventory();
            startKeyAnimation(player, action.substring(7));
        }
    }

    private void openMainMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, getConfig().getInt("gui.size", 27), color(getConfig().getString("gui.main-title", "&6&lMSURVIVAL MENU")));
        fill(inv);

        addMenuItem(inv, "gui.items.lobby", "command:" + getConfig().getString("gui.items.lobby.command", "spawn %player%"));
        addMenuItem(inv, "gui.items.survival", "command:" + getConfig().getString("gui.items.survival.command", "spawn %player%"));
        addMenuItem(inv, "gui.items.keys", "keys");

        player.openInventory(inv);
    }

    private void openKeysMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, color(getConfig().getString("gui.keys-title", "&6&lKLUCZE SERWERA")));
        fill(inv);

        ConfigurationSection section = getConfig().getConfigurationSection("keys");

        if (section == null) {
            player.openInventory(inv);
            return;
        }

        int slot = 10;

        for (String key : section.getKeys(false)) {
            if (slot >= 44) {
                break;
            }

            inv.setItem(slot, createGuiKeyItem(key, player));
            slot++;

            if (slot == 17 || slot == 26 || slot == 35) {
                slot += 2;
            }
        }

        player.openInventory(inv);
    }

    private void fill(Inventory inv) {
        Material filler = parseMaterial(getConfig().getString("gui.filler", "BLACK_STAINED_GLASS_PANE"));
        ItemStack item = new ItemStack(filler);
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            meta.setDisplayName(" ");
            item.setItemMeta(meta);
        }

        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, item);
        }
    }

    private void addMenuItem(Inventory inv, String path, String action) {
        int slot = getConfig().getInt(path + ".slot", 13);
        ItemStack item = new ItemStack(parseMaterial(getConfig().getString(path + ".material", "STONE")));
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            meta.setDisplayName(color(getConfig().getString(path + ".name", "&fItem")));

            List<String> lore = new ArrayList<>();

            for (String line : getConfig().getStringList(path + ".lore")) {
                lore.add(color(line));
            }

            meta.setLore(lore);
            meta.getPersistentDataContainer().set(menuActionKey, PersistentDataType.STRING, action);
            item.setItemMeta(meta);
        }

        inv.setItem(slot, item);
    }

    private void addWeeklyKeyItem(Inventory inv, Player player) {
        int slot = getConfig().getInt("weekly-menu.slot", 4);
        ItemStack item = new ItemStack(parseMaterial(getConfig().getString("weekly-menu.material", "CHEST")));
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            meta.setDisplayName(color(getConfig().getString("weekly-menu.name", "&a&lOdbierz cotygodniowy klucz")));

            List<String> lore = new ArrayList<>();
            long cooldown = getConfig().getLong("settings.cooldown-seconds", 604800L) * 1000L;
            long last = data.getLong(path(player.getName()) + ".lastClaim", 0L);
            long left = Math.max(0L, cooldown - (System.currentTimeMillis() - last));
            boolean canClaim = last <= 0 || left <= 0L;

            for (String line : getConfig().getStringList("weekly-menu.lore")) {
                lore.add(color(line
                        .replace("%status%", canClaim ? "&aDostępny" : "&cZa " + formatTime(left))
                        .replace("%key_name%", getKeyDisplay(getConfig().getString("settings.default-key", "klasyczny")))));
            }

            meta.setLore(lore);
            meta.getPersistentDataContainer().set(menuActionKey, PersistentDataType.STRING, "weekly");
            item.setItemMeta(meta);
        }

        inv.setItem(slot, item);
    }

    private ItemStack createGuiKeyItem(String key, Player player) {
        ItemStack item = new ItemStack(parseMaterial(getConfig().getString("keys." + key + ".material", "LIGHTNING_ROD")));
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            meta.setDisplayName(color(getKeyDisplay(key)));

            List<String> lore = new ArrayList<>();
            lore.add(color("&8MSurvival Key"));
            lore.add("");
            lore.add(color("&7Posiadasz: &e" + getTotalKeys(player, key)));
            lore.add(color(getConfig().getString("keys." + key + ".price-info", "&7Brak informacji.")));
            lore.add("");
            lore.add(color("&eKliknij, aby użyć."));

            meta.setLore(lore);
            meta.getPersistentDataContainer().set(menuActionKey, PersistentDataType.STRING, "usekey:" + key);
            item.setItemMeta(meta);
        }

        return item;
    }

    private ItemStack createMenuCompass() {
        ItemStack item = new ItemStack(parseMaterial(getConfig().getString("menu-item.material", "COMPASS")));
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            meta.setDisplayName(color(getConfig().getString("menu-item.name", "&6&lMSurvival Menu")));

            List<String> lore = new ArrayList<>();

            for (String line : getConfig().getStringList("menu-item.lore")) {
                lore.add(color(line));
            }

            meta.setLore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            meta.getPersistentDataContainer().set(menuItemKey, PersistentDataType.STRING, "true");
            item.setItemMeta(meta);
        }

        return item;
    }

    private ItemStack createKeyItem(String key, int amount) {
        ItemStack item = new ItemStack(parseMaterial(getConfig().getString("key-item.material", "LIGHTNING_ROD")), Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            meta.setDisplayName(color(getConfig().getString("key-item.name", "&6&l%key_name%")
                    .replace("%key_name%", getKeyDisplay(key))));

            List<String> lore = new ArrayList<>();

            for (String line : getConfig().getStringList("key-item.lore")) {
                lore.add(color(line
                        .replace("%key%", key)
                        .replace("%key_name%", getKeyDisplay(key))));
            }

            meta.setLore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            meta.getPersistentDataContainer().set(itemTypeKey, PersistentDataType.STRING, key);
            item.setItemMeta(meta);
        }

        return item;
    }

    private boolean isMenuCompass(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }

        ItemMeta meta = item.getItemMeta();

        return meta != null && meta.getPersistentDataContainer().has(menuItemKey, PersistentDataType.STRING);
    }

    private String getKeyFromItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }

        ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return null;
        }

        return meta.getPersistentDataContainer().get(itemTypeKey, PersistentDataType.STRING);
    }

    private void claimWeekly(Player player) {
        String key = chooseWeeklyKey();
        String p = path(player.getName());
        long cooldown = getConfig().getLong("settings.cooldown-seconds", 604800L) * 1000L;
        long now = System.currentTimeMillis();
        long last = data.getLong(p + ".lastClaim", 0L);

        if (last > 0 && now - last < cooldown) {
            player.sendMessage(msg("cooldown").replace("%time%", formatTime(cooldown - (now - last))));
            return;
        }

        data.set(p + ".lastClaim", now);
        saveData();

        player.getInventory().addItem(createKeyItem(key, 1));
        player.sendMessage(msg("claimed")
                .replace("%key_name%", color(getKeyDisplay(key)))
                .replace("%amount%", String.valueOf(getTotalKeys(player, key))));
    }

    private String chooseWeeklyKey() {
        if (!getConfig().getBoolean("weekly-random.enabled", true)) {
            return getConfig().getString("settings.default-key", "klasyczny");
        }

        org.bukkit.configuration.ConfigurationSection section = getConfig().getConfigurationSection("weekly-random.keys");

        if (section == null || section.getKeys(false).isEmpty()) {
            return getConfig().getString("settings.default-key", "klasyczny");
        }

        int total = 0;
        for (String key : section.getKeys(false)) {
            total += Math.max(0, section.getInt(key, 0));
        }

        if (total <= 0) {
            return getConfig().getString("settings.default-key", "klasyczny");
        }

        int roll = new java.util.Random().nextInt(total) + 1;
        int current = 0;

        for (String key : section.getKeys(false)) {
            current += Math.max(0, section.getInt(key, 0));
            if (roll <= current) return normalize(key);
        }

        return getConfig().getString("settings.default-key", "klasyczny");
    }

    private void useKey(Player player, String key) {
        if (isBlockedWorld(player)) {
            player.sendMessage(msg("cannot-use-in-lobby"));
            return;
        }

        if (!keyExists(key)) {
            player.sendMessage(msg("unknown-key"));
            return;
        }

        if (!takeOneKey(player, key)) {
            player.sendMessage(msg("no-keys"));
            return;
        }

        if (getConfig().getBoolean("keys." + key + ".owner-set", false)) {
            giveOwnerSet(player);
        }

        for (String command : getConfig().getStringList("keys." + key + ".rewards")) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.replace("%player%", player.getName()));
        }

        playEffects(player, key);
        player.sendMessage(msg("used").replace("%key_name%", color(getKeyDisplay(key))));
    }

    private void giveOwnerSet(Player player) {
        player.getInventory().addItem(item(Material.NETHERITE_HELMET, "&b&lHełm MILEKZ", List.of("&8Set Właściciela", "&7Zdobycie zbroi &aNurt", "&bDiamentowy materiał"),
                new String[][]{{"protection", "4"}, {"respiration", "3"}, {"aqua_affinity", "1"}, {"thorns", "3"}, {"unbreaking", "3"}, {"mending", "1"}}));

        player.getInventory().addItem(item(Material.NETHERITE_CHESTPLATE, "&b&lNapierśnik MILEKZ", List.of("&8Set Właściciela", "&7Zdobycie zbroi &aNurt", "&bDiamentowy materiał"),
                new String[][]{{"protection", "4"}, {"thorns", "3"}, {"unbreaking", "3"}, {"mending", "1"}}));

        player.getInventory().addItem(item(Material.NETHERITE_LEGGINGS, "&b&lSpodnie MILEKZ", List.of("&8Set Właściciela", "&7Zdobycie zbroi &aNurt", "&aSzmaragdowy materiał"),
                new String[][]{{"protection", "4"}, {"thorns", "3"}, {"unbreaking", "3"}, {"mending", "1"}}));

        player.getInventory().addItem(item(Material.NETHERITE_BOOTS, "&b&lButy MILEKZ", List.of("&8Set Właściciela", "&7Zdobycie zbroi &aNurt", "&bDiamentowy materiał"),
                new String[][]{{"protection", "4"}, {"feather_falling", "4"}, {"depth_strider", "3"}, {"soul_speed", "3"}, {"thorns", "3"}, {"unbreaking", "3"}, {"mending", "1"}}));

        player.getInventory().addItem(item(Material.NETHERITE_SWORD, "&c&lMieczyk", List.of("&8Broń Właściciela", "&7Najwyższy poziom MSurvival"),
                new String[][]{{"sharpness", "5"}, {"looting", "3"}, {"fire_aspect", "2"}, {"sweeping_edge", "3"}, {"knockback", "2"}, {"unbreaking", "3"}, {"mending", "1"}}));

        player.getInventory().addItem(item(Material.NETHERITE_PICKAXE, "&b&lKilof MILEKZ", List.of("&8Narzędzie Właściciela"),
                new String[][]{{"efficiency", "5"}, {"silk_touch", "1"}, {"unbreaking", "3"}, {"mending", "1"}}));

        player.getInventory().addItem(item(Material.NETHERITE_AXE, "&b&lSiekiera MILEKZ", List.of("&8Narzędzie Właściciela"),
                new String[][]{{"efficiency", "5"}, {"smite", "5"}, {"sharpness", "3"}, {"unbreaking", "3"}, {"mending", "1"}}));

        player.getInventory().addItem(item(Material.NETHERITE_SHOVEL, "&b&lŁopata MILEKZ", List.of("&8Narzędzie Właściciela"),
                new String[][]{{"efficiency", "5"}, {"silk_touch", "1"}, {"unbreaking", "3"}, {"mending", "1"}}));

        player.getInventory().addItem(item(Material.NETHERITE_HOE, "&d&lMotyczka MILEKZ", List.of("&8Narzędzie Właściciela"),
                new String[][]{{"efficiency", "5"}, {"silk_touch", "1"}, {"unbreaking", "3"}, {"mending", "1"}}));

        player.getInventory().addItem(item(Material.BOW, "&e&lŁuk Boga", List.of("&8Broń Właściciela", "&7Najlepszy łuk MSurvival"),
                new String[][]{{"power", "5"}, {"punch", "2"}, {"flame", "1"}, {"infinity", "1"}, {"unbreaking", "3"}, {"mending", "1"}}));

        player.getInventory().addItem(item(Material.TRIDENT, "&3&lTrójząb MILEKZ", List.of("&8Broń Właściciela"),
                new String[][]{{"impaling", "5"}, {"loyalty", "3"}, {"channeling", "1"}, {"unbreaking", "3"}, {"mending", "1"}}));

        Material mace = parseMaterial("MACE");
        player.getInventory().addItem(item(mace, "&4&lBuzdygan MILEKZ", List.of("&8Broń Właściciela", "&7Zdobycie broni &aNurt", "&bDiamentowy materiał"),
                new String[][]{{"density", "5"}, {"breach", "3"}, {"smite", "5"}, {"fire_aspect", "2"}, {"unbreaking", "3"}, {"mending", "1"}}));

        player.getInventory().addItem(item(Material.ELYTRA, "&f&lElytry MILEKZ", List.of("&8Elytry Właściciela"),
                new String[][]{{"unbreaking", "3"}, {"mending", "1"}}));
    }

    private ItemStack item(Material material, String name, List<String> lore, String[][] enchantments) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            meta.setDisplayName(color(name));

            List<String> coloredLore = new ArrayList<>();

            for (String line : lore) {
                coloredLore.add(color(line));
            }

            meta.setLore(coloredLore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE);
            item.setItemMeta(meta);
        }

        for (String[] enchant : enchantments) {
            Enchantment e = Enchantment.getByKey(NamespacedKey.minecraft(enchant[0]));

            if (e != null) {
                item.addUnsafeEnchantment(e, Integer.parseInt(enchant[1]));
            }
        }

        return item;
    }

    private void playEffects(Player player, String key) {
        if (!getConfig().getBoolean("effects.enabled", true)) {
            return;
        }

        try {
            Sound sound = Sound.valueOf(getConfig().getString("effects.sound", "ENTITY_PLAYER_LEVELUP"));
            player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
        } catch (Exception ignored) {
        }

        player.sendTitle(
                color(getConfig().getString("effects.title", "&6&lKLUCZ OTWARTY")),
                color(getConfig().getString("effects.subtitle", "&7Użyto: &e%key_name%").replace("%key_name%", getKeyDisplay(key))),
                10,
                50,
                10
        );

        spawnFirework(player);
    }

    private void spawnFirework(Player player) {
        Firework firework = player.getWorld().spawn(player.getLocation(), Firework.class);
        FireworkMeta meta = firework.getFireworkMeta();

        meta.addEffect(FireworkEffect.builder()
                .withColor(Color.ORANGE, Color.YELLOW)
                .withFade(Color.RED)
                .with(FireworkEffect.Type.BALL_LARGE)
                .trail(true)
                .flicker(true)
                .build());

        meta.setPower(1);
        firework.setFireworkMeta(meta);
    }

    private void removeOneItem(Player player, ItemStack item) {
        if (item.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(null);
        } else {
            item.setAmount(item.getAmount() - 1);
        }
    }

    private boolean isBlockedWorld(Player player) {
        for (String world : getConfig().getStringList("blocked-worlds")) {
            if (player.getWorld().getName().equalsIgnoreCase(world)) {
                return true;
            }
        }
        return false;
    }

    private boolean takeOneKey(Player player, String key) {
        int virtualKeys = getKeys(player.getName(), key);

        if (virtualKeys > 0) {
            setKeys(player.getName(), key, virtualKeys - 1);
            return true;
        }

        return removePhysicalKey(player, key);
    }

    private int getTotalKeys(Player player, String key) {
        return getKeys(player.getName(), key) + countPhysicalKeys(player, key);
    }

    private int countPhysicalKeys(Player player, String key) {
        int amount = 0;

        for (ItemStack item : player.getInventory().getContents()) {
            if (key.equals(getKeyFromItem(item))) {
                amount += item.getAmount();
            }
        }

        return amount;
    }

    private boolean removePhysicalKey(Player player, String key) {
        ItemStack[] contents = player.getInventory().getContents();

        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];

            if (!key.equals(getKeyFromItem(item))) {
                continue;
            }

            if (item.getAmount() <= 1) {
                player.getInventory().setItem(i, null);
            } else {
                item.setAmount(item.getAmount() - 1);
                player.getInventory().setItem(i, item);
            }

            return true;
        }

        return false;
    }

    private void addKeys(String name, String key, int amount) {
        setKeys(name, key, getKeys(name, key) + amount);
    }

    private int getKeys(String name, String key) {
        return data.getInt(path(name) + ".keys." + normalize(key), 0);
    }

    private void setKeys(String name, String key, int amount) {
        data.set(path(name) + ".keys." + normalize(key), Math.max(0, amount));
        saveData();
    }

    private boolean keyExists(String key) {
        return getConfig().contains("keys." + normalize(key));
    }

    private String getKeyDisplay(String key) {
        return getConfig().getString("keys." + normalize(key) + ".display", key);
    }

    private Material parseMaterial(String value) {
        try {
            return Material.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return Material.STONE;
        }
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
        try {
            data.save(dataFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String path(String name) {
        return "players." + normalize(name);
    }

    private String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private String msg(String key) {
        return color(getConfig().getString("messages.prefix", "") + getConfig().getString("messages." + key, ""));
    }

    private String color(String text) {
        if (text == null) return "";
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private String formatTime(long millis) {
        long sec = Math.max(0, millis / 1000L);
        long days = sec / 86400L;
        sec %= 86400L;
        long hours = sec / 3600L;
        sec %= 3600L;
        long minutes = sec / 60L;

        if (days > 0) return days + "d " + hours + "h";
        if (hours > 0) return hours + "h " + minutes + "m";
        if (minutes > 0) return minutes + "m";
        return sec + "s";
    }
}
