package com.sitemanager.service;

import com.sitemanager.dto.LoginRequest;
import com.sitemanager.dto.RegisterRequest;
import com.sitemanager.dto.SetupRequest;
import com.sitemanager.exception.AccountDeniedException;
import com.sitemanager.exception.AccountPendingApprovalException;
import com.sitemanager.model.SiteSettings;
import com.sitemanager.model.User;
import com.sitemanager.model.UserGroup;
import com.sitemanager.model.enums.UserRole;
import com.sitemanager.repository.UserGroupRepository;
import com.sitemanager.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final UserGroupRepository userGroupRepository;
    private final PasswordEncoder passwordEncoder;
    private final SiteSettingsService siteSettingsService;
    private final SlackNotificationService slackNotificationService;

    public AuthService(UserRepository userRepository,
                       UserGroupRepository userGroupRepository,
                       PasswordEncoder passwordEncoder,
                       SiteSettingsService siteSettingsService,
                       SlackNotificationService slackNotificationService) {
        this.userRepository = userRepository;
        this.userGroupRepository = userGroupRepository;
        this.passwordEncoder = passwordEncoder;
        this.siteSettingsService = siteSettingsService;
        this.slackNotificationService = slackNotificationService;
    }

    public boolean isSetupRequired() {
        return !userRepository.existsByRole(UserRole.ROOT_ADMIN);
    }

    public User setupRootAdmin(SetupRequest request) {
        if (!isSetupRequired()) {
            throw new IllegalStateException("Root admin already exists");
        }
        User admin = new User(
                request.getUsername(),
                passwordEncoder.encode(request.getPassword()),
                UserRole.ROOT_ADMIN
        );
        return userRepository.save(admin);
    }

    public Optional<User> authenticate(LoginRequest request) {
        Optional<User> match = userRepository.findByUsername(request.getUsername())
                .filter(user -> passwordEncoder.matches(request.getPassword(), user.getPasswordHash()));

        if (match.isPresent()) {
            User user = match.get();
            if (user.getRole() == UserRole.USER && !user.isApproved()) {
                if (user.isDenied()) {
                    throw new AccountDeniedException();
                }
                throw new AccountPendingApprovalException();
            }
        }

        return match;
    }

    public User register(RegisterRequest request) {
        SiteSettings settings = siteSettingsService.getSettings();
        if (!settings.isRegistrationsEnabled()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Registrations are currently disabled");
        }

        if (userRepository.findByUsername(request.getUsername()).isPresent()) {
            throw new IllegalArgumentException("Username already taken");
        }

        UserGroup registeredUserGroup = userGroupRepository.findByName("Registered User")
                .orElseThrow(() -> new IllegalStateException("Default 'Registered User' group not found"));

        boolean requireApproval = siteSettingsService.getSettings().isRequireRegistrationApproval();

        User user = new User(
                request.getUsername(),
                passwordEncoder.encode(request.getPassword()),
                UserRole.USER
        );
        user.setGroup(registeredUserGroup);
        user.setApproved(!requireApproval);

        User saved = userRepository.save(user);

        if (requireApproval) {
            slackNotificationService.sendRegistrationPendingNotification(saved.getUsername());
        }

        return saved;
    }

    /**
     * Change the password for the currently-logged-in user. The caller proves
     * they know the existing password (so a stolen session can't lock out the
     * legitimate owner) and the new password is bcrypt-hashed with the same
     * encoder the login flow uses.
     *
     * @throws ResponseStatusException 401 if {@code currentPassword} doesn't match
     * @throws ResponseStatusException 404 if the username has no row (session
     *         outlived the user being deleted)
     */
    public void changePassword(String username, String currentPassword, String newPassword) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Current password is incorrect");
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "New password must be different from the current password");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
    }

    public User createAdmin(String username, String password) {
        if (userRepository.findByUsername(username).isPresent()) {
            throw new IllegalArgumentException("Username already taken");
        }
        User admin = new User(username, passwordEncoder.encode(password), UserRole.ADMIN);
        return userRepository.save(admin);
    }

    public Optional<User> findByUsername(String username) {
        return userRepository.findByUsername(username);
    }
}
