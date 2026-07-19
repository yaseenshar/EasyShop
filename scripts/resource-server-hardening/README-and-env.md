# Containerized OIDC: issuer vs JWKS reachability

## The problem in one line
`iss` must match what the TOKEN says (`http://localhost:8090/...`, because
that's the URL tokens are minted through), but the JWKS endpoint must be
reachable FROM INSIDE the Docker network (`http://keycloak:8080/...`).
One property can't be both. So use two.

## The fix: split issuer-uri and jwk-set-uri

Spring Boot supports specifying both. When `jwk-set-uri` is present it is
used to fetch keys, and when `issuer-uri` is ALSO present its value is used
for issuer validation. Result: keys fetched over the Docker network,
`iss` validated against the URL clients actually used.

CONFIDENCE FLAG: this dual-property behavior is my strong understanding of
Spring Boot's resource-server auto-config, but I have not verified it
against the 4.1 reference. Verify empirically with the test below. If
issuer validation turns out NOT to be applied when jwk-set-uri is set,
fall back to Alternative B.

## Apply to infra/docker-compose.yml — EVERY resource server

Add to the `environment:` block of api-gateway, user-service,
order-service, review-service, and cart-service (replacing the existing
single SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI line):

      SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI: http://localhost:8090/realms/easyshop
      SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI: http://keycloak:8080/realms/easyshop/protocol/openid-connect/certs
      SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES: easyshop-api

Note SPRING_..._AUDIENCES as a comma-separated string is the env-var form
of the yml list `audiences: [easyshop-api]`.

## Alternative B (if the split doesn't work): pin Keycloak's hostname

Make Keycloak ALWAYS mint `iss=http://localhost:8090/realms/easyshop`
regardless of network path, by adding to the keycloak service:

      KC_HOSTNAME: http://localhost:8090
      KC_HOSTNAME_STRICT: "false"

then services still need jwk-set-uri pointing at keycloak:8080 to fetch
keys — so B complements the split rather than replacing it. (KC_HOSTNAME
syntax changed across KC 24/25/26; on 26 it takes a full URL. Verify in
the startup log which hostname Keycloak reports.)

## Verify the fix in isolation, before re-running the suite

    # 1. Can the gateway container reach the JWKS URL at all?
    docker compose -f infra/docker-compose.yml exec api-gateway sh -c \
      'wget -qO- http://keycloak:8080/realms/easyshop/protocol/openid-connect/certs' \
      || echo "unreachable - wrong hostname or KC not on this network"
    # (no wget in the image? try: docker compose -f infra/docker-compose.yml exec keycloak sh -c \
    #  'curl -s http://keycloak:8080/realms/easyshop/.well-known/openid-configuration | head')

    # 2. What issuer do minted tokens actually claim?
    source .env
    curl -s -X POST http://localhost:8090/realms/easyshop/protocol/openid-connect/token \
      -d client_id=easyshop-gateway -d "client_secret=$KEYCLOAK_GATEWAY_CLIENT_SECRET" \
      -d grant_type=password -d username=demo.customer -d 'password=Customer#Pass1' \
      | jq -r .access_token | cut -d. -f2 | base64 -d 2>/dev/null | jq '{iss, aud}'
    # The iss printed here MUST equal your issuer-uri, character for character.

## Why this matters beyond dev
Identical in Kubernetes: pods resolve the IdP by internal service DNS while
tokens are issued through the public ingress hostname. Same split, same
fix. "Our services 401 on valid tokens only in the cluster" is a real
on-call scenario and this is the answer.