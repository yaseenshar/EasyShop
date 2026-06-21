package com.easyshop.user.service;

import com.easyshop.common.exception.DuplicateResourceException;
import com.easyshop.user.dto.UserDtos.UserResponse;
import com.easyshop.user.dto.UserDtos.RegisterUserRequest;
import com.easyshop.user.dto.UserDtos.UpdateProfileRequest;
import com.easyshop.user.entity.User;
import com.easyshop.user.exception.UserNotFoundException;
import com.easyshop.user.repository.UserRepository;
import org.springframework.stereotype.Service;

@Service
public class UserServiceImpl implements UserService{

    private final UserRepository userRepository;

    public UserServiceImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
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
    public UserResponse getUserByKeycloakId(String keycloakId) {
        User user = userRepository.findByKeycloakId(keycloakId)
                .orElseThrow(() -> new UserNotFoundException(keycloakId));
        return UserResponse.from(user);
    }

    @Override
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
}
