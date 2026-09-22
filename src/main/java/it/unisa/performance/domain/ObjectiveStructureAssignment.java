package it.unisa.performance.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "objective_structure_assignments")
public class ObjectiveStructureAssignment {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "objective_id", nullable = false)
  private Objective objective;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "structure_unit_id", nullable = false)
  private StructureUnit structureUnit;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private PerformanceTier tier;

  @Column(nullable = false)
  private double averagePerformance;

  @Column(nullable = false)
  private double stretch;

  @ElementCollection
  @CollectionTable(name = "objective_assignment_actions", joinColumns = @JoinColumn(name = "assignment_id"))
  @OrderColumn(name = "action_index")
  private List<ObjectiveAction> actions = new ArrayList<>();

  public Long getId() {
    return id;
  }

  public Objective getObjective() {
    return objective;
  }

  public void setObjective(Objective objective) {
    this.objective = objective;
  }

  public StructureUnit getStructureUnit() {
    return structureUnit;
  }

  public void setStructureUnit(StructureUnit structureUnit) {
    this.structureUnit = structureUnit;
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

  public List<ObjectiveAction> getActions() {
    return actions;
  }

  public void setActions(List<ObjectiveAction> actions) {
    this.actions = actions;
  }
}
