package jp.rumi_room.raspberrypi_tv.Type.TopMenu;

import java.util.Optional;

public record TopMenuItem(
	String id,
	String name,
	Optional<Runnable> action
) {}
