package com.logadviser.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.text.NumberFormat;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JWindow;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import lombok.Value;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.util.AsyncBufferedImage;

/**
 * A small floating window listing an activity's collection-log slots — missing first, then
 * collected (greyed with a check). Capped in size with a vertical scrollbar so long activities
 * scroll instead of growing off-screen. Non-focusable so it never steals focus or intercepts
 * clicks meant for the panel. All methods must be called on the EDT.
 */
final class ActivityLogPopup
{
	// The popup is a fixed-size box. The body never resizes to fit the content, so a small
	// activity shows trailing empty space rather than a shrunken window, and the vertical
	// scrollbar appears only when the rows genuinely overflow this height.
	static final int FIXED_W = 250;
	static final int FIXED_H = 300;
	private static final int ICON_SIZE = 24;
	private static final Color COLLECTED_COLOR = new Color(120, 120, 120);
	private static final Color RATE_COLOR = new Color(160, 160, 160);

	/** Pre-resolved row data, snapshotted on the EDT so the hover path never reads the engine.
	 *  {@code slotDifficulty} / {@code dropRateAttempts} are the engine's "easiest" sort keys
	 *  (lower difficulty = easier, tie-broken by fewer attempts) and the drop rate to display. */
	@Value
	static final class SlotEntry
	{
		int itemId;
		String name;
		boolean collected;
		int slotDifficulty;
		double dropRateAttempts;
	}

	private final ItemManager itemManager;
	private final JWindow window;
	private final JLabel header;
	private final JPanel rows;

	ActivityLogPopup(ItemManager itemManager)
	{
		this.itemManager = itemManager;

		window = new JWindow();
		window.setFocusableWindowState(false);
		window.setFocusable(false);

		JPanel root = new JPanel(new BorderLayout());
		root.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		root.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));

		header = new JLabel();
		header.setForeground(Color.WHITE);
		header.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
		root.add(header, BorderLayout.NORTH);

		rows = new JPanel();
		rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
		rows.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JScrollPane scroll = new JScrollPane(rows);
		scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
		scroll.setBorder(null);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		// Match the empty area shown below the rows when an activity has only a few items.
		scroll.getViewport().setBackground(ColorScheme.DARKER_GRAY_COLOR);
		root.add(scroll, BorderLayout.CENTER);

		window.setContentPane(root);
		// Fixed window size: BorderLayout gives the header its natural height and the scroll
		// pane the rest, so the viewport is constant and only scrolls when the rows overflow.
		window.setSize(FIXED_W, FIXED_H);
	}

	/** Rebuilds the body for one activity. {@code slots} must already be ordered missing-first. */
	void setContent(String activityName, List<SlotEntry> slots)
	{
		int total = slots.size();
		int got = 0;
		for (SlotEntry s : slots)
		{
			if (s.isCollected())
			{
				got++;
			}
		}
		header.setText("<html><b>" + escape(activityName) + "</b><br>"
			+ got + "/" + total + " collected</html>");

		rows.removeAll();
		if (slots.isEmpty())
		{
			JLabel empty = new JLabel("No tracked log slots");
			empty.setForeground(Color.GRAY);
			empty.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
			empty.setAlignmentX(Component.LEFT_ALIGNMENT);
			rows.add(empty);
		}
		else
		{
			for (SlotEntry s : slots)
			{
				rows.add(buildRow(s));
			}
		}
		rows.revalidate();
		rows.repaint();
		// Window size stays fixed (set once in the constructor); the scrollbar appears only
		// when these rows are taller than the fixed viewport.
	}

	private JPanel buildRow(SlotEntry s)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);

		JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(ICON_SIZE, ICON_SIZE));
		icon.setHorizontalAlignment(SwingConstants.CENTER);
		setIconAsync(icon, s.getItemId());
		row.add(icon, BorderLayout.WEST);

		JLabel name = new JLabel(s.isCollected() ? s.getName() + " ✔" : s.getName());
		name.setForeground(s.isCollected() ? COLLECTED_COLOR : Color.WHITE);
		row.add(name, BorderLayout.CENTER);

		String rate = formatRate(s.getDropRateAttempts());
		if (rate != null)
		{
			JLabel rateLabel = new JLabel(rate);
			rateLabel.setForeground(RATE_COLOR);
			rateLabel.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 0));
			row.add(rateLabel, BorderLayout.EAST);
		}

		// Keep rows at their natural height under the Y_AXIS BoxLayout instead of stretching.
		row.setMaximumSize(new Dimension(FIXED_W, row.getPreferredSize().height));
		return row;
	}

	/** Per-item drop rate as "1/N". Returns null for non-positive rates (no meaningful drop). */
	private static String formatRate(double k)
	{
		if (k <= 0.0)
		{
			return null;
		}
		if (k < 1.0)
		{
			// Sub-1 means more than one per completion; show one decimal rather than rounding to 0.
			return "1/" + (Math.round(k * 10.0) / 10.0);
		}
		return "1/" + NumberFormat.getIntegerInstance().format(Math.round(k));
	}

	private void setIconAsync(JLabel label, int itemId)
	{
		if (itemId <= 0 || itemManager == null)
		{
			return;
		}
		AsyncBufferedImage image = itemManager.getImage(itemId);
		if (image == null)
		{
			return;
		}
		label.setIcon(new ImageIcon(image));
		image.onLoaded(() -> SwingUtilities.invokeLater(() ->
			label.setIcon(new ImageIcon((BufferedImage) image))));
	}

	void showAt(int screenX, int screenY)
	{
		window.setLocation(screenX, screenY);
		window.setVisible(true);
	}

	boolean isShowing()
	{
		return window.isVisible();
	}

	Rectangle getBoundsOnScreen()
	{
		return window.isVisible() ? window.getBounds() : null;
	}

	void hide()
	{
		window.setVisible(false);
	}

	void dispose()
	{
		window.dispose();
	}

	private static String escape(String s)
	{
		return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;");
	}
}
