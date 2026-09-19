package com.seatsurge.admin;

import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.seatsurge.common.config.SeatSurgeProperties;
import com.seatsurge.user.Role;
import com.seatsurge.user.User;
import com.seatsurge.user.UserRepository;

import lombok.RequiredArgsConstructor;

/** Nobody can sign up as ADMIN, so the first admin comes from configuration (ADMIN_EMAIL / ADMIN_PASSWORD). */
@Component
@RequiredArgsConstructor
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final SeatSurgeProperties properties;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        SeatSurgeProperties.Admin admin = properties.admin();
        if (admin == null || admin.password() == null || admin.password().isBlank()) {
            return;
        }
        String email = admin.email().trim().toLowerCase(Locale.ROOT);
        if (userRepository.existsByEmail(email)) {
            return;
        }
        userRepository.save(new User(email, passwordEncoder.encode(admin.password()), "Administrator", Role.ADMIN));
        log.info("Bootstrap admin {} created", email);
    }
}
