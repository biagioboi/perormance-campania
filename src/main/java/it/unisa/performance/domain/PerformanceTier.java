package it.unisa.performance.domain;

public enum PerformanceTier {
  eccellente("Eccellente"),
  buona("Buona"),
  adeguata("Adeguata"),
  recupero("In recupero");

  private final String label;

  PerformanceTier(String label) {
    this.label = label;
  }

  public String getLabel() {
    return label;
  }

  public static PerformanceTier fromAverage(double average) {
    if (average >= 90) {
      return eccellente;
    }
    if (average >= 80) {
      return buona;
    }
    if (average >= 70) {
      return adeguata;
    }
    return recupero;
  }
}
