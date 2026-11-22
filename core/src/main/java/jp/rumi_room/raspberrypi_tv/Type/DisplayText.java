package jp.rumi_room.raspberrypi_tv.Type;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

//テキストオブジェクト（種別0x05）。フォントはdisplay側のUDEV Gothic。
//引数3: X座標・Y座標（左上）・サイズ（ピクセル、各u64、ビッグエンディアン）
//引数4: 色（R・G・B・A、各u8）
//引数5: 文字列（UTF-8、最大255バイト）
public class DisplayText extends DisplayObject {
	private long x;
	private long y;
	private long size;
	private String text;
	private int r;
	private int g;
	private int b;
	private int a;

	public DisplayText(long x, long y, long size, String text, int r, int g, int b, int a) {
		super(Type.TEXT);
		this.x = x;
		this.y = y;
		this.size = size;
		this.text = text;
		this.r = r;
		this.g = g;
		this.b = b;
		this.a = a;
	}

	//位置とサイズをまとめて変える（書き換えコマンド用）。
	public void set_geometry(long x, long y, long size) {
		this.x = x;
		this.y = y;
		this.size = size;
	}

	//文字列を変える（書き換えコマンド用）。
	public void set_text(String text) {
		this.text = text;
	}

	//色をまとめて変える（書き換えコマンド用）。
	public void set_color(int r, int g, int b, int a) {
		this.r = r;
		this.g = g;
		this.b = b;
		this.a = a;
	}

	@Override
	public byte[][] build_args() {
		byte[] text_bytes = this.text.getBytes(StandardCharsets.UTF_8);
		if (text_bytes.length > 255) {
			throw new IllegalArgumentException("text is too long: " + text_bytes.length + " bytes (max 255)");
		}
		ByteBuffer geometry = ByteBuffer.allocate(24);
		geometry.order(ByteOrder.BIG_ENDIAN);
		geometry.putLong(this.x);
		geometry.putLong(this.y);
		geometry.putLong(this.size);
		byte[] color = new byte[]{(byte) this.r, (byte) this.g, (byte) this.b, (byte) this.a};
		return new byte[][]{geometry.array(), color, text_bytes};
	}
}
