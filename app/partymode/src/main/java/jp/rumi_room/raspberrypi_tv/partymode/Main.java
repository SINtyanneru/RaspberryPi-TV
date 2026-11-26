package jp.rumi_room.raspberrypi_tv.partymode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import su.rumishistem.rsdf_java.RSDFDecoder;
import su.rumishistem.rsdf_java.RSDFEncoder;

public class Main {
	public static void main(String[] args) throws IOException, InterruptedException{
		Thread.sleep(5000);

		send_message(new HashMap<>(){{
			put("TYPE", "READY");
		}});

		send_message(new HashMap<>(){{
			put("TYPE", "NEW_FB");
			put("X", 0L);
			put("Y", 0L);
			put("W", 500L);
			put("H", 100L);
		}});

		receive_loop();
	}

	private static void receive_loop() throws IOException {
		while (true) {
			byte[] header = System.in.readNBytes(4);
			if (header.length < 4) {
				break;
			}
			int length = ByteBuffer.wrap(header).getInt();

			byte[] body = System.in.readNBytes(length);
			if (body.length < length) {
				break;
			}

			Map<String, Object> message = RSDFDecoder.decode(body).get_dict();
			String mode = (String)message.get("TYPE");
			switch (mode) {
				case "NEW_FB": {
					String path = (String)message.get("PATH");
					send_message(new HashMap<>(){{
						put("TYPE", "LOG");
						put("TEXT", path + "が届いた");
					}});
					break;
				}
			}
		}
	}

	public static void send_message(Map<String, Object> message) throws IOException {
		byte[] rsdf = RSDFEncoder.encode(message);
		System.out.write(ByteBuffer.allocate(4).putInt(rsdf.length).array());
		System.out.write(rsdf);
		System.out.flush();
	}
}
