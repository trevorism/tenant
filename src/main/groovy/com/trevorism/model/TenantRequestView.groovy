package com.trevorism.model

class TenantRequestView {

    static final String LOGIN_BASE_URL = "https://login.auth.trevorism.com"

    String id
    String name
    String domain
    String status
    String tenantGuid
    String loginUrl
    Date paidThrough
    Date dateCreated
    Date dateProvisioned
    Date dateLapsed
    Date accessEndsOn

    static TenantRequestView from(TenantRequest request) {
        if (!request) {
            return null
        }
        return new TenantRequestView(
                id: request.id,
                name: request.name,
                domain: request.domain,
                status: request.status,
                tenantGuid: request.tenantGuid,
                loginUrl: toLoginUrl(request.tenantGuid),
                paidThrough: request.paidThrough,
                dateCreated: request.dateCreated,
                dateProvisioned: request.dateProvisioned,
                dateLapsed: request.dateLapsed,
                accessEndsOn: toAccessEndsOn(request))
    }

    private static String toLoginUrl(String tenantGuid) {
        return tenantGuid ? "${LOGIN_BASE_URL}/${tenantGuid}" : null
    }

    private static Date toAccessEndsOn(TenantRequest request) {
        if (request.status != TenantRequestStatus.PROVISIONED) {
            return null
        }
        return request.accessEndsOn()
    }
}
