package com.easyshop.user.controller;

import com.easyshop.common.dto.response.ApiResponse;
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
     */
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserResponse>> getCurrentUser(
            @AuthenticationPrincipal Jwt jwt) {
        UserResponse response = userService.getUserByKeycloakId(jwt.getSubject());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PutMapping("/me")
    public ResponseEntity<ApiResponse<UserResponse>> updateCurrentUser(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UpdateProfileRequest request) {
        UserResponse response = userService.updateProfile(jwt.getSubject(), request);
        return ResponseEntity.ok(ApiResponse.success("Profile updated", response));
    }
}