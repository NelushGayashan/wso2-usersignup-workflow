# WSO2 API Manager Custom User Signup Approval Workflow Extension

![Java](https://img.shields.io/badge/Java-17-orange)
![WSO2 APIM](https://img.shields.io/badge/WSO2_APIM-4.2.0-blue)
![OSGi](https://img.shields.io/badge/OSGi-Bundle-green)
![JUnit 5](https://img.shields.io/badge/JUnit-5.10.2-success)
![Mockito](https://img.shields.io/badge/Mockito-5.11.0-brightgreen)

---

# Overview

This project extends the default **WSO2 API Manager User Signup Workflow** by introducing an administrator approval process and enterprise-grade HTML email notifications.

By default, WSO2 API Manager allows user registrations through the Developer Portal and processes them using workflow executors.

This extension enhances the standard workflow by:

- Sending an email to administrators when a new user registers
- Keeping the registration in a pending state
- Allowing administrators to approve or reject requests
- Sending approval emails to approved users
- Sending rejection emails to rejected users
- Preserving the default WSO2 workflow lifecycle

---

# Features

## Administrative Approval Workflow

Extends:

```java
UserSignUpApprovalWorkflowExecutor
```

Benefits:

- Uses native APIM workflow engine
- No database schema changes required
- No modifications to APIM source code
- Deployable as an OSGi bundle

---

## Administrator Notification Email

When a user submits a registration request:

Administrator receives:

- Username
- Email address
- Submission timestamp
- Pending approval status
- Direct Admin Portal review link

---

## User Approval Email

When registration is approved:

User receives:

- Approval confirmation
- Developer Portal link
- API subscription guidance
- OAuth credential guidance

---

## User Rejection Email

When registration is rejected:

User receives:

- Rejection notification
- Support contact information
- Friendly explanatory message

---

## Security

The implementation includes HTML escaping to prevent:

- XSS attacks
- HTML injection
- Malicious username rendering

Escaped characters:

```text
&
<
>
"
'
=
`
/
```

---

# Workflow Architecture

```text
┌──────────────────────────┐
│ Developer Portal         │
└────────────┬─────────────┘
             │
             ▼
      User Registration
             │
             ▼
┌──────────────────────────┐
│ execute()               │
│ Custom Workflow         │
└────────────┬─────────────┘
             │
             ▼
     Admin Notification
             │
             ▼
      Pending Approval
             │
             ▼
      Admin Decision
      (Approve / Reject)
             │
             ▼
┌──────────────────────────┐
│ complete()              │
│ Custom Workflow         │
└───────┬────────┬─────────┘
        │        │
        ▼        ▼
 Approved     Rejected
  Email        Email
```

---

# Workflow Lifecycle

## Stage 1 — Registration Submission

User registers through:

```text
https://localhost:9443/devportal
```

WSO2 invokes:

```java
execute(WorkflowDTO workflowDTO)
```

Actions performed:

1. Execute default signup workflow
2. Resolve administrator email
3. Generate pending approval email
4. Send notification

---

## Stage 2 — Approval Decision

Administrator reviews registration through:

```text
https://localhost:9443/admin
```

WSO2 invokes:

```java
complete(WorkflowDTO workflowDTO)
```

Actions performed:

1. Execute default workflow completion
2. Determine final workflow status
3. Resolve user email
4. Send approval/rejection notification

---

# Email Templates

## Template 1 — Pending Approval

Recipient:

```text
Administrator
```

Purpose:

```text
Notify administrators that a developer registration requires approval.
```

Contains:

- Username
- Email address
- Submission time
- Status indicator
- Admin Console link

---

## Template 2 — Registration Approved

Recipient:

```text
Developer
```

Contains:

- Welcome message
- Developer Portal link
- API subscription information
- OAuth credential guidance

---

## Template 3 — Registration Rejected

Recipient:

```text
Developer
```

Contains:

- Registration rejection notification
- Support contact information

---

# Class Overview

## CustomUserSignUpWorkflowExecutor

Extends:

```java
UserSignUpApprovalWorkflowExecutor
```

### Core Methods

| Method | Description |
|----------|-------------|
| execute() | Sends administrator notification |
| complete() | Sends approval/rejection notification |
| sendAdminAlert() | Creates pending approval email |
| sendUserNotification() | Creates approval/rejection email |
| sendEmail() | Sends email through JavaMail |
| sharedCss() | Generates reusable email styling |
| esc() | Escapes HTML-sensitive characters |

---

# Configuration Properties

Default values:

| Property | Default Value |
|-----------|--------------|
| mailSmtpHost | localhost |
| mailSmtpPort | 1025 |
| mailFromAddress | apim-noreply@example.com |
| mailFromName | WSO2 API Manager |
| adminUsername | admin |
| portalUrl | https://localhost:9443/devportal |

Example:

```java
executor.setMailSmtpHost("smtp.company.com");
executor.setMailSmtpPort("587");
executor.setMailFromAddress("noreply@company.com");
executor.setMailFromName("Company API Manager");
executor.setAdminUsername("admin");
executor.setPortalUrl("https://apim.company.com/devportal");
```

---

# Technology Stack

| Component | Version |
|------------|----------|
| Java | 17 |
| Maven | 3.x |
| WSO2 API Manager | 4.2.0 |
| Carbon API Manager Components | 9.28.1 |
| Carbon Kernel Components | 4.7.0 |
| JUnit | 5.10.2 |
| Mockito | 5.11.0 |
| SubEthaSMTP (Wiser) | 3.1.7 |

---

# Maven Dependencies

## Runtime Dependencies

### org.wso2.carbon.apimgt.impl

Provides:

- Workflow executors
- Workflow DTOs
- Workflow APIs

Version:

```xml
9.28.1
```

---

### org.wso2.carbon.user.core

Provides:

- UserStoreManager
- User claims
- User realm access

Version:

```xml
4.7.0
```

---

### org.wso2.carbon.utils

Provides Carbon utility functionality used by APIM.

Version:

```xml
4.7.0
```

---

### commons-logging

Provides logging abstraction.

Version:

```xml
1.2
```

---

### javax.mail

Provides:

- SMTP communication
- MIME message creation
- HTML email support

Version:

```xml
1.4.7
```

---

# Test Dependencies

## JUnit 5

Framework for unit testing.

```xml
org.junit.jupiter:junit-jupiter
```

---

## Mockito

Framework for mocking WSO2 APIs and Carbon components.

```xml
org.mockito:mockito-core
org.mockito:mockito-junit-jupiter
```

---

## SubEthaSMTP (Wiser)

Lightweight in-memory SMTP server used for testing email delivery.

```xml
org.subethamail:subethasmtp
```

---

# Build Requirements

## Required Software

| Software | Version |
|-----------|----------|
| JDK | 17 |
| Maven | 3.8+ |
| WSO2 API Manager | 4.2.0 |

Verify:

```bash
java --version
```

Expected:

```text
17.x.x
```

---

# Building

Clean build:

```bash
mvn clean
```

Compile:

```bash
mvn compile
```

Run tests:

```bash
mvn test
```

Package bundle:

```bash
mvn clean package
```

Generated artifact:

```text
target/
└── com.mycompany.custom.usersignup.extension-1.0.0.jar
```

---

# OSGi Bundle Configuration

Packaging:

```xml
<packaging>bundle</packaging>
```

Bundle plugin:

```xml
org.apache.felix:maven-bundle-plugin
```

Exports:

```text
com.mycompany.custom.usersignup.*
```

Imports:

```text
org.wso2.carbon.apimgt.impl.workflow.*
org.wso2.carbon.context.*
org.wso2.carbon.user.api.*
javax.mail.*
```

---

# Deployment

Copy generated JAR:

```bash
target/com.mycompany.custom.usersignup.extension-1.0.0.jar
```

to:

```text
<APIM_HOME>/repository/components/dropins/
```

Restart WSO2 API Manager.

---

# APIM Configuration

Configure the workflow executor in:

```toml
deployment.toml
```

Example:

```toml
[apim.workflow]
enable = true

[[apim.workflow.workflows]]
type = "AM_USER_SIGNUP"
executor = "com.mycompany.custom.usersignup.CustomUserSignUpWorkflowExecutor"
```

Restart APIM after configuration changes.

---

# Testing Coverage

The project includes comprehensive tests for:

## Workflow Execution

- execute()
- complete()
- workflow type validation

## Email Delivery

- Administrator emails
- Approval emails
- Rejection emails
- SMTP failures

## Template Rendering

- Pending approval template
- Approval template
- Rejection template

## Security

- XSS prevention
- HTML escaping
- Null handling

## Configuration

- Getter methods
- Setter methods
- Property overrides

---

# Project Structure

```text
src
├── main
│   └── java
│       └── com.mycompany.custom.usersignup
│           └── CustomUserSignUpWorkflowExecutor.java
│
├── test
│   └── java
│       └── com.mycompany.custom.usersignup
│           └── CustomUserSignUpWorkflowExecutorTest.java
│
└── pom.xml
```

---

# Future Enhancements

Potential improvements:

- SMTP authentication support
- Externalized HTML templates
- Localization support
- Slack notifications
- Microsoft Teams integration
- Reminder notifications
- Audit event logging
- Branding customization

---

# License

Internal / Custom Enterprise Extension

---

# Author

Custom User Signup Approval Workflow Extension for WSO2 API Manager 4.2.0