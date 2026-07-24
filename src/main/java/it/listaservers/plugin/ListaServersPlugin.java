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
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ListaServersPlugin extends JavaPlugin {

    private static final String API_URL = "https://listaservers.it/api/v1/servers/ping";
    private static final String CONFIG_PATH = "mods/ListaServers/config.json";
    private static final String DEFAULT_KEY = "INSERISCI_QUI_LA_TUA_API_KEY";

    private static final int UPDATE_INTERVAL_SECONDS = 60;
    private static final int INITIAL_DELAY_SECONDS = 10;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient httpClient;
    private ScheduledExecutorService scheduler;
    private String apiKey = DEFAULT_KEY;
    private String serverVersion = "1.0.0";

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

        log("Plugin avviato con successo! Connesso a ListaServers.it");
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
              "api_key": "%s",
              "server_version": "1.0.0"
            }
            """.formatted(DEFAULT_KEY);
        Files.writeString(file.toPath(), config, StandardCharsets.UTF_8);
        log("File config.json creato in " + CONFIG_PATH);
        log("Inserisci la tua API Key di ListaServers per attivare il plugin.");
    }

    private void parseConfig(String content) {
        Pattern pattern = Pattern.compile("\"api_key\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(content);

        if (matcher.find()) {
            String key = matcher.group(1).trim();
            if (!key.isEmpty() && !key.equals(DEFAULT_KEY)) {
                apiKey = key;
                log("API Key caricata con successo.");
            } else if (key.equals(DEFAULT_KEY)) {
                log("ATTENZIONE: API Key non configurata! Il plugin non invierà dati.");
            }
        } else {
            logError("Formato config.json non valido!");
        }

        Pattern versionPattern = Pattern.compile("\"server_version\"\\s*:\\s*\"([^\"]+)\"");
        Matcher versionMatcher = versionPattern.matcher(content);
        if (versionMatcher.find()) {
            serverVersion = versionMatcher.group(1).trim();
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

        java.util.Collection<com.hypixel.hytale.server.core.universe.PlayerRef> players = universe.getPlayers();
        lastPlayerCount = players.size();

        String json = buildJsonPayload(players);
        HttpRequest request = buildRequest(json);

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .orTimeout(HTTP_TIMEOUT.toSeconds(), TimeUnit.SECONDS)
                .thenAccept(this::handleResponse)
                .exceptionally(this::handleError);
    }

    public CompletableFuture<Boolean> sendUpdateAsync() {
        if (!isConfigured()) {
            return CompletableFuture.completedFuture(false);
        }

        Universe universe = Universe.get();
        if (universe == null) {
            return CompletableFuture.completedFuture(false);
        }

        java.util.Collection<com.hypixel.hytale.server.core.universe.PlayerRef> players = universe.getPlayers();
        lastPlayerCount = players.size();

        String json = buildJsonPayload(players);
        HttpRequest request = buildRequest(json);

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .orTimeout(HTTP_TIMEOUT.toSeconds(), TimeUnit.SECONDS)
                .thenApply(response -> {
                    handleResponse(response);
                    return response.statusCode() >= 200 && response.statusCode() < 300;
                })
                .exceptionally(e -> {
                    handleError(e);
                    return false;
                });
    }

    private boolean isConfigured() {
        return apiKey != null && !apiKey.isEmpty() && !apiKey.equals(DEFAULT_KEY);
    }

    private String buildJsonPayload(java.util.Collection<com.hypixel.hytale.server.core.universe.PlayerRef> players) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"apiKey\":\"").append(escapeJson(apiKey))
          .append("\",\"serverVersion\":\"").append(escapeJson(serverVersion))
          .append("\",\"players\":[");
        boolean first = true;
        for (com.hypixel.hytale.server.core.universe.PlayerRef p : players) {
            if (!first) sb.append(",");
            sb.append("{\"uuid\":\"").append(p.getUuid().toString())
              .append("\",\"name\":\"").append(escapeJson(p.getUsername())).append("\"}");
            first = false;
        }
        sb.append("]}");
        return sb.toString();
    }

    private HttpRequest buildRequest(String json) {
        return HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", "ListaServers-Plugin/1.0.0")
                .timeout(HTTP_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
    }

    private void handleResponse(HttpResponse<String> response) {
        int status = response.statusCode();

        if (status >= 200 && status < 300) {
            lastSuccessfulUpdate = Instant.now();
            consecutiveFailures = 0;
            lastErrorMessage = null;
        } else {
            consecutiveFailures++;
            lastErrorMessage = "HTTP " + status + ": " + truncate(response.body(), 100);
            logError("Risposta API: " + lastErrorMessage);
        }
    }

    private Void handleError(Throwable e) {
        consecutiveFailures++;
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        lastErrorMessage = cause.getClass().getSimpleName() + ": " + cause.getMessage();
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
                    "Azione: reload | status | ping",
                    ArgTypes.STRING
            );
        }

        @Override
        protected void executeSync(@Nonnull CommandContext ctx) {
            String action = ctx.get(actionArg).toLowerCase();

            switch (action) {
                case "reload" -> handleReload(ctx);
                case "status" -> handleStatus(ctx);
                case "ping" -> handleTest(ctx);
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
                        send(ctx, "Ping inviato con successo a ListaServers.it!");
                    } else {
                        send(ctx, "Errore nell'invio. Controlla la console.");
                    }
                });
            } else {
                send(ctx, "API Key non configurata! Inseriscila nel config.json");
            }
        }

        private void handleStatus(CommandContext ctx) {
            send(ctx, "═══ ListaServers Status ═══");
            send(ctx, "API Key: " + (isConfigured() ? "Configurata" : "Non configurata"));
            send(ctx, "Giocatori: " + lastPlayerCount);

            if (lastSuccessfulUpdate != null) {
                long secsAgo = Duration.between(lastSuccessfulUpdate, Instant.now()).toSeconds();
                send(ctx, "Ultimo ping: " + secsAgo + "s fa");
            } else {
                send(ctx, "Ultimo ping: Mai");
            }

            if (consecutiveFailures > 0) {
                send(ctx, "Errori consecutivi: " + consecutiveFailures);
                if (lastErrorMessage != null) {
                    send(ctx, "Ultimo errore: " + lastErrorMessage);
                }
            } else {
                send(ctx, "Stato: Operativo e Connesso");
            }
        }

        private void handleTest(CommandContext ctx) {
            if (!isConfigured()) {
                send(ctx, "API Key non configurata!");
                return;
            }

            send(ctx, "Invio richiesta di ping forzato...");
            sendUpdateAsync().thenAccept(success -> {
                if (success) {
                    send(ctx, "Ping completato con successo!");
                } else {
                    send(ctx, "Ping fallito: " + (lastErrorMessage != null ? lastErrorMessage : "Errore sconosciuto"));
                }
            });
        }

        private void sendHelp(CommandContext ctx) {
            send(ctx, "═══ ListaServers Comandi ═══");
            send(ctx, "/listaservers reload - Ricarica la configurazione dal file config.json");
            send(ctx, "/listaservers status - Mostra lo stato di connessione con ListaServers.it");
            send(ctx, "/listaservers ping - Forza l'invio immediato dei dati al sito");
        }

        private void send(CommandContext ctx, String msg) {
            ctx.sendMessage(Message.raw(msg));
        }
    }
}
