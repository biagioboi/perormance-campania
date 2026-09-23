# Performance

Applicativo Spring Boot per la dashboard OCR Assistant della Regione Campania sul ciclo della performance: gestione linee strategiche, strutture organizzative (organigramma) e generazione AI degli obiettivi di performance, con un layer RAG condiviso per migliorare l'accuratezza delle tre pipeline generative.

## Indice

- [Architettura](#architettura)
- [Servizi e responsabilità](#servizi-e-responsabilità)
- [Le tre pipeline Ollama](#le-tre-pipeline-ollama)
- [Layer RAG (Qdrant)](#layer-rag-qdrant)
- [Schema dati](#schema-dati)
- [API REST](#api-rest)
- [Avvio con Docker Compose](#avvio-con-docker-compose)
- [Avvio locale (senza Docker)](#avvio-locale-senza-docker)
- [Configurazione (variabili d'ambiente)](#configurazione-variabili-dambiente)

## Architettura

Lo stack è composto da **4 servizi** più un modello set esterno:

```
                         ┌─────────────────────────┐
                         │   Ollama (host esterno)  │
                         │   172.16.16.206:11434    │
                         │  llama3.1:8b  (testo)    │
                         │  gemma4:12b   (vision)   │
                         │  nomic-embed-text (emb.) │
                         └───────────▲──────────────┘
                                     │ HTTP
┌────────────────────────────────────┴───────────────────────────────────┐
│                        performance-network (Docker, esterna)            │
│                                                                          │
│  ┌───────────────┐   multipart PDF   ┌──────────────────┐              │
│  │   app          │ ────────────────▶│  pdf-extractor    │              │
│  │  Spring Boot   │◀──────────────── │  FastAPI+PyMuPDF   │              │
│  │  (Java 17)     │  testo+immagine  │  porta 8000        │              │
│  │  porta 8080    │   per pagina     └──────────────────┘              │
│  │                │                                                     │
│  │                │   REST (vettori)  ┌──────────────────┐              │
│  │                │ ────────────────▶ │  qdrant           │              │
│  │                │◀────────────────  │  vector DB        │              │
│  │                │   top-K chunk     │  porta 6333        │              │
│  └───────┬────────┘                  └──────────────────┘              │
│          │ JDBC                                                         │
└──────────┼───────────────────────────────────────────────────────────┘
           ▼
  ┌─────────────────┐
  │  mysql-biasi      │  container esterno (non in questo compose),
  │  MySQL             │  collegato manualmente a performance-network
  └─────────────────┘
```

- **`app`**: applicativo Spring Boot, unico punto di ingresso HTTP (dashboard statica + API REST). Orchestra le chiamate a `pdf-extractor`, a Ollama e a Qdrant, persiste lo stato applicativo (linee, strutture, obiettivi, fonti RAG) su MySQL.
- **`pdf-extractor`**: microservizio Python (FastAPI + PyMuPDF), stateless, estrae testo e rendering immagine per pagina da un PDF caricato.
- **`qdrant`**: vector database dedicato al layer RAG, con volume persistente `qdrant-data`.
- **`mysql-biasi`**: MySQL gestito **fuori** da questo `docker-compose.yml` (container esistente sulla macchina), raggiunto tramite la rete Docker condivisa `performance-network` — non è responsabilità di questo repo avviarlo.
- **Ollama**: non è containerizzato in questo stack. Gira come servizio di sistema su un host separato della stessa rete locale (`172.16.16.206`), condiviso con altri utenti/processi della GPU. L'app vi si connette solo via HTTP.

Tutti i container applicativi (`app`, `pdf-extractor`, `qdrant`) girano sulla rete Docker **esterna** `performance-network`, creata una tantum e condivisa anche dal container MySQL.

## Servizi e responsabilità

| Servizio | Tecnologia | Responsabilità | Persistenza |
|---|---|---|---|
| `app` | Spring Boot 3.3 / Java 17 | API REST, orchestrazione pipeline, dashboard statica (`regione/ocr-assistant-campania.html`) | MySQL (metadati), nessuno storage file |
| `pdf-extractor` | FastAPI + PyMuPDF (Python) | Estrazione testo nativo pagina-per-pagina + rendering PNG (150 dpi) per fallback vision | stateless |
| `qdrant` | Qdrant (Rust, immagine ufficiale) | Vector store per il layer RAG: embedding dei chunk di documenti caricati dall'admin | volume Docker `qdrant-data` |
| Ollama (esterno) | llama3.1:8b / gemma4:12b / nomic-embed-text | Generazione testo, vision multimodale, embedding | nessuna (stateless, servito da host esterno) |
| MySQL (esterno) | MySQL 8 | Dati applicativi: linee strategiche, strutture, obiettivi, generazioni, fonti RAG | volume gestito fuori da questo repo |

Import ed elaborazioni pesanti (import PDF, generazione obiettivi, ingestion RAG) girano come **job asincroni in-memory** (`CompletableFuture.runAsync` + job map, snapshot del `MultipartFile` prima dell'esecuzione async), esposti via endpoint di polling e aggregati nella dashboard task-board (`GET /api/tasks`).

## Le tre pipeline Ollama

L'app usa Ollama per tre compiti distinti, ciascuno con un modello e una configurazione di contesto (`num_ctx`) dedicati, per evitare di forzare un unico contesto sovradimensionato su tutte le chiamate:

1. **Generazione obiettivi** (`OllamaObjectiveService`, modello testo `llama3.1:8b`, `num_ctx=8192`) — a partire da una linea strategica e dalle strutture organizzative candidate (matching per similarità semantica), genera obiettivi SMART assegnati alle strutture, con vincoli su numero, pesi e indicatori.
2. **Estrazione linee strategiche da PDF** (`OllamaStrategicLineService`, stesso modello testo, `num_ctx=16384`) — estrae macro-categorie e obiettivi da un documento di programmazione. Gli obiettivi elencati come bullet point vengono **splittati deterministicamente per marcatore di lista** (non via LLM: i modelli piccoli su liste lunghe possono troncare o unire voci in modo non riproducibile anche a `temperature=0`); il modello interviene solo sui titoli di categoria e sulle sezioni in prosa senza bullet. Include un controllo anti-hallucination (`verifyAgainstSource`) che verifica ogni estrazione contro il testo letterale della pagina.
3. **Estrazione organigramma** (`OllamaOrganigramService`, modello vision `gemma4:12b`, `num_ctx=32768`) — pipeline ibrida pagina-per-pagina: usa il testo nativo se sufficiente, altrimenti passa in fallback l'immagine PNG della pagina al modello vision.

## Layer RAG (Qdrant)

Le tre pipeline sopra condividono un layer RAG opzionale: un admin può caricare da dashboard (tab "Fonti RAG") documenti PDF/TXT arbitrari (normativa, linee guida, materiale di riferimento), che vengono chunkati per pagina, embeddati (`nomic-embed-text`, 768 dimensioni) e indicizzati su Qdrant (collection `rag_chunks`, distanza cosine).

Il retrieval è **a doppio canale**, distinzione fatta perché un documento normativo/metodologico non si trova per similarità con l'argomento della linea strategica, ma va sempre incluso come regola:

- **CONTENT** — retrieval per similarità semantica rispetto al testo corrente (linea strategica, pagina PDF), per continuità terminologica.
- **GUIDELINE** — retrieval con query fissa (embedding cachato), per materiale metodologico/normativo che deve comparire sempre a prescindere dal topic.

Il contesto RAG è iniettato nel prompt come blocco separato ed esplicitamente etichettato "materiale di supporto, non fonte primaria da cui copiare" — non interferisce mai con i controlli anti-hallucination, che confrontano l'output solo contro il testo sorgente originale.

Il layer è **fail-soft by design**: se Qdrant non risponde, il retrieval ritorna lista vuota e la generazione prosegue senza contesto RAG, senza mai bloccare un job.

## Schema dati

Tabelle principali (MySQL, generate da Hibernate con `ddl-auto=update`):

- `strategic_lines` — linee di indirizzo/macro-categorie, import da PDF o inserimento manuale
- `structure_units` — organigramma (Direzioni Generali, Settori, UOS), import da PDF organigramma
- `objectives`, `objective_structure_assignments`, `objective_assignment_actions` — obiettivi generati, loro assegnazione alle strutture e le azioni/indicatori collegati
- `generation_runs` — storico delle sessioni di generazione AI
- `rag_sources` — metadati delle fonti caricate per il RAG (stato ingestion, conteggio chunk); il testo grezzo dei chunk non è duplicato in MySQL, vive solo nei payload Qdrant

Le tabelle OBSA (`obsa_objectives` e derivate) sono state rimosse: la feature di confronto con il documento OBSA reale è stata tolta dallo scope del prodotto.

## API REST

Tutte le API sono sotto `/api`. Principali gruppi:

- `/api/strategic-lines`, `/api/strategic-lines/extract-pdf`, `/api/strategic-lines/import-pdf` (+ `/{jobId}` di polling)
- `/api/structures`, `/api/structures/extract-organigram-pdf`, `/api/structures/import-organigram-pdf` (+ `/{jobId}`)
- `/api/generation-jobs` (+ `/{jobId}`), `/api/generations`, `/api/generations/{id}`, `/api/generations/{id}/objectives`
- `/api/objectives/{id}/base-target`
- `/api/rag/sources`, `/api/rag/sources/import` (+ `/{jobId}`), `DELETE /api/rag/sources/{id}`
- `/api/tasks` — aggregato di tutti i job asincroni in corso/recenti (import organigramma, import linee strategiche, import RAG, generazione obiettivi), per la task-board della dashboard

## Avvio con Docker Compose

Lo stack applicativo (`app`, `pdf-extractor`, `qdrant`) si avvia con Docker Compose; MySQL e Ollama restano esterni.

1. Crea la rete condivisa (una tantum) e collegaci il container MySQL esistente:

   ```bash
   docker network create performance-network
   docker network connect performance-network mysql-biasi
   ```

2. Copia `.env.example` in `.env` e valorizza le variabili (almeno `DB_PASSWORD`, il nome del DB, e `OLLAMA_BASE_URL` se Ollama non è su `172.16.16.206`):

   ```bash
   cp .env.example .env
   ```

3. Avvia lo stack:

   ```bash
   docker compose up --build
   ```

   `app` parte solo dopo che `pdf-extractor` e `qdrant` risultano `healthy` (`depends_on` con `condition: service_healthy`).

Dashboard disponibile su `http://localhost:8080/`.

## Avvio locale (senza Docker)

Utile per sviluppo rapido sul solo backend Spring Boot (richiede comunque MySQL, Ollama e — se si vuole testare il RAG — Qdrant raggiungibili).

```sql
CREATE DATABASE performance CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

```bash
DB_URL='jdbc:mysql://localhost:3306/performance?useSSL=false&serverTimezone=Europe/Rome&allowPublicKeyRetrieval=true' \
DB_USERNAME='root' \
DB_PASSWORD='toor' \
DB_DRIVER='com.mysql.cj.jdbc.Driver' \
mvn spring-boot:run
```

Il microservizio `pdf-extractor` va avviato a parte (vedi `pdf-extractor-service/`), oppure puntando `PDF_EXTRACTOR_BASE_URL` a un'istanza già in esecuzione.

## Configurazione (variabili d'ambiente)

| Variabile | Default | Descrizione |
|---|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `DB_DRIVER` | vedi `application.properties` | Connessione MySQL |
| `OLLAMA_BASE_URL` | `http://172.16.16.206:11434` | Host Ollama |
| `OLLAMA_MODEL` | `gemma4:12b` | Modello testo (generazione obiettivi, estrazione linee strategiche) |
| `OLLAMA_VISION_MODEL` | `gemma4:12b` | Modello vision (estrazione organigramma) |
| `OLLAMA_EMBEDDING_MODEL` | `nomic-embed-text` | Modello embedding (RAG) |
| `PDF_EXTRACTOR_BASE_URL` | `http://localhost:8000` | Endpoint del microservizio `pdf-extractor` |
| `QDRANT_BASE_URL` | `http://localhost:6333` | Endpoint Qdrant |
| `QDRANT_COLLECTION` | `rag_chunks` | Nome collection Qdrant |
| `RAG_CHUNK_SIZE` / `RAG_CHUNK_OVERLAP` | `1800` / `200` | Dimensione/overlap chunk in caratteri per l'ingestion RAG |
| `RAG_TOP_K` | `5` | Numero di chunk recuperati per query |
| `RAG_ENABLED_STRATEGIC_LINES` | `false` | Abilita il contesto RAG anche nell'estrazione linee strategiche (task estrattivo, disattivo di default per non rischiare contaminazione) |

In produzione (vedi `.env` reale, non incluso nel repo) i valori effettivi differiscono dai default in `application.properties`: ad esempio `OLLAMA_MODEL=llama3.1:8b` per il testo, con `gemma4:12b` riservato al solo compito vision.
