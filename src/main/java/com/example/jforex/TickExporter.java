package com.example.jforex;

import com.dukascopy.api.*;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.zip.GZIPOutputStream;

/**
 * Exports historical ticks to a CSV (optionally gzipped) file.
 *
 * All settings come from Config (env vars / CLI args) so the same jar can run
 * unchanged inside GitHub Actions.
 */
public class TickExporter implements IStrategy {

    private final Config config;
    private final CountDownLatch finished;

    private IHistory history;
    private IConsole console;
    private volatile boolean success = false;
    private volatile long totalTicks = 0;

    public TickExporter(Config config, CountDownLatch finished) {
        this.config = config;
        this.finished = finished;
    }

    public boolean isSuccess() {
        return success;
    }

    public long getTotalTicks() {
        return totalTicks;
    }

    @Override
    public void onStart(IContext context) throws JFException {
        history = context.getHistory();
        console = context.getConsole();

        PrintWriter out = null;
        try {
            // JForex timestamps are always GMT based
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
            fmt.setTimeZone(TimeZone.getTimeZone("GMT"));

            long from = config.getFromMillis();
            long to = config.getToMillis();
            Instrument instrument = config.getInstrument();

            // Second safety net: subscribe from inside the strategy thread using the
            // blocking overload, so history requests can never run against an
            // unsubscribed instrument (the cause of
            // "JFException: Instrument [BTC/USD] is not subscribed").
            context.setSubscribedInstruments(Collections.singleton(instrument), true);
            log("subscribed instruments: " + context.getSubscribedInstruments());

            out = openWriter();
            out.println("GmtTime,Bid,Ask,BidVolume,AskVolume");

            long cursor = from;
            long lastTickTime = -1;
            long total = 0;

            while (cursor < to) {
                long chunkEnd = Math.min(cursor + config.getChunkMillis(), to);

                List<ITick> ticks = getTicksWithRetry(instrument, cursor, chunkEnd);

                for (ITick t : ticks) {
                    if (t.getTime() <= lastTickTime) continue; // drop duplicates on chunk borders
                    lastTickTime = t.getTime();

                    out.println(fmt.format(new Date(t.getTime())) + ","
                            + t.getBid() + ","
                            + t.getAsk() + ","
                            + t.getBidVolume() + ","
                            + t.getAskVolume());
                    total++;
                }

                log("chunk: " + fmt.format(new Date(chunkEnd))
                        + " | ticks: " + ticks.size() + " | total: " + total);

                cursor = chunkEnd;
                Thread.sleep(config.getSleepMillis());
            }

            out.flush();
            totalTicks = total;
            success = true;
            if (total == 0) {
                log("WARNING: 0 ticks were returned. Check that the date range is covered by"
                        + " this instrument's history (crypto tick history starts years later"
                        + " than FX; the default DATE_FROM of 2010 returns nothing for BTC/USD).");
            }
            log("FINISHED. total ticks = " + total + " -> " + config.getOutFile());

        } catch (Exception e) {
            success = false;
            logErr("Error: " + e);
            e.printStackTrace();
        } finally {
            if (out != null) out.close();
            finished.countDown();
            context.stop();
        }
    }

    private PrintWriter openWriter() throws Exception {
        java.io.File file = new java.io.File(config.getOutFile());
        if (file.getParentFile() != null) {
            file.getParentFile().mkdirs();
        }
        java.io.OutputStream os = new FileOutputStream(file);
        if (config.isGzip()) {
            os = new GZIPOutputStream(os, 1 << 16);
        }
        Writer w = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8), 1 << 16);
        return new PrintWriter(w, false);
    }

    /** getTicks is synchronous but can fail transiently; retry a few times. */
    private List<ITick> getTicksWithRetry(Instrument instrument, long from, long to) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= config.getMaxRetries(); attempt++) {
            try {
                return history.getTicks(instrument, from, to);
            } catch (JFException e) {
                last = e;
                logErr("getTicks failed (attempt " + attempt + "): " + e);
                // Not-subscribed is a configuration problem, not a transient one:
                // retrying it only hides the real cause.
                if (String.valueOf(e.getMessage()).contains("not subscribed")) {
                    throw e;
                }
                Thread.sleep(2000L * attempt);
            } catch (Exception e) {
                last = e;
                logErr("getTicks failed (attempt " + attempt + "): " + e);
                Thread.sleep(2000L * attempt);
            }
        }
        throw last;
    }

    // FIX: در کلاینت standalone خروجی console.getOut() خودش به System.out می‌رود؛
    // چاپ هر دو باعث تکرار هر خط لاگ می‌شد. اکنون فقط یکی استفاده می‌شود.
    private void log(String msg) {
        if (console != null) {
            console.getOut().println(msg);
        } else {
            System.out.println(msg);
        }
    }

    private void logErr(String msg) {
        if (console != null) {
            console.getErr().println(msg);
        } else {
            System.err.println(msg);
        }
    }

    @Override public void onTick(Instrument instrument, ITick tick) {}
    @Override public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) {}
    @Override public void onMessage(IMessage message) {}
    @Override public void onAccount(IAccount account) {}
    @Override public void onStop() {}
}
