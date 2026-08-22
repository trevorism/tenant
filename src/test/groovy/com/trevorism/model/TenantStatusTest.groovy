package com.trevorism.model

import org.junit.jupiter.api.Test

class TenantStatusTest {

    @Test
    void testAMissingStatusDefaultsToActive() {
        assert TenantStatus.orDefault(null) == TenantStatus.ACTIVE
        assert TenantStatus.orDefault("") == TenantStatus.ACTIVE
    }

    @Test
    void testAnExplicitStatusIsPreserved() {
        assert TenantStatus.orDefault(TenantStatus.SUSPENDED) == TenantStatus.SUSPENDED
    }

    @Test
    void testOnlyASuspendedStatusReadsAsSuspended() {
        assert TenantStatus.isSuspended(TenantStatus.SUSPENDED)
        assert !TenantStatus.isSuspended(TenantStatus.ACTIVE)
        assert !TenantStatus.isSuspended(null)
    }
}
