package jp.rumi_room.raspberrypi_tv;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import jp.rumi_room.raspberrypi_tv.Type.DisplayRect;

public class Core {
	public static BlockingQueue<Byte> key_queue = new LinkedBlockingQueue<>();

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

	public static void start() throws IOException {
		menu_width = value_clamp(500, Main.display.get_width() / 4);
		menu_base = Main.display.create_object(new DisplayRect(menu_width * -1, 0, menu_width, Main.display.get_height(), 0xFF, 0xFF, 0xFF, 0xFF));
		menu_inner = Main.display.create_object(new DisplayRect(0, Main.display.get_height() / 2, menu_width, 0, 0x30, 0x90, 0xFF, 0xB0));

		while (true) {
			key_receive();

			draw();
		}
	}

	static int value_clamp(int min, int value) {
		if (value < min) {
			return min;
		}
		return value;
	}

	private static void draw() throws IOException {
		menu_draw();
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

	private static void key_receive() {
		Byte keycode = key_queue.poll();
		if (keycode == null) return;

		switch (keycode) {
			case HdmiCec.KEY_POWER: {
				System.out.println("電源ON");
				return;
			}

			case HdmiCec.KEY_SELECT: {
				System.out.println("リモコン: 決定");
				return;
			}

			case HdmiCec.KEY_UP: {
				System.out.println("リモコン: ↑");
				return;
			}

			case HdmiCec.KEY_DOWN: {
				System.out.println("リモコン: ↓");
				return;
			}

			case HdmiCec.KEY_LEFT: {
				System.out.println("リモコン: ←");
				return;
			}

			case HdmiCec.KEY_RIGHT: {
				System.out.println("リモコン: →");
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
