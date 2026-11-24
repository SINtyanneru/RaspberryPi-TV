package jp.rumi_room.raspberrypi_tv.Type.TopMenu;

import java.util.List;

public record TopMenuCategory(
	String id,
	String name,
	List<TopMenuItem> item_list
) {}
