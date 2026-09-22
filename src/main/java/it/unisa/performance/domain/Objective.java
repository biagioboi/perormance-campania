package it.unisa.performance.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "objectives")
public class Objective {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 32)
  private String publicId;

  @Column(nullable = false, length = 32)
  private String lineCode;

  @Column(nullable = false)
  private String lineTitle;

  @Column(length = 32)
  private String categoryCode;

  @Column(length = 512)
  private String categoryTitle;

  @Column(nullable = false, length = 80)
  private String area;

  @Column(nullable = false)
  private String title;

  @Column(nullable = false, length = 1200)
  private String description;

  private Boolean publicValue;

  @Column(length = 512)
  private String missionsPrograms;

  @OneToMany(mappedBy = "objective", cascade = CascadeType.ALL, orphanRemoval = true)
  private List<ObjectiveStructureAssignment> assignments = new ArrayList<>();

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "generation_run_id", nullable = false)
  private GenerationRun generationRun;

  public Long getId() {
    return id;
  }

  public String getPublicId() {
    return publicId;
  }

  public void setPublicId(String publicId) {
    this.publicId = publicId;
  }

  public String getLineCode() {
    return lineCode;
  }

  public void setLineCode(String lineCode) {
    this.lineCode = lineCode;
  }

  public String getLineTitle() {
    return lineTitle;
  }

  public void setLineTitle(String lineTitle) {
    this.lineTitle = lineTitle;
  }

  public String getCategoryCode() {
    return categoryCode;
  }

  public void setCategoryCode(String categoryCode) {
    this.categoryCode = categoryCode;
  }

  public String getCategoryTitle() {
    return categoryTitle;
  }

  public void setCategoryTitle(String categoryTitle) {
    this.categoryTitle = categoryTitle;
  }

  public String getArea() {
    return area;
  }

  public void setArea(String area) {
    this.area = area;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public Boolean getPublicValue() {
    return publicValue;
  }

  public void setPublicValue(Boolean publicValue) {
    this.publicValue = publicValue;
  }

  public String getMissionsPrograms() {
    return missionsPrograms;
  }

  public void setMissionsPrograms(String missionsPrograms) {
    this.missionsPrograms = missionsPrograms;
  }

  public List<ObjectiveStructureAssignment> getAssignments() {
    return assignments;
  }

  public void setAssignments(List<ObjectiveStructureAssignment> assignments) {
    this.assignments = assignments;
  }

  public GenerationRun getGenerationRun() {
    return generationRun;
  }

  public void setGenerationRun(GenerationRun generationRun) {
    this.generationRun = generationRun;
  }
}
