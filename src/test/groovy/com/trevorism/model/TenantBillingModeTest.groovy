package com.trevorism.model

import org.junit.jupiter.api.Test

class TenantBillingModeTest {

    @Test
    void testTenantsPredatingBillingModeReadAsUnbilled() {
        assert TenantBillingMode.orDefault(null) == TenantBillingMode.UNBILLED
        assert TenantBillingMode.orDefault("") == TenantBillingMode.UNBILLED
        assert !TenantBillingMode.isSubscription(null)
    }

    @Test
    void testSubscriptionModeIsRecognized() {
        assert TenantBillingMode.isSubscription(TenantBillingMode.SUBSCRIPTION)
        assert !TenantBillingMode.isSubscription(TenantBillingMode.UNBILLED)
    }
}
