package jp.rumi_room.raspberrypi_tv;

import jp.rumi_room.raspberrypi_tv.Type.DisplayCircle;
import jp.rumi_room.raspberrypi_tv.Type.DisplayLine;
import jp.rumi_room.raspberrypi_tv.Type.DisplayRect;
import jp.rumi_room.raspberrypi_tv.Type.DisplayText;
import jp.rumi_room.raspberrypi_tv.Type.DisplayTriangle;

//デモ用のMain。displayを初期化して図形と文字を描き、そのまま待機する。
//通常起動は実機用にDRM/KMSへ直接出す。
//--devを付けるとデバッグ起動になり、windowバックエンドで起動する
//（debug.shがWeston経由でこの形で呼ぶ）。
public class Main {
	public static void main(String[] args) throws Exception {
		boolean dev = false;
		for (String arg : args) {
			if (arg.equals("--dev")) {
				dev = true;
			}
		}
		Display display;
		if (dev) {
			display = new Display("./rtv-display", "--backend", "window");
		} else {
			display = new Display("./rtv-display", "--backend", "drm");
		}
		display.init();
		display.start();

		//背景
		display.create_object(new DisplayRect(0, 0, 1920, 1080, 32, 36, 42, 255));
		//図形いろいろ
		display.create_object(new DisplayRect(120, 120, 480, 320, 255, 59, 48, 255));
		display.create_object(new DisplayTriangle(760, 440, 1000, 120, 1240, 440, 255, 204, 0, 255));
		display.create_object(new DisplayCircle(1560, 280, 160, 50, 200, 90, 255));
		display.create_object(new DisplayLine(120, 560, 1800, 560, 255, 255, 255, 255));
		//文字（UDEV Gothicで描かれる）
		display.create_object(new DisplayText(120, 640, 96, "RaspberryPi TV", 255, 255, 255, 255));
		display.create_object(new DisplayText(120, 780, 64, "こんにちは、テレビ", 168, 237, 234, 255));

		System.out.println("demo running (Ctrl+C to quit)");

		//画面を出し続けるため、ここで待機する
		Object lock = new Object();
		synchronized (lock) {
			lock.wait();
		}
	}
}
