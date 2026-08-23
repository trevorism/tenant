package com.trevorism.entitlement

import com.google.gson.Gson
import com.trevorism.https.SecureHttpClient
import io.micronaut.security.authentication.Authentication
import jakarta.inject.Named
import jakarta.inject.Singleton
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.time.Instant
import java.time.format.DateTimeParseException

@Singleton
class StripeEntitlementProvider implements TenantEntitlementProvider {

    private static final Logger log = LoggerFactory.getLogger(StripeEntitlementProvider)

    static final String PROVIDER_NAME = "STRIPE"
    static final String BASE_URL = "https://stripe.trade.trevorism.com"

    private SecureHttpClient passThruHttpClient
    private SecureHttpClient appHttpClient
    private Gson gson = new Gson()

    StripeEntitlementProvider(@Named("passThruSecureHttpClient") SecureHttpClient passThruHttpClient,
                              @Named("appSecureHttpClient") SecureHttpClient appHttpClient) {
        this.passThruHttpClient = passThruHttpClient
        this.appHttpClient = appHttpClient
    }

    @Override
    String getName() {
        return PROVIDER_NAME
    }

    @Override
    Checkout startCheckout(CheckoutRequest request, Authentication authentication) {
        Map body = [name               : request.planName,
                    dollars            : request.monthlyPriceDollars,
                    successCallbackUrl : request.successUrl,
                    failureCallbackUrl : request.cancelUrl]

        String json = passThruHttpClient.post("${BASE_URL}/api/subscription/session", gson.toJson(body))
        Map session = gson.fromJson(json, Map)
        return new Checkout(id: session?.id as String, url: session?.url as String)
    }

    @Override
    Checkout startBillingPortal(String returnUrl, Authentication authentication) {
        String json = passThruHttpClient.post("${BASE_URL}/api/subscription/portal", gson.toJson([returnUrl: returnUrl]))
        Map session = gson.fromJson(json, Map)
        return new Checkout(url: session?.url as String)
    }

    @Override
    Entitlement forCaller(Authentication authentication) {
        try {
            return toEntitlement(passThruHttpClient.get("${BASE_URL}/api/subscription"))
        } catch (Exception e) {
            log.warn("Unable to read the caller's subscription: ${e.message}")
            return Entitlement.unknown(PROVIDER_NAME)
        }
    }

    @Override
    Entitlement forReference(String reference) {
        if (!reference) {
            return Entitlement.unknown(PROVIDER_NAME)
        }
        try {
            return toEntitlement(appHttpClient.get("${BASE_URL}/api/subscription/customer/${reference}"))
        } catch (Exception e) {
            log.warn("Unable to read the subscription for customer ${reference}: ${e.message}")
            return Entitlement.unknown(PROVIDER_NAME)
        }
    }

    private Entitlement toEntitlement(String json) {
        Map subscription = json ? gson.fromJson(json, Map) : null
        if (!subscription) {
            return Entitlement.unknown(PROVIDER_NAME)
        }

        String reference = subscription.customerId as String
        if (!(subscription.active instanceof Boolean)) {
            log.warn("Subscription response for customer ${reference} carries no usable active flag")
            return Entitlement.unknown(PROVIDER_NAME)
        }
        if (!subscription.active) {
            return Entitlement.inactive(PROVIDER_NAME, reference)
        }
        return Entitlement.active(PROVIDER_NAME, reference, subscription.subscriptionId as String,
                toDate(subscription.renewalDate))
    }

    private static Date toDate(Object value) {
        if (value instanceof Number) {
            return new Date(((Number) value).longValue())
        }
        if (value instanceof CharSequence) {
            try {
                return Date.from(Instant.parse(value.toString()))
            } catch (DateTimeParseException ignored) {
                return null
            }
        }
        return null
    }
}
