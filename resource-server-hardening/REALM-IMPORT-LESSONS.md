# Realm import: what we learned the hard way

The realm JSON has now caused three separate failures. All three share one
root cause: **a Keycloak realm import is declarative and total** - whatever
section you provide REPLACES Keycloak's defaults for that section, and
whatever you omit is left to defaults. Partial declarations silently
destroy things you did not intend to touch.

| # | Symptom | Cause |
|---|---------|-------|
| 1 | `unauthorized_client` on every confidential client | `${env.VAR}` substitution did not run; secrets imported as literal placeholder strings |
| 2 | `aud` claim absent | realm-level `defaultDefaultClientScopes` only applies to clients created AFTER it exists - not retroactive |
| 3 | `sub` claim absent -> `jwt.getSubject()` null in EVERY service | declaring a `clientScopes` array replaced ALL built-in scopes; `basic` (which carries the sub mapper) was never created |

## The rule we now follow

**Keep the import minimal; provision the rest explicitly.**

The import file declares only: realm settings, roles, clients, users.
Everything else - client secrets, custom client scopes, protocol mappers,
scope attachments, service-account roles - is applied by idempotent kcadm
scripts that can be re-run and, crucially, VERIFIED:

    ./fix-client-secrets.sh      # secrets from .env
    ./provision-audience.sh      # easyshop-api-audience scope + attach
    ./add-sub-mapper.sh          # oidc-sub-mapper (restores 'sub')

This is the same conclusion real teams reach with the Keycloak Terraform
provider or the Keycloak Operator: declarative-but-total import files are
a blunt instrument for anything beyond initial structure.

## Also worth remembering

Deleting a realm destroys its users. We deleted and re-imported `easyshop`
while fixing the secrets, which gave `demo.customer` a NEW Keycloak ID -
leaving a stale `keycloak_id` in user-service's database pointing at a user
that no longer exists. Any external system keyed on IdP user IDs needs
reconciliation after a realm rebuild; in production that is a migration,
not a footnote.