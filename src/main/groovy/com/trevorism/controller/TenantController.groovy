package com.trevorism.controller

import com.trevorism.data.PingingDatastoreRepository
import com.trevorism.data.Repository
import com.trevorism.model.Tenant
import com.trevorism.secure.Roles
import com.trevorism.secure.Secure
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Delete
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Post
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag

@Controller("/tenant")
class TenantController {

    private Repository<Tenant> tenantRepository = new PingingDatastoreRepository<>(Tenant)

    @Tag(name = "Tenant Operations")
    @Operation(summary = "Lists all tenants **Secure")
    @Secure(Roles.SYSTEM)
    @Get(value = "/", produces = MediaType.APPLICATION_JSON)
    List<Tenant> listTenant() {
        tenantRepository.list()
    }

    @Tag(name = "Tenant Operations")
    @Operation(summary = "Gets tenant from a tenant id **Secure")
    @Secure(Roles.SYSTEM)
    @Get(value = "/{id}", produces = MediaType.APPLICATION_JSON)
    Tenant getTenant(String id) {
        tenantRepository.get(id)
    }

    @Tag(name = "Tenant Operations")
    @Operation(summary = "Creates a new tenant **Secure")
    @Secure(Roles.SYSTEM)
    @Post(value = "/", produces = MediaType.APPLICATION_JSON, consumes = MediaType.APPLICATION_JSON)
    Tenant saveTenant(@Body Tenant tenant) {
        tenant.guid = UUID.randomUUID().toString()
        tenantRepository.create(tenant)
    }

    @Tag(name = "Tenant Operations")
    @Operation(summary = "Remove tenant with tenant id **Secure")
    @Secure(Roles.USER)
    @Delete(value = "{id}", produces = MediaType.APPLICATION_JSON)
    Tenant removeTenant(String id) {
        tenantRepository.delete(id)
    }
}
