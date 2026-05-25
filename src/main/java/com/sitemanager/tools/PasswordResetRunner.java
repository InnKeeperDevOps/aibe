package com.sitemanager.tools;

import com.sitemanager.model.User;
import com.sitemanager.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * One-shot password reset utility. Activated only when the application is
 * started with {@code --reset-password=<username>:<new-password>} — otherwise
 * this component is not created and normal startup proceeds.
 *
 * <p>Usage:
 * <pre>
 *   java -jar site-manager.jar --reset-password=Firestar:newpass
 *
 *   # or in a running container:
 *   kubectl exec &lt;pod&gt; -- java -jar /app/site-manager.jar \
 *       --reset-password=Firestar:newpass --server.port=0
 *   # (--server.port=0 picks an ephemeral port so this short-lived JVM
 *   #  doesn't clash with the main one on 8080)
 * </pre>
 *
 * <p>The runner re-hashes the new password with the same BCryptPasswordEncoder
 * the rest of the app uses, writes it to {@code app_users.password_hash} for
 * the named user, prints success, and then triggers a clean JVM exit so the
 * process doesn't keep running as a web server.
 *
 * <p>Splits on the FIRST {@code :} only, so passwords may contain colons.
 */
@Component
@ConditionalOnProperty(name = "reset-password")
public class PasswordResetRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetRunner.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final ConfigurableApplicationContext context;

    @Value("${reset-password}")
    private String resetSpec;

    public PasswordResetRunner(UserRepository userRepository,
                               PasswordEncoder passwordEncoder,
                               ConfigurableApplicationContext context) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        int exitCode = doReset();
        // Schedule an exit on a separate thread so Spring's startup phase
        // can complete cleanly before the JVM shuts down.
        new Thread(() -> {
            int code = exitCode;
            try {
                context.close();
            } catch (Exception e) {
                log.warn("Error while closing application context: {}", e.getMessage());
            } finally {
                System.exit(code);
            }
        }, "password-reset-exit").start();
    }

    private int doReset() {
        if (resetSpec == null || resetSpec.isBlank()) {
            log.error("--reset-password requires a value of the form <username>:<new-password>");
            return 2;
        }
        int colon = resetSpec.indexOf(':');
        if (colon <= 0 || colon == resetSpec.length() - 1) {
            log.error("--reset-password value must be <username>:<new-password> "
                    + "(both sides non-empty, separated by the first colon)");
            return 2;
        }
        String username = resetSpec.substring(0, colon);
        String newPassword = resetSpec.substring(colon + 1);

        Optional<User> match = userRepository.findByUsername(username);
        if (match.isEmpty()) {
            log.error("No user found with username '{}' — nothing to reset", username);
            return 3;
        }
        User user = match.get();
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        log.info("Password reset for user '{}' (role={}). The old password no longer works.",
                username, user.getRole());
        return 0;
    }
}
