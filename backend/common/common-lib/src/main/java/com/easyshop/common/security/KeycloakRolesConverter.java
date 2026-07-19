package com.easyshop.common.security; // align with your common-lib package layout

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Maps Keycloak roles in a validated JWT to Spring Security authorities.
 *
 * WHY THIS EXISTS: Spring Security's default JwtAuthenticationConverter only reads
 * the "scope"/"scp" claims into SCOPE_* authorities. Keycloak roles are NOT mapped
 * out of the box, so without this converter every hasRole(...) rule denies everyone
 * (symptom: 403 on every endpoint for every persona — see README traps).
 *
 * CLAIM LOCATIONS, in priority order:
 *   1. Flat "roles" claim  — what our realm's mapper emits (asserted by verify-keycloak.sh).
 *   2. "realm_access.roles" — Keycloak's default location, kept as a defensive fallback
 *      so a realm rebuilt without the custom mapper degrades to still-working RBAC
 *      instead of silent universal denial (§6.4 lesson: realm imports are total).
 *
 * CASE POLICY: roles are used EXACTLY as they appear in the token — no normalization.
 * Run decode-token.sh first (working practice #1) and make the Keycloak role names
 * match the strings used in hasRole(...) rules (CUSTOMER / VENDOR / ADMIN / SERVICE).
 * Silent .toUpperCase() here would paper over a Keycloak misconfiguration.
 *
 * PREFIX: hasRole("ADMIN") checks for the authority "ROLE_ADMIN"; the prefix is added
 * here, once, so rules stay readable. hasAuthority(...) would require the caller to
 * write the prefix at every rule — rejected as the more error-prone option.
 */
public class KeycloakRolesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    private static final String FLAT_ROLES_CLAIM = "roles";
    private static final String REALM_ACCESS_CLAIM = "realm_access";
    private static final String REALM_ACCESS_ROLES_KEY = "roles";
    private static final String ROLE_PREFIX = "ROLE_";

    @Override
    @SuppressWarnings("unchecked")
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Collection<String> roles = jwt.getClaimAsStringList(FLAT_ROLES_CLAIM);

        if (roles == null) {
            Map<String, Object> realmAccess = jwt.getClaimAsMap(REALM_ACCESS_CLAIM);
            roles = realmAccess == null
                    ? List.of()
                    : (Collection<String>) realmAccess.getOrDefault(REALM_ACCESS_ROLES_KEY, List.of());
        }

        return roles.stream()
                .map(role -> new SimpleGrantedAuthority(ROLE_PREFIX + role))
                .collect(Collectors.toUnmodifiableSet());
    }
}