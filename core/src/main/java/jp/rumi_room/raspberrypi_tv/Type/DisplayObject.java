package jp.rumi_room.raspberrypi_tv.Type;

import java.io.IOException;

import jp.rumi_room.raspberrypi_tv.Display;

//display上のオブジェクトの基底クラス。
//IDはDisplayクラスが作成時に割り当てる（0は未割り当て）。
//変更と削除はこのインスタンスのupdate()とdelete()から行う。
public abstract class DisplayObject {
	//オブジェクトの種類。数字はプロトコルの種別コード。
	public enum Type {
		RECT((byte) 0x01),
		TRIANGLE((byte) 0x02),
		LINE((byte) 0x03),
		CIRCLE((byte) 0x04),
		TEXT((byte) 0x05),
		FRAMEBUFFER((byte) 0x06);

		private final byte code;

		Type(byte code) {
			this.code = code;
		}

		public byte get_code() {
			return this.code;
		}
	}

	private final Type type;
	private long id;
	//作成したDisplay。未作成ならnull。
	private Display display;

	protected DisplayObject(Type type) {
		this.type = type;
		this.id = 0;
		this.display = null;
	}

	public Type get_type() {
		return this.type;
	}

	public long get_id() {
		return this.id;
	}

	public void set_id(long id) {
		this.id = id;
	}

	//Display.create_objectが呼ぶ。所属するDisplayを記録する。
	public void set_display(Display display) {
		this.display = display;
	}

	public Display get_display() {
		return this.display;
	}

	//変更（位置・サイズ・色）をdisplayへ送る。
	public void update() throws IOException {
		if (this.display == null) {
			throw new IOException("object is not created yet");
		}
		this.display.__update_object(this);
	}

	//このオブジェクトを削除する。
	public void delete() throws IOException {
		if (this.display == null) {
			throw new IOException("object is not created yet");
		}
		this.display.__delete_object(this);
	}

	//作成コマンドの引数のうち、IDと種類に続く分を作る。
	public abstract byte[][] build_args();
}
