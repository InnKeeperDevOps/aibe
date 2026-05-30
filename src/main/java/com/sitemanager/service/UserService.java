package com.sitemanager.service;

import com.sitemanager.model.User;
import com.sitemanager.model.UserGroup;
import com.sitemanager.repository.UserGroupRepository;
import com.sitemanager.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final UserGroupRepository userGroupRepository;
    private final SlackNotificationService slackNotificationService;

    public UserService(UserRepository userRepository, UserGroupRepository userGroupRepository,
                       SlackNotificationService slackNotificationService) {
        this.userRepository = userRepository;
        this.userGroupRepository = userGroupRepository;
        this.slackNotificationService = slackNotificationService;
    }

    public List<User> findAll() {
        return userRepository.findAll();
    }

    public List<User> findPending() {
        return userRepository.findByApprovedFalseAndDeniedFalse();
    }

    public User approve(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + id));
        user.setApproved(true);
        user.setDenied(false);
        User saved = userRepository.save(user);
        slackNotificationService.sendUserApprovedNotification(user.getUsername());
        return saved;
    }

    public User deny(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + id));
        user.setApproved(false);
        user.setDenied(true);
        User saved = userRepository.save(user);
        slackNotificationService.sendUserDeniedNotification(user.getUsername());
        return saved;
    }

    public User assignGroup(Long userId, Long groupId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        UserGroup group = userGroupRepository.findById(groupId)
                .orElseThrow(() -> new IllegalArgumentException("Group not found: " + groupId));
        user.setGroup(group);
        return userRepository.save(user);
    }

    public Optional<User> findById(Long id) {
        return userRepository.findById(id);
    }
}
