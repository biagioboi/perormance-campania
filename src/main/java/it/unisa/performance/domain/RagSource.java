package it.unisa.performance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "rag_sources")
public class RagSource {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 512)
  private String fileName;

  @Column(nullable = false, length = 40)
  private String contentType;

  @Column(nullable = false)
  private long fileSizeBytes;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private RagSourceStatus status;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private RagSourcePurpose purpose;

  @Column(length = 2000)
  private String statusMessage;

  @Column(nullable = false)
  private Instant uploadedAt;

  private Instant ingestedAt;

  private int pageCount;

  private int chunkCount;

  public Long getId() {
    return id;
  }

  public String getFileName() {
    return fileName;
  }

  public void setFileName(String fileName) {
    this.fileName = fileName;
  }

  public String getContentType() {
    return contentType;
  }

  public void setContentType(String contentType) {
    this.contentType = contentType;
  }

  public long getFileSizeBytes() {
    return fileSizeBytes;
  }

  public void setFileSizeBytes(long fileSizeBytes) {
    this.fileSizeBytes = fileSizeBytes;
  }

  public RagSourceStatus getStatus() {
    return status;
  }

  public void setStatus(RagSourceStatus status) {
    this.status = status;
  }

  public RagSourcePurpose getPurpose() {
    return purpose;
  }

  public void setPurpose(RagSourcePurpose purpose) {
    this.purpose = purpose;
  }

  public String getStatusMessage() {
    return statusMessage;
  }

  public void setStatusMessage(String statusMessage) {
    this.statusMessage = statusMessage;
  }

  public Instant getUploadedAt() {
    return uploadedAt;
  }

  public void setUploadedAt(Instant uploadedAt) {
    this.uploadedAt = uploadedAt;
  }

  public Instant getIngestedAt() {
    return ingestedAt;
  }

  public void setIngestedAt(Instant ingestedAt) {
    this.ingestedAt = ingestedAt;
  }

  public int getPageCount() {
    return pageCount;
  }

  public void setPageCount(int pageCount) {
    this.pageCount = pageCount;
  }

  public int getChunkCount() {
    return chunkCount;
  }

  public void setChunkCount(int chunkCount) {
    this.chunkCount = chunkCount;
  }
}
