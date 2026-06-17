package it.unisa.performance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

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

  @Column(nullable = false, length = 80)
  private String area;

  @Column(nullable = false)
  private String structureName;

  @Column(nullable = false, length = 32)
  private String structureCode;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private PerformanceTier tier;

  @Column(nullable = false)
  private double averagePerformance;

  @Column(nullable = false)
  private double stretch;

  @Column(nullable = false)
  private String title;

  @Column(nullable = false, length = 1200)
  private String description;

  @Column(nullable = false)
  private String indicator;

  @Column(nullable = false)
  private double baseTarget;

  @Column(nullable = false)
  private double calibratedTarget;

  @Column(nullable = false, length = 24)
  private String unit;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 8)
  private Direction direction;

  @Column(nullable = false)
  private int weight;

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

  public String getArea() {
    return area;
  }

  public void setArea(String area) {
    this.area = area;
  }

  public String getStructureName() {
    return structureName;
  }

  public void setStructureName(String structureName) {
    this.structureName = structureName;
  }

  public String getStructureCode() {
    return structureCode;
  }

  public void setStructureCode(String structureCode) {
    this.structureCode = structureCode;
  }

  public PerformanceTier getTier() {
    return tier;
  }

  public void setTier(PerformanceTier tier) {
    this.tier = tier;
  }

  public double getAveragePerformance() {
    return averagePerformance;
  }

  public void setAveragePerformance(double averagePerformance) {
    this.averagePerformance = averagePerformance;
  }

  public double getStretch() {
    return stretch;
  }

  public void setStretch(double stretch) {
    this.stretch = stretch;
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

  public String getIndicator() {
    return indicator;
  }

  public void setIndicator(String indicator) {
    this.indicator = indicator;
  }

  public double getBaseTarget() {
    return baseTarget;
  }

  public void setBaseTarget(double baseTarget) {
    this.baseTarget = baseTarget;
  }

  public double getCalibratedTarget() {
    return calibratedTarget;
  }

  public void setCalibratedTarget(double calibratedTarget) {
    this.calibratedTarget = calibratedTarget;
  }

  public String getUnit() {
    return unit;
  }

  public void setUnit(String unit) {
    this.unit = unit;
  }

  public Direction getDirection() {
    return direction;
  }

  public void setDirection(Direction direction) {
    this.direction = direction;
  }

  public int getWeight() {
    return weight;
  }

  public void setWeight(int weight) {
    this.weight = weight;
  }

  public GenerationRun getGenerationRun() {
    return generationRun;
  }

  public void setGenerationRun(GenerationRun generationRun) {
    this.generationRun = generationRun;
  }
}
