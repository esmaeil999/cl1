package com.example.jforex;

import com.dukascopy.api.IMessage;
import com.dukascopy.api.Instrument;
import com.dukascopy.api.system.ClientFactory;
import com.dukascopy.api.system.IClient;
import com.dukascopy.api.system.ISystemListener;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Headless runner: connects to the Dukascopy JForex standalone API,
 * starts TickExporter, waits for it to finish, then exits.
 *
 * Exit code 0 = export finished successfully, 1 = failure.
 */
public class Main {

    /** How long we wait for the server to confirm the instrument subscription. */
    private static final int SUBSCRIBE_TIMEOUT_SECONDS = 30;

    /**
     * Dukascopy sits behind Cloudflare and answers 403/404 to some clients
     * (the default "Java/x.y" user agent, datacenter IP ranges). The JDK turns
     * an HTTP 404 on the JNLP descriptor into a bare
     * "java.io.FileNotFoundException: https://...jforex.jnlp", so we send a
     * browser-like UA and log the real status code.
     */
    private static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    public static void main(String[] args) throws Exception {
        Config config = Config.fromEnv();

        String userAgent = System.getenv("HTTP_USER_AGENT");
        if (userAgent == null || userAgent.trim().isEmpty()) {
            userAgent = DEFAULT_USER_AGENT;
        }
        System.setProperty("http.agent", userAgent);

        final CountDownLatch strategyFinished = new CountDownLatch(1);
        final IClient client = ClientFactory.getDefaultInstance();

        client.setSystemListener(new ISystemListener() {
            @Override public void onStart(long processId) {
                System.out.println("Strategy started, processId=" + processId);
            }
            @Override public void onStop(long processId) {
                System.out.println("Strategy stopped, processId=" + processId);
                strategyFinished.countDown();
            }
            @Override public void onConnect() {
                System.out.println("Connected to Dukascopy.");
            }
            @Override public void onDisconnect() {
                System.out.println("Disconnected from Dukascopy.");
            }
        });

        if (!connectWithRetry(client, config)) {
            System.err.println("Failed to connect to Dukascopy.");
            System.err.println("If the JNLP probe above reported 403 or 404, the descriptor is");
            System.err.println("reachable but this host is being blocked (common for CI and other");
            System.err.println("datacenter IPs) - run from a different network, or override the URL");
            System.err.println("with DUKASCOPY_JNLP / DUKASCOPY_JNLP_FALLBACKS.");
            System.err.println("If it reported 200, the credentials or account type are wrong.");
            System.exit(1);
        }

        final Instrument instrument = config.getInstrument();

        // 1) Is this instrument allowed for this account at all?
        //    Crypto pairs (BTC/USD, ETH/USD, ...) are not enabled on every demo
        //    account group. setSubscribedInstruments() silently drops instruments
        //    the account is not entitled to, which used to show up much later as
        //    "Instrument [BTC/USD] is not subscribed" inside getTicks().
        Set<Instrument> available = client.getAvailableInstruments();
        if (available != null && !available.isEmpty() && !available.contains(instrument)) {
            System.err.println("Instrument " + instrument + " is NOT available for this account.");
            System.err.println("Ask Dukascopy to enable it, or pick another INSTRUMENT.");
            System.err.println("Crypto instruments available on this account: " + cryptoLike(available));
            safeDisconnect(client);
            System.exit(1);
        }

        // 2) Subscribe and actually wait for the server to confirm it.
        client.setSubscribedInstruments(Collections.singleton(instrument));

        boolean subscribed = false;
        for (int i = 0; i < SUBSCRIBE_TIMEOUT_SECONDS * 2; i++) {
            Set<Instrument> current = client.getSubscribedInstruments();
            if (current != null && current.contains(instrument)) {
                subscribed = true;
                break;
            }
            Thread.sleep(500);
        }

        if (!subscribed) {
            System.err.println("Subscription to " + instrument + " was not confirmed within "
                    + SUBSCRIBE_TIMEOUT_SECONDS + "s. Server reported: "
                    + client.getSubscribedInstruments());
            safeDisconnect(client);
            System.exit(1);
        }
        System.out.println("Subscription confirmed: " + client.getSubscribedInstruments());

        TickExporter strategy = new TickExporter(config, strategyFinished);
        client.startStrategy(strategy);

        long timeoutHours = Long.parseLong(
                System.getenv().getOrDefault("RUN_TIMEOUT_HOURS", "5"));
        boolean completed = strategyFinished.await(timeoutHours, TimeUnit.HOURS);

        if (!completed) {
            System.err.println("Timed out after " + timeoutHours + "h. Narrow the date range.");
        }

        safeDisconnect(client);

        boolean ok = completed && strategy.isSuccess();
        System.out.println(ok
                ? "DONE. ticks=" + strategy.getTotalTicks() + " file=" + config.getOutFile()
                : "FAILED.");
        System.exit(ok ? 0 : 1);
    }

    /**
     * Tries every candidate JNLP URL, a few times, with backoff. The descriptor
     * download is the single most fragile step of the whole run.
     */
    private static boolean connectWithRetry(IClient client, Config config) throws InterruptedException {
        List<String> candidates = jnlpCandidates(config);
        int maxAttempts = Integer.parseInt(
                System.getenv().getOrDefault("CONNECT_RETRIES", "5"));

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            for (String jnlp : candidates) {
                System.out.println("Connecting to " + jnlp
                        + " (attempt " + attempt + "/" + maxAttempts + ") ...");

                int status = probe(jnlp);
                if (status > 0) {
                    System.out.println("  JNLP probe HTTP status: " + status);
                }

                try {
                    client.connect(jnlp, config.getUsername(), config.getPassword());
                } catch (Exception e) {
                    System.err.println("  connect() failed: " + e);
                    continue;
                }

                int waited = 0;
                while (!client.isConnected() && waited < 60) {
                    Thread.sleep(1000);
                    waited++;
                }
                if (client.isConnected()) {
                    return true;
                }
                System.err.println("  not connected within 60s");
            }

            if (attempt < maxAttempts) {
                long backoff = 15_000L * attempt;
                System.err.println("Retrying in " + (backoff / 1000) + "s ...");
                Thread.sleep(backoff);
            }
        }
        return false;
    }

    /** Primary JNLP URL plus optional comma-separated DUKASCOPY_JNLP_FALLBACKS. */
    private static List<String> jnlpCandidates(Config config) {
        Set<String> urls = new LinkedHashSet<>();
        urls.add(config.getJnlpUrl());
        String extra = System.getenv("DUKASCOPY_JNLP_FALLBACKS");
        if (extra != null) {
            for (String candidate : extra.split(",")) {
                String trimmed = candidate.trim();
                if (!trimmed.isEmpty()) {
                    urls.add(trimmed);
                }
            }
        }
        return new ArrayList<>(urls);
    }

    /** Returns the HTTP status of the JNLP descriptor, or -1 if unreachable. */
    private static int probe(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(15_000);
            conn.setRequestProperty("User-Agent", System.getProperty("http.agent"));
            conn.setRequestProperty("Accept", "*/*");
            return conn.getResponseCode();
        } catch (IOException e) {
            System.err.println("  JNLP probe failed: " + e);
            return -1;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** Best-effort list of crypto instruments, to make the error message actionable. */
    private static List<String> cryptoLike(Set<Instrument> available) {
        String[] tokens = {"BTC", "ETH", "LTC", "XRP", "BCH", "ADA", "SOL", "DOT", "MATIC", "AVAX"};
        List<String> found = new ArrayList<>();
        for (Instrument i : available) {
            String name = i.toString();
            for (String token : tokens) {
                if (name.contains(token)) {
                    found.add(name);
                    break;
                }
            }
        }
        Collections.sort(found);
        return found;
    }

    private static void safeDisconnect(IClient client) {
        try {
            client.disconnect();
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("unused")
    private static String describe(IMessage m) {
        return m == null ? "" : m.toString();
    }
}
