package net.kayomn.common;

import java.nio.ByteBuffer;

/**
 * A data transfer type that can be transformed into a {@link ByteBuffer} for transmission over serial devices like file
 * systems and networks.
 */
public interface Encodable {
	/**
	 * Transforms the contained data into a series of bytes and returns them as a {@link ByteBuffer}.
	 */
	ByteBuffer encode();
}
