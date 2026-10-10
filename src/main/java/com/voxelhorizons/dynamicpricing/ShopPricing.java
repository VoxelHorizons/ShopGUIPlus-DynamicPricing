package com.voxelhorizons.dynamicpricing;

import java.util.Objects;

/** Immutable pricing policy selected per shop (or item override). */
public record ShopPricing(Mode mode, PricingEngine.Parameters parameters) {
    public enum Mode { STATIC, DYNAMIC, STOCK }

    public ShopPricing {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(parameters, "parameters");
    }

    public PricingEngine.Quote quote(PricingEngine engine, PricingEngine.State state,
                                     PricingEngine.Side side, int quantity) {
        Objects.requireNonNull(engine);
        Objects.requireNonNull(state);
        Objects.requireNonNull(side);
        if (quantity <= 0 || quantity > 100_000) throw new IllegalArgumentException("quantity");
        return switch (mode) {
            case DYNAMIC -> engine.quote(parameters, state, side, quantity);
            case STATIC -> fixedQuote(parameters, state, side, quantity);
            case STOCK -> engine.quote(stockOnly(parameters), state, side, quantity);
        };
    }

    private static PricingEngine.Quote fixedQuote(PricingEngine.Parameters p, PricingEngine.State state,
                                                 PricingEngine.Side side, int quantity) {
        double unit = side == PricingEngine.Side.BUY ? p.baseBuy() :
                Math.min(p.baseSell(), p.baseBuy() * (1 - p.minimumSpread()));
        double total = unit * quantity;
        if (!Double.isFinite(total)) throw new ArithmeticException("Trade total exceeds numeric range");
        return new PricingEngine.Quote(total, unit, unit, state);
    }

    private static PricingEngine.Parameters stockOnly(PricingEngine.Parameters p) {
        return new PricingEngine.Parameters(p.baseBuy(), p.baseSell(),
                p.minimumMultiplier(), p.maximumMultiplier(), 0, 0,
                p.stockSensitivity(), p.stockTarget(), p.recoveryHalfLifeHours(),
                p.minimumSpread(), p.absoluteMinimum(), p.absoluteMaximum());
    }
}
