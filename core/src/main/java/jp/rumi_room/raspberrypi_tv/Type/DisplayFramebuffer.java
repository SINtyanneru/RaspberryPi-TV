package jp.rumi_room.raspberrypi_tv.Type;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

//フレームバッファーオブジェクト（種別0x06）。
//displayが /tmp/rpitv-buffer-{ID}.sock というUNIXソケットを用意するので、
//そこへBGRA形式（1画素4バイト、B,G,R,Aの並び）の映像を流し込むと、
//表示位置・サイズへリサイズして表示される。
//DRMバックエンドではハードウェアプレーンが使えれば、リサイズは
//表示コントローラ側で行われる（使えなければソフトウェア描画）。
//引数3: 表示位置とサイズ（X・Y・横幅・縦幅、各u64、ビッグエンディアン）
//引数4: 映像のサイズ（横幅・縦幅、各u64、ビッグエンディアン）
//引数5: フラグ（1バイト）。bit0=アルファ有効（画素ごとのアルファで
//下と合成する。ハードウェアプレーンは使わずソフトウェア描画になる）
public class DisplayFramebuffer extends DisplayObject {
	private long x;
	private long y;
	private long width;
	private long height;
	private long source_width;
	private long source_height;
	private boolean alpha;

	public DisplayFramebuffer(long x, long y, long width, long height, long source_width, long source_height) {
		super(Type.FRAMEBUFFER);
		this.x = x;
		this.y = y;
		this.width = width;
		this.height = height;
		this.source_width = source_width;
		this.source_height = source_height;
	}

	//表示位置とサイズをまとめて変える（書き換えコマンド用）。
	public void set_geometry(long x, long y, long width, long height) {
		this.x = x;
		this.y = y;
		this.width = width;
		this.height = height;
	}

	//映像のサイズを変える（書き換えコマンド用）。
	public void set_source_size(long source_width, long source_height) {
		this.source_width = source_width;
		this.source_height = source_height;
	}

	//アルファ有効/無効を変える（書き換えコマンド用）。
	//有効にすると画素ごとのアルファで下と合成される。
	public void set_alpha(boolean alpha) {
		this.alpha = alpha;
	}

	public boolean get_alpha() {
		return this.alpha;
	}

	//映像を流し込むUNIXソケットのパス。未作成ならnull。
	public String get_socket_path() {
		if (this.get_id() == 0) {
			return null;
		}
		return "/tmp/rpitv-buffer-" + this.get_id() + ".sock";
	}

	@Override
	public byte[][] build_args() {
		ByteBuffer geometry = ByteBuffer.allocate(32);
		geometry.order(ByteOrder.BIG_ENDIAN);
		geometry.putLong(this.x);
		geometry.putLong(this.y);
		geometry.putLong(this.width);
		geometry.putLong(this.height);
		ByteBuffer source = ByteBuffer.allocate(16);
		source.order(ByteOrder.BIG_ENDIAN);
		source.putLong(this.source_width);
		source.putLong(this.source_height);
		byte[] flag_list = new byte[]{(byte)(this.alpha ? 0x01 : 0x00)};
		return new byte[][]{geometry.array(), source.array(), flag_list};
	}
}
