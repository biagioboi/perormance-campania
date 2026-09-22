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
@Table(name = "strategic_lines", uniqueConstraints = @UniqueConstraint(columnNames = "code"))
public class StrategicLine {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 32)
  private String code;

  @Column(nullable = false)
  private String title;

  @Enumerated(EnumType.STRING)
  @Column(length = 32)
  private StrategicLineType type;

  @ManyToOne
  @JoinColumn(name = "parent_strategic_line_id")
  private StrategicLine parent;

  @Column(nullable = false, length = 80)
  private String area;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private Priority priority;

  @Column(nullable = false, length = 1200)
  private String description;

  public Long getId() {
    return id;
  }

  public String getCode() {
    return code;
  }

  public void setCode(String code) {
    this.code = code;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public StrategicLineType getType() {
    if (type != null) {
      return type;
    }
    return parent == null ? StrategicLineType.macro_categoria : StrategicLineType.obiettivo_strategico;
  }

  public void setType(StrategicLineType type) {
    this.type = type;
  }

  public StrategicLine getParent() {
    return parent;
  }

  public void setParent(StrategicLine parent) {
    this.parent = parent;
  }

  public String getArea() {
    return area;
  }

  public void setArea(String area) {
    this.area = area;
  }

  public Priority getPriority() {
    return priority;
  }

  public void setPriority(Priority priority) {
    this.priority = priority;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }
}
