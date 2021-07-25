/*
 * Copyright (c) 2014, 2021 Oracle and/or its affiliates. All rights reserved.
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

import static java.lang.String.format;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/*
 * @test
 * @bug 8265891
 * @summary tests whether sun.nio.ChannelInputStream.transferTo conforms to the
 *          InputStream.transferTo contract defined in the javadoc
 * @key randomness
 */
public class TransferTo {

	public static void main(String[] args) throws Exception {
		test(fileChannelInput(), writableByteChannelOutput());
		test(seekableByteChannelInput(), fileChannelOutput());
		test(readableByteChannelInput(), fileChannelOutput());
		test(readableByteChannelInput(), writableByteChannelOutput());
		test(defaultInput(), defaultOutput());
	}

	private static void test(InputStreamProvider inputStreamProvider, OutputStreamProvider outputStreamProvider) throws Exception {
		contents(inputStreamProvider, outputStreamProvider);
	}

	private static void contents(InputStreamProvider inputStreamProvider, OutputStreamProvider outputStreamProvider) throws Exception {
		checkTransferredContents(inputStreamProvider, outputStreamProvider, new byte[0]);
		checkTransferredContents(inputStreamProvider, outputStreamProvider, createRandomBytes(1024, 4096));
		// to span through several batches
		checkTransferredContents(inputStreamProvider, outputStreamProvider, createRandomBytes(16384, 16384));
	}

	private static void checkTransferredContents(InputStreamProvider inputStreamProvider, OutputStreamProvider outputStreamProvider, byte[] inBytes)
			throws Exception {
		AtomicReference<Supplier<byte[]>> recorder = new AtomicReference<>();
		try (InputStream in = inputStreamProvider.input(inBytes); OutputStream out = outputStreamProvider.output(recorder::set)) {
			in.transferTo(out);

			byte[] outBytes = recorder.get().get();

			if (!Arrays.equals(inBytes, outBytes))
				throw new AssertionError(format("bytes.length=%s, outBytes.length=%s", inBytes.length, outBytes.length));
		}
	}

	private static byte[] createRandomBytes(int min, int maxRandomAdditive) {
		Random rnd = new Random();
		byte[] bytes = new byte[min + rnd.nextInt(maxRandomAdditive)];
		rnd.nextBytes(bytes);
		return bytes;
	}

	private static interface InputStreamProvider {
		InputStream input(byte... bytes) throws Exception;
	}

	private static interface OutputStreamProvider {
		OutputStream output(Consumer<Supplier<byte[]>> spy) throws Exception;
	}

	private static InputStreamProvider defaultInput() {
		return new InputStreamProvider() {
			@Override
			public InputStream input(byte... bytes) {
				return new ByteArrayInputStream(bytes);
			}
		};
	}

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

	private static InputStreamProvider fileChannelInput() {
		return new InputStreamProvider() {
			@Override
			public InputStream input(byte... bytes) throws Exception {
				Path path = Files.createTempFile(null, null);
				Files.write(path, bytes);
				FileChannel fileChannel = FileChannel.open(path);
				return Channels.newInputStream(fileChannel);
			}
		};
	}

	private static InputStreamProvider seekableByteChannelInput() {
		return new InputStreamProvider() {
			@Override
			public InputStream input(byte... bytes) throws Exception {
				Path temporaryJarFile = Files.createTempFile(null, ".zip");
				try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(temporaryJarFile))) {
					JarEntry jarEntry = new JarEntry("raw-bytes");
					out.putNextEntry(jarEntry);
					out.write(bytes);
					out.closeEntry();
				}
				FileSystem zipFileSystem = FileSystems.newFileSystem(
						new URI("jar", URLDecoder.decode(temporaryJarFile.toUri().toString(), StandardCharsets.UTF_8), null), Collections.emptyMap(), null);
				SeekableByteChannel sbc = zipFileSystem.provider().newByteChannel(zipFileSystem.getPath("raw-bytes"),
						Collections.singleton(StandardOpenOption.READ));
				return Channels.newInputStream(sbc);
			}
		};
	}

	private static InputStreamProvider readableByteChannelInput() {
		return new InputStreamProvider() {
			@Override
			public InputStream input(byte... bytes) throws Exception {
				return Channels.newInputStream(Channels.newChannel(new ByteArrayInputStream(bytes)));
			}
		};
	}

	private static OutputStreamProvider fileChannelOutput() {
		return new OutputStreamProvider() {
			public OutputStream output(Consumer<Supplier<byte[]>> spy) throws Exception {
				Path path = Files.createTempFile(null, null);
				FileChannel fileChannel = FileChannel.open(path, StandardOpenOption.WRITE);
				spy.accept(() -> {
					try {
						return Files.readAllBytes(path);
					} catch (IOException e) {
						return null;
					}
				});
				return Channels.newOutputStream(fileChannel);
			}
		};
	}

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
