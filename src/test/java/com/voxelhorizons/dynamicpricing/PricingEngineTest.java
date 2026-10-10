package com.voxelhorizons.dynamicpricing;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.voxelhorizons.dynamicpricing.PricingEngine.Side.*;

class PricingEngineTest {
    private final PricingEngine engine = new PricingEngine();
    private final PricingEngine.Parameters p = PricingEngine.Parameters.conservative(100);
    private final PricingEngine.State neutral = new PricingEngine.State(0, 1000);

    @Test void buyingRaisesPriceAndSellingLowersIt() {
        var buy = engine.quote(p, neutral, BUY, 10);
        assertTrue(engine.unitPrice(p, buy.after(), BUY) > engine.unitPrice(p, neutral, BUY));
        var sell = engine.quote(p, neutral, SELL, 10);
        assertTrue(engine.unitPrice(p, sell.after(), BUY) < engine.unitPrice(p, neutral, BUY));
    }
    @Test void bulkQuotesChargeProgressively() {
        var quote = engine.quote(p, neutral, BUY, 10);
        assertTrue(quote.total() > 10 * engine.unitPrice(p, neutral, BUY));
        assertEquals(990, quote.after().stock());
        assertEquals(10, quote.after().netDemand());
    }
    @Test void recoversWithConfiguredHalfLife() {
        assertEquals(40, engine.recover(new PricingEngine.State(80, 1000), p, 24).netDemand(), 1e-8);
    }
    @Test void guaranteesSpread() {
        for (int demand = -10000; demand <= 10000; demand += 100) {
            var state = new PricingEngine.State(demand, 1000);
            assertTrue(engine.unitPrice(p, state, SELL) <= engine.unitPrice(p, state, BUY) * .9 + 1e-8);
        }
    }
    @Test void failsClosedWhenStockMissing() {
        assertThrows(IllegalStateException.class, () -> engine.quote(p, new PricingEngine.State(0, 0), BUY, 1));
    }
    @Test void omittedCapsPermitExponentialGrowth() {
        var unlimited = new PricingEngine.Parameters(100, 70, null, null, .002, .0015,
                .2, 1000, 24, .10, null, null);
        assertTrue(engine.unitPrice(unlimited, new PricingEngine.State(2000, 1000), BUY) > 1000);
        assertThrows(ArithmeticException.class,
                () -> engine.unitPrice(unlimited, new PricingEngine.State(1_000_000, 1000), BUY));
    }
    @Test void shopTypesCanBeResolvedIndependently() {
        var dynamic = new ShopPricing(ShopPricing.Mode.DYNAMIC, p);
        var fixed = new ShopPricing(ShopPricing.Mode.STATIC, p);
        var stock = new ShopPricing(ShopPricing.Mode.STOCK, p);
        var state = new PricingEngine.State(100, 500);
        assertEquals(p.baseBuy(), fixed.quote(engine, state, BUY, 1).total(), 1e-6);
        assertTrue(dynamic.quote(engine, state, BUY, 1).total() >
                   stock.quote(engine, state, BUY, 1).total());
        assertTrue(stock.quote(engine, state, BUY, 1).total() > p.baseBuy());
    }
    @Test void fixedStockModePreservesPriceAndRejectsOutOfStock() {
        var fixed = new ShopPricing(ShopPricing.Mode.STATIC_STOCK, p);
        var before = new PricingEngine.State(20, 3);
        var quoted = fixed.quote(engine, before, BUY, 2);
        assertEquals(200, quoted.total(), 1e-8);
        assertEquals(1, quoted.after().stock(), 1e-8);
        assertThrows(IllegalStateException.class, () -> fixed.quote(engine, before, BUY, 4));
    }
    @Test void dynamicModeDoesNotRequirePhysicalStock() {
        var dynamic = new ShopPricing(ShopPricing.Mode.DYNAMIC, p);
        assertDoesNotThrow(() -> dynamic.quote(engine, new PricingEngine.State(0,0), BUY, 64));
    }
    @Test void recipeModeReturnsFixedDerivedPrice() {
        var recipe = new ShopPricing(ShopPricing.Mode.RECIPE, p);
        var q = recipe.quote(engine, new PricingEngine.State(80,0), BUY, 3);
        assertEquals(300,q.total(),1e-9);
        assertEquals(0,q.after().stock(),1e-9);
    }
    @Test void uncappedDemandMonotonicallyRaisesQuotes() {
        var noCaps = new PricingEngine.Parameters(20,12,null,null,.002,.0015,0,1000,24,.15,null,null);
        var dynamic = new ShopPricing(ShopPricing.Mode.DYNAMIC,noCaps);
        var state = new PricingEngine.State(0,0);
        double previous=0;
        for(int batch=0;batch<20;batch++) {
            var quote=dynamic.quote(engine,state,BUY,64);
            assertTrue(quote.averageUnitPrice()>previous);
            previous=quote.averageUnitPrice();
            state=quote.after();
        }
        assertTrue(previous>20);
    }
    @Test void boundedAndUnboundedMarketsHaveDifferentCeilings() {
        var capped=PricingEngine.Parameters.conservative(100);
        var uncapped=new PricingEngine.Parameters(100,70,null,null,.002,.0015,.20,1000,24,.10,null,null);
        var state=new PricingEngine.State(1000,1000);
        assertTrue(engine.unitPrice(uncapped,state,BUY)>engine.unitPrice(capped,state,BUY));
    }
    @Test void respectsLimitsAndRejectsInvalidParameters() {
        var high = new PricingEngine.State(1e8, 0);
        assertTrue(engine.unitPrice(p, high, BUY) <= 175 + 1e-8);
        assertThrows(IllegalArgumentException.class, () -> PricingEngine.Parameters.conservative(-5));
        assertThrows(IllegalArgumentException.class, () -> engine.quote(p, neutral, BUY, 0));
    }
}
