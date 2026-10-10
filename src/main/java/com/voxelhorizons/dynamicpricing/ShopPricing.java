package com.voxelhorizons.dynamicpricing;

import java.util.Objects;

/** Immutable pricing policy selected per shop (or item override). */
public record ShopPricing(Mode mode, PricingEngine.Parameters parameters) {
    public enum Mode { STATIC, DYNAMIC, STOCK, STATIC_STOCK }

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
            case DYNAMIC -> dynamicQuote(engine, parameters, state, side, quantity);
            case STATIC -> fixedQuote(parameters, state, side, quantity);
            case STATIC_STOCK -> fixedStockQuote(parameters, state, side, quantity);
            case STOCK -> engine.quote(stockOnly(parameters), state, side, quantity);
        };
    }

    private static PricingEngine.Quote dynamicQuote(PricingEngine engine, PricingEngine.Parameters p,
            PricingEngine.State state, PricingEngine.Side side, int quantity) {
        var params = new PricingEngine.Parameters(p.baseBuy(), p.baseSell(), p.minimumMultiplier(),
                p.maximumMultiplier(), p.buyImpact(), p.sellImpact(), 0, p.stockTarget(),
                p.recoveryHalfLifeHours(), p.minimumSpread(), p.absoluteMinimum(), p.absoluteMaximum());
        var virtual = new PricingEngine.State(state.netDemand(),
                Math.max(state.stock(), quantity + p.stockTarget()));
        var q = engine.quote(params, virtual, side, quantity);
        return new PricingEngine.Quote(q.total(), q.averageUnitPrice(), q.lastUnitPrice(),
                new PricingEngine.State(q.after().netDemand(), state.stock()));
    }

    private static PricingEngine.Quote fixedQuote(PricingEngine.Parameters p, PricingEngine.State state,
                                                 PricingEngine.Side side, int quantity) {
        double unit = side == PricingEngine.Side.BUY ? p.baseBuy() :
                Math.min(p.baseSell(), p.baseBuy() * (1 - p.minimumSpread()));
        double total = unit * quantity;
        if (!Double.isFinite(total)) throw new ArithmeticException("Trade total exceeds numeric range");
        return new PricingEngine.Quote(total, unit, unit, state);
    }

    private static PricingEngine.Quote fixedStockQuote(PricingEngine.Parameters p, PricingEngine.State state,
                                                 PricingEngine.Side side, int quantity) {
        if (side == PricingEngine.Side.BUY && state.stock() < quantity)
            throw new IllegalStateException("Insufficient stock");
        PricingEngine.Quote price = fixedQuote(p,state,side,quantity);
        double next = state.stock() + (side == PricingEngine.Side.BUY ? -quantity : quantity);
        if (!Double.isFinite(next) || next < 0) throw new ArithmeticException("Stock out of range");
        return new PricingEngine.Quote(price.total(),price.averageUnitPrice(),price.lastUnitPrice(),
                new PricingEngine.State(state.netDemand(),next));
    }
    private static PricingEngine.Parameters stockOnly(PricingEngine.Parameters p) {
        return new PricingEngine.Parameters(p.baseBuy(), p.baseSell(),
                p.minimumMultiplier(), p.maximumMultiplier(), 0, 0,
                p.stockSensitivity(), p.stockTarget(), p.recoveryHalfLifeHours(),
                p.minimumSpread(), p.absoluteMinimum(), p.absoluteMaximum());
    }
}
