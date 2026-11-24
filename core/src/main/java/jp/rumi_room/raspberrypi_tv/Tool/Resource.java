package jp.rumi_room.raspberrypi_tv.Tool;

import java.io.*;
import java.util.Optional;
import jp.rumi_room.raspberrypi_tv.Main;

public class Resource {
	public static boolean is_exists(String path) {
		if (Main.class.getResource(path) == null) {
			return false;
		} else {
			return true;
		}
	}

	public static Optional<byte[]> load(String path) {
		InputStream in = Main.class.getResourceAsStream(path);
		if (in == null) {
			return Optional.empty();
		}

		try {
			return Optional.of(in.readAllBytes());
		} catch (IOException ex) {
			ex.printStackTrace();
			return Optional.empty();
		}
	}
}
