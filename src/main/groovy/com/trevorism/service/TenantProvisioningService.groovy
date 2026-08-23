package com.trevorism.service

import com.trevorism.model.SubscriptionView
import com.trevorism.model.TenantRequest
import com.trevorism.model.TenantRequestInput
import io.micronaut.security.authentication.Authentication

interface TenantProvisioningService {

    TenantRequest requestTenant(TenantRequestInput input, Authentication authentication)
    TenantRequest getRequestForCaller(Authentication authentication)
    SubscriptionView getSubscriptionForCaller(Authentication authentication)
    Map createCheckoutSession(String requestId, Authentication authentication)
    TenantRequest provision(String requestId, Authentication authentication)
    TenantRequest deleteRequest(String requestId)
    Map synchronizeEntitlements()
}
