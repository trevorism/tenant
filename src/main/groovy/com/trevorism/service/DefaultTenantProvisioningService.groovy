package com.trevorism.service

import com.google.gson.Gson
import com.trevorism.data.FastDatastoreRepository
import com.trevorism.data.Repository
import com.trevorism.data.model.filtering.FilterConstants
import com.trevorism.data.model.filtering.SimpleFilter
import com.trevorism.https.SecureHttpClient
import com.trevorism.model.ActivationRequest
import com.trevorism.model.AuthenticatedUser
import com.trevorism.model.BillingSubscription
import com.trevorism.model.ForgotPasswordRequest
import com.trevorism.model.PaymentRequest
import com.trevorism.model.RegistrationRequest
import com.trevorism.model.Tenant
import com.trevorism.model.TenantRequest
import com.trevorism.model.TenantRequestInput
import com.trevorism.model.TenantRequestStatus
import io.micronaut.security.authentication.Authentication
import jakarta.inject.Named
import jakarta.inject.Singleton
import org.slf4j.Logger
import org.slf4j.LoggerFactory

@Singleton
class DefaultTenantProvisioningService implements TenantProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(DefaultTenantProvisioningService)

    static final String STRIPE_BASE_URL = "https://stripe.trade.trevorism.com"
    static final String AUTH_BASE_URL = "https://auth.trevorism.com"
    static final String PORTAL_BASE_URL = "https://trevorism.com/tenant"
    static final double SUBSCRIPTION_PRICE_DOLLARS = 10.00d
    static final String TENANT_ADMIN_PERMISSIONS = "CRUDE"
    static final String ID_CLAIM = "id"
    static final String OWNER_FIELD = "ownerUserId"
    static final String SUBSCRIPTION_FIELD = "subscriptionId"

    private SecureHttpClient passThruHttpClient
    private SecureHttpClient appHttpClient
    private TenantRequestValidator validator = new TenantRequestValidator()
    private Gson gson = new Gson()

    Repository<TenantRequest> tenantRequestRepository
    Repository<Tenant> tenantRepository

    DefaultTenantProvisioningService(@Named("passThruSecureHttpClient") SecureHttpClient passThruHttpClient,
                                     @Named("appSecureHttpClient") SecureHttpClient appHttpClient) {
        this.passThruHttpClient = passThruHttpClient
        this.appHttpClient = appHttpClient
        this.tenantRequestRepository = new FastDatastoreRepository<>(TenantRequest, appHttpClient)
        this.tenantRepository = new FastDatastoreRepository<>(Tenant, appHttpClient)
    }

    @Override
    TenantRequest requestTenant(TenantRequestInput input, Authentication authentication) {
        String ownerUserId = callerId(authentication)
        validator.validate(input, ownerUserId, tenantRepository.list(), requestsForOwner(ownerUserId))

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

        PaymentRequest paymentRequest = new PaymentRequest(
                name: "Trevorism Tenant: ${request.name}",
                dollars: SUBSCRIPTION_PRICE_DOLLARS,
                successCallbackUrl: "${PORTAL_BASE_URL}?request=${request.id}&status=success",
                failureCallbackUrl: "${PORTAL_BASE_URL}?request=${request.id}&status=cancelled")

        String json = passThruHttpClient.post("${STRIPE_BASE_URL}/api/subscription/session", gson.toJson(paymentRequest))
        return gson.fromJson(json, Map)
    }

    @Override
    TenantRequest provision(String requestId, Authentication authentication) {
        TenantRequest request = requireOwnedRequest(requestId, authentication)
        if (request.status == TenantRequestStatus.PROVISIONED) {
            return request
        }

        BillingSubscription subscription = fetchSubscriptionForCaller()
        if (!subscription?.active) {
            throw new TenantRequestException("An active subscription is required before a tenant can be provisioned")
        }
        ensureSubscriptionFundsOnlyThisRequest(subscription.subscriptionId, request.id)

        Tenant tenant = tenantRepository.create(new Tenant(
                name: request.name,
                domain: request.domain,
                guid: UUID.randomUUID().toString()))

        createTenantAdministrator(request, tenant.guid)

        request.tenantGuid = tenant.guid
        request.billingCustomerId = subscription.customerId
        request.subscriptionId = subscription.subscriptionId
        request.status = TenantRequestStatus.PROVISIONED
        request.dateProvisioned = new Date()
        return tenantRequestRepository.update(request.id, request)
    }

    @Override
    int synchronizeEntitlements() {
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
        return changed
    }

    private boolean applyEntitlement(TenantRequest request) {
        Boolean active = subscriptionIsActive(request.billingCustomerId)
        if (active == null) {
            log.warn("Skipping tenant request ${request.id}; subscription state could not be determined")
            return false
        }

        if (!active && request.status == TenantRequestStatus.PROVISIONED) {
            setAdministratorActive(request, false)
            return updateStatus(request, TenantRequestStatus.SUSPENDED)
        }
        if (active && request.status == TenantRequestStatus.SUSPENDED) {
            setAdministratorActive(request, true)
            return updateStatus(request, TenantRequestStatus.PROVISIONED)
        }
        return false
    }

    private boolean updateStatus(TenantRequest request, String status) {
        request.status = status
        tenantRequestRepository.update(request.id, request)
        return true
    }

    private Boolean subscriptionIsActive(String billingCustomerId) {
        if (!billingCustomerId) {
            return null
        }
        try {
            String json = appHttpClient.get("${STRIPE_BASE_URL}/api/subscription/customer/${billingCustomerId}")
            return gson.fromJson(json, BillingSubscription)?.active
        } catch (Exception e) {
            log.warn("Unable to read subscription for customer ${billingCustomerId}: ${e.message}")
            return null
        }
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

    private void ensureSubscriptionFundsOnlyThisRequest(String subscriptionId, String requestId) {
        if (!subscriptionId) {
            throw new TenantRequestException("The active subscription is missing an identifier")
        }
        List<TenantRequest> funded = tenantRequestRepository.filter(
                new SimpleFilter(SUBSCRIPTION_FIELD, FilterConstants.OPERATOR_EQUAL, subscriptionId))

        if (funded.any { it.id != requestId }) {
            throw new TenantRequestException("This subscription already funds another tenant")
        }
    }

    private BillingSubscription fetchSubscriptionForCaller() {
        try {
            String json = passThruHttpClient.get("${STRIPE_BASE_URL}/api/subscription")
            return gson.fromJson(json, BillingSubscription)
        } catch (Exception e) {
            log.warn("Unable to read the caller's subscription: ${e.message}")
            return null
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
