package com.example.myai;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserChatHistoryRepository extends JpaRepository<UserChatHistory, Long> {
    Optional<UserChatHistory> findByUsername(String username);
}
