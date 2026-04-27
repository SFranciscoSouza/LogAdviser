package com.logadviser.ui;

import com.logadviser.data.ActivityItem;
import com.logadviser.data.ActivityNpcInfo;
import com.logadviser.data.Category;
import com.logadviser.data.LogSlot;
import com.logadviser.data.StaticData;
import com.logadviser.engine.AccountMode;
import com.logadviser.engine.AdviserEngine;
import com.logadviser.engine.RankedActivity;
import com.logadviser.sync.CollectionLogTracker;
import com.logadviser.sync.HiscoreRankFetcher;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

@Slf4j
public class LogAdviserPanel extends PluginPanel
{
	private static final int ICON_SIZE = 32;
	private static final NumberFormat RANK_FMT = NumberFormat.getIntegerInstance();

	private final AdviserEngine engine;
	private final ItemManager itemManager;
	private final CollectionLogTracker tracker;
	private final StaticData staticData;
	private final Consumer<AccountMode> onAccountModeChanged;
	private final Runnable refreshHiscores;
	private final IntSupplier upcomingListSize;

	// Header
	private final JLabel playerLabel = new JLabel("(not logged in)");
	private final JComboBox<AccountMode> accountModeBox = new JComboBox<>(AccountMode.values());
	private final JLabel modeBadge = new JLabel("");
	// Stats row
	private final JLabel slotsLabel = new JLabel("0 / 0 slots");
	private final JLabel rankLabel = new JLabel("Rank: —");
	// Filter row — single dropdown: {All, Combat, Minigame, Misc}.
	private final JComboBox<FilterChoice> filterBox = new JComboBox<>(FilterChoice.values());
	// Current target card
	private final JLabel currentIcon = new JLabel();
	private final JLabel currentItem = new JLabel("—");
	private final JLabel currentActivity = new JLabel(" ");
	private final JLabel currentHint = new JLabel(" ");
	private final JLabel currentTime = new JLabel(" ");
	private final JButton skipButton = new JButton("Skip");
	// List
	private final DefaultListModel<RankedActivity> listModel = new DefaultListModel<>();
	private final JList<RankedActivity> list = new JList<>(listModel);
	// Footer
	private final JButton resetSkipsButton = new JButton("Reset skips");

	private RankedActivity currentTopRanked;
	private boolean accountModeBoxLoading = false;

	public LogAdviserPanel(
		AdviserEngine engine,
		ItemManager itemManager,
		CollectionLogTracker tracker,
		StaticData staticData,
		Consumer<AccountMode> onAccountModeChanged,
		Runnable refreshHiscores,
		IntSupplier upcomingListSize)
	{
		// Skip PluginPanel's built-in outer JScrollPane — we manage our own scrolling
		// inside the upcoming-list region so the Reset button can stay pinned to the
		// bottom of the visible viewport.
		super(false);
		this.engine = engine;
		this.itemManager = itemManager;
		this.tracker = tracker;
		this.staticData = staticData;
		this.onAccountModeChanged = onAccountModeChanged;
		this.refreshHiscores = refreshHiscores;
		this.upcomingListSize = upcomingListSize;

		setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Top: fixed-height stack of header / stats / filter / current target card.
		JPanel top = new JPanel();
		top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
		top.setBackground(ColorScheme.DARK_GRAY_COLOR);
		top.add(buildHeader());
		top.add(verticalGap(8));
		top.add(buildStatsRow());
		top.add(verticalGap(6));
		top.add(buildFilterRow());
		top.add(verticalGap(8));
		top.add(buildCurrentCard());
		top.add(verticalGap(8));
		add(top, BorderLayout.NORTH);

		// Center: the upcoming list. BorderLayout.CENTER lets it expand to fill all
		// available vertical space — grows when the user resizes the RuneLite window.
		add(buildList(), BorderLayout.CENTER);

		// Bottom: footer pinned to the bottom of the panel.
		JPanel bottom = new JPanel();
		bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
		bottom.setBackground(ColorScheme.DARK_GRAY_COLOR);
		bottom.add(verticalGap(6));
		bottom.add(buildFooter());
		add(bottom, BorderLayout.SOUTH);

		engine.addListener(this::onRankingChanged);
		onRankingChanged(engine.getRanking());
	}

	private JPanel buildHeader()
	{
		JPanel p = new JPanel(new BorderLayout(4, 2));
		p.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JLabel title = new JLabel("Log Adviser");
		title.setFont(title.getFont().deriveFont(Font.BOLD, 14f));
		p.add(title, BorderLayout.NORTH);

		JPanel sub = new JPanel(new BorderLayout(4, 2));
		sub.setBackground(ColorScheme.DARK_GRAY_COLOR);
		playerLabel.setForeground(Color.LIGHT_GRAY);
		sub.add(playerLabel, BorderLayout.WEST);
		modeBadge.setForeground(new Color(170, 170, 170));
		modeBadge.setHorizontalAlignment(SwingConstants.RIGHT);
		sub.add(modeBadge, BorderLayout.EAST);
		p.add(sub, BorderLayout.CENTER);

		JPanel modePanel = new JPanel(new BorderLayout(4, 2));
		modePanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JLabel modeLabel = new JLabel("Account mode:");
		modeLabel.setForeground(Color.LIGHT_GRAY);
		modePanel.add(modeLabel, BorderLayout.WEST);
		accountModeBox.addActionListener(e ->
		{
			if (accountModeBoxLoading)
			{
				return;
			}
			AccountMode m = (AccountMode) accountModeBox.getSelectedItem();
			if (m != null)
			{
				onAccountModeChanged.accept(m);
			}
		});
		modePanel.add(accountModeBox, BorderLayout.CENTER);
		p.add(modePanel, BorderLayout.SOUTH);
		return p;
	}

	private JPanel buildStatsRow()
	{
		JPanel p = new JPanel(new BorderLayout(8, 2));
		p.setBackground(ColorScheme.DARK_GRAY_COLOR);
		slotsLabel.setForeground(Color.LIGHT_GRAY);
		rankLabel.setForeground(Color.LIGHT_GRAY);
		rankLabel.setHorizontalAlignment(SwingConstants.RIGHT);
		p.add(slotsLabel, BorderLayout.WEST);
		p.add(rankLabel, BorderLayout.EAST);
		return p;
	}

	private JPanel buildFilterRow()
	{
		JPanel p = new JPanel(new BorderLayout(4, 0));
		p.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JLabel label = new JLabel("Show:");
		label.setForeground(Color.LIGHT_GRAY);
		filterBox.setSelectedItem(FilterChoice.ALL);
		filterBox.addActionListener(e -> applyFilter());
		p.add(label, BorderLayout.WEST);
		p.add(filterBox, BorderLayout.CENTER);
		return p;
	}

	private JPanel buildCurrentCard()
	{
		JPanel card = new JPanel(new BorderLayout(8, 4));
		card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		card.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
			BorderFactory.createEmptyBorder(8, 8, 8, 8)));

		currentIcon.setPreferredSize(new Dimension(ICON_SIZE, ICON_SIZE));
		card.add(currentIcon, BorderLayout.WEST);

		JPanel text = new JPanel();
		text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
		text.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		currentItem.setFont(currentItem.getFont().deriveFont(Font.BOLD, 13f));
		currentItem.setForeground(Color.WHITE);
		currentActivity.setForeground(Color.LIGHT_GRAY);
		currentHint.setForeground(new Color(180, 180, 180));
		currentTime.setForeground(new Color(120, 200, 255));
		text.add(currentItem);
		text.add(currentActivity);
		text.add(currentHint);
		text.add(currentTime);
		card.add(text, BorderLayout.CENTER);

		skipButton.setToolTipText("Skip this activity (you can reset skips below)");
		skipButton.addActionListener(e ->
		{
			if (currentTopRanked != null)
			{
				engine.skip(currentTopRanked.getActivity().getIndex());
				tracker.persistSkipped();
			}
		});
		card.add(skipButton, BorderLayout.SOUTH);
		return card;
	}

	private JScrollPane buildList()
	{
		list.setBackground(ColorScheme.DARK_GRAY_COLOR);
		list.setForeground(Color.LIGHT_GRAY);
		list.setCellRenderer(new RankRenderer());
		list.setVisibleRowCount(15);
		// Pin cells to the panel's inner width so long activity names wrap inside the
		// HTML renderer instead of forcing a horizontal scrollbar.
		list.setFixedCellWidth(PluginPanel.PANEL_WIDTH - 32);
		JScrollPane scroll = new JScrollPane(list);
		scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		return scroll;
	}

	private JPanel buildFooter()
	{
		JPanel p = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
		p.setBackground(ColorScheme.DARK_GRAY_COLOR);
		resetSkipsButton.addActionListener(e ->
		{
			engine.unskipAll();
			tracker.persistSkipped();
		});
		p.add(resetSkipsButton);
		return p;
	}

	public void setPlayerLabel(String name, boolean detectedIronman)
	{
		SwingUtilities.invokeLater(() ->
		{
			playerLabel.setText(name == null || name.isEmpty() ? "(not logged in)" : name);
			AccountMode mode = (AccountMode) accountModeBox.getSelectedItem();
			modeBadge.setText(modeBadgeText(mode, detectedIronman));
		});
	}

	public void setAccountMode(AccountMode mode)
	{
		SwingUtilities.invokeLater(() ->
		{
			accountModeBoxLoading = true;
			try
			{
				accountModeBox.setSelectedItem(mode);
			}
			finally
			{
				accountModeBoxLoading = false;
			}
		});
	}

	public void setHiscoreRank(Long rank, Long score)
	{
		SwingUtilities.invokeLater(() ->
		{
			if (rank == null || rank < 0)
			{
				rankLabel.setText("Rank: —");
			}
			else
			{
				rankLabel.setText("Rank: #" + RANK_FMT.format(rank));
			}
			if (score != null && score >= 0)
			{
				slotsLabel.setText(score + " / " + engine.totalSlots() + " slots");
			}
		});
	}

	private String modeBadgeText(AccountMode mode, boolean detectedIronman)
	{
		String detected = detectedIronman ? "Iron" : "Main";
		switch (mode)
		{
			case MAIN:
				return "Main" + (detectedIronman ? " (overridden)" : "");
			case IRONMAN:
				return "Iron" + (detectedIronman ? "" : " (overridden)");
			case AUTO:
			default:
				return detected + " (auto)";
		}
	}

	private void applyFilter()
	{
		FilterChoice choice = (FilterChoice) filterBox.getSelectedItem();
		EnumSet<Category> set = (choice == null ? FilterChoice.ALL : choice).asCategorySet();
		engine.setCategoryFilter(set);
	}

	private enum FilterChoice
	{
		ALL("All"),
		COMBAT("Combat"),
		MINIGAME("Minigame"),
		MISCELLANEOUS("Miscellaneous");

		private final String label;

		FilterChoice(String label)
		{
			this.label = label;
		}

		EnumSet<Category> asCategorySet()
		{
			switch (this)
			{
				case COMBAT:
					return EnumSet.of(Category.COMBAT);
				case MINIGAME:
					return EnumSet.of(Category.MINIGAME);
				case MISCELLANEOUS:
					return EnumSet.of(Category.MISCELLANEOUS);
				case ALL:
				default:
					return EnumSet.allOf(Category.class);
			}
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	public void onRankingChanged(List<RankedActivity> ranking)
	{
		List<RankedActivity> snapshot = new ArrayList<>(ranking);
		SwingUtilities.invokeLater(() -> applyRanking(snapshot));
	}

	private void applyRanking(List<RankedActivity> ranking)
	{
		slotsLabel.setText(engine.collectedSlotCount() + " / " + engine.totalSlots() + " slots");

		if (ranking.isEmpty())
		{
			currentTopRanked = null;
			currentItem.setText("All filtered activities complete");
			currentActivity.setText(" ");
			currentHint.setText(" ");
			currentTime.setText(" ");
			currentIcon.setIcon(null);
			skipButton.setEnabled(false);
			listModel.clear();
			return;
		}

		RankedActivity top = ranking.get(0);
		currentTopRanked = top;
		ActivityItem display = top.getEasiestItem() != null ? top.getEasiestItem() : top.getFastestItem();
		String itemName = display != null ? safeName(display.getItemId(), display.getItemName()) : "—";
		currentItem.setText(itemName);
		currentActivity.setText(top.getActivity().getName());
		ActivityNpcInfo info = staticData.npcInfoFor(top.getActivity().getIndex());
		currentHint.setText(info.getHint().isEmpty() ? "(see activity name)" : info.getHint());
		currentTime.setText("≈ " + TargetInfoBox.formatHours(top.getTimeToNextSlotHours()) + " to slot");
		setIconAsync(currentIcon, display != null ? display.getItemId() : 0);
		skipButton.setEnabled(true);

		listModel.clear();
		int desired = upcomingListSize.getAsInt();
		if (desired <= 0)
		{
			desired = 30;
		}
		int max = Math.min(ranking.size(), desired);
		for (int i = 0; i < max; i++)
		{
			listModel.addElement(ranking.get(i));
		}
	}

	private String safeName(int itemId, String fallback)
	{
		LogSlot slot = staticData.slotsByItemId().get(itemId);
		return slot != null ? slot.getSlotName() : (fallback != null ? fallback : "Item " + itemId);
	}

	private void setIconAsync(JLabel label, int itemId)
	{
		if (itemId <= 0 || itemManager == null)
		{
			label.setIcon(null);
			return;
		}
		AsyncBufferedImage image = itemManager.getImage(itemId);
		if (image == null)
		{
			label.setIcon(null);
			return;
		}
		label.setIcon(new ImageIcon(image));
		image.onLoaded(() -> SwingUtilities.invokeLater(() -> label.setIcon(new ImageIcon((BufferedImage) image))));
	}

	private static JPanel verticalGap(int h)
	{
		JPanel p = new JPanel();
		p.setBackground(ColorScheme.DARK_GRAY_COLOR);
		p.setPreferredSize(new Dimension(1, h));
		return p;
	}

	private final class RankRenderer extends DefaultListCellRenderer
	{
		// Reserve room for the icon + padding; HTML body wraps at this width.
		private static final int TEXT_WIDTH = PluginPanel.PANEL_WIDTH - 90;

		@Override
		public Component getListCellRendererComponent(
			JList<?> list, Object value, int index, boolean selected, boolean focus)
		{
			JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focus);
			if (value instanceof RankedActivity)
			{
				RankedActivity r = (RankedActivity) value;
				ActivityItem display = r.getEasiestItem() != null ? r.getEasiestItem() : r.getFastestItem();
				String name = r.getActivity().getName();
				String time = TargetInfoBox.formatHours(r.getTimeToNextSlotHours());
				// Explicit body width so Swing's HTML renderer word-wraps long names
				// instead of pushing the cell past the viewport (and adding a hscroll).
				label.setText("<html><body style='width:" + TEXT_WIDTH + "px'>"
					+ "<b>" + escape(name) + "</b><br>"
					+ "<span style='color:#9bc7ff'>≈ " + time + "</span>"
					+ " · " + (r.getSlotsTotal() - r.getSlotsLeft()) + "/" + r.getSlotsTotal()
					+ "</body></html>");
				label.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
				label.setVerticalAlignment(SwingConstants.TOP);
				label.setVerticalTextPosition(SwingConstants.TOP);
				if (display != null && itemManager != null)
				{
					AsyncBufferedImage img = itemManager.getImage(display.getItemId());
					if (img != null)
					{
						label.setIcon(new ImageIcon(img));
					}
				}
				else
				{
					label.setIcon(null);
				}
			}
			if (!selected)
			{
				label.setBackground(ColorScheme.DARK_GRAY_COLOR);
				label.setForeground(Color.LIGHT_GRAY);
			}
			return label;
		}

		private String escape(String s)
		{
			return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;");
		}
	}
}
