package net.kayomn.common;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;

/**
 * General-purpose networking abstraction capable of spawning {@link Client} and {@link Server} instances for
 * communicating with other remote {@link Service}s over a network.
 */
public class Service {
	/**
	 * Client-server connection for sending requests to a remote server.
	 */
	public interface Client extends AutoCloseable {
		/**
		 * Closes the client connection to the server, freeing any associated network resources.
		 */
		@Override
		void close();

		/**
		 * Submits {@code request} as a request to the connected server, returning a {@link CompletableFuture} that will
		 * hold the resulting {@link Response} wrapped in a {@link Optional} at a later point.
		 *
		 * If the request failed to reach the server, the {@link CompletableFuture} will hold an empty {@link Optional}
		 * instead.
		 *
		 * See {@link CompletableFuture} for more information on how to await and query for the future value.
		 */
		CompletableFuture<Optional<Response>> request(Request request);
	}

	/**
	 * Log severity level identifiers used for hinting how their associated message should be logged.
	 */
	public enum LogLevel {
		Info,
		Warning,
		Critical,
	}

	/**
	 * Client-server broadcaster for receiving requests from remote clients.
	 */
	public interface Server extends AutoCloseable {
		/**
		 * Closes the server broadcast from all clients, freeing any associated network resources.
		 */
		@Override
		void close();

		/**
		 * Assigns {@code quitConsumer} as the handling logic for any future client quit requests.
		 */
		void onQuit(BiConsumer<InetSocketAddress, byte[]> quitConsumer);

		/**
		 * Assigns {@code requestProcessor} as the handling logic for any future client requests submitted with the name
		 * {@code requestName}.
		 */
		void onRequest(String requestName, BiFunction<InetSocketAddress, byte[], Response> requestProcessor);
	}

	private final String name;

	/**
	 * Constructs a new {@link Service} with {@code serviceName} as its name.
	 */
	public Service(String serviceName) {
		this.name = serviceName;
	}

	/**
	 * Attempts to connect to a service located at {@code socketAddress}, returning a {@link Client} instance
	 * representing the established connection.
	 *
	 * Should any exception occur while establishing the connection to the remote service, an {@link IOException} is
	 * thrown.
	 *
	 * {@link Client#close} must be called to close the connection and release any associated network resources.
	 */
	public Client connect(InetSocketAddress socketAddress) {
		final class ClientImplementation implements Client, Runnable {
			record Event(Request request, Consumer<Optional<Response>> optionalResponseConsumer) {

			}

			private final ArrayBlockingQueue<Event> eventQueue;

			private final AtomicBoolean isRunning;

			public ClientImplementation() {
				this.eventQueue = new ArrayBlockingQueue<>(64);
				this.isRunning = new AtomicBoolean(true);
			}

			@Override
			public void close() {
				this.isRunning.set(false);
			}

			@Override
			public CompletableFuture<Optional<Response>> request(Request request) {
				// The "complete" function which sets the held data of the future is passed to a list of functions to be
				// called later, once the request has been processed in the event queue by the server and the remote
				// server has generated a response.
				var responseFuture = new CompletableFuture<Optional<Response>>();

				this.eventQueue.offer(new Event(request, responseFuture::complete));

				return responseFuture;
			}

			@Override
			public void run() {
				// Attempt to open a connection.
				try (var socketChannel = SocketChannel.open(socketAddress)) {
					log(LogLevel.Info, "Client started connection to " + socketAddress);

					var buffer = ByteBuffer.allocateDirect(1024);

					// Handle connection to server.
					while (this.isRunning.get()) {
						while (!this.eventQueue.isEmpty()) {
							try {
								// Each event is submitted to the remote server one-by-one from the queue.
								var event = this.eventQueue.poll();
								var encodedRequest = event.request().encode();

								if (socketChannel.write(encodedRequest) == encodedRequest.capacity()) {
									if (socketChannel.read(buffer) > 0) {
										// Send the decoded request to the "complete" function of the future created
										// earlier in "request".
										event.optionalResponseConsumer().accept(Response.decode(buffer));
									}

									buffer.clear();
								}
							} catch (IOException exception) {
								log(
									LogLevel.Warning,
									"Failed to communicate with remote service: " + exception.getMessage()
								);
							}
						}
					}
				} catch (IOException exception) {
					log(LogLevel.Critical, "Failed to connect to " + socketAddress + ": " + exception.getMessage());

					Optional<Response> optionalResponse = Optional.empty();

					while (this.isRunning.get()) {
						// Respond to any future requests after the exception with empty optionals.
						while (!this.eventQueue.isEmpty()) {
							this.eventQueue.poll().optionalResponseConsumer().accept(optionalResponse);
						}
					}
				}
			}
		}

		var client = new ClientImplementation();

		new Thread(client).start();

		return client;
	}

	/**
	 * Attempts to listen on the port {@code portNumber}, returning a {@link Server} instance representing the running
	 * broadcast.
	 *
	 * A {@code portNumber} value of {@code 0} will make the server choose the first available port it can find.
	 * Otherwise, any integer between {@code 1} and {@code 65535} is a valid port number.
	 *
	 * While most request names are freely programmable, certain ones are hardcoded, namely:
	 *
	 *   * "quit" is reserved to disconnect the client connection from the server and respond with
	 *     {@link Response#EmptyOk} if successful.
	 *
	 *   * "noop" is reserved as a quiet operation with no side effects to server state, responding with
	 *     {@link Response#EmptyOk} if successful. NOOP requests are useful for checking the validity of the connection
	 *     from the client-side.
	 *
	 * Should any exception occur while starting the broadcast, an {@link IOException} is thrown.
	 *
	 * {@link Server#close} must be called to kill the broadcast and release any associated network resources.
	 */
	public Server listen(int portNumber) {
		final class ServerImplementation implements Runnable, Server {
			private final HashMap<String, BiFunction<InetSocketAddress, byte[], Response>> requestProcessors;

			private Optional<BiConsumer<InetSocketAddress, byte[]>> optionalQuitConsumer;

			private final AtomicBoolean isRunning;

			public ServerImplementation() {
				this.requestProcessors = new HashMap<>();
				this.isRunning = new AtomicBoolean(true);
				this.optionalQuitConsumer = Optional.empty();
			}

			@Override
			public void close() {
				this.isRunning.set(false);
			}

			private void handle(SocketChannel clientSocketChannel) {
				try {
					var inBuffer = ByteBuffer.allocateDirect(1024);

					if (clientSocketChannel.read(inBuffer) > 0) {
						var decodedRequest = Request.decode(inBuffer);

						if (decodedRequest.isPresent()) {
							var request = decodedRequest.get();
							var requestName = request.name();

							// Some requests are hardcoded into service servers.
							switch (requestName) {
								case "quit" -> {
									// Attempting to acquire the local address after closing the socket channel throws
									// an unchecked exception.
									var address = clientSocketChannel.getRemoteAddress();

									this.optionalQuitConsumer.ifPresent(inetSocketAddressBiConsumer -> {
										inetSocketAddressBiConsumer.accept((InetSocketAddress)address, request.data());
									});

									clientSocketChannel.write(new Response(Response.Status.Ok).encode());
									clientSocketChannel.close();
									log(LogLevel.Info, address + " disconnected");
								}

								case "noop" -> clientSocketChannel.write(Response.EmptyOk.encode());

								default -> {
									log(
										LogLevel.Info,
										clientSocketChannel.getRemoteAddress() + " requested \"" + requestName + "\""
									);

									var requestProcessor = Optional.ofNullable(
										this.requestProcessors.get(requestName)
									);

									if (requestProcessor.isPresent()) {
										clientSocketChannel.write(
											requestProcessor.get().apply(
												(InetSocketAddress)clientSocketChannel.getRemoteAddress(),
												request.data()
											).encode()
										);
									} else {
										clientSocketChannel.write(new Response(
											Response.Status.ClientFail,
											"request name unsupported".getBytes(StandardCharsets.UTF_8)
										).encode());
									}
								}
							}
						} else {
							clientSocketChannel.write(new Response(
								Response.Status.ClientFail,
								"request corrupt".getBytes(StandardCharsets.UTF_8)
							).encode());
						}
					} else {
						clientSocketChannel.write(new Response(
							Response.Status.ClientFail,
							"request empty".getBytes(StandardCharsets.UTF_8)
						).encode());
					}
				} catch (IOException exception) {
					log(LogLevel.Warning, "Failed to reach client: " + exception.getMessage());

					try {
						clientSocketChannel.close();
					} catch (IOException closeException) {
						log(LogLevel.Warning, "Failed to close client connection: " + closeException.getMessage());
					}
				}
			}

			@Override
			public void onQuit(BiConsumer<InetSocketAddress, byte[]> quitConsumer) {
				this.optionalQuitConsumer = Optional.of(quitConsumer);
			}

			public void onRequest(String requestName, BiFunction<InetSocketAddress, byte[], Response> requestProcessor) {
				this.requestProcessors.put(requestName, requestProcessor);
			}

			@Override
			public void run() {
				try {
					var selector = Selector.open();

					var serverSocketChannel = ServerSocketChannel.open()
						.setOption(StandardSocketOptions.SO_REUSEADDR, true)
						.bind(new InetSocketAddress(portNumber));

					serverSocketChannel.configureBlocking(false);
					serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
					log(LogLevel.Info, "Server started on " + portNumber);

					while (this.isRunning.get()) {
						try {
							selector.select(100);

							var selectedKeyIterator = selector.selectedKeys().iterator();

							while (selectedKeyIterator.hasNext()) {
								var selectedKey = selectedKeyIterator.next();

								selectedKeyIterator.remove();

								if (selectedKey.isAcceptable()) {
									// Handle incoming connection...
									SocketChannel clientSocketChannel = serverSocketChannel.accept();

									clientSocketChannel.configureBlocking(false);
									clientSocketChannel.register(selector, SelectionKey.OP_READ);
									log(LogLevel.Info, clientSocketChannel.getRemoteAddress() + " connected");
								}

								if (selectedKey.isReadable()) {
									// Handle incoming request...
									this.handle((SocketChannel)selectedKey.channel());
								}
							}
						} catch (IOException exception) {
							log(LogLevel.Warning, "Failed to handle client: " + exception.getMessage());
						}
					}

					serverSocketChannel.close();
					selector.close();

					log(LogLevel.Info, "Closed");
				} catch (IOException exception) {
					log(LogLevel.Critical, "Failed to bind service on port: " + exception.getMessage());
				}
			}
		}

		var server = new ServerImplementation();

		new Thread(server).start();

		return server;
	}

	/**
	 * Logs {@code message} using {@code logLevel} as the log severity.
	 *
	 * See {@link LogLevel} for more information on log severity levels.
	 */
	public void log(LogLevel logLevel, String message) {
		var composedMessage = ("[" + this.name + "] " + message);

		switch (logLevel) {
			case Info -> System.out.println(composedMessage);
			case Warning -> System.out.println(composedMessage);
			case Critical -> System.err.println(composedMessage);
		}
	}
}
