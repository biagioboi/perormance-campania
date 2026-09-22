package it.unisa.performance.service;

import it.unisa.performance.domain.Priority;
import it.unisa.performance.domain.StrategicLine;
import it.unisa.performance.domain.StrategicLineType;
import it.unisa.performance.domain.StructureUnit;
import it.unisa.performance.domain.StructureUnitType;
import it.unisa.performance.repository.StrategicLineRepository;
import it.unisa.performance.repository.StructureUnitRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DemoDataService {

  private final StrategicLineRepository strategicLineRepository;
  private final StructureUnitRepository structureUnitRepository;

  public DemoDataService(
      StrategicLineRepository strategicLineRepository,
      StructureUnitRepository structureUnitRepository) {
    this.strategicLineRepository = strategicLineRepository;
    this.structureUnitRepository = structureUnitRepository;
  }

  @Transactional
  public void seedIfEmpty() {
    // Runs on every app boot (ApplicationRunner) — must only touch the database on a genuinely
    // fresh install. Previously this upserted the demo rows unconditionally on every restart,
    // silently overwriting any real strategic line or structure that happened to reuse a demo code
    // (e.g. LS.01-LS.06).
    if (strategicLineRepository.count() == 0) {
      upsertStrategicLines(defaultStrategicLines());
    }
    if (structureUnitRepository.count() == 0) {
      upsertStructures(defaultStructures());
    }
  }

  @Transactional
  public List<StrategicLine> resetStrategicLines() {
    return upsertStrategicLines(defaultStrategicLines());
  }

  private List<StrategicLine> defaultStrategicLines() {
    return List.of(
        line("LS.01", "Semplificazione amministrativa e digitalizzazione", "Digitalizzazione", Priority.alta,
            "Riduzione dei tempi dei procedimenti, dematerializzazione dei flussi documentali, adozione di servizi pienamente digitali per cittadini e imprese."),
        line("LS.02", "Riduzione delle liste d'attesa sanitarie", "Sanita", Priority.alta,
            "Potenziamento delle agende, monitoraggio dei tempi di erogazione delle prestazioni e recupero della domanda inevasa nelle aziende sanitarie regionali."),
        line("LS.03", "Transizione ecologica e gestione del ciclo dei rifiuti", "Ambiente", Priority.alta,
            "Incremento della raccolta differenziata, riduzione del conferimento in discarica, monitoraggio delle aree contaminate e bonifica dei SIN."),
        line("LS.04", "Politiche attive del lavoro e competenze", "Lavoro e Formazione", Priority.media,
            "Aumento della partecipazione ai percorsi formativi, integrazione tra Centri per l'Impiego e sistema regionale di formazione professionale."),
        line("LS.05", "Valorizzazione del patrimonio culturale", "Cultura e Turismo", Priority.media,
            "Incremento dei visitatori dei siti regionali, digitalizzazione delle collezioni museali, sviluppo di itinerari turistici integrati."),
        line("LS.06", "Anticorruzione e trasparenza", "Trasversale", Priority.alta,
            "Rotazione del personale nei procedimenti a maggior rischio, pubblicazione integrale dei dati di Amministrazione Trasparente, formazione anticorruzione."));
  }

  private List<StructureUnit> defaultStructures() {
    return List.of(
        /*structure("DG.50.01", "DG Risorse Umane", "Trasversale", 78, 82, 86),
        structure("DG.50.02", "DG Risorse Finanziarie e Patrimonio", "Trasversale", 84, 88, 91),
        structure("DG.50.03", "DG Sviluppo Economico e Attivita Produttive", "Sviluppo Economico", 72, 75, 79),
        structure("DG.50.04", "DG Tutela della Salute", "Sanita", 68, 71, 74),
        structure("DG.50.05", "DG Politiche Sociali e Sociosanitarie", "Politiche Sociali", 81, 84, 87),
        structure("DG.50.06", "DG Istruzione, Formazione, Lavoro", "Lavoro e Formazione", 76, 80, 83),
        structure("DG.50.07", "DG Mobilita", "Mobilita", 63, 66, 69),
        structure("DG.50.08", "DG Governo del Territorio e Lavori Pubblici", "Ambiente", 70, 73, 77),
        structure("DG.50.09", "DG Ambiente, Difesa del Suolo ed Ecosistema", "Ambiente", 79, 83, 89),
        structure("DG.50.10", "DG Politiche Culturali e Turismo", "Cultura e Turismo", 85, 89, 92),
        structure("DG.50.11", "DG Universita, Ricerca e Innovazione", "Digitalizzazione", 88, 91, 93),*/
        structure("DG.50.12", "DG Politiche Agricole e Forestali", "Sviluppo Economico", 74, 77, 80));
  }

  private StrategicLine line(String code, String title, String area, Priority priority, String description) {
    var line = new StrategicLine();
    line.setCode(code);
    line.setTitle(title);
    line.setType(StrategicLineType.macro_categoria);
    line.setArea(area);
    line.setPriority(priority);
    line.setDescription(description);
    return line;
  }

  private List<StrategicLine> upsertStrategicLines(List<StrategicLine> defaults) {
    var saved = defaults.stream()
        .map(defaultLine -> {
          var line = strategicLineRepository.findByCode(defaultLine.getCode())
              .orElseGet(StrategicLine::new);
          line.setCode(defaultLine.getCode());
          line.setTitle(defaultLine.getTitle());
          line.setType(defaultLine.getType());
          line.setParent(defaultLine.getParent());
          line.setArea(defaultLine.getArea());
          line.setPriority(defaultLine.getPriority());
          line.setDescription(defaultLine.getDescription());
          return strategicLineRepository.save(line);
        })
        .toList();
    strategicLineRepository.flush();
    return saved;
  }

  private List<StructureUnit> upsertStructures(List<StructureUnit> defaults) {
    var saved = defaults.stream()
        .map(defaultStructure -> {
          var structure = structureUnitRepository.findByCode(defaultStructure.getCode())
              .orElseGet(StructureUnit::new);
          structure.setCode(defaultStructure.getCode());
          structure.setName(defaultStructure.getName());
          structure.setType(defaultStructure.getType());
          structure.setParent(defaultStructure.getParent());
          structure.setArea(defaultStructure.getArea());
          structure.setPerformance2023(defaultStructure.getPerformance2023());
          structure.setPerformance2024(defaultStructure.getPerformance2024());
          structure.setPerformance2025(defaultStructure.getPerformance2025());
          structure.setAveragePerformance(defaultStructure.getAveragePerformance());
          return structureUnitRepository.save(structure);
        })
        .toList();
    structureUnitRepository.flush();
    return saved;
  }

  private StructureUnit structure(String code, String name, String area, double p2023, double p2024, double p2025) {
    var structure = new StructureUnit();
    structure.setCode(code);
    structure.setName(name);
    structure.setType(StructureUnitType.direzione_generale);
    structure.setArea(area);
    structure.setPerformance2023(p2023);
    structure.setPerformance2024(p2024);
    structure.setPerformance2025(p2025);
    structure.setAveragePerformance(Math.round((p2023 * 0.2 + p2024 * 0.3 + p2025 * 0.5) * 10.0) / 10.0);
    return structure;
  }
}
