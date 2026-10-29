package com.vcrd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import com.vcrd.component.ClamAvScanner;
import com.vcrd.component.MalwareScanner.ScanUnavailableException;

/** Exercises the clamd INSTREAM protocol against a fake clamd. */
class ClamAvScannerTest {

    @Test
    void cleanFileReturnsNoThreat() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<byte[]> received = fakeClamd(server, "stream: OK\0");
            ClamAvScanner scanner = new ClamAvScanner("localhost", server.getLocalPort(), Duration.ofSeconds(5));

            byte[] content = new byte[20_000]; // spans several chunks
            assertThat(scanner.findThreat(content)).isNull();
            assertThat(received.get()).hasSize(content.length);
        }
    }

    @Test
    void infectedFileReturnsThreatName() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            fakeClamd(server, "stream: Eicar-Test-Signature FOUND\0");
            ClamAvScanner scanner = new ClamAvScanner("localhost", server.getLocalPort(), Duration.ofSeconds(5));

            assertThat(scanner.findThreat("X5O!P%@AP".getBytes(StandardCharsets.US_ASCII)))
                    .isEqualTo("Eicar-Test-Signature");
        }
    }

    @Test
    void unreachableClamdFailsClosed() throws Exception {
        int freePort;
        try (ServerSocket probe = new ServerSocket(0)) {
            freePort = probe.getLocalPort();
        }
        ClamAvScanner scanner = new ClamAvScanner("localhost", freePort, Duration.ofSeconds(2));
        assertThatThrownBy(() -> scanner.findThreat(new byte[10])).isInstanceOf(ScanUnavailableException.class);
    }

    /** Reads one INSTREAM request, returns the streamed bytes, and sends the given reply. */
    private static CompletableFuture<byte[]> fakeClamd(ServerSocket server, String reply) {
        return CompletableFuture.supplyAsync(() -> {
            try (Socket client = server.accept()) {
                DataInputStream in = new DataInputStream(client.getInputStream());
                byte[] command = new byte["zINSTREAM\0".length()];
                in.readFully(command);
                assertThat(new String(command, StandardCharsets.US_ASCII)).isEqualTo("zINSTREAM\0");
                ByteArrayOutputStream data = new ByteArrayOutputStream();
                for (int length = in.readInt(); length > 0; length = in.readInt()) {
                    byte[] chunk = new byte[length];
                    in.readFully(chunk);
                    data.write(chunk);
                }
                client.getOutputStream().write(reply.getBytes(StandardCharsets.US_ASCII));
                client.getOutputStream().flush();
                return data.toByteArray();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }
}
