package com.devpilot.server.user.controller;

import com.devpilot.server.common.ApiResponse;
import com.devpilot.server.security.DevPilotPrincipal;
import com.devpilot.server.user.dto.CreateUserRequest;
import com.devpilot.server.user.dto.ResetPasswordRequest;
import com.devpilot.server.user.dto.UpdateUserRequest;
import com.devpilot.server.user.dto.UserResponse;
import com.devpilot.server.user.service.UserAdministrationService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class UserAdministrationController {

    private final UserAdministrationService userService;

    @GetMapping
    public ApiResponse<List<UserResponse>> list() { return ApiResponse.success(userService.list()); }

    @PostMapping
    public ApiResponse<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        return ApiResponse.success(userService.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<UserResponse> update(@PathVariable Long id, @Valid @RequestBody UpdateUserRequest request,
                                            @AuthenticationPrincipal DevPilotPrincipal principal) {
        return ApiResponse.success(userService.update(id, request, principal));
    }

    @PutMapping("/{id}/password")
    public ApiResponse<Void> resetPassword(@PathVariable Long id,
                                           @Valid @RequestBody ResetPasswordRequest request) {
        userService.resetPassword(id, request);
        return ApiResponse.success(null);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id,
                                    @AuthenticationPrincipal DevPilotPrincipal principal) {
        userService.delete(id, principal);
        return ApiResponse.success(null);
    }
}
