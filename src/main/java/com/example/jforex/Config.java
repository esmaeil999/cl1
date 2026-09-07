package com.example.jforex;

import com.dukascopy.api.Instrument;

import java.text.SimpleDateFormat;
import java.util.TimeZone;

/**
 * All runtime settings, read from environment variables (with defaults).
 * This keeps credentials and date ranges out of the source code.
 */
public class Config {

    private final String username;
    private final String password;
    private final String jnlpUrl;
    private final Instrument instrument;
    private final long fromMillis;
    private final long toMillis;
    private final String outFile;
    private final long chunkMillis;
    private final long sleepMillis;
    private final int maxRetries;
    private final boolean gzip;

    private Config(String username, String password, String jnlpUrl, Instrument instrument,
                   long fromMillis, long toMillis, String outFile, long chunkMillis,
                   long sleepMillis, int maxRetries, boolean gzip) {
        this.username = username;
        this.password = password;
        this.jnlpUrl = jnlpUrl;
        this.instrument = instrument;
        this.fromMillis = fromMillis;
        this.toMillis = toMillis;
        this.outFile = outFile;
        this.chunkMillis = chunkMillis;
        this.sleepMillis = sleepMillis;
        this.maxRetries = maxRetries;
        this.gzip = gzip;
    }

    public static Config fromEnv() throws Exception {
        String username = required("DUKASCOPY_USERNAME");
        String password = required("DUKASCOPY_PASSWORD");

        String account = env("DUKASCOPY_ACCOUNT_TYPE", "DEMO").trim().toUpperCase();
        String jnlp = env("DUKASCOPY_JNLP", "").trim();
        if (jnlp.isEmpty()) {
            jnlp = "LIVE".equals(account)
                    ? "https://www.dukascopy.com/client/live/jclient/jforex.jnlp"
                    : "https://www.dukascopy.com/client/demo/jclient/jforex.jnlp";
        }

        String instrumentName = env("INSTRUMENT", "EURUSD").trim().toUpperCase();
        Instrument instrument = Instrument.valueOf(instrumentName);

        SimpleDateFormat parser = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        parser.setTimeZone(TimeZone.getTimeZone("GMT"));
        long from = parser.parse(env("DATE_FROM", "2010-01-01 00:00:00")).getTime();
        long to = parser.parse(env("DATE_TO", "2010-02-01 00:00:00")).getTime();
        if (to <= from) {
            throw new IllegalArgumentException("DATE_TO must be after DATE_FROM");
        }

        boolean gzip = Boolean.parseBoolean(env("GZIP", "true"));
        String defaultName = "out/" + instrumentName + "_ticks_"
                + env("DATE_FROM", "2010-01-01 00:00:00").substring(0, 10) + "_"
                + env("DATE_TO", "2010-02-01 00:00:00").substring(0, 10)
                + ".csv" + (gzip ? ".gz" : "");
        String outFile = env("OUT_FILE", defaultName);

        long chunkHours = Long.parseLong(env("CHUNK_HOURS", "6"));
        long sleepMillis = Long.parseLong(env("SLEEP_MS", "500"));
        int maxRetries = Integer.parseInt(env("MAX_RETRIES", "5"));

        return new Config(username, password, jnlp, instrument, from, to, outFile,
                chunkHours * 60 * 60 * 1000L, sleepMillis, maxRetries, gzip);
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        if (v == null || v.trim().isEmpty()) {
            v = System.getProperty(key);
        }
        return (v == null || v.trim().isEmpty()) ? fallback : v;
    }

    private static String required(String key) {
        String v = env(key, "");
        if (v.isEmpty()) {
            throw new IllegalStateException("Missing required environment variable: " + key);
        }
        return v;
    }

    public String getUsername() { return username; }
    public String getPassword() { return password; }
    public String getJnlpUrl() { return jnlpUrl; }
    public Instrument getInstrument() { return instrument; }
    public long getFromMillis() { return fromMillis; }
    public long getToMillis() { return toMillis; }
    public String getOutFile() { return outFile; }
    public long getChunkMillis() { return chunkMillis; }
    public long getSleepMillis() { return sleepMillis; }
    public int getMaxRetries() { return maxRetries; }
    public boolean isGzip() { return gzip; }
}
