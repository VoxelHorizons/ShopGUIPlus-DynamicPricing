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
    @Test void respectsLimitsAndRejectsInvalidParameters() {
        var high = new PricingEngine.State(1e8, 0);
        assertTrue(engine.unitPrice(p, high, BUY) <= 175 + 1e-8);
        assertThrows(IllegalArgumentException.class, () -> PricingEngine.Parameters.conservative(-5));
        assertThrows(IllegalArgumentException.class, () -> engine.quote(p, neutral, BUY, 0));
    }
}
