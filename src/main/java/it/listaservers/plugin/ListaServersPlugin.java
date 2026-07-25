package it.listaservers.plugin;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.Universe;

import javax.annotation.Nonnull;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.List;

public class ListaServersPlugin extends JavaPlugin {

    private static class EndpointConfig {
        String url;
        String apiKey;
        EndpointConfig(String url, String apiKey) {
            this.url = url;
            this.apiKey = apiKey;
        }
    }

    private static final String DEFAULT_ENDPOINT = "https://listaservers.it/api/v1/servers/ping";
    private static final String CONFIG_PATH = "mods/ListaServers/config.json";
    private static final String DEFAULT_KEY = "INSERISCI_QUI_LA_TUA_CHIAVE";

    private static final int UPDATE_INTERVAL_SECONDS = 60;
    private static final int INITIAL_DELAY_SECONDS = 10;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_RETRY_ATTEMPTS = 3;

    private final HttpClient httpClient;
    private ScheduledExecutorService scheduler;
    private final String nodeId = UUID.randomUUID().toString();
    private List<EndpointConfig> endpoints = new ArrayList<>();

    private volatile int lastPlayerCount = 0;
    private volatile Instant lastSuccessfulUpdate = null;
    private volatile int consecutiveFailures = 0;
    private volatile String lastErrorMessage = null;

    public ListaServersPlugin(@Nonnull JavaPluginInit init) {
        super(init);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .executor(Executors.newCachedThreadPool(r -> {
                    Thread t = new Thread(r, "ListaServers-HTTP");
                    t.setDaemon(true);
                    return t;
                }))
                .build();
    }

    @Override
    protected void setup() {
        loadConfig();
        startScheduler();
        registerCommands();

        log("Plugin avviato con successo!");
        log("Node ID: " + nodeId);
        log("Intervallo aggiornamento: " + UPDATE_INTERVAL_SECONDS + "s");
    }

    @Override
    protected void shutdown() {
        stopScheduler();
        log("Plugin disabilitato.");
    }

    private void startScheduler() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ListaServers-Scheduler");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(
                this::sendUpdateSafe,
                INITIAL_DELAY_SECONDS,
                UPDATE_INTERVAL_SECONDS,
                TimeUnit.SECONDS
        );
    }

    private void stopScheduler() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private void registerCommands() {
        getCommandRegistry().registerCommand(new ListaServersCommand());
    }

    public void loadConfig() {
        File file = new File(CONFIG_PATH);
        try {
            if (!file.exists()) {
                createDefaultConfig(file);
                return;
            }

            String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            parseConfig(content);

        } catch (Exception e) {
            logError("Errore caricamento config: " + e.getMessage());
        }
    }

    private void createDefaultConfig(File file) throws Exception {
        file.getParentFile().mkdirs();
        String config = """
            {
              // ==========================================================
              // COME INVIARE LE STATISTICHE A PIÙ LISTE SERVER
              // ==========================================================
              // Se vuoi inviare le statistiche anche ad un'altra lista server italiana,
              // ti basta copiare e incollare il blocco qui sotto (aggiungendo una virgola).
              // 
              // ESEMPIO:
              // "endpoints": [
              //   {
              //     "url": "https://listaservers.it/api/v1/servers/ping",
              //     "api_key": "LA_TUA_CHIAVE"
              //   },
              //   {
              //     "url": "https://altra-lista.com/api/ping",
              //     "api_key": "CHIAVE_ALTRA_LISTA"
              //   }
              // ]
              
              "endpoints": [
                {
                  "url": "%s",
                  "api_key": "%s"
                }
              ]
            }
            """.formatted(DEFAULT_ENDPOINT, DEFAULT_KEY);
        Files.writeString(file.toPath(), config, StandardCharsets.UTF_8);
        
        log("File config.json creato in " + CONFIG_PATH);
        log("Inserisci la tua API Key per attivare il plugin.");
    }

    private void parseConfig(String content) {
        endpoints.clear();
        
        // Rimuove i commenti (linee che iniziano con //) per evitare che il regex legga gli esempi
        content = content.replaceAll("(?m)^\\s*//.*$", "");
        
        Pattern oldKeyPattern = Pattern.compile("\"api_key\"\\s*:\\s*\"([^\"]+)\"");
        Matcher oldKeyMatcher = oldKeyPattern.matcher(content);
        boolean hasOldRootKey = false;
        
        if (!content.contains("\"endpoints\"") && oldKeyMatcher.find()) {
            hasOldRootKey = true;
            String oldKey = oldKeyMatcher.group(1).trim();
            log("Rilevato config nel vecchio formato. Migrazione in corso...");
            
            endpoints.add(new EndpointConfig(DEFAULT_ENDPOINT, oldKey));
            
            String migratedConfig = """
                {
                  // ==========================================================
                  // COME INVIARE LE STATISTICHE A PIÙ LISTE SERVER
                  // ==========================================================
                  // Se vuoi inviare le statistiche anche ad un'altra lista server italiana,
                  // ti basta copiare e incollare il blocco qui sotto (aggiungendo una virgola).
                  // 
                  // ESEMPIO:
                  // "endpoints": [
                  //   {
                  //     "url": "https://listaservers.it/api/v1/servers/ping",
                  //     "api_key": "LA_TUA_CHIAVE"
                  //   },
                  //   {
                  //     "url": "https://altra-lista.com/api/ping",
                  //     "api_key": "CHIAVE_ALTRA_LISTA"
                  //   }
                  // ]
                  
                  "endpoints": [
                    {
                      "url": "%s",
                      "api_key": "%s"
                    }
                  ]
                }
                """.formatted(DEFAULT_ENDPOINT, oldKey);
            try {
                Files.writeString(new File(CONFIG_PATH).toPath(), migratedConfig, StandardCharsets.UTF_8);
                log("Migrazione completata con successo!");
            } catch (IOException e) {
                logError("Impossibile salvare il config migrato: " + e.getMessage());
            }
            return;
        }

        Pattern endpointPattern = Pattern.compile("\"endpoints\"\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL);
        Matcher endpointMatcher = endpointPattern.matcher(content);
        if (endpointMatcher.find()) {
            String arrayContent = endpointMatcher.group(1);
            
            Pattern objectPattern = Pattern.compile("\\{(.*?)\\}", Pattern.DOTALL);
            Matcher objectMatcher = objectPattern.matcher(arrayContent);
            
            while (objectMatcher.find()) {
                String objContent = objectMatcher.group(1);
                
                Pattern urlPattern = Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"");
                Matcher urlMatcher = urlPattern.matcher(objContent);
                
                Pattern keyPattern = Pattern.compile("\"api_key\"\\s*:\\s*\"([^\"]+)\"");
                Matcher keyMatcher = keyPattern.matcher(objContent);
                
                if (urlMatcher.find() && keyMatcher.find()) {
                    String url = urlMatcher.group(1).trim();
                    String key = keyMatcher.group(1).trim();
                    if (!url.isEmpty() && !key.isEmpty()) {
                        endpoints.add(new EndpointConfig(url, key));
                    }
                }
            }
        }

        if (endpoints.isEmpty()) {
            endpoints.add(new EndpointConfig(DEFAULT_ENDPOINT, DEFAULT_KEY));
            log("Nessun endpoint valido trovato nel config, utilizzo quello di default.");
        } else {
            log("Trovati " + endpoints.size() + " endpoints nel config.");
        }
    }

    private void sendUpdateSafe() {
        try {
            sendUpdate();
        } catch (Exception e) {
            consecutiveFailures++;
            lastErrorMessage = e.getMessage();
            logError("Errore invio aggiornamento: " + e.getMessage());
        }
    }

    private void sendUpdate() {
        if (!isConfigured()) return;

        Universe universe = Universe.get();
        if (universe == null) return;

        int players = universe.getPlayers().size();
        lastPlayerCount = players;

        String playersJsonArray = buildPlayersJson(universe);

        for (EndpointConfig endpoint : endpoints) {
            if (endpoint.apiKey.equals(DEFAULT_KEY)) continue;
            
            String json = buildJsonPayload(playersJsonArray, endpoint.apiKey);
            HttpRequest request = buildRequest(json, endpoint.url);
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .orTimeout(HTTP_TIMEOUT.toSeconds(), TimeUnit.SECONDS)
                    .thenAccept(response -> handleResponse(response, endpoint.url))
                    .exceptionally(e -> handleError(e, endpoint.url));
        }
    }

    public CompletableFuture<Boolean> sendUpdateAsync() {
        if (!isConfigured()) {
            return CompletableFuture.completedFuture(false);
        }

        Universe universe = Universe.get();
        if (universe == null) {
            return CompletableFuture.completedFuture(false);
        }

        int players = universe.getPlayers().size();
        lastPlayerCount = players;

        String playersJsonArray = buildPlayersJson(universe);
        
        CompletableFuture<Boolean> overallFuture = CompletableFuture.completedFuture(true);

        for (EndpointConfig endpoint : endpoints) {
            if (endpoint.apiKey.equals(DEFAULT_KEY)) continue;
            
            String json = buildJsonPayload(playersJsonArray, endpoint.apiKey);
            HttpRequest request = buildRequest(json, endpoint.url);
            CompletableFuture<Boolean> reqFuture = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .orTimeout(HTTP_TIMEOUT.toSeconds(), TimeUnit.SECONDS)
                    .thenApply(response -> {
                        handleResponse(response, endpoint.url);
                        return response.statusCode() >= 200 && response.statusCode() < 300;
                    })
                    .exceptionally(e -> {
                        handleError(e, endpoint.url);
                        return false;
                    });
            
            overallFuture = overallFuture.thenCombine(reqFuture, (a, b) -> a && b);
        }

        return overallFuture;
    }

    private boolean isConfigured() {
        for (EndpointConfig ep : endpoints) {
            if (!ep.apiKey.equals(DEFAULT_KEY)) return true;
        }
        return false;
    }

    private String buildPlayersJson(Universe universe) {
        StringBuilder playersJson = new StringBuilder("[");
        boolean first = true;
        try {
            for (Object playerObj : universe.getPlayers()) {
                if (!first) playersJson.append(",");
                first = false;
                String name = "";
                String uuid = "";
                try {
                    name = (String) playerObj.getClass().getMethod("getName").invoke(playerObj);
                    uuid = playerObj.getClass().getMethod("getUuid").invoke(playerObj).toString();
                } catch (Exception e) {
                    name = "Unknown";
                    uuid = UUID.randomUUID().toString();
                }
                playersJson.append(String.format("{\"uuid\":\"%s\",\"name\":\"%s\"}", escapeJson(uuid), escapeJson(name)));
            }
        } catch (Exception ignored) {}
        playersJson.append("]");
        return playersJson.toString();
    }

    private String buildJsonPayload(String playersJsonArray, String targetApiKey) {
        return String.format(
                "{\"api_key\":\"%s\",\"serverVersion\":\"Hytale\",\"players\":%s}",
                escapeJson(targetApiKey),
                playersJsonArray
        );
    }

    private HttpRequest buildRequest(String json, String endpoint) {
        return HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", "ListaServers-Plugin/1.0.0")
                .timeout(HTTP_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
    }

    private void handleResponse(HttpResponse<String> response, String endpoint) {
        int status = response.statusCode();

        if (status >= 200 && status < 300) {
            lastSuccessfulUpdate = Instant.now();
            consecutiveFailures = 0;
            lastErrorMessage = null;
        } else {
            consecutiveFailures++;
            lastErrorMessage = "HTTP " + status + " da " + endpoint + ": " + truncate(response.body(), 100);
            logError("Risposta API: " + lastErrorMessage);
        }
    }

    private Void handleError(Throwable e, String endpoint) {
        consecutiveFailures++;
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        lastErrorMessage = cause.getClass().getSimpleName() + " verso " + endpoint + ": " + cause.getMessage();
        logError("Errore comunicazione: " + lastErrorMessage);
        return null;
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    private void log(String message) {
        System.out.println("[ListaServers] " + message);
    }

    private void logError(String message) {
        System.err.println("[ListaServers] ERROR: " + message);
    }

    public class ListaServersCommand extends CommandBase {

        private final RequiredArg<String> actionArg;

        public ListaServersCommand() {
            super("listaservers", "Gestione plugin ListaServers.it");
            this.actionArg = withRequiredArg(
                    "action",
                    "Azione: reload | status | test",
                    ArgTypes.STRING
            );
        }

        @Override
        protected void executeSync(@Nonnull CommandContext ctx) {
            String action = ctx.get(actionArg).toLowerCase();

            switch (action) {
                case "reload" -> handleReload(ctx);
                case "status" -> handleStatus(ctx);
                case "test" -> handleTest(ctx);
                default -> sendHelp(ctx);
            }
        }

        private void handleReload(CommandContext ctx) {
            loadConfig();
            send(ctx, "Configurazione ricaricata!");

            if (isConfigured()) {
                send(ctx, "Invio aggiornamento di test...");
                sendUpdateAsync().thenAccept(success -> {
                    if (success) {
                        send(ctx, "Aggiornamento inviato con successo!");
                    } else {
                        send(ctx, "Errore nell'invio. Controlla la console.");
                    }
                });
            } else {
                send(ctx, "API Key non configurata!");
            }
        }

        private void handleStatus(CommandContext ctx) {
            send(ctx, "═══ ListaServers Status ═══");
            send(ctx, "Node ID: " + nodeId);
            send(ctx, "API Key: " + (isConfigured() ? "Configurata" : "Non configurata"));
            send(ctx, "Giocatori: " + lastPlayerCount);

            if (lastSuccessfulUpdate != null) {
                long secsAgo = Duration.between(lastSuccessfulUpdate, Instant.now()).toSeconds();
                send(ctx, "Ultimo sync: " + secsAgo + "s fa");
            } else {
                send(ctx, "Ultimo sync: Mai");
            }

            if (consecutiveFailures > 0) {
                send(ctx, "Errori consecutivi: " + consecutiveFailures);
                if (lastErrorMessage != null) {
                    send(ctx, "Ultimo errore: " + lastErrorMessage);
                }
            } else {
                send(ctx, "Stato: Operativo");
            }
        }

        private void handleTest(CommandContext ctx) {
            if (!isConfigured()) {
                send(ctx, "API Key non configurata!");
                return;
            }

            send(ctx, "Invio richiesta di test...");
            sendUpdateAsync().thenAccept(success -> {
                if (success) {
                    send(ctx, "Test completato con successo!");
                } else {
                    send(ctx, "Test fallito: " + (lastErrorMessage != null ? lastErrorMessage : "Errore sconosciuto"));
                }
            });
        }

        private void sendHelp(CommandContext ctx) {
            send(ctx, "═══ ListaServers Comandi ═══");
            send(ctx, "/listaservers reload - Ricarica la configurazione");
            send(ctx, "/listaservers status - Mostra lo stato del plugin");
            send(ctx, "/listaservers test - Testa la connessione API");
        }

        private void send(CommandContext ctx, String msg) {
            ctx.sendMessage(Message.raw(msg));
        }
    }
}