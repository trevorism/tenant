package com.trevorism.entitlement

import com.google.gson.Gson
import com.trevorism.https.SecureHttpClient
import io.micronaut.security.authentication.Authentication
import org.junit.jupiter.api.Test

class StripeEntitlementProviderTest {

    private static final String SUBSCRIPTION_URL = "https://stripe.trade.trevorism.com/api/subscription"
    private static final String CUSTOMER_URL = "https://stripe.trade.trevorism.com/api/subscription/customer/cus_1"
    private static final String SESSION_URL = "https://stripe.trade.trevorism.com/api/subscription/session"

    private Gson gson = new Gson()
    private Map<String, String> passThruGets = [:]
    private Map<String, String> appGets = [:]
    private List<Map> passThruPosts = []

    @Test
    void testProviderIsNamedForItsRail() {
        assert buildProvider().name == "STRIPE"
    }

    @Test
    void testStartCheckoutSendsThePlanAndReturnsTheRedirect() {
        CheckoutRequest request = new CheckoutRequest(planName: "Trevorism Tenant: Acme", monthlyPriceDollars: 10.00d,
                successUrl: "https://trevorism.com/tenant?status=success",
                cancelUrl: "https://trevorism.com/tenant?status=cancelled")

        Checkout checkout = buildProvider().startCheckout(request, authentication())

        assert checkout.id == "cs_test_123"
        assert checkout.url == "https://checkout.stripe.com/c/pay/cs_test_123"
        Map body = gson.fromJson(passThruPosts.find { it.url == SESSION_URL }.body as String, Map)
        assert body.dollars == 10.0d
        assert body.name == "Trevorism Tenant: Acme"
        assert body.successCallbackUrl == "https://trevorism.com/tenant?status=success"
        assert body.failureCallbackUrl == "https://trevorism.com/tenant?status=cancelled"
    }

    @Test
    void testForCallerMapsAnActiveSubscriptionWithAnEpochRenewalDate() {
        passThruGets[SUBSCRIPTION_URL] = '{"customerId":"cus_1","subscriptionId":"sub_1","active":true,"renewalDate":1756000000000}'

        Entitlement entitlement = buildProvider().forCaller(authentication())

        assert entitlement.active
        assert entitlement.provider == "STRIPE"
        assert entitlement.reference == "cus_1"
        assert entitlement.entitlementId == "sub_1"
        assert entitlement.paidThrough == new Date(1756000000000L)
    }

    @Test
    void testForCallerMapsAnIsoRenewalDate() {
        passThruGets[SUBSCRIPTION_URL] = '{"customerId":"cus_1","subscriptionId":"sub_1","active":true,"renewalDate":"2026-09-16T00:00:00Z"}'

        Entitlement entitlement = buildProvider().forCaller(authentication())

        assert entitlement.paidThrough == Date.from(java.time.Instant.parse("2026-09-16T00:00:00Z"))
    }

    @Test
    void testForCallerToleratesAMissingRenewalDate() {
        passThruGets[SUBSCRIPTION_URL] = '{"customerId":"cus_1","subscriptionId":"sub_1","active":true}'

        Entitlement entitlement = buildProvider().forCaller(authentication())

        assert entitlement.active
        assert entitlement.paidThrough == null
    }

    @Test
    void testForCallerReportsInactiveWhenTheSubscriptionIsNotActive() {
        passThruGets[SUBSCRIPTION_URL] = '{"customerId":"cus_1","active":false}'

        Entitlement entitlement = buildProvider().forCaller(authentication())

        assert !entitlement.active
        assert !entitlement.undetermined
        assert entitlement.reference == "cus_1"
    }

    @Test
    void testForCallerReportsUndeterminedWhenStripeCannotBeReached() {
        Entitlement entitlement = buildProvider().forCaller(authentication())

        assert entitlement.undetermined
        assert !entitlement.active
    }

    @Test
    void testForReferenceReadsTheCustomerLookupWithTheAppIdentity() {
        appGets[CUSTOMER_URL] = '{"customerId":"cus_1","subscriptionId":"sub_9","active":true}'

        Entitlement entitlement = buildProvider().forReference("cus_1")

        assert entitlement.active
        assert entitlement.entitlementId == "sub_9"
    }

    @Test
    void testForReferenceReportsUndeterminedWithoutAReference() {
        Entitlement entitlement = buildProvider().forReference(null)

        assert entitlement.undetermined
    }

    @Test
    void testForReferenceReportsUndeterminedWhenTheLookupFails() {
        Entitlement entitlement = buildProvider().forReference("cus_missing")

        assert entitlement.undetermined
    }

    private static Authentication authentication() {
        [getName: { "caller" }, getRoles: { ["user"] }, getAttributes: { [id: "user-1"] }] as Authentication
    }

    private StripeEntitlementProvider buildProvider() {
        return new StripeEntitlementProvider(stubClient(passThruGets, passThruPosts), stubClient(appGets, []))
    }

    private static SecureHttpClient stubClient(Map<String, String> gets, List<Map> posts) {
        return [
                get : { String url ->
                    if (!gets.containsKey(url)) {
                        throw new RuntimeException("unexpected GET ${url}")
                    }
                    return gets[url]
                },
                post: { String url, String body ->
                    posts << [url: url, body: body]
                    return '{"id":"cs_test_123","url":"https://checkout.stripe.com/c/pay/cs_test_123"}'
                }
        ] as SecureHttpClient
    }
}
