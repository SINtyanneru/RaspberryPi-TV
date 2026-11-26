package jp.rumi_room.raspberrypi_tv;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import jp.rumi_room.raspberrypi_tv.Type.DisplayCircle;
import jp.rumi_room.raspberrypi_tv.Type.DisplayLine;
import jp.rumi_room.raspberrypi_tv.Type.DisplayRect;
import jp.rumi_room.raspberrypi_tv.Type.DisplayText;
import jp.rumi_room.raspberrypi_tv.Type.DisplayTriangle;
import su.rumishistem.rumi_java_logger.RumiJavaLogger;
import su.rumishistem.rumi_java_logger.SeverityLevel;

public class Main {
	public final static String ANSI = "\u001B";
	public final static String PACTL = "/usr/bin/pactl";
	public final static String ETC_DIR_PATH = "/etc/raspberrypi_tv";
	public static boolean DEV = false;
	public static String VERSION = "";
	public static String DISPLAY_PATH = "//TODO: kake";
	public static String HDMI_CEC_PATH = "//TODO: kake";

	public static HttpClient ajax = HttpClient.newHttpClient();
	public static RumiJavaLogger logger;
	public static HdmiCec hdmi_cec;
	public static Display display;

	public static void main(String[] args) throws Exception {
		for (String arg : args) {
			if (arg.equals("--dev")) {
				DEV = true;
				continue;
			}
		}

		if (DEV == false) {
			//tty0を出力側に
			PrintStream tty0 = new PrintStream(new FileOutputStream("/dev/tty0"), true);
			System.setOut(tty0);
			System.setErr(tty0);
		} else {
			DISPLAY_PATH = "./rtv-display";
			HDMI_CEC_PATH = "./rtv-cec";
		}

		//バージョンを取得
		InputStream is = Main.class.getResourceAsStream("/app.properties");
		try {
			if (is != null) {
				Properties p = new Properties();
				p.load(is);
				VERSION = p.getProperty("VERSION", VERSION);
			}
		} finally {
			is.close();
		}

		start();

		// Display display;

		// //背景
		// display.create_object(new DisplayRect(0, 0, 1920, 1080, 32, 36, 42, 255));
		// //図形いろいろ
		// display.create_object(new DisplayRect(120, 120, 480, 320, 255, 59, 48, 255));
		// display.create_object(new DisplayTriangle(760, 440, 1000, 120, 1240, 440, 255, 204, 0, 255));
		// display.create_object(new DisplayCircle(1560, 280, 160, 50, 200, 90, 255));
		// display.create_object(new DisplayLine(120, 560, 1800, 560, 255, 255, 255, 255));
		// //文字（UDEV Gothicで描かれる）
		// display.create_object(new DisplayText(120, 640, 96, "RaspberryPi TV", 255, 255, 255, 255));
		// display.create_object(new DisplayText(120, 780, 64, "by rumi-room.jp", 168, 237, 234, 255));

		// System.out.println("demo running (Ctrl+C to quit)");

		// //画面を出し続けるため、ここで待機する
		// Object lock = new Object();
		// synchronized (lock) {
		// 	lock.wait();
		// }
	}

	private static void start() throws InterruptedException {
		System.out.print(ANSI + "[1;1H");					//1, 1に移動
		System.out.print(ANSI + "[46m");					//背景色をシアンに
		System.out.print(ANSI + "[37m");					//文字色を白に
		System.out.print(ANSI + "[2K");						//行クリア
		System.out.print(" RaspberryPi TV\n");				//「ラズパイTV」 + 改行
		System.out.print(ANSI + "[2K");						//行クリア
		System.out.print("    Se rumi-room.jp");			//「rumi-room.jpが作った」

		//セルフチェック
		self_check();

		System.out.print("\n");

		System.out.println("    ____                   __                         ____  _    _______    __");
		System.out.println("   / __ \\____ __________  / /_  ___  ____________  __/ __ \\(_)  /_  __/ |  / /");
		System.out.println("  / /_/ / __ `/ ___/ __ \\/ __ \\/ _ \\/ ___/ ___/ / / / /_/ / /    / /  | | / / ");
		System.out.println(" / _, _/ /_/ (__  ) /_/ / /_/ /  __/ /  / /  / /_/ / ____/ /    / /   | |/ /  ");
		System.out.println("/_/ |_|\\__,_/____/ .___/_.___/\\___/_/  /_/   \\__, /_/   /_/    /_/    |___/   ");
		System.out.println("                /_/                         /____/      V" + VERSION);
		System.out.println("                                                        Se rumi-room.jp");
		System.out.println("Source code: https://github.com/SINtyanneru/RaspberryPi-TV");

		if (DEV == false) Thread.sleep(5000);

		//RJL
		logger = new RumiJavaLogger();
		try {
			logger.hijack_std(SeverityLevel.Debug);
			logger.print(SeverityLevel.Ok, "Initialized RumiJavaLogger");
		} catch (IOException ex) {
			ex.printStackTrace();
		}

		//アプリ
		try {
			App.init();
			logger.print(SeverityLevel.Ok, "Initialized APP");
		} catch (Exception ex) {
			ex.printStackTrace();
			logger.print(SeverityLevel.Error, "APP Initialize Error.");
		}

		//HDMI-CEC
		try {
			hdmi_cec = new HdmiCec(HDMI_CEC_PATH);
			hdmi_cec.init();
			hdmi_cec.start();
			hdmi_cec.set_osd_name("RaspberryPi TV");
			logger.print(SeverityLevel.Ok, "Initialized HDMI-CEC");
		} catch (Exception ex) {
			ex.printStackTrace();
			logger.print(SeverityLevel.Error, "HDMI-CEC Initialize Error.");
		}

		//HTTP Server
		new Thread(new Runnable() {
			@Override
			public void run() {
			try {
				HTTPServer.start();
				logger.print(SeverityLevel.Ok, "Started HTTP Server.");
			} catch (Exception ex) {
				ex.printStackTrace();
				logger.print(SeverityLevel.Error, "HTTP Server Start Error.");
			}
			}
		}).run();

		//Display
		try {
			if (DEV) {
				display = new Display("./rtv-display", "--backend", "window");
			} else {
				display = new Display("./rtv-display", "--backend", "drm");
			}
			display.init();
			display.start();

			logger.print(SeverityLevel.Ok, "Initialized Display");
		} catch (Exception ex) {
			ex.printStackTrace();
			logger.print(SeverityLevel.Error, "DisplayC Initialize Error.");
		}

		try {
			Core.start();
		} catch (IOException ex) {
			ex.printStackTrace();
			System.exit(1);
		}
	}

	private static void self_check() {
		System.out.print(ANSI + "[0m");							//色初期化
		System.out.print(ANSI + "[5;0H");						//5行目に移動
		System.out.print("SELF CHECK IN PROGRESS\n");			//セルフチェック中

		//HDMI-CEC
		if (Files.exists(Path.of(HDMI_CEC_PATH)) == false) {
			error_println("HDMI-CEC Process not found, reinstall required.");
			System.exit(1);
		}
		ok_println("HDMI-CEC Process OK.");

		//Display
		if (Files.exists(Path.of(HDMI_CEC_PATH)) == false) {
			error_println("Display Process not found, reinstall required.");
			System.exit(1);
		}
		ok_println("Display Process OK.");

		//DRM/KMS(Wayだからチェック不要かも)
		if (Files.exists(Path.of("/dev/fb0")) == false) {
			error_println("Require DRM/KMS.");
			System.exit(1);
		}
		ok_println("DRM/KMS OK.");

		//Pipewire-pulse
		if (Files.exists(Path.of("/usr/bin/pipewire-pulse")) == false) {
			error_println("Require pipewire-pulse");
			System.exit(1);
		}
		ok_println("Pipewire-pulse OK.");

		//Pipewire-pulse
		if (Files.exists(Path.of(PACTL)) == false) {
			error_println("Require pactl");
			System.exit(1);
		}
		ok_println("pactl OK.");

		//etcのディレクトリ
		if (Files.exists(Path.of(ETC_DIR_PATH)) == false) {
			error_println("Pls \"mkdir " + ETC_DIR_PATH + "\".");
			System.exit(1);
		}
		ok_println("etc dir OK.");

		//インターネット
		HttpRequest.Builder request_builder = HttpRequest.newBuilder();
		request_builder.uri(URI.create("https://rumi-room.net/"));
		request_builder.GET();
		HttpRequest request = request_builder.build();
		try {
			ajax.send(request, HttpResponse.BodyHandlers.ofString());
			ok_println("Internet connection OK.");
		} catch (Exception e) {
			error_println("Internet connection Error");
			System.exit(1);
		}
	}

	public static void ok_println(String message) {
		System.out.print(ANSI + "[42m");							//背景色を緑に
		System.out.print(ANSI + "[37m");							//文字色を白に
		System.out.print(message);
		System.out.print(ANSI + "[0m");								//色初期化
		System.out.print("\n");
		System.out.print("\n");
	}

	public static void error_println(String message) {
		System.out.print(ANSI + "[41m");							//背景色を赤に
		System.out.print(ANSI + "[37m");							//文字色を白に
		System.out.print(message);
		System.out.print(ANSI + "[0m");								//色初期化
		System.out.print("\n");
		System.out.print("\n");
	}
}
