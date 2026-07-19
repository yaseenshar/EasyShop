package com.easyshop.user.service;

import com.easyshop.user.dto.UserDtos.UpdateProfileRequest;
import com.easyshop.user.dto.UserDtos.RegisterUserRequest;
import com.easyshop.user.dto.UserDtos.UserResponse;

import java.util.UUID;

public interface UserService {

    UserResponse registerUser(RegisterUserRequest request);

    UserResponse getUserByKeycloakId(String keycloakId);

    UserResponse getUserById(UUID userId);

    UserResponse updateProfile(String keycloakId, UpdateProfileRequest request);

}
