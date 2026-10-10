package com.example.myai;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, Long> {
    List<KnowledgeDocument> findByUsernameOrderByUpdatedAtDesc(String username);

    Optional<KnowledgeDocument> findByUsernameAndId(String username, Long id);

    void deleteByUsernameAndId(String username, Long id);
}
