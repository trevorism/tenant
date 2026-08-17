package com.trevorism.service

import com.trevorism.model.TenantRequest
import com.trevorism.model.TenantRequestInput
import io.micronaut.security.authentication.Authentication

interface TenantProvisioningService {

    TenantRequest requestTenant(TenantRequestInput input, Authentication authentication)
    TenantRequest getRequestForCaller(Authentication authentication)
    Map createCheckoutSession(String requestId, Authentication authentication)
    TenantRequest provision(String requestId, Authentication authentication)
    int synchronizeEntitlements()
}
