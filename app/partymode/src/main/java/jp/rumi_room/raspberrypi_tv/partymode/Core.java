package jp.rumi_room.raspberrypi_tv.partymode;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jp.rumi_room.raspberrypi_tv.partymode.Tool.YtDlp;
import jp.rumi_room.raspberrypi_tv.partymode.Type.*;

public class Core {
	private final static ObjectMapper JSON_MAPPER = new ObjectMapper();

	private static Font font;
	private static PlayItem download_current_item = null;
	private static int download_current_percent = 0;
	private static int download_title_scroll = 0;
	private static String indicate_error_text = null;
	private static long indicate_error_until = 0;

	private static ObservableList<PlayItem> play_queue = new ObservableList<>();
	private static BlockingQueue<PlayItem> download_queue = new ArrayBlockingQueue<>(100);

	public static void main() throws InterruptedException {
		try {
			font = Font.createFont(Font.TRUETYPE_FONT, new File(System.getProperty("user.home"), ".local/share/fonts/UDEVGothic-Regular.ttf"));
		} catch (Exception e) {
			System.exit(1);
		}

		//タイトルスクロール用スレッド
		new Thread(new Runnable() {
			@Override
			public void run() {
				while (true) {
					//エラー表示の期限が来たら消す
					synchronized (Core.class) {
						if (indicate_error_text != null && System.currentTimeMillis() >= indicate_error_until) {
							indicate_error_text = null;
							clear_indicate();
						}
					}
					if (download_current_item != null) {
						download_title_scroll += 2;
						draw_indicate();
					}
					try {
						Thread.sleep(33);
					} catch (InterruptedException e) {
						return;
					}
				}
			}
		}).start();

		//再生用スレッド
		new Thread(new Runnable() {
			@Override
			public void run() {
				Player.main();
			}
		}).start();

		while (true) {
			PlayItem pi = download_queue.take();
			if (pi.get_type() != PlayItemType.YoutubeVideo) continue;

			try {
				Path cache_dir = Main.cache_dir.resolve(get_video_id(pi.get_url()));
				if (Files.exists(cache_dir.resolve("video.mkv"))) {
					pi.set_downloaded(true);
					send_log("再生キューに追加(キャッシュ済み): " + pi.get_title());
					Player.add_wait_queue(pi);
					continue;
				}

				download_current_item = pi;
				download_current_percent = 0;
				download_title_scroll = 0;
				boolean ok = YtDlp.download(pi.get_url(), cache_dir.toFile(), "video", new ArrayList<>(), new YtDlp.ProgressListener() {
					@Override
					public void on_progress(int percent) {
						download_current_percent = percent;
						pi.set_download_progress(percent);
						draw_indicate();
					}
				});

				download_current_item = null;
				clear_indicate();

				if (ok == false) {
					send_log("ダウンロード失敗(yt-dlpがエラー終了): " + pi.get_title());
					show_indicate_error("ダウンロード失敗: " + pi.get_title());
					pi.set_error(true);
					continue;
				}

				pi.set_downloaded(true);

				send_log("再生キューに追加: " + pi.get_title());
				Player.add_wait_queue(pi);
			} catch (Exception ex) {
				download_current_item = null;
				StringWriter string_writer = new StringWriter();
				ex.printStackTrace(new PrintWriter(string_writer));
				send_log(string_writer.toString());
				show_indicate_error("エラー: " + pi.get_title());
				pi.set_error(true);
				continue;
			}
		}
	}

	//Indicateを全透明にして消す
	private static synchronized void clear_indicate() {
		Indicate.get_g().setComposite(AlphaComposite.getInstance(AlphaComposite.SRC));
		Indicate.get_g().setColor(new Color(0, 0, 0, 0));
		Indicate.get_g().fillRect(0, 0, Main.indicate_width, Main.indicate_height);
		Indicate.redraw();
	}

	//Indicateにエラーを5秒間出す(期限が来たらスクロール用スレッドが消す)
	private static synchronized void show_indicate_error(String text) {
		indicate_error_text = text;
		indicate_error_until = System.currentTimeMillis() + 5000;

		Graphics2D g = Indicate.get_g();
		g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC));
		g.setColor(new Color(0, 0, 0, 0));
		g.fillRect(0, 0, Main.indicate_width, Main.indicate_height);
		g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER));
		g.setColor(new Color(60, 0, 0, 160));
		g.fillRect(0, 0, Main.indicate_width, Main.indicate_height);
		g.setFont(font.deriveFont(32.0f));
		g.setColor(new Color(255, 150, 150));
		FontMetrics metrics = g.getFontMetrics();
		g.drawString(text, 20, (Main.indicate_height - metrics.getHeight()) / 2 + metrics.getAscent());
		Indicate.redraw();
	}

	//親へLOGを送る(失敗しても本処理は止めない)
	private static void send_log(String text) {
		try {
			Main.send_message(new HashMap<>(){{
				put("TYPE", "LOG");
				put("TEXT", text);
			}});
		} catch (Exception ignored) {
		}
	}

	private static synchronized void draw_indicate() {
		PlayItem pi = download_current_item;
		if (pi == null) {
			return;
		}
		int percent = download_current_percent;

		Indicate.get_g().setComposite(AlphaComposite.getInstance(AlphaComposite.SRC));
		Indicate.get_g().setColor(new Color(0, 0, 0, 0));
		Indicate.get_g().fillRect(0, 0, Main.indicate_width, Main.indicate_height);

		Indicate.get_g().setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER));
		Indicate.get_g().setColor(new Color(0, 0, 0, 128));
		Indicate.get_g().fillRect(0, 0, Main.indicate_width, Main.indicate_height);

		//サムネ
		BufferedImage thumbnail = pi.get_thumbnail();
		int thumb_height = Main.indicate_height;
		int thumb_width = pi.get_thumbnail().getWidth() * thumb_height / pi.get_thumbnail().getHeight();
		Indicate.get_g().setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		Indicate.get_g().drawImage(thumbnail, 0, 0, thumb_width, thumb_height, null);

		//進捗
		String text = percent + "%";
		Indicate.get_g().setFont(font.deriveFont(32.0f));
		FontMetrics metrics = Indicate.get_g().getFontMetrics();
		int text_slot = metrics.stringWidth("100%") + 10;

		//進捗バー
		int bar_height = 20;
		int bar_x = thumb_width + 50;
		int bar_width = Main.indicate_width - bar_x - 50 - text_slot;
		int bar_y = Main.indicate_height - bar_height - 10;

		//進捗バー背景
		Indicate.get_g().setColor(Color.WHITE);
		Indicate.get_g().fillRect(bar_x, bar_y, bar_width, bar_height);

		//進捗バー
		int progress_width = bar_width * percent / 100;
		Indicate.get_g().setColor(Color.GREEN);
		Indicate.get_g().fillRect(bar_x, bar_y, progress_width, bar_height);

		//進捗
		int text_x = bar_x + bar_width + 10;
		int text_y = bar_y + (bar_height - metrics.getHeight()) / 2 + metrics.getAscent();
		Indicate.get_g().setColor(Color.WHITE);
		Indicate.get_g().drawString(text, text_x, text_y);

		//タイトル
		String title_text = "Youtube - " + pi.get_title();
		int title_x = thumb_width + 10;
		int title_limit = Main.indicate_width - title_x - 10;
		Indicate.get_g().setColor(Color.WHITE);
		Indicate.get_g().setFont(font.deriveFont(48.0f));
		FontMetrics title_metrics = Indicate.get_g().getFontMetrics();
		int title_y = title_metrics.getAscent();
		int title_width = title_metrics.stringWidth(title_text);
		if (title_width > title_limit) {
			int cycle = title_width + 50;
			int offset = download_title_scroll % cycle;
			Shape original_clip = Indicate.get_g().getClip();
			Indicate.get_g().setClip(title_x, 0, title_limit, Main.indicate_height);
			Indicate.get_g().drawString(title_text, title_x - offset, title_y);
			Indicate.get_g().drawString(title_text, title_x - offset + cycle, title_y);
			Indicate.get_g().setClip(original_clip);
		} else {
			Indicate.get_g().drawString(title_text, title_x, title_y);
		}

		Indicate.redraw();
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

	public static ObservableList<PlayItem> get_play_queue() {
		return play_queue;
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

			//動画の概要はキャッシュ(info.json)があればそれを使う。無ければ取得してキャッシュへ書く
			String url_video_id = get_video_id(url);
			Path url_cache_dir = null;
			if (url_video_id != null) {
				url_cache_dir = Main.cache_dir.resolve(url_video_id.replaceAll("[^A-Za-z0-9_-]", "_"));
			}
			JsonNode cached_info = null;
			if (url_cache_dir != null && Files.exists(url_cache_dir.resolve("info.json"))) {
				try {
					cached_info = JSON_MAPPER.readTree(Files.readString(url_cache_dir.resolve("info.json")));
				} catch (Exception ex) {
					cached_info = null;
				}
			}

			if (cached_info != null) {
				video_id = cached_info.get("id").asText();
				title = cached_info.get("title").asText();
				author = cached_info.get("author").asText();
				cache_dir = url_cache_dir;
			} else {
				//動画の概要を取得
				JsonNode info = YtDlp.get_info(url);
				video_id = info.get("id").asText().replaceAll("[^A-Za-z0-9_-]", "_");
				title = info.get("title").asText();
				author = info.get("uploader").asText();

				cache_dir = Main.cache_dir.resolve(video_id);
				Files.createDirectories(cache_dir);

				//次回からは取得しなくて済むよう、概要をキャッシュへ書く
				try {
					Map<String, Object> info_map = new HashMap<>();
					info_map.put("id", video_id);
					info_map.put("title", title);
					info_map.put("author", author);
					Files.writeString(cache_dir.resolve("info.json"), JSON_MAPPER.writeValueAsString(info_map));
				} catch (Exception ex) {
					//キャッシュは書けなくても続行する
				}
			}

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
				cache_dir.resolve("video.mkv"),
				PlayItemType.YoutubeVideo,

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
