package net.kayomn;

import net.kayomn.common.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Scanner;

public final class ControlNode {
	public static final String PrimaryIP = "127.0.0.1";

	public static final int PrimaryPort = 50000;

	public static final int ServicesExpected = 1;

	public static void main(String[] args) {
		var service = new Service("Control");

		try (var server = service.listen(PrimaryPort)) {
			record RemoteService(String name, InetSocketAddress inetAddress) {

			}

			var remoteServices = Collections.synchronizedList(new ArrayList<RemoteService>());
			var inScanner = new Scanner(System.in);
			var isRunning = true;

			server.onRequest("helo", (inetAddress, data) -> {
				var remoteServiceName = new String(data, StandardCharsets.UTF_8);

				if (remoteServiceName.equals("distribution")) {
					remoteServices.add(new RemoteService(remoteServiceName, inetAddress));

					return Response.EmptyOk;
				}

				return new Response(Response.Status.ClientFail, "service unknown".getBytes(StandardCharsets.UTF_8));
			});

			server.onRequest("redy", (inetAddress, data) -> {
				if (remoteServices.size() >= ServicesExpected) {
					var bodyBuilder = new StringBuilder();

					for (var remoteService : remoteServices) {
						var remoteAddress = remoteService.inetAddress();

						bodyBuilder.append(remoteService.name());
						bodyBuilder.append('\t');
						bodyBuilder.append(remoteAddress.getHostString());
						bodyBuilder.append('\t');
						bodyBuilder.append(remoteAddress.getPort());
						bodyBuilder.append('\n');
					}

					return new Response(Response.Status.Ok, bodyBuilder.toString().getBytes(StandardCharsets.UTF_8));
				}

				return Response.EmptyBusy;
			});

			server.onQuit((inetAdress, data) -> {
				remoteServices.removeIf(remoteService -> remoteService.inetAddress().equals(inetAdress));
			});

			while (isRunning) {
				if (inScanner.nextLine().equals("quit")) {
					isRunning = false;
				} else {
					System.out.println("Unknown command");
				}
			}
		}
	}
}
