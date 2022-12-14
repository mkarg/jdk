package eu.sandbox;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.Pipe;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import org.openjdk.jmh.Main;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;

public class TransferToPerformance {

    public static void main(final String[] args) throws IOException {
        Main.main(args);
    }

    @State(Scope.Benchmark)
    public static class Config {

        @Param({ "104857" })
        public int size;

        public InputStream source;

        public OutputStream target;

        private Path path;

        @Setup(Level.Invocation)
        public void setUp() throws IOException {
            this.path = Files.createTempFile("a-", ".bin");
            final var bytes = createRandomBytes(size);
            this.source = readableByteChannelInput(bytes);          
            Files.deleteIfExists(this.path);
            // this.target = Files.newOutputStream(this.path, CREATE, TRUNCATE_EXISTING, WRITE);
            this.target = writableByteChannelInput();
        }

        @TearDown(Level.Invocation)
        public void tearDown() throws IOException {
            this.source.close();
            this.target.close();
            this.source = null;
            this.target = null;
            Files.deleteIfExists(this.path);
        }

        /*
         * Creates a provider for an input stream which wraps a readable byte
         * channel but is not a file channel
         */
        private static InputStream readableByteChannelInput(final byte[] bytes) {
            return Channels.newInputStream(Channels.newChannel(new ByteArrayInputStream(bytes)));
        }

        /*
         * Creates a provider for an output stream which wraps a writable byte
         * channel but is not a file channel
         */
        private static OutputStream writableByteChannelInput() {
            return Channels.newOutputStream(Channels.newChannel(new ByteArrayOutputStream()));
        }

    }

    private static final Random RND = new Random(); 

    /*
     * Creates an array of random size (between min and min + maxRandomAdditive)
     * filled with random bytes
     */
    private static byte[] createRandomBytes(int size) {
        final var bytes = new byte[size];
        RND.nextBytes(bytes);
        return bytes;
    }

    @Benchmark
    public final void transferTo(final Config config, final Blackhole blackhole) throws IOException {
        blackhole.consume(config.source.transferTo(config.target));
        config.target.flush();
        config.target.close();
    }

}
