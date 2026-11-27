package jp.rumi_room.raspberrypi_tv.partymode;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import javax.imageio.ImageIO;

import com.fasterxml.jackson.databind.JsonNode;

import jp.rumi_room.raspberrypi_tv.partymode.Tool.YtDlp;
import jp.rumi_room.raspberrypi_tv.partymode.Type.ObservableList;
import jp.rumi_room.raspberrypi_tv.partymode.Type.PlayItem;

public class Core {
	private static Font font;

	private static ObservableList<PlayItem> play_queue = new ObservableList<>();
	private static BlockingQueue<PlayItem> download_queue = new ArrayBlockingQueue<>(100);

	public static void main() throws InterruptedException {
		try {
			font = Font.createFont(Font.TRUETYPE_FONT, new File(System.getProperty("user.home"), ".local/share/fonts/UDEVGothic-Regular.ttf"));
		} catch (Exception e) {
			System.exit(1);
		}

		while (true) {
			PlayItem pi = download_queue.take();
			//動画をダウンロード
			try {
				Path cache_dir = Main.cache_dir.resolve(get_video_id(pi.get_url()));
				boolean ok = YtDlp.download(pi.get_url(), cache_dir.toFile(), "video", new ArrayList<>(), new YtDlp.ProgressListener() {
					@Override
					public void on_progress(int percent) {
						Indicate.get_g().setComposite(AlphaComposite.getInstance(AlphaComposite.SRC));
						Indicate.get_g().setColor(new Color(0, 0, 0, 0));
						Indicate.get_g().fillRect(0, 0, Main.indicate_width, Main.indicate_height);

						Indicate.get_g().setFont(font.deriveFont(32.0f));
						Indicate.get_g().setColor(Color.WHITE);
						Indicate.get_g().drawString(percent + "%", 0, 0);

						Indicate.redraw();

						pi.set_download_progress(percent);
					}
				});

				if (ok == false) {
					pi.set_error(true);
					continue;
				}

				pi.set_downloaded(true);
			} catch (Exception ex) {
				pi.set_error(true);
				continue;
			}
		}
	}

	/**
	 * プレイリストにイベントハンドラする
	 */
	public static void add_playqueue_listener(ObservableList.ChangeListener<PlayItem> listener) {
		play_queue.add_listener(listener);
	}

	/**
	 * プレイリストのイベントハンドラを解除する
	 */
	public static void detach_playqueue_listener(ObservableList.ChangeListener<PlayItem> listener) {
		play_queue.remove_listener(listener);
	}

	public static boolean add_play(String input_url, InetAddress adder) throws Exception{
		URI url = URI.create(input_url);

		//Youtube
		if (url.getHost().equals("www.youtube.com") || url.getHost().equals("youtu.be")) {
			if ("youtu.be".equals(url.getHost())) {
				String id = url.getPath().substring(1); //先頭の "/" を落とす
				url = URI.create("https://www.youtube.com/watch?v=" + id);
			}

			String video_id;
			String title;
			String author;
			BufferedImage thumbnail;
			Path cache_dir;

			//動画の概要を取得
			JsonNode info = YtDlp.get_info(url);
			video_id = info.get("id").asText().replaceAll("[^A-Za-z0-9_-]", "_");
			title = info.get("title").asText();
			author = info.get("uploader").asText();

			cache_dir = Main.cache_dir.resolve(video_id);
			Files.createDirectories(cache_dir);

			//サムネイルを取得
			Path thumbnail_path = cache_dir.resolve("thumbnail.jpeg");
			if (Files.exists(thumbnail_path)) {
				thumbnail = ImageIO.read(thumbnail_path.toFile());
			} else {
				thumbnail = YtDlp.get_thumbnail(url);
				if (thumbnail == null) {
					throw new IllegalStateException("なぜかサムネがnull");
				}
				ImageIO.write(thumbnail, "jpg", thumbnail_path.toFile());
			}

			PlayItem pi = new PlayItem(
				UUID.randomUUID(),
				LocalDateTime.now(),
				adder,
				url,

				title,
				author,
				thumbnail,

				false,
				false,
				0
			);

			play_queue.add(pi);
			download_queue.add(pi);

			return true;
		}

		return false;
	}

	private static String get_video_id(URI uri) {
		String query = uri.getQuery();
		if (query == null) {
			return null;
		}
		for (String param : query.split("&")) {
			String[] pair = param.split("=", 2);
			if (pair.length == 2 && pair[0].equals("v")) {
				return pair[1];
			}
		}
		return null;
	}
}
