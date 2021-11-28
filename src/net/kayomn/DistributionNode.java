package net.kayomn;

import net.kayomn.common.Request;
import net.kayomn.common.Response;
import net.kayomn.common.Service;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public class DistributionNode {
	public static void main(String[] args) {
		var service = new Service("Distribution");

		try (var client = service.connect(new InetSocketAddress(ControlNode.PrimaryIP, ControlNode.PrimaryPort))) {
			var charset = StandardCharsets.UTF_8;
			var heloOptionalResponse = client.request(new Request("helo", "distribution".getBytes(charset))).join();

			if (heloOptionalResponse.isPresent()) {
				var heloResponse = heloOptionalResponse.get();

				switch (heloResponse.status()) {
					case Ok -> {
						var redyRequest = new Request("redy");
						var redyOptionalResponse = client.request(redyRequest).join();

						while (true) {
							if (redyOptionalResponse.isEmpty()) {
								break;
							}

							var redyResponse = redyOptionalResponse.get();

							if (redyResponse.status() != Response.Status.Busy) {
								break;
							}
						}

						if (redyOptionalResponse.isPresent()) {
							var redyResponse = redyOptionalResponse.get();

							if (redyResponse.status() == Response.Status.Ok) {
								System.out.println(new String(redyResponse.body(), StandardCharsets.UTF_8));
							} else {
								service.log(Service.LogLevel.Critical, "Failed to reach remote service");
							}
						} else {
							service.log(Service.LogLevel.Critical, "Server failed to respond");
						}
					}

					case ClientFail -> service.log(Service.LogLevel.Critical, "Service unrecognized by control node");
					default -> service.log(Service.LogLevel.Critical, "Unknown server error");
				}
			} else {
				service.log(Service.LogLevel.Critical, "Failed to reach remote service");
			}

			client.request(new Request("quit")).join();
		}
	}
}
