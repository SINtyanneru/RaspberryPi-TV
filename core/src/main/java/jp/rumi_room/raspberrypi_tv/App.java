package jp.rumi_room.raspberrypi_tv;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jp.rumi_room.raspberrypi_tv.Type.AppInfo;
import jp.rumi_room.raspberrypi_tv.Type.DisplayFramebuffer;
import jp.rumi_room.raspberrypi_tv.Type.DisplayText;
import su.rumishistem.rsdf_java.RSDFDecoder;
import su.rumishistem.rsdf_java.RSDFEncoder;
import su.rumishistem.rumi_java_logger.SeverityLevel;

public class App {
	private final static String APP_DIR = "app/";

	private static Map<String, AppInfo> app_table = new HashMap<>();
	private static OutputStream app_out;

	private static List<DisplayFramebuffer> fb_list = new ArrayList<>();

	public static void init() throws IOException, InterruptedException {
		File dir = new File(APP_DIR);
		File[] file_list = dir.listFiles();
		if (file_list != null) {
			for (File file : file_list) {
				if (file.isDirectory() == false) continue;

				String app_id = UUID.randomUUID().toString();
				String app_name = "無名";
				String[] app_start = new String[0];

				Path meta_path = Path.of(file.getAbsolutePath(), "meta.txt");
				String meta_txt = Files.readString(meta_path);
				for (String line : meta_txt.split("\n")) {
					String[] parts = line.split("=");
					String name = parts[0];
					String value = parts[1];
					if (name.equals("NAME")) {
						app_name = value;
					}
					if (name.equals("START")) {
						app_start = value.split(" ");
					}
				}

				app_table.put(app_id, new AppInfo(app_id, app_name, file.getAbsolutePath(), app_start));
			}
		}
	}

	public static AppInfo[] get_list() {
		Set<String> id_list = app_table.keySet();
		AppInfo[] list = new AppInfo[id_list.size()];

		int i = 0;
		for (String id : id_list) {
			list[i] = app_table.get(id);
			i = i + 1;
		}

		return list;
	}

	public static void run(String id) throws IOException {
		AppInfo app = app_table.get(id);
		if (app == null) throw new RuntimeException("APP Start Error");

		DisplayText starting_text = Main.display.create_object(new DisplayText(120, Main.display.get_height() / 2, 96, "起動中...", 0x00, 0x00, 0x00, 0xFF));

		ProcessBuilder pb = new ProcessBuilder(app.start_cmd());
		pb.directory(new File(app.working_dir())); 
		pb.redirectErrorStream(true);

		Process p = pb.start();
		InputStream in = p.getInputStream();
		app_out = p.getOutputStream();

		new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					while (true) {
						byte[] header = in.readNBytes(4);
						if (header.length < 4) {
							break;
						}
						int length = ByteBuffer.wrap(header).getInt();

						byte[] body = in.readNBytes(length);
						if (body.length < length) {
							break;
						}

						Map<String, Object> message = RSDFDecoder.decode(body).get_dict();
						String mode = (String)message.get("TYPE");

						//メッセージに応じた処理
						if (mode.equals("READY")) {
							starting_text.delete();
							continue;
						}

						send_message(new HashMap<>(){{
							put("TYPE", "READY");
							put("WIDTH", Main.display.get_width());
							put("HEIGHT", Main.display.get_height());
						}});

						switch (mode) {
							case "NEW_FB": {
								long x = ((Number)message.get("X")).longValue();
								long y = ((Number)message.get("Y")).longValue();
								long width = ((Number)message.get("W")).longValue();
								long height = ((Number)message.get("H")).longValue();

								//表示サイズ＝ソースサイズで作る（スケーリングなし）
								DisplayFramebuffer fb = Main.display.create_object(new DisplayFramebuffer(x, y, width, height, width, height));
								fb_list.add(fb);

								send_message(new HashMap<>(){{
									put("TYPE", "NEW_FB");
									put("PATH", fb.get_socket_path());
								}});
								break;
							}

							case "LOG": {
								Main.logger.print(SeverityLevel.Informational, ((String)message.get("TEXT")));
								break;
							}
						}
					}

					//アプリが終了した
					for (DisplayFramebuffer fb : fb_list) {
						fb.delete();
					}
					Core.end_app();
				} catch (Exception ex) {
					ex.printStackTrace();
				}
			}
		}).start();
	}

	public static void send_key(byte keycode) throws IOException {
		send_message(new HashMap<>(){{
			put("TYPE", "EVENT_KEY");
			put("KEYCODE", (int)keycode);
		}});
	}

	private static void send_message(Map<String, Object> message) throws IOException{
		byte[] rsdf = RSDFEncoder.encode(message);
		app_out.write(ByteBuffer.allocate(4).putInt(rsdf.length).array());
		app_out.write(rsdf);
		app_out.flush();
	}
}