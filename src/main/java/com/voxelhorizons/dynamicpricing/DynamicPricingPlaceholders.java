package com.voxelhorizons.dynamicpricing;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

/**
 * %dynamicpricing_buy_shopId:itemId%
 * %dynamicpricing_sell_shopId:itemId#64%
 * %dynamicpricing_stock_shopId:itemId%
 * %dynamicpricing_demand_shopId:itemId%
 */
public final class DynamicPricingPlaceholders extends PlaceholderExpansion {
    private final DynamicPricingPlugin plugin;

    public DynamicPricingPlaceholders(DynamicPricingPlugin plugin) {
        this.plugin=plugin;
    }
    @Override public String getIdentifier() { return "dynamicpricing"; }
    @Override public String getAuthor() { return "VoxelHorizons"; }
    @Override public String getVersion() { return plugin.getDescription().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override public String onRequest(OfflinePlayer player, String params) {
        int underscore=params.indexOf('_');
        if (underscore<=0 || underscore>=params.length()-1) return null;
        String action=params.substring(0,underscore).toLowerCase(java.util.Locale.ROOT);
        String reference=params.substring(underscore+1);
        int amount=1;
        int hash=reference.lastIndexOf('#');
        if(hash>=0) {
            try {amount=Integer.parseInt(reference.substring(hash+1));}
            catch(NumberFormatException ex) {return null;}
            reference=reference.substring(0,hash);
        }
        if (!(action.equals("buy") || action.equals("sell") ||
                action.equals("stock") || action.equals("demand"))) return null;
        return plugin.preview(reference,action,amount);
    }
}
