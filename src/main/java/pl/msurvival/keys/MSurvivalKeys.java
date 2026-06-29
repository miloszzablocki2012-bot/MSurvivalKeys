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

import java.io.*;
import java.util.*;

@SuppressWarnings("deprecation")
public final class MSurvivalKeys extends JavaPlugin implements Listener {
    private NamespacedKey keyKey, actionKey;
    private File dataFile;
    private YamlConfiguration data;
    private final Random random = new Random();

    @Override public void onEnable(){ saveDefaultConfig(); loadData(); keyKey=new NamespacedKey(this,"key_type"); actionKey=new NamespacedKey(this,"action"); Bukkit.getPluginManager().registerEvents(this,this); commands(); }

    private void commands(){
        getCommand("keysmenu").setExecutor((s,c,l,a)->{ if(s instanceof Player p) openMenu(p); return true; });
        getCommand("key").setExecutor((s,c,l,a)->{ if(!(s instanceof Player p)) return true; if(a.length<2 || !a[0].equalsIgnoreCase("use")) return true; openKey(p, norm(a[1])); return true; });
        getCommand("keyadmin").setExecutor((s,c,l,a)->{
            if(!s.hasPermission("msurvivalkeys.admin")){ s.sendMessage(msg("no-permission")); return true; }
            if(a.length>=1 && a[0].equalsIgnoreCase("reload")){ reloadConfig(); s.sendMessage(msg("reload")); return true; }
            if(a.length<3){ s.sendMessage(color("&c/keyadmin <give|item|reset> <gracz> <klucz> [ilosc]")); return true; }
            String mode=a[0].toLowerCase(Locale.ROOT), player=a[1], key=norm(a[2]); int amount=a.length>=4?parse(a[3]):1;
            if(mode.equals("reset")){ data.set(path(player)+".lastWeekly",0L); saveData(); return true; }
            if(!getConfig().contains("keys."+key)){ s.sendMessage(color("&cNie ma klucza.")); return true; }
            if(mode.equals("give")){ setKeys(player,key,getKeys(player,key)+amount); s.sendMessage(msg("admin-give").replace("%key%", key).replace("%player%", player)); return true; }
            if(mode.equals("item")){ Player t=Bukkit.getPlayerExact(player); if(t==null){s.sendMessage(color("&cGracz offline.")); return true;} t.getInventory().addItem(keyItem(key,amount)); s.sendMessage(msg("admin-item").replace("%key%", key).replace("%player%", player)); return true; }
            return true;
        });
    }

    @EventHandler public void click(PlayerInteractEvent e){ Action a=e.getAction(); if(a!=Action.RIGHT_CLICK_AIR && a!=Action.RIGHT_CLICK_BLOCK) return; String key=itemKey(e.getItem()); if(key==null) return; e.setCancelled(true); openKey(e.getPlayer(), key); }

    @EventHandler public void gui(InventoryClickEvent e){
        if(!(e.getWhoClicked() instanceof Player p)) return;
        if(!e.getView().getTitle().equals(color(getConfig().getString("gui.title")))) return;
        e.setCancelled(true);
        ItemStack it=e.getCurrentItem(); if(it==null || !it.hasItemMeta()) return;
        String action=it.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if(action==null) return;
        p.closeInventory();
        if(action.equals("weekly")) weekly(p);
        else if(action.startsWith("key:")) openKey(p, action.substring(4));
    }

    private void openMenu(Player p){
        Inventory inv=Bukkit.createInventory(null,54,color(getConfig().getString("gui.title")));
        ItemStack fill=named(mat(getConfig().getString("gui.filler"))," "); for(int i=0;i<54;i++) inv.setItem(i,fill);
        inv.setItem(getConfig().getInt("weekly.slot",4), actionItem(mat(getConfig().getString("weekly.material")), getConfig().getString("weekly.name"), weeklyLore(p), "weekly"));
        int slot=10; ConfigurationSection sec=getConfig().getConfigurationSection("keys");
        if(sec!=null) for(String key:sec.getKeys(false)){ if(slot>=44) break; inv.setItem(slot, actionItem(Material.LIGHTNING_ROD, getConfig().getString("keys."+key+".display"), List.of("&7Posiadasz: &e"+totalKeys(p,key), "&eKliknij, aby otworzyć."), "key:"+key)); slot++; if(slot==17||slot==26||slot==35) slot+=2; }
        p.openInventory(inv);
    }

    private List<String> weeklyLore(Player p){
        long cd=getConfig().getLong("settings.cooldown-seconds")*1000L, last=data.getLong(path(p.getName())+".lastWeekly",0), left=Math.max(0, cd-(System.currentTimeMillis()-last));
        String status=(last<=0||left<=0)?"&aDostępny":"&cZa "+time(left);
        List<String> list=new ArrayList<>(); for(String s:getConfig().getStringList("weekly.lore")) list.add(s.replace("%status%", status)); return list;
    }

    private void weekly(Player p){
        long cd=getConfig().getLong("settings.cooldown-seconds")*1000L, last=data.getLong(path(p.getName())+".lastWeekly",0), left=Math.max(0, cd-(System.currentTimeMillis()-last));
        if(last>0 && left>0){ p.sendMessage(msg("cooldown").replace("%time%", time(left))); return; }
        String key=roll("weekly-random");
        data.set(path(p.getName())+".lastWeekly",System.currentTimeMillis()); saveData();
        p.getInventory().addItem(keyItem(key,1));
        p.sendMessage(msg("claimed").replace("%key%", display(key)));
    }

    private void openKey(Player p,String key){
        if(blocked(p)){ p.sendMessage(msg("blocked")); return; }
        if(!hasKey(p,key)){ p.sendMessage(msg("no-key")); return; }
        takeKey(p,key);
        p.sendTitle(color("&6&lOTWIERANIE KITU"), color(display(key)), 5,25,5);
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING,1,1);
        Bukkit.getScheduler().runTaskLater(this, ()->{ if(p.isOnline()) giveKitFromKey(p,key); }, 25L);
    }

    private void giveKitFromKey(Player p,String key){
        String kit=getConfig().getString("keys."+key+".kit", null);
        if(kit==null && getConfig().contains("keys."+key+".random-kits")) kit=roll("keys."+key+".random-kits");
        if(kit==null) kit=key;
        if(getConfig().getBoolean("kits."+kit+".owner-set",false)) ownerSet(p);
        for(String cmd:getConfig().getStringList("kits."+kit+".rewards")) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%",p.getName()));
        p.sendMessage(msg("opened").replace("%kit%", kit));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP,1,1);
    }

    private String roll(String path){ ConfigurationSection s=getConfig().getConfigurationSection(path); if(s==null) return "klasyczny"; int total=0; for(String k:s.getKeys(false)) total+=Math.max(0,s.getInt(k)); int r=random.nextInt(Math.max(1,total))+1,c=0; for(String k:s.getKeys(false)){ c+=Math.max(0,s.getInt(k)); if(r<=c) return norm(k); } return "klasyczny"; }
    private boolean blocked(Player p){ for(String w:getConfig().getStringList("blocked-worlds")) if(p.getWorld().getName().equalsIgnoreCase(w)) return true; return false; }

    private ItemStack keyItem(String key,int amount){
        ItemStack it=new ItemStack(mat(getConfig().getString("key-item.material")), amount);
        ItemMeta m=it.getItemMeta(); m.setDisplayName(color(getConfig().getString("key-item.name").replace("%key_name%",display(key))));
        List<String> lore=new ArrayList<>(); for(String s:getConfig().getStringList("key-item.lore")) lore.add(color(s.replace("%key%",key).replace("%key_name%",display(key))));
        m.setLore(lore); m.getPersistentDataContainer().set(keyKey, PersistentDataType.STRING, key); it.setItemMeta(m); return it;
    }

    private ItemStack actionItem(Material mat,String name,List<String> lore,String action){ ItemStack it=named(mat,name); ItemMeta m=it.getItemMeta(); List<String> l=new ArrayList<>(); for(String s:lore) l.add(color(s)); m.setLore(l); m.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action); it.setItemMeta(m); return it; }
    private ItemStack named(Material mat,String name){ ItemStack it=new ItemStack(mat); ItemMeta m=it.getItemMeta(); m.setDisplayName(color(name)); it.setItemMeta(m); return it; }
    private String itemKey(ItemStack it){ if(it==null || !it.hasItemMeta()) return null; return it.getItemMeta().getPersistentDataContainer().get(keyKey, PersistentDataType.STRING); }
    private boolean hasKey(Player p,String key){ return getKeys(p.getName(),key)>0 || countPhysical(p,key)>0; }
    private void takeKey(Player p,String key){ int v=getKeys(p.getName(),key); if(v>0){ setKeys(p.getName(),key,v-1); return; } removePhysical(p,key); }
    private int countPhysical(Player p,String key){ int c=0; for(ItemStack it:p.getInventory().getContents()) if(key.equals(itemKey(it))) c+=it.getAmount(); return c; }
    private void removePhysical(Player p,String key){ ItemStack[] c=p.getInventory().getContents(); for(int i=0;i<c.length;i++){ if(key.equals(itemKey(c[i]))){ if(c[i].getAmount()<=1) p.getInventory().setItem(i,null); else c[i].setAmount(c[i].getAmount()-1); return; } } }
    private int totalKeys(Player p,String key){ return getKeys(p.getName(),key)+countPhysical(p,key); }
    private int getKeys(String player,String key){ return data.getInt(path(player)+".keys."+key,0); }
    private void setKeys(String player,String key,int amount){ data.set(path(player)+".keys."+key,Math.max(0,amount)); saveData(); }

    private void ownerSet(Player p){
        p.getInventory().addItem(enchant(Material.NETHERITE_HELMET,"&b&lHełm MILEKZ",new String[][]{{"protection","4"},{"respiration","3"},{"aqua_affinity","1"},{"thorns","3"},{"unbreaking","3"},{"mending","1"}}));
        p.getInventory().addItem(enchant(Material.NETHERITE_CHESTPLATE,"&b&lNapierśnik MILEKZ",new String[][]{{"protection","4"},{"thorns","3"},{"unbreaking","3"},{"mending","1"}}));
        p.getInventory().addItem(enchant(Material.NETHERITE_LEGGINGS,"&b&lSpodnie MILEKZ",new String[][]{{"protection","4"},{"thorns","3"},{"unbreaking","3"},{"mending","1"}}));
        p.getInventory().addItem(enchant(Material.NETHERITE_BOOTS,"&b&lButy MILEKZ",new String[][]{{"protection","4"},{"feather_falling","4"},{"depth_strider","3"},{"soul_speed","3"},{"thorns","3"},{"unbreaking","3"},{"mending","1"}}));
        p.getInventory().addItem(enchant(Material.NETHERITE_SWORD,"&c&lMieczyk",new String[][]{{"sharpness","5"},{"looting","3"},{"fire_aspect","2"},{"sweeping_edge","3"},{"knockback","2"},{"unbreaking","3"},{"mending","1"}}));
        p.getInventory().addItem(enchant(Material.BOW,"&e&lŁuk Boga",new String[][]{{"power","5"},{"punch","2"},{"flame","1"},{"infinity","1"},{"unbreaking","3"},{"mending","1"}}));
        p.getInventory().addItem(enchant(mat("MACE"),"&4&lBuzdygan MILEKZ",new String[][]{{"density","5"},{"breach","3"},{"smite","5"},{"unbreaking","3"},{"mending","1"}}));
    }
    private ItemStack enchant(Material mat,String name,String[][] ench){ ItemStack it=named(mat,name); for(String[] e:ench){ Enchantment en=Enchantment.getByKey(NamespacedKey.minecraft(e[0])); if(en!=null) it.addUnsafeEnchantment(en,Integer.parseInt(e[1])); } return it; }

    private int parse(String s){ try{return Math.max(1,Integer.parseInt(s));}catch(Exception e){return 1;} }
    private Material mat(String s){ try{return Material.valueOf(s.toUpperCase(Locale.ROOT));}catch(Exception e){return Material.STONE;} }
    private String display(String key){ return getConfig().getString("keys."+key+".display", key); }
    private String path(String player){ return "players."+player.toLowerCase(Locale.ROOT); }
    private String norm(String s){ return s.toLowerCase(Locale.ROOT); }
    private String msg(String k){ return color(getConfig().getString("messages.prefix","")+getConfig().getString("messages."+k,"")); }
    private String color(String s){ return s==null?"":ChatColor.translateAlternateColorCodes('&',s); }
    private String time(long ms){ long s=ms/1000,d=s/86400; s%=86400; long h=s/3600; s%=3600; long m=s/60; if(d>0)return d+"d "+h+"h"; if(h>0)return h+"h "+m+"m"; return Math.max(1,m)+"m"; }
    private void loadData(){ dataFile=new File(getDataFolder(),"data.yml"); if(!dataFile.exists()){ try{getDataFolder().mkdirs(); dataFile.createNewFile();}catch(IOException e){e.printStackTrace();} } data=YamlConfiguration.loadConfiguration(dataFile); }
    private void saveData(){ try{data.save(dataFile);}catch(IOException e){e.printStackTrace();} }
}
