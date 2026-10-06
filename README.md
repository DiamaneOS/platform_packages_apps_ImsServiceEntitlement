# Carrier IMS entitlement

This fork keeps Android's carrier HTTPS activation and polling client, without
its Firebase/Google push components and dependencies. It replaces the
`ImsServiceEntitlement` module inherited from Android's telephony product. It
is not the IMS service, an emergency dialler or an AML implementation.

## When it runs

Use is carrier-specific.

- The inspected FP6 factory configuration and current source carrier assets
  configure no IMS entitlement URL, activation activity, FCM sender or
  background IMS provisioning. Their presence is not a reason to force
  activation.
- With no carrier HTTPS endpoint, the client makes no entitlement request.
- Carrier-authorized configuration may enable it for networks that need TS.43
  provisioning or a Wi-Fi-calling address portal.

## Security and privacy

- Keeps SIM authentication and normal TLS certificate/hostname checks.
- Sends no notification token and does not pretend to support push.
- HTTPS on the default Android network, bounded response streams, bounded XML
  parsing without external references.
- The app logs no carrier portal data or raw responses.
- Portals block non-HTTPS navigation, local file/content access and mixed
  content.
- Failed queries keep existing provisioning and never count as approval.
  SIM/job validity is rechecked after the network response. An explicit carrier
  denial may still revoke provisioning.
- Permissions: the necessary phone-state/provisioning permissions and ordinary
  Internet access, with its own application UID and no platform signing. It
  opts out of DiamaneOS's unused implicit motion-sensor permission; carrier
  HTTPS and phone provisioning permissions stay explicit.
- Include `diamaneos/board.mk` for its dedicated SELinux domain. It cannot speak
  directly to the modem.
- Runtime permission, domain and carrier behavior still need native
  qualification.

## Polling and retries

- Server validity controls polling: a thirty-second floor and a daily ceiling;
  unlimited validity refreshes daily.
- Ordinary failure uses JobScheduler's own exponential retry backoff, with no
  permanent attempt cutoff. Server-directed stop states stay stopped.
- A thirty-second minimum Retry-After delay prevents tight request loops but
  keeps longer carrier-directed delays. Carrier Retry-After sets a
  subscription-local not-before time; early framework retries make no HTTPS or
  AKA requests.
- Immediate carrier push notifications cannot be reproduced. Carriers that
  require that transport are an explicit compatibility limit.
- Accepted carrier stop states are reconciled locally after a setter failure,
  with the existing entitlement-version upgrade exception.
- A run/generation fence and a nonblocking per-subscription provisioning gate
  stop a late poll from applying a successor's state.
- Provisioning setters stay separate framework operations; a failed/expired
  sequence is retried, not described as an atomic update.

## Downstream budgets

Containment choices, not TS.43 carrier requirements or protocol timeouts:

- Parser nesting 64 levels, element count 4096, independent of the transport
  byte cap.
- One 120-second lifetime per check, covering admission, HTTPS, redirects,
  AKA/token renewal and local provisioning: four ordinary 30-second HTTP stages.
- Three physical workers and three queued checks cover the two active FP6
  subscriptions and the activation UI. Cancellation does not create
  replacement capacity for a blocked framework/transport operation.
- Transport cleanup and serialized job scheduling have separate bounded
  workers; neither runs on the deadline or main thread.

## Tests

- `sh tests/run-host-tests.sh` with a JDK (or `JAVA_HOME`): memory-only HTTPS,
  stream-limit, parser-bound and XML tests.
- The existing `ImsServiceEntitlementUnitTests` exercise Android
  service/activation flows with mocks after a platform test build.
- No host test proves carrier activation, Wi-Fi calling or emergency-address
  registration.
