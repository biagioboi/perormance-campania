package it.unisa.performance.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

@Service
public class PdfExtractionService {

  private static final String EXTRACT_ENDPOINT =
      "https://n8n.habeslab.it/webhook/extratPriorityFromPDF";

  private final RestClient restClient;

  public PdfExtractionService(RestClient.Builder restClientBuilder) {
    this.restClient = restClientBuilder.build();
  }

  public JsonNode extractStrategicLines(MultipartFile file) throws IOException {
    var body = new LinkedMultiValueMap<String, Object>();
    body.add("data", multipartResource(file));

    return restClient.post()
        .uri(EXTRACT_ENDPOINT)
        .contentType(MediaType.MULTIPART_FORM_DATA)
        .body(body)
        .retrieve()
        .body(JsonNode.class);
  }

  private ByteArrayResource multipartResource(MultipartFile file) throws IOException {
    return new ByteArrayResource(file.getBytes()) {
      @Override
      public String getFilename() {
        return file.getOriginalFilename();
      }
    };
  }
}
