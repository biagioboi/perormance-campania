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
    if (average >= 99) {
      return eccellente;
    }
    if (average >= 98) {
      return buona;
    }
    if (average >= 97) {
      return adeguata;
    }
    return recupero;
  }
}
