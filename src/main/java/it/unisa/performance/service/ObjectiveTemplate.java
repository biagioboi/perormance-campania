package it.unisa.performance.service;

import it.unisa.performance.domain.Direction;

record ObjectiveTemplate(
    String title,
    String description,
    String indicator,
    double base,
    String unit,
    Direction direction) {}

record AssignedObjectiveTemplate(
    String lineCode,
    String structureCode,
    ObjectiveTemplate objective) {}
