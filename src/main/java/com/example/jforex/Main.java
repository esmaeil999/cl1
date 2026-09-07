package com.example.jforex;

import com.dukascopy.api.IMessage;
import com.dukascopy.api.Instrument;
import com.dukascopy.api.system.ClientFactory;
import com.dukascopy.api.system.IClient;
import com.dukascopy.api.system.ISystemListener;

import java.util.HashSet;
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

        Set<Instrument> instruments = new HashSet<>();
        instruments.add(config.getInstrument());
        client.setSubscribedInstruments(instruments);
        System.out.println("Subscribed to " + config.getInstrument());
        Thread.sleep(3000);

        TickExporter strategy = new TickExporter(config, strategyFinished);
        client.startStrategy(strategy);

        long timeoutHours = Long.parseLong(
                System.getenv().getOrDefault("RUN_TIMEOUT_HOURS", "5"));
        boolean completed = strategyFinished.await(timeoutHours, TimeUnit.HOURS);

        if (!completed) {
            System.err.println("Timed out after " + timeoutHours + "h. Narrow the date range.");
        }

        try {
            client.disconnect();
        } catch (Exception ignored) {
        }

        boolean ok = completed && strategy.isSuccess();
        System.out.println(ok
                ? "DONE. ticks=" + strategy.getTotalTicks() + " file=" + config.getOutFile()
                : "FAILED.");
        System.exit(ok ? 0 : 1);
    }

    @SuppressWarnings("unused")
    private static String describe(IMessage m) {
        return m == null ? "" : m.toString();
    }
}
