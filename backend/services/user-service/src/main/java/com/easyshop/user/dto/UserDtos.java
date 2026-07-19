package com.easyshop.user.dto;

import com.easyshop.user.entity.ShippingAddress;
import com.easyshop.user.entity.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.List;
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
            Instant createdAt,
            List<String> roles,
            List<AddressResponse> addresses
    ) {
        /** Used where the caller's own JWT roles aren't in scope (register, admin by-id lookup). */
        public static UserResponse from(User user) {
            return from(user, List.of(), List.of());
        }

        /**
         * roles come from the CALLER'S OWN JWT (Keycloak owns role assignment,
         * user-service's DB does not) - only ever populated for /me, never for
         * an admin looking up someone ELSE'S record.
         */
        public static UserResponse from(User user, List<String> roles, List<AddressResponse> addresses) {
            return new UserResponse(
                    user.getId(),
                    user.getEmail(),
                    user.getFirstName(),
                    user.getLastName(),
                    user.getPhoneNumber(),
                    user.getLoyaltyTier().name(),
                    user.getCreatedAt(),
                    roles,
                    addresses
            );
        }
    }

    public record CreateAddressRequest(
            @NotBlank String label,
            @NotBlank String line1,
            String line2,
            @NotBlank String city,
            String stateProvince,
            @NotBlank String postalCode,
            @NotBlank String countryCode
    ) {
    }

    /** `line` is the single display line the checkout screen renders. */
    public record AddressResponse(
            UUID id,
            String label,
            String line,
            boolean isDefault
    ) {
        public static AddressResponse from(ShippingAddress a) {
            StringBuilder line = new StringBuilder(a.getLine1());
            if (a.getLine2() != null && !a.getLine2().isBlank()) {
                line.append(", ").append(a.getLine2());
            }
            line.append(", ").append(a.getCity());
            if (a.getStateProvince() != null && !a.getStateProvince().isBlank()) {
                line.append(", ").append(a.getStateProvince());
            }
            line.append(' ').append(a.getPostalCode()).append(", ").append(a.getCountryCode());
            return new AddressResponse(a.getId(), a.getLabel(), line.toString(), a.isDefault());
        }
    }
}
