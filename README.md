# ShopGUIPlus DynamicPricing

Configurable market pricing plugin for ShopGUIPlus.

## Current status

**Pre-release integration build — do not deploy to a live economy without staging tests.**
The Maven CI builds a plugin jar using the published ShopGUIPlus **API stub source**;
that confirms compilation, not compatibility with a specific proprietary runtime JAR.

### Supported market types

- `STATIC`: fixed prices, unlimited inventory, no tracked demand.
- `DYNAMIC`: demand-dependent price growth/decay, without finite stock.
- `STOCK`: prices depend on finite stock.
- `STATIC_STOCK`: fixed prices with finite stock.
- `RECIPE`: derives base prices from configured ingredient shop listings.
- `LINK`: uses another shop listing as a shared market (configured in the YAML, resolved before pricing).

Global policy is set at `pricing.defaults`; overrides under `shops.<shopId>`
and `shops.<shopId>.items.<itemId>`. Each configured field inherits the nearest
parent value unless explicitly set. Default base prices come from ShopGUIPlus.
Sell-only / buy-only listings retain their restrictions.

### Custom pricing equations

Optional `buy-formula` and `sell-formula` settings override the built-in price
equations for a shop or individual item. Both must be provided. The formulas are
compiled with a restricted expression parser (not JavaScript), supporting
`+`, `-`, `*`, `/`, `^` and functions `min`, `max`, `pow`, `exp`, `ln`,
`sqrt`, `abs`, `clamp`. Variables include `baseBuy`, `baseSell`, `demand`,
`stock`, `target`, `buyImpact`, `sellImpact`, `stockSensitivity`,
`stockRatio`, `quantity`, `unitIndex`, and `side`.
Pricing limits and the minimum buy/sell spread still apply.

```yaml
shops:
  ores:
    type: DYNAMIC
    maximum-multiplier: unlimited
    absolute-maximum: unlimited
    buy-formula: "baseBuy * exp(demand * buyImpact)"
    sell-formula: "baseSell * exp(demand * sellImpact)"
```

### Price previews through PlaceholderAPI

When PlaceholderAPI is installed, the plugin registers an internal expansion:
`%dynamicpricing_buy_ores:diamond%`,
`%dynamicpricing_sell_ores:diamond#64%`,
`%dynamicpricing_stock_ores:diamond%` and
`%dynamicpricing_demand_ores:diamond%`.
These are read-only quote previews, not trade commits.
Support for these placeholders in ShopGUIPlus's built-in lore is dependent on
that plugin's PlaceholderAPI handling; staging validation is required.

### Transaction controls

Per-player buy/sell unit limits are available at shop or item scope:
`limits.buy.units`, `limits.buy.period-minutes`,
`limits.sell.units` and `limits.sell.period-minutes`.
Failed trades never increment the counters; `SELL` and `SELL_ALL` share
a quota. The counters persist through a clean server restart.

`currency-decimals` is configurable per listing (0–6), with purchases rounded
up and sell payouts rounded down. `tax.buy-percent` and
`tax.sell-percent` are optional checkout adjustments (money sinks).
They **do not** deposit proceeds into a treasury account.

`RECIPE` currently uses explicit referenced shop ingredients,
`recipe.output-count`, `recipe.markup`, and `recipe.sell-ratio`.
Vanilla recipe scanning, recipe stock dependencies, multi-recipe choice and
derived dynamic ingredient market prices require further implementation.

Only explicitly configured shops/items are intercepted by default.
`pricing.manage-all-shops: true` intentionally enables interception for
all ShopGUIPlus listings.

The four optional bounds are `minimum-multiplier`, `maximum-multiplier`,
`absolute-minimum`, and `absolute-maximum`. To remove a globally inherited cap
set it to the string `unlimited` in a shop or item configuration. Omission from
global defaults leaves that bound unset. The *shipped* defaults are deliberately
conservative. Unbounded prices fail closed upon numeric overflow and at the safe
transaction amount limit; "unbounded" is not unlimited floating-point precision.

### Running in staging

1. Install ShopGUIPlus in a test Paper server.
2. Download the `DynamicPricing-plugin` JAR from a successful GitHub Actions run.
3. Put the JAR in `plugins/`, restart, and configure `plugins/DynamicPricing/config.yml`.
4. Configure one small stock-controlled shop, one uncapped dynamic shop, and one fixed shop.
5. Check BUY, SELL, SELL_ALL, large batches, insufficient stock, modified prices,
   cancelled purchases, full inventory, saving/restart, and disabled shop directions.
6. Compare displayed prices to charged amounts. ShopGUIPlus price preview and
   transaction price must agree before this can be considered production-ready.

The `pricing.max-transaction-quantity` setting limits expensive progressive
trade simulations (default 4096). The numeric currency precision of the
current runtime bridge is fixed at two decimal places; other economy precisions
require further adapter work.

Admin commands: `/dynamicpricing reload` and `/dynamicpricing save`;
permission `dynamicpricing.admin` (default OP).

## Model

`multiplier = exp(buyImpact * max(demand, 0) + sellImpact * min(demand, 0)
+ stockSensitivity * (stockTarget - currentStock) / stockTarget)`,
with any configured minimum/maximum multiplier applied prior to exponentiation.

Market recovery halves demand each `recovery-half-life-hours`. Every unit of a
bulk purchase/sale is quoted progressively against the evolving market. Trades
update market data only on successful ShopGUIPlus post-transaction results.
State is saved to `market-state.yml` after confirmed transactions.

**Not complete:** live ShopGUIPlus/Paper integration and exploit tests, transaction
recovery under disk failures, automatic vanilla recipe discovery, inflation and
treasury routing, transaction history, GUI synchronisation, cache/restock
scheduling, MySQL storage, multi-server persistence, and specialised
VaultUnlocked currency routing. The plugin
lets ShopGUIPlus continue handling currencies; it does not implement its own economy.

This is original implementation informed by the publicly documented behaviour of
[DynaShop](https://github.com/Tylwen/ShopGUIPlus-DynaShop), not a source-code copy.
