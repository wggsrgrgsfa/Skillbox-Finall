package searchengine.services;

import org.springframework.stereotype.Service;
import searchengine.dto.search.SearchResponse;
import searchengine.dto.search.SearchResult;
import searchengine.repository.PageRepository;
import searchengine.repository.LemmaRepository;
import searchengine.repository.IndexRepository;
import searchengine.utils.LemmaProcessor;
import searchengine.model.Page;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import searchengine.config.ConfigSite ;
import searchengine.config.SitesList ;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class SearchServiceImpl implements SearchService {

    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;
    private final LemmaProcessor lemmaProcessor;
    private final SitesList sitesList;

    public SearchServiceImpl(PageRepository pageRepository, LemmaRepository lemmaRepository,
                             IndexRepository indexRepository, LemmaProcessor lemmaProcessor, SitesList sitesList) {
        this.pageRepository = pageRepository;
        this.lemmaRepository = lemmaRepository;
        this.indexRepository = indexRepository;
        this.lemmaProcessor = lemmaProcessor;
        this.sitesList = sitesList;
    }

    @Override
    public SearchResponse search(String query, String site, int offset, int limit) {
        if (query == null || query.trim().isEmpty()) {
            return new SearchResponse("Задан пустой поисковый запрос");
        }

        List<String> lemmas = lemmaProcessor.extractLemmas(query);
        if (lemmas.isEmpty()) {
            return new SearchResponse("Не удалось обработать запрос");
        }

        List<String> allowedSites = sitesList.getSites().stream()
                .map(ConfigSite::getUrl)
                .collect(Collectors.toList());

        List<Page> pages = pageRepository.findPagesByLemmas(lemmas);
        List<Page> pagesWithMatches = pages.stream()
                .filter(page -> hasLemmasMatches(page, lemmas) && isSiteAllowed(page.getSite().getUrl(), allowedSites))
                .collect(Collectors.toList());

        if (site != null && !site.isEmpty()) {
            pagesWithMatches = pagesWithMatches.stream()
                    .filter(page -> page.getSite().getUrl().equalsIgnoreCase(site))
                    .collect(Collectors.toList());
        }

        int totalCount = pagesWithMatches.size();

        List<SearchResult> results = pagesWithMatches.stream()
                .map(page -> new SearchResult(
                        safeString(page.getSite().getUrl()),
                        safeString(page.getSite().getName()),
                        safeString(page.getPath()),
                        buildTitleBlock(page),
                        generateSnippet(page.getContent(), lemmas),
                        calculateRelevance(page, lemmas)
                ))
                .sorted(Comparator.comparingDouble(SearchResult::getRelevance).reversed())
                .skip(offset)
                .limit(limit)
                .collect(Collectors.toList());

        return new SearchResponse(true, totalCount, results);
    }

    private boolean isSiteAllowed(String pageUrl, List<String> allowedSites) {
        try {
            URI pageUri = new URI(pageUrl);
            String pageHost = pageUri.getHost();

            if (pageHost == null) {
                pageUrl = resolveFullUrl("https://default-site.com", pageUrl);
                pageUri = new URI(pageUrl);
                pageHost = pageUri.getHost();
            }

            if (pageHost == null) return false;

            for (String allowed : allowedSites) {
                URI allowedUri = new URI(allowed);
                String allowedHost = allowedUri.getHost();
                if (allowedHost != null && allowedHost.equalsIgnoreCase(pageHost)) {
                    return true;
                }
            }
        } catch (URISyntaxException e) {
            System.err.println("Ошибка разбора URL: " + pageUrl);
        }
        return false;
    }

    private boolean hasLemmasMatches(Page page, List<String> lemmas) {
        String content = page.getContent().toLowerCase();
        for (String lemma : lemmas) {
            String pattern = "\\b" + Pattern.quote(lemma.toLowerCase()) + "\\b"; // Ограничиваем поиск границами слов
            if (!content.matches(".*" + pattern + ".*")) {
                return false;
            }
        }
        return true;
    }

    private String generateSnippet(String content, List<String> lemmas) {
        int snippetLength = 200;
        String lowerContent = content.toLowerCase();

        List<Integer> lemmaPositions = new ArrayList<>();
        for (String lemma : lemmas) {
            String pattern = "\\b" + Pattern.quote(lemma.toLowerCase()) + "\\b"; // Используем границы слова
            Matcher matcher = Pattern.compile(pattern).matcher(lowerContent);
            while (matcher.find()) {
                lemmaPositions.add(matcher.start());
            }
        }

        if (lemmaPositions.isEmpty()) {
            return "...Совпадений не найдено...";
        }

        int start = Math.max(lemmaPositions.get(0) - 50, 0);
        int end = Math.min(start + snippetLength, content.length());

        String snippet = content.substring(start, end);

        for (String lemma : lemmas) {
            String pattern = "\\b" + Pattern.quote(lemma) + "\\b"; // Экранируем лемму и ищем полные слова
            snippet = snippet.replaceAll("(?i)" + pattern, "<b>$0</b>");
        }

        return "..." + snippet + "...";
    }

    private double calculateRelevance(Page page, List<String> lemmas) {
        String content = page.getContent().toLowerCase();
        double relevance = 0.0;

        for (String lemma : lemmas) {
            String lowerLemma = lemma.toLowerCase();
            relevance += content.split(Pattern.quote(lowerLemma), -1).length - 1;
        }

        return relevance;
    }

    private String safeString(String value) {
        return value != null ? value.trim() : "";
    }

    private String buildTitleBlock(Page page) {
        String title = safeString(page.getTitle());
        String path = safeString(page.getPath());

        if (!title.isEmpty() && !path.isEmpty()) {
            return title + " - " + path;
        } else if (!title.isEmpty()) {
            return title;
        } else {
            return path;
        }
    }

    private String resolveFullUrl(String baseUrl, String relativeUrl) {
        try {
            URI baseUri = new URI(baseUrl);
            URI resolvedUri = new URI(baseUri.getScheme(), baseUri.getAuthority(), relativeUrl, null, null);
            return resolvedUri.toString();
        } catch (URISyntaxException e) {
            System.err.println("Ошибка при разборе URL: " + relativeUrl);
            return relativeUrl;
        }
    }
}
