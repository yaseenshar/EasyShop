package com.easyshop.user.controller;

import com.easyshop.common.dto.response.ApiResponse;
import com.easyshop.user.dto.UserDtos.AddressResponse;
import com.easyshop.user.dto.UserDtos.CreateAddressRequest;
import com.easyshop.user.dto.UserDtos.RegisterUserRequest;
import com.easyshop.user.dto.UserDtos.UpdateProfileRequest;
import com.easyshop.user.dto.UserDtos.UserResponse;
import com.easyshop.user.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * Called by a Keycloak webhook (or an event listener you wire up) immediately
     * after a new user completes registration in Keycloak. This keeps our
     * business 'users' table in sync with Keycloak's identity store without
     * user-service ever handling credentials directly.
     */
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<UserResponse>> register(
            @Valid @RequestBody RegisterUserRequest request) {
        UserResponse response = userService.registerUser(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("User registered", response));
    }

    /**
     * @AuthenticationPrincipal Jwt - Spring Security injects the validated JWT
     * directly. jwt.getSubject() returns the 'sub' claim, which is the
     * Keycloak user ID - never trust a client-supplied user ID in the URL
     * or body for "who am I" endpoints; always derive identity from the token.
     *
     * roles ride along in the response, read straight from THIS token - the
     * only place user-service can ever learn a caller's roles, since Keycloak
     * (not the local DB) owns role assignment.
     *
     * email/given_name/family_name are ALSO read from the token (added by
     * resource-server-hardening/provision-self-registration.sh) - passed
     * through purely as JIT-provisioning material for a first-ever call;
     * see UserService.getMyProfile.
     */
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserResponse>> getCurrentUser(
            @AuthenticationPrincipal Jwt jwt) {
        UserResponse response = userService.getMyProfile(
                jwt.getSubject(),
                jwt.getClaimAsString("email"),
                jwt.getClaimAsString("given_name"),
                jwt.getClaimAsString("family_name"),
                rolesOf(jwt));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PutMapping("/me")
    public ResponseEntity<ApiResponse<UserResponse>> updateCurrentUser(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UpdateProfileRequest request) {
        UserResponse response = userService.updateProfile(jwt.getSubject(), request);
        return ResponseEntity.ok(ApiResponse.success("Profile updated", response));
    }

    @GetMapping("/me/addresses")
    public ResponseEntity<ApiResponse<List<AddressResponse>>> listMyAddresses(
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(ApiResponse.success(userService.listAddresses(jwt.getSubject())));
    }

    @PostMapping("/me/addresses")
    public ResponseEntity<ApiResponse<AddressResponse>> addMyAddress(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateAddressRequest request) {
        AddressResponse response = userService.addAddress(jwt.getSubject(), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Address added", response));
    }

    /**
     * Admin surface (see SecurityConfig: /api/v1/users/** is ADMIN-only) for
     * looking up any user by their internal id - not the Keycloak subject.
     */
    @GetMapping("/{userId}")
    public ResponseEntity<ApiResponse<UserResponse>> getUserById(@PathVariable UUID userId) {
        UserResponse response = userService.getUserById(userId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Same claim locations as common-lib's KeycloakRolesConverter, but
     * returning the RAW role strings (no ROLE_ prefix) - this is data for the
     * client to read, not a Spring Security authority.
     */
    @SuppressWarnings("unchecked")
    private static List<String> rolesOf(Jwt jwt) {
        List<String> flat = jwt.getClaimAsStringList("roles");
        if (flat != null) {
            return flat;
        }
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess == null) {
            return List.of();
        }
        Object roles = realmAccess.get("roles");
        return roles instanceof List<?> list ? (List<String>) list : List.of();
    }
}
