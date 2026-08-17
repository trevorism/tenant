package com.trevorism.controller

import com.trevorism.model.TenantRequest
import com.trevorism.model.TenantRequestInput
import com.trevorism.secure.Roles
import com.trevorism.secure.Secure
import com.trevorism.service.TenantProvisioningService
import com.trevorism.service.TenantRequestException
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Post
import io.micronaut.http.annotation.Status
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.security.authentication.Authentication
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.inject.Inject

@Controller("/tenant/request")
class TenantRequestController {

    @Inject
    TenantProvisioningService tenantProvisioningService

    @Tag(name = "Tenant Request Operations")
    @Operation(summary = "Request a new tenant for the current caller **Secure")
    @Secure(Roles.USER)
    @Post(value = "/", produces = MediaType.APPLICATION_JSON, consumes = MediaType.APPLICATION_JSON)
    @Status(HttpStatus.CREATED)
    TenantRequest requestTenant(@Body TenantRequestInput input, Authentication authentication) {
        return invoke { tenantProvisioningService.requestTenant(input, authentication) }
    }

    @Tag(name = "Tenant Request Operations")
    @Operation(summary = "Gets the tenant request of the current caller **Secure")
    @Secure(Roles.USER)
    @Get(value = "/me", produces = MediaType.APPLICATION_JSON)
    HttpResponse<TenantRequest> getCurrentRequest(Authentication authentication) {
        TenantRequest request = invoke { tenantProvisioningService.getRequestForCaller(authentication) }
        return request ? HttpResponse.ok(request) : HttpResponse.noContent()
    }

    @Tag(name = "Tenant Request Operations")
    @Operation(summary = "Creates a subscription checkout session for a tenant request **Secure")
    @Secure(Roles.USER)
    @Get(value = "/{requestId}/session", produces = MediaType.APPLICATION_JSON)
    Map createCheckoutSession(String requestId, Authentication authentication) {
        return invoke { tenantProvisioningService.createCheckoutSession(requestId, authentication) }
    }

    @Tag(name = "Tenant Request Operations")
    @Operation(summary = "Provisions the tenant once the subscription is active **Secure")
    @Secure(Roles.USER)
    @Post(value = "/{requestId}/provision", produces = MediaType.APPLICATION_JSON)
    TenantRequest provision(String requestId, Authentication authentication) {
        return invoke { tenantProvisioningService.provision(requestId, authentication) }
    }

    @Tag(name = "Tenant Request Operations")
    @Operation(summary = "Suspends or restores tenants to match their subscription state **Secure")
    @Secure(Roles.SYSTEM)
    @Post(value = "/sweep", produces = MediaType.APPLICATION_JSON)
    Map synchronizeEntitlements() {
        return [updated: tenantProvisioningService.synchronizeEntitlements()]
    }

    private static <T> T invoke(Closure<T> closure) {
        try {
            return closure.call()
        } catch (TenantRequestException e) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, e.message)
        }
    }
}
