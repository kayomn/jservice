package net.kayomn.common;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Data transfer type used by {@link Service}s for requesting {@link Response}s from remote server {@link Service}s.
 *
 * {@link Request#name()} marks the utf-8 encoded name of the request, which is used by {@link Service}s to determine
 * how it is processed on the server-side.
 *
 * {@link Request#data()} contains any request-specific argument data that may be acknowledged and used by the
 * processing {@link Service}.
 */
public record Request(String name, byte[] data) implements Encodable {
	private static final Charset NameCharset = StandardCharsets.UTF_8;

	private static final int HeaderSize = (Integer.BYTES + Integer.BYTES);

	/**
	 * Constructs a new {@link Request} instance with {@code name} as the request name and no additional data.
	 */
	public Request(String name) {
		this(name, new byte[0]);
	}

	/**
	 * Attempts to decode {@code buffer} into a new {@link Request} instance, returning it or nothing wrapped in an
	 * {@link Optional}.
	 *
	 * Nothing is returned if the data contained in {@code buffer} does not follow a supported byte pattern for
	 * decoding.
	 */
	public static Optional<Request> decode(ByteBuffer buffer) {
		if (buffer.capacity() > HeaderSize) {
			// Request name must be at least one byte long.
			var nameSize = buffer.getInt(0);
			var dataSize = buffer.getInt(Integer.BYTES);
			var name = new byte[nameSize];
			var data = new byte[dataSize];

			buffer.get(HeaderSize, name, 0, nameSize);
			buffer.get(HeaderSize + nameSize, data, 0, dataSize);

			return Optional.of(new Request(new String(name, NameCharset), data));
		}

		return Optional.empty();
	}

	/**
	 * Transforms the {@link Request} into a series of bytes, returning it as a {@link ByteBuffer}.
	 *
	 * The returned {@link ByteBuffer} may be passed to {@link #decode(ByteBuffer)} to be decoded back into a
	 * {@link Request}.
	 */
	@Override
	public ByteBuffer encode() {
		var nameBytes = this.name.getBytes(NameCharset);
		var nameSize = nameBytes.length;
		var dataSize = this.data.length;

		return ByteBuffer.allocate(HeaderSize + nameSize + dataSize)
			.putInt(nameSize)
			.putInt(dataSize)
			.put(nameBytes)
			.put(this.data)
			.position(0);
	}
}
