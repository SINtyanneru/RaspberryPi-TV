package jp.rumi_room.raspberrypi_tv.partymode.Tool;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStreamReader;
import java.net.URI;
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
			"-f", "bv*+ba*",
			"--audio-multistreams",
			"--merge-output-format", "mkv",
			"--write-subs",
			"--sub-langs", sub_langs,
			"--embed-subs",
			"--no-playlist",
			"-o", new File(out_dir, file_name + ".%(ext)s").getPath(),
			url.toString()
		);

		if (listener == null) {
			Process process = new ProcessBuilder(command_list).inheritIO().start();
			return process.waitFor() == 0;
		}

		//進捗行を1行ずつ受け取るため --newline を足し、[download] NN% の行を拾う
		command_list.add(1, "--newline");
		ProcessBuilder builder = new ProcessBuilder(command_list);
		builder.redirectErrorStream(true);
		Process process = builder.start();
		BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
		String line;
		int last_percent = -1;
		while ((line = reader.readLine()) != null) {
			//stdoutはRSDFプロトコル用なので、yt-dlpの出力はstderrへ
			System.err.println(line);
			Matcher matcher = PROGRESS_PATTERN.matcher(line);
			if (matcher.find()) {
				int percent = (int) Double.parseDouble(matcher.group(1));
				if (percent != last_percent) {
					last_percent = percent;
					listener.on_progress(percent);
				}
			}
		}
		boolean success = process.waitFor() == 0;
		if (success && last_percent != 100) {
			listener.on_progress(100);
		}
		return success;
	}
}
