package com.devpilot.server.auth.service;

import com.devpilot.server.auth.entity.UserEntity;
import com.devpilot.server.auth.mapper.UserMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LoginAttemptService {

    private static final int FAILURE_THRESHOLD = 5;
    private static final int LOCK_MINUTES = 15;
    private final UserMapper userMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(String username) {
        UserEntity user = userMapper.selectActiveByUsernameForUpdate(username);
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(now)) {
            return;
        }
        int failures = user.getLockedUntil() == null ? user.getFailedLoginCount() + 1 : 1;
        user.setFailedLoginCount(failures);
        user.setLockedUntil(failures >= FAILURE_THRESHOLD ? now.plusMinutes(LOCK_MINUTES) : null);
        user.setUpdatedAt(now);
        userMapper.updateById(user);
    }
}
