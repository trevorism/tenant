package com.trevorism.service

import com.google.gson.Gson
import com.trevorism.data.Repository
import com.trevorism.data.model.filtering.SimpleFilter
import com.trevorism.https.SecureHttpClient
import com.trevorism.model.Tenant
import com.trevorism.model.TenantRequest
import com.trevorism.model.TenantRequestInput
import com.trevorism.model.TenantRequestStatus
import io.micronaut.security.authentication.Authentication
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertThrows

class DefaultTenantProvisioningServiceTest {

    private static final String OWNER_ID = "user-1"
    private static final String USER_ME_URL = "https://auth.trevorism.com/user/me"
    private static final String SUBSCRIPTION_URL = "https://stripe.trade.trevorism.com/api/subscription"
    private static final String SESSION_URL = "https://stripe.trade.trevorism.com/api/subscription/session"

    private Gson gson = new Gson()
    private List<Map> appPosts = []
    private List<Map> passThruPosts = []
    private Map<String, String> appGets = [:]
    private Map<String, String> passThruGets = [:]

    @Test
    void testRequestTenantPersistsAPendingRequestWithOwnerDetails() {
        passThruGets[USER_ME_URL] = '{"id":"user-1","username":"trevor","email":"trevor@example.com"}'
        TenantRequest created = null
        def service = buildService(
                requestRepository([], { TenantRequest r -> created = r; r.id = "req-1"; r }),
                tenantRepository([]))

        TenantRequest result = service.requestTenant(new TenantRequestInput(name: " Acme ", domain: " ACME.com "), auth(OWNER_ID))

        assert result.id == "req-1"
        assert created.name == "Acme"
        assert created.domain == "acme.com"
        assert created.status == TenantRequestStatus.PENDING_PAYMENT
        assert created.ownerUserId == OWNER_ID
        assert created.ownerUsername == "trevor"
        assert created.ownerEmail == "trevor@example.com"
        assert created.dateCreated
        assert !created.tenantGuid
    }

    @Test
    void testRequestTenantRejectsADomainAlreadyInUse() {
        passThruGets[USER_ME_URL] = '{"id":"user-1","username":"trevor","email":"trevor@example.com"}'
        def service = buildService(requestRepository([]), tenantRepository([new Tenant(name: "Other", domain: "acme.com")]))

        assertThrows(TenantRequestException) {
            service.requestTenant(new TenantRequestInput(name: "Acme", domain: "acme.com"), auth(OWNER_ID))
        }
    }

    @Test
    void testRequestTenantRejectsAnUnresolvableOwner() {
        passThruGets[USER_ME_URL] = '{"id":"user-1"}'
        def service = buildService(requestRepository([]), tenantRepository([]))

        assertThrows(TenantRequestException) {
            service.requestTenant(new TenantRequestInput(name: "Acme", domain: "acme.com"), auth(OWNER_ID))
        }
    }

    @Test
    void testGetRequestForCallerReturnsTheOwnedRequest() {
        TenantRequest owned = pendingRequest()
        def service = buildService(requestRepository([owned]), tenantRepository([]))

        assert service.getRequestForCaller(auth(OWNER_ID)).is(owned)
    }

    @Test
    void testGetRequestForCallerPrefersTheLiveRequestOverASuspendedOne() {
        TenantRequest suspended = pendingRequest()
        suspended.id = "req-0"
        suspended.status = TenantRequestStatus.SUSPENDED
        TenantRequest live = pendingRequest()
        def service = buildService(requestRepository([suspended, live]), tenantRepository([]))

        assert service.getRequestForCaller(auth(OWNER_ID)).is(live)
    }

    @Test
    void testGetRequestForCallerFallsBackToTheSuspendedRequest() {
        TenantRequest suspended = provisionedRequest()
        suspended.status = TenantRequestStatus.SUSPENDED
        def service = buildService(requestRepository([suspended]), tenantRepository([]))

        assert service.getRequestForCaller(auth(OWNER_ID)).is(suspended)
    }

    @Test
    void testCreateCheckoutSessionAsksStripeForTheTenDollarPlan() {
        TenantRequest owned = pendingRequest()
        def service = buildService(requestRepository([owned]), tenantRepository([]))

        Map session = service.createCheckoutSession("req-1", auth(OWNER_ID))

        assert session.id == "cs_test_123"
        Map posted = passThruPosts.find { it.url == SESSION_URL }
        Map body = gson.fromJson(posted.body as String, Map)
        assert body.dollars == 10.0d
        assert body.name == "Trevorism Tenant: Acme"
        assert body.successCallbackUrl == "https://trevorism.com/tenant?request=req-1&status=success"
        assert body.failureCallbackUrl == "https://trevorism.com/tenant?request=req-1&status=cancelled"
    }

    @Test
    void testCreateCheckoutSessionRejectsARequestOwnedBySomebodyElse() {
        def service = buildService(requestRepository([pendingRequest()]), tenantRepository([]))

        assertThrows(TenantRequestException) {
            service.createCheckoutSession("req-1", auth("intruder"))
        }
    }

    @Test
    void testCreateCheckoutSessionRejectsAnAlreadyProvisionedRequest() {
        def service = buildService(requestRepository([provisionedRequest()]), tenantRepository([]))

        assertThrows(TenantRequestException) {
            service.createCheckoutSession("req-1", auth(OWNER_ID))
        }
    }

    @Test
    void testProvisionRequiresAnActiveSubscription() {
        passThruGets[SUBSCRIPTION_URL] = '{"customerId":"cus_1","subscriptionId":"sub_1","active":false}'
        def service = buildService(requestRepository([pendingRequest()]), tenantRepository([]))

        assertThrows(TenantRequestException) {
            service.provision("req-1", auth(OWNER_ID))
        }
        assert appPosts.isEmpty()
    }

    @Test
    void testProvisionCreatesTheTenantAndPromotesTheOwnerToTenantAdmin() {
        passThruGets[SUBSCRIPTION_URL] = '{"customerId":"cus_1","subscriptionId":"sub_1","active":true}'
        TenantRequest owned = pendingRequest()
        Tenant createdTenant = null
        TenantRequest updated = null
        def service = buildService(
                requestRepository([owned], null, { String id, TenantRequest r -> updated = r; r }),
                tenantRepository([], { Tenant t -> createdTenant = t; t }))

        TenantRequest result = service.provision("req-1", auth(OWNER_ID))

        assert createdTenant.name == "Acme"
        assert createdTenant.domain == "acme.com"
        assert UUID.fromString(createdTenant.guid)

        Map registration = gson.fromJson(appPosts[0].body as String, Map)
        assert appPosts[0].url == "https://auth.trevorism.com/user/"
        assert registration.username == "trevor"
        assert registration.email == "trevor@example.com"
        assert registration.tenantGuid == createdTenant.guid
        assert registration.autoRegister
        assert registration.permissions == "CRUDE"
        assert registration.password

        Map activation = gson.fromJson(appPosts[1].body as String, Map)
        assert appPosts[1].url == "https://auth.trevorism.com/user/activate"
        assert activation.isAdmin
        assert activation.tenantGuid == createdTenant.guid

        assert appPosts[2].url == "https://auth.trevorism.com/user/reset"

        assert result.status == TenantRequestStatus.PROVISIONED
        assert updated.tenantGuid == createdTenant.guid
        assert updated.billingCustomerId == "cus_1"
        assert updated.subscriptionId == "sub_1"
        assert updated.dateProvisioned
    }

    @Test
    void testProvisionIsIdempotent() {
        def service = buildService(requestRepository([provisionedRequest()]), tenantRepository([]))

        TenantRequest result = service.provision("req-1", auth(OWNER_ID))

        assert result.status == TenantRequestStatus.PROVISIONED
        assert appPosts.isEmpty()
    }

    @Test
    void testProvisionRejectsASubscriptionThatAlreadyFundsAnotherTenant() {
        passThruGets[SUBSCRIPTION_URL] = '{"customerId":"cus_1","subscriptionId":"sub_1","active":true}'
        TenantRequest owned = pendingRequest()
        TenantRequest other = new TenantRequest(id: "req-2", subscriptionId: "sub_1", status: TenantRequestStatus.PROVISIONED)
        def service = buildService(requestRepository([owned, other]), tenantRepository([]))

        assertThrows(TenantRequestException) {
            service.provision("req-1", auth(OWNER_ID))
        }
        assert appPosts.isEmpty()
    }

    @Test
    void testProvisionRejectsASubscriptionWhoseEarlierTenantIsMerelySuspended() {
        passThruGets[SUBSCRIPTION_URL] = '{"customerId":"cus_1","subscriptionId":"sub_1","active":true}'
        TenantRequest owned = pendingRequest()
        TenantRequest other = new TenantRequest(id: "req-2", subscriptionId: "sub_1", status: TenantRequestStatus.SUSPENDED)
        def service = buildService(requestRepository([owned, other]), tenantRepository([]))

        assertThrows(TenantRequestException) {
            service.provision("req-1", auth(OWNER_ID))
        }
        assert appPosts.isEmpty()
    }

    @Test
    void testSynchronizeEntitlementsSuspendsALapsedTenant() {
        appGets["https://stripe.trade.trevorism.com/api/subscription/customer/cus_1"] =
                '{"customerId":"cus_1","active":false}'
        TenantRequest provisioned = provisionedRequest()
        TenantRequest updated = null
        def service = buildService(
                requestRepository([provisioned], null, { String id, TenantRequest r -> updated = r; r }),
                tenantRepository([]))

        assert service.synchronizeEntitlements() == 1
        assert appPosts[0].url == "https://auth.trevorism.com/user/deactivate"
        assert updated.status == TenantRequestStatus.SUSPENDED
    }

    @Test
    void testSynchronizeEntitlementsRestoresARenewedTenant() {
        appGets["https://stripe.trade.trevorism.com/api/subscription/customer/cus_1"] =
                '{"customerId":"cus_1","active":true}'
        TenantRequest suspended = provisionedRequest()
        suspended.status = TenantRequestStatus.SUSPENDED
        TenantRequest updated = null
        def service = buildService(
                requestRepository([suspended], null, { String id, TenantRequest r -> updated = r; r }),
                tenantRepository([]))

        assert service.synchronizeEntitlements() == 1
        Map activation = gson.fromJson(appPosts[0].body as String, Map)
        assert appPosts[0].url == "https://auth.trevorism.com/user/activate"
        assert activation.isAdmin
        assert updated.status == TenantRequestStatus.PROVISIONED
    }

    @Test
    void testSynchronizeEntitlementsLeavesAHealthyTenantAlone() {
        appGets["https://stripe.trade.trevorism.com/api/subscription/customer/cus_1"] =
                '{"customerId":"cus_1","active":true}'
        def service = buildService(requestRepository([provisionedRequest()]), tenantRepository([]))

        assert service.synchronizeEntitlements() == 0
        assert appPosts.isEmpty()
    }

    @Test
    void testSynchronizeEntitlementsSkipsWhenSubscriptionStateCannotBeRead() {
        TenantRequest provisioned = provisionedRequest()
        def service = buildService(requestRepository([provisioned]), tenantRepository([]))

        assert service.synchronizeEntitlements() == 0
        assert appPosts.isEmpty()
        assert provisioned.status == TenantRequestStatus.PROVISIONED
    }

    @Test
    void testSynchronizeEntitlementsIgnoresRequestsAwaitingPayment() {
        def service = buildService(requestRepository([pendingRequest()]), tenantRepository([]))

        assert service.synchronizeEntitlements() == 0
        assert appPosts.isEmpty()
    }

    private static TenantRequest pendingRequest() {
        return new TenantRequest(id: "req-1", name: "Acme", domain: "acme.com",
                status: TenantRequestStatus.PENDING_PAYMENT, ownerUserId: OWNER_ID,
                ownerUsername: "trevor", ownerEmail: "trevor@example.com", dateCreated: new Date())
    }

    private static TenantRequest provisionedRequest() {
        TenantRequest request = pendingRequest()
        request.status = TenantRequestStatus.PROVISIONED
        request.tenantGuid = "guid-1"
        request.billingCustomerId = "cus_1"
        request.subscriptionId = "sub_1"
        request.dateProvisioned = new Date()
        return request
    }

    private static Authentication auth(String id) {
        [getName: { "caller" }, getRoles: { ["user"] }, getAttributes: { [id: id] }] as Authentication
    }

    private DefaultTenantProvisioningService buildService(Repository<TenantRequest> requests, Repository<Tenant> tenants) {
        DefaultTenantProvisioningService service = new DefaultTenantProvisioningService(
                stubClient(passThruGets, passThruPosts), stubClient(appGets, appPosts))
        service.tenantRequestRepository = requests
        service.tenantRepository = tenants
        return service
    }

    private SecureHttpClient stubClient(Map<String, String> gets, List<Map> posts) {
        return [
                get : { String url ->
                    if (!gets.containsKey(url)) {
                        throw new RuntimeException("unexpected GET ${url}")
                    }
                    return gets[url]
                },
                post: { String url, String body ->
                    posts << [url: url, body: body]
                    return url == SESSION_URL ? '{"id":"cs_test_123","url":"https://checkout.stripe.com/c/pay/cs_test_123"}' : "{}"
                }
        ] as SecureHttpClient
    }

    private static Repository<TenantRequest> requestRepository(List<TenantRequest> stored,
                                                               Closure<TenantRequest> onCreate = null,
                                                               Closure<TenantRequest> onUpdate = null) {
        return [
                list  : { stored },
                get   : { String id -> stored.find { it.id == id } },
                filter: { SimpleFilter filter -> stored.findAll { it[filter.field] == filter.value } },
                create: { TenantRequest request -> onCreate ? onCreate.call(request) : request },
                update: { String id, TenantRequest request -> onUpdate ? onUpdate.call(id, request) : request }
        ] as Repository
    }

    private static Repository<Tenant> tenantRepository(List<Tenant> stored, Closure<Tenant> onCreate = null) {
        return [
                list  : { stored },
                create: { Tenant tenant -> onCreate ? onCreate.call(tenant) : tenant }
        ] as Repository
    }
}
