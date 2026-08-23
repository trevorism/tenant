package com.trevorism.service

import com.google.gson.Gson
import com.trevorism.https.SecureHttpClient
import com.trevorism.model.TenantRequest
import jakarta.inject.Named
import jakarta.inject.Singleton
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.text.SimpleDateFormat

@Singleton
class TenantEmailer {

    private static final Logger log = LoggerFactory.getLogger(TenantEmailer)

    static final String EMAIL_BASE_URL = "https://email.action.trevorism.com"
    static final String LOGIN_BASE_URL = "https://login.auth.trevorism.com"
    static final String ADMIN_CONSOLE_URL = "https://admin.auth.trevorism.com"
    static final String PORTAL_URL = "https://trevorism.com/tenant"
    static final int PASSWORD_DEADLINE_HOURS = 24

    private SecureHttpClient appHttpClient
    private Gson gson = new Gson()

    TenantEmailer(@Named("appSecureHttpClient") SecureHttpClient appHttpClient) {
        this.appHttpClient = appHttpClient
    }

    void sendTenantIsReady(TenantRequest request) {
        String name = escapeHtml(request.name)
        String username = escapeHtml(request.ownerUsername)
        String loginUrl = "${LOGIN_BASE_URL}/${request.tenantGuid}"

        String body = "<p><strong>${name}</strong> is now active.</p>" +
                "<p>Sign in at <a href=\"${loginUrl}\">${loginUrl}</a> as <strong>${username}</strong>. " +
                "Bookmark that address &mdash; it is the only one that reaches your tenant.</p>" +
                "<p>Your tenant id is <code>${request.tenantGuid}</code>.</p>" +
                "<p><strong>Set your password within ${PASSWORD_DEADLINE_HOURS} hours.</strong> A separate email " +
                "carries a temporary password that expires after that. If it lapses, request a new one at " +
                "<a href=\"${LOGIN_BASE_URL}/forgot/${request.tenantGuid}\">${LOGIN_BASE_URL}/forgot/${request.tenantGuid}</a>.</p>" +
                "<p>Your trevorism.com account is unchanged and keeps its own password. The <strong>${username}</strong> " +
                "account inside ${name} is a separate account that happens to share the same name.</p>" +
                "<p>Invite and manage your users at <a href=\"${ADMIN_CONSOLE_URL}\">${ADMIN_CONSOLE_URL}</a>.</p>" +
                "<p>Your subscription renews monthly. Manage or cancel it any time from " +
                "<a href=\"${PORTAL_URL}\">${PORTAL_URL}</a>; cancelling suspends tenant administrator access.</p>"

        send(request, "Your Trevorism tenant ${request.name} is ready", body)
    }

    void sendPaymentFailed(TenantRequest request) {
        String name = escapeHtml(request.name)
        String body = "<p>We could not collect this month's payment for <strong>${name}</strong>.</p>" +
                "<p>Your tenant is still running, but access ends on <strong>${formatDate(request.accessEndsOn())}</strong> " +
                "unless the payment goes through.</p>" +
                "<p>Update your card at <a href=\"${PORTAL_URL}\">${PORTAL_URL}</a>.</p>"

        send(request, "Action needed: payment failed for ${request.name}", body)
    }

    void sendPaymentFailedReminder(TenantRequest request) {
        String name = escapeHtml(request.name)
        String body = "<p>This is a final reminder that <strong>${name}</strong> will be suspended on " +
                "<strong>${formatDate(request.accessEndsOn())}</strong> because its subscription is unpaid.</p>" +
                "<p>Update your card at <a href=\"${PORTAL_URL}\">${PORTAL_URL}</a> to keep access.</p>"

        send(request, "Final reminder: ${request.name} will be suspended", body)
    }

    void sendTenantSuspended(TenantRequest request) {
        String name = escapeHtml(request.name)
        String body = "<p><strong>${name}</strong> has been suspended because its subscription is no longer active.</p>" +
                "<p>Your data is retained. Restart the subscription at " +
                "<a href=\"${PORTAL_URL}\">${PORTAL_URL}</a> to restore administrator access.</p>"

        send(request, "${request.name} has been suspended", body)
    }

    void sendTenantRestored(TenantRequest request) {
        String name = escapeHtml(request.name)
        String loginUrl = "${LOGIN_BASE_URL}/${request.tenantGuid}"
        String body = "<p><strong>${name}</strong> is active again and your administrator access has been restored.</p>" +
                "<p>Sign in at <a href=\"${loginUrl}\">${loginUrl}</a>.</p>"

        send(request, "${request.name} is active again", body)
    }

    private void send(TenantRequest request, String subject, String body) {
        if (!request.ownerEmail) {
            return
        }
        Map mail = [subject: subject.toString(), recipients: [request.ownerEmail], body: body.toString()]
        try {
            appHttpClient.post("${EMAIL_BASE_URL}/mail/", gson.toJson(mail))
        } catch (Exception e) {
            log.warn("Unable to send \"${subject}\" for tenant request ${request.id}: ${e.message}")
        }
    }

    private static String formatDate(Date date) {
        return date ? new SimpleDateFormat("MMMM d, yyyy").format(date) : "soon"
    }

    private static String escapeHtml(String value) {
        return value?.replace("&", "&amp;")
                ?.replace("<", "&lt;")
                ?.replace(">", "&gt;")
                ?.replace('"', "&quot;")
    }
}
