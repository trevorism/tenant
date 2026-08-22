package com.trevorism.model

class TenantRequestView {

    String id
    String name
    String domain
    String status
    Date paidThrough
    Date dateCreated
    Date dateProvisioned

    static TenantRequestView from(TenantRequest request) {
        if (!request) {
            return null
        }
        return new TenantRequestView(
                id: request.id,
                name: request.name,
                domain: request.domain,
                status: request.status,
                paidThrough: request.paidThrough,
                dateCreated: request.dateCreated,
                dateProvisioned: request.dateProvisioned)
    }
}
