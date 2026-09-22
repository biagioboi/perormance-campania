package it.unisa.performance.service;

import it.unisa.performance.domain.Direction;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ObjectiveTemplateCatalog {

  private final Map<String, List<ObjectiveTemplate>> templates = Map.of(
      "Digitalizzazione", List.of(
          template("Riduzione tempi medi dei procedimenti", "Diminuire i tempi medi di conclusione dei procedimenti amministrativi mediante reingegnerizzazione dei flussi e adozione di workflow digitali.", "giorni medi di durata procedimento", 30, "gg", Direction.down),
          template("Servizi pienamente digitali al cittadino", "Aumentare la percentuale di servizi erogati interamente in modalita digitale tramite il portale unico regionale.", "% servizi digitali su totale", 60, "%", Direction.up)),
      "Sanita", List.of(
          template("Riduzione liste d'attesa per prestazioni di classe B", "Ridurre il tempo medio di attesa per le prestazioni ambulatoriali di classe B nelle ASL regionali.", "giorni medi attesa classe B", 25, "gg", Direction.down),
          template("Copertura screening oncologici", "Incrementare l'adesione ai programmi di screening oncologico mammografico, colorettale e cervicale.", "% copertura popolazione target", 55, "%", Direction.up)),
      "Ambiente", List.of(
          template("Raccolta differenziata", "Incrementare la quota di raccolta differenziata nei comuni capoluogo della Regione.", "% RD media regionale", 58, "%", Direction.up),
          template("Bonifica siti contaminati", "Avanzamento dei procedimenti di bonifica nei Siti di Interesse Nazionale presenti nel territorio regionale.", "% superficie certificata", 35, "%", Direction.up)),
      "Lavoro e Formazione", List.of(
          template("Tasso di occupazione post-formazione", "Innalzare il tasso di occupazione a 12 mesi dei partecipanti ai corsi di formazione professionale finanziati dalla Regione.", "% occupati a 12 mesi", 50, "%", Direction.up),
          template("Presa in carico CPI", "Aumentare le prese in carico effettive presso i Centri per l'Impiego entro 60 giorni dalla DID.", "% prese in carico entro 60gg", 65, "%", Direction.up)),
      "Cultura e Turismo", List.of(
          template("Visitatori siti regionali", "Incrementare il numero di visitatori dei siti museali e archeologici regionali.", "visitatori annui (migliaia)", 850, "k", Direction.up),
          template("Digitalizzazione collezioni", "Aumentare la quota di patrimonio museale catalogato e accessibile in formato digitale aperto.", "% beni digitalizzati", 40, "%", Direction.up)),
      "Politiche Sociali", List.of(
          template("Copertura assistenza domiciliare anziani", "Estendere la copertura del servizio di assistenza domiciliare integrata per la popolazione anziana non autosufficiente.", "% popolazione target servita", 32, "%", Direction.up),
          template("Posti in strutture protette", "Aumento dei posti disponibili in strutture residenziali e semi-residenziali per disabilita grave.", "posti disponibili", 1200, "posti", Direction.up)),
      "Mobilita", List.of(
          template("Puntualita del trasporto pubblico", "Migliorare la puntualita del servizio di trasporto pubblico locale su gomma e ferro.", "% corse puntuali (+/-5')", 78, "%", Direction.up),
          template("Estensione reti ciclabili", "Aumentare i chilometri di rete ciclabile regionale realizzati o messi in esercizio.", "km cumulati realizzati", 180, "km", Direction.up)),
      "Sviluppo Economico", List.of(
          template("Tempi medi erogazione contributi alle imprese", "Ridurre i tempi medi di erogazione dei contributi a fondo perduto e degli incentivi POR FESR alle PMI.", "giorni medi da ammissione a erogazione", 120, "gg", Direction.down),
          template("Imprese beneficiarie programmi regionali", "Aumentare il numero di imprese beneficiarie dei bandi regionali di sostegno all'innovazione.", "n. imprese beneficiarie", 480, "imprese", Direction.up)),
      "Trasversale", List.of(
          template("Pubblicazione obblighi di trasparenza", "Garantire la pubblicazione tempestiva e integrale degli obblighi di Amministrazione Trasparente.", "% adempimenti pubblicati nei termini", 88, "%", Direction.up),
          template("Formazione anticorruzione del personale", "Estendere la partecipazione ai percorsi formativi obbligatori in materia di anticorruzione e codice di comportamento.", "% personale formato", 75, "%", Direction.up)));

  public List<ObjectiveTemplate> pick(String area, int count) {
    var pool = templates.getOrDefault(area, templates.get("Trasversale"));
    return pool.stream().limit(count).toList();
  }

  private static final int TEMPLATE_ACTION_WEIGHT = 20;

  private static ObjectiveTemplate template(
      String title,
      String description,
      String indicator,
      double base,
      String unit,
      Direction direction) {
    var action = new ObjectiveActionTemplate(description, indicator, base, unit, direction, TEMPLATE_ACTION_WEIGHT);
    return new ObjectiveTemplate(title, description, List.of(action));
  }
}
