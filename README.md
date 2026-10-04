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
validity controls polling: refreshes have a thirty-second floor and a daily
ceiling, while unlimited validity is refreshed daily. JobScheduler handles retry backoff after
failure; server-directed stop states remain stopped.
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

Parser nesting (64levels) and element count (4096) are named downstream
containment budgets, independent of the transport byte cap; they are not TS.43
carrier requirements. A thirty-second minimum Retry-After delay prevents tight
request loops while preserving longer carrier-directed delays. Existing host
checks cover parser bounds and transport behavior. The application opts out of
DiamaneOS's unused implicit motion-sensor permission; carrier HTTPS and phone
provisioning permissions remain explicit.

Each check has one 120-second lifetime covering admission, HTTPS, redirects,
AKA/token renewal and local provisioning. This downstream budget is four ordinary
30-second HTTP stages; it is not a carrier protocol timeout. Three physical
workers and three queued checks cover the two active FP6 subscriptions and the
activation UI. Cancellation does not create replacement capacity for a blocked
framework/transport operation. Transport cleanup and serialized job scheduling
have separate bounded workers; neither runs on the deadline or main thread.

Ordinary failure uses Android JobScheduler's own exponential retry policy, without
a permanent attempt cutoff. Carrier Retry-After establishes a subscription-local
not-before timestamp; early framework retries do not make HTTPS or AKA requests.
Accepted carrier stop states are reconciled locally after a setter failure, with
the existing entitlement-version upgrade exception. A run/generation fence and
nonblocking per-subscription provisioning gate prevent a late poll from applying
a successor's state. Provisioning setters remain separate framework operations;
a failed/expired sequence is retried rather than described as an atomic update.
