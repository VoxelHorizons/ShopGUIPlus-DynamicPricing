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

Global policy is set at `pricing.defaults`; overrides under `shops.<shopId>`
and `shops.<shopId>.items.<itemId>`. Each configured field inherits the nearest
parent value unless explicitly set. Default base prices come from ShopGUIPlus.
Sell-only / buy-only listings retain their restrictions.

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

**Not complete:** real-runtime testing, transaction/recovery hardening, explicit
currency precision rules, safe formula expression language, recipe/linked modes,
multi-server persistence and optional VaultUnlocked-specific behaviour. The plugin
lets ShopGUIPlus continue handling currencies; it does not implement its own economy.

This is original implementation informed by the publicly documented behaviour of
[DynaShop](https://github.com/Tylwen/ShopGUIPlus-DynaShop), not a source-code copy.
