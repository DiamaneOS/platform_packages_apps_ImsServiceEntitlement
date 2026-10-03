# Carrier IMS entitlement

This fork keeps Android's carrier HTTPS activation and polling client, without
its Firebase/Google push components and dependencies. It replaces the
`ImsServiceEntitlement` module inherited from Android's telephony product. It
is not the IMS service.

## When it runs

The client keeps SIM authentication and normal TLS certificate/hostname checks.
It sends no notification token and does not pretend to support push. Server
validity controls polling: shorter validity periods are honored, while longer or
unlimited validity is refreshed daily. JobScheduler handles retry backoff after
failure; server-directed stop states remain stopped.
This cannot reproduce immediate carrier push notifications, and carriers that
require that transport remain an explicit compatibility limit.

- The supported devices' factory configuration and the source carrier assets
  configure no IMS entitlement URL, activation activity, FCM sender or
  background IMS provisioning.
- The package being installed is not a reason to force activation.
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
- SIM/job validity is rechecked after the network response.
- An explicit carrier denial may still revoke provisioning.
- Permissions: the necessary phone-state/provisioning permissions and ordinary
  Internet access, with its own application UID and no platform signing.
- It opts out of DiamaneOS's implicit motion-sensor permission, which it does
  not use. Carrier HTTPS and phone provisioning permissions stay explicit.
- Include `diamaneos/board.mk` for its dedicated SELinux domain. It cannot speak
  directly to the modem.

## Polling and retries
