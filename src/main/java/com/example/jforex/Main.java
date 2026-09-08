package com.example.jforex;

import com.dukascopy.api.IMessage;
import com.dukascopy.api.Instrument;
import com.dukascopy.api.system.ClientFactory;
import com.dukascopy.api.system.IClient;
import com.dukascopy.api.system.ISystemListener;

import java.util.ArrayList;
import java.util.Collections;
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

    public static void main(String[] args) throws Exception {
        Config config = Config.fromEnv();

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

        System.out.println("Connecting to " + config.getJnlpUrl() + " ...");
        client.connect(config.getJnlpUrl(), config.getUsername(), config.getPassword());

        int waited = 0;
        while (!client.isConnected() && waited < 60) {
            Thread.sleep(1000);
            waited++;
        }
        if (!client.isConnected()) {
            System.err.println("Failed to connect within 60 seconds. Check credentials/account type.");
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
