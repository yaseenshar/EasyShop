package com.easyshop.user.service;

import com.easyshop.user.dto.UserDtos.UpdateProfileRequest;
import com.easyshop.user.dto.UserDtos.RegisterUserRequest;
import com.easyshop.user.dto.UserDtos.UserResponse;

public interface UserService {

    UserResponse registerUser(RegisterUserRequest request);

    UserResponse getUserByKeycloakId(String keycloakId);
    
    UserResponse updateProfile(String keycloakId, UpdateProfileRequest request);

}
