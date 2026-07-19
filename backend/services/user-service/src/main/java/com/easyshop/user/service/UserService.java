package com.easyshop.user.service;

import com.easyshop.user.dto.UserDtos.AddressResponse;
import com.easyshop.user.dto.UserDtos.CreateAddressRequest;
import com.easyshop.user.dto.UserDtos.UpdateProfileRequest;
import com.easyshop.user.dto.UserDtos.RegisterUserRequest;
import com.easyshop.user.dto.UserDtos.UserResponse;

import java.util.List;
import java.util.UUID;

public interface UserService {

    UserResponse registerUser(RegisterUserRequest request);

    /**
     * roles come from the caller's own JWT - see UserResponse.from javadoc.
     * email/firstName/lastName (also JWT claims - see UserController.rolesOf's
     * sibling claim readers) are JIT-provisioning material ONLY: used to create
     * the local row on a self-registered user's first call, ignored entirely
     * if the row already exists.
     */
    UserResponse getMyProfile(String keycloakId, String email, String firstName, String lastName,
                              List<String> roles);

    UserResponse getUserById(UUID userId);

    UserResponse updateProfile(String keycloakId, UpdateProfileRequest request);

    List<AddressResponse> listAddresses(String keycloakId);

    AddressResponse addAddress(String keycloakId, CreateAddressRequest request);

}
