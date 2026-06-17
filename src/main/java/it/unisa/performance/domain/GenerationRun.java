package it.unisa.performance.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "generation_runs")
public class GenerationRun {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Instant createdAt = Instant.now();

  @Column(nullable = false, length = 32)
  private String mode;

  @Column(nullable = false)
  private int objectivesPerLine;

  @Column(nullable = false)
  private double stretchEccellente;

  @Column(nullable = false)
  private double stretchBuona;

  @Column(nullable = false)
  private double stretchAdeguata;

  @Column(nullable = false)
  private double stretchRecupero;

  @OneToMany(mappedBy = "generationRun", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("id ASC")
  private List<Objective> objectives = new ArrayList<>();

  public Long getId() {
    return id;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public String getMode() {
    return mode;
  }

  public void setMode(String mode) {
    this.mode = mode;
  }

  public int getObjectivesPerLine() {
    return objectivesPerLine;
  }

  public void setObjectivesPerLine(int objectivesPerLine) {
    this.objectivesPerLine = objectivesPerLine;
  }

  public double getStretchEccellente() {
    return stretchEccellente;
  }

  public void setStretchEccellente(double stretchEccellente) {
    this.stretchEccellente = stretchEccellente;
  }

  public double getStretchBuona() {
    return stretchBuona;
  }

  public void setStretchBuona(double stretchBuona) {
    this.stretchBuona = stretchBuona;
  }

  public double getStretchAdeguata() {
    return stretchAdeguata;
  }

  public void setStretchAdeguata(double stretchAdeguata) {
    this.stretchAdeguata = stretchAdeguata;
  }

  public double getStretchRecupero() {
    return stretchRecupero;
  }

  public void setStretchRecupero(double stretchRecupero) {
    this.stretchRecupero = stretchRecupero;
  }

  public List<Objective> getObjectives() {
    return objectives;
  }

  public void addObjective(Objective objective) {
    objectives.add(objective);
    objective.setGenerationRun(this);
  }
}
