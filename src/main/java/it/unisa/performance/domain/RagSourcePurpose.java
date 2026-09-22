package it.unisa.performance.domain;

public enum RagSourcePurpose {
  /** Sostanza legata al tema delle linee strategiche: recuperato per similarità col contenuto specifico. */
  CONTENT,
  /** Regole/normativa/metodologia (es. D.Lgs. 150/2009, linee guida PIAO): recuperato con query fissa, non per tema. */
  GUIDELINE
}
