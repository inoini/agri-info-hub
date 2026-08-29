package com.example.agriinfohub.repository;

import com.example.agriinfohub.entity.AgricultureArticle;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgricultureArticleRepository extends JpaRepository<AgricultureArticle, Long> {

    boolean existsByUrlHash(String urlHash);

    List<AgricultureArticle> findTop100ByOrderByPublishedAtDesc();
}
