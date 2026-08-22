package com.trevorism.controller

import com.trevorism.data.FastDatastoreRepository
import com.trevorism.data.Repository
import com.trevorism.data.model.filtering.FilterConstants
import com.trevorism.data.model.filtering.SimpleFilter
import com.trevorism.model.Tenant
import com.trevorism.model.TenantBillingMode
import com.trevorism.secure.Roles
import com.trevorism.secure.Secure
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Delete
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Post
import io.micronaut.http.annotation.Status
import io.micronaut.security.authentication.Authentication
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag

@Controller("/tenant")
class TenantController {

    private static final String TENANT_CLAIM = "tenant"
    private static final String GUID_FIELD = "guid"

    private Repository<Tenant> tenantRepository = new FastDatastoreRepository<>(Tenant)

    @Tag(name = "Tenant Operations")
    @Operation(summary = "Lists all tenants **Secure")
    @Secure(Roles.SYSTEM)
    @Get(value = "/", produces = MediaType.APPLICATION_JSON)
    List<Tenant> listTenant() {
        tenantRepository.list()
    }

    @Tag(name = "Tenant Operations")
    @Operation(summary = "Gets the tenant of the current caller **Secure")
    @Secure(Roles.USER)
    @Get(value = "/me", produces = MediaType.APPLICATION_JSON)
    HttpResponse<Tenant> getCurrentTenant(Authentication authentication) {
        String guid = authentication?.attributes?.get(TENANT_CLAIM)
        if (!guid) {
            return HttpResponse.noContent()
        }
        def list = tenantRepository.filter(new SimpleFilter(GUID_FIELD, FilterConstants.OPERATOR_EQUAL, guid))
        return list ? HttpResponse.ok(list[0]) : HttpResponse.notFound()
    }

    @Tag(name = "Tenant Operations")
    @Operation(summary = "Gets tenant from a tenant id **Secure")
    @Secure(Roles.SYSTEM)
    @Get(value = "/{id}", produces = MediaType.APPLICATION_JSON)
    Tenant getTenant(String id) {
        tenantRepository.get(id)
    }

    @Tag(name = "Tenant Operations")
    @Operation(summary = "Creates a new unbilled tenant **Secure")
    @Secure(Roles.SYSTEM)
    @Post(value = "/", produces = MediaType.APPLICATION_JSON, consumes = MediaType.APPLICATION_JSON)
    @Status(HttpStatus.CREATED)
    Tenant saveTenant(@Body Tenant tenant) {
        tenant.guid = UUID.randomUUID().toString()
        tenant.billingMode = TenantBillingMode.UNBILLED
        tenantRepository.create(tenant)
    }

    @Tag(name = "Tenant Operations")
    @Operation(summary = "Remove tenant with tenant id **Secure")
    @Secure(Roles.SYSTEM)
    @Delete(value = "{id}", produces = MediaType.APPLICATION_JSON)
    Tenant removeTenant(String id) {
        tenantRepository.delete(id)
    }
}
