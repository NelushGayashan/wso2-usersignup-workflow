package com.mycompany.custom.usersignup;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.apimgt.api.WorkflowResponse;
import org.wso2.carbon.apimgt.impl.dto.WorkflowDTO;
import org.wso2.carbon.apimgt.impl.workflow.UserSignUpApprovalWorkflowExecutor;
import org.wso2.carbon.apimgt.impl.workflow.WorkflowException;
import org.wso2.carbon.apimgt.impl.workflow.WorkflowStatus;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.user.api.UserStoreManager;
import org.wso2.carbon.user.api.UserStoreException;

import java.io.UnsupportedEncodingException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.mail.*;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

/**
 * Custom workflow executor for WSO2 API Manager user signup workflows.
 * Captures user contact details during submission and handles asynchronous email notifications
 * for administrative review alerts and final registration outcomes.
 */
public class CustomUserSignUpWorkflowExecutor extends UserSignUpApprovalWorkflowExecutor {

    private static final Log log = LogFactory.getLog(CustomUserSignUpWorkflowExecutor.class);

    @SuppressWarnings("HttpUrlsUsage")
    private static final String EMAIL_CLAIM_URI = "http://wso2.org/claims/emailaddress";

    private static final ConcurrentMap<String, String> pendingUserEmailCache = new ConcurrentHashMap<>();

    private String mailSmtpHost    = "localhost";
    private String mailSmtpPort    = "1025";
    private String mailFromAddress = "apim-noreply@example.com";
    private String mailFromName    = "WSO2 API Manager";
    private String adminUsername   = "admin";
    private String portalUrl       = "https://localhost:9443/devportal";

    /**
     * Sets the SMTP server host address used for dispatching notifications.
     *
     * @param v the SMTP host address
     */
    public void setMailSmtpHost(String v) { this.mailSmtpHost = v; }

    /**
     * Retrieves the configured SMTP server host address.
     *
     * @return the SMTP host address
     */
    public String getMailSmtpHost() { return mailSmtpHost; }

    /**
     * Sets the SMTP server port used for dispatching notifications.
     *
     * @param v the SMTP port number as a string
     */
    public void setMailSmtpPort(String v) { this.mailSmtpPort = v; }

    /**
     * Retrieves the configured SMTP server port.
     *
     * @return the SMTP port number
     */
    public String getMailSmtpPort() { return mailSmtpPort; }

    /**
     * Sets the outgoing email address used in the "From" header.
     *
     * @param v the originating email address
     */
    public void setMailFromAddress(String v) { this.mailFromAddress = v; }

    /**
     * Retrieves the configured outgoing email address.
     *
     * @return the originating email address
     */
    public String getMailFromAddress() { return mailFromAddress; }

    /**
     * Sets the display name used in the "From" header of outgoing emails.
     *
     * @param v the originating display name
     */
    public void setMailFromName(String v) { this.mailFromName = v; }

    /**
     * Retrieves the configured display name for outgoing emails.
     *
     * @return the originating display name
     */
    public String getMailFromName() { return mailFromName; }

    /**
     * Sets the username of the administrator who should receive pending registration alerts.
     *
     * @param v the administrator's username
     */
    public void setAdminUsername(String v) { this.adminUsername = v; }

    /**
     * Retrieves the configured administrator username for alert routing.
     *
     * @return the administrator's username
     */
    public String getAdminUsername() { return adminUsername; }

    /**
     * Sets the base URL of the API Manager Developer Portal.
     *
     * @param v the developer portal URL
     */
    public void setPortalUrl(String v) { this.portalUrl = v; }

    /**
     * Retrieves the configured Developer Portal base URL.
     *
     * @return the developer portal URL
     */
    public String getPortalUrl() { return portalUrl; }

    /**
     * Returns the workflow type handled by this executor.
     *
     * @return the workflow type identifier
     */
    @Override
    public String getWorkflowType() {
        return "AM_USER_SIGNUP";
    }

    /**
     * Executes the initial user signup workflow request when a form is submitted.
     *
     * @param workflowDTO the data transfer object containing workflow details
     * @return the response indicating the state of the workflow
     * @throws WorkflowException if an error occurs during execution
     */
    @Override
    public WorkflowResponse execute(WorkflowDTO workflowDTO) throws WorkflowException {
        WorkflowResponse response = superExecute(workflowDTO);
        String username = workflowDTO.getWorkflowReference();
        log.info("Signup submitted by: " + username + " — notifying admin and user.");
        sendAdminAlert(username);
        sendUserPendingAlert(username);
        return response;
    }

    /**
     * Processes completion steps after an admin approves or rejects a registration request.
     *
     * @param workflowDTO the data transfer object containing workflow decision details
     * @return the response indicating the final state of the workflow
     * @throws WorkflowException if an error occurs during completion processing
     */
    @Override
    public WorkflowResponse complete(WorkflowDTO workflowDTO) throws WorkflowException {
        WorkflowResponse response = superComplete(workflowDTO);
        String username = workflowDTO.getWorkflowReference();
        WorkflowStatus status = workflowDTO.getStatus();
        log.info("Admin decision for user: " + username + " — status: " + status);
        sendUserNotification(username, status);
        return response;
    }

    /**
     * Wraps the superclass execution framework to provide integration testing seams.
     *
     * @param workflowDTO the workflow data transfer object
     * @return the standard workflow response
     * @throws WorkflowException if superclass execution fails
     */
    WorkflowResponse superExecute(WorkflowDTO workflowDTO) throws WorkflowException {
        return super.execute(workflowDTO);
    }

    /**
     * Wraps the superclass completion framework to provide integration testing seams.
     *
     * @param workflowDTO the workflow data transfer object
     * @return the standard workflow response
     * @throws WorkflowException if superclass completion fails
     */
    WorkflowResponse superComplete(WorkflowDTO workflowDTO) throws WorkflowException {
        return super.complete(workflowDTO);
    }

    /**
     * Retrieves administrative profile info and issues pending registration review requests.
     *
     * @param username the username of the applicant awaiting approval
     */
    private void sendAdminAlert(String username) {
        try {
            PrivilegedCarbonContext ctx = PrivilegedCarbonContext.getThreadLocalCarbonContext();
            UserStoreManager usm = ctx.getUserRealm().getUserStoreManager();

            String resolvedAdmin = (adminUsername != null && !adminUsername.isEmpty())
                    ? adminUsername
                    : ctx.getUserRealm().getRealmConfiguration().getAdminUserName();

            String adminEmail = usm.getUserClaimValue(resolvedAdmin, EMAIL_CLAIM_URI, null);
            String userEmail  = usm.getUserClaimValue(username, EMAIL_CLAIM_URI, null);

            if (userEmail != null && !userEmail.isEmpty()) {
                pendingUserEmailCache.put(username, userEmail);
            }

            String adminUrl   = portalUrl.replace("devportal", "admin");
            String submittedAt = ZonedDateTime.now(ZoneOffset.UTC)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'"));

            if (adminEmail != null && !adminEmail.isEmpty()) {
                sendEmail(adminEmail,
                        "Action required: pending developer registration",
                        getAdminPendingTemplate(username, userEmail, adminUrl, submittedAt));
            } else {
                log.warn("No email claim found for admin: " + resolvedAdmin);
            }
        } catch (UserStoreException e) {
            log.error("Error reading claims during admin alert dispatch", e);
        }
    }

    /**
     * Sends an acknowledgment notification to the user indicating their application is under review.
     *
     * @param username the username of the applicant
     */
    private void sendUserPendingAlert(String username) {
        try {
            PrivilegedCarbonContext ctx = PrivilegedCarbonContext.getThreadLocalCarbonContext();
            UserStoreManager usm = ctx.getUserRealm().getUserStoreManager();
            String userEmail = usm.getUserClaimValue(username, EMAIL_CLAIM_URI, null);

            if (userEmail != null && !userEmail.isEmpty()) {
                sendEmail(userEmail,
                        "Your developer registration is pending approval",
                        getUserPendingTemplate(username));
            } else {
                log.warn("No email claim found for user: " + username + " — pending alert not sent.");
            }
        } catch (UserStoreException e) {
            log.error("Error reading user email during pending alert dispatch", e);
        }
    }

    /**
     * Looks up user information from active directories or local state caches to forward outcomes.
     *
     * @param username the username of the applicant
     * @param status   the final decision status of the workflow
     */
    private void sendUserNotification(String username, WorkflowStatus status) {
        String userEmail = null;
        try {
            PrivilegedCarbonContext ctx = PrivilegedCarbonContext.getThreadLocalCarbonContext();
            UserStoreManager usm = ctx.getUserRealm().getUserStoreManager();
            userEmail = usm.getUserClaimValue(username, EMAIL_CLAIM_URI, null);
        } catch (UserStoreException e) {
            log.warn("Could not look up live email claim for user: " + username
                    + " (account may already be removed — falling back to cached email). Cause: "
                    + e.getMessage());
        }

        if ((userEmail == null || userEmail.isEmpty())) {
            userEmail = pendingUserEmailCache.get(username);
            if (userEmail != null) {
                log.info("Using cached email captured at signup time for user: " + username);
            }
        }

        pendingUserEmailCache.remove(username);

        if (userEmail != null && !userEmail.isEmpty()) {
            if (WorkflowStatus.APPROVED.equals(status)) {
                sendEmail(userEmail,
                        "Your developer account has been approved",
                        getUserApprovedTemplate(username));
            } else if (WorkflowStatus.REJECTED.equals(status)) {
                sendEmail(userEmail,
                        "An update on your registration request",
                        getUserRejectedTemplate(username));
            }
        } else {
            log.warn("No email available (live or cached) for user: " + username
                    + " — notification not sent.");
        }
    }

    /**
     * Establishes outbound mail server links and delivers individual transmission requests.
     *
     * @param recipient   the destination email address
     * @param subject     the subject line of the email
     * @param htmlContent the HTML-formatted body of the email message
     */
    private void sendEmail(String recipient, String subject, String htmlContent) {
        Properties props = System.getProperties();
        props.setProperty("mail.smtp.host", mailSmtpHost);
        props.setProperty("mail.smtp.port", mailSmtpPort);
        Session session = Session.getInstance(props);
        try {
            MimeMessage msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress(mailFromAddress, mailFromName));
            msg.addRecipient(Message.RecipientType.TO, new InternetAddress(recipient));
            msg.setSubject(subject);
            msg.setContent(htmlContent, "text/html; charset=utf-8");
            Transport.send(msg);
            log.info("Email dispatched to: " + recipient);
        } catch (MessagingException | UnsupportedEncodingException e) {
            log.error("Failed to send email to: " + recipient, e);
        }
    }

    /**
     * Sanitizes strings containing structural markup characters to counter cross-site injection risks.
     *
     * @param s the input string to sanitize
     * @return the HTML-escaped string, or an empty string if input is null
     */
    private String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;")
                .replace("=", "&#61;");
    }

    /**
     * Constructs generic component and block level styling sheets for rendering engine displays.
     *
     * @return a string containing minified CSS styles for email templates
     */
    private String sharedCss() {
        return "<style>"
                + "body{margin:0;padding:32px 16px;background:#f1f5f9;"
                + "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;"
                + "color:#334155;-webkit-font-smoothing:antialiased}"
                + ".wrap{max-width:540px;margin:0 auto;background:#fff;border-radius:12px;border:1px solid #e2e8f0;overflow:hidden}"
                + ".hdr{background:#0f172a;padding:22px 28px;display:flex;align-items:center;gap:12px}"
                + ".hdr-icon{width:32px;height:32px;border-radius:50%;background:rgba(255,255,255,.1);"
                + "display:flex;align-items:center;justify-content:center;font-size:18px;color:#cbd5e1;flex-shrink:0}"
                + ".hdr-sub{margin:0;font-size:11px;color:#94a3b8;letter-spacing:.5px;text-transform:uppercase}"
                + ".hdr-main{margin:0;font-size:14px;font-weight:600;color:#f1f5f9}"
                + ".body{padding:24px 28px}"
                + ".banner{display:flex;align-items:flex-start;gap:10px;border-radius:8px;padding:12px 14px;margin-bottom:20px}"
                + ".b-amber{background:#fefce8;border:1px solid #fde047}"
                + ".b-green{background:#f0fdf4;border:1px solid #bbf7d0}"
                + ".b-red{background:#fef2f2;border:1px solid #fecaca}"
                + ".bi{font-size:18px;flex-shrink:0;margin-top:1px}"
                + ".bt{margin:0 0 2px;font-size:13px;font-weight:600}"
                + ".bb{margin:0;font-size:12px;line-height:1.5}"
                + ".amber-title{color:#713f12}.amber-body{color:#a16207}"
                + ".green-title{color:#14532d}.green-body{color:#166534}"
                + ".red-title{color:#7f1d1d}.red-body{color:#991b1b}"
                + ".sec-lbl{margin:0 0 8px;font-size:11px;font-weight:700;color:#64748b;text-transform:uppercase;letter-spacing:.6px}"
                + ".dt{width:100%;border-collapse:collapse;border:1px solid #e2e8f0;border-radius:8px;overflow:hidden;margin-bottom:20px}"
                + ".dt td{padding:10px 14px;font-size:13px;border-bottom:1px solid #e2e8f0;vertical-align:middle}"
                + ".dt tr:last-child td{border-bottom:none}"
                + ".dt td.l{font-weight:600;color:#475569;background:#f8fafc;width:110px;border-right:1px solid #e2e8f0;font-size:12px}"
                + ".dt td.v{color:#0f172a;font-family:'SFMono-Regular',Consolas,monospace;font-size:12px}"
                + ".pill-a{display:inline-block;background:#fef9c3;color:#713f12;font-size:11px;font-weight:600;padding:2px 9px;border-radius:99px}"
                + ".pill-g{display:inline-block;background:#dcfce7;color:#14532d;font-size:11px;font-weight:600;padding:2px 9px;border-radius:99px}"
                + ".intro{font-size:14px;line-height:1.7;color:#475569;margin:0 0 20px}"
                + ".gname{font-size:16px;font-weight:600;color:#1e293b;margin:0 0 3px}"
                + ".gsub{font-size:12px;color:#64748b;margin:0 0 16px}"
                + ".fbox{background:#f8fafc;border:1px solid #e2e8f0;border-radius:8px;padding:14px 16px;margin-bottom:20px}"
                + ".fi{display:flex;align-items:flex-start;gap:8px;margin-bottom:8px}"
                + ".fi:last-child{margin-bottom:0}"
                + ".fdot{width:5px;height:5px;border-radius:50%;flex-shrink:0;margin-top:6px}"
                + ".fdot-g{background:#16a34a}"
                + ".ft{font-size:13px;color:#334155;line-height:1.5}"
                + ".sbox{background:#fef2f2;border:1px solid #fecaca;border-radius:8px;padding:12px 16px;margin-bottom:20px;font-size:13px;color:#991b1b;line-height:1.5}"
                + ".btn-dark{display:block;text-align:center;background:#0f172a;color:#fff;text-decoration:none;padding:11px 20px;border-radius:8px;font-size:13px;font-weight:600}"
                + ".btn-green{display:block;text-align:center;background:#16a34a;color:#fff;text-decoration:none;padding:11px 20px;border-radius:8px;font-size:13px;font-weight:600}"
                + ".btn-ghost{display:block;text-align:center;background:#f8fafc;color:#334155;text-decoration:none;padding:11px 20px;border-radius:8px;font-size:13px;font-weight:600;border:1px solid #e2e8f0}"
                + ".footer{border-top:1px solid #f1f5f9;padding:12px 28px;display:flex;justify-content:space-between}"
                + ".fs{margin:0;font-size:11px;color:#94a3b8}"
                + "</style>";
    }

    /**
     * Assembles the presentation structure for dashboard alert actions sent directly to administration queues.
     *
     * @param user        the username of the applicant
     * @param email       the email address of the applicant
     * @param adminUrl    the base URL for the admin console
     * @param submittedAt the formatted timestamp of the submission
     * @return a complete HTML string for the pending administrative alert email
     */
    private String getAdminPendingTemplate(String user, String email, String adminUrl, String submittedAt) {
        String safeEmail = (email != null && !email.isEmpty()) ? email : "Not provided";
        return "<!DOCTYPE html><html lang='en'><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>Action required: pending registration</title>"
                + sharedCss()
                + "</head><body><div class='wrap'>"
                + "<div class='hdr'>"
                + "  <div class='hdr-icon'>&#128276;</div>"
                + "  <div><p class='hdr-sub'>WSO2 API Manager</p><p class='hdr-main'>Admin Notification</p></div>"
                + "</div>"
                + "<div class='body'>"
                + "  <div class='banner b-amber'>"
                + "    <span class='bi' style='color:#a16207'>&#9202;</span>"
                + "    <div>"
                + "      <p class='bt amber-title'>Action required: pending registration</p>"
                + "      <p class='bb amber-body'>A new developer is waiting for your approval before they can access the portal.</p>"
                + "    </div>"
                + "  </div>"
                + "  <p class='sec-lbl'>Applicant details</p>"
                + "  <table class='dt'>"
                + "    <tr><td class='l'>Username</td><td class='v'>" + esc(user) + "</td></tr>"
                + "    <tr><td class='l'>Email</td><td class='v'>" + esc(safeEmail) + "</td></tr>"
                + "    <tr><td class='l'>Submitted</td><td class='v'>" + submittedAt + "</td></tr>"
                + "    <tr><td class='l'>Status</td><td class='v'><span class='pill-a'>Pending approval</span></td></tr>"
                + "  </table>"
                + "  <a href='" + adminUrl + "/tasks/user-creation' class='btn-dark'>Review in Admin Console &rarr;</a>"
                + "</div>"
                + "<div class='footer'>"
                + "  <p class='fs'>WSO2 API Manager &middot; Security &amp; Audit</p>"
                + "  <p class='fs'>Automated alert</p>"
                + "</div>"
                + "</div></body></html>";
    }

    /**
     * Assembles the presentation structure for acknowledging receipt of an applicant's registration request.
     *
     * @param username the username of the applicant
     * @return a complete HTML string for the pending applicant notification email
     */
    private String getUserPendingTemplate(String username) {
        return "<!DOCTYPE html><html lang='en'><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>Your registration is pending approval</title>"
                + sharedCss()
                + "</head><body><div class='wrap'>"
                + "<div class='hdr'>"
                + "  <div class='hdr-icon'>&#9670;</div>"
                + "  <div><p class='hdr-sub'>WSO2 API Manager</p><p class='hdr-main'>Developer Portal</p></div>"
                + "</div>"
                + "<div class='body'>"
                + "  <div class='banner b-amber'>"
                + "    <span class='bi' style='color:#a16207'>&#9202;</span>"
                + "    <div>"
                + "      <p class='bt amber-title'>Registration received</p>"
                + "      <p class='bb amber-body'>Your developer account request has been received and is awaiting administrator approval.</p>"
                + "    </div>"
                + "  </div>"
                + "  <p class='gname'>Hello, " + esc(username) + "</p>"
                + "  <p class='intro'>Thank you for registering. You will receive another email as soon as your account has been reviewed and activated.</p>"
                + "  <div class='fbox' style='font-size:13px; color:#475569;'>"
                + "    No further action is required from you at this time."
                + "  </div>"
                + "  <a href='" + portalUrl + "' class='btn-ghost'>Visit Developer Portal &rarr;</a>"
                + "</div>"
                + "<div class='footer'>"
                + "  <p class='fs'>Automated message &mdash; please do not reply</p>"
                + "  <p class='fs'>WSO2 API Manager</p>"
                + "</div>"
                + "</div></body></html>";
    }

    /**
     * Assembles the presentation structure for welcoming validated consumers into platform domains.
     *
     * @param username the username of the newly approved applicant
     * @return a complete HTML string for the account approval notification email
     */
    private String getUserApprovedTemplate(String username) {
        return "<!DOCTYPE html><html lang='en'><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>Your developer account has been approved</title>"
                + sharedCss()
                + "</head><body><div class='wrap'>"
                + "<div class='hdr'>"
                + "  <div class='hdr-icon'>&#9670;</div>"
                + "  <div><p class='hdr-sub'>WSO2 API Manager</p><p class='hdr-main'>Developer Portal</p></div>"
                + "</div>"
                + "<div class='body'>"
                + "  <div class='banner b-green'>"
                + "    <span class='bi' style='color:#166534'>&#10004;</span>"
                + "    <div>"
                + "      <p class='bt green-title'>Your account has been approved</p>"
                + "      <p class='bb green-body'>The administrator has reviewed your request and granted you access to the Developer Portal.</p>"
                + "    </div>"
                + "  </div>"
                + "  <p class='gname'>Welcome, " + esc(username) + "</p>"
                + "  <p class='gsub'>Account activated &middot; Full access granted</p>"
                + "  <p class='intro'>You can now explore the full API catalog, build applications, and manage your subscriptions.</p>"
                + "  <div class='fbox'>"
                + "    <div class='fi'><div class='fdot fdot-g'></div><p class='ft'>Browse and subscribe to APIs in the catalog</p></div>"
                + "    <div class='fi'><div class='fdot fdot-g'></div><p class='ft'>Create applications and manage OAuth 2.0 credentials</p></div>"
                + "    <div class='fi'><div class='fdot fdot-g'></div><p class='ft'>Generate API keys for production endpoints</p></div>"
                + "  </div>"
                + "  <a href='" + portalUrl + "' class='btn-green'>Go to Developer Portal &rarr;</a>"
                + "</div>"
                + "<div class='footer'>"
                + "  <p class='fs'>Automated message &mdash; please do not reply</p>"
                + "  <p class='fs'>WSO2 API Manager</p>"
                + "</div>"
                + "</div></body></html>";
    }

    /**
     * Assembles the presentation structure for alerting applicants that onboarding conditions were unmet.
     *
     * @param username the username of the rejected applicant
     * @return a complete HTML string for the account rejection notification email
     */
    private String getUserRejectedTemplate(String username) {
        return "<!DOCTYPE html><html lang='en'><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>An update on your registration request</title>"
                + sharedCss()
                + "</head><body><div class='wrap'>"
                + "<div class='hdr'>"
                + "  <div class='hdr-icon'>&#9670;</div>"
                + "  <div><p class='hdr-sub'>WSO2 API Manager</p><p class='hdr-main'>Developer Portal</p></div>"
                + "</div>"
                + "<div class='body'>"
                + "  <div class='banner b-red'>"
                + "    <span class='bi' style='color:#991b1b'>&#10007;</span>"
                + "    <div>"
                + "      <p class='bt red-title'>Your registration was not approved</p>"
                + "      <p class='bb red-body'>After review, the administrator was unable to approve your account at this time.</p>"
                + "    </div>"
                + "  </div>"
                + "  <p class='gname'>Hello, " + esc(username) + "</p>"
                + "  <p class='intro'>We're sorry to let you know that your request to register for a developer account has been declined.</p>"
                + "  <div class='sbox'>"
                + "    If you believe this is a mistake or would like more information, please reach out to the support team &mdash; we're happy to help clarify."
                + "  </div>"
                + "  <a href='mailto:support@example.com' class='btn-ghost'>Contact Support &rarr;</a>"
                + "</div>"
                + "<div class='footer'>"
                + "  <p class='fs'>Automated message &mdash; please do not reply</p>"
                + "  <p class='fs'>WSO2 API Manager</p>"
                + "</div>"
                + "</div></body></html>";
    }
}