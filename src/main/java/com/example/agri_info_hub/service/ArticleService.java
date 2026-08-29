package com.example.agri_info_hub.service;

import com.example.agri_info_hub.entity.AgricultureArticle;
import com.example.agri_info_hub.repository.AgricultureArticleRepository;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class ArticleService {

    private static final int PAGE_SIZE = 10;

    private final AgricultureArticleRepository articleRepository;

    public ArticleService(AgricultureArticleRepository articleRepository) {
        this.articleRepository = articleRepository;
    }

    public SearchResult search(String keyword, String category, int requestedPage) {
        String normalizedKeyword = keyword == null
                ? ""
                : keyword.replace('　', ' ')
                        .replace('、', ' ')
                        .strip()
                        .toLowerCase(Locale.JAPANESE);
        List<String> keywords = normalizedKeyword.isBlank()
                ? List.of()
                : List.of(normalizedKeyword.split("\\s+"));
        String normalizedCategory = category == null ? "" : category.strip();

        List<AgricultureArticle> filteredArticles =
                articleRepository.findTop100ByOrderByPublishedAtDesc().stream()
                .filter(article -> normalizedCategory.isBlank()
                        || normalizedCategory.equals(article.getCategory()))
                .filter(article -> keywords.isEmpty()
                        || keywords.stream().anyMatch(searchWord ->
                                contains(article.getTitle(), searchWord)
                                || contains(article.getSummary(), searchWord)
                                || contains(article.getSourceName(), searchWord)
                                || contains(article.getRegion(), searchWord)))
                .toList();

        if (filteredArticles.isEmpty()) {
            return new SearchResult(List.of(), 1, 0, 0);
        }

        int totalItems = filteredArticles.size();
        int totalPages = (totalItems + PAGE_SIZE - 1) / PAGE_SIZE;
        int currentPage = Math.max(1, Math.min(requestedPage, totalPages));
        int fromIndex = (currentPage - 1) * PAGE_SIZE;
        int toIndex = Math.min(fromIndex + PAGE_SIZE, totalItems);

        return new SearchResult(
                filteredArticles.subList(fromIndex, toIndex),
                currentPage,
                totalPages,
                totalItems);
    }

    private boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase(Locale.JAPANESE).contains(keyword);
    }

    public record SearchResult(
            List<AgricultureArticle> articles,
            int currentPage,
            int totalPages,
            int totalItems) {
    }
}
