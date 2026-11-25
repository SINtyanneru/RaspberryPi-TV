package jp.rumi_room.raspberrypi_tv.partymode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import su.rumishistem.rsdf_java.RSDFEncoder;

public class Main {
	public static void main(String[] args) throws IOException, InterruptedException{
		Thread.sleep(5000);

		send_message(new HashMap<>(){{
			put("TYPE", "READY");
		}});
	}

	public static void send_message(Map<String, Object> message) throws IOException {
		byte[] rsdf = RSDFEncoder.encode(message);
		System.out.write(ByteBuffer.allocate(4).putInt(rsdf.length).array());
		System.out.write(rsdf);
		System.out.flush();
	}
}
