package com.rishav.user.service;

import com.rishav.user.dto.LoginRequest;
import com.rishav.user.dto.LoginResponse;
import com.rishav.user.model.User;

import java.util.List;

public interface UserService {

    User createUser(User user);
    User getUserById(Long id);
    List<User> getAllUsers();
    User updateUser(Long id, User user);
    void deleteUser(Long id);
    LoginResponse login(LoginRequest request);
}
