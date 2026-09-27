# Carrier IMS entitlement

This fork retains Android's carrier HTTPS activation and polling client while
removing its Firebase/Google push components and dependencies. It replaces the
existing `ImsServiceEntitlement` module inherited from Android's telephony product;
it is not the IMS service, an emergency dialler or an AML implementation.

Use is carrier-specific. The inspected FP6 factory configuration and current
source carrier assets do not configure an IMS entitlement URL, activation activity,
FCM sender or background IMS provisioning. Their presence is not a reason to
force activation. With no carrier HTTPS endpoint, this client makes no entitlement
request. Carrier-authorized configuration may enable it for networks that need
TS.43 provisioning or a Wi-Fi-calling address portal.

The client keeps SIM authentication and normal TLS certificate/hostname checks.
It sends no notification token and does not pretend to support push. Server
validity controls polling, with a daily refresh for active configurations and
JobScheduler backoff after failure; server-directed stop states remain stopped.
This cannot reproduce immediate carrier push notifications, and carriers that
require that transport remain an explicit compatibility limit.

Requests use HTTPS on the default Android network, bounded response streams and
bounded XML parsing without external references. Carrier portal data and raw
responses are not logged by this app. Portals block non-HTTPS navigation, local
file/content access and mixed content. Failed queries preserve existing
provisioning; they never count as an approval. SIM/job validity is rechecked after
the network response. An explicit carrier denial may still revoke provisioning.

The app retains the necessary phone-state/provisioning permissions and ordinary
Internet access, with its own application UID and no platform signing. Include
`diamaneos/board.mk` for its dedicated SELinux domain. It cannot speak directly to
the modem. Runtime permission, domain and carrier behavior still need native
qualification.

Run `sh tests/run-host-tests.sh` with a JDK (or `JAVA_HOME`) for memory-only HTTPS,
stream-limit and XML tests. The existing `ImsServiceEntitlementUnitTests` exercise
Android service/activation flows with mocks after a platform test build. No host
test proves carrier activation, Wi-Fi calling or emergency-address registration.
