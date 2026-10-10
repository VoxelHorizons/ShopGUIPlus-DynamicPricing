package com.voxelhorizons.dynamicpricing;

import java.util.Objects;

/**
 * Side-effect-free pricing math. All monetary amounts are denominated in the caller's currency.
 * No Bukkit or ShopGUIPlus dependency: adapters can quote and commit atomic trades separately.
 */
public final class PricingEngine {
    public enum Side { BUY, SELL }

    /**
     * Coefficients are independent and validated. Sell prices must never exceed buy prices
     * when configured spreads/floors permit it.
     */
    public record Parameters(
            double baseBuy, double baseSell,
            double minimumMultiplier, double maximumMultiplier,
            double buyImpact, double sellImpact,
            double stockSensitivity, double stockTarget,
            double recoveryHalfLifeHours, double minimumSpread,
            double absoluteMinimum, double absoluteMaximum) {
        public Parameters {
            requirePositive(baseBuy, "baseBuy");
            requirePositive(baseSell, "baseSell");
            requireNonNegative(minimumMultiplier, "minimumMultiplier");
            requirePositive(maximumMultiplier, "maximumMultiplier");
            requireNonNegative(buyImpact, "buyImpact");
            requireNonNegative(sellImpact, "sellImpact");
            requireNonNegative(stockSensitivity, "stockSensitivity");
            requirePositive(stockTarget, "stockTarget");
            requirePositive(recoveryHalfLifeHours, "recoveryHalfLifeHours");
            requireNonNegative(minimumSpread, "minimumSpread");
            requirePositive(absoluteMinimum, "absoluteMinimum");
            requirePositive(absoluteMaximum, "absoluteMaximum");
            if (baseSell > baseBuy || minimumMultiplier > 1 || maximumMultiplier < 1 ||
                    absoluteMaximum < absoluteMinimum || minimumSpread >= 1)
                throw new IllegalArgumentException("Inconsistent price bounds, bases or spread");
        }
        private static void requirePositive(double v, String n) {
            if (!Double.isFinite(v) || v <= 0) throw new IllegalArgumentException(n);
        }
        private static void requireNonNegative(double v, String n) {
            if (!Double.isFinite(v) || v < 0) throw new IllegalArgumentException(n);
        }
        public static Parameters conservative(double buyPrice) {
            return new Parameters(buyPrice, buyPrice * 0.70, 0.65, 1.75,
                    0.002, 0.0015, 0.20, 1000, 24, 0.10, 0.01, 1_000_000);
        }
    }

    /** netDemand is quantity bought by players minus quantity sold, positive when demand rises. */
    public record State(double netDemand, double stock) {
        public State {
            if (!Double.isFinite(netDemand) || !Double.isFinite(stock) || stock < 0)
                throw new IllegalArgumentException("Invalid market state");
        }
    }
    public record Quote(double total, double averageUnitPrice, double lastUnitPrice, State after) {}

    /**
     * Exponential recovery toward zero demand; use elapsed real time (not tick counts).
     * A 24h half-life halves demand after 24h of inactivity.
     */
    public State recover(State state, Parameters params, double elapsedHours) {
        Objects.requireNonNull(state);
        Objects.requireNonNull(params);
        if (!Double.isFinite(elapsedHours) || elapsedHours < 0) throw new IllegalArgumentException("elapsedHours");
        return new State(state.netDemand() * Math.pow(0.5, elapsedHours / params.recoveryHalfLifeHours()), state.stock());
    }

    public double unitPrice(Parameters p, State s, Side side) {
        Objects.requireNonNull(p); Objects.requireNonNull(s); Objects.requireNonNull(side);
        double demandImpact = s.netDemand() >= 0
                ? p.buyImpact() * s.netDemand() : p.sellImpact() * s.netDemand();
        double stockImpact = p.stockSensitivity() * (p.stockTarget() - s.stock()) / p.stockTarget();
        double exponent = Math.max(-100, Math.min(100, demandImpact + stockImpact));
        double multiplier = clamp(Math.exp(exponent), p.minimumMultiplier(), p.maximumMultiplier());
        double buy = clamp(p.baseBuy() * multiplier, p.absoluteMinimum(), p.absoluteMaximum());
        double sell = clamp(p.baseSell() * multiplier, p.absoluteMinimum(), p.absoluteMaximum());
        // No arbitrage from configuration or clamping; if the cap prevents spread, stop offering sales.
        if (side == Side.BUY) return buy;
        return Math.max(0, Math.min(sell, buy * (1.0 - p.minimumSpread())));
    }

    /**
     * Simulates each unit, so bulk trades cannot evade market impact.
     * Callers MUST quote and commit under the same item lock/transaction.
     * Prices returned here are unrounded; round ONLY the aggregate at the currency boundary.
     */
    public Quote quote(Parameters p, State before, Side side, int quantity) {
        if (quantity <= 0 || quantity > 100_000) throw new IllegalArgumentException("quantity out of bounds");
        State cursor = Objects.requireNonNull(before);
        double total = 0, last = 0;
        for (int i = 0; i < quantity; i++) {
            if (side == Side.BUY && cursor.stock() < 1) throw new IllegalStateException("Insufficient stock");
            last = unitPrice(p, cursor, side);
            total += last;
            cursor = side == Side.BUY
                    ? new State(cursor.netDemand() + 1, cursor.stock() - 1)
                    : new State(cursor.netDemand() - 1, cursor.stock() + 1);
        }
        return new Quote(total, total / quantity, last, cursor);
    }
    private static double clamp(double number, double min, double max) {
        return Math.max(min, Math.min(max, number));
    }
}
