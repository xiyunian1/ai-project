package com.example.myai;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserChatSessionRepository extends JpaRepository<UserChatSession, Long> {
    Page<UserChatSession> findByUsernameOrderByUpdatedAtEpochMsDesc(String username, Pageable pageable);

    Optional<UserChatSession> findByUsernameAndSessionId(String username, String sessionId);

    void deleteByUsernameAndSessionId(String username, String sessionId);
}
