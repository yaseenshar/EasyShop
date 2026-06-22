package com.easyshop.user.dto;

import com.easyshop.user.entity.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.UUID;

public final class UserDtos {
    private UserDtos() {
    } // namespace holder, not instantiable

    /**
     * Request DTO for the Keycloak-triggered registration webhook.
     * Java 25 record - immutable, auto-generates equals/hashCode/toString,
     * and the validation annotations are honored by spring-boot-starter-validation
     * when used with @Valid on the controller method parameter.
     */
    public record RegisterUserRequest(
            @NotBlank String keycloakId,
            @NotBlank @Email String email,
            @NotBlank String firstName,
            @NotBlank String lastName
    ) {
    }

    public record UpdateProfileRequest(
            @NotBlank String firstName,
            @NotBlank String lastName,
            String phoneNumber
    ) {
    }

    public record UserResponse(
            UUID id,
            String email,
            String firstName,
            String lastName,
            String phoneNumber,
            String loyaltyTier,
            Instant createdAt
    ) {
        public static UserResponse from(User user) {
            return new UserResponse(
                    user.getId(),
                    user.getEmail(),
                    user.getFirstName(),
                    user.getLastName(),
                    user.getPhoneNumber(),
                    user.getLoyaltyTier().name(),
                    user.getCreatedAt()
            );
        }
    }
}
