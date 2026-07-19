package com.easyshop.user.exception;

import com.easyshop.common.exception.ResourceNotFoundException;

public class UserNotFoundException extends ResourceNotFoundException {

    public UserNotFoundException(String identifier) {
        super("User not found: " + identifier);
    }
}
