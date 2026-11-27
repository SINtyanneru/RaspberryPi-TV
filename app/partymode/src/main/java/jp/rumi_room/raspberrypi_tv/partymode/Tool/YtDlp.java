package jp.rumi_room.raspberrypi_tv.partymode.Tool;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

//yt-dlpのラッパー
public class YtDlp {
	final static ObjectMapper JSON_MAPPER = new ObjectMapper();
	final static Pattern PROGRESS_PATTERN = Pattern.compile("\\[download\\]\\s+(\\d+(?:\\.\\d+)?)%");
	final static Pattern TIME_PATTERN = Pattern.compile("time=(\\d+):(\\d+):([0-9.]+)");
	final static Pattern DURATION_PATTERN = Pattern.compile("Duration: (\\d+):(\\d+):([0-9.]+)");

	//ダウンロード進捗の通知先
	public interface ProgressListener {
		void on_progress(int percent);
	}

	//yt-dlpを実行して標準出力を文字列で返す
	private static String run_capture(List<String> command_list) throws Exception {
		Process process = new ProcessBuilder(command_list).start();
		StringBuilder result = new StringBuilder();
		BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
		String line;
		while ((line = reader.readLine()) != null) {
			result.append(line);
			result.append("\n");
		}
		int exit_code = process.waitFor();
		if (exit_code != 0) {
			StringBuilder error = new StringBuilder();
			BufferedReader error_reader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
			while ((line = error_reader.readLine()) != null) {
				error.append(line);
				error.append("\n");
			}
			throw new RuntimeException("yt-dlp failed (exit " + exit_code + "): " + error);
		}
		return result.toString();
	}

	private static List<String> make_command(String... args) {
		List<String> command_list = new ArrayList<String>();
		command_list.add("yt-dlp");
		for (String arg : args) {
			command_list.add(arg);
		}
		return command_list;
	}

	//動画の情報をJSONで取得する
	public static JsonNode get_info(URI url) throws Exception {
		String json = run_capture(make_command("-J", "--no-playlist", "--no-warnings", url.toString()));
		return JSON_MAPPER.readTree(json);
	}

	//サムネイル画像を取得する(無ければnull)
	//info.thumbnailはWebPのことがありImageIOで読めないため、thumbnails一覧から最大のJPEGを選ぶ
	public static BufferedImage get_thumbnail(URI url) throws Exception {
		JsonNode info = get_info(url);
		String thumbnail_url = null;
		JsonNode thumbnail_list = info.get("thumbnails");
		if (thumbnail_list != null) {
			int best_width = -1;
			for (JsonNode entry : thumbnail_list) {
				String entry_url = entry.get("url").asText();
				if (!entry_url.endsWith(".jpg")) {
					continue;
				}
				JsonNode width = entry.get("width");
				int entry_width = 0;
				if (width != null) {
					entry_width = width.asInt();
				}
				if (entry_width > best_width) {
					best_width = entry_width;
					thumbnail_url = entry_url;
				}
			}
		}
		if (thumbnail_url == null) {
			JsonNode thumbnail = info.get("thumbnail");
			if (thumbnail == null) {
				return null;
			}
			thumbnail_url = thumbnail.asText();
		}
		HttpClient client = HttpClient.newHttpClient();
		HttpRequest request = HttpRequest.newBuilder(URI.create(thumbnail_url)).GET().build();
		HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
		return ImageIO.read(new ByteArrayInputStream(response.body()));
	}

	//字幕の言語コード一覧を取得する(手動字幕→自動字幕の順、重複なし)
	public static List<String> get_subtitle_list(URI url) throws Exception {
		JsonNode info = get_info(url);
		List<String> language_list = new ArrayList<String>();
		JsonNode subtitles = info.get("subtitles");
		if (subtitles != null) {
			java.util.Iterator<String> field_itr = subtitles.fieldNames();
			while (field_itr.hasNext()) {
				String language = field_itr.next();
				if (!language_list.contains(language)) {
					language_list.add(language);
				}
			}
		}
		JsonNode automatic = info.get("automatic_captions");
		if (automatic != null) {
			java.util.Iterator<String> field_itr = automatic.fieldNames();
			while (field_itr.hasNext()) {
				String language = field_itr.next();
				if (!language_list.contains(language)) {
					language_list.add(language);
				}
			}
		}
		return language_list;
	}

	//動画をダウンロードする(字幕の埋め込み、複数音声トラックの結合あり)
	//subtitle_language_listが空なら全言語の字幕を取得する
	//ファイル名は「タイトル [動画ID]」になる
	public static boolean download(URI url, File out_dir, List<String> subtitle_language_list) throws Exception {
		return download_impl(url, out_dir, "%(title)s [%(id)s]", subtitle_language_list, null);
	}

	//ファイル名を指定してダウンロードする(拡張子は自動で付く)
	public static boolean download(URI url, File out_dir, String file_name, List<String> subtitle_language_list) throws Exception {
		return download_impl(url, out_dir, file_name, subtitle_language_list, null);
	}

	//進捗通知つきでダウンロードする(percentは0〜100、ファイルごとに0から数え直す)
	public static boolean download(URI url, File out_dir, String file_name, List<String> subtitle_language_list, ProgressListener listener) throws Exception {
		return download_impl(url, out_dir, file_name, subtitle_language_list, listener);
	}

	private static boolean download_impl(URI url, File out_dir, String file_name, List<String> subtitle_language_list, ProgressListener listener) throws Exception {
		String sub_langs;
		if (subtitle_language_list == null || subtitle_language_list.size() == 0) {
			sub_langs = "all";
		} else {
			StringBuilder builder = new StringBuilder();
			for (String language : subtitle_language_list) {
				if (builder.length() > 0) {
					builder.append(",");
				}
				builder.append(language);
			}
			sub_langs = builder.toString();
		}

		List<String> command_list = make_command(
			//再生時のデコードを軽くするため、H.264・1080pまでを優先する(無ければ次の候補へ)
			"-f", "bv*[vcodec^=avc1][height<=1080]+ba*/bv*[height<=1080]+ba*/bv*+ba*",
			"--audio-multistreams",
			"--merge-output-format", "mkv",
			"--write-subs",
			"--sub-langs", sub_langs,
			"--embed-subs",
			"--no-playlist",
			"-o", new File(out_dir, file_name + ".%(ext)s").getPath(),
			url.toString()
		);

		//進捗は全体を通しで報告する(ダウンロード0〜60、音量の計測60〜80、音量の適用80〜100)
		int[] last_percent = new int[]{-1};
		boolean success;
		if (listener == null) {
			Process process = new ProcessBuilder(command_list).inheritIO().start();
			success = process.waitFor() == 0;
		} else {
			//進捗行を1行ずつ受け取るため --newline を足し、[download] NN% の行を拾う
			command_list.add(1, "--newline");
			ProcessBuilder builder = new ProcessBuilder(command_list);
			builder.redirectErrorStream(true);
			Process process = builder.start();
			BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
			String line;
			while ((line = reader.readLine()) != null) {
				//stdoutはRSDFプロトコル用なので、yt-dlpの出力はstderrへ
				System.err.println(line);
				Matcher matcher = PROGRESS_PATTERN.matcher(line);
				if (matcher.find()) {
					int percent = (int) Double.parseDouble(matcher.group(1));
					fire_progress(listener, last_percent, percent * 60 / 100);
				}
			}
			success = process.waitFor() == 0;
		}
		if (!success) {
			return false;
		}

		//音量均一化はダウンロード時に済ませる(再生時にloudnormを掛けないため)。
		//失敗してもダウンロード自体は成功扱いにして、元のファイルを残す
		File out_file = new File(out_dir, file_name + ".mkv");
		if (out_file.exists()) {
			try {
				normalize_loudness(out_file, listener, last_percent);
			} catch (Exception ex) {
				System.err.println("音量均一化に失敗(元のファイルをそのまま使う): " + ex);
			}
		}
		if (listener != null) {
			fire_progress(listener, last_percent, 100);
		}
		return true;
	}

	//進捗を通知する(同じ値の連続通知はしない)
	private static void fire_progress(ProgressListener listener, int[] last_percent, int percent) {
		if (percent < 0) {
			percent = 0;
		}
		if (percent > 100) {
			percent = 100;
		}
		if (percent != last_percent[0]) {
			last_percent[0] = percent;
			listener.on_progress(percent);
		}
	}

	//音量を-14 LUFSへ均一化する。loudnormで計測→計測値を使って適用の2パス。
	//映像と字幕はそのままコピーし、音声だけ再エンコードする
	private static void normalize_loudness(File file, ProgressListener listener, int[] last_percent) throws Exception {
		List<String> measure_command = new ArrayList<>();
		measure_command.add("ffmpeg");
		measure_command.add("-hide_banner");
		measure_command.add("-i");
		measure_command.add(file.getPath());
		measure_command.add("-map");
		measure_command.add("0:a:0");
		measure_command.add("-af");
		measure_command.add("loudnorm=I=-14:TP=-1.0:LRA=11:print_format=json");
		measure_command.add("-f");
		measure_command.add("null");
		measure_command.add("-");
		String measure_output = run_ffmpeg(measure_command, listener, last_percent, 60, 20);

		int json_start = measure_output.lastIndexOf("{");
		int json_end = measure_output.lastIndexOf("}");
		if (json_start < 0 || json_end <= json_start) {
			throw new IOException("loudnormの計測結果が取れなかった");
		}
		JsonNode measured = JSON_MAPPER.readTree(measure_output.substring(json_start, json_end + 1));

		String filter = "loudnorm=I=-14:TP=-1.0:LRA=11"
			+ ":measured_I=" + measured.get("input_i").asText()
			+ ":measured_LRA=" + measured.get("input_lra").asText()
			+ ":measured_TP=" + measured.get("input_tp").asText()
			+ ":measured_thresh=" + measured.get("input_thresh").asText()
			+ ":offset=" + measured.get("target_offset").asText()
			+ ":linear=true";

		File tmp_file = new File(file.getParentFile(), file.getName().replace(".mkv", ".loud.mkv"));
		List<String> apply_command = new ArrayList<>();
		apply_command.add("ffmpeg");
		apply_command.add("-hide_banner");
		apply_command.add("-y");
		apply_command.add("-i");
		apply_command.add(file.getPath());
		apply_command.add("-map");
		apply_command.add("0");
		apply_command.add("-c");
		apply_command.add("copy");
		apply_command.add("-c:a");
		apply_command.add("libopus");
		apply_command.add("-b:a");
		apply_command.add("192k");
		apply_command.add("-af");
		apply_command.add(filter);
		apply_command.add(tmp_file.getPath());
		try {
			run_ffmpeg(apply_command, listener, last_percent, 80, 20);
		} catch (Exception ex) {
			tmp_file.delete();
			throw ex;
		}
		Files.move(tmp_file.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
	}

	//ffmpegを実行し、出力を全部返す。進捗はtime=の行から計算して通知する
	private static String run_ffmpeg(List<String> command_list, ProgressListener listener, int[] last_percent, int base_percent, int span_percent) throws Exception {
		ProcessBuilder builder = new ProcessBuilder(command_list);
		builder.redirectErrorStream(true);
		Process process = builder.start();
		StringBuilder output = new StringBuilder();
		InputStreamReader input = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8);
		char[] chunk = new char[4096];
		double duration_sec = -1;
		int read;
		while ((read = input.read(chunk)) >= 0) {
			output.append(chunk, 0, read);
			if (listener != null) {
				if (duration_sec < 0) {
					Matcher duration_matcher = DURATION_PATTERN.matcher(output);
					if (duration_matcher.find()) {
						duration_sec = Integer.parseInt(duration_matcher.group(1)) * 3600 + Integer.parseInt(duration_matcher.group(2)) * 60 + Double.parseDouble(duration_matcher.group(3));
					}
				}
				if (duration_sec > 0) {
					int from = Math.max(0, output.length() - 2048);
					Matcher time_matcher = TIME_PATTERN.matcher(output.substring(from));
					double current_sec = -1;
					while (time_matcher.find()) {
						current_sec = Integer.parseInt(time_matcher.group(1)) * 3600 + Integer.parseInt(time_matcher.group(2)) * 60 + Double.parseDouble(time_matcher.group(3));
					}
					if (current_sec >= 0) {
						fire_progress(listener, last_percent, base_percent + (int)(span_percent * current_sec / duration_sec));
					}
				}
			}
		}
		int exit_code = process.waitFor();
		if (exit_code != 0) {
			throw new IOException("ffmpeg exit code: " + exit_code);
		}
		return output.toString();
	}
}
