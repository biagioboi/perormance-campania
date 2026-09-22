package it.unisa.performance.service;

import it.unisa.performance.domain.Direction;
import java.util.List;

record ObjectiveActionTemplate(
    String action,
    String indicator,
    double base,
    String unit,
    Direction direction,
    int weight) {}

record ObjectiveAssignmentTemplate(
    String structureCode,
    List<ObjectiveActionTemplate> actions) {}

record ObjectiveTemplate(
    String title,
    String description,
    List<ObjectiveActionTemplate> actions) {}

record AssignedObjectiveTemplate(
    String lineCode,
    String title,
    String description,
    Boolean publicValue,
    String missionsPrograms,
    List<ObjectiveAssignmentTemplate> assignments) {}
