package com.mycompany.custom.usersignup;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.subethamail.wiser.Wiser;
import org.subethamail.wiser.WiserMessage;
import org.wso2.carbon.apimgt.api.WorkflowResponse;
import org.wso2.carbon.apimgt.impl.dto.WorkflowDTO;
import org.wso2.carbon.apimgt.impl.workflow.WorkflowException;
import org.wso2.carbon.apimgt.impl.workflow.WorkflowStatus;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.user.api.RealmConfiguration;
import org.wso2.carbon.user.api.UserRealm;
import org.wso2.carbon.user.api.UserStoreException;
import org.wso2.carbon.user.api.UserStoreManager;

import javax.mail.internet.MimeMessage;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive test suite for CustomUserSignUpWorkflowExecutor.
 * Validates workflow interception, context extraction, email generation, and SMTP dispatch functionality.
 */
@DisplayName("CustomUserSignUpWorkflowExecutor")
class CustomUserSignUpWorkflowExecutorTest {

    private static Wiser wiser;
    private static int smtpPort;

    private PrivilegedCarbonContext carbonContext;
    private UserRealm userRealm;
    private UserStoreManager userStoreManager;
    private RealmConfiguration realmConfiguration;

    private CustomUserSignUpWorkflowExecutor executor;

    private static final String TEST_USERNAME    = "john_dev";
    private static final String TEST_USER_EMAIL  = "john@example.com";
    private static final String TEST_ADMIN_EMAIL = "admin@example.com";
    private static final String TEST_ADMIN_USER  = "admin";

    @SuppressWarnings("HttpUrlsUsage")
    private static final String EMAIL_CLAIM_URI = "http://wso2.org/claims/emailaddress";

    /**
     * Initializes and starts the in-memory SMTP server before all tests.
     *
     * @throws Exception if server initialization fails
     */
    @BeforeAll
    static void startSmtpServer() throws Exception {
        smtpPort = findFreePort();
        wiser = new Wiser();
        wiser.setPort(smtpPort);
        wiser.start();
    }

    /**
     * Stops the in-memory SMTP server after all tests have completed.
     */
    @AfterAll
    static void stopSmtpServer() {
        if (wiser != null) {
            wiser.stop();
        }
    }

    /**
     * Identifies an available local port for the embedded SMTP server.
     *
     * @return an available port number
     * @throws Exception if a socket connection cannot be established
     */
    private static int findFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /**
     * Clears the static pending user email cache using reflection to ensure strict test isolation.
     *
     * @throws Exception if the reflection operation fails
     */
    @SuppressWarnings("unchecked")
    private static void clearPendingUserEmailCache() throws Exception {
        java.lang.reflect.Field field =
                CustomUserSignUpWorkflowExecutor.class.getDeclaredField("pendingUserEmailCache");
        field.setAccessible(true);
        ((java.util.Map<String, String>) field.get(null)).clear();
    }

    /**
     * Configures mock objects and workflow executor properties before each test.
     *
     * @throws Exception if mock setup or context initialization fails
     */
    @BeforeEach
    void setUp() throws Exception {
        wiser.getMessages().clear();
        clearPendingUserEmailCache();

        carbonContext      = mock(PrivilegedCarbonContext.class);
        userRealm          = mock(UserRealm.class);
        userStoreManager   = mock(UserStoreManager.class);
        realmConfiguration = mock(RealmConfiguration.class);

        when(carbonContext.getUserRealm()).thenReturn(userRealm);
        when(userRealm.getUserStoreManager()).thenReturn(userStoreManager);
        when(userRealm.getRealmConfiguration()).thenReturn(realmConfiguration);
        when(realmConfiguration.getAdminUserName()).thenReturn(TEST_ADMIN_USER);

        when(userStoreManager.getUserClaimValue(eq(TEST_USERNAME), eq(EMAIL_CLAIM_URI), isNull()))
                .thenReturn(TEST_USER_EMAIL);
        when(userStoreManager.getUserClaimValue(eq(TEST_ADMIN_USER), eq(EMAIL_CLAIM_URI), isNull()))
                .thenReturn(TEST_ADMIN_EMAIL);

        executor = spy(new CustomUserSignUpWorkflowExecutor());
        executor.setMailSmtpHost("localhost");
        executor.setMailSmtpPort(String.valueOf(smtpPort));
        executor.setMailFromAddress("noreply@wso2.test");
        executor.setMailFromName("WSO2 Test");
        executor.setAdminUsername(TEST_ADMIN_USER);
        executor.setPortalUrl("https://localhost:9443/devportal");

        doReturn(mock(WorkflowResponse.class)).when(executor).superExecute(any());
        doReturn(mock(WorkflowResponse.class)).when(executor).superComplete(any());
    }

    /**
     * Clears intercepted messages from the SMTP server after each test.
     */
    @AfterEach
    void tearDown() {
        wiser.getMessages().clear();
    }

    /**
     * Retrieves the list of messages intercepted by the embedded SMTP server.
     *
     * @return a list of intercepted WiserMessage objects
     */
    private List<WiserMessage> receivedMessages() {
        return wiser.getMessages();
    }

    /**
     * Waits for a specified number of messages to be received by the SMTP server to prevent asynchronous races.
     *
     * @param expectedCount the number of messages expected
     * @param timeoutMillis the maximum time to wait in milliseconds
     * @throws InterruptedException if the thread sleep is interrupted
     */
    private void awaitMessageCount(int expectedCount, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (receivedMessages().size() < expectedCount && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }

    /**
     * Extracts a specific MIME message from the received messages list.
     *
     * @param index the index of the message to retrieve
     * @return the MimeMessage object
     * @throws Exception if retrieval fails
     */
    private MimeMessage mimeMessageAt(int index) throws Exception {
        return receivedMessages().get(index).getMimeMessage();
    }

    /**
     * Extracts the string body content from a MIME message.
     *
     * @param msg the target MIME message
     * @return the body content as a string
     * @throws Exception if content extraction fails
     */
    private String bodyOf(MimeMessage msg) throws Exception {
        Object content = msg.getContent();
        return content != null ? content.toString() : "";
    }

    /**
     * Executes a given action within a mocked PrivilegedCarbonContext block.
     *
     * @param action the executable action
     */
    private void withCarbonContext(Runnable action) {
        try (MockedStatic<PrivilegedCarbonContext> ctxStatic =
                     mockStatic(PrivilegedCarbonContext.class)) {
            ctxStatic.when(PrivilegedCarbonContext::getThreadLocalCarbonContext)
                    .thenReturn(carbonContext);
            action.run();
        }
    }

    /**
     * Executes an action within a mocked context and waits for a specific number of emails to be dispatched.
     *
     * @param action        the executable action
     * @param expectedCount the target number of expected dispatched emails
     * @throws InterruptedException if the message await thread is interrupted
     */
    private void withCarbonContextExpectingMessages(Runnable action, int expectedCount) throws InterruptedException {
        withCarbonContext(action);
        awaitMessageCount(expectedCount, 2000);
    }

    /**
     * Constructs a mock WorkflowDTO with a designated username and status.
     *
     * @param username the target workflow reference username
     * @param status the target workflow status
     * @return a configured WorkflowDTO mock
     */
    private WorkflowDTO buildDto(String username, WorkflowStatus status) {
        WorkflowDTO dto = mock(WorkflowDTO.class);
        when(dto.getWorkflowReference()).thenReturn(username);
        when(dto.getStatus()).thenReturn(status);
        return dto;
    }

    /**
     * Test scope validating workflow executor configuration property accessors.
     */
    @Nested
    @DisplayName("Property accessors")
    class PropertyAccessors {

        /**
         * Verifies that all property setters correctly assign values retrievable by getters.
         */
        @Test
        @DisplayName("all setters round-trip through getters")
        void settersAndGetters() {
            executor.setMailSmtpHost("smtp.example.com");
            executor.setMailSmtpPort("587");
            executor.setMailFromAddress("from@example.com");
            executor.setMailFromName("My App");
            executor.setAdminUsername("superadmin");
            executor.setPortalUrl("https://portal.example.com/devportal");

            assertAll(
                    () -> assertEquals("smtp.example.com", executor.getMailSmtpHost()),
                    () -> assertEquals("587",              executor.getMailSmtpPort()),
                    () -> assertEquals("from@example.com", executor.getMailFromAddress()),
                    () -> assertEquals("My App",           executor.getMailFromName()),
                    () -> assertEquals("superadmin",       executor.getAdminUsername()),
                    () -> assertEquals("https://portal.example.com/devportal", executor.getPortalUrl())
            );
        }
    }

    /**
     * Test scope validating correct workflow identifier association.
     */
    @Nested
    @DisplayName("getWorkflowType")
    class WorkflowType {

        /**
         * Verifies that the executor returns the correct workflow type identifier.
         */
        @Test
        @DisplayName("returns AM_USER_SIGNUP")
        void returnsCorrectType() {
            assertEquals("AM_USER_SIGNUP", executor.getWorkflowType());
        }
    }

    /**
     * Test scope validating logic for Stage 1 execution (Administrative alert notifications).
     */
    @Nested
    @DisplayName("execute() — Stage 1 admin alert and user pending alert")
    class ExecuteStage {

        /**
         * Verifies that dual alert emails are dispatched to both the administrator and the applicant upon form submission.
         *
         * @throws Exception if the email verification fails
         */
        @Test
        @DisplayName("sends emails to both admin and user when signup is submitted")
        void sendsAlertsToBothAdminAndUserOnSubmit() throws Exception {
            WorkflowDTO dto = buildDto(TEST_USERNAME, WorkflowStatus.CREATED);
            withCarbonContextExpectingMessages(() -> assertDoesNotThrow(() -> executor.execute(dto)), 2);

            assertEquals(2, receivedMessages().size(), "two emails should be dispatched during execute phase");

            WiserMessage adminMsg = receivedMessages().get(0);
            assertEquals(TEST_ADMIN_EMAIL, adminMsg.getEnvelopeReceiver());
            assertTrue(adminMsg.getMimeMessage().getSubject().toLowerCase().contains("pending"));

            WiserMessage userMsg = receivedMessages().get(1);
            assertEquals(TEST_USER_EMAIL, userMsg.getEnvelopeReceiver());
            assertTrue(userMsg.getMimeMessage().getSubject().toLowerCase().contains("pending approval"));
        }

        /**
         * Verifies that the administrative email contains correct applicant details and status indicators.
         *
         * @throws Exception if the email body verification fails
         */
        @Test
        @DisplayName("email to admin contains username and 'Pending approval' pill")
        void adminEmailContainsUserDetails() throws Exception {
            WorkflowDTO dto = buildDto(TEST_USERNAME, WorkflowStatus.CREATED);
            withCarbonContextExpectingMessages(() -> assertDoesNotThrow(() -> executor.execute(dto)), 2);

            String body = bodyOf(mimeMessageAt(0));
            assertAll(
                    () -> assertTrue(body.contains(TEST_USERNAME),       "body should contain username"),
                    () -> assertTrue(body.contains(TEST_USER_EMAIL),     "body should contain user email"),
                    () -> assertTrue(body.contains("Pending approval"),  "body should contain status pill"),
                    () -> assertTrue(body.contains("tasks/user-creation"), "body should contain admin tasks link")
            );
        }

        /**
         * Verifies that the applicant's pending notification email contains empathetic text and explicit status instruction.
         *
         * @throws Exception if body analysis fails
         */
        @Test
        @DisplayName("user pending email body contains welcome text and instructions")
        void userPendingEmailBodyContainsWelcomeText() throws Exception {
            WorkflowDTO dto = buildDto(TEST_USERNAME, WorkflowStatus.CREATED);
            withCarbonContextExpectingMessages(() -> assertDoesNotThrow(() -> executor.execute(dto)), 2);

            String body = bodyOf(mimeMessageAt(1));
            assertAll(
                    () -> assertTrue(body.contains(TEST_USERNAME), "body should contain username"),
                    () -> assertTrue(body.contains("awaiting administrator approval"), "body should contain pending text"),
                    () -> assertTrue(body.contains("No further action is required"), "body should contain specific instruction")
            );
        }

        /**
         * Verifies that the administrator portal URL is correctly derived from the developer portal URL.
         *
         * @throws Exception if URL verification fails
         */
        @Test
        @DisplayName("admin URL replaces 'devportal' with 'admin' in the portal URL")
        void adminUrlIsCorrectlyDerived() throws Exception {
            executor.setPortalUrl("https://myhost:9443/devportal");
            WorkflowDTO dto = buildDto(TEST_USERNAME, WorkflowStatus.CREATED);
            withCarbonContextExpectingMessages(() -> assertDoesNotThrow(() -> executor.execute(dto)), 2);

            String body = bodyOf(mimeMessageAt(0));
            assertTrue(body.contains("https://myhost:9443/admin"),
                    "admin URL should replace devportal with admin");
        }

        /**
         * Verifies that the applicant still receives their pending status email even if the administrative claim fails.
         *
         * @throws Exception if exception handling fails
         */
        @Test
        @DisplayName("no admin email sent when admin email claim is empty, but user still receives pending alert")
        void noAdminEmailWhenAdminClaimEmpty() throws Exception {
            when(userStoreManager.getUserClaimValue(eq(TEST_ADMIN_USER), eq(EMAIL_CLAIM_URI), isNull()))
                    .thenReturn("");

            WorkflowDTO dto = buildDto(TEST_USERNAME, WorkflowStatus.CREATED);
            withCarbonContextExpectingMessages(() -> assertDoesNotThrow(() -> executor.execute(dto)), 1);

            assertEquals(1, receivedMessages().size(), "only the user pending email should be dispatched");
            assertEquals(TEST_USER_EMAIL, receivedMessages().get(0).getEnvelopeReceiver());
        }

        /**
         * Verifies that no admin email is sent if the targeted administrator's email claim evaluates to null.
         *
         * @throws Exception if exception handling fails
         */
        @Test
        @DisplayName("no admin email sent when admin email claim is null, but user still receives pending alert")
        void noAdminEmailWhenAdminClaimNull() throws Exception {
            when(userStoreManager.getUserClaimValue(eq(TEST_ADMIN_USER), eq(EMAIL_CLAIM_URI), isNull()))
                    .thenReturn(null);

            WorkflowDTO dto = buildDto(TEST_USERNAME, WorkflowStatus.CREATED);
            withCarbonContextExpectingMessages(() -> assertDoesNotThrow(() -> executor.execute(dto)), 1);

            assertEquals(1, receivedMessages().size(), "only the user pending email should be dispatched");
            assertEquals(TEST_USER_EMAIL, receivedMessages().get(0).getEnvelopeReceiver());
        }

        /**
         * Verifies that the executor falls back to querying the realm administrator if an explicit username is absent.
         *
         * @throws Exception if configuration or assertion fails
         */
        @Test
        @DisplayName("falls back to realm admin when adminUsername property is blank")
        void fallsBackToRealmAdmin() throws Exception {
            executor.setAdminUsername("");

            String fallbackAdmin = "realmAdmin";
            when(realmConfiguration.getAdminUserName()).thenReturn(fallbackAdmin);
            when(userStoreManager.getUserClaimValue(eq(fallbackAdmin), eq(EMAIL_CLAIM_URI), isNull()))
                    .thenReturn("realm-admin@example.com");

            WorkflowDTO dto = buildDto(TEST_USERNAME, WorkflowStatus.CREATED);
            withCarbonContextExpectingMessages(() -> assertDoesNotThrow(() -> executor.execute(dto)), 2);

            assertEquals(2, receivedMessages().size());
            assertEquals("realm-admin@example.com", receivedMessages().get(0).getEnvelopeReceiver());
            assertEquals(TEST_USER_EMAIL, receivedMessages().get(1).getEnvelopeReceiver());
        }

        /**
         * Verifies that execution completes gracefully without throwing external exceptions if the user store fails.
         *
         * @throws Exception if failure verification throws
         */
        @Test
        @DisplayName("does not throw when UserStoreManager throws UserStoreException")
        void gracefullyHandlesUserStoreException() throws Exception {
            when(userRealm.getUserStoreManager()).thenThrow(new UserStoreException("DB down"));

            WorkflowDTO dto = buildDto(TEST_USERNAME, WorkflowStatus.CREATED);
            withCarbonContext(() -> assertDoesNotThrow(() -> executor.execute(dto),
                    "execute should swallow UserStoreException and not propagate"));

            assertEquals(0, receivedMessages().size(), "no emails should be dispatched during a critical user store fault");
        }
    }

    /**
     * Test scope validating logic for Stage 2 execution (Final outcome notifications to applicants).
     */
    @Nested
    @DisplayName("complete() — Stage 2 user outcome notification")
    class CompleteStage {

        /**
         * Verifies that an approval email is dispatched to the user when their registration is accepted.
         *
         * @throws Exception if message validation fails
         */
        @Test
        @DisplayName("sends approval email to user when status is APPROVED")
        void sendsApprovalEmail() throws Exception {
            WorkflowDTO dto = buildDto(TEST_USERNAME, WorkflowStatus.APPROVED);
            withCarbonContextExpectingMessages(() -> assertDoesNotThrow(() -> executor.complete(dto)), 1);

            assertEquals(1, receivedMessages().size());
            assertEquals(TEST_USER_EMAIL, receivedMessages().get(0).getEnvelopeReceiver());
            assertTrue(mimeMessageAt(0).getSubject().toLowerCase().contains("approved"));
        }

        /**
         * Verifies that the approval email body correctly incorporates portal links and confirmation messaging.
         *
         * @throws Exception if body analysis fails
         */
        @Test
        @DisplayName("approval email body contains welcome text and portal link")
        void approvalEmailBody() throws Exception {
            WorkflowDTO dto = buildDto(TEST_USERNAME, WorkflowStatus.APPROVED);
            withCarbonContextExpectingMessages(() -> assertDoesNotThrow(() -> executor.complete(dto)), 1);

            String body = bodyOf(mimeMessageAt(0));
            assertAll(
                    () -> assertTrue(body.contains(TEST_USERNAME), "body should contain username"),
                    () -> assertTrue(body.contains("approved"),    "body should contain approval text"),
                    () -> assertTrue(body.contains("devportal"),   "body should contain portal URL"),
                    () -> assertTrue(body.contains("OAuth"),       "body should mention OAuth credentials")
            );
        }

        /**
         * Verifies that a rejection email is dispatched to the user when their registration is declined.
         *
         * @throws Exception if message validation fails
         */
        @Test
        @DisplayName("sends rejection email to user when status is REJECTED")
        void sendsRejectionEmail() throws Exception {
            WorkflowDTO dto = buildDto(TEST_USERNAME, WorkflowStatus.REJECTED);
            withCarbonContextExpectingMessages(() -> assertDoesNotThrow(() -> executor.complete(dto)), 1);

            assertEquals(1, receivedMessages().size());
            assertEquals(TEST_USER_EMAIL, receivedMessages().get(0).getEnvelopeReceiver());

            String subject = mimeMessageAt(0).getSubject().toLowerCase();
            assertTrue(subject.contains("update") || subject.contains("registration"),
                    "subject should be a soft, informational message");
        }

        /**
         * Verifies that the rejection email body contains empathetic rejection messaging and support contexts.
         *
         * @throws Exception if body analysis fails
         */
        @Test
        @DisplayName("rejection email body contains support contact and empathetic text")
        void rejectionEmailBody() throws Exception {
            WorkflowDTO dto = buildDto(TEST_USERNAME, WorkflowStatus.REJECTED);
            withCarbonContextExpectingMessages(() -> assertDoesNotThrow(() -> executor.complete(dto)), 1);

            String body = bodyOf(mimeMessageAt(0));
            assertAll(
                    () -> assertTrue(body.contains(TEST_USERNAME), "body should contain username"),
                    () -> assertTrue(body.contains("declined"),    "body should mention declined"),
                    () -> assertTrue(body.contains("support"),     "body should reference support contact")
            );
        }

        /**
         * Verifies that no completion email is sent if the applicant's email claim evaluates to null.
         *
         * @throws Exception if claim testing fails
         */
        @Test
        @DisplayName("no email sent when user email claim is null")
        void noEmailWhenUserClaimNull() throws Exception {
            when(userStoreManager.getUserClaimValue(eq(TEST_USERNAME), eq(EMAIL_CLAIM_URI), isNull()))
                    .thenReturn(null);

            WorkflowDTO dto = buildDto(TEST_USERNAME, WorkflowStatus.APPROVED);
            withCarbonContext(() -> assertDoesNotThrow(() -> executor.complete(dto)));

            assertEquals(0, receivedMessages().size());
        }

        /**
         * Verifies that no completion email is sent if the applicant's email claim evaluates to an empty string.
         *
         * @throws Exception if claim testing fails
         */
        @Test
        @DisplayName("no email sent when user email claim is empty string")
        void noEmailWhenUserClaimEmpty() throws Exception {
            when(userStoreManager.getUserClaimValue(eq(TEST_USERNAME), eq(EMAIL_CLAIM_URI), isNull()))
                    .thenReturn("");

            WorkflowDTO dto = buildDto(TEST_USERNAME, WorkflowStatus.APPROVED);
            withCarbonContext(() -> assertDoesNotThrow(() -> executor.complete(dto)));

            assertEquals(0, receivedMessages().size(),
                    "no email should be sent when email claim is an empty string");
        }

        /**
         * Verifies that no email is sent for intermediary states aside from definitive APPROVAL or REJECTION.
         *
         * @throws Exception if unhandled state tests fail
         */
        @Test
        @DisplayName("no email sent for status other than APPROVED or REJECTED")
        void noEmailForUnhandledStatus() throws Exception {
            WorkflowDTO dto = buildDto(TEST_USERNAME, WorkflowStatus.REGISTERED);
            withCarbonContext(() -> assertDoesNotThrow(() -> executor.complete(dto)));

            assertEquals(0, receivedMessages().size(),
                    "no email should be sent for REGISTERED or other intermediate statuses");
        }

        /**
         * Verifies that failure to read user store attributes resolves gracefully without exception escalation.
         */
        @Test
        @DisplayName("does not throw when UserStoreManager throws in complete()")
        void gracefullyHandlesUserStoreExceptionInComplete() {
            try {
                when(userRealm.getUserStoreManager()).thenThrow(new UserStoreException("DB down"));
            } catch (UserStoreException e) {
                fail(e);
            }

            WorkflowDTO dto = buildDto(TEST_USERNAME, WorkflowStatus.APPROVED);
            withCarbonContext(() -> assertDoesNotThrow(() -> executor.complete(dto)));
        }

        /**
         * Validates the regression fix ensuring fallback mechanisms trigger when a user is purged from the datastore prior to completion.
         *
         * @throws Exception if cache simulation fails
         */
        @Test
        @DisplayName("REGRESSION: rejection email still sent via cache when user record is already gone")
        void rejectionEmailSentFromCacheWhenUserRecordAlreadyRemoved() throws Exception {
            WorkflowDTO executeDto = buildDto(TEST_USERNAME, WorkflowStatus.CREATED);
            withCarbonContextExpectingMessages(() -> assertDoesNotThrow(() -> executor.execute(executeDto)), 2);

            assertEquals(2, receivedMessages().size(), "dual alerts should have been sent during execute()");
            wiser.getMessages().clear();

            when(userStoreManager.getUserClaimValue(eq(TEST_USERNAME), eq(EMAIL_CLAIM_URI), isNull()))
                    .thenThrow(new UserStoreException("30007 - UserNotFound: User " + TEST_USERNAME + " does not exist in: PRIMARY"));

            WorkflowDTO completeDto = buildDto(TEST_USERNAME, WorkflowStatus.REJECTED);
            withCarbonContextExpectingMessages(() -> assertDoesNotThrow(() -> executor.complete(completeDto)), 1);

            assertEquals(1, receivedMessages().size(),
                    "rejection email should still be sent via the cached email fallback");
            assertEquals(TEST_USER_EMAIL, receivedMessages().get(0).getEnvelopeReceiver());

            String subject = mimeMessageAt(0).getSubject().toLowerCase();
            assertTrue(subject.contains("update") || subject.contains("registration"),
                    "subject should be the rejection notification, not silently dropped");

            String body = bodyOf(mimeMessageAt(0));
            assertTrue(body.contains("declined"), "body should contain the rejection message");
        }

        /**
         * Validates the regression fix preventing crash propagation when both the datastore entry and the cache fallback are missing.
         *
         * @throws Exception if empty cache validation throws
         */
        @Test
        @DisplayName("REGRESSION: no email and no crash when user record is gone AND nothing was ever cached")
        void noEmailWhenUserStoreThrowsAndCacheIsEmpty() throws Exception {
            when(userStoreManager.getUserClaimValue(eq(TEST_USERNAME), eq(EMAIL_CLAIM_URI), isNull()))
                    .thenThrow(new UserStoreException("30007 - UserNotFound: User " + TEST_USERNAME + " does not exist in: PRIMARY"));

            WorkflowDTO dto = buildDto(TEST_USERNAME, WorkflowStatus.REJECTED);
            withCarbonContext(() -> assertDoesNotThrow(() -> executor.complete(dto)));

            assertEquals(0, receivedMessages().size(),
                    "no email can be sent when neither a live claim nor a cached email is available");
        }
    }

    /**
     * Test scope validating template compilation rendering behavior.
     */
    @Nested
    @DisplayName("HTML template content")
    class TemplateContent {

        /**
         * Verifies that the administrative pending template successfully incorporates formatted date injections.
         *
         * @throws Exception if the regex assertion fails
         */
        @Test
        @DisplayName("admin template includes submission timestamp")
        void adminTemplateHasTimestamp() throws Exception {
            WorkflowDTO dto = buildDto(TEST_USERNAME, WorkflowStatus.CREATED);
            withCarbonContextExpectingMessages(() -> assertDoesNotThrow(() -> executor.execute(dto)), 2);

            String body = bodyOf(mimeMessageAt(0));
            assertTrue(body.matches("(?s).*\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2} UTC.*"),
                    "admin email should contain a formatted UTC timestamp");
        }

        /**
         * Verifies that the administrative pending template handles missing emails gracefully during layout construction.
         *
         * @throws Exception if compilation assertions fail
         */
        @Test
        @DisplayName("admin template shows 'Not provided' when user has no email claim")
        void adminTemplateShowsNotProvidedForMissingEmail() throws Exception {
            when(userStoreManager.getUserClaimValue(eq(TEST_USERNAME), eq(EMAIL_CLAIM_URI), isNull()))
                    .thenReturn(null);

            WorkflowDTO dto = buildDto(TEST_USERNAME, WorkflowStatus.CREATED);
            withCarbonContextExpectingMessages(() -> assertDoesNotThrow(() -> executor.execute(dto)), 1);

            String body = bodyOf(mimeMessageAt(0));
            assertTrue(body.contains("Not provided"),
                    "template should gracefully display 'Not provided' for missing user email");
        }

        /**
         * Asserts that all core HTML string templates execute compilation processes without crashing.
         */
        @Test
        @DisplayName("all templates render without error")
        void allTemplatesRenderCleanly() {
            withCarbonContext(() -> assertDoesNotThrow(
                    () -> executor.execute(buildDto(TEST_USERNAME, WorkflowStatus.CREATED))));
            wiser.getMessages().clear();

            withCarbonContext(() -> assertDoesNotThrow(
                    () -> executor.complete(buildDto(TEST_USERNAME, WorkflowStatus.APPROVED))));
            wiser.getMessages().clear();

            withCarbonContext(() -> assertDoesNotThrow(
                    () -> executor.complete(buildDto(TEST_USERNAME, WorkflowStatus.REJECTED))));
        }
    }

    /**
     * Test scope enforcing cross-site scripting mitigation controls within dynamic template generation.
     */
    @Nested
    @DisplayName("XSS / HTML escaping in esc()")
    class HtmlEscaping {

        /**
         * Asserts that targeted characters within potential injection vectors are successfully escaped by the rendering engine.
         *
         * @param maliciousInput parameterized malicious input string
         * @throws Exception if vulnerability checks fail
         */
        @ParameterizedTest(name = "input [{0}] is escaped in email body")
        @ValueSource(strings = {
                "<script>alert('xss')</script>",
                "\" onmouseover=\"alert(1)",
                "' OR '1'='1",
                "<img src=x onerror=alert(1)>",
                "&amp;already-encoded"
        })
        @DisplayName("dangerous characters in username are escaped before rendering")
        void usernameIsSafelyEscapedInApprovalEmail(String maliciousInput) throws Exception {
            when(userStoreManager.getUserClaimValue(eq(maliciousInput), eq(EMAIL_CLAIM_URI), isNull()))
                    .thenReturn(TEST_USER_EMAIL);

            WorkflowDTO dto = buildDto(maliciousInput, WorkflowStatus.APPROVED);
            withCarbonContextExpectingMessages(() -> assertDoesNotThrow(() -> executor.complete(dto)), 1);

            String body = bodyOf(mimeMessageAt(0));
            assertFalse(body.contains("<script>"),      "raw <script> must be escaped");
            assertFalse(body.contains("onerror=alert"),  "raw onerror attribute must be escaped");

            wiser.getMessages().clear();
        }

        /**
         * Validates that the internal escaping utility natively tolerates and resolves null values without fault.
         *
         * @throws Exception if reflection or execution faults
         */
        @Test
        @DisplayName("esc() returns empty string for null input")
        void escHandlesNull() throws Exception {
            Method esc = CustomUserSignUpWorkflowExecutor.class.getDeclaredMethod("esc", String.class);
            esc.setAccessible(true);
            assertEquals("", esc.invoke(executor, (Object) null));
        }

        /**
         * Validates that the internal escaping utility maps specified structural symbols to secure HTML entity equivalents.
         *
         * @throws Exception if reflection or assertion operations fail
         */
        @Test
        @DisplayName("esc() escapes all five dangerous HTML characters")
        void escEscapesAllSpecialChars() throws Exception {
            Method esc = CustomUserSignUpWorkflowExecutor.class.getDeclaredMethod("esc", String.class);
            esc.setAccessible(true);

            String result = (String) esc.invoke(executor, "& < > \" '");

            assertAll(
                    () -> assertTrue(result.contains("&amp;"),  "& -> &amp;"),
                    () -> assertTrue(result.contains("&lt;"),   "< -> &lt;"),
                    () -> assertTrue(result.contains("&gt;"),   "> -> &gt;"),
                    () -> assertTrue(result.contains("&quot;"), "\" -> &quot;"),
                    () -> assertTrue(result.contains("&#39;"),  "' -> &#39;")
            );
        }
    }

    /**
     * Test scope validating low-level mail session configuration, content negotiation, and transmission fault tolerance.
     */
    @Nested
    @DisplayName("Email sending infrastructure")
    class EmailInfrastructure {

        /**
         * Asserts that dynamic sender and address configuration targets bind accurately onto the finalized MIME header.
         *
         * @throws Exception if extraction analysis fails
         */
        @Test
        @DisplayName("sent email uses configured From address and display name")
        void emailFromAddressAndName() throws Exception {
            executor.setMailFromAddress("custom-from@test.com");
            executor.setMailFromName("Custom Sender");

            WorkflowDTO dto = buildDto(TEST_USERNAME, WorkflowStatus.APPROVED);
            withCarbonContextExpectingMessages(() -> assertDoesNotThrow(() -> executor.complete(dto)), 1);

            String from = mimeMessageAt(0).getFrom()[0].toString();
            assertTrue(from.contains("custom-from@test.com"), "From address should match config");
            assertTrue(from.contains("Custom Sender"),        "From name should match config");
        }

        /**
         * Asserts that dispatched transmissions indicate proper html payload formatting within protocol headers.
         *
         * @throws Exception if content type interrogation throws
         */
        @Test
        @DisplayName("sent email Content-Type is text/html")
        void emailContentTypeIsHtml() throws Exception {
            WorkflowDTO dto = buildDto(TEST_USERNAME, WorkflowStatus.APPROVED);
            withCarbonContextExpectingMessages(() -> assertDoesNotThrow(() -> executor.complete(dto)), 1);

            assertTrue(mimeMessageAt(0).getContentType().startsWith("text/html"),
                    "email content type should be text/html");
        }

        /**
         * Asserts that a breakdown in the SMTP transport link generates internal warning logs instead of bubbling fault codes upward.
         */
        @Test
        @DisplayName("no exception propagates when SMTP is unreachable")
        void doesNotThrowOnSmtpFailure() {
            executor.setMailSmtpHost("127.0.0.1");
            executor.setMailSmtpPort("19999");

            assertDoesNotThrow(() -> {
                Method sendEmail = CustomUserSignUpWorkflowExecutor.class
                        .getDeclaredMethod("sendEmail", String.class, String.class, String.class);
                sendEmail.setAccessible(true);
                sendEmail.invoke(executor, "to@example.com", "Subject", "<p>body</p>");
            }, "sendEmail must swallow MessagingException and log — never propagate");
        }
    }

    /**
     * Test scope assessing layout components globally consumed across view models.
     */
    @Nested
    @DisplayName("sharedCss() utility")
    class SharedCss {

        /**
         * Performs basic presence assertions validating the compilation output of the raw styling elements block.
         *
         * @throws Exception if extraction or structural validation throws
         */
        @Test
        @DisplayName("sharedCss returns non-null, non-empty style block")
        void sharedCssNotEmpty() throws Exception {
            Method sharedCss = CustomUserSignUpWorkflowExecutor.class.getDeclaredMethod("sharedCss");
            sharedCss.setAccessible(true);

            String css = (String) sharedCss.invoke(executor);
            assertNotNull(css);
            assertTrue(css.startsWith("<style>"), "CSS block should open with <style>");
            assertTrue(css.endsWith("</style>"),  "CSS block should close with </style>");
            assertTrue(css.contains(".wrap"),      "shared CSS should define .wrap class");
            assertTrue(css.contains(".hdr"),       "shared CSS should define .hdr class");
            assertTrue(css.contains(".banner"),    "shared CSS should define .banner class");
            assertTrue(css.contains(".footer"),    "shared CSS should define .footer class");
        }
    }
}