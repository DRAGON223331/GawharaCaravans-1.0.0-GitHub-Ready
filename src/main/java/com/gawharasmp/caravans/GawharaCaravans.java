package com.gawharasmp.caravans;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Llama;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

public final class GawharaCaravans extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private Economy economy;
    private NamespacedKey caravanKey;
    private Caravan activeCaravan;
    private BukkitTask nextSpawnTask;
    private BukkitTask announceTask;
    private BukkitTask despawnTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        caravanKey = new NamespacedKey(this, "caravan_entity");
        if (!hookEconomy()) {
            getLogger().severe("Vault economy provider was not found. Install Vault and an economy plugin (for example EssentialsX Economy).");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("caravans"), "caravans command missing from plugin.yml").setExecutor(this);
        Objects.requireNonNull(getCommand("caravans")).setTabCompleter(this);
        removeOrphanedCaravanEntities();
        scheduleNextCaravan(true);
        getLogger().info("GawharaCaravans enabled. Vault economy hooked successfully.");
    }

    @Override
    public void onDisable() {
        cancelTask(nextSpawnTask);
        cancelTask(announceTask);
        cancelTask(despawnTask);
        despawnActiveCaravan(false);
    }

    private boolean hookEconomy() {
        RegisteredServiceProvider<Economy> registration = getServer().getServicesManager().getRegistration(Economy.class);
        if (registration == null) return false;
        economy = registration.getProvider();
        return economy != null;
    }

    private void scheduleNextCaravan(boolean first) {
        cancelTask(nextSpawnTask);
        cancelTask(announceTask);
        int min = first ? getConfig().getInt("settings.initial-delay-minutes", 10)
                : getConfig().getInt("settings.interval-minutes.min", 45);
        int max = first ? min : getConfig().getInt("settings.interval-minutes.max", 90);
        if (max < min) max = min;
        int delayMinutes = first ? Math.max(1, min) : ThreadLocalRandom.current().nextInt(Math.max(1, min), Math.max(1, max) + 1);
        long delayTicks = delayMinutes * 1200L;
        List<World> worlds = getEnabledWorlds();
        if (worlds.isEmpty()) {
            getLogger().warning("No enabled caravan worlds are loaded. Retrying in 5 minutes.");
            nextSpawnTask = Bukkit.getScheduler().runTaskLater(this, () -> scheduleNextCaravan(false), 6000L);
            return;
        }
        World targetWorld = worlds.get(ThreadLocalRandom.current().nextInt(worlds.size()));
        int before = getConfig().getInt("settings.announce-before-minutes", 2);
        if (before > 0 && delayMinutes > before) {
            long announceTicks = (delayMinutes - before) * 1200L;
            announceTask = Bukkit.getScheduler().runTaskLater(this, () ->
                    broadcast("messages.approaching", Map.of("world", targetWorld.getName())), announceTicks);
        }
        nextSpawnTask = Bukkit.getScheduler().runTaskLater(this, () -> {
            if (activeCaravan == null && !spawnCaravan(targetWorld, false)) {
                getLogger().warning("Could not find a safe caravan spawn in " + targetWorld.getName());
            }
            scheduleNextCaravan(false);
        }, delayTicks);
    }

    private List<World> getEnabledWorlds() {
        List<World> result = new ArrayList<>();
        for (String name : getConfig().getStringList("settings.enabled-worlds")) {
            if (isWorldAllowed(name)) {
                World world = Bukkit.getWorld(name);
                if (world != null) result.add(world);
            }
        }
        return result;
    }

    private boolean isWorldAllowed(String worldName) {
        if (worldName == null || worldName.isBlank()) return false;
        for (String blocked : getConfig().getStringList("settings.blocked-worlds")) {
            if (blocked.equalsIgnoreCase(worldName)) return false;
        }
        for (String enabled : getConfig().getStringList("settings.enabled-worlds")) {
            if (enabled.equalsIgnoreCase(worldName)) return true;
        }
        return false;
    }

    private boolean spawnCaravan(World world, boolean manual) {
        if (activeCaravan != null) return false;
        if (world == null || !isWorldAllowed(world.getName())) return false;
        Location location = findSafeLocation(world);
        if (location == null) return false;

        Caravan caravan = new Caravan(UUID.randomUUID(), world, location);
        createStock(caravan);
        try {
            Villager merchant = (Villager) world.spawnEntity(location, EntityType.VILLAGER);
            merchant.setAI(false);
            merchant.setInvulnerable(getConfig().getBoolean("merchant.invulnerable", true));
            merchant.setSilent(getConfig().getBoolean("merchant.silent", false));
            merchant.setPersistent(true);
            merchant.setProfession(parseProfession(getConfig().getString("merchant.profession", "ARMORER")));
            merchant.setCustomName(color(getConfig().getString("merchant.name", "&b&lGawhara &fTraveling Merchant")));
            merchant.setCustomNameVisible(true);
            tag(merchant);
            caravan.entities.add(merchant.getUniqueId());

            int llamaCount = Math.max(0, Math.min(6, getConfig().getInt("merchant.llamas", 2)));
            for (int i = 0; i < llamaCount; i++) {
                double angle = (Math.PI * 2.0 * i) / Math.max(1, llamaCount);
                Location llamaLocation = location.clone().add(Math.cos(angle) * 1.8, 0, Math.sin(angle) * 1.8);
                Llama llama = (Llama) world.spawnEntity(llamaLocation, EntityType.LLAMA);
                llama.setAI(false);
                llama.setInvulnerable(getConfig().getBoolean("merchant.invulnerable", true));
                llama.setPersistent(true);
                llama.setCustomName(color(getConfig().getString("merchant.llama-name", "&eCaravan Llama")));
                llama.setCustomNameVisible(true);
                tag(llama);
                caravan.entities.add(llama.getUniqueId());
            }
        } catch (RuntimeException ex) {
            for (UUID uuid : caravan.entities) {
                Entity entity = Bukkit.getEntity(uuid);
                if (entity != null) entity.remove();
            }
            getLogger().severe("Failed to spawn caravan entities: " + ex.getMessage());
            return false;
        }

        activeCaravan = caravan;
        Map<String, String> values = locationValues(location);
        broadcast("messages.spawned", values);
        int stay = Math.max(1, getConfig().getInt("settings.stay-minutes", 18));
        despawnTask = Bukkit.getScheduler().runTaskLater(this, () -> despawnActiveCaravan(true), stay * 1200L);
        if (manual) getLogger().info("Caravan was spawned manually at " + location.getWorld().getName() + " " + location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ());
        return true;
    }

    private Villager.Profession parseProfession(String raw) {
        try {
            return Villager.Profession.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return Villager.Profession.ARMORER;
        }
    }

    private void tag(Entity entity) {
        entity.getPersistentDataContainer().set(caravanKey, PersistentDataType.BYTE, (byte) 1);
    }

    private boolean isTagged(Entity entity) {
        return entity.getPersistentDataContainer().has(caravanKey, PersistentDataType.BYTE);
    }

    private void removeOrphanedCaravanEntities() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (isTagged(entity)) entity.remove();
            }
        }
    }

    private Location findSafeLocation(World world) {
        Location center = getConfiguredCenter(world);
        int minRadius = Math.max(1, getConfig().getInt("settings.spawn-radius.min", 250));
        int maxRadius = Math.max(minRadius, getConfig().getInt("settings.spawn-radius.max", 750));
        int spawnProtection = Math.max(0, getConfig().getInt("settings.protect-spawn-radius", 180));
        int attempts = Math.max(5, getConfig().getInt("settings.spawn-attempts", 50));
        Random random = ThreadLocalRandom.current();
        for (int i = 0; i < attempts; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double radius = minRadius + random.nextDouble() * (maxRadius - minRadius);
            int x = center.getBlockX() + (int) Math.round(Math.cos(angle) * radius);
            int z = center.getBlockZ() + (int) Math.round(Math.sin(angle) * radius);
            Location worldSpawn = world.getSpawnLocation();
            double spawnDx = (x + 0.5) - worldSpawn.getX();
            double spawnDz = (z + 0.5) - worldSpawn.getZ();
            if ((spawnDx * spawnDx) + (spawnDz * spawnDz) < (double) spawnProtection * spawnProtection) continue;
            int y = world.getHighestBlockYAt(x, z) + 1;
            if (y <= world.getMinHeight() || y + 1 >= world.getMaxHeight()) continue;
            if (world.getEnvironment() == World.Environment.NETHER && y > 120) continue;
            Location result = new Location(world, x + 0.5, y, z + 0.5);
            if (getConfig().getBoolean("settings.respect-world-border", true) && !world.getWorldBorder().isInside(result)) continue;
            Material floor = world.getBlockAt(x, y - 1, z).getType();
            Material feet = world.getBlockAt(x, y, z).getType();
            Material head = world.getBlockAt(x, y + 1, z).getType();
            if (!floor.isSolid() || floor.isAir() || floor == Material.MAGMA_BLOCK || floor == Material.CAMPFIRE || floor == Material.SOUL_CAMPFIRE) continue;
            if (!feet.isAir() && !world.getBlockAt(x, y, z).isPassable()) continue;
            if (!head.isAir() && !world.getBlockAt(x, y + 1, z).isPassable()) continue;
            if (feet == Material.WATER || feet == Material.LAVA || head == Material.WATER || head == Material.LAVA) continue;
            return result;
        }
        return null;
    }

    private Location getConfiguredCenter(World world) {
        String path = "world-centers." + world.getName();
        if (getConfig().contains(path + ".x") && getConfig().contains(path + ".z")) {
            return new Location(world, getConfig().getDouble(path + ".x"), 0, getConfig().getDouble(path + ".z"));
        }
        return world.getSpawnLocation();
    }

    private void createStock(Caravan caravan) {
        var section = getConfig().getConfigurationSection("shop.products");
        if (section == null) return;
        boolean dynamic = getConfig().getBoolean("shop.dynamic-prices.enabled", true);
        double minMultiplier = getConfig().getDouble("shop.dynamic-prices.min-multiplier", 0.85);
        double maxMultiplier = Math.max(minMultiplier, getConfig().getDouble("shop.dynamic-prices.max-multiplier", 1.25));
        for (String id : section.getKeys(false)) {
            var product = section.getConfigurationSection(id);
            if (product == null) continue;
            Material material;
            try {
                material = Material.valueOf(product.getString("material", "DIAMOND").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                getLogger().warning("Unknown product material for " + id + ": " + product.getString("material"));
                continue;
            }
            if (material.isAir()) continue;
            int minStock = Math.max(0, product.getInt("min-stock", 1));
            int maxStock = Math.max(minStock, product.getInt("max-stock", 5));
            int stock = ThreadLocalRandom.current().nextInt(minStock, maxStock + 1);
            double multiplier = dynamic ? minMultiplier + ThreadLocalRandom.current().nextDouble() * (maxMultiplier - minMultiplier) : 1.0;
            int amount = Math.max(1, product.getInt("amount", 1));
            int slot = product.getInt("slot", 10);
            if (slot < 0 || slot >= 36) continue;
            long coinBuyPrice = Math.max(0L, Math.round(product.getDouble("coins-buy-price", 0.0) * multiplier));
            ProductStock state = new ProductStock(id, material, product.getString("name", prettify(id)), slot, amount,
                    money(product.getDouble("buy-price", 100.0) * multiplier),
                    money(product.getDouble("sell-price", 0.0) * multiplier), coinBuyPrice, stock);
            caravan.products.put(slot, state);
        }
    }

    private double money(double value) {
        return Math.max(0.0, Math.round(value * 100.0) / 100.0);
    }

    private String prettify(String id) {
        String[] parts = id.replace('_', ' ').split(" ");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (!part.isEmpty()) out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1)).append(' ');
        }
        return out.toString().trim();
    }

    private void despawnActiveCaravan(boolean announce) {
        if (activeCaravan == null) return;
        for (UUID uuid : activeCaravan.entities) {
            Entity entity = Bukkit.getEntity(uuid);
            if (entity != null && !entity.isDead()) entity.remove();
        }
        activeCaravan = null;
        cancelTask(despawnTask);
        if (announce) broadcast("messages.despawned", Map.of());
    }

    private void cancelTask(BukkitTask task) {
        if (task != null) task.cancel();
    }

    private void openShop(Player player, Caravan caravan) {
        openShop(player, caravan, false);
    }

    private void openShop(Player player, Caravan caravan, boolean coinMode) {
        if (caravan == null || caravan != activeCaravan) {
            player.sendMessage(message("messages.no-active", Map.of()));
            return;
        }
        int rows = Math.max(3, Math.min(6, getConfig().getInt("shop.rows", 4)));
        CaravanMenuHolder holder = new CaravanMenuHolder(caravan.id, caravan.products, coinMode);
        Inventory inventory = Bukkit.createInventory(holder, rows * 9, color(getConfig().getString("shop.title", "&3&lGawhara &8| &bCaravan")));
        holder.setInventory(inventory);
        ItemStack filler = namedItem(Material.CYAN_STAINED_GLASS_PANE, "&8 ", List.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (slot < 9 || slot >= inventory.getSize() - 9) inventory.setItem(slot, filler);
        }
        for (ProductStock product : caravan.products.values()) {
            if (product.slot >= inventory.getSize() - 9) continue;
            inventory.setItem(product.slot, createProductItem(product));
        }
        int infoSlot = inventory.getSize() - 5;
        inventory.setItem(infoSlot - 2, namedItem(Material.BOOK, "&b&lHow to Trade", List.of(
                "&fLeft click: &aBuy 1",
                "&fRight click: &aBuy 8",
                "&fShift + Left: &eSell 1 for Vault",
                "&fShift + Right: &eSell 8 for Vault",
                "&7Stock changes with every visit.")));
        inventory.setItem(infoSlot, namedItem(coinMode ? Material.GOLD_INGOT : Material.EMERALD,
                coinMode ? "&e&lCurrency: Gawhara Coins" : "&a&lCurrency: Vault Cash",
                List.of("&7Click to switch currency mode.", "&fCurrent mode: " + (coinMode ? "&eGC" : "&aVault"),
                        "&7Products with a GC price can be purchased using GC.")));
        player.openInventory(inventory);
    }

    private ItemStack createProductItem(ProductStock product) {
        List<String> lore = new ArrayList<>();
        lore.add(color("&7Stock: &f" + product.stock));
        lore.add(color("&aVault Buy: &f" + economy.format(product.buyPrice) + " &8(per item)"));
        if (product.coinBuyPrice > 0L) lore.add(color("&eGC Buy: &f" + product.coinBuyPrice + " GC &8(per item)"));
        lore.add(color("&cSell: &f" + economy.format(product.sellPrice) + " &8(per item)"));
        lore.add(color("&8 "));
        lore.add(color("&fLeft click: &aBuy 1 using selected currency"));
        lore.add(color("&fRight click: &aBuy 8 using selected currency"));
        lore.add(color("&fShift + Left: &eSell 1"));
        lore.add(color("&fShift + Right: &eSell 8"));
        ItemStack item = new ItemStack(product.material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(product.name));
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack namedItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(name));
            meta.setLore(lore.stream().map(this::color).toList());
            item.setItemMeta(meta);
        }
        return item;
    }

    @EventHandler
    public void onMerchantInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        if (!(event.getRightClicked() instanceof Villager villager) || !isTagged(villager)) return;
        event.setCancelled(true);
        if (activeCaravan != null) openShop(event.getPlayer(), activeCaravan);
    }

    @EventHandler
    public void onTaggedEntityDamage(EntityDamageEvent event) {
        if (isTagged(event.getEntity()) && getConfig().getBoolean("merchant.invulnerable", true)) event.setCancelled(true);
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        // Remove stale tagged merchants left behind by an abrupt shutdown or old plugin reload.
        Bukkit.getScheduler().runTask(this, () -> {
            if (!event.getWorld().isChunkLoaded(event.getChunk().getX(), event.getChunk().getZ())) return;
            for (Entity entity : event.getChunk().getEntities()) {
                if (isTagged(entity) && (activeCaravan == null || !activeCaravan.entities.contains(entity.getUniqueId()))) entity.remove();
            }
        });
    }

    @EventHandler
    public void onShopClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof CaravanMenuHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;
        if (activeCaravan == null || !activeCaravan.id.equals(holder.caravanId)) {
            player.closeInventory();
            player.sendMessage(message("messages.no-active", Map.of()));
            return;
        }
        int currencySlot = event.getView().getTopInventory().getSize() - 5;
        if (event.getRawSlot() == currencySlot) {
            holder.coinMode = !holder.coinMode;
            openShop(player, activeCaravan, holder.coinMode);
            return;
        }
        ProductStock product = holder.products.get(event.getRawSlot());
        if (product == null) return;
        ClickType click = event.getClick();
        int quantity;
        boolean sell;
        if (click == ClickType.LEFT) { quantity = product.amount; sell = false; }
        else if (click == ClickType.RIGHT) { quantity = product.amount * 8; sell = false; }
        else if (click == ClickType.SHIFT_LEFT) { quantity = product.amount; sell = true; }
        else if (click == ClickType.SHIFT_RIGHT) { quantity = product.amount * 8; sell = true; }
        else return;
        if (sell) sellProduct(player, product, quantity);
        else if (holder.coinMode) buyProductCoins(player, product, quantity);
        else buyProduct(player, product, quantity);
        openShop(player, activeCaravan, holder.coinMode);
    }

    private boolean hasCoins(UUID playerId, long amount) {
        var coins = Bukkit.getPluginManager().getPlugin("GawharaCoins");
        if (coins == null || !coins.isEnabled()) return false;
        try {
            return Boolean.TRUE.equals(coins.getClass().getMethod("hasCoins", UUID.class, long.class).invoke(coins, playerId, amount));
        } catch (ReflectiveOperationException ex) {
            getLogger().warning("Could not access GawharaCoins balance API: " + ex.getMessage());
            return false;
        }
    }

    private boolean withdrawCoins(UUID playerId, long amount, String reason) {
        var coins = Bukkit.getPluginManager().getPlugin("GawharaCoins");
        if (coins == null || !coins.isEnabled()) return false;
        try {
            return Boolean.TRUE.equals(coins.getClass().getMethod("withdrawCoins", UUID.class, long.class, String.class)
                    .invoke(coins, playerId, amount, reason));
        } catch (ReflectiveOperationException ex) {
            getLogger().warning("Could not withdraw GawharaCoins: " + ex.getMessage());
            return false;
        }
    }

    private void buyProductCoins(Player player, ProductStock product, int amount) {
        if (product.coinBuyPrice <= 0L) {
            player.sendMessage(color("&cThis product cannot be purchased with GC."));
            return;
        }
        if (product.stock < amount) {
            player.sendMessage(message("shop.messages.no-stock", Map.of()));
            return;
        }
        long price = product.coinBuyPrice * amount;
        if (!hasCoins(player.getUniqueId(), price)) {
            player.sendMessage(color(getConfig().getString("shop.messages.not-enough-coins", "&cYou do not have enough GC.")));
            return;
        }
        if (!withdrawCoins(player.getUniqueId(), price, "Caravan GC purchase: " + product.id)) {
            player.sendMessage(color("&cGC payment failed."));
            return;
        }
        ItemStack reward = new ItemStack(product.material, amount);
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(reward);
        for (ItemStack leftover : leftovers.values()) player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        product.stock -= amount;
        player.sendMessage(color("&aYou bought &f" + amount + "x " + product.name + " &afor &e" + price + " GC&a."));
        if (!leftovers.isEmpty()) player.sendMessage(message("shop.messages.full-inventory", Map.of()));
    }

    private void buyProduct(Player player, ProductStock product, int amount) {
        if (product.stock < amount) {
            player.sendMessage(message("shop.messages.no-stock", Map.of()));
            return;
        }
        double price = money(product.buyPrice * amount);
        if (!economy.has(player, price)) {
            player.sendMessage(message("shop.messages.not-enough-money", Map.of()));
            return;
        }
        EconomyResponse response = economy.withdrawPlayer(player, price);
        if (!response.transactionSuccess()) {
            player.sendMessage(color("&cPayment failed: " + response.errorMessage));
            return;
        }
        ItemStack reward = new ItemStack(product.material, amount);
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(reward);
        for (ItemStack leftover : leftovers.values()) player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        product.stock -= amount;
        Map<String, String> values = Map.of(
                "amount", String.valueOf(amount), "item", color(product.name), "price", economy.format(price));
        player.sendMessage(message("shop.messages.bought", values));
        if (!leftovers.isEmpty()) player.sendMessage(message("shop.messages.full-inventory", Map.of()));
    }

    private void sellProduct(Player player, ProductStock product, int amount) {
        if (product.sellPrice <= 0) {
            player.sendMessage(color("&cThe caravan does not buy this item."));
            return;
        }
        if (countItems(player, product.material) < amount) {
            player.sendMessage(message("shop.messages.not-enough-items", Map.of()));
            return;
        }
        removeItems(player, product.material, amount);
        double price = money(product.sellPrice * amount);
        EconomyResponse response = economy.depositPlayer(player, price);
        if (!response.transactionSuccess()) {
            player.getInventory().addItem(new ItemStack(product.material, amount));
            player.sendMessage(color("&cPayment failed: " + response.errorMessage));
            return;
        }
        Map<String, String> values = Map.of(
                "amount", String.valueOf(amount), "item", color(product.name), "price", economy.format(price));
        player.sendMessage(message("shop.messages.sold", values));
    }

    private int countItems(Player player, Material material) {
        int count = 0;
        ItemStack plainItem = new ItemStack(material);
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && item.isSimilar(plainItem)) count += item.getAmount();
        }
        return count;
    }

    private void removeItems(Player player, Material material, int amount) {
        int remaining = amount;
        ItemStack plainItem = new ItemStack(material);
        for (int slot = 0; slot < player.getInventory().getStorageContents().length && remaining > 0; slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (item == null || !item.isSimilar(plainItem)) continue;
            int take = Math.min(remaining, item.getAmount());
            if (take == item.getAmount()) player.getInventory().setItem(slot, null);
            else {
                ItemStack updated = item.clone();
                updated.setAmount(updated.getAmount() - take);
                player.getInventory().setItem(slot, updated);
            }
            remaining -= take;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("info")) {
            sender.sendMessage(message("messages.info", Map.of(
                    "active", activeCaravan == null ? "No" : "Yes",
                    "worlds", String.join(", ", getConfig().getStringList("settings.enabled-worlds")))));
            sender.sendMessage(message("messages.usage", Map.of()));
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "locate" -> {
                if (activeCaravan == null) sender.sendMessage(message("messages.no-active", Map.of()));
                else {
                    sender.sendMessage(message("messages.located", locationValues(activeCaravan.location)));
                    if (sender instanceof Player player && player.getWorld().equals(activeCaravan.world)) player.setCompassTarget(activeCaravan.location);
                }
                return true;
            }
            case "spawn" -> {
                if (!isAdmin(sender)) return true;
                World world;
                if (args.length >= 2) world = Bukkit.getWorld(args[1]);
                else world = sender instanceof Player player ? player.getWorld() : null;
                if (world == null) {
                    sender.sendMessage(color("&cSpecify a loaded world: /caravans spawn <world>"));
                    return true;
                }
                if (!isWorldAllowed(world.getName())) {
                    sender.sendMessage(message("messages.denied-world", Map.of()));
                    return true;
                }
                if (activeCaravan != null) {
                    sender.sendMessage(message("messages.already-active", Map.of()));
                    return true;
                }
                if (!spawnCaravan(world, true)) sender.sendMessage(message("messages.unsafe-location", Map.of()));
                else {
                    sender.sendMessage(message("messages.spawned-manual", Map.of()));
                    scheduleNextCaravan(false);
                }
                return true;
            }
            case "despawn" -> {
                if (!isAdmin(sender)) return true;
                if (activeCaravan == null) sender.sendMessage(message("messages.no-active", Map.of()));
                else despawnActiveCaravan(true);
                return true;
            }
            case "setcenter" -> {
                if (!isAdmin(sender)) return true;
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(message("messages.player-only", Map.of()));
                    return true;
                }
                String worldName = player.getWorld().getName();
                if (!isWorldAllowed(worldName)) {
                    sender.sendMessage(message("messages.denied-world", Map.of()));
                    return true;
                }
                String path = "world-centers." + worldName;
                getConfig().set(path + ".x", player.getLocation().getX());
                getConfig().set(path + ".z", player.getLocation().getZ());
                saveConfig();
                sender.sendMessage(message("messages.center-set", Map.of("world", worldName)));
                return true;
            }
            case "reload" -> {
                if (!isAdmin(sender)) return true;
                reloadConfig();
                scheduleNextCaravan(false);
                sender.sendMessage(message("messages.reloaded", Map.of()));
                return true;
            }
            default -> {
                sender.sendMessage(message("messages.usage", Map.of()));
                return true;
            }
        }
    }

    private boolean isAdmin(CommandSender sender) {
        if (sender.hasPermission("gawharacaravans.admin")) return true;
        sender.sendMessage(message("messages.admin-only", Map.of()));
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return filter(List.of("info", "locate", "spawn", "despawn", "setcenter", "reload"), args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("spawn") && sender.hasPermission("gawharacaravans.admin")) {
            return filter(getConfig().getStringList("settings.enabled-worlds"), args[1]);
        }
        return Collections.emptyList();
    }

    private List<String> filter(List<String> options, String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        return options.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(lower)).sorted(Comparator.naturalOrder()).collect(Collectors.toList());
    }

    private Map<String, String> locationValues(Location location) {
        return Map.of(
                "world", location.getWorld().getName(),
                "x", String.valueOf(location.getBlockX()),
                "y", String.valueOf(location.getBlockY()),
                "z", String.valueOf(location.getBlockZ()));
    }

    private void broadcast(String path, Map<String, String> values) {
        Bukkit.broadcastMessage(message(path, values));
    }

    private String message(String path, Map<String, String> values) {
        String prefix = getConfig().getString("messages.prefix", "&8[&bCaravan&8] &r");
        String body = getConfig().getString(path, "&cMissing message: " + path);
        String output = prefix + body;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            // Canonical format: %x%. Also accept the old malformed format (%x, %y, %z)
            // so existing config.yml files keep working after upgrading.
            output = output.replace("%" + key + "%", value);
            Pattern legacyToken = Pattern.compile("%" + Pattern.quote(key) + "(?![A-Za-z0-9_])");
            output = legacyToken.matcher(output).replaceAll(Matcher.quoteReplacement(value));
        }
        return color(output);
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    private static final class Caravan {
        private final UUID id;
        private final World world;
        private final Location location;
        private final List<UUID> entities = new ArrayList<>();
        private final Map<Integer, ProductStock> products = new LinkedHashMap<>();
        private Caravan(UUID id, World world, Location location) {
            this.id = id;
            this.world = world;
            this.location = location.clone();
        }
    }

    private static final class ProductStock {
        private final String id;
        private final Material material;
        private final String name;
        private final int slot;
        private final int amount;
        private final double buyPrice;
        private final double sellPrice;
        private final long coinBuyPrice;
        private int stock;
        private ProductStock(String id, Material material, String name, int slot, int amount, double buyPrice, double sellPrice, long coinBuyPrice, int stock) {
            this.id = id;
            this.material = material;
            this.name = name;
            this.slot = slot;
            this.amount = amount;
            this.buyPrice = buyPrice;
            this.sellPrice = sellPrice;
            this.coinBuyPrice = coinBuyPrice;
            this.stock = stock;
        }
    }

    private static final class CaravanMenuHolder implements InventoryHolder {
        private final UUID caravanId;
        private final Map<Integer, ProductStock> products;
        private Inventory inventory;
        private boolean coinMode;
        private CaravanMenuHolder(UUID caravanId, Map<Integer, ProductStock> products, boolean coinMode) {
            this.caravanId = caravanId;
            this.products = products;
            this.coinMode = coinMode;
        }
        private void setInventory(Inventory inventory) { this.inventory = inventory; }
        @Override
        public Inventory getInventory() { return inventory; }
    }
}
