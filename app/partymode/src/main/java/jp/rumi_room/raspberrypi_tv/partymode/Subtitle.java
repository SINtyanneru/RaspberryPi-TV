package jp.rumi_room.raspberrypi_tv.partymode;

import java.awt.*;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.image.*;
import java.io.File;
import java.io.IOException;
import java.net.UnixDomainSocketAddress;
import java.nio.*;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

//字幕を映像の上に重ねるフレームバッファ(地デジの字幕のように、白文字に黒い縁取りで下部中央に出す)
public class Subtitle {
	public static class Cue {
		public final long start_ms;
		public final long end_ms;
		public final String text;

		public Cue(long start_ms, long end_ms, String text) {
			this.start_ms = start_ms;
			this.end_ms = end_ms;
			this.text = text;
		}
	}

	private static SocketChannel fb = null;
	private static BufferedImage image;
	private static Graphics2D g;
	private static Font font;

	private static List<Cue> cue_list = new ArrayList<>();
	private static Cue shown_cue = null;

	//字幕の表示位置(キューが変わるたびに下部の中でランダムに決める)
	private static Random random = new Random();
	private static boolean position_dirty = false;
	private static int box_x = 0;
	private static int box_bottom = 0;

	public static void open_fb(String path) throws IOException {
		fb = SocketChannel.open(UnixDomainSocketAddress.of(path));
		image = new BufferedImage(Main.screen_width, Main.screen_height, BufferedImage.TYPE_INT_ARGB);
		g = image.createGraphics();
		try {
			font = Font.createFont(Font.TRUETYPE_FONT, new File(System.getProperty("user.home"), ".local/share/fonts/UDEVGothic-Regular.ttf"));
		} catch (Exception ex) {
			font = new Font(Font.SANS_SERIF, Font.PLAIN, 48);
		}
		draw();
	}

	//字幕データを差し替える
	public static synchronized void set_cue_list(List<Cue> list) {
		cue_list = list;
		shown_cue = null;
		draw();
	}

	//字幕を消す
	public static synchronized void clear_cue() {
		cue_list = new ArrayList<>();
		if (shown_cue != null) {
			shown_cue = null;
			draw();
		}
	}

	//再生位置に合った字幕を表示する(表示が変わる時だけ描き直す)
	public static synchronized void update(long position_ms) {
		if (fb == null) {
			return;
		}
		Cue active = null;
		for (Cue cue : cue_list) {
			if (position_ms >= cue.start_ms && position_ms < cue.end_ms) {
				active = cue;
				break;
			}
		}
		if (active == shown_cue) {
			return;
		}
		shown_cue = active;
		if (active != null) {
			position_dirty = true;
		}
		draw();
	}

	private static void draw() {
		if (fb == null) {
			return;
		}

		//全透明でクリア
		g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC));
		g.setColor(new Color(0, 0, 0, 0));
		g.fillRect(0, 0, Main.screen_width, Main.screen_height);
		g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER));

		if (shown_cue != null) {
			int font_size = Main.screen_height * 48 / 1080;
			Font draw_font = font.deriveFont((float) font_size);
			g.setFont(draw_font);
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

			String[] line_list = shown_cue.text.split("\n");
			FontMetrics metrics = g.getFontMetrics();
			int line_height = font_size * 5 / 4;
			int margin_bottom = Main.screen_height * 40 / 1080;
			int pad_x = font_size / 2;
			int pad_y = font_size / 4;

			int max_width = 0;
			for (String line : line_list) {
				int width = metrics.stringWidth(line);
				if (width > max_width) {
					max_width = width;
				}
			}
			int box_width = max_width + pad_x * 2;
			int box_height = metrics.getAscent() + metrics.getDescent() + line_height * (line_list.length - 1) + pad_y * 2;

			//表示位置はキューごとに下部の中でランダムに決める(地デジの字幕が位置指定で動くのの真似)
			if (position_dirty) {
				position_dirty = false;
				int margin_x = font_size;
				int span_x = Main.screen_width - box_width - margin_x * 2;
				if (span_x > 0) {
					box_x = margin_x + random.nextInt(span_x);
				} else {
					box_x = (Main.screen_width - box_width) / 2;
				}
				int default_bottom = Main.screen_height - margin_bottom + metrics.getDescent() + pad_y;
				box_bottom = default_bottom - random.nextInt(Main.screen_height / 4 + 1);
			}

			//字幕の後ろに半透明の四角形を出して見やすくする
			g.setColor(new Color(0, 0, 0, 160));
			g.fillRect(box_x, box_bottom - box_height, box_width, box_height);

			//一番下の行のベースラインから、上へ積む
			int base_y = box_bottom - pad_y - metrics.getDescent();
			int box_center_x = box_x + box_width / 2;
			for (int i = line_list.length - 1; i >= 0; i--) {
				String line = line_list[i];
				if (line.length() > 0) {
					int line_width = metrics.stringWidth(line);
					int x = box_center_x - line_width / 2;
					draw_outlined_text(line, x, base_y, draw_font, font_size);
				}
				base_y -= line_height;
			}
		}

		redraw();
	}

	//白文字に黒い縁取りで描く
	private static void draw_outlined_text(String text, int x, int y, Font draw_font, int font_size) {
		TextLayout layout = new TextLayout(text, draw_font, g.getFontRenderContext());
		Shape outline = layout.getOutline(AffineTransform.getTranslateInstance(x, y));
		g.setStroke(new BasicStroke(font_size / 8.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.setColor(Color.BLACK);
		g.draw(outline);
		g.setColor(Color.WHITE);
		g.fill(outline);
	}

	public static void redraw() {
		int[] pixel_list = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
		ByteBuffer buffer = ByteBuffer.allocateDirect(Main.screen_width * Main.screen_height * 4);
		buffer.order(ByteOrder.nativeOrder());

		try {
			//1フレーム送信(imageに描いた後で呼ぶ)
			buffer.asIntBuffer().put(pixel_list);
			buffer.position(0);
			while (buffer.hasRemaining()) {
				fb.write(buffer);
			}
		} catch (Exception ex) {
		}
	}

	//SRT形式の字幕を解析する
	public static List<Cue> parse_srt(String content) {
		List<Cue> list = new ArrayList<>();
		String[] line_list = content.replace("\r\n", "\n").split("\n");
		int i = 0;
		while (i < line_list.length) {
			if (line_list[i].contains(" --> ") == false) {
				i++;
				continue;
			}
			try {
				String[] parts = line_list[i].split(" --> ");
				long start_ms = parse_time(parts[0].trim());
				long end_ms = parse_time(parts[1].trim().split(" ")[0]);
				i++;
				StringBuilder text = new StringBuilder();
				while (i < line_list.length && line_list[i].length() > 0) {
					if (text.length() > 0) {
						text.append("\n");
					}
					text.append(line_list[i].replaceAll("<[^>]*>", ""));
					i++;
				}
				list.add(new Cue(start_ms, end_ms, text.toString()));
			} catch (Exception ex) {
				i++;
			}
		}
		return list;
	}

	//HH:MM:SS,mmmをミリ秒にする
	private static long parse_time(String time) {
		String[] hms = time.split(":");
		long hour = Long.parseLong(hms[0]);
		long minute = Long.parseLong(hms[1]);
		String[] sec = hms[2].split(",");
		long second = Long.parseLong(sec[0]);
		long ms = Long.parseLong(sec[1]);
		return ((hour * 60 + minute) * 60 + second) * 1000 + ms;
	}
}
