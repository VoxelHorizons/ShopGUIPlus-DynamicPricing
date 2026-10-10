package com.voxelhorizons.dynamicpricing;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Level;
import net.brcdev.shopgui.event.*;
import net.brcdev.shopgui.shop.ShopTransactionResult;
import net.brcdev.shopgui.shop.item.ShopItem;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.command.*;
import org.bukkit.event.*;
import org.bukkit.plugin.java.JavaPlugin;

/** Transaction-price bridge. Only successful checkout updates demand. */
public final class DynamicPricingPlugin extends JavaPlugin implements Listener, CommandExecutor {
    private final PricingEngine calculator = new PricingEngine();
    private final Map<String, Market> markets = new HashMap<>();
    private final Map<String, FormulaPricing> formulas = new HashMap<>();
    private final Map<UUID, Pending> pending = new HashMap<>();
    private record Market(PricingEngine.State state, long time) {}
    private record Pending(String key, int amount, String action, double price, PricingEngine.State resulting) {}
    private File file;
    private DynamicPricingPlaceholders expansion;
    @Override public void onEnable() {
        saveDefaultConfig();
        file = new File(getDataFolder(), "market-state.yml");
        loadMarket();
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("dynamicpricing")).setExecutor(this);
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            expansion = new DynamicPricingPlaceholders(this);
            if (!expansion.register()) getLogger().warning("PlaceholderAPI expansion failed to register");
        }
    }
    @Override public void onDisable() {
        pending.clear();
        if (expansion != null) expansion.unregister();
        try { saveMarket(); } catch(IOException ex) { getLogger().log(Level.SEVERE, "Save failed", ex); }
    }
    private static String id(ShopItem item) { return item.getShop().getId() + ":" + item.getId(); }
    private Object setting(ShopItem item, String name, Object fallback) {
        String shop = "shops." + item.getShop().getId();
        String specific = shop + ".items." + item.getId() + "." + name;
        if (getConfig().contains(specific)) return getConfig().get(specific);
        if (getConfig().contains(shop + "." + name)) return getConfig().get(shop + "." + name);
        String global = "pricing.defaults." + name;
        return getConfig().contains(global) ? getConfig().get(global) : fallback;
    }
    private double number(ShopItem item, String name, double fallback) {
        Object value = setting(item,name,fallback);
        if (!(value instanceof Number n)) throw new IllegalArgumentException("Expected number: " + name);
        return n.doubleValue();
    }
    private Double optional(ShopItem item, String name, Double fallback) {
        Object value = setting(item,name,fallback);
        if (value == null || "null".equalsIgnoreCase(value.toString()) ||
                "unlimited".equalsIgnoreCase(value.toString()) || "none".equalsIgnoreCase(value.toString()))
            return null;
        if (!(value instanceof Number n)) throw new IllegalArgumentException("Expected number: " + name);
        return n.doubleValue();
    }
    private boolean managed(ShopItem item) {
        if (getConfig().getBoolean("pricing.manage-all-shops", false)) return true;
        String shopPath = "shops." + item.getShop().getId();
        String itemPath = shopPath + ".items." + item.getId();
        if (getConfig().contains(itemPath + ".enabled"))
            return getConfig().getBoolean(itemPath + ".enabled");
        if (getConfig().contains(shopPath + ".enabled"))
            return getConfig().getBoolean(shopPath + ".enabled");
        return getConfig().contains(itemPath + ".type") || getConfig().contains(shopPath + ".type");
    }
    private ShopPricing policy(ShopItem item) {
        var type = ShopPricing.Mode.valueOf(String.valueOf(setting(item, "type", "DYNAMIC"))
                .toUpperCase(Locale.ROOT));
        // Disabled buy/sell directions are filtered before policy resolution.
        // Use a synthetic base for the unavailable side so sell-only and buy-only listings remain valid.
        double originalBuy = item.getBuyPrice(), originalSell = item.getSellPrice();
        double buy = number(item,"base-buy",originalBuy > 0 ? originalBuy : Math.max(.01, originalSell / .7));
        double sell = number(item,"base-sell",originalSell > 0 ? originalSell : Math.max(.01, buy * .7));
        var params = new PricingEngine.Parameters(buy, sell,
            optional(item,"minimum-multiplier",null), optional(item,"maximum-multiplier",null),
            number(item,"buy-impact-per-item",.002), number(item,"sell-impact-per-item",.0015),
            number(item,"stock-sensitivity",.2), number(item,"stock-target",1000),
            number(item,"recovery-half-life-hours",24), number(item,"minimum-spread",.1),
            optional(item,"absolute-minimum",null), optional(item,"absolute-maximum",null));
        return new ShopPricing(type,params);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void before(ShopPreTransactionEvent event) {
        UUID player = event.getPlayer().getUniqueId();
        pending.remove(player);
        ShopItem item = event.getShopItem();
        if (item == null || !managed(item)) return;
        // Preserve ShopGUIPlus disabled/free item behaviour; never make unavailable sides tradeable.
        if (event.getShopAction().name().equals("BUY") && item.getBuyPrice() <= 0) return;
        if (!event.getShopAction().name().equals("BUY") && item.getSellPrice() <= 0) return;
        try {
            String key = id(item);
            ShopPricing policy = policy(item);
            PricingEngine.Parameters params = policy.parameters();
            var current = markets.getOrDefault(key,
                    new Market(new PricingEngine.State(0,params.stockTarget()),System.currentTimeMillis()));
            double hours = Math.max(0,System.currentTimeMillis()-current.time())/3600000.0;
            var recovered = calculator.recover(current.state(),params,hours);
            var side = event.getShopAction().name().equals("BUY") ?
                PricingEngine.Side.BUY : PricingEngine.Side.SELL;
            int maximum = getConfig().getInt("pricing.max-transaction-quantity",4096);
            if (event.getAmount() <= 0 || maximum <= 0 || event.getAmount() > maximum)
                throw new IllegalArgumentException("Trade quantity exceeds configured limit");
            String buyFormula = String.valueOf(setting(item,"buy-formula",""));
            String sellFormula = String.valueOf(setting(item,"sell-formula",""));
            PricingEngine.Quote quote;
            if (!buyFormula.isBlank() || !sellFormula.isBlank()) {
                if (buyFormula.isBlank() || sellFormula.isBlank())
                    throw new IllegalArgumentException("Both buy-formula and sell-formula are required");
                String formulaKey = buyFormula + "\\n" + sellFormula;
                FormulaPricing custom = formulas.computeIfAbsent(formulaKey,
                        k -> new FormulaPricing(buyFormula,sellFormula));
                quote = custom.quote(params,recovered,policy.mode(),side,event.getAmount());
            } else {
                quote = policy.quote(calculator,recovered,side,event.getAmount());
            }
            if (!Double.isFinite(quote.total()) || quote.total() <= 0 || quote.total() > 9.0e15)
                throw new ArithmeticException("Price cannot be represented safely");
            double price = Math.round(quote.total()*100.0)/100.0;
            if (price < .01) throw new ArithmeticException("Dynamic price rounds below one currency cent");
            event.setPrice(price);
            // Commit the recovered market state on success, not on price preview.
            pending.put(player,new Pending(key,event.getAmount(),event.getShopAction().name(),
                    price,quote.after()));
        } catch (RuntimeException ex) {
            event.setCancelled(true); // Fail closed rather than allow zero-price transactions.
            getLogger().warning("Blocked transaction for " + id(item) + ": " + ex.getMessage());
        }
    }
    @EventHandler(priority=EventPriority.MONITOR)
    public void after(ShopPostTransactionEvent event) {
        ShopTransactionResult result = event.getResult();
        if (result == null || result.getPlayer() == null) return;
        Pending candidate = pending.remove(result.getPlayer().getUniqueId());
        if (candidate == null || result.getResult() != ShopTransactionResult.ShopTransactionResultType.SUCCESS
                || result.getShopItem() == null || !candidate.key().equals(id(result.getShopItem()))
                || candidate.amount()!=result.getAmount()
                || !candidate.action().equals(result.getShopAction().name())
                || Math.abs(candidate.price()-result.getPrice()) > .011) return;
        markets.put(candidate.key(),new Market(candidate.resulting(),System.currentTimeMillis()));
        try { saveMarket(); }
        catch(IOException ex) { getLogger().log(Level.SEVERE,"Failed to persist completed trade",ex); }
    }
    /** Read-only preview for PlaceholderAPI; a quote never mutates or persists market state. */
    public String preview(String key, String field, int count) {
        try {
            if (count <= 0 || count > getConfig().getInt("pricing.max-transaction-quantity",4096))
                return null;
            int separator = key.indexOf(':');
            if (separator <= 0 || separator == key.length()-1) return null;
            var shop = net.brcdev.shopgui.ShopGuiPlusApi.getShop(key.substring(0,separator));
            if (shop == null) return null;
            ShopItem item = shop.getShopItem(key.substring(separator+1));
            if (item == null || !managed(item)) return null;
            var policy = policy(item);
            var params = policy.parameters();
            var original = markets.getOrDefault(id(item),
                    new Market(new PricingEngine.State(0,params.stockTarget()),System.currentTimeMillis()));
            var state = calculator.recover(original.state(),params,
                    Math.max(0,System.currentTimeMillis()-original.time())/3600000.0);
            if ("stock".equals(field)) return String.format(Locale.ROOT,"%.0f",state.stock());
            if ("demand".equals(field)) return String.format(Locale.ROOT,"%.2f",state.netDemand());
            PricingEngine.Side side = "buy".equals(field) ? PricingEngine.Side.BUY :
                    "sell".equals(field) ? PricingEngine.Side.SELL : null;
            if (side == null) return null;
            if (side == PricingEngine.Side.BUY && item.getBuyPrice() <= 0) return "unavailable";
            if (side == PricingEngine.Side.SELL && item.getSellPrice() <= 0) return "unavailable";
            String buyFormula=String.valueOf(setting(item,"buy-formula",""));
            String sellFormula=String.valueOf(setting(item,"sell-formula",""));
            PricingEngine.Quote quoted;
            if (!buyFormula.isBlank() || !sellFormula.isBlank()) {
                if (buyFormula.isBlank() || sellFormula.isBlank()) return null;
                String formulaKey=buyFormula+"\\n"+sellFormula;
                FormulaPricing formula=formulas.computeIfAbsent(formulaKey,
                        k->new FormulaPricing(buyFormula,sellFormula));
                quoted=formula.quote(params,state,policy.mode(),side,count);
            } else {
                quoted=policy.quote(calculator,state,side,count);
            }
            if (!Double.isFinite(quoted.total()) || quoted.total() <= 0) return "unavailable";
            return String.format(Locale.ROOT,"%.2f",quoted.total());
        } catch (RuntimeException e) {
            return "unavailable";
        }
    }

    private void loadMarket() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection entries = yaml.getConfigurationSection("markets");
        if(entries == null) return;
        for(String key : entries.getKeys(false)) {
            String p = "markets."+key+".";
            try {
                markets.put(key,new Market(new PricingEngine.State(yaml.getDouble(p+"demand"),
                    yaml.getDouble(p+"stock")),yaml.getLong(p+"time",System.currentTimeMillis())));
            } catch(RuntimeException ex) { getLogger().warning("Ignored invalid market: "+key); }
        }
    }
    private void saveMarket() throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        for(var entry:markets.entrySet()) {
            String p = "markets."+entry.getKey()+".";
            yaml.set(p+"demand",entry.getValue().state().netDemand());
            yaml.set(p+"stock",entry.getValue().state().stock());
            yaml.set(p+"time",entry.getValue().time());
        }
        File temp = new File(getDataFolder(),"market-state.yml.tmp");
        yaml.save(temp);
        try { Files.move(temp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
        catch(AtomicMoveNotSupportedException ex) {
            Files.move(temp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING);
        }
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if(!sender.hasPermission("dynamicpricing.admin")) return true;
        if(args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig(); pending.clear(); formulas.clear(); sender.sendMessage("DynamicPricing reloaded."); return true;
        }
        if(args.length == 1 && args[0].equalsIgnoreCase("save")) {
            try { saveMarket(); sender.sendMessage("State saved."); }
            catch(IOException ex) { sender.sendMessage("Save failed. Check server log."); }
            return true;
        }
        sender.sendMessage("/dynamicpricing <reload|save>");
        return true;
    }
}
