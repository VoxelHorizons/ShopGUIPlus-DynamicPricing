package com.voxelhorizons.dynamicpricing;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Prices each unit using administrator-supplied safe equations. */
public final class FormulaPricing {
    private final PriceExpression.Expression buy;
    private final PriceExpression.Expression sell;

    public FormulaPricing(String buyExpression, String sellExpression) {
        this.buy = PriceExpression.compile(Objects.requireNonNull(buyExpression));
        this.sell = PriceExpression.compile(Objects.requireNonNull(sellExpression));
    }

    public PricingEngine.Quote quote(PricingEngine.Parameters p, PricingEngine.State initial,
                                      ShopPricing.Mode mode, PricingEngine.Side side, int count) {
        Objects.requireNonNull(p); Objects.requireNonNull(initial);
        if (count <= 0 || count > 100000) throw new IllegalArgumentException("Invalid quantity");
        boolean finiteStock = mode == ShopPricing.Mode.STOCK || mode == ShopPricing.Mode.STATIC_STOCK;
        double demand = initial.netDemand();
        double stock = initial.stock();
        double total = 0, unit = 0;
        Map<String,Double> variables = new HashMap<>();
        variables.put("baseBuy",p.baseBuy());
        variables.put("baseSell",p.baseSell());
        variables.put("target",p.stockTarget());
        variables.put("buyImpact",p.buyImpact());
        variables.put("sellImpact",p.sellImpact());
        variables.put("stockSensitivity",p.stockSensitivity());
        variables.put("quantity",(double)count);
        variables.put("side",side == PricingEngine.Side.BUY ? 1.0 : -1.0);
        for (int i=0; i<count; i++) {
            if (finiteStock && side==PricingEngine.Side.BUY && stock<1)
                throw new IllegalStateException("Insufficient market stock");
            variables.put("demand",demand);
            variables.put("stock",stock);
            variables.put("stockRatio",stock/p.stockTarget());
            variables.put("unitIndex",(double)i);
            double nextBuy = bounded(buy.evaluate(variables),p.baseBuy(),p);
            double nextSell = bounded(sell.evaluate(variables),p.baseSell(),p);
            unit = side == PricingEngine.Side.BUY ? nextBuy :
                    Math.max(0,Math.min(nextSell,nextBuy*(1-p.minimumSpread())));
            if (!Double.isFinite(unit) || unit<=0) throw new ArithmeticException("Invalid/zero formula price");
            total += unit;
            if (!Double.isFinite(total)) throw new ArithmeticException("Trade amount overflow");
            demand += side==PricingEngine.Side.BUY ? 1 : -1;
            if (finiteStock) stock += side==PricingEngine.Side.BUY ? -1 : 1;
        }
        return new PricingEngine.Quote(total,total/count,unit,
                new PricingEngine.State(demand,stock));
    }

    private static double bounded(double value, double base, PricingEngine.Parameters p) {
        if (!Double.isFinite(value) || value<0) throw new ArithmeticException("Invalid formula price");
        if (p.minimumMultiplier()!=null) value=Math.max(value,base*p.minimumMultiplier());
        if (p.maximumMultiplier()!=null) value=Math.min(value,base*p.maximumMultiplier());
        if (p.absoluteMinimum()!=null) value=Math.max(value,p.absoluteMinimum());
        if (p.absoluteMaximum()!=null) value=Math.min(value,p.absoluteMaximum());
        if (!Double.isFinite(value)) throw new ArithmeticException("Price out of numerical range");
        return value;
    }
}
