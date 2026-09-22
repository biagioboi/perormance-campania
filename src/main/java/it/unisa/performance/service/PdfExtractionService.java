package it.unisa.performance.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import it.unisa.performance.domain.StrategicLineType;
import it.unisa.performance.dto.ExtractedStrategicLineResponse;
import it.unisa.performance.dto.ExtractedStructureResponse;
import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.multipart.MultipartFile;

@Service
public class PdfExtractionService {

  private static final int MAX_TEXT_CHARS_PER_CHUNK = 4500;
  private static final int STRATEGIC_LINE_BATCH_CHAR_BUDGET = 60000;

  private final RestClient restClient;
  private final OllamaOrganigramService ollamaOrganigramService;
  private final OllamaStrategicLineService ollamaStrategicLineService;
  private final String pdfExtractorBaseUrl;

  public PdfExtractionService(
      RestClient.Builder restClientBuilder,
      OllamaOrganigramService ollamaOrganigramService,
      OllamaStrategicLineService ollamaStrategicLineService,
      @Value("${pdfextractor.base-url}") String pdfExtractorBaseUrl) {
    this.restClient = restClientBuilder.build();
    this.ollamaOrganigramService = ollamaOrganigramService;
    this.ollamaStrategicLineService = ollamaStrategicLineService;
    this.pdfExtractorBaseUrl = pdfExtractorBaseUrl;
  }

  public List<ExtractedStrategicLineResponse> extractStrategicLines(MultipartFile file) throws IOException {
    var extracted = new ArrayList<ExtractedStrategicLineResponse>();
    processStrategicLinesByPage(file, result -> extracted.addAll(result.items()));
    return extracted;
  }

  public void processStrategicLinesByPage(
      MultipartFile file,
      StrategicLinePageConsumer pageConsumer) throws IOException {
    var pages = extractPdfPages(file);
    if (pages.isEmpty()) {
      throw new IllegalStateException("Impossibile leggere il PDF: nessuna pagina disponibile per l'estrazione.");
    }
    var batches = batchPagesByCharBudget(pages);
    var batchTexts = batches.stream().map(this::buildBatchText).toList();

    // Fase 1: trova solo i titoli delle macro-categorie in ogni batch, con la loro posizione letterale nel testo.
    Map<String, String> categoryLiteralByNormalized = new LinkedHashMap<>();
    var occurrencesByBatch = new LinkedHashMap<Integer, List<CategoryOccurrence>>();
    for (int i = 0; i < batchTexts.size(); i++) {
      var batchText = batchTexts.get(i);
      if (!isTextSufficient(batchText)) {
        continue;
      }
      List<String> found;
      try {
        found = ollamaStrategicLineService.extractCategories(batchText);
      } catch (Exception exception) {
        found = List.of();
      }
      for (var title : found) {
        var key = normalizeTitleKey(title);
        if (key.isBlank()) {
          continue;
        }
        var startIndex = findApproximateIndex(batchText, title);
        if (startIndex < 0) {
          continue;
        }
        categoryLiteralByNormalized.putIfAbsent(key, title);
        occurrencesByBatch.computeIfAbsent(i, ignored -> new ArrayList<>())
            .add(new CategoryOccurrence(key, startIndex));
      }
    }

    if (categoryLiteralByNormalized.isEmpty()) {
      throw new IllegalStateException("Ollama non ha trovato linee strategiche leggibili nel PDF.");
    }

    for (var occurrences : occurrencesByBatch.values()) {
      occurrences.sort(Comparator.comparingInt(CategoryOccurrence::startIndex));
    }
    var scopeTextByNormalized = buildCategoryScopeSlices(batchTexts, occurrencesByBatch);

    int totalSteps = 1 + categoryLiteralByNormalized.size();
    int step = 1;

    Map<String, String> categoryKeyByNormalized = new LinkedHashMap<>();
    var categoryItems = new ArrayList<ExtractedStrategicLineResponse>();
    int categoryIndex = 1;
    for (var entry : categoryLiteralByNormalized.entrySet()) {
      var categoryKey = "CAT-" + categoryIndex++;
      categoryKeyByNormalized.put(entry.getKey(), categoryKey);
      var literal = entry.getValue();
      categoryItems.add(new ExtractedStrategicLineResponse(
          categoryKey,
          null,
          compact(literal, 240),
          StrategicLineType.macro_categoria.name(),
          inferArea(literal),
          sanitizePriority(""),
          literal));
    }

    int totalItems = categoryItems.size();
    pageConsumer.accept(new StrategicLinePageResult(step, totalSteps, categoryItems));

    // Fase 2: per ogni macro-categoria, cerca i suoi obiettivi specifici SOLO nel testo tra la sua intestazione e la prossima.
    var seenObjectiveKeys = new HashSet<String>();
    for (var entry : categoryLiteralByNormalized.entrySet()) {
      step++;
      var categoryTitle = entry.getValue();
      var categoryKey = categoryKeyByNormalized.get(entry.getKey());
      var scopeText = scopeTextByNormalized.getOrDefault(entry.getKey(), "");

      var objectiveItems = new ArrayList<ExtractedStrategicLineResponse>();
      int objectiveIndex = 0;
      if (!scopeText.isBlank()) {
        // Bullet markers ("-", normalized upstream from PDF bullet glyphs) are a reliable
        // structural signal: split on them deterministically first, with zero LLM involvement,
        // so a small model's non-determinism on long lists (splitting/dropping the last item,
        // observed even at temperature=0) can never lose or corrupt a real bullet point. Fall
        // back to the LLM only for prose-style sections with no bullet markers to split on.
        var bulletedObjectives = splitBulletedObjectives(scopeText);
        List<String> found;
        if (!bulletedObjectives.isEmpty()) {
          found = bulletedObjectives;
        } else {
          try {
            found = ollamaStrategicLineService.extractObjectivesForCategory(scopeText, categoryTitle).stream()
                .map(rawObjectiveText -> recoverWordBoundaries(scopeText, rawObjectiveText))
                .toList();
          } catch (Exception exception) {
            found = List.of();
          }
        }
        for (var objectiveText : found) {
          var objectiveKey = normalizeTitleKey(objectiveText);
          if (!seenObjectiveKeys.add(objectiveKey)) {
            continue;
          }
          objectiveIndex++;
          objectiveItems.add(new ExtractedStrategicLineResponse(
              categoryKey + "-OBJ-" + objectiveIndex,
              categoryKey,
              compact(objectiveText, 240),
              StrategicLineType.obiettivo_strategico.name(),
              inferArea(objectiveText),
              sanitizePriority(""),
              objectiveText));
        }
      }

      totalItems += objectiveItems.size();
      pageConsumer.accept(new StrategicLinePageResult(step, totalSteps, objectiveItems));
    }

    if (totalItems == 0) {
      throw new IllegalStateException("Ollama non ha trovato linee strategiche leggibili nel PDF.");
    }
  }

  private List<List<PdfPage>> batchPagesByCharBudget(List<PdfPage> pages) {
    var batches = new ArrayList<List<PdfPage>>();
    var current = new ArrayList<PdfPage>();
    int currentChars = 0;

    for (var page : pages) {
      var pageText = compact(defaultIfBlank(page.text(), ""), MAX_TEXT_CHARS_PER_CHUNK);
      if (!current.isEmpty() && currentChars + pageText.length() > STRATEGIC_LINE_BATCH_CHAR_BUDGET) {
        batches.add(current);
        current = new ArrayList<>();
        currentChars = 0;
      }
      current.add(page);
      currentChars += pageText.length();
    }
    if (!current.isEmpty()) {
      batches.add(current);
    }
    return batches;
  }

  private Map<String, String> buildCategoryScopeSlices(
      List<String> batchTexts,
      Map<Integer, List<CategoryOccurrence>> occurrencesByBatch) {
    var scopes = new LinkedHashMap<String, String>();
    for (var batchEntry : occurrencesByBatch.entrySet()) {
      int batchIndex = batchEntry.getKey();
      var occurrences = batchEntry.getValue();
      var batchText = batchTexts.get(batchIndex);

      for (int i = 0; i < occurrences.size(); i++) {
        var occurrence = occurrences.get(i);
        if (scopes.containsKey(occurrence.normalizedKey())) {
          continue;
        }
        int end = i + 1 < occurrences.size() ? occurrences.get(i + 1).startIndex() : batchText.length();
        var slice = new StringBuilder(batchText.substring(occurrence.startIndex(), end));

        if (i + 1 >= occurrences.size() && batchIndex + 1 < batchTexts.size()) {
          var nextBatchText = batchTexts.get(batchIndex + 1);
          var nextOccurrences = occurrencesByBatch.get(batchIndex + 1);
          int nextEnd = nextOccurrences != null && !nextOccurrences.isEmpty()
              ? nextOccurrences.get(0).startIndex()
              : nextBatchText.length();
          slice.append('\n').append(nextBatchText, 0, nextEnd);
        }

        scopes.put(occurrence.normalizedKey(), slice.toString());
      }
    }
    return scopes;
  }

  /**
   * Splits a category's scope text into one string per bullet point ("-" at
   * start of a line, already normalized upstream from PDF bullet glyphs),
   * joining wrapped lines within each bullet. Returns an empty list if the
   * scope has no bullet markers (prose-style section), signalling the caller
   * to fall back to LLM-based extraction instead.
   */
  private List<String> splitBulletedObjectives(String scopeText) {
    var titleLineEnd = scopeText.indexOf('\n');
    if (titleLineEnd < 0) {
      return List.of();
    }
    var body = scopeText.substring(titleLineEnd + 1);
    var bulletMarker = java.util.regex.Pattern.compile("(?m)^[ \\t]*-[ \\t]+");
    var matcher = bulletMarker.matcher(body);
    var markerStarts = new ArrayList<Integer>();
    var contentStarts = new ArrayList<Integer>();
    while (matcher.find()) {
      markerStarts.add(matcher.start());
      contentStarts.add(matcher.end());
    }
    if (markerStarts.isEmpty()) {
      return List.of();
    }
    var items = new ArrayList<String>();
    for (int i = 0; i < contentStarts.size(); i++) {
      int start = contentStarts.get(i);
      int end = i + 1 < markerStarts.size() ? markerStarts.get(i + 1) : body.length();
      var joined = body.substring(start, end).replaceAll("\\s+", " ").trim();
      if (!joined.isBlank()) {
        items.add(joined);
      }
    }
    return items;
  }

  private record CategoryOccurrence(String normalizedKey, int startIndex) {}

  private String buildBatchText(List<PdfPage> batch) {
    var sb = new StringBuilder();
    for (var page : batch) {
      // Keeps real line breaks (unlike compact()) so splitBulletedObjectives() can still find
      // "-" bullet markers at line start after category scope-slicing; only caps length.
      var pageText = capLength(defaultIfBlank(page.text(), ""), MAX_TEXT_CHARS_PER_CHUNK);
      if (pageText.isBlank()) {
        continue;
      }
      sb.append("--- Pagina ").append(page.pageNumber()).append(" ---\n")
          .append(pageText)
          .append("\n\n");
    }
    return sb.toString();
  }

  private String normalizeTitleKey(String title) {
    return stripAccents(defaultIfBlank(title, ""))
        .toLowerCase(Locale.ROOT)
        .replaceAll("\\s+", " ")
        .trim();
  }

  public List<ExtractedStructureResponse> extractOrganigramStructures(MultipartFile file) throws IOException {
    var extracted = new ArrayList<ExtractedStructureResponse>();
    processOrganigramStructuresByPage(file, result -> {
      if (result.imported()) {
        extracted.addAll(result.structures());
      }
    });
    return mergeStructures(extracted);
  }

  public OrganigramPageProcessSummary processOrganigramStructuresByPage(
      MultipartFile file,
      OrganigramPageConsumer pageConsumer) throws IOException {
    var pages = extractPdfPages(file);
    int pageCount = pages.size();
    if (pageCount == 0) {
      throw new IllegalStateException("Impossibile leggere il PDF: nessuna pagina disponibile per l'estrazione.");
    }

    int pagesImported = 0;
    int pagesFailed = 0;
    for (var page : pages) {
      var pageNumber = page.pageNumber();
      var pageText = compact(defaultIfBlank(page.text(), ""), MAX_TEXT_CHARS_PER_CHUNK);
      var pageImage = page.imageBase64();
      try {
        var extracted = extractStructuresFromSinglePage(pageText, pageImage, pageNumber);
        if (extracted.isEmpty()) {
          pagesFailed++;
          pageConsumer.accept(new OrganigramPageResult(pageNumber, pageCount, false, List.of()));
          continue;
        }
        pageConsumer.accept(new OrganigramPageResult(pageNumber, pageCount, true, extracted));
        pagesImported++;
      } catch (Exception pageException) {
        pagesFailed++;
        pageConsumer.accept(new OrganigramPageResult(pageNumber, pageCount, false, List.of()));
      }
    }

    if (pagesImported == 0) {
      throw new IllegalStateException("Ollama non ha restituito strutture organizzative leggibili da nessuna pagina del PDF.");
    }
    return new OrganigramPageProcessSummary(pageCount, pagesImported, pagesFailed);
  }

  private List<PdfPage> extractPdfPages(MultipartFile file) throws IOException {
    var body = new LinkedMultiValueMap<String, Object>();
    body.add("file", multipartResource(file));

    PdfExtractionResponse response;
    try {
      response = restClient.post()
          .uri(pdfExtractorBaseUrl + "/extract-pages")
          .contentType(MediaType.MULTIPART_FORM_DATA)
          .body(body)
          .retrieve()
          .body(PdfExtractionResponse.class);
    } catch (RestClientException exception) {
      throw new IllegalStateException("Impossibile contattare il servizio di estrazione PDF", exception);
    }

    if (response == null || response.pages() == null) {
      return List.of();
    }
    return response.pages();
  }

  public List<TextPage> extractSortedTextPages(MultipartFile file) throws IOException {
    var body = new LinkedMultiValueMap<String, Object>();
    body.add("file", multipartResource(file));

    TextExtractionResponse response;
    try {
      response = restClient.post()
          .uri(pdfExtractorBaseUrl + "/extract-pages-text")
          .contentType(MediaType.MULTIPART_FORM_DATA)
          .body(body)
          .retrieve()
          .body(TextExtractionResponse.class);
    } catch (RestClientException exception) {
      throw new IllegalStateException("Impossibile contattare il servizio di estrazione PDF", exception);
    }

    if (response == null || response.pages() == null) {
      return List.of();
    }
    return response.pages();
  }

  private String compact(String value, int maxLength) {
    if (value == null) {
      return "";
    }
    var normalized = value.trim().replaceAll("\\s+", " ");
    if (normalized.length() <= maxLength) {
      return normalized;
    }
    return normalized.substring(0, maxLength - 1).trim() + "…";
  }

  private String capLength(String value, int maxLength) {
    var trimmed = value == null ? "" : value.trim();
    if (trimmed.length() <= maxLength) {
      return trimmed;
    }
    return trimmed.substring(0, maxLength - 1).trim() + "…";
  }

  private String defaultIfBlank(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private String sanitizePriority(String value) {
    return switch (value == null ? "" : value.trim().toLowerCase(Locale.ROOT)) {
      case "bassa", "low" -> "bassa";
      case "media", "medium" -> "media";
      default -> "alta";
    };
  }

  private List<ExtractedStructureResponse> extractStructuresFromSinglePage(
      String pageText,
      String pageImageBase64,
      int pageNumber) {
    var prompt = "Pagina " + pageNumber + " dell'organigramma";
    var normalizedText = compact(defaultIfBlank(pageText, ""), MAX_TEXT_CHARS_PER_CHUNK);
    if (!normalizedText.isBlank()) {
      prompt += "\n\n" + normalizedText;
    }

    var images = pageImageBase64 == null || pageImageBase64.isBlank()
        ? List.<String>of()
        : List.of(pageImageBase64);
    if (!isTextSufficient(normalizedText) && images.isEmpty()) {
      return List.of();
    }
    return mergeStructures(ollamaOrganigramService.extractStructures(prompt, images));
  }

  private List<ExtractedStructureResponse> mergeStructures(List<ExtractedStructureResponse> structures) {
    Map<String, ExtractedStructureResponse> ordered = new LinkedHashMap<>();
    Map<String, String> keyAliases = new LinkedHashMap<>();
    int fallbackIndex = 1;

    for (var structure : structures) {
      if (structure == null || defaultIfBlank(structure.name(), "").isBlank()) {
        continue;
      }
      var externalKey = defaultIfBlank(structure.externalKey(), "ORG-" + fallbackIndex++);
      var normalized = new ExtractedStructureResponse(
          externalKey,
          structure.parentExternalKey(),
          compact(defaultIfBlank(structure.code(), ""), 32),
          compact(structure.name(), 280),
          defaultIfBlank(structure.type(), "struttura"),
          defaultIfBlank(structure.area(), ""));
      var identity = structureIdentity(normalized);
      ordered.putIfAbsent(identity, normalized);
      keyAliases.put(externalKey, ordered.get(identity).externalKey());
    }

    if (ordered.isEmpty()) {
      throw new IllegalStateException("Ollama non ha restituito strutture organizzative leggibili dal PDF.");
    }

    var merged = new ArrayList<ExtractedStructureResponse>();
    for (var structure : ordered.values()) {
      var parentExternalKey = defaultIfBlank(structure.parentExternalKey(), "");
      if (!parentExternalKey.isBlank()) {
        parentExternalKey = keyAliases.getOrDefault(parentExternalKey, parentExternalKey);
      }
      merged.add(new ExtractedStructureResponse(
          structure.externalKey(),
          parentExternalKey.isBlank() ? null : parentExternalKey,
          structure.code(),
          structure.name(),
          structure.type(),
          structure.area()));
    }
    return merged;
  }

  private String structureIdentity(ExtractedStructureResponse structure) {
    return stripAccents(defaultIfBlank(structure.code(), "") + "|" + defaultIfBlank(structure.name(), ""))
        .toLowerCase(Locale.ROOT)
        .replaceAll("\\s+", " ")
        .trim();
  }

  private String inferArea(String text) {
    var value = stripAccents(defaultIfBlank(text, "").toLowerCase(Locale.ROOT));
    if (containsAny(value, "digital", "semplificazione", "procediment", "servizi online", "piattaforma")) {
      return "Digitalizzazione";
    }
    if (containsAny(value, "sanita", "salute", "screening", "liste d attesa", "asl", "ospedal")) {
      return "Sanita";
    }
    if (containsAny(value, "ambiente", "rifiuti", "ecologic", "bonifica", "suolo", "energia")) {
      return "Ambiente";
    }
    if (containsAny(value, "lavoro", "formazione", "competenze", "occupazione", "cpi")) {
      return "Lavoro e Formazione";
    }
    if (containsAny(value, "cultura", "turismo", "muse", "patrimonio", "visitatori")) {
      return "Cultura e Turismo";
    }
    if (containsAny(value, "social", "anziani", "disabilita", "fragilita", "assistenza")) {
      return "Politiche Sociali";
    }
    if (containsAny(value, "mobilita", "trasporto", "tpl", "ciclab", "ferro")) {
      return "Mobilita";
    }
    if (containsAny(value, "sviluppo economico", "impres", "pmi", "contributi", "innovazione")) {
      return "Sviluppo Economico";
    }
    return "Trasversale";
  }

  private boolean isTextSufficient(String text) {
    if (text == null) {
      return false;
    }
    var normalized = text.replaceAll("\\s+", " ").trim();
    if (normalized.length() < 500) {
      return false;
    }
    long letters = normalized.chars().filter(Character::isLetter).count();
    return letters >= 250;
  }

  private boolean containsAny(String value, String... needles) {
    for (var needle : needles) {
      if (value.contains(needle)) {
        return true;
      }
    }
    return false;
  }

  private String stripAccents(String value) {
    return Normalizer.normalize(value, Normalizer.Form.NFD)
        .replaceAll("\\p{M}+", "")
        .replace('’', '\'')
        .replace('`', '\'');
  }

  /**
   * Finds a title inside raw PDF text tolerating accent/apostrophe/whitespace
   * differences introduced by the LLM (e.g. "LEGALITA' E WELFARE" as embedded
   * in the PDF vs "LEGALITA E WELFARE" as returned verbatim-but-normalized by
   * Ollama). Exact indexOf on these would silently drop the whole category.
   * Returns the offset in the ORIGINAL (unnormalized) text, or -1.
   */
  private int findApproximateIndex(String haystack, String needle) {
    var span = findApproximateSpan(haystack, needle);
    return span == null ? -1 : span[0];
  }

  /**
   * Same tolerant matching as findApproximateIndex, but also returns the end
   * offset (exclusive) in the original text, so callers can re-anchor a
   * model-copied snippet to real word boundaries in the source.
   */
  private int[] findApproximateSpan(String haystack, String needle) {
    if (needle == null || needle.isBlank()) {
      return null;
    }
    var normHaystack = new StringBuilder();
    var origIndex = new ArrayList<Integer>();
    for (int i = 0; i < haystack.length(); i++) {
      var normalized = normalizeMatchChar(haystack.charAt(i));
      if (normalized == 0) {
        continue;
      }
      if (normalized == ' ' && !normHaystack.isEmpty() && normHaystack.charAt(normHaystack.length() - 1) == ' ') {
        continue;
      }
      normHaystack.append(normalized);
      origIndex.add(i);
    }
    var normNeedle = new StringBuilder();
    for (int i = 0; i < needle.length(); i++) {
      var normalized = normalizeMatchChar(needle.charAt(i));
      if (normalized == 0) {
        continue;
      }
      if (normalized == ' ' && (normNeedle.isEmpty() || normNeedle.charAt(normNeedle.length() - 1) == ' ')) {
        continue;
      }
      normNeedle.append(normalized);
    }
    if (normNeedle.isEmpty() || origIndex.isEmpty()) {
      return null;
    }
    var normalizedStart = normHaystack.indexOf(normNeedle.toString());
    if (normalizedStart < 0) {
      return null;
    }
    var normalizedEnd = normalizedStart + normNeedle.length();
    int origStart = origIndex.get(normalizedStart);
    int origEnd = normalizedEnd < origIndex.size() ? origIndex.get(normalizedEnd) : haystack.length();
    return new int[] {origStart, origEnd};
  }

  /**
   * Re-anchors a model-copied snippet to real word boundaries in the source
   * text. Small models sometimes drop a leading/trailing letter when copying
   * a long sentence verbatim (e.g. "favorire" -> "avorire"); if the char
   * right before/after the matched span is still a letter, we know we cut
   * mid-word and extend to recover it. Falls back to the model's text
   * unchanged if no match is found.
   */
  private String recoverWordBoundaries(String sourceText, String candidate) {
    var span = findApproximateSpan(sourceText, candidate);
    if (span == null) {
      return candidate;
    }
    int start = span[0];
    int end = span[1];
    while (start > 0 && Character.isLetter(sourceText.charAt(start - 1)) && Character.isLetter(sourceText.charAt(start))) {
      start--;
    }
    while (end < sourceText.length() && Character.isLetter(sourceText.charAt(end - 1)) && Character.isLetter(sourceText.charAt(end))) {
      end++;
    }
    return sourceText.substring(start, end).trim();
  }

  private char normalizeMatchChar(char c) {
    if (Character.isWhitespace(c)) {
      return ' ';
    }
    if (c == '’' || c == '`' || c == '\'') {
      return '\'';
    }
    var decomposed = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFD);
    return Character.toLowerCase(decomposed.charAt(0));
  }

  private ByteArrayResource multipartResource(MultipartFile file) throws IOException {
    return new ByteArrayResource(file.getBytes()) {
      @Override
      public String getFilename() {
        return file.getOriginalFilename();
      }
    };
  }

  @FunctionalInterface
  public interface OrganigramPageConsumer {
    void accept(OrganigramPageResult result);
  }

  public record OrganigramPageResult(
      int pageNumber,
      int totalPages,
      boolean imported,
      List<ExtractedStructureResponse> structures) {}

  public record OrganigramPageProcessSummary(int pagesProcessed, int pagesImported, int pagesFailed) {}

  @FunctionalInterface
  public interface StrategicLinePageConsumer {
    void accept(StrategicLinePageResult result);
  }

  public record StrategicLinePageResult(
      int pageNumber,
      int totalPages,
      List<ExtractedStrategicLineResponse> items) {}

  private record PdfPage(
      @JsonProperty("page_number") int pageNumber,
      String text,
      @JsonProperty("image_base64") String imageBase64) {}

  private record PdfExtractionResponse(List<PdfPage> pages) {}

  public record TextPage(
      @JsonProperty("page_number") int pageNumber,
      String text) {}

  private record TextExtractionResponse(List<TextPage> pages) {}
}
