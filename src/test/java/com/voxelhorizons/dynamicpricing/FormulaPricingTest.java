package com.voxelhorizons.dynamicpricing;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.voxelhorizons.dynamicpricing.PricingEngine.Side.*;

class FormulaPricingTest {
    private final PricingEngine.Parameters p = PricingEngine.Parameters.conservative(100);

    @Test void supportsSafeOperatorsAndWhitelistedMath() {
        var f = PriceExpression.compile("max(2, 3) + pow(2, 3) * clamp(-1, 0, 10)");
        assertEquals(3, f.evaluate(Map.of()),1e-9);
        assertEquals(200, PriceExpression.compile("baseBuy * exp(ln(2))")
                .evaluate(Map.of("baseBuy",100.0)),1e-9);
    }
    @Test void doesNotRunArbitraryCodeOrAcceptMalformedFunctions() {
        assertThrows(IllegalArgumentException.class,()->PriceExpression.compile("System.exit(0)"));
        assertThrows(IllegalArgumentException.class,()->PriceExpression.compile("random()"));
        assertThrows(IllegalArgumentException.class,()->PriceExpression.compile("pow(2)"));
        assertThrows(IllegalArgumentException.class,()->PriceExpression.compile("1+"));
        assertThrows(ArithmeticException.class,()->PriceExpression.compile("1/0").evaluate(Map.of()));
    }
    @Test void dynamicBulkUsesFreshDemandForEveryUnit() {
        var pricing = new FormulaPricing("baseBuy * exp(demand * buyImpact)", "baseSell * exp(demand * buyImpact)");
        var initial = new PricingEngine.State(0,1000);
        var quoted = pricing.quote(p,initial,ShopPricing.Mode.DYNAMIC,BUY,10);
        assertTrue(quoted.total()>1000);
        assertEquals(10,quoted.after().netDemand());
        assertEquals(1000,quoted.after().stock());
    }
    @Test void capsAreOptionalForFormulaPrices() {
        var uncapped = new PricingEngine.Parameters(100,70,null,null,.002,.0015,.2,1000,24,.10,null,null);
        var formula = new FormulaPricing("baseBuy * exp(demand * buyImpact)", "baseSell * exp(demand * buyImpact)");
        assertTrue(formula.quote(uncapped,new PricingEngine.State(2000,1000),
                ShopPricing.Mode.DYNAMIC,BUY,1).total()>1000);
    }
    @Test void finiteStockIsEnforcedForFormulaPricing() {
        var formula = new FormulaPricing("baseBuy","baseSell");
        assertThrows(IllegalStateException.class,()->formula.quote(p,new PricingEngine.State(0,1),
                ShopPricing.Mode.STOCK,BUY,2));
    }
}
