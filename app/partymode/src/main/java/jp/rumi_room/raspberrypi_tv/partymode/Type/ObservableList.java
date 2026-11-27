package jp.rumi_room.raspberrypi_tv.partymode.Type;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

//変更をリスナーへ通知する、スレッドセーフなList
public class ObservableList<E> {
	public interface ChangeListener<E> {
		void on_add(int index, E element);
		void on_remove(int index, E element);
		void on_set(int index, E old_element, E new_element);
	}

	private final List<E> list = Collections.synchronizedList(new ArrayList<E>());
	private final List<ChangeListener<E>> listener_list = new CopyOnWriteArrayList<ChangeListener<E>>();

	public void add_listener(ChangeListener<E> listener) {
		listener_list.add(listener);
	}

	public void remove_listener(ChangeListener<E> listener) {
		listener_list.remove(listener);
	}

	public void add(E element) {
		int index;
		synchronized (list) {
			index = list.size();
			list.add(element);
		}
		//通知はロックの外でやる(遅いリスナーが変更処理を止めないため)
		//リスナーの例外でadd自体が失敗してはいけないので、通知の失敗は握りつぶす
		for (ChangeListener<E> listener : listener_list) {
			try {
				listener.on_add(index, element);
			} catch (Exception ignored) {
			}
		}
	}

	public E remove(int index) {
		E removed;
		synchronized (list) {
			removed = list.remove(index);
		}
		for (ChangeListener<E> listener : listener_list) {
			try {
				listener.on_remove(index, removed);
			} catch (Exception ignored) {
			}
		}
		return removed;
	}

	public E set(int index, E element) {
		E old_element;
		synchronized (list) {
			old_element = list.set(index, element);
		}
		for (ChangeListener<E> listener : listener_list) {
			try {
				listener.on_set(index, old_element, element);
			} catch (Exception ignored) {
			}
		}
		return old_element;
	}

	public E get(int index) {
		return list.get(index);
	}

	public int size() {
		return list.size();
	}
}