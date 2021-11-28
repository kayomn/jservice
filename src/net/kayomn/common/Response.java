package net.kayomn.common;

import java.nio.ByteBuffer;
import java.util.Optional;

/**
 * Data transfer type used by {@link Service}s for responding to received {@link Request}s from remote client
 * {@link Service}s.
 *
 * {@link Response#status()} identifies the success of the response, with {@link Status#Ok} acting as the generic
 * "success" state. See {@link Status} for more information on the potential status codes that a {@link Response} may
 * hold.
 *
 * {@link Response#body()} contains the data requested as raw bytes.
 */
public record Response(Status status, byte[] body) implements Encodable {
	/**
	 * Shortcut constant to avoid constructing another {@link Response} that acts as an empty "Busy" status.
	 */
	public static final Response EmptyBusy = new Response(Status.Busy);

	/**
	 * Shortcut constant to avoid constructing another {@link Response} that acts as an empty "Ok" status.
	 */
	public static final Response EmptyOk = new Response(Status.Ok);

	private static final int HeaderSize = (Integer.BYTES + Integer.BYTES);

	/**
	 * Strongly typed abstractions over the raw response status code integer values.
	 */
	public enum Status {
		Ok,
		Busy,
		ClientFail,
		ServerFail,
	}

	/**
	 * Constructs a new {@link Response} with {@code status} as the status code and an empty body.
	 */
	public Response(Status status) {
		this(status, new byte[0]);
	}

	/**
	 * Attempts to decode {@code buffer} into a new {@link Response} instance, returning it or nothing wrapped in an
	 * {@link Optional}.
	 *
	 * Nothing is returned if the data contained in {@code buffer} does not follow a supported byte pattern for
	 * decoding.
	 */
	public static Optional<Response> decode(ByteBuffer buffer) {
		if (buffer.capacity() >= HeaderSize) {
			var statusCode = buffer.getInt(0);

			if ((statusCode >= 200) && (statusCode < 300)) {
				var bodySize = buffer.getInt(Integer.BYTES);
				var bodyBytes = new byte[bodySize];

				buffer.get(HeaderSize, bodyBytes, 0, bodySize);

				return Optional.of(new Response(Status.Ok, bodyBytes));
			}

			if ((statusCode >= 300) && (statusCode < 400)) {
				var bodySize = buffer.getInt(Integer.BYTES);
				var bodyBytes = new byte[bodySize];

				buffer.get(HeaderSize, bodyBytes, 0, bodySize);

				return Optional.of(new Response(Status.Busy, bodyBytes));
			}

			if ((statusCode >= 400) && (statusCode < 500)) {
				var bodySize = buffer.getInt(Integer.BYTES);
				var bodyBytes = new byte[bodySize];

				buffer.get(HeaderSize, bodyBytes, 0, bodySize);

				return Optional.of(new Response(Status.ClientFail, bodyBytes));
			}

			if ((statusCode >= 500) && (statusCode < 600)) {
				var bodySize = buffer.getInt(Integer.BYTES);
				var bodyBytes = new byte[bodySize];

				buffer.get(HeaderSize, bodyBytes, 0, bodySize);

				return Optional.of(new Response(Status.ServerFail, bodyBytes));
			}
		}

		return Optional.empty();
	}

	/**
	 * Transforms the {@link Response} into a series of bytes, returning it as a {@link ByteBuffer}.
	 *
	 * The returned {@link ByteBuffer} may be passed to {@link #decode(ByteBuffer)} to be decoded back into a
	 * {@link Response}.
	 */
	public ByteBuffer encode() {
		var bodySize = this.body.length;
		var buffer = ByteBuffer.allocate(HeaderSize + bodySize);

		buffer.putInt(switch (this.status) {
			case Ok -> 200;
			case Busy -> 300;
			case ClientFail -> 400;
			case ServerFail -> 500;
		});

		return buffer.putInt(bodySize).put(this.body).position(0);
	}
}
