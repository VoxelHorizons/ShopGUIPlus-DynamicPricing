# ShopGUIPlus DynamicPricing

Independent, configurable dynamic pricing foundation for VoxelHorizons.

**Status: initial pricing-engine foundation only. Not a deployable Bukkit plugin yet.**
The present Maven module deliberately has no runtime dependency on Bukkit or ShopGUIPlus.
Run `mvn test` with JDK 17+.

## Model

For net demand `D`, stock `S`, stock target `T`, buy coefficient `a`, sell coefficient `b`, stock sensitivity `k`:

```text
demandImpact = D >= 0 ? a * D : b * D
stockImpact  = k * (T - S) / T
multiplier   = clamp(exp(clamp(demandImpact + stockImpact, -100, 100)), minMultiplier, maxMultiplier)
buy          = clamp(baseBuy * multiplier, absoluteMinimum, absoluteMaximum)
sell         = min(clamp(baseSell * multiplier, absoluteMinimum, absoluteMaximum), buy * (1 - minimumSpread))
```

Each unit of a bulk transaction is priced against the new market state, then summed. A buy removes stock and adds demand; a sale increases stock and subtracts demand. Demand decays toward zero with a configurable half-life (24 hours default). **The quoted state must only be committed after an actual successful transaction**; pricing without execution must never change market state. Do not call quote outside an item-level transaction lock when committing. Apply currency rounding only after summing the transaction.

## Design goals and remaining integration

1. Implement ShopGUIPlus price provider/shop type or supported API hook, based on a verified compatible ShopGUIPlus version. Never silently inject GUI display prices that differ from transaction prices.
2. Atomic persistent state per shop/item/currency; rollback on cancelled/failed transactions, with durable writes and restart recovery.
3. YAML loading + validation, global/shop/item overrides, and independently configurable buy/sell equations with safe whitelist expression parser.
4. Quote API and explicit commerce API (preview vs commit), operator commands, test fixtures for farming/hoarding, configurable restock and monetary sinks.
5. VaultUnlocked currency routing (cosmetic and main currencies) after its API is verified; do not assume Vault's single-currency behavior covers both.
6. Dedicated presets for farmables, mined resources, rare cosmetics and unlimited-stock items. Simulate them before declaring economic defaults balanced.
7. Verify modern Paper compatibility and add CI-produced plugin JAR only after Bukkit entrypoint is implemented.

The default spread is at least 10% and base sell is 70% of base buy in the convenience factory. No claims of universal optimality are made.

## Reference

Architecture and feature inspiration: [ShopGUIPlus-DynaShop](https://github.com/Tylwen/ShopGUIPlus-DynaShop) (AGPL-3.0). This repository uses an independently written pricing engine; no DynaShop source files have been copied.
