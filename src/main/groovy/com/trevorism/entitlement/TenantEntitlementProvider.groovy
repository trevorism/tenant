package com.trevorism.entitlement

import io.micronaut.security.authentication.Authentication

interface TenantEntitlementProvider {

    String getName()

    Checkout startCheckout(CheckoutRequest request, Authentication authentication)

    Checkout startBillingPortal(String returnUrl, Authentication authentication)

    Entitlement forCaller(Authentication authentication)

    Entitlement forReference(String reference)
}
