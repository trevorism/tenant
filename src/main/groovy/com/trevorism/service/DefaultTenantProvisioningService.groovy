package com.trevorism.service

import com.google.gson.Gson
import com.trevorism.data.FastDatastoreRepository
import com.trevorism.data.Repository
import com.trevorism.data.model.filtering.FilterConstants
import com.trevorism.data.model.filtering.SimpleFilter
import com.trevorism.entitlement.Checkout
import com.trevorism.entitlement.CheckoutRequest
import com.trevorism.entitlement.Entitlement
import com.trevorism.entitlement.TenantEntitlementProvider
import com.trevorism.https.SecureHttpClient
import com.trevorism.model.ActivationRequest
import com.trevorism.model.AuthenticatedUser
import com.trevorism.model.ForgotPasswordRequest
import com.trevorism.model.RegistrationRequest
import com.trevorism.model.Tenant
import com.trevorism.model.TenantBillingMode
import com.trevorism.model.TenantRequest
import com.trevorism.model.TenantRequestInput
import com.trevorism.model.TenantRequestStatus
import com.trevorism.model.TenantStatus
import io.micronaut.security.authentication.Authentication
import jakarta.inject.Named
import jakarta.inject.Singleton
import org.slf4j.Logger
import org.slf4j.LoggerFactory

@Singleton
class DefaultTenantProvisioningService implements TenantProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(DefaultTenantProvisioningService)

    static final String AUTH_BASE_URL = "https://auth.trevorism.com"
    static final String PORTAL_BASE_URL = "https://trevorism.com/tenant"
    static final double MONTHLY_PRICE_DOLLARS = 10.00d
    static final String TENANT_ADMIN_PERMISSIONS = "CRUDE"
    static final String ID_CLAIM = "id"
    static final String OWNER_FIELD = "ownerUserId"
    static final String ENTITLEMENT_FIELD = "entitlementId"
    static final String GUID_FIELD = "guid"

    private SecureHttpClient passThruHttpClient
    private SecureHttpClient appHttpClient
    private TenantRequestValidator validator = new TenantRequestValidator()
    private Gson gson = new Gson()

    TenantEntitlementProvider entitlementProvider
    Repository<TenantRequest> tenantRequestRepository
    Repository<Tenant> tenantRepository

    DefaultTenantProvisioningService(@Named("passThruSecureHttpClient") SecureHttpClient passThruHttpClient,
                                     @Named("appSecureHttpClient") SecureHttpClient appHttpClient,
                                     TenantEntitlementProvider entitlementProvider) {
        this.passThruHttpClient = passThruHttpClient
        this.appHttpClient = appHttpClient
        this.entitlementProvider = entitlementProvider
        this.tenantRequestRepository = new FastDatastoreRepository<>(TenantRequest, appHttpClient)
        this.tenantRepository = new FastDatastoreRepository<>(Tenant, appHttpClient)
    }

    @Override
    TenantRequest requestTenant(TenantRequestInput input, Authentication authentication) {
        String ownerUserId = callerId(authentication)
        validator.validate(input, ownerUserId, tenantRepository.list(), tenantRequestRepository.list() ?: [])

        AuthenticatedUser owner = fetchCallerIdentity()
        if (!owner?.username || !owner?.email) {
            throw new TenantRequestException("Unable to resolve the requesting user's account details")
        }

        TenantRequest request = new TenantRequest(
                name: TenantRequestValidator.normalizeName(input.name),
                domain: TenantRequestValidator.normalizeDomain(input.domain),
                status: TenantRequestStatus.PENDING_PAYMENT,
                ownerUserId: ownerUserId,
                ownerUsername: owner.username,
                ownerEmail: owner.email,
                dateCreated: new Date())

        return tenantRequestRepository.create(request)
    }

    @Override
    TenantRequest getRequestForCaller(Authentication authentication) {
        List<TenantRequest> owned = requestsForOwner(callerId(authentication))
        return owned.find { it.status != TenantRequestStatus.SUSPENDED } ?: owned[0]
    }

    @Override
    Map createCheckoutSession(String requestId, Authentication authentication) {
        TenantRequest request = requireOwnedRequest(requestId, authentication)
        if (request.status == TenantRequestStatus.PROVISIONED) {
            throw new TenantRequestException("Tenant request ${requestId} has already been provisioned")
        }

        CheckoutRequest checkoutRequest = new CheckoutRequest(
                planName: "Trevorism Tenant: ${request.name}",
                monthlyPriceDollars: MONTHLY_PRICE_DOLLARS,
                successUrl: "${PORTAL_BASE_URL}?request=${request.id}&status=success",
                cancelUrl: "${PORTAL_BASE_URL}?request=${request.id}&status=cancelled")

        Checkout checkout = entitlementProvider.startCheckout(checkoutRequest, authentication)
        return [id: checkout?.id, url: checkout?.url]
    }

    @Override
    TenantRequest provision(String requestId, Authentication authentication) {
        TenantRequest request = requireOwnedRequest(requestId, authentication)
        if (request.status == TenantRequestStatus.PROVISIONED) {
            return request
        }

        Entitlement entitlement = entitlementProvider.forCaller(authentication)
        if (!entitlement?.active) {
            throw new TenantRequestException("An active subscription is required before a tenant can be provisioned")
        }
        if (!entitlement.reference) {
            throw new TenantRequestException("The active subscription is missing a billing reference")
        }
        ensureEntitlementFundsOnlyThisRequest(entitlement.entitlementId, request.id)

        if (request.tenantGuid) {
            resumeTenant(request)
        } else {
            claimTenant(request)
        }

        request.billingProvider = entitlement.provider
        request.billingReference = entitlement.reference
        request.entitlementId = entitlement.entitlementId
        request.paidThrough = entitlement.paidThrough
        request.status = TenantRequestStatus.PROVISIONED
        request.dateProvisioned = new Date()
        return tenantRequestRepository.update(request.id, request)
    }

    @Override
    TenantRequest deleteRequest(String requestId) {
        TenantRequest request = requestId ? tenantRequestRepository.get(requestId) : null
        if (!request) {
            throw new TenantRequestException("Unable to locate tenant request ${requestId}")
        }
        if (request.tenantGuid) {
            throw new TenantRequestException("Remove tenant ${request.tenantGuid} before deleting this request")
        }
        return tenantRequestRepository.delete(requestId)
    }

    @Override
    Map synchronizeEntitlements() {
        int changed = 0
        List<TenantRequest> tracked = tenantRequestRepository.list().findAll {
            it.status == TenantRequestStatus.PROVISIONED || it.status == TenantRequestStatus.SUSPENDED
        }

        tracked.each { TenantRequest request ->
            try {
                if (applyEntitlement(request)) {
                    changed++
                }
            } catch (Exception e) {
                log.error("Unable to synchronize entitlement for tenant request ${request.id}", e)
            }
        }

        List<String> unmanaged = findUnmanagedSubscriptionTenants(tracked)
        unmanaged.each { log.error("Subscription tenant ${it} has no tenant request backing it") }

        return [reviewed: tracked.size(), updated: changed, unmanaged: unmanaged.size()]
    }

    private List<String> findUnmanagedSubscriptionTenants(List<TenantRequest> tracked) {
        Set<String> managedGuids = tracked.collect { it.tenantGuid }.findAll() as Set
        return tenantRepository.list()
                .findAll { TenantBillingMode.isSubscription(it.billingMode) && !managedGuids.contains(it.guid) }
                .collect { it.guid }
    }

    private boolean applyEntitlement(TenantRequest request) {
        Entitlement entitlement = entitlementProvider.forReference(request.billingReference)
        if (!entitlement || entitlement.undetermined) {
            log.warn("Skipping tenant request ${request.id}; entitlement state could not be determined")
            return false
        }

        if (!entitlement.active && request.status == TenantRequestStatus.PROVISIONED) {
            setTenantStatus(request.tenantGuid, TenantStatus.SUSPENDED)
            setAdministratorActive(request, false)
            return updateStatus(request, TenantRequestStatus.SUSPENDED, entitlement)
        }
        if (entitlement.active && request.status == TenantRequestStatus.SUSPENDED) {
            setTenantStatus(request.tenantGuid, TenantStatus.ACTIVE)
            setAdministratorActive(request, true)
            return updateStatus(request, TenantRequestStatus.PROVISIONED, entitlement)
        }
        return false
    }

    private void claimTenant(TenantRequest request) {
        TenantRequestValidator.validateAvailability(request.name, request.domain, tenantRepository.list())

        Tenant tenant = tenantRepository.create(new Tenant(
                name: request.name,
                domain: request.domain,
                guid: UUID.randomUUID().toString(),
                billingMode: TenantBillingMode.SUBSCRIPTION,
                status: TenantStatus.ACTIVE))

        request.tenantGuid = tenant.guid
        tenantRequestRepository.update(request.id, request)

        createTenantAdministrator(request, tenant.guid)
    }

    private void resumeTenant(TenantRequest request) {
        Tenant tenant = findTenantByGuid(request.tenantGuid)
        if (!tenant) {
            throw new TenantRequestException("Unable to locate tenant ${request.tenantGuid} for this request")
        }
        markTenant(tenant, TenantStatus.ACTIVE)

        if (request.status == TenantRequestStatus.SUSPENDED) {
            setAdministratorActive(request, true)
        } else {
            createTenantAdministrator(request, tenant.guid)
        }
    }

    private Tenant findTenantByGuid(String tenantGuid) {
        if (!tenantGuid) {
            return null
        }
        List<Tenant> found = tenantRepository.filter(
                new SimpleFilter(GUID_FIELD, FilterConstants.OPERATOR_EQUAL, tenantGuid))
        return found ? found[0] : null
    }

    private void setTenantStatus(String tenantGuid, String status) {
        Tenant tenant = findTenantByGuid(tenantGuid)
        if (!tenant) {
            log.warn("Unable to locate tenant ${tenantGuid} to mark it ${status}")
            return
        }
        markTenant(tenant, status)
    }

    private void markTenant(Tenant tenant, String status) {
        if (tenant.status == status) {
            return
        }
        tenant.status = status
        tenantRepository.update(tenant.id, tenant)
    }

    private boolean updateStatus(TenantRequest request, String status, Entitlement entitlement) {
        request.status = status
        request.paidThrough = entitlement.paidThrough ?: request.paidThrough
        tenantRequestRepository.update(request.id, request)
        return true
    }

    private void setAdministratorActive(TenantRequest request, boolean active) {
        ActivationRequest activationRequest = new ActivationRequest(
                username: request.ownerUsername,
                tenantGuid: request.tenantGuid,
                isAdmin: active)
        String path = active ? "activate" : "deactivate"
        appHttpClient.post("${AUTH_BASE_URL}/user/${path}", gson.toJson(activationRequest))
    }

    private void createTenantAdministrator(TenantRequest request, String tenantGuid) {
        RegistrationRequest registration = new RegistrationRequest(
                username: request.ownerUsername,
                password: UUID.randomUUID().toString(),
                email: request.ownerEmail,
                tenantGuid: tenantGuid,
                autoRegister: true,
                doNotNotifySiteAdminOfRegistration: true,
                permissions: TENANT_ADMIN_PERMISSIONS)
        appHttpClient.post("${AUTH_BASE_URL}/user/", gson.toJson(registration))

        ActivationRequest activation = new ActivationRequest(
                username: request.ownerUsername,
                tenantGuid: tenantGuid,
                isAdmin: true,
                doNotSendWelcomeEmail: false)
        appHttpClient.post("${AUTH_BASE_URL}/user/activate", gson.toJson(activation))

        ForgotPasswordRequest reset = new ForgotPasswordRequest(username: request.ownerUsername, tenantGuid: tenantGuid)
        appHttpClient.post("${AUTH_BASE_URL}/user/reset", gson.toJson(reset))
    }

    private void ensureEntitlementFundsOnlyThisRequest(String entitlementId, String requestId) {
        if (!entitlementId) {
            throw new TenantRequestException("The active subscription is missing an identifier")
        }
        List<TenantRequest> funded = tenantRequestRepository.filter(
                new SimpleFilter(ENTITLEMENT_FIELD, FilterConstants.OPERATOR_EQUAL, entitlementId))

        if (funded.any { it.id != requestId }) {
            throw new TenantRequestException("This subscription already funds another tenant")
        }
    }

    private AuthenticatedUser fetchCallerIdentity() {
        String json = passThruHttpClient.get("${AUTH_BASE_URL}/user/me")
        return gson.fromJson(json, AuthenticatedUser)
    }

    private TenantRequest requireOwnedRequest(String requestId, Authentication authentication) {
        String ownerUserId = callerId(authentication)
        TenantRequest request = requestId ? tenantRequestRepository.get(requestId) : null
        if (!request || request.ownerUserId != ownerUserId) {
            throw new TenantRequestException("Unable to locate tenant request ${requestId}")
        }
        return request
    }

    private List<TenantRequest> requestsForOwner(String ownerUserId) {
        return tenantRequestRepository.filter(new SimpleFilter(OWNER_FIELD, FilterConstants.OPERATOR_EQUAL, ownerUserId)) ?: []
    }

    private static String callerId(Authentication authentication) {
        String ownerUserId = authentication?.attributes?.get(ID_CLAIM)
        if (!ownerUserId) {
            throw new TenantRequestException("Unable to identify the requesting user")
        }
        return ownerUserId
    }
}
