/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.IllegalBlockingModeException;
import java.nio.channels.Pipe;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import jdk.test.lib.RandomFactory;

import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.*;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

/*
 * @test
 * @library /test/lib
 * @build jdk.test.lib.RandomFactory
 * @run testng/othervm/timeout=180 TransferTo
 * @bug 8265891
 * @summary Tests whether sun.nio.ChannelInputStream.transferTo conforms to the
 *          InputStream.transferTo specification
 * @key randomness
 */
public class TransferTo {
    private static final int MIN_SIZE      = 10_000;
    private static final int MAX_SIZE_INCR = 100_000_000 - MIN_SIZE;

    private static final int ITERATIONS = 10;

    private static final int NUM_WRITES = 3*1024;
    private static final int BYTES_PER_WRITE = 1024*1024;
    private static final long BYTES_WRITTEN = (long) NUM_WRITES*BYTES_PER_WRITE;

    private static final Random RND = RandomFactory.getRandom();

    private static final Path CWD = Path.of(".");

    /*
     * Provides test scenarios, i.e., combinations of input and output streams
     * to be tested.
     */
    @DataProvider
    public static Object[][] streamCombinations() throws Exception {
        return new Object[][] {
            // tests FileChannel.transferTo(FileChannel) optimized case
            { fileChannelInput(), fileChannelOutput() },

            // tests FileChannel.transferTo(SelectableChannel)
            // optimized case
            { fileChannelInput(), selectableChannelOutput() },

            // tests FileChannel.transferTo(WritableByteChannel)
            // optimized case
            { fileChannelInput(), writableByteChannelOutput() },

            // tests FileChannel.transferFrom(SelectableChannel) optimized case
            { selectableChannelInput(), fileChannelOutput() },

            // tests FileChannel.transferFrom(SeekableByteChannel) optimized case
            { seekableByteChannelInput(), fileChannelOutput() },

            // tests FileChannel.transferFrom(ReadableByteChannel) optimized case
            { readableByteChannelInput(), fileChannelOutput() },

            // tests InputStream.transferTo(OutputStream) default case
            { readableByteChannelInput(), defaultOutput() }
        };
    }

    /*
     * Testing API compliance: input stream must throw NullPointerException
     * when parameter "out" is null.
     */
    @Test(dataProvider = "streamCombinations")
    public void testNullPointerException(InputStreamProvider inputStreamProvider,
            OutputStreamProvider outputStreamProvider) throws Exception {
        // tests empty input stream
        assertThrows(NullPointerException.class, () -> inputStreamProvider.input().transferTo(null));

        // tests single-byte input stream
        assertThrows(NullPointerException.class, () -> inputStreamProvider.input((byte) 1).transferTo(null));

        // tests dual-byte input stream
        assertThrows(NullPointerException.class, () -> inputStreamProvider.input((byte) 1, (byte) 2).transferTo(null));
    }

    /*
     * Testing API compliance: complete content of input stream must be
     * transferred to output stream.
     */
    @Test(dataProvider = "streamCombinations")
    public void testStreamContents(InputStreamProvider inputStreamProvider,
            OutputStreamProvider outputStreamProvider) throws Exception {
        // tests empty input stream
        checkTransferredContents(inputStreamProvider, outputStreamProvider, new byte[0]);

        // tests input stream with a length between 1k and 4k
        checkTransferredContents(inputStreamProvider, outputStreamProvider, createRandomBytes(1024, 4096));

        // tests input stream with several data chunks, as 16k is more than a
        // single chunk can hold
        checkTransferredContents(inputStreamProvider, outputStreamProvider, createRandomBytes(16384, 16384));

        // tests randomly chosen starting positions within source and
        // target stream
        for (int i = 0; i < ITERATIONS; i++) {
            byte[] inBytes = createRandomBytes(MIN_SIZE, MAX_SIZE_INCR);
            int posIn = RND.nextInt(inBytes.length);
            int posOut = RND.nextInt(MIN_SIZE);
            checkTransferredContents(inputStreamProvider, outputStreamProvider, inBytes, posIn, posOut);
        }

        // tests reading beyond source EOF (must not transfer any bytes)
        checkTransferredContents(inputStreamProvider, outputStreamProvider, createRandomBytes(4096, 0), 4096, 0);

        // tests writing beyond target EOF (must extend output stream)
        checkTransferredContents(inputStreamProvider, outputStreamProvider, createRandomBytes(4096, 0), 0, 4096);
    }

    @FunctionalInterface
    private interface CheckedFunction<T, R, E extends Throwable> {
        R apply(T t) throws E;
    }

    private static void testMoreThanTwoGB(String testName, CheckedFunction<Path, ReadableByteChannel, IOException> factory) throws IOException {
        // prepare two temporary files to be compared at the end of the test
        // set the source file name
        String sourceName = String.format("test3GB%sSource%s.tmp", testName,
            String.valueOf(RND.nextInt(Integer.MAX_VALUE)));
        Path sourceFile = CWD.resolve(sourceName);

        try {
            // set the target file name
            String targetName = String.format("test3GB%sTarget%s.tmp", testName,
                String.valueOf(RND.nextInt(Integer.MAX_VALUE)));
            Path targetFile = CWD.resolve(targetName);

            try {
                // calculate initial position to be just short of 2GB
                final long initPos = 2047*BYTES_PER_WRITE;

                // create the source file with a hint to be sparse
                try (FileChannel fc = FileChannel.open(sourceFile, CREATE_NEW, SPARSE, WRITE, APPEND);) {
                    // set initial position to avoid writing nearly 2GB
                    fc.position(initPos);

                    // fill the remainder of the file with random bytes
                    int nw = (int)(NUM_WRITES - initPos/BYTES_PER_WRITE);
                    for (int i = 0; i < nw; i++) {
                        byte[] rndBytes = createRandomBytes(BYTES_PER_WRITE, 0);
                        ByteBuffer src = ByteBuffer.wrap(rndBytes);
                        fc.write(src);
                    }
                }

                // create the target file with a hint to be sparse
                try (FileChannel fc = FileChannel.open(targetFile, CREATE_NEW, WRITE, SPARSE);) {
                }

                // perform actual transfer, effectively by multiple invocations
                // of FileChannel.transferTo(WritableByteChannel) or FileChannel.transferFrom(ReadableByteChannel)
                try (InputStream inputStream = Channels.newInputStream(factory.apply(sourceFile));
                     OutputStream outputStream = Channels.newOutputStream(FileChannel.open(targetFile, WRITE))) {
                    long count = inputStream.transferTo(outputStream);

                    // compare reported transferred bytes, must be 3 GB
                    // less the value of the initial position
                    assertEquals(count, BYTES_WRITTEN - initPos);
                }

                // compare content of both files, failing if different
                assertEquals(Files.mismatch(sourceFile, targetFile), -1);

            } finally {
                 Files.delete(targetFile);
            }
        } finally {
            Files.delete(sourceFile);
        }
    }

    /*
     * Special test for file-to-stream transfer of more than 2 GB. This test
     * covers multiple iterations of FileChannel.transferTo(WritableByteChannel),
     * which ChannelInputStream.transferTo() only applies in this particular
     * case, and cannot get tested using a single byte[] due to size limitation
     * of arrays.
     */
    @Test
    public void testMoreThanTwoGBtoStream() throws IOException {
        testMoreThanTwoGB("toStream", FileChannel::open);
    }

    /*
     * Special test for stream-to-file transfer of more than 2 GB. This test
     * covers multiple iterations of FileChannel.transferFrom(ReadableByteChannel),
     * which ChannelInputStream.transferFrom() only applies in this particular
     * case, and cannot get tested using a single byte[] due to size limitation
     * of arrays.
     */
    @Test
    public void testMoreThanTwoGBfromStream() throws IOException {
        testMoreThanTwoGB("fromStream", sourceFile -> Channels.newChannel(
                new BufferedInputStream(Files.newInputStream(sourceFile))));
    }

    /*
     * Special test whether selectable channel based transfer throws blocking mode exception.
     */
    @Test
    public void testIllegalBlockingMode() throws IOException {
        Pipe pipe = Pipe.open();
        try {
            // testing arbitrary input (here: empty file) to non-blocking
            // selectable output
            try (FileChannel fc = FileChannel.open(Files.createTempFile(CWD, "testIllegalBlockingMode", null));
                InputStream is = Channels.newInputStream(fc);
                SelectableChannel sc = pipe.sink().configureBlocking(false);
                OutputStream os = Channels.newOutputStream((WritableByteChannel) sc)) {

                // IllegalBlockingMode must be thrown when trying to perform
                // a transfer
                assertThrows(IllegalBlockingModeException.class, () -> is.transferTo(os));
            }

            // testing non-blocking selectable input to arbitrary output
            // (here: byte array)
            try (SelectableChannel sc = pipe.source().configureBlocking(false);
                InputStream is = Channels.newInputStream((ReadableByteChannel) sc);
                OutputStream os = new ByteArrayOutputStream()) {

                // IllegalBlockingMode must be thrown when trying to perform
                // a transfer
                assertThrows(IllegalBlockingModeException.class, () -> is.transferTo(os));
            }
        } finally {
            pipe.source().close();
            pipe.sink().close();
        }
    }

    /*
     * Asserts that the transferred content is correct, i.e., compares the bytes
     * actually transferred to those expected. The position of the input and
     * output streams before the transfer are zero (BOF).
     */
    private static void checkTransferredContents(InputStreamProvider inputStreamProvider,
            OutputStreamProvider outputStreamProvider, byte[] inBytes) throws Exception {
        checkTransferredContents(inputStreamProvider, outputStreamProvider, inBytes, 0, 0);
    }

    /*
     * Asserts that the transferred content is correct, i. e. compares the bytes
     * actually transferred to those expected. The positions of the input and
     * output streams before the transfer are provided by the caller.
     */
    private static void checkTransferredContents(InputStreamProvider inputStreamProvider,
            OutputStreamProvider outputStreamProvider, byte[] inBytes, int posIn, int posOut) throws Exception {
        AtomicReference<Supplier<byte[]>> recorder = new AtomicReference<>();
        try (InputStream in = inputStreamProvider.input(inBytes);
            OutputStream out = outputStreamProvider.output(recorder::set)) {
            // skip bytes until starting position
            in.skipNBytes(posIn);
            out.write(new byte[posOut]);

            long reported = in.transferTo(out);
            int count = inBytes.length - posIn;

            assertEquals(reported, count, format("reported %d bytes but should report %d", reported, count));

            byte[] outBytes = recorder.get().get();
            assertTrue(Arrays.equals(inBytes, posIn, posIn + count, outBytes, posOut, posOut + count),
                format("inBytes.length=%d, outBytes.length=%d", count, outBytes.length));
        }
    }

    /*
     * Creates an array of random size (between min and min + maxRandomAdditive)
     * filled with random bytes
     */
    private static byte[] createRandomBytes(int min, int maxRandomAdditive) {
        byte[] bytes = new byte[min + (maxRandomAdditive == 0 ? 0 : RND.nextInt(maxRandomAdditive))];
        RND.nextBytes(bytes);
        return bytes;
    }

    private interface InputStreamProvider {
        InputStream input(byte... bytes) throws Exception;
    }

    private interface OutputStreamProvider {
        OutputStream output(Consumer<Supplier<byte[]>> spy) throws Exception;
    }

    /*
     * Creates a provider for an output stream which does not wrap a channel
     */
    private static OutputStreamProvider defaultOutput() {
        return new OutputStreamProvider() {
            @Override
            public OutputStream output(Consumer<Supplier<byte[]>> spy) {
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                spy.accept(outputStream::toByteArray);
                return outputStream;
            }
        };
    }

    /*
     * Creates a provider for an input stream which wraps a file channel
     */
    private static InputStreamProvider fileChannelInput() {
        return new InputStreamProvider() {
            @Override
            public InputStream input(byte... bytes) throws Exception {
                Path path = Files.createTempFile(CWD, "fileChannelInput", null);
                Files.write(path, bytes);
                FileChannel fileChannel = FileChannel.open(path);
                return Channels.newInputStream(fileChannel);
            }
        };
    }

    /*
     * Creates a provider for an input stream which wraps a seekable byte
     * channel but is not a file channel
     */
    private static InputStreamProvider seekableByteChannelInput() {
        return new InputStreamProvider() {
            @Override
            public InputStream input(byte... bytes) throws Exception {
                Path temporaryJarFile = Files.createTempFile(CWD, "seekableChannelInput", ".zip");
                try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(temporaryJarFile))) {
                    JarEntry jarEntry = new JarEntry("raw-bytes");
                    out.putNextEntry(jarEntry);
                    out.write(bytes);
                    out.closeEntry();
                }
                FileSystem zipFileSystem = FileSystems.newFileSystem(
                        new URI("jar", URLDecoder.decode(temporaryJarFile.toUri().toString(), UTF_8), null),
                        Map.of(), null);
                SeekableByteChannel sbc = zipFileSystem.provider().newByteChannel(zipFileSystem.getPath("raw-bytes"),
                        Set.of(READ));
                return Channels.newInputStream(sbc);
            }
        };
    }

    /*
     * Creates a provider for an input stream which wraps a readable byte
     * channel but is not a file channel
     */
    private static InputStreamProvider readableByteChannelInput() {
        return new InputStreamProvider() {
            @Override
            public InputStream input(byte... bytes) throws Exception {
                return Channels.newInputStream(Channels.newChannel(new ByteArrayInputStream(bytes)));
            }
        };
    }

    /*
     * Creates a provider for an input stream which wraps a selectable channel
     */
    private static InputStreamProvider selectableChannelInput() throws IOException {
        return new InputStreamProvider() {
            public InputStream input(byte... bytes) throws Exception {
                Pipe pipe = Pipe.open();
                new Thread(() -> {
                    try (OutputStream os = Channels.newOutputStream(pipe.sink())) {
                      os.write(bytes);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }).start();
                return Channels.newInputStream(pipe.source());
            }
        };
    }

    /*
     * Creates a provider for an output stream which wraps a file channel
     */
    private static OutputStreamProvider fileChannelOutput() {
        return new OutputStreamProvider() {
            public OutputStream output(Consumer<Supplier<byte[]>> spy) throws Exception {
                Path path = Files.createTempFile(CWD, "fileChannelOutput", null);
                FileChannel fileChannel = FileChannel.open(path, WRITE);
                spy.accept(() -> {
                    try {
                        return Files.readAllBytes(path);
                    } catch (IOException e) {
                        throw new AssertionError("Failed to verify output file", e);
                    }
                });
                return Channels.newOutputStream(fileChannel);
            }
        };
    }

    /*
     * Creates a provider for an output stream which wraps a selectable channel
     */
    private static OutputStreamProvider selectableChannelOutput() throws IOException {
        return new OutputStreamProvider() {
            public OutputStream output(Consumer<Supplier<byte[]>> spy) throws Exception {
                Pipe pipe = Pipe.open();
                Future<byte[]> bytes = CompletableFuture.supplyAsync(() -> {
                    try {
                        InputStream is = Channels.newInputStream(pipe.source());
                        return is.readAllBytes();
                    } catch (IOException e) {
                        throw new AssertionError("Exception while asserting content", e);
                    }
                });
                final OutputStream os = Channels.newOutputStream(pipe.sink());
                spy.accept(() -> {
                    try {
                        os.close();
                        return bytes.get();
                    } catch (IOException | InterruptedException | ExecutionException e) {
                        throw new AssertionError("Exception while asserting content", e);
                    }
                });
                return os;
            }
        };
    }

    /*
     * Creates a provider for an output stream which wraps a writable byte channel but is not a file channel
     */
    private static OutputStreamProvider writableByteChannelOutput() {
        return new OutputStreamProvider() {
            public OutputStream output(Consumer<Supplier<byte[]>> spy) throws Exception {
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                spy.accept(outputStream::toByteArray);
                return Channels.newOutputStream(Channels.newChannel(outputStream));
            }
        };
    }
}
