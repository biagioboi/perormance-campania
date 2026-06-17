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

La generazione AI usa Ollama via HTTP. Host e modello si configurano in `src/main/resources/application.properties`:

```properties
ollama.base-url=http://172.16.16.206:11434
ollama.model=gemma3:12b
```

Modifica questi due valori se sposti il servizio o cambi modello.

Il backend espone API REST sotto `/api` per linee strategiche, strutture, generazioni e obiettivi salvati.
