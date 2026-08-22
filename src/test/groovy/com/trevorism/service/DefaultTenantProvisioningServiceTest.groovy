package com.trevorism.service

import com.google.gson.Gson
import com.trevorism.data.Repository
import com.trevorism.data.model.filtering.SimpleFilter
import com.trevorism.entitlement.Checkout
import com.trevorism.entitlement.CheckoutRequest
import com.trevorism.entitlement.Entitlement
import com.trevorism.entitlement.EntitlementState
import com.trevorism.entitlement.TenantEntitlementProvider
import com.trevorism.https.SecureHttpClient
import com.trevorism.model.Tenant
import com.trevorism.model.TenantBillingMode
import com.trevorism.model.TenantRequest
import com.trevorism.model.TenantRequestInput
import com.trevorism.model.TenantRequestStatus
import io.micronaut.security.authentication.Authentication
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertThrows

class DefaultTenantProvisioningServiceTest {

    private static final String OWNER_ID = "user-1"
    private static final String PROVIDER = "STRIPE"
    private static final String USER_ME_URL = "https://auth.trevorism.com/user/me"

    private Gson gson = new Gson()
    private List<Map> appPosts = []
    private Map<String, String> passThruGets = [:]
    private Map<String, Object> entitlementOverrides = [:]

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
        assert !created.billingProvider
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
    void testCreateCheckoutSessionAsksTheProviderForTheTenDollarPlan() {
        CheckoutRequest captured = null
        entitlementOverrides.startCheckout = { CheckoutRequest r, Authentication a ->
            captured = r
            new Checkout(id: "cs_test_123", url: "https://checkout.example/cs_test_123")
        }
        def service = buildService(requestRepository([pendingRequest()]), tenantRepository([]))

        Map session = service.createCheckoutSession("req-1", auth(OWNER_ID))

        assert session.id == "cs_test_123"
        assert session.url == "https://checkout.example/cs_test_123"
        assert captured.monthlyPriceDollars == 10.0d
        assert captured.planName == "Trevorism Tenant: Acme"
        assert captured.successUrl == "https://trevorism.com/tenant?request=req-1&status=success"
        assert captured.cancelUrl == "https://trevorism.com/tenant?request=req-1&status=cancelled"
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
    void testProvisionRequiresAnActiveEntitlement() {
        entitlementOverrides.forCaller = { Authentication a -> Entitlement.inactive(PROVIDER, "cus_1") }
        def service = buildService(requestRepository([pendingRequest()]), tenantRepository([]))

        assertThrows(TenantRequestException) {
            service.provision("req-1", auth(OWNER_ID))
        }
        assert appPosts.isEmpty()
    }

    @Test
    void testProvisionTreatsAnUndeterminedEntitlementAsUnpaid() {
        entitlementOverrides.forCaller = { Authentication a -> Entitlement.unknown(PROVIDER) }
        def service = buildService(requestRepository([pendingRequest()]), tenantRepository([]))

        assertThrows(TenantRequestException) {
            service.provision("req-1", auth(OWNER_ID))
        }
        assert appPosts.isEmpty()
    }

    @Test
    void testProvisionRejectsAnEntitlementWithoutABillingReference() {
        entitlementOverrides.forCaller = { Authentication a ->
            new Entitlement(provider: PROVIDER, entitlementId: "sub_1", state: EntitlementState.ACTIVE)
        }
        def service = buildService(requestRepository([pendingRequest()]), tenantRepository([]))

        assertThrows(TenantRequestException) {
            service.provision("req-1", auth(OWNER_ID))
        }
        assert appPosts.isEmpty()
    }

    @Test
    void testProvisionCreatesTheTenantAndPromotesTheOwnerToTenantAdmin() {
        Date renewal = new Date()
        entitlementOverrides.forCaller = { Authentication a -> Entitlement.active(PROVIDER, "cus_1", "sub_1", renewal) }
        TenantRequest owned = pendingRequest()
        Tenant createdTenant = null
        TenantRequest updated = null
        def service = buildService(
                requestRepository([owned], null, { String id, TenantRequest r -> updated = r; r }),
                tenantRepository([], { Tenant t -> createdTenant = t; t }))

        TenantRequest result = service.provision("req-1", auth(OWNER_ID))

        assert createdTenant.name == "Acme"
        assert createdTenant.domain == "acme.com"
        assert createdTenant.billingMode == TenantBillingMode.SUBSCRIPTION
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
        assert updated.billingProvider == PROVIDER
        assert updated.billingReference == "cus_1"
        assert updated.entitlementId == "sub_1"
        assert updated.paidThrough == renewal
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
    void testProvisionRejectsAnEntitlementThatAlreadyFundsAnotherTenant() {
        entitlementOverrides.forCaller = { Authentication a -> Entitlement.active(PROVIDER, "cus_1", "sub_1", null) }
        TenantRequest other = new TenantRequest(id: "req-2", entitlementId: "sub_1", status: TenantRequestStatus.PROVISIONED)
        def service = buildService(requestRepository([pendingRequest(), other]), tenantRepository([]))

        assertThrows(TenantRequestException) {
            service.provision("req-1", auth(OWNER_ID))
        }
        assert appPosts.isEmpty()
    }

    @Test
    void testProvisionRejectsAnEntitlementWhoseEarlierTenantIsMerelySuspended() {
        entitlementOverrides.forCaller = { Authentication a -> Entitlement.active(PROVIDER, "cus_1", "sub_1", null) }
        TenantRequest other = new TenantRequest(id: "req-2", entitlementId: "sub_1", status: TenantRequestStatus.SUSPENDED)
        def service = buildService(requestRepository([pendingRequest(), other]), tenantRepository([]))

        assertThrows(TenantRequestException) {
            service.provision("req-1", auth(OWNER_ID))
        }
        assert appPosts.isEmpty()
    }

    @Test
    void testDeleteRequestRemovesAnAbandonedRequest() {
        List<String> deleted = []
        def service = buildService(requestRepository([pendingRequest()], null, null, deleted), tenantRepository([]))

        service.deleteRequest("req-1")

        assert deleted == ["req-1"]
    }

    @Test
    void testDeleteRequestRefusesToOrphanAProvisionedTenant() {
        List<String> deleted = []
        def service = buildService(requestRepository([provisionedRequest()], null, null, deleted), tenantRepository([]))

        assertThrows(TenantRequestException) {
            service.deleteRequest("req-1")
        }
        assert deleted.isEmpty()
    }

    @Test
    void testDeleteRequestRejectsAnUnknownRequest() {
        def service = buildService(requestRepository([]), tenantRepository([]))

        assertThrows(TenantRequestException) {
            service.deleteRequest("missing")
        }
    }

    @Test
    void testSynchronizeEntitlementsSuspendsALapsedTenant() {
        entitlementOverrides.forReference = { String reference -> Entitlement.inactive(PROVIDER, reference) }
        TenantRequest updated = null
        def service = buildService(
                requestRepository([provisionedRequest()], null, { String id, TenantRequest r -> updated = r; r }),
                tenantRepository([]))

        assert service.synchronizeEntitlements().updated == 1
        assert appPosts[0].url == "https://auth.trevorism.com/user/deactivate"
        assert updated.status == TenantRequestStatus.SUSPENDED
    }

    @Test
    void testSynchronizeEntitlementsRestoresARenewedTenant() {
        Date renewal = new Date()
        entitlementOverrides.forReference = { String reference -> Entitlement.active(PROVIDER, reference, "sub_1", renewal) }
        TenantRequest suspended = provisionedRequest()
        suspended.status = TenantRequestStatus.SUSPENDED
        TenantRequest updated = null
        def service = buildService(
                requestRepository([suspended], null, { String id, TenantRequest r -> updated = r; r }),
                tenantRepository([]))

        assert service.synchronizeEntitlements().updated == 1
        Map activation = gson.fromJson(appPosts[0].body as String, Map)
        assert appPosts[0].url == "https://auth.trevorism.com/user/activate"
        assert activation.isAdmin
        assert updated.status == TenantRequestStatus.PROVISIONED
        assert updated.paidThrough == renewal
    }

    @Test
    void testSynchronizeEntitlementsLeavesAHealthyTenantAlone() {
        entitlementOverrides.forReference = { String reference -> Entitlement.active(PROVIDER, reference, "sub_1", null) }
        def service = buildService(requestRepository([provisionedRequest()]), tenantRepository([]))

        assert service.synchronizeEntitlements().updated == 0
        assert appPosts.isEmpty()
    }

    @Test
    void testSynchronizeEntitlementsSkipsWhenEntitlementStateCannotBeRead() {
        TenantRequest provisioned = provisionedRequest()
        def service = buildService(requestRepository([provisioned]), tenantRepository([]))

        assert service.synchronizeEntitlements().updated == 0
        assert appPosts.isEmpty()
        assert provisioned.status == TenantRequestStatus.PROVISIONED
    }

    @Test
    void testSynchronizeEntitlementsNeverTouchesUnbilledTenants() {
        Tenant personalProject = new Tenant(id: "t-1", name: "Sandbox", domain: "sandbox.test",
                guid: "guid-unbilled", billingMode: TenantBillingMode.UNBILLED)
        Tenant legacyTenant = new Tenant(id: "t-2", name: "Legacy", domain: "legacy.test", guid: "guid-legacy")
        def service = buildService(requestRepository([]), tenantRepository([personalProject, legacyTenant]))

        Map result = service.synchronizeEntitlements()

        assert result.reviewed == 0
        assert result.updated == 0
        assert result.unmanaged == 0
        assert appPosts.isEmpty()
    }

    @Test
    void testSynchronizeEntitlementsReportsASubscriptionTenantWithNoBackingRequest() {
        Tenant orphan = new Tenant(id: "t-3", name: "Orphan", domain: "orphan.test",
                guid: "guid-orphan", billingMode: TenantBillingMode.SUBSCRIPTION)
        def service = buildService(requestRepository([]), tenantRepository([orphan]))

        assert service.synchronizeEntitlements().unmanaged == 1
    }

    @Test
    void testSynchronizeEntitlementsDoesNotReportATrackedSubscriptionTenant() {
        entitlementOverrides.forReference = { String reference -> Entitlement.active(PROVIDER, reference, "sub_1", null) }
        Tenant tracked = new Tenant(id: "t-4", name: "Acme", domain: "acme.com",
                guid: "guid-1", billingMode: TenantBillingMode.SUBSCRIPTION)
        def service = buildService(requestRepository([provisionedRequest()]), tenantRepository([tracked]))

        Map result = service.synchronizeEntitlements()

        assert result.reviewed == 1
        assert result.unmanaged == 0
    }

    @Test
    void testSynchronizeEntitlementsIgnoresRequestsAwaitingPayment() {
        def service = buildService(requestRepository([pendingRequest()]), tenantRepository([]))

        assert service.synchronizeEntitlements().updated == 0
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
        request.billingProvider = PROVIDER
        request.billingReference = "cus_1"
        request.entitlementId = "sub_1"
        request.dateProvisioned = new Date()
        return request
    }

    private static Authentication auth(String id) {
        [getName: { "caller" }, getRoles: { ["user"] }, getAttributes: { [id: id] }] as Authentication
    }

    private DefaultTenantProvisioningService buildService(Repository<TenantRequest> requests, Repository<Tenant> tenants) {
        DefaultTenantProvisioningService service = new DefaultTenantProvisioningService(
                stubClient(passThruGets, []), stubClient([:], appPosts), stubEntitlementProvider())
        service.tenantRequestRepository = requests
        service.tenantRepository = tenants
        return service
    }

    private TenantEntitlementProvider stubEntitlementProvider() {
        Map behaviour = [
                getName      : { PROVIDER },
                startCheckout: { CheckoutRequest r, Authentication a -> new Checkout(id: "cs_test_123", url: "https://checkout.example/cs_test_123") },
                forCaller    : { Authentication a -> Entitlement.unknown(PROVIDER) },
                forReference : { String reference -> Entitlement.unknown(PROVIDER) }
        ]
        return (behaviour + entitlementOverrides) as TenantEntitlementProvider
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
                    return "{}"
                }
        ] as SecureHttpClient
    }

    private static Repository<TenantRequest> requestRepository(List<TenantRequest> stored,
                                                               Closure<TenantRequest> onCreate = null,
                                                               Closure<TenantRequest> onUpdate = null,
                                                               List<String> deleted = []) {
        return [
                list  : { stored },
                get   : { String id -> stored.find { it.id == id } },
                filter: { SimpleFilter filter -> stored.findAll { it[filter.field] == filter.value } },
                create: { TenantRequest request -> onCreate ? onCreate.call(request) : request },
                update: { String id, TenantRequest request -> onUpdate ? onUpdate.call(id, request) : request },
                delete: { String id ->
                    deleted << id
                    return stored.find { it.id == id }
                }
        ] as Repository
    }

    private static Repository<Tenant> tenantRepository(List<Tenant> stored, Closure<Tenant> onCreate = null) {
        return [
                list  : { stored },
                create: { Tenant tenant -> onCreate ? onCreate.call(tenant) : tenant }
        ] as Repository
    }
}
