package net.kayomn;

import net.kayomn.common.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

public final class UserNode {
	public static void main(String[] args) {
		var service = new Service("User");

		try (var client = service.connect(new InetSocketAddress(ControlNode.PrimaryIP, ControlNode.PrimaryPort))) {
			var inScanner = new Scanner(System.in);
			var isRunning = true;

			while (isRunning) {
				var commandName = inScanner.next();
				var commandData = inScanner.nextLine();

				var optionalResponse = client.request(new Request(
					commandName,
					commandData.getBytes(StandardCharsets.UTF_8)
				)).join();

				if (optionalResponse.isPresent()) {
					var response = optionalResponse.get();

					if (response.status() == Response.Status.Ok) {
						if (commandName.equals("quit")) {
							isRunning = false;
						} else {
							System.out.println(new String(response.body(), StandardCharsets.UTF_8));
						}
					}
				} else {
					service.log(Service.LogLevel.Critical, "Request to reach remote service");
				}
			}
		}
	}
}
