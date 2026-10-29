package com.vcrd.component;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Scans with a ClamAV daemon (clamd) using its INSTREAM command over TCP:
 * "zINSTREAM\0", then chunks of [4-byte big-endian length][bytes], then a zero length.
 * clamd answers "stream: OK" or "stream: &lt;threat&gt; FOUND". Fails closed: any error refuses the upload.
 */
@Component
@ConditionalOnProperty(name = "vcrd.malware.mode", havingValue = "clamav")
public class ClamAvScanner implements MalwareScanner {

    private static final int CHUNK = 8192;

    private final String host;
    private final int port;
    private final int timeoutMillis;

    public ClamAvScanner(@Value("${vcrd.malware.clamav.host:localhost}") String host,
            @Value("${vcrd.malware.clamav.port:3310}") int port,
            @Value("${vcrd.malware.clamav.timeout:PT30S}") Duration timeout) {
        this.host = host;
        this.port = port;
        this.timeoutMillis = (int) timeout.toMillis();
    }

    @Override
    public String findThreat(byte[] content) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            for (int offset = 0; offset < content.length; offset += CHUNK) {
                int length = Math.min(CHUNK, content.length - offset);
                out.writeInt(length);
                out.write(content, offset, length);
            }
            out.writeInt(0);
            out.flush();

            String reply = readReply(socket.getInputStream());
            if (reply.endsWith("OK")) {
                return null;
            }
            if (reply.endsWith("FOUND")) {
                return reply.substring(reply.indexOf(':') + 1, reply.length() - "FOUND".length()).strip();
            }
            throw new ScanUnavailableException("Unexpected ClamAV reply: " + reply, null);
        } catch (IOException e) {
            throw new ScanUnavailableException("ClamAV at " + host + ":" + port + " is unreachable", e);
        }
    }

    private static String readReply(InputStream in) throws IOException {
        ByteArrayOutputStream reply = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) > 0) { // reply is terminated by \0 (or the connection closing)
            reply.write(b);
        }
        return reply.toString(StandardCharsets.US_ASCII).strip();
    }
}
