package com.example.agriinfohub.service;

import com.example.agriinfohub.entity.AgricultureArticle;
import com.example.agriinfohub.repository.AgricultureArticleRepository;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class ArticleService {

    private final AgricultureArticleRepository articleRepository;

    public ArticleService(AgricultureArticleRepository articleRepository) {
        this.articleRepository = articleRepository;
    }

    public List<AgricultureArticle> search(String keyword, String category) {
        String normalizedKeyword = keyword == null
                ? ""
                : keyword.strip().toLowerCase(Locale.JAPANESE);
        String normalizedCategory = category == null ? "" : category.strip();

        return articleRepository.findTop100ByOrderByPublishedAtDesc().stream()
                .filter(article -> normalizedCategory.isBlank()
                        || normalizedCategory.equals(article.getCategory()))
                .filter(article -> normalizedKeyword.isBlank()
                        || contains(article.getTitle(), normalizedKeyword)
                        || contains(article.getSummary(), normalizedKeyword)
                        || contains(article.getSourceName(), normalizedKeyword)
                        || contains(article.getRegion(), normalizedKeyword))
                .toList();
    }

    private boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase(Locale.JAPANESE).contains(keyword);
    }
}
