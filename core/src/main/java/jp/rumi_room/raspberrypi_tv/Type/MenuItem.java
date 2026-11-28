package jp.rumi_room.raspberrypi_tv.Type;

//サイドメニューのアイテム。選んで決定するとactionが実行される
public record MenuItem(
	String name,
	Runnable action
) {}
