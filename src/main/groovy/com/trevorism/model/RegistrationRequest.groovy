package com.trevorism.model

class RegistrationRequest {

    String username
    String password
    String email
    String tenantGuid
    boolean autoRegister = false
    boolean doNotNotifySiteAdminOfRegistration = false
    String permissions
}
