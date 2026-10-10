package com.voxelhorizons.dynamicpricing;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
    private final Map<String, Usage> limitUsage = new HashMap<>();
    private final Map<String, FormulaPricing> formulas = new HashMap<>();
    private final Map<UUID, Pending> pending = new HashMap<>();
    private record Market(PricingEngine.State state, long time) {}
    private record Usage(long windowStart, long units) {}
    private record Pending(String sourceKey, String marketKey, String limitKey, int amount, String action,
                           double price, double tolerance,
                           PricingEngine.State resulting) {}
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
        return getConfig().isConfigurationSection(itemPath)
                || getConfig().isConfigurationSection(shopPath);
    }
    /** Resolve LINK policies without allowing recursive references or cross-reference cycles. */
    private ShopItem resolveMarket(ShopItem source) {
        ShopItem current = source;
        Set<String> visited = new HashSet<>();
        for (int depth = 0; depth < 16; depth++) {
            if (!visited.add(id(current))) throw new IllegalArgumentException("Cyclic linked market: " + id(current));
            String mode = String.valueOf(setting(current,"type","DYNAMIC"));
            if (!"LINK".equalsIgnoreCase(mode)) return current;
            Object raw = setting(current,"link","");
            String ref = String.valueOf(raw);
            int separator = ref.indexOf(':');
            if (separator <= 0 || separator == ref.length()-1)
                throw new IllegalArgumentException("Invalid linked market target: " + ref);
            var shop = net.brcdev.shopgui.ShopGuiPlusApi.getShop(ref.substring(0,separator));
            if (shop == null) throw new IllegalArgumentException("Linked shop not found: " + ref);
            ShopItem item = shop.getShopItem(ref.substring(separator+1));
            if (item == null) throw new IllegalArgumentException("Linked item not found: " + ref);
            current = item;
        }
        throw new IllegalArgumentException("Linked market chain exceeds 16 entries");
    }

    private double recipeCost(ShopItem item, Set<String> visited, int depth) {
        if (depth > 12 || !visited.add(id(item)))
            throw new IllegalArgumentException("Recursive or excessively deep crafting recipe");
        try {
            String type=String.valueOf(setting(item,"type","STATIC"));
            if (!"RECIPE".equalsIgnoreCase(type)) {
                if ("LINK".equalsIgnoreCase(type)) return recipeCost(resolveMarket(item),visited,depth+1);
                double price=number(item,"base-buy",item.getBuyPrice());
                if (!Double.isFinite(price) || price<=0)
                    throw new IllegalArgumentException("Ingredient has no buy price: "+id(item));
                return price;
            }
            String path="shops."+item.getShop().getId()+".items."+item.getId()+".recipe.ingredients";
            ConfigurationSection ingredients=getConfig().getConfigurationSection(path);
            if (ingredients==null || ingredients.getKeys(false).isEmpty())
                throw new IllegalArgumentException("Recipe ingredients missing: "+id(item));
            double total=0;
            for(String ref:ingredients.getKeys(false)) {
                int sep=ref.indexOf(':');
                if (sep <= 0 || sep == ref.length()-1)
                    throw new IllegalArgumentException("Bad recipe ingredient: "+ref);
                var shop=net.brcdev.shopgui.ShopGuiPlusApi.getShop(ref.substring(0,sep));
                if(shop==null) throw new IllegalArgumentException("Recipe shop not found: "+ref);
                ShopItem component=shop.getShopItem(ref.substring(sep+1));
                if(component==null) throw new IllegalArgumentException("Recipe item not found: "+ref);
                double count=ingredients.getDouble(ref);
                if(!Double.isFinite(count) || count<=0 || count>1024)
                    throw new IllegalArgumentException("Bad ingredient quantity: "+ref);
                total+=recipeCost(component,visited,depth+1)*count;
                if(!Double.isFinite(total)) throw new ArithmeticException("Recipe price overflow");
            }
            double yield=number(item,"recipe.output-count",1);
            double markup=number(item,"recipe.markup",1.25);
            if(!Double.isFinite(yield) || yield<=0 || !Double.isFinite(markup) || markup<=0)
                throw new IllegalArgumentException("Invalid recipe output or markup");
            double price=total*markup/yield;
            if(!Double.isFinite(price) || price<=0) throw new ArithmeticException("Invalid recipe price");
            return price;
        } finally {visited.remove(id(item));}
    }
    private ShopPricing policy(ShopItem item) {
        var type = ShopPricing.Mode.valueOf(String.valueOf(setting(item, "type", "DYNAMIC"))
                .toUpperCase(Locale.ROOT));
        // Disabled buy/sell directions are filtered before policy resolution.
        // Use a synthetic base for the unavailable side so sell-only and buy-only listings remain valid.
        double originalBuy = item.getBuyPrice(), originalSell = item.getSellPrice();
        double buy = number(item,"base-buy",originalBuy > 0 ? originalBuy : Math.max(.01, originalSell / .7));
        double sell = number(item,"base-sell",originalSell > 0 ? originalSell : Math.max(.01, buy * .7));
        if(type == ShopPricing.Mode.RECIPE) {
            buy=recipeCost(item,new HashSet<>(),0);
            double ratio=number(item,"recipe.sell-ratio",0.60);
            if(!Double.isFinite(ratio) || ratio<=0 || ratio>1)
                throw new IllegalArgumentException("recipe.sell-ratio must be in (0,1]");
            sell=buy*ratio;
        }
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
            String sourceKey = id(item);
            ShopItem marketItem = resolveMarket(item);
            String key = id(marketItem);
            ShopPricing policy = policy(marketItem);
            PricingEngine.Parameters params = policy.parameters();
            var current = markets.getOrDefault(key,
                    new Market(new PricingEngine.State(0,params.stockTarget()),System.currentTimeMillis()));
            double hours = Math.max(0,System.currentTimeMillis()-current.time())/3600000.0;
            var recovered = calculator.recover(current.state(),params,hours);
            var side = event.getShopAction().name().equals("BUY") ?
                PricingEngine.Side.BUY : PricingEngine.Side.SELL;
            checkTradeLimit(item, player, event.getShopAction().name(), event.getAmount());
            int maximum = getConfig().getInt("pricing.max-transaction-quantity",4096);
            if (event.getAmount() <= 0 || maximum <= 0 || event.getAmount() > maximum)
                throw new IllegalArgumentException("Trade quantity exceeds configured limit");
            String buyFormula = String.valueOf(setting(marketItem,"buy-formula",""));
            String sellFormula = String.valueOf(setting(marketItem,"sell-formula",""));
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
            int decimals = (int) number(item, "currency-decimals", 2);
            if (decimals < 0 || decimals > 6) throw new IllegalArgumentException("Currency decimals must be 0..6");
            double taxable = taxAdjustedTotal(item, quote.total(), side);
            double price = roundTotal(taxable, side, decimals);
            if (price <= 0) throw new ArithmeticException("Dynamic price rounds to zero");
            event.setPrice(price);
            // Commit the recovered market state on success, not on price preview.
            pending.put(player,new Pending(sourceKey,key,limitKey(player,item,event.getShopAction().name()),
                    event.getAmount(),event.getShopAction().name(),
                    price,Math.pow(10,-decimals)/100.0,quote.after()));
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
                || result.getShopItem() == null || !candidate.sourceKey().equals(id(result.getShopItem()))
                || candidate.amount()!=result.getAmount()
                || !candidate.action().equals(result.getShopAction().name())
                || Math.abs(candidate.price()-result.getPrice()) > candidate.tolerance()) return;
        markets.put(candidate.marketKey(),new Market(candidate.resulting(),System.currentTimeMillis()));
        recordTradeLimit(result.getShopItem(), candidate.limitKey(), candidate.action(), candidate.amount());
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
            ShopItem marketItem = resolveMarket(item);
            var policy = policy(marketItem);
            var params = policy.parameters();
            var original = markets.getOrDefault(id(marketItem),
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
            String buyFormula=String.valueOf(setting(marketItem,"buy-formula",""));
            String sellFormula=String.valueOf(setting(marketItem,"sell-formula",""));
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
            int decimals = (int) number(item,"currency-decimals",2);
            if (decimals < 0 || decimals > 6) return null;
            double rounded = roundTotal(taxAdjustedTotal(item,quoted.total(),side),side,decimals);
            return String.format(Locale.ROOT,"%."+decimals+"f",rounded);
        } catch (RuntimeException e) {
            return "unavailable";
        }
    }

    /** Percentage-based checkout fee/sink; no external economy payout is attempted. */
    private double taxAdjustedTotal(ShopItem item, double amount, PricingEngine.Side side) {
        double rate=number(item,side==PricingEngine.Side.BUY?"tax.buy-percent":"tax.sell-percent",0);
        if (!Double.isFinite(rate) || rate < 0 || rate > 100)
            throw new IllegalArgumentException("Tax percentage must be between 0 and 100");
        double adjusted=amount * (side==PricingEngine.Side.BUY ? 1+rate/100 : 1-rate/100);
        if (!Double.isFinite(adjusted) || adjusted <= 0)
            throw new ArithmeticException("Tax-adjusted price is zero or invalid");
        return adjusted;
    }

    private static double roundTotal(double total, PricingEngine.Side side, int decimals) {
        if (!Double.isFinite(total) || total <= 0) throw new ArithmeticException("Invalid price");
        return BigDecimal.valueOf(total).setScale(decimals,
                side == PricingEngine.Side.BUY ? RoundingMode.CEILING : RoundingMode.DOWN).doubleValue();
    }

    private String limitKey(UUID player, ShopItem item, String action) {
        return player.toString() + "|" + id(item) + "|" + (action.equals("BUY") ? "BUY" : "SELL");
    }

    private void checkTradeLimit(ShopItem item, UUID player, String action, int amount) {
        String mode = action.equals("BUY") ? "buy" : "sell";
        int max = (int) number(item,"limits." + mode + ".units",0);
        if (max <= 0) return;
        int minutes = (int) number(item,"limits." + mode + ".period-minutes",60);
        if (minutes <= 0 || minutes > 525600) throw new IllegalArgumentException("Invalid limit period");
        long duration = minutes * 60_000L;
        String key = limitKey(player,item,action);
        Usage usage = limitUsage.get(key);
        long now = System.currentTimeMillis();
        long already = usage == null || now < usage.windowStart() || now - usage.windowStart() >= duration
                ? 0 : usage.units();
        if (amount <= 0 || amount > max - already)
            throw new IllegalArgumentException("Shop transaction limit exceeded");
    }
    private void recordTradeLimit(ShopItem item, String key, String action, int amount) {
        String mode = action.equals("BUY") ? "buy" : "sell";
        int max = (int) number(item,"limits." + mode + ".units",0);
        if (max <= 0) return;
        int minutes = (int) number(item,"limits." + mode + ".period-minutes",60);
        long now = System.currentTimeMillis(), duration = minutes * 60_000L;
        Usage usage = limitUsage.get(key);
        if (usage == null || now < usage.windowStart() || now - usage.windowStart() >= duration)
            limitUsage.put(key,new Usage(now,amount));
        else limitUsage.put(key,new Usage(usage.windowStart(),usage.units()+amount));
    }

    private void loadMarket() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        limitUsage.clear();
        ConfigurationSection limits = yaml.getConfigurationSection("limits");
        if (limits != null) for (String key : limits.getKeys(false)) {
            String p = "limits." + key + ".";
            long time=yaml.getLong(p+"window"), units=yaml.getLong(p+"units");
            if (time>0 && units>=0 && units <= Integer.MAX_VALUE)
                limitUsage.put(key,new Usage(time,units));
        }
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
        for(var entry:limitUsage.entrySet()) {
            String p = "limits." + entry.getKey() + ".";
            yaml.set(p+"window",entry.getValue().windowStart());
            yaml.set(p+"units",entry.getValue().units());
        }
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
