# ListaServers.it Hytale Plugin

Questo è il plugin ufficiale per server Hytale di ListaServers.it. Permette agli amministratori di sincronizzare automaticamente le statistiche del proprio server con la piattaforma ListaServers.it in tempo reale.

## Cosa fa

Il plugin viene eseguito silenziosamente in background sul tuo server Hytale. Una volta al minuto, raccoglie le statistiche in tempo reale dal server e le invia direttamente all'API di ListaServers tramite una richiesta HTTP asincrona.

Utilizzando questo plugin, il tuo server mostrerà in modo accurato lo stato online e il numero attuale di giocatori sul sito ListaServers, rendendo il tutto molto più affidabile e sicuro rispetto all'affidarsi a query UDP esterne.

## Dati Inviati all'API

Il plugin è progettato per essere leggero e sicuro. Trasmette i seguenti dati per aggiornare la pagina del tuo server:

1. **API Key**: Un token di autenticazione univoco che collega il tuo server alla tua dashboard su ListaServers.it.
2. **Lista Giocatori**: Un array contenente l'UUID e l'Username di ogni giocatore attualmente connesso al server.
3. **Versione Server**: La versione del server recuperata automaticamente dall'infrastruttura di gioco, utile per far sapere agli utenti quale versione del gioco è richiesta per entrare.

Questa architettura ti permette di installare il plugin su **server multipli** (es. in un Network) utilizzando l'esatta stessa API Key. L'API di ListaServers aggregherà automaticamente i giocatori, filtrando i duplicati senza bisogno di configurazioni complesse per i vari nodi.

Non vengono trasmessi log delle chat, indirizzi IP o configurazioni sensibili del server.

## Installazione

1. Scarica il file `.jar` compilato del plugin dalla pagina Releases.
2. Inserisci il file nella cartella `mods` del tuo server Hytale.
3. Avvia il server una prima volta per generare il file di configurazione predefinito.
4. Spegni il server e apri il file `mods/ListaServers/config.json`.
5. Inserisci la tua API Key (generata dalla dashboard di ListaServers.it).
6. Riavvia il server, oppure se è già acceso usa semplicemente il comando `/listaservers reload`. Il plugin si connetterà e inizierà a inviare i dati automaticamente.

## Comandi in Gioco

Il plugin fornisce un comando base per gestire la connessione direttamente dalla console del server o in gioco (se possiedi i permessi corretti).

- `/listaservers status`
  Mostra lo stato attuale della connessione, inclusi il numero di errori consecutivi (se presenti), il tempo trascorso dall'ultima trasmissione di dati avvenuta con successo e il numero di giocatori attualmente letto.

- `/listaservers reload`
  Ricarica il file `config.json`. Utile se modifichi la tua API Key mentre il server è in esecuzione, senza dover riavviare l'intero server.

- `/listaservers ping`
  Forza una trasmissione immediata dei dati all'API di ListaServers, ignorando l'intervallo programmato di 60 secondi. Ideale per testare se la connessione e l'API Key funzionano correttamente.

## Compilazione dal Sorgente

Se desideri compilare il plugin autonomamente:

1. Clona o scarica la repository del codice sorgente.
2. Inserisci il file `HytaleServer.jar` all'interno della cartella `libs/`.
3. Apri un terminale nella cartella principale del progetto ed esegui `./gradlew build`.
4. Il plugin compilato sarà disponibile all'interno della cartella `build/libs/`.

## Implementazione per altre Liste Server

Questo plugin supporta l'invio simultaneo delle statistiche a più liste server tramite l'aggiunta di endpoint multipli nel file `config.json`. 
Se gestisci una lista server Hytale e vuoi permettere ai tuoi utenti di utilizzare questo plugin per sincronizzare il loro server con la tua piattaforma, devi implementare un endpoint API in grado di ricevere e processare i dati.

### Specifiche dell'Endpoint

Il plugin effettua una singola richiesta HTTP `POST` asincrona ogni 60 secondi verso l'URL specificato dall'utente nella configurazione.

**Headers della richiesta:**
- `Content-Type: application/json`
- `Accept: application/json`
- `User-Agent: ListaServers-Plugin/1.0.0`

**Corpo della richiesta (JSON):**
```json
{
  "api_key": "LA_CHIAVE_API_DELL_UTENTE",
  "serverVersion": "Hytale",
  "players": [
    {
      "uuid": "550e8400-e29b-41d4-a716-446655440000",
      "name": "PlayerUno"
    },
    {
      "uuid": "110e8400-e29b-41d4-a716-446655441111",
      "name": "PlayerDue"
    },
    {
      "uuid": "220e8400-e29b-41d4-a716-446655442222",
      "name": "PlayerTre"
    }
  ]
}
```

### Dettagli sui Dati
- **api_key**: La stringa inserita dall'utente nel file di configurazione, essenziale per autenticare e identificare a quale server appartengono le statistiche.
- **serverVersion**: Attualmente impostato sempre sulla stringa fissa "Hytale".
- **players**: La lista esatta dei giocatori online al momento dell'invio. 

### Risposte e Codici di Stato
Il plugin considera la sincronizzazione andata a buon fine solo se il tuo endpoint restituisce un codice di stato HTTP compreso tra `200` e `299`.
Se restituisci un codice di errore (ad esempio `401 Unauthorized` o `400 Bad Request`), l'errore verrà registrato all'interno della console del server Hytale assieme ai primi 100 caratteri del corpo della tua risposta. Questo ti permette di inviare messaggi di errore testuali (es. "API Key non valida") che l'utente potra' leggere nei propri log.

Per invitare gli utenti a usare il plugin per il tuo sito, ti basta fornire loro questo blocco di esempio da incollare nell'array `endpoints` del loro file `config.json`:

```json
{
  "url": "https://api.tuosito.it/v1/ping",
  "api_key": "LA_CHIAVE_API_UTENTE"
}
```
