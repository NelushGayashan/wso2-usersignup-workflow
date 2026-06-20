# WSO2 APIM — Custom User Signup Workflow Extension

<p align="center">

![Java](https://img.shields.io/badge/Java-17-orange)
![WSO2](https://img.shields.io/badge/WSO2-API_Manager_4.2.0-red)
![Maven](https://img.shields.io/badge/Maven-3.6+-blue)
![OSGi](https://img.shields.io/badge/OSGi-Equinox_3.14-green)
![License](https://img.shields.io/badge/License-MIT-success)

</p>

A production-grade OSGi bundle for **WSO2 API Manager 4.2.0** that replaces the platform's silent, unaudited developer-signup workflow with a fully branded, two-stage approval process — complete with HTML email notifications at every step, a hermetic unit test suite, and a documented fix for a real production bug discovered during development.

This README documents the project end-to-end: the problem being solved, the architecture, every iteration the implementation went through, the bugs that were found and fixed along the way, and how to build, deploy, and test it.

---

## Table of Contents

- [Why this project exists](#why-this-project-exists)
- [What it does](#what-it-does)
- [Architecture](#architecture)
- [Workflow lifecycle](#workflow-lifecycle)
- [Email templates](#email-templates)
- [Technology stack](#technology-stack)
- [Project structure](#project-structure)
- [Requirements](#requirements)
- [Build & deploy](#build--deploy)
- [Configuration](#configuration)
- [Test suite](#test-suite)
- [The development journey — problems faced & solved](#the-development-journey--problems-faced--solved)
  - [OSGi packaging and classloader issues](#1-osgi-packaging-and-classloader-issues)
  - [Designing the two-stage workflow](#2-designing-the-two-stage-workflow)
  - [Building a hermetic test suite without a real SMTP server](#3-building-a-hermetic-test-suite-without-a-real-smtp-server)
  - [The SMTP race condition](#4-the-smtp-race-condition)
  - [The XSS escaping gap](#5-the-xss-escaping-gap)
  - [The silent rejection-email bug](#6-the-silent-rejection-email-bug-the-big-one)
  - [File drift between iterations](#7-file-drift-between-iterations)
- [XSS protection](#xss-protection)
- [Known limitations & future work](#known-limitations--future-work)
- [Troubleshooting](#troubleshooting)
- [License](#license)

---

## Why this project exists

WSO2 API Manager ships with a default developer-signup workflow (`UserSignUpApprovalWorkflowExecutor`) that is functionally complete but operationally silent:

- An administrator has **no way of knowing** a new developer has registered, short of manually polling the Admin Portal's pending-tasks list.
- An applicant who submits a signup form receives **no confirmation** that their request was received.
- When an admin approves or rejects a request, the applicant receives **no outcome notification** — they simply have to try logging in and see what happens.

For any organization running APIM in a real onboarding pipeline — internal developer platforms, partner API programs, B2B integrations — this silence is a real operational gap. This project closes it by hooking into the workflow lifecycle and adding branded, informative email notifications at every decision point, without touching WSO2's underlying persistence or approval logic.

## What it does

This extension intercepts both stages of WSO2's developer signup workflow and adds:

1. **Instant admin alert** — the moment someone submits a signup, the configured administrator gets an email with the applicant's details and a direct link into the Admin Console's review queue.
2. **Instant applicant acknowledgment** — the applicant immediately receives a "we've got your request" email, so they're not left wondering if the form submission worked.
3. **Outcome notification** — when the admin approves or rejects the request, the applicant receives a corresponding email: a warm welcome with next steps on approval, or a soft, support-oriented message on rejection.
4. **A resilience fix** ensuring rejected applicants are still notified even when WSO2 has already removed their account record by the time the rejection is processed (see [the deep-dive below](#6-the-silent-rejection-email-bug-the-big-one) — this was a real bug found via production logs, not a hypothetical).

All four emails share one consistent visual design system, are rendered with zero external templating dependencies (no Thymeleaf, no FreeMarker — just Java string concatenation), and are hardened against HTML/attribute injection from malicious usernames.

---

## Architecture

WSO2 API Manager's workflow extension mechanism allows custom Java classes to hook into lifecycle events by extending a base executor class and registering the subclass in server configuration. This project extends `UserSignUpApprovalWorkflowExecutor`, which exposes exactly two lifecycle hooks relevant to signup:

```
WSO2 API Manager Runtime (OSGi / Equinox container)
│
├── Developer Portal — user submits signup form
│   ▼
├── WorkflowExecutorFactory resolves the configured executor class
│   └── CustomUserSignUpWorkflowExecutor
│       (deployed as a JAR in repository/components/dropins)
│
│       STAGE 1 — execute()
│       ├── super.execute()  → WSO2 persists the CREATED workflow record
│       ├── Resolve admin + applicant email claims via Carbon UserStoreManager
│       ├── Cache applicant email (pendingUserEmailCache) — see bug fix below
│       ├── Render admin pending-alert HTML email
│       ├── Render applicant pending-acknowledgment HTML email
│       └── Dispatch both via javax.mail / SMTP
│
│       [ ... admin reviews the request in the Admin Console ... ]
│
│       STAGE 2 — complete()
│       ├── super.complete()  → WSO2 persists APPROVED or REJECTED
│       ├── Resolve applicant email (live lookup, falling back to cache)
│       ├── Render approval OR rejection HTML email
│       └── Dispatch via javax.mail / SMTP
│
└── SMTP relay (local dev SMTP server / corporate relay in production)
```

The bundle has **zero runtime dependencies beyond what WSO2 Carbon already provides** (`javax.mail`, `commons-logging`, Carbon's own user-store and workflow APIs). This was a deliberate constraint, not an oversight — see [OSGi packaging issues](#1-osgi-packaging-and-classloader-issues) below for why.

---

## Workflow lifecycle

```
┌───────────────────────┐         ┌─────────────────────────┐
│   USER SUBMITS         │         │   ADMIN DECIDES          │
│   signup form           │         │   approve / reject       │
└──────────┬─────────────┘         └────────────┬──────────────┘
           │                                     │
           ▼                                     ▼
     execute()                              complete()
     (Stage 1)                              (Stage 2)
           │                                     │
           ├─► Email: Admin pending alert         ├─► Email: Approved, OR
           │   "Action required:                  │   Email: Rejected
           │    pending registration"             │
           │                                      │
           └─► Email: Applicant acknowledgment    └─► Cache entry cleared
               "Your registration is
                pending approval"
```

| Stage | Method | Trigger | Emails sent |
|---|---|---|---|
| **1** | `execute()` | User submits the signup form | Admin pending-alert **+** Applicant pending-acknowledgment |
| **2** | `complete()` | Admin clicks Approve/Reject in the Admin Console | Applicant approval **or** Applicant rejection |

Both methods first delegate to the superclass (`super.execute()` / `super.complete()`, wrapped as `superExecute()` / `superComplete()` for testability — see [test suite](#test-suite)) so that WSO2's own state-machine persistence always runs **before** any notification logic. This ordering matters: if email dispatch happened first and then the database commit failed, applicants could receive a "you're approved" email for a request that was never actually persisted as approved.

---

## Email templates

Four HTML emails, all built on one shared CSS design system (`sharedCss()`), so a single style tweak propagates everywhere:

| # | Template method | Sent to | Sent when | Subject line |
|---|---|---|---|---|
| 1 | `getAdminPendingTemplate()` | Admin | Stage 1 | *Action required: pending developer registration* |
| 2 | `getUserPendingTemplate()` | Applicant | Stage 1 | *Your developer registration is pending approval* |
| 3 | `getUserApprovedTemplate()` | Applicant | Stage 2 (approved) | *Your developer account has been approved* |
| 4 | `getUserRejectedTemplate()` | Applicant | Stage 2 (rejected) | *An update on your registration request* |

**Design language:**
- A dark (`#0f172a`) header bar with the WSO2 branding mark on every email, for instant visual recognition in an inbox.
- Color-coded status banners: **amber** for pending/awaiting-action states, **green** for approval, **red** for rejection — using soft pastel backgrounds (`#fefce8`, `#f0fdf4`, `#fef2f2`) rather than harsh saturated colors, so the rejection email in particular doesn't read as alarming.
- A structured details table (username / email / timestamp / status pill) in the admin alert, using monospace font for data values to visually distinguish them from prose.
- A feature checklist with colored dot bullets in the approval email, previewing what the new developer can now do.
- Deliberately **soft, empathetic copy on rejection** — "An update on your registration request" rather than "Registration Denied," and a visible support-contact path, because a harsh rejection email actively damages an applicant's perception of the platform/organization.

Every dynamic value (username, email address) that gets interpolated into these templates passes through `esc()` first — see [XSS protection](#xss-protection).

---

## Technology stack

| Layer | Choice | Why |
|---|---|---|
| Language | **Java 17** | Matches the target WSO2 APIM 4.2.0 / Carbon Kernel 4.7.0 runtime's supported JDK range (11–17) |
| Build | **Maven 3.6+** | Standard for WSO2 Carbon component development |
| Packaging | **Apache Felix `maven-bundle-plugin` 5.1.8** | Generates the OSGi `MANIFEST.MF` (`Import-Package`/`Export-Package`) required for deployment into Equinox |
| Email transport | **`javax.mail` 1.4.7** | Already exported by the WSO2 Carbon runtime — using anything else risks classloader conflicts (see below) |
| HTML rendering | **Plain Java string concatenation** | Zero external templating engine — no Thymeleaf, no FreeMarker, no Velocity. This is a deliberate architectural constraint, not a missing feature |
| Workflow base class | **`org.wso2.carbon.apimgt.impl.workflow.UserSignUpApprovalWorkflowExecutor`** | WSO2's own two-stage (execute/complete) approval workflow base |
| User claim resolution | **Carbon `UserStoreManager` / `PrivilegedCarbonContext`** | The native WSO2 mechanism for resolving user email addresses and other profile claims |
| Unit testing | **JUnit 5.10.2 + Mockito 5.11.0** | Modern, well-supported, good `@Nested`/parameterized-test ergonomics |
| Test SMTP server | **SubEthaSMTP / Wiser 3.1.7** | `javax.mail`-native in-process SMTP server — no jakarta.mail package conflicts, no external infrastructure dependency for `mvn test` |

---

## Project structure

```
wso2-usersignup-workflow/
├── pom.xml                                                    # Maven build + OSGi bundle config
├── deploy.ps1                                                 # Local dev deploy automation (build → copy → restart)
│
├── src/
│   ├── main/java/com/mycompany/custom/usersignup/
│   │   └── CustomUserSignUpWorkflowExecutor.java              # The entire executor: workflow hooks,
│   │                                                            #   email dispatch, HTML templates, esc()
│   │
│   └── test/java/com/mycompany/custom/usersignup/
│       └── CustomUserSignUpWorkflowExecutorTest.java           # Hermetic unit suite (runs on every `mvn test`)
│
└── target/
    └── com.mycompany.custom.usersignup.extension-1.0.0.jar    # Compiled OSGi bundle, ready for dropins/
```

---

## Requirements

- **JDK 11–17** — WSO2 Carbon explicitly only supports this range; anything outside it (e.g. JDK 21) will cause the server to refuse to start cleanly.
- **Apache Maven 3.6+**
- **WSO2 API Manager 4.2.0** target instance (local or remote)

---

## Build & deploy

### Build

```powershell
mvn clean install
```

This compiles the executor, runs the hermetic unit test suite (32 tests, no external dependencies required), and packages the result as an OSGi bundle via `maven-bundle-plugin`.

### Deploy

The compiled JAR (`target/com.mycompany.custom.usersignup.extension-1.0.0.jar`) is deployed by copying it into:

```
<APIM_HOME>/repository/components/dropins/
```

A `deploy.ps1` PowerShell script automates the full local-development cycle — build, copy into dropins, clear the OSGi `work/` cache, and restart the server — with `-FreshInstall` and `-SkipBuild` flags for faster iteration when only redeploying without rebuilding. This script exists because the manual version of this cycle (clean dropins → clear OSGi cache → copy jar → restart with `--clean`) is tedious and easy to get wrong, especially the cache-clearing step (see [troubleshooting](#troubleshooting)).

### Register the executor

Point WSO2 at the custom class via `<APIM_HOME>/repository/conf/workflow-extensions.xml`:

```xml
<WorkFlowExtensions>
    <UserSignUp executor="com.mycompany.custom.usersignup.CustomUserSignUpWorkflowExecutor">
        <Property name="mailSmtpHost">localhost</Property>
        <Property name="mailSmtpPort">1025</Property>
        <Property name="mailFromAddress">apim-noreply@example.com</Property>
        <Property name="mailFromName">WSO2 API Manager</Property>
        <Property name="adminUsername">admin</Property>
        <Property name="portalUrl">https://localhost:9443/devportal</Property>
    </UserSignUp>
</WorkFlowExtensions>
```

WSO2 populates these `Property` values into the executor via the standard JavaBean setter pattern (`setMailSmtpHost`, `setMailSmtpPort`, etc.) at startup, using reflection — which is exactly why every configurable field has a public getter/setter pair, even though nothing else in the codebase calls them directly. They exist purely as the contract WSO2's configuration loader expects.

---

## Configuration

| Property | Default | Purpose |
|---|---|---|
| `mailSmtpHost` | `localhost` | SMTP server hostname |
| `mailSmtpPort` | `1025` | SMTP server port |
| `mailFromAddress` | `apim-noreply@example.com` | Email `From` address |
| `mailFromName` | `WSO2 API Manager` | Email `From` display name |
| `adminUsername` | `admin` | Who receives pending-approval alerts — falls back to the realm's configured admin user if left blank |
| `portalUrl` | `https://localhost:9443/devportal` | Developer Portal base URL. The Admin Console URL used in the admin alert email is derived automatically by replacing `devportal` → `admin` in this value |

---

## Test suite

The project maintains **two deliberately separate test classes**, because they serve fundamentally different purposes and have different infrastructure requirements:

### `CustomUserSignUpWorkflowExecutorTest`

Runs on **every** `mvn test`. Requires **zero external infrastructure** — no live SMTP server, no WSO2 instance, no network access. This is what makes it safe to run in CI on every commit.

**How it achieves this:**
- **Mockito** mocks WSO2's `PrivilegedCarbonContext`, `UserRealm`, and `UserStoreManager` — so the test never touches a real Carbon user store.
- **SubEthaSMTP (Wiser)** spins up a real, in-process SMTP server on a randomly-assigned free port for the duration of the test class, and tears it down afterward. This means the tests assert against *actual* `javax.mail.internet.MimeMessage` objects — real parsed email content, real headers, real `Content-Type` — rather than just verifying that a method was called with certain arguments. This is meaningfully stronger test coverage than pure mock-verification would give.

**32 test executions across 8 nested groups:**

| Group | What it verifies |
|---|---|
| `PropertyAccessors` | All setter/getter pairs round-trip correctly |
| `WorkflowType` | `getWorkflowType()` returns `AM_USER_SIGNUP` |
| `ExecuteStage` | Dual admin+applicant alerts on signup; admin Console URL derivation; missing/empty/null admin-claim guards; realm-admin fallback when `adminUsername` is blank; graceful no-crash behavior when the user store throws |
| `CompleteStage` | Approval and rejection emails; missing/empty user-claim guards; no-op for unhandled intermediate statuses; **the rejection-cache regression test and its edge case** (see below) |
| `TemplateContent` | UTC timestamp formatting in the admin email; "Not provided" fallback text for a missing applicant email; smoke test confirming all templates render without throwing |
| `HtmlEscaping` | Five parameterized XSS payloads (`<script>`, attribute-breakout via `onmouseover`, SQL-style quote injection, `<img onerror>`, pre-encoded entities) asserted against the *actual rendered email body* — plus direct reflection-based unit tests on `esc()` itself |
| `EmailInfrastructure` | Configured From-address/name correctly bound onto MIME headers; `Content-Type: text/html` confirmed; SMTP-unreachable failures are swallowed, never thrown |
| `SharedCss` | The shared CSS block is well-formed and defines the expected structural classes |

**A non-obvious but important detail:** the test suite includes a deliberate **race-condition guard**. `javax.mail.Transport.send()` returns as soon as the SMTP server ACKs the `DATA` command — which can happen *before* Wiser's accept thread finishes appending the message to its internal list. Without accounting for this, fast machines and CI runners can read an empty message list immediately after `send()` returns, producing intermittent, hard-to-reproduce test failures. The suite solves this with `awaitMessageCount()` — a short poll-with-timeout helper — wired in via `withCarbonContextExpectingMessages()`, used anywhere a test needs to immediately inspect a just-sent email.

---

## The development journey — problems faced & solved

This section documents the real problems encountered while building this extension, in the order they came up, because each one represents a genuine lesson about building WSO2 OSGi extensions — not just a changelog entry.

### 1. OSGi packaging and classloader issues

WSO2 APIM runs on **Eclipse Equinox**, an OSGi container with strict classloader isolation between bundles. Early iterations of this kind of extension are tempting to build with a templating library (Thymeleaf, FreeMarker) for cleaner HTML generation — but doing so inside a `dropins/`-deployed bundle risks `NoClassDefFoundError` at runtime, because the templating library's classes aren't visible across the OSGi module boundary unless extensively (and fragile-ly) wired via `Import-Package`/`Export-Package` directives.

**Resolution:** the executor uses **zero external runtime dependencies** beyond what Carbon already exports — `javax.mail` for SMTP and plain Java string concatenation for HTML. This trades templating ergonomics for deployment reliability, which is the right trade for a single-file extension with four templates.

Recurring deployment friction during local development included:
- Wrong `bundles.info` profile path being targeted by manual dropins copies
- The `<packaging>jar</packaging>` vs `<packaging>bundle</packaging>` distinction in `pom.xml` — using plain `jar` silently skips OSGi manifest generation entirely
- **Stale OSGi cache** in `work/osgi/` causing the server to keep running an old version of the bundle even after a fresh JAR was dropped in — this is why `deploy.ps1`'s `-FreshInstall` flag explicitly clears `work/` and `tmp/` before restarting
- Duplicate bundle conflicts caused by Windows path-separator mismatches between how the JAR was copied in and how Equinox indexed it

### 2. Designing the two-stage workflow

WSO2's `UserSignUpApprovalWorkflowExecutor` exposes `execute()` (called once, at signup submission) and `complete()` (called once, when the admin makes a decision) as two **separate HTTP-triggered invocations** — not two steps of one continuous method call. This matters: any state you want to share between them cannot simply be a local variable; it has to be persisted somewhere external to the method call stack. This constraint directly shaped the [caching fix](#6-the-silent-rejection-email-bug-the-big-one) described below.

### 3. Building a hermetic test suite without a real SMTP server

The first testing approach considered was mocking `javax.mail.Transport.send()` directly via Mockito — but this only proves the method *was called*, not that the resulting `MimeMessage` was actually well-formed, correctly addressed, or contained the expected rendered HTML. A mocked `Transport.send()` can't catch a bug where the email body silently contains `null` instead of a username, for example.

**Resolution:** SubEthaSMTP's `Wiser` class runs a real, minimal SMTP server in-process. The executor's `sendEmail()` method is configured to point at Wiser's ephemeral port during tests, and assertions read the actual `MimeMessage` objects Wiser received — real subject lines, real MIME content-type headers, real parsed HTML bodies. This is strictly stronger test coverage for the same amount of test code.

An earlier candidate, **GreenMail**, was rejected specifically because GreenMail's modern releases (2.x) depend on **`jakarta.mail`** — a different Maven coordinate and package namespace (`jakarta.mail.*`) from the **`javax.mail`** (`javax.mail.*`) used throughout this project and required by the WSO2 Carbon runtime. Mixing the two on one classpath risks split-package conflicts and `NoClassDefFoundError` at test-run time. Wiser, being `javax.mail`-native, avoids this entirely.

### 4. The SMTP race condition

Even with Wiser working correctly, two specific tests (`adminUrlIsCorrectlyDerived`, `rejectionEmailBody`) intermittently failed with "message not found at index 0" — despite the production code being completely correct.

**Root cause:** `Transport.send()` is synchronous from the caller's perspective — it blocks until the SMTP server acknowledges the `DATA` command — but Wiser's *internal bookkeeping* (appending the parsed message to its `getMessages()` list) happens on a separate accept thread, slightly *after* that acknowledgment is sent. On a sufficiently fast machine, the test's very next line (reading `wiser.getMessages()`) could execute before Wiser finished that append.

**Resolution:** `awaitMessageCount(expectedCount, timeoutMillis)` polls the message list every 20ms up to a timeout, instead of asserting immediately after `send()` returns. This is a textbook example of why "send returned successfully" and "the receiving system has fully processed the message" are not the same guarantee, even for synchronous-looking APIs.

### 5. The XSS escaping gap

The original `esc()` implementation escaped five characters: `&`, `<`, `>`, `"`, `'`. This neutralizes the most common injection vector — a literal `<script>` tag — but a parameterized test case using the payload `<img src=x onerror=alert(1)>` exposed a gap: escaping `<` and `>` prevents the string from being parsed as an actual `<img>` HTML element, but it does **not** remove the literal substring `onerror=alert(1)` from the rendered output, since `=` was never escaped.

**Resolution:** adding `=` → `&#61;` to `esc()` closes this gap. Deliberately **not** added: backtick (`` ` ``) and forward-slash (`/`) escaping, which appeared in one intermediate iteration of this code. Backtick escaping is relevant to JavaScript template literal injection, not HTML rendering — irrelevant here. Forward-slash escaping is actively harmful: since `esc()` is sometimes applied near URLs in templates, escaping `/` would corrupt every `https://...` link the moment that pattern was touched. This is a good example of "more escaping" not automatically meaning "more secure" — each character needs to be justified by an actual attack vector relevant to the rendering context.

### 6. The silent rejection-email bug (the big one)

This was found via **real production server logs**, not a test case — which is itself a useful data point about the limits of unit testing with mocked dependencies.

**The symptom:** rejection emails were not being sent to applicants, with no visible error anywhere except a single `ERROR`-level log line buried in the WSO2 server log:

```
ERROR - CustomUserSignUpWorkflowExecutor Error reading user email during workflow completion
org.wso2.carbon.user.core.UserStoreException: 30007 - UserNotFound:
User testaccount1 does not exist in: PRIMARY
    at ...AbstractUserStoreManager.getUserClaimValue(...)
    at CustomUserSignUpWorkflowExecutor.sendUserNotification(...)
    at CustomUserSignUpWorkflowExecutor.complete(...)
```

**Root cause:** by the time an admin's **rejection** decision reaches `complete()`, WSO2 has, in some cases, already removed or invalidated the applicant's user-store record. The original implementation performed a **live** claim lookup at that point:

```java
String userEmail = usm.getUserClaimValue(username, EMAIL_CLAIM_URI, null);
```

When the account no longer exists, this throws `UserStoreException`, which was caught, logged at `ERROR` level, and the method simply returned — **silently dropping the rejection email**. This is precisely the worst-case outcome: the *one* status where the applicant most needs a clear explanation (rejection) was exactly the status most likely to trigger this failure mode, since rejected accounts are more likely to be cleaned up quickly than approved ones.

**Resolution — capture-then-fallback caching:**

```java
private static final ConcurrentMap<String, String> pendingUserEmailCache = new ConcurrentHashMap<>();
```

- **At Stage 1** (`execute()`), while the user record is guaranteed to still exist (the applicant just submitted the form), their email is captured into `pendingUserEmailCache`, keyed by username.
- **At Stage 2** (`complete()`), `sendUserNotification()` now tries the live claim lookup first. If that throws `UserStoreException` *or* returns null/empty, it falls back to the cached email captured in Stage 1.
- The cache entry is removed once the workflow reaches a terminal state (approved or rejected), preventing unbounded memory growth.

```java
private void sendUserNotification(String username, WorkflowStatus status) {
    String userEmail = null;
    try {
        userEmail = usm.getUserClaimValue(username, EMAIL_CLAIM_URI, null);
    } catch (UserStoreException e) {
        log.warn("Could not look up live email claim for user: " + username
                + " (account may already be removed — falling back to cached email).");
    }

    if (userEmail == null || userEmail.isEmpty()) {
        userEmail = pendingUserEmailCache.get(username);
    }

    pendingUserEmailCache.remove(username);
    // ... proceed to send the approval/rejection email with whichever email was resolved
}
```

Two regression tests lock this behavior in permanently:
- `rejectionEmailSentFromCacheWhenUserRecordAlreadyRemoved` — runs `execute()` to populate the cache, then forces `UserStoreException` on the Stage 2 lookup, and asserts the rejection email is **still sent**, sourced from the cache.
- `noEmailWhenUserStoreThrowsAndCacheIsEmpty` — the companion edge case: if `complete()` runs for a user whose `execute()` was never observed (e.g. a process restart between stages), the system must still fail gracefully — no email, no crash — rather than throwing.

> **Known limitation:** this cache is **process-local (in-memory)**. It does not survive a WSO2 server restart occurring between Stage 1 and Stage 2. For deployments where pending approvals can realistically sit for hours or days, a persisted lookup (a small dedicated DB table, or storage in the workflow's own WSO2-managed properties) would be a more durable replacement — see [Known limitations](#known-limitations--future-work).

### 7. File drift between iterations

A meta-lesson worth documenting: across many rounds of iteration on this codebase, the single most time-consuming class of bug was not a logic error — it was **the file actually being compiled not matching the file believed to be in place**. A test failure (`onerror=alert` not being escaped) was eventually traced not to a logic bug, but to the production `esc()` method on disk still being an earlier 5-character-escape version, missing the `=` rule that had been discussed and supposedly applied in a previous iteration.

**Lesson:** when iterating on a single file across many sessions/conversations, periodically re-verify the actual file on disk against what's believed to be current — especially before debugging a test failure, since "the test is wrong" and "the file is stale" produce identical symptoms but require completely different fixes.

---

## XSS protection

Usernames are user-controlled input, rendered directly into HTML email bodies. `esc()` escapes six characters before any username or email address is interpolated into a template:

| Character | Escaped to | Why |
|---|---|---|
| `&` | `&amp;` | Prevents breaking other entity references |
| `<` | `&lt;` | Prevents opening a new HTML tag |
| `>` | `&gt;` | Prevents closing into an unintended tag boundary |
| `"` | `&quot;` | Prevents breaking out of a double-quoted HTML attribute |
| `'` | `&#39;` | Prevents breaking out of a single-quoted HTML attribute |
| `=` | `&#61;` | Closes the bare-attribute-injection gap (see below) |

**Why `=` matters specifically:** a username like `<img src=x onerror=alert(1)>` has its `<` and `>` escaped, which prevents the browser/email client from parsing it as an actual `<img>` element — but without escaping `=`, the literal substring `onerror=alert(1)` survives intact in the rendered output. Escaping `=` ensures this can never even superficially resemble a live HTML attribute, closing the gap completely. This exact scenario is covered by a parameterized XSS test case in the unit suite (see [Test suite](#test-suite)).

`esc()` is intentionally a narrow, purpose-built escaper for plain-text values (usernames, email addresses) interpolated into a controlled, known-safe template — it is not a general-purpose HTML sanitizer and should not be reused for arbitrary rich-text or user-supplied HTML input.

---

## Known limitations & future work

- **In-memory email cache is single-node only.** A WSO2 server restart occurring between a user's signup submission and the admin's decision will lose the cached fallback email, reverting to the original failure mode for that one request. Acceptable for typical same-session approval flows; not safe for deployments where approvals can remain pending for extended periods without a persisted store.
- **No retry or dead-letter queue for failed SMTP sends.** Failures are logged and dropped. Reasonable for a notification-only, non-business-critical feature as currently scoped — worth revisiting if email delivery becomes a hard requirement (e.g. compliance-driven audit trails).
- **`esc()` is a manual, narrow-purpose escaper**, not a full HTML sanitization library. It is correctly scoped for its one job (escaping plain-text usernames/emails into a fixed template) and should not be generalized to arbitrary HTML input without a proper sanitization library.

---

## Troubleshooting

**`NoClassDefFoundError` for any external library class** — A dependency was added to `pom.xml` without `<scope>provided</scope>`, or without a corresponding `Import-Package` entry. Eclipse Equinox cannot resolve classes across bundle boundaries without explicit OSGi metadata. Revert to using only classes already exported by the Carbon runtime, or properly configure `Import-Package` in the `maven-bundle-plugin` configuration.

**Custom executor doesn't seem to trigger at all** — Check for a typo in the fully-qualified class name inside `workflow-extensions.xml`, and clear the OSGi `work/` cache before restarting (`deploy.ps1 -FreshInstall` does this automatically). A stale cache can cause Equinox to keep an old bundle version loaded even after a new JAR is dropped into `dropins/`.

**Emails aren't arriving / fields are blank** — Most commonly, the target user's profile is missing the `http://wso2.org/claims/emailaddress` claim. Check via the Carbon Admin Console (`https://localhost:9443/carbon` → Users and Roles → user profile) that the Email Address attribute is populated.

**`CARBON is supported only between JDK 11 and JDK 17`** at server startup — `JAVA_HOME` is pointing at an unsupported JDK version (e.g. 21). Set `JAVA_HOME` to a JDK in the 11–17 range before starting WSO2.

---

## License

Distributed under the MIT License.

---

## Author

Built as a hands-on deep-dive into WSO2 API Manager's OSGi extension model, Carbon user-store APIs, and production-grade email notification design — including finding and fixing a real silent-failure bug through actual server log analysis rather than just unit test coverage.