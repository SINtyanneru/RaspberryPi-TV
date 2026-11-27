package jp.rumi_room.raspberrypi_tv.partymode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CountDownLatch;

import jp.rumi_room.raspberrypi_tv.partymode.Tool.Installer;
import su.rumishistem.rsdf_java.*;

public class Main {
	private static CountDownLatch fb_cdl = new CountDownLatch(2);

	public static String vide_fb = null;
	public static int screen_width = 0;
	public static int screen_height = 1;

	public static int indicate_width = 0;
	public static int indicate_height = 0;

	public  static Path cache_dir;

	public static void main(String[] args) throws IOException, InterruptedException{
		cache_dir = Path.of(System.getProperty("user.home"), "rtv-cache");
		Files.createDirectories(cache_dir);

		try {
			Installer.install();
		} catch (Exception ex) {
			System.exit(1);
		}

		send_message(new HashMap<>(){{
			put("TYPE", "READY");
		}});

		new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					receive_loop();
				} catch (IOException e) {
					System.exit(1);
				}
			}
		}).start();

		new Thread(new Runnable() {
			@Override
			public void run() {
				HTTPServer.start();
			}
		}).start();

		fb_cdl.await();

		send_message(new HashMap<>(){{
			put("TYPE", "LOG");
			put("TEXT", "フレームバッファ準備完了");
		}});

		HTTPServer.await();

		send_message(new HashMap<>(){{
			put("TYPE", "LOG");
			put("TEXT", "初期化完了しました");
		}});

		send_message(new HashMap<>(){{
			put("TYPE", "WEBPAGE");
			put("PORT", 81);
		}});

		Core.main();
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
				case "READY": {
					screen_width = (int)message.get("WIDTH");
					screen_height = (int)message.get("HEIGHT");

					send_message(new HashMap<>(){{
						put("TYPE", "NEW_FB");
						put("X", 0L);
						put("Y", 0L);
						put("W", (long)screen_width);
						put("H", (long)screen_height);
						put("ALPHA", false);
					}});

					indicate_width = screen_width / 2;
					indicate_height = screen_height / 5;

					send_message(new HashMap<>(){{
						put("TYPE", "NEW_FB");
						put("X", 0L);
						put("Y", 0L);
						put("W", (long)indicate_width);
						put("H", (long)indicate_height);
						put("ALPHA", true);
					}});
					break;
				}

				case "NEW_FB": {
					String path = (String)message.get("PATH");

					if (vide_fb == null) {
						vide_fb = path;
						fb_cdl.countDown();
					} else {
						Indicate.open_fb(path);
						fb_cdl.countDown();
					}
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
