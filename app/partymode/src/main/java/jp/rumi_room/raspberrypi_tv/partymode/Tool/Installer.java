package jp.rumi_room.raspberrypi_tv.partymode.Tool;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jp.rumi_room.raspberrypi_tv.partymode.Main;

public class Installer {
	//コマンドが実行できるか(終了コード0か)で導入済みを判定する
	private  static boolean is_installed(String... command) {
		try {
			Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
			//出力を読み捨てないと、バッファが満杯でプロセスが止まることがある
			BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
			while (reader.readLine() != null) {
			}
			return process.waitFor() == 0;
		} catch (Exception e) {
			return false;
		}
	}
	
	//インストール系は出力をそのまま端末へ流す(sudoのパスワード入力もここで受ける)
	private static int run_install(String... command) throws Exception {
		Process process = new ProcessBuilder(command).inheritIO().start();
		return process.waitFor();
	}

	private static String get_yt_dlp_version() throws Exception {
		Process process = new ProcessBuilder("yt-dlp", "--version").redirectErrorStream(true).start();
		BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
		String version = reader.readLine();
		process.waitFor();
		return version;
	}

	private static String get_latest_version() throws Exception {
		HttpClient client = HttpClient.newHttpClient();
		HttpRequest request = HttpRequest.newBuilder(URI.create("https://pypi.org/pypi/yt-dlp/json")).GET().build();
		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
		JsonNode root = new ObjectMapper().readTree(response.body());
		return root.get("info").get("version").asText();
	}

	//yt-dlpのバージョンは2026.11.23のような日付形式なので、.区切りの数値として比較できる
	private static int compare_version(String version_a, String version_b) {
		String[] part_a = version_a.split("\\.");
		String[] part_b = version_b.split("\\.");
		int length = Math.max(part_a.length, part_b.length);
		for (int i = 0; i < length; i++) {
			int num_a = 0;
			int num_b = 0;
			if (i < part_a.length) {
				num_a = Integer.parseInt(part_a[i]);
			}
			if (i < part_b.length) {
				num_b = Integer.parseInt(part_b[i]);
			}
			if (num_a != num_b) {
				return num_a - num_b;
			}
		}
		return 0;
	}

	public static void install() throws Exception {
		if (!is_installed("yt-dlp", "--version")) {
			Main.send_message(new HashMap<>(){{
				put("TYPE", "LOG");
				put("TEXT", "pip経由でyt-dlpをインストール中...");
			}});

			run_install("python3", "-m", "pip", "install", "--break-system-packages", "yt-dlp");
		}

		if (!is_installed("ffmpeg", "-version")) {
			// Main.send_message(new HashMap<>(){{
			// 	put("TYPE", "LOG");
			// 	put("TEXT", "apt-get経由でffmpegをインストール中...");
			// }});

			// run_install("sudo", "apt-get", "install", "-y", "ffmpeg");

			Main.send_message(new HashMap<>(){{
				put("TYPE", "LOG");
				put("TEXT", "ffmpegが見つかりません");
			}});

			System.exit(1);
		}

		String current = get_yt_dlp_version();
		String latest = get_latest_version();
		if (compare_version(current, latest) < 0) {
			run_install("python3", "-m", "pip", "install", "--break-system-packages", "-U", "yt-dlp");
		}
	}
}
