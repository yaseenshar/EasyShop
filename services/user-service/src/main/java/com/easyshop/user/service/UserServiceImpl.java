package com.easyshop.user.service;

import com.easyshop.common.exception.DuplicateResourceException;
import com.easyshop.user.dto.UserDtos.AddressResponse;
import com.easyshop.user.dto.UserDtos.CreateAddressRequest;
import com.easyshop.user.dto.UserDtos.UserResponse;
import com.easyshop.user.dto.UserDtos.RegisterUserRequest;
import com.easyshop.user.dto.UserDtos.UpdateProfileRequest;
import com.easyshop.user.entity.ShippingAddress;
import com.easyshop.user.entity.User;
import com.easyshop.user.exception.UserNotFoundException;
import com.easyshop.user.repository.ShippingAddressRepository;
import com.easyshop.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class UserServiceImpl implements UserService{

    private final UserRepository userRepository;
    private final ShippingAddressRepository addressRepository;

    public UserServiceImpl(UserRepository userRepository, ShippingAddressRepository addressRepository) {
        this.userRepository = userRepository;
        this.addressRepository = addressRepository;
    }

    @Override
    @Transactional
    public UserResponse registerUser(RegisterUserRequest request) {
        if (userRepository.existsByKeycloakId(request.keycloakId())) {
            throw new DuplicateResourceException(
                    "User already registered for keycloakId: " + request.keycloakId());
        }

        User user = User.createNew(
                request.keycloakId(),
                request.email(),
                request.firstName(),
                request.lastName()
        );

        User saved = userRepository.save(user);
        return UserResponse.from(saved);
    }

    @Override
    @Transactional
    public UserResponse getMyProfile(String keycloakId, String email, String firstName, String lastName,
                                      List<String> roles) {
        User user = userRepository.findByKeycloakId(keycloakId)
                .orElseGet(() -> provisionNewUser(keycloakId, email, firstName, lastName));
        List<AddressResponse> addresses = user.getShippingAddresses().stream()
                .map(AddressResponse::from)
                .toList();
        return UserResponse.from(user, roles, addresses);
    }

    /**
     * JIT (just-in-time) provisioning: a validly authenticated JWT for a
     * keycloakId with no local row means "first /me call after a self-service
     * Keycloak registration" - there is no webhook wiring Keycloak's own
     * registration page to POST /register (that endpoint is ADMIN-only and
     * meant for an operator/event-listener flow that was never built). Rather
     * than 404 every self-registered user forever, create the row here from
     * the token's own profile claims.
     */
    private User provisionNewUser(String keycloakId, String email, String firstName, String lastName) {
        User user = User.createNew(
                keycloakId,
                email,
                blankToDefault(firstName, "New"),
                blankToDefault(lastName, "User"));
        return userRepository.save(user);
    }

    private static String blankToDefault(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    @Override
    public UserResponse getUserById(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId.toString()));
        return UserResponse.from(user);
    }

    @Override
    @Transactional
    public UserResponse updateProfile(String keycloakId, UpdateProfileRequest request) {
        User user = userRepository.findByKeycloakId(keycloakId)
                .orElseThrow(() -> new UserNotFoundException(keycloakId));

        user.updateProfile(request.firstName(), request.lastName(), request.phoneNumber());
        // No explicit save() call needed - 'user' is a managed entity inside
        // this @Transactional method, so Hibernate's dirty-checking flushes
        // changes automatically at commit. Calling save() here would be
        // redundant but harmless; omitting it is the idiomatic JPA style.

        return UserResponse.from(user);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AddressResponse> listAddresses(String keycloakId) {
        User user = userRepository.findByKeycloakId(keycloakId)
                .orElseThrow(() -> new UserNotFoundException(keycloakId));
        return user.getShippingAddresses().stream().map(AddressResponse::from).toList();
    }

    @Override
    @Transactional
    public AddressResponse addAddress(String keycloakId, CreateAddressRequest request) {
        User user = userRepository.findByKeycloakId(keycloakId)
                .orElseThrow(() -> new UserNotFoundException(keycloakId));

        ShippingAddress address = ShippingAddress.createNew(
                user, request.label(), request.line1(), request.line2(),
                request.city(), request.stateProvince(), request.postalCode(), request.countryCode());
        // First address for this user becomes the default - the checkout
        // screen always needs a preselected option.
        if (user.getShippingAddresses().isEmpty()) {
            address.markAsDefault();
        }
        ShippingAddress saved = addressRepository.save(address);
        return AddressResponse.from(saved);
    }
}
