package com.gathin.tvhome;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Plain HTTPS GETs for the glass home (series art, weather), always off the main thread. */
final class Net {
    /** One thread for everything that touches the network, so a slow request never piles up others. */
    static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "glass-net");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    private Net() {
    }

    static byte[] get(String url, String bearer) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(15_000);
        c.setRequestProperty("Accept", "application/json, image/*");
        c.setRequestProperty("User-Agent", "TvHome-Glass/1.0");
        if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code + " for " + url.replaceAll("api_key=[^&]+", "api_key=…"));
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > MAX_BYTES) throw new IOException("too large: " + url);
                }
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    static String getText(String url, String bearer) throws IOException {
        return new String(get(url, bearer), StandardCharsets.UTF_8);
    }
}
