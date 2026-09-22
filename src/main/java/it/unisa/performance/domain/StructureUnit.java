package it.unisa.performance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "structure_units", uniqueConstraints = @UniqueConstraint(columnNames = "code"))
public class StructureUnit {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 32)
  private String code;

  @Column(nullable = false, length = 512)
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(length = 40)
  private StructureUnitType type;

  @ManyToOne
  @JoinColumn(name = "parent_id")
  private StructureUnit parent;

  @Column(nullable = false, length = 80)
  private String area;

  @Column(nullable = false)
  private double performance2023;

  @Column(nullable = false)
  private double performance2024;

  @Column(nullable = false)
  private double performance2025;

  @Column(nullable = false)
  private double averagePerformance;

  public Long getId() {
    return id;
  }

  public String getCode() {
    return code;
  }

  public void setCode(String code) {
    this.code = code;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public StructureUnitType getType() {
    if (type != null) {
      return type;
    }
    return parent == null ? StructureUnitType.direzione_generale : StructureUnitType.uos;
  }

  public void setType(StructureUnitType type) {
    this.type = type;
  }

  public StructureUnit getParent() {
    return parent;
  }

  public void setParent(StructureUnit parent) {
    this.parent = parent;
  }

  public String getArea() {
    return area;
  }

  public void setArea(String area) {
    this.area = area;
  }

  public double getPerformance2023() {
    return performance2023;
  }

  public void setPerformance2023(double performance2023) {
    this.performance2023 = performance2023;
  }

  public double getPerformance2024() {
    return performance2024;
  }

  public void setPerformance2024(double performance2024) {
    this.performance2024 = performance2024;
  }

  public double getPerformance2025() {
    return performance2025;
  }

  public void setPerformance2025(double performance2025) {
    this.performance2025 = performance2025;
  }

  public double getAveragePerformance() {
    return averagePerformance;
  }

  public void setAveragePerformance(double averagePerformance) {
    this.averagePerformance = averagePerformance;
  }
}
