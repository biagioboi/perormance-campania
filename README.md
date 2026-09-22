# Performance

Applicativo Spring Boot per la dashboard Regione Campania sul ciclo della performance.

## Avvio rapido

Di default l'applicazione usa MySQL su `localhost:3306`, database `performance`, utente `root` e password `toor`.

Creare il database:

```sql
CREATE DATABASE performance CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

Avviare Spring Boot:

```bash
mvn spring-boot:run
```

Dashboard:

```text
http://localhost:8080/
```

## Override configurazione

Per cambiare credenziali o JDBC URL:

```bash
DB_URL='jdbc:mysql://localhost:3306/performance?useSSL=false&serverTimezone=Europe/Rome&allowPublicKeyRetrieval=true' \
DB_USERNAME='root' \
DB_PASSWORD='toor' \
DB_DRIVER='com.mysql.cj.jdbc.Driver' \
mvn spring-boot:run
```

## Configurazione Ollama

La generazione AI usa Ollama via HTTP. Host e modello si configurano tramite le variabili d'ambiente `OLLAMA_BASE_URL` e `OLLAMA_MODEL` (default in `src/main/resources/application.properties`):

```properties
ollama.base-url=${OLLAMA_BASE_URL:http://172.16.16.206:11434}
ollama.model=${OLLAMA_MODEL:gemma4:12b}
```

Imposta queste variabili se sposti il servizio o cambi modello.

## Estrazione automatica dell'organigramma

La dashboard supporta anche l'import del PDF dell'organigramma dalla sezione **Strutture**. Il backend usa una pipeline ibrida basata sul microservizio Python `pdf-extractor` (vedi `pdf-extractor-service/`):

1. il PDF viene inviato al microservizio, che estrae il testo digitale nativo pagina per pagina (PyMuPDF) e, in parallelo, renderizza ogni pagina come immagine PNG
2. se il testo di una pagina non e sufficiente, l'immagine viene passata in fallback all'analisi vision del modello Ollama locale
3. normalizzazione JSON delle strutture e import in tabella con baseline storica neutra `97-97-97` per le nuove voci

Endpoint dedicato: `POST /api/structures/extract-organigram-pdf` (multipart, campo `data`).

L'app raggiunge il microservizio tramite la proprieta `pdfextractor.base-url` (env `PDF_EXTRACTOR_BASE_URL`, default `http://localhost:8000`).

Il backend espone API REST sotto `/api` per linee strategiche, strutture, generazioni e obiettivi salvati.

## Docker Compose

Lo stack e composto da due servizi: `app` (Spring Boot) e `pdf-extractor` (microservizio Python). Il database MySQL e gestito in un container esterno, raggiunto tramite una rete Docker condivisa.

1. Crea la rete condivisa (una tantum) e collegaci il container MySQL esistente:

   ```bash
   docker network create performance-network
   docker network connect performance-network <nome-container-mysql>
   ```

2. Copia `.env.example` in `.env` e valorizza `DB_PASSWORD` (e le altre variabili se necessario):

   ```bash
   cp .env.example .env
   ```

3. Avvia lo stack:

   ```bash
   docker compose up --build
   ```

Dashboard disponibile su `http://localhost:8080/`.
