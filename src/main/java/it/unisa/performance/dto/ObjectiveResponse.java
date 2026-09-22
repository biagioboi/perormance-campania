package it.unisa.performance.dto;

import it.unisa.performance.domain.Objective;
import it.unisa.performance.domain.ObjectiveAction;
import java.util.ArrayList;
import java.util.List;

public record ObjectiveResponse(
    Long objectiveId,
    String id,
    String line,
    String lineTitle,
    String categoryCode,
    String categoryTitle,
    String area,
    String title,
    String desc,
    Boolean publicValue,
    String missionsPrograms,
    int totalWeight,
    List<AssignmentResponse> assignments) {

  public record ActionResponse(
      int index,
      String action,
      String indicator,
      double baseTarget,
      double calibratedTarget,
      String unit,
      String direction,
      int weight) {}

  public record AssignmentResponse(
      int index,
      Long structureId,
      String structureCode,
      String structureName,
      String tier,
      String tierLabel,
      double avgPerf,
      double stretch,
      List<ActionResponse> actions) {}

  public static ObjectiveResponse from(Objective objective) {
    var assignments = new ArrayList<AssignmentResponse>();
    var objectiveAssignments = objective.getAssignments();
    for (var i = 0; i < objectiveAssignments.size(); i++) {
      var assignment = objectiveAssignments.get(i);
      var structure = assignment.getStructureUnit();
      var actions = new ArrayList<ActionResponse>();
      var assignmentActions = assignment.getActions();
      for (var j = 0; j < assignmentActions.size(); j++) {
        var action = assignmentActions.get(j);
        actions.add(new ActionResponse(
            j,
            action.getAction(),
            action.getIndicator(),
            action.getBaseTarget(),
            action.getCalibratedTarget(),
            action.getUnit(),
            action.getDirection().name(),
            action.getWeight()));
      }
      assignments.add(new AssignmentResponse(
          i,
          structure != null ? structure.getId() : null,
          structure != null ? structure.getCode() : null,
          structure != null ? structure.getName() : null,
          assignment.getTier().name(),
          assignment.getTier().getLabel(),
          assignment.getAveragePerformance(),
          assignment.getStretch(),
          actions));
    }

    var totalWeight = objectiveAssignments.stream()
        .flatMap(assignment -> assignment.getActions().stream())
        .mapToInt(ObjectiveAction::getWeight)
        .sum();

    return new ObjectiveResponse(
        objective.getId(),
        objective.getPublicId(),
        objective.getLineCode(),
        objective.getLineTitle(),
        objective.getCategoryCode(),
        objective.getCategoryTitle(),
        objective.getArea(),
        objective.getTitle(),
        objective.getDescription(),
        objective.getPublicValue(),
        objective.getMissionsPrograms(),
        totalWeight,
        assignments);
  }
}
