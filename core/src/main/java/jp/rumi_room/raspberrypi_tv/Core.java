package jp.rumi_room.raspberrypi_tv;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import jp.rumi_room.raspberrypi_tv.Type.AppInfo;
import jp.rumi_room.raspberrypi_tv.Type.DisplayRect;
import jp.rumi_room.raspberrypi_tv.Type.DisplayText;
import jp.rumi_room.raspberrypi_tv.Type.TopMenu.TopMenuCategory;
import jp.rumi_room.raspberrypi_tv.Type.TopMenu.TopMenuItem;

public class Core {
	public static BlockingQueue<Byte> key_queue = new LinkedBlockingQueue<>();

	//メニュー関係
	private static boolean menu_open = false;
	private static long menu_anim_start = 0;
	private static boolean menu_anim_open;
	private static long menu_last_x = Long.MIN_VALUE;
	private static boolean menu_animing = false;
	private static DisplayRect menu_base;
	private static int menu_width;
	private static final long MENU_PHASE1_MS = 250;
	private static final long MENU_PHASE2_MS = 200;
	private static final long MENU_INNER_MARGIN = 100;
	private static DisplayRect menu_inner;
	private static int menu_phase = 0;
	private static long menu_inner_last_y = Long.MIN_VALUE;
	private static long menu_inner_last_height = Long.MIN_VALUE;

	//トップメニュー関係
	private static boolean topmenu_now = true;
	private static List<TopMenuCategory> topmenu_category = new ArrayList<>();
	private final static long TOPMENU_ANIM_MS = 200;
	private final static long TOPMENU_CATEGORY_TEXT_SIZE = 44;
	private final static long TOPMENU_ITEM_TEXT_SIZE = 40;
	private final static long TOPMENU_CATEGORY_PITCH = 280;	//カテゴリ同士の間隔
	private final static long TOPMENU_ITEM_PITCH = 76;		//アイテム同士の間隔
	private static int topmenu_select_col = 0;
	private static int topmenu_select_row = 0;
	private static double topmenu_disp_col = 0;	//表示上の列位置（アニメーション用、小数）
	private static double topmenu_disp_row = 0;
	private static double topmenu_from_col = 0;
	private static double topmenu_from_row = 0;
	private static long topmenu_col_anim_start = 0;
	private static long topmenu_row_anim_start = 0;
	private static boolean topmenu_dirty = true;
	private static List<DisplayText> topmenu_category_text_list = new ArrayList<>();
	private static List<List<DisplayText>> topmenu_item_text_list = new ArrayList<>();
	private static DisplayRect topmenu_cursor;

	//APP
	private static boolean runing_app = false;

	public static void start() throws IOException {
		//メニュー初期化
		menu_width = value_clamp(500, Main.display.get_width() / 4);
		menu_base = Main.display.create_object(new DisplayRect(menu_width * -1, 0, menu_width, Main.display.get_height(), 0xFF, 0xFF, 0xFF, 0xFF));
		menu_inner = Main.display.create_object(new DisplayRect(0, Main.display.get_height() / 2, menu_width, 0, 0x30, 0x90, 0xFF, 0xB0));

		//トップメニュー初期化
		topmenu_category.add(new TopMenuCategory("SETTING", "設定", new ArrayList<>(){{
			add(new TopMenuItem("WHAT", "これについて", Optional.empty()));
			add(new TopMenuItem("UPDATE", "アップデート", Optional.empty()));
			add(new TopMenuItem("OPEN_SHELL", "シェルを開く", Optional.empty()));
		}}));
		topmenu_category.add(new TopMenuCategory("APP", "アプリ", new ArrayList<>()));

		for (AppInfo app : App.get_list()) {
			topmenu_category.get(1).item_list().add(new TopMenuItem("APP-" + app.id(), app.name(), Optional.of(new Runnable() {
					@Override
					public void run() {
						try {
							hide_topmenu();
						} catch (IOException ex) {
						}

						runing_app = true;

						try {
							App.run(app.id());
						} catch (IOException ex) {
						}
					}
				})
			));
		}

		init_topmenu_objects();

		//メインループ
		while (true) {
			key_receive();

			draw();
		}
	}

	public static void end_app() {
		runing_app = false;
		show_topmenu();
	}

	private static int value_clamp(int min, int value) {
		if (value < min) {
			return min;
		}
		return value;
	}

	private static void draw() throws IOException {
		menu_draw();
		draw_topmenu();
	}

	private static void show_topmenu() {
		topmenu_now = true;
		topmenu_dirty = true;
	}

	private static void hide_topmenu() throws IOException {
		topmenu_now = false;
		
		for (DisplayText text : topmenu_category_text_list) {
			text.set_color(0x00, 0x00, 0x00, 0x00);
			text.update();
		}
		for (List<DisplayText> item_text_list : topmenu_item_text_list) {
			for (DisplayText text : item_text_list) {
				text.set_color(0x00, 0x00, 0x00, 0x00);
				text.update();
			}
		}
		topmenu_cursor.set_color(0x00, 0x00, 0x00, 0x00);
		topmenu_cursor.update();
	}

	private static void init_topmenu_objects() throws IOException {
		long bar_y = Main.display.get_height() / 3;

		//カーソルはアイテムのテキストより先に作って下に敷く
		topmenu_cursor = Main.display.create_object(new DisplayRect(0, 0, 0, 0, 0x30, 0x90, 0xFF, 0x80));

		for (TopMenuCategory category : topmenu_category) {
			topmenu_category_text_list.add(Main.display.create_object(new DisplayText(0, bar_y, TOPMENU_CATEGORY_TEXT_SIZE, category.name(), 0x00, 0x00, 0x00, 0xFF)));
			List<DisplayText> items = new ArrayList<>();
			for (TopMenuItem item : category.item_list()) {
				//アイテムは最初は透明（alpha 0 = display側で描画自体スキップされる）
				items.add(Main.display.create_object(new DisplayText(0, 0, TOPMENU_ITEM_TEXT_SIZE, item.name(), 0xFF, 0xFF, 0xFF, 0)));
			}
			topmenu_item_text_list.add(items);
		}
	}

	//UDEV Gothicは全角=サイズ、半角=サイズ/2として幅を概算する
	private static long text_width(String text, long size) {
		long width = 0;
		for (int i = 0; i < text.length();) {
			int cp = text.codePointAt(i);
			if (cp > 0xFF) {
				width += size;
			} else {
				width += size / 2;
			}
			i += Character.charCount(cp);
		}
		return width;
	}

	private static void draw_topmenu() throws IOException {
		if (topmenu_now == false) return;

		long now = System.currentTimeMillis();
		boolean animating = false;
		if (topmenu_col_anim_start != 0) {
			double t = (now - topmenu_col_anim_start) / (double) TOPMENU_ANIM_MS;
			if (t >= 1) {
				t = 1;
				topmenu_col_anim_start = 0;
			} else {
				animating = true;
			}
			double eased = 1 - Math.pow(1 - t, 3);
			topmenu_disp_col = topmenu_from_col + (topmenu_select_col - topmenu_from_col) * eased;
		}
		if (topmenu_row_anim_start != 0) {
			double t = (now - topmenu_row_anim_start) / (double) TOPMENU_ANIM_MS;
			if (t >= 1) {
				t = 1;
				topmenu_row_anim_start = 0;
			} else {
				animating = true;
			}
			double eased = 1 - Math.pow(1 - t, 3);
			topmenu_disp_row = topmenu_from_row + (topmenu_select_row - topmenu_from_row) * eased;
		}
		if (!animating && !topmenu_dirty) return;

		long center_x = Main.display.get_width() / 2;
		long bar_y = Main.display.get_height() / 3;
		long item_anchor_y = bar_y + 150;	//選択中アイテムの固定位置

		//カテゴリを横に並べる（選択中が中央に来る）
		for (int i = 0; i < topmenu_category_text_list.size(); i++) {
			DisplayText text = topmenu_category_text_list.get(i);
			long slot_x = center_x + (long) ((i - topmenu_disp_col) * TOPMENU_CATEGORY_PITCH);
			long x = slot_x - text_width(topmenu_category.get(i).name(), TOPMENU_CATEGORY_TEXT_SIZE) / 2;
			text.set_geometry(x, bar_y, TOPMENU_CATEGORY_TEXT_SIZE);
			if (i == topmenu_select_col) {
				text.set_color(0x00, 0x00, 0x00, 0xFF);
			} else {
				text.set_color(0x00, 0x00, 0x00, 0x70);
			}
			text.update();
		}

		//選択中カテゴリのアイテムだけを縦に並べる
		List<TopMenuItem> items = topmenu_category.get(topmenu_select_col).item_list();
		List<DisplayText> item_texts = topmenu_item_text_list.get(topmenu_select_col);
		for (int j = 0; j < item_texts.size(); j++) {
			DisplayText text = item_texts.get(j);
			long y = item_anchor_y + (long) ((j - topmenu_disp_row) * TOPMENU_ITEM_PITCH);
			long x = center_x - text_width(items.get(j).name(), TOPMENU_ITEM_TEXT_SIZE) / 2;
			text.set_geometry(x, y, TOPMENU_ITEM_TEXT_SIZE);
			int alpha;
			if (j == topmenu_select_row) {
				alpha = 0xFF;
			} else {
				alpha = 0xA0;
			}
			//カテゴリ行に近づいたらフェードアウト（かぶり防止）
			long fade_top = bar_y + TOPMENU_CATEGORY_TEXT_SIZE;
			long fade_bottom = fade_top + 32;
			if (y < fade_bottom) {
				if (y <= fade_top) {
					alpha = 0;
				} else {
					alpha = alpha * (int) (y - fade_top) / (int) (fade_bottom - fade_top);
				}
			}
			text.set_color(0x00, 0x00, 0x00, alpha);
			text.update();
		}

		//カーソルはアンカー位置に固定（リストの方が動く）
		if (items.size() > 0) {
			long cursor_width = text_width(items.get(topmenu_select_row).name(), TOPMENU_ITEM_TEXT_SIZE) + 32;
			topmenu_cursor.set_geometry(center_x - cursor_width / 2, item_anchor_y - 8, cursor_width, TOPMENU_ITEM_TEXT_SIZE + 16);
			topmenu_cursor.set_color(0x30, 0x90, 0xFF, 0x80);
		} else {
			topmenu_cursor.set_color(0x30, 0x90, 0xFF, 0);
		}
		topmenu_cursor.update();

		topmenu_dirty = animating;
	}

	private static void menu_draw() throws IOException {
		if (!menu_animing) return;

		long now = System.currentTimeMillis();
		long duration = menu_phase == 1 ? MENU_PHASE1_MS : MENU_PHASE2_MS;
		double t = (now - menu_anim_start) / (double) duration;
		boolean phase_finished = false;
		if (t >= 1) {
			t = 1;
			phase_finished = true;
		}
		double eased = 1 - Math.pow(1 - t, 3);	//ease-out cubic

		long display_height = Main.display.get_height();
		long full_inner_height = display_height - MENU_INNER_MARGIN * 2;
		long base_x;
		long inner_height;
		if (menu_anim_open) {
			if (menu_phase == 1) {
				base_x = -menu_width + (long) (menu_width * eased);
				inner_height = 0;
			} else {
				base_x = 0;
				inner_height = (long) (full_inner_height * eased);
			}
		} else {
			if (menu_phase == 2) {
				base_x = 0;
				inner_height = (long) (full_inner_height * (1 - eased));
			} else {
				base_x = -(long) (menu_width * eased);
				inner_height = 0;
			}
		}
		long inner_y = display_height / 2 - inner_height / 2;	//真ん中から上下へ

		if (base_x != menu_last_x) {
			menu_last_x = base_x;
			menu_base.set_geometry(base_x, 0, menu_width, display_height);
			menu_base.update();
		}
		if (inner_y != menu_inner_last_y || inner_height != menu_inner_last_height) {
			menu_inner_last_y = inner_y;
			menu_inner_last_height = inner_height;
			menu_inner.set_geometry(base_x, inner_y, menu_width, inner_height);
			menu_inner.update();
		}

		if (phase_finished) {
			if (menu_anim_open) {
				if (menu_phase == 1) {
					menu_phase = 2;
					menu_anim_start = now;
				} else {
					menu_animing = false;
				}
			} else {
				if (menu_phase == 2) {
					menu_phase = 1;
					menu_anim_start = now;
				} else {
					menu_animing = false;
				}
			}
		}
	}

	private static void open_menu() {
		if (menu_open) return;
		menu_open = true;
		menu_animing = true;
		menu_anim_open = true;
		menu_phase = 1;
		menu_anim_start = System.currentTimeMillis();
	}

	private static void close_menu() {
		if (menu_open == false) return;
		menu_open = false;
		menu_animing = true;
		menu_anim_open = false;
		menu_phase = 2;	//閉じるときは逆順に再生
		menu_anim_start = System.currentTimeMillis();
	}

	private static void change_col(int col) throws IOException {
		//今のカテゴリのアイテムを隠す
		for (DisplayText text : topmenu_item_text_list.get(topmenu_select_col)) {
			text.set_color(0xFF, 0xFF, 0xFF, 0);
			text.update();
		}
		topmenu_from_col = topmenu_disp_col;
		topmenu_select_col = col;
		topmenu_col_anim_start = System.currentTimeMillis();
		topmenu_select_row = 0;
		topmenu_disp_row = 0;
		topmenu_from_row = 0;
		topmenu_row_anim_start = 0;
		topmenu_dirty = true;
	}

	private static void change_row(int row) {
		topmenu_from_row = topmenu_disp_row;
		topmenu_select_row = row;
		topmenu_row_anim_start = System.currentTimeMillis();
		topmenu_dirty = true;
	}

	private static void key_receive() throws IOException {
		Byte keycode = key_queue.poll();
		if (keycode == null) return;

		switch (keycode) {
			case HdmiCec.KEY_POWER: {
				System.out.println("電源ON");
				return;
			}

			case HdmiCec.KEY_LEFT: {
				if ((topmenu_now && menu_open == false) && topmenu_select_col > 0) {
					change_col(topmenu_select_col - 1);
				}
				return;
			}

			case HdmiCec.KEY_RIGHT: {
				if ((topmenu_now && menu_open == false) && topmenu_select_col + 1 < topmenu_category.size()) {
					change_col(topmenu_select_col + 1);
				}
				return;
			}

			case HdmiCec.KEY_UP: {
				if ((topmenu_now && menu_open == false) && topmenu_select_row > 0) {
					change_row(topmenu_select_row - 1);
				}
				return;
			}

			case HdmiCec.KEY_DOWN: {
				if ((topmenu_now && menu_open == false) && topmenu_select_row + 1 < topmenu_category.get(topmenu_select_col).item_list().size()) {
					change_row(topmenu_select_row + 1);
				}
				return;
			}

			case HdmiCec.KEY_SELECT: {
				if ((topmenu_now && menu_open == false)) {
					List<TopMenuItem> items = topmenu_category.get(topmenu_select_col).item_list();
					if (topmenu_select_row < items.size()) {
						Optional<Runnable> action = items.get(topmenu_select_row).action();
						if (action.isPresent()) {
							action.get().run();
						}
					}
				}
				return;
			}

			case HdmiCec.KEY_BLUE: {
				if (menu_open) {
					close_menu();
				}
				return;
			}

			case HdmiCec.KEY_RED: {
				open_menu();
				return;
			}
		}
	}
}
