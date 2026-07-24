# ListaServers.it Hytale Plugin

Questo è il plugin ufficiale per server Hytale di ListaServers.it. Permette agli amministratori di sincronizzare automaticamente le statistiche del proprio server con la piattaforma ListaServers.it in tempo reale.

## Cosa fa

Il plugin viene eseguito silenziosamente in background sul tuo server Hytale. Una volta al minuto, raccoglie le statistiche in tempo reale dal server e le invia direttamente all'API di ListaServers tramite una richiesta HTTP asincrona.

Utilizzando questo plugin, il tuo server mostrerà in modo accurato lo stato online e il numero attuale di giocatori sul sito ListaServers, rendendo il tutto molto più affidabile e sicuro rispetto all'affidarsi a query UDP esterne.

## Dati Inviati all'API

Il plugin è progettato per essere leggero e sicuro. Trasmette i seguenti dati per aggiornare la pagina del tuo server:

1. **API Key**: Un token di autenticazione univoco che collega il tuo server alla tua dashboard su ListaServers.it.
2. **Lista Giocatori**: Un array contenente l'UUID e l'Username di ogni giocatore attualmente connesso al server.
3. **Versione Server**: La versione del server configurata nel file `config.json`, utile per far sapere agli utenti quale versione del gioco è richiesta per entrare.

Questa architettura ti permette di installare il plugin su **server multipli** (es. in un Network) utilizzando l'esatta stessa API Key. L'API di ListaServers aggregherà automaticamente i giocatori, filtrando i duplicati senza bisogno di configurazioni complesse per i vari nodi.

Non vengono trasmessi log delle chat, indirizzi IP o configurazioni sensibili del server.

## Installazione

1. Scarica il file `.jar` compilato del plugin dalla pagina Releases.
2. Inserisci il file nella cartella `mods` del tuo server Hytale.
3. Avvia il server una prima volta per generare il file di configurazione predefinito.
4. Spegni il server e apri il file `mods/ListaServers/config.json`.
5. Inserisci la tua API Key (generata dalla dashboard di ListaServers.it) e, se necessario, aggiorna la versione del server.
6. Riavvia il server, oppure se è già acceso usa semplicemente il comando `/listaservers reload`. Il plugin si connetterà e inizierà a inviare i dati automaticamente.

## Comandi in Gioco

Il plugin fornisce un comando base per gestire la connessione direttamente dalla console del server o in gioco (se possiedi i permessi corretti).

- `/listaservers status`
  Mostra lo stato attuale della connessione, inclusi il numero di errori consecutivi (se presenti), il tempo trascorso dall'ultima trasmissione di dati avvenuta con successo e il numero di giocatori attualmente letto.

- `/listaservers reload`
  Ricarica il file `config.json`. Utile se modifichi la tua API Key o la versione del server mentre il server è in esecuzione, senza dover riavviare l'intero server.

- `/listaservers ping`
  Forza una trasmissione immediata dei dati all'API di ListaServers, ignorando l'intervallo programmato di 60 secondi. Ideale per testare se la connessione e l'API Key funzionano correttamente.

## Compilazione dal Sorgente

Se desideri compilare il plugin autonomamente:

1. Clona o scarica la repository del codice sorgente.
2. Inserisci il file `HytaleServer.jar` all'interno della cartella `libs/`.
3. Apri un terminale nella cartella principale del progetto ed esegui `./gradlew build`.
4. Il plugin compilato sarà disponibile all'interno della cartella `build/libs/`.
