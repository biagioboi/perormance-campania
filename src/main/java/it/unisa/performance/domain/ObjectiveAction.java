package it.unisa.performance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

@Embeddable
public class ObjectiveAction {

  @Column(length = 600)
  private String action;

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

  // Human-in-the-loop review: null = not yet reviewed, true/false = approved/rejected by a reviewer.
  // Independent of baseTarget/calibratedTarget so an indicator can be reviewed before or after the
  // real base value is entered.
  private Boolean approved;

  private Integer reviewScore;

  protected ObjectiveAction() {}

  public ObjectiveAction(
      String action,
      String indicator,
      double baseTarget,
      double calibratedTarget,
      String unit,
      Direction direction,
      int weight) {
    this(action, indicator, baseTarget, calibratedTarget, unit, direction, weight, null, null);
  }

  public ObjectiveAction(
      String action,
      String indicator,
      double baseTarget,
      double calibratedTarget,
      String unit,
      Direction direction,
      int weight,
      Boolean approved,
      Integer reviewScore) {
    this.action = action;
    this.indicator = indicator;
    this.baseTarget = baseTarget;
    this.calibratedTarget = calibratedTarget;
    this.unit = unit;
    this.direction = direction;
    this.weight = weight;
    this.approved = approved;
    this.reviewScore = reviewScore;
  }

  public String getAction() {
    return action;
  }

  public String getIndicator() {
    return indicator;
  }

  public double getBaseTarget() {
    return baseTarget;
  }

  public double getCalibratedTarget() {
    return calibratedTarget;
  }

  public String getUnit() {
    return unit;
  }

  public Direction getDirection() {
    return direction;
  }

  public int getWeight() {
    return weight;
  }

  public Boolean getApproved() {
    return approved;
  }

  public Integer getReviewScore() {
    return reviewScore;
  }
}
