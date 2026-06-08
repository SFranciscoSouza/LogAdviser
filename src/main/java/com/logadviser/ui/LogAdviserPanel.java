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
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsConfiguration;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.MouseMotionListener;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JToggleButton;
import javax.swing.JViewport;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

@Slf4j
public class LogAdviserPanel extends PluginPanel
{
	private static final int ICON_SIZE = 32;

	private final AdviserEngine engine;
	private final ItemManager itemManager;
	private final CollectionLogTracker tracker;
	private final StaticData staticData;
	private final Consumer<AccountMode> onAccountModeChanged;
	private final Consumer<Boolean> onIgnoreRequirementsChanged;
	private final IntSupplier upcomingListSize;
	// When this supplies true, the hover preview popup is suppressed (config "Disable hover preview").
	private final BooleanSupplier hoverDisabled;

	// Header
	private final JLabel playerLabel = new JLabel("(not logged in)");
	private final JComboBox<AccountMode> accountModeBox = new JComboBox<>(AccountMode.values());
	private final JLabel modeBadge = new JLabel("");
	// Filter row — single dropdown: {All, Combat, Minigame, Misc}.
	private final JComboBox<FilterChoice> filterBox = new JComboBox<>(FilterChoice.values());
	// When ticked, activities you don't meet skill/quest requirements for are ranked
	// normally instead of being demoted to the locked section.
	private final JCheckBox ignoreReqBox = new JCheckBox("Ignore requirements", false);
	// Progress line shown directly under the "Show:" filter. Turns orange with a marker
	// when the plugin's data is behind the player's real collection log.
	private final JLabel progressCountLabel = new JLabel(" ");
	// Latest known sync status, so updateCounts() can re-apply the indication after a
	// ranking refresh rebuilds the progress text.
	private boolean inSync = true;
	private int playerClogCount = 0;
	// True while a full sync is running — shown on the progress line so the feedback is
	// visible even when the in-interface button is covered by the collection-log redraw.
	private boolean syncing = false;
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
	private final JButton skipSelectedButton = new JButton("Skip");
	private final JToggleButton viewSkipListButton = new JToggleButton("Skipped");
	private final JButton resetSkipsButton = new JButton("Reset");

	// When true the list box shows the skipped activities instead of the ranking.
	private boolean showingSkipList = false;
	// Most recent normal ranking, so the skip-list toggle can rebuild the default
	// view without re-querying the engine.
	private List<RankedActivity> lastRanking = new ArrayList<>();

	private RankedActivity currentTopRanked;
	private boolean accountModeBoxLoading = false;
	private boolean ignoreReqBoxLoading = false;

	// Hover preview of an activity's log slots. The popup data is snapshotted on the EDT in
	// applyRanking so the hover callbacks never read the engine off the client thread.
	private final ActivityLogPopup logPopup;
	private final Map<Integer, List<ActivityLogPopup.SlotEntry>> slotSnapshot = new HashMap<>();
	private final Timer hideTimer;
	// The current-target card, kept so the hide timer can test its on-screen bounds.
	private JPanel currentCard;
	// The list's scroll pane, kept so the popup anchors to the visible viewport region.
	private JScrollPane listScroll;
	// Tracks what the popup is currently showing so re-entering the same row doesn't flicker.
	private int hoveredIndex = -1;
	private RankedActivity hoveredCard;

	public LogAdviserPanel(
		AdviserEngine engine,
		ItemManager itemManager,
		CollectionLogTracker tracker,
		StaticData staticData,
		Consumer<AccountMode> onAccountModeChanged,
		Consumer<Boolean> onIgnoreRequirementsChanged,
		IntSupplier upcomingListSize,
		BooleanSupplier hoverDisabled)
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
		this.onIgnoreRequirementsChanged = onIgnoreRequirementsChanged;
		this.upcomingListSize = upcomingListSize;
		this.hoverDisabled = hoverDisabled;

		setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Top: fixed-height stack of header / stats / filter / current target card.
		JPanel top = new JPanel();
		top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
		top.setBackground(ColorScheme.DARK_GRAY_COLOR);
		top.add(buildHeader());
		top.add(verticalGap(8));
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

		logPopup = new ActivityLogPopup(itemManager);
		hideTimer = new Timer(200, e -> pollHide());
		hideTimer.setRepeats(true);
		installHover();

		engine.addListener(this::onRankingChanged);
		onRankingChanged(engine.getRanking());
		updateCounts();
	}

	/** Wires hover preview onto the upcoming list and the current-target card. Motion-only:
	 *  never touches selection, so the click-based skip flow is unaffected. */
	private void installHover()
	{
		list.addMouseMotionListener(new MouseMotionAdapter()
		{
			@Override
			public void mouseMoved(MouseEvent e)
			{
				int idx = list.locationToIndex(e.getPoint());
				if (idx < 0)
				{
					return;
				}
				Rectangle cell = list.getCellBounds(idx, idx);
				if (cell == null || !cell.contains(e.getPoint()))
				{
					return;
				}
				if (idx == hoveredIndex && hoveredCard == null && logPopup.isShowing())
				{
					return;
				}
				hoveredIndex = idx;
				hoveredCard = null;
				showPopupFor(listModel.getElementAt(idx));
			}
		});

		// A container's motion listener doesn't fire over opaque children, so add the same
		// adapter to the card and each of its labels.
		MouseMotionListener cardHover = new MouseMotionAdapter()
		{
			@Override
			public void mouseMoved(MouseEvent e)
			{
				if (currentTopRanked == null)
				{
					return;
				}
				if (hoveredCard == currentTopRanked && logPopup.isShowing())
				{
					return;
				}
				hoveredIndex = -1;
				hoveredCard = currentTopRanked;
				showPopupFor(currentTopRanked);
			}
		};
		if (currentCard != null)
		{
			currentCard.addMouseMotionListener(cardHover);
		}
		for (JLabel l : new JLabel[]{currentIcon, currentItem, currentActivity, currentHint, currentTime})
		{
			l.addMouseMotionListener(cardHover);
		}
	}

	private void showPopupFor(RankedActivity r)
	{
		if (r == null || (hoverDisabled != null && hoverDisabled.getAsBoolean()))
		{
			return;
		}
		int idx = r.getActivity().getIndex();
		List<ActivityLogPopup.SlotEntry> entries =
			slotSnapshot.getOrDefault(idx, Collections.emptyList());
		logPopup.setContent(r.getActivity().getName(), entries);

		JViewport vp = listScroll == null ? null : listScroll.getViewport();
		if (vp == null || !vp.isShowing())
		{
			return;
		}
		// Anchor to a CONSTANT position derived only from the visible list region: flush to the
		// left of the list and vertically centered. Independent of the row/cursor/scroll offset,
		// so the box never drifts to the top when scrolling, and is always to the left.
		Point vpLoc = vp.getLocationOnScreen();
		// Clamp within the monitor RuneLite is actually on (its bounds can have a negative origin
		// for a left-of-primary monitor) — not the primary screen — so the popup never jumps to the
		// other monitor on a multi-display setup.
		GraphicsConfiguration gc = vp.getGraphicsConfiguration();
		Rectangle b = gc != null
			? gc.getBounds()
			: new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
		int x = vpLoc.x - ActivityLogPopup.FIXED_W - 8;
		x = Math.max(b.x, Math.min(x, b.x + b.width - ActivityLogPopup.FIXED_W));
		int y = vpLoc.y + (vp.getHeight() - ActivityLogPopup.FIXED_H) / 2;
		y = Math.max(b.y, Math.min(y, b.y + b.height - ActivityLogPopup.FIXED_H));
		logPopup.showAt(x, y);
		if (!hideTimer.isRunning())
		{
			hideTimer.start();
		}
	}

	/** Hides the popup once the pointer leaves the list, the card, and the popup itself — a
	 *  poll rather than mouseExited so moving onto the popup to scroll doesn't dismiss it. */
	private void pollHide()
	{
		if (!logPopup.isShowing())
		{
			hideTimer.stop();
			return;
		}
		if (hoverDisabled != null && hoverDisabled.getAsBoolean())
		{
			hidePopup();
			return;
		}
		// Deliberately NOT keyed on window focus: the popup must stay put even when the client
		// isn't the active window, otherwise it flickers (hidden each tick, re-shown each move)
		// while the game runs in the background. Visibility depends only on the pointer location.
		PointerInfo pi = MouseInfo.getPointerInfo();
		if (pi == null)
		{
			return;
		}
		Point p = pi.getLocation();
		JViewport vp = listScroll == null ? null : listScroll.getViewport();
		boolean overList = vp != null && vp.isShowing()
			&& new Rectangle(vp.getLocationOnScreen(), vp.getSize()).contains(p);
		boolean overCard = currentCard != null && currentCard.isShowing()
			&& new Rectangle(currentCard.getLocationOnScreen(), currentCard.getSize()).contains(p);
		Rectangle pop = logPopup.getBoundsOnScreen();
		boolean overPopup = pop != null && pop.contains(p);
		if (!overList && !overCard && !overPopup)
		{
			hidePopup();
		}
	}

	private void hidePopup()
	{
		logPopup.hide();
		hideTimer.stop();
		hoveredIndex = -1;
		hoveredCard = null;
	}

	/** Builds the missing-first, then-collected slot list for one activity. EDT only. */
	private List<ActivityLogPopup.SlotEntry> buildSlotEntries(int activityIndex)
	{
		LinkedHashSet<Integer> seen = new LinkedHashSet<>();
		List<ActivityLogPopup.SlotEntry> missing = new ArrayList<>();
		List<ActivityLogPopup.SlotEntry> done = new ArrayList<>();
		for (ActivityItem it : engine.visibleItemsForActivity(activityIndex))
		{
			if (!seen.add(it.getItemId()))
			{
				continue;
			}
			boolean collected = engine.isObtained(it.getItemId());
			ActivityLogPopup.SlotEntry entry = new ActivityLogPopup.SlotEntry(
				it.getItemId(), safeName(it.getItemId(), it.getItemName()), collected,
				it.getSlotDifficulty(), it.getDropRateAttempts());
			(collected ? done : missing).add(entry);
		}
		// Within each group, easiest first: lower slotDifficulty, tie-broken by fewer attempts —
		// mirrors AdviserEngine.recomputeActivity's "easiest" pick. Missing group precedes done.
		Comparator<ActivityLogPopup.SlotEntry> easiest = Comparator
			.comparingInt(ActivityLogPopup.SlotEntry::getSlotDifficulty)
			.thenComparingDouble(ActivityLogPopup.SlotEntry::getDropRateAttempts);
		missing.sort(easiest);
		done.sort(easiest);
		List<ActivityLogPopup.SlotEntry> out = new ArrayList<>(missing.size() + done.size());
		out.addAll(missing);
		out.addAll(done);
		return out;
	}

	/** Called when the "Disable hover preview" config toggle changes; hides any popup that's
	 *  currently showing so the change takes effect immediately. */
	public void onHoverDisabledChanged()
	{
		SwingUtilities.invokeLater(() ->
		{
			if (hoverDisabled != null && hoverDisabled.getAsBoolean() && logPopup.isShowing())
			{
				hidePopup();
			}
		});
	}

	/** Stops the hover timer and disposes the popup window. Call from the plugin's shutDown. */
	public void shutdown()
	{
		if (hideTimer != null)
		{
			hideTimer.stop();
		}
		if (logPopup != null)
		{
			logPopup.dispose();
		}
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

	private JPanel buildFilterRow()
	{
		JPanel row = new JPanel(new BorderLayout(4, 0));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JLabel label = new JLabel("Show:");
		label.setForeground(Color.LIGHT_GRAY);
		filterBox.setSelectedItem(FilterChoice.ALL);
		filterBox.addActionListener(e -> applyFilter());
		row.add(label, BorderLayout.WEST);
		row.add(filterBox, BorderLayout.CENTER);

		progressCountLabel.setForeground(Color.WHITE);
		progressCountLabel.setFont(progressCountLabel.getFont().deriveFont(Font.BOLD, 16f));
		progressCountLabel.setAlignmentX(LEFT_ALIGNMENT);

		ignoreReqBox.setForeground(Color.LIGHT_GRAY);
		ignoreReqBox.setBackground(ColorScheme.DARK_GRAY_COLOR);
		ignoreReqBox.setFocusable(false);
		ignoreReqBox.setToolTipText("Show activities you don't meet the skill/quest "
			+ "requirements for in the normal ranking instead of the locked section");
		ignoreReqBox.setAlignmentX(LEFT_ALIGNMENT);
		ignoreReqBox.addActionListener(e ->
		{
			if (ignoreReqBoxLoading || onIgnoreRequirementsChanged == null)
			{
				return;
			}
			onIgnoreRequirementsChanged.accept(ignoreReqBox.isSelected());
		});

		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setAlignmentX(LEFT_ALIGNMENT);
		p.add(row);
		p.add(verticalGap(4));
		p.add(ignoreReqBox);
		p.add(verticalGap(4));
		p.add(progressCountLabel);
		return p;
	}

	private static final Color OUT_OF_SYNC = new Color(255, 152, 0);
	private static final Color SYNCING = new Color(120, 200, 255);

	/** Refreshes the "X / Y log slots" line and its in-sync/out-of-sync indication.
	 *  Must run on the EDT. */
	private void updateCounts()
	{
		String text = engine.collectedSlotCount() + " / " + engine.totalSlots() + " log slots";
		if (syncing)
		{
			progressCountLabel.setForeground(SYNCING);
			progressCountLabel.setText("Syncing collection log...");
			progressCountLabel.setToolTipText("Reading your full collection log");
			return;
		}
		if (inSync)
		{
			progressCountLabel.setForeground(Color.WHITE);
			progressCountLabel.setText(text);
			progressCountLabel.setToolTipText(null);
		}
		else
		{
			progressCountLabel.setForeground(OUT_OF_SYNC);
			progressCountLabel.setText("<html>" + text + " <span style='font-size:9px'>&#9888; not synced</span></html>");
			progressCountLabel.setToolTipText(playerClogCount > 0
				? "Your collection log has " + playerClogCount + " items logged — open it and click Sync to catch up"
				: "Open your collection log and click Sync to update Log Adviser");
		}
	}

	/** Pushes the latest collection-log sync status onto the progress line. Called by the
	 *  plugin from the client thread; marshals to the EDT. */
	public void setSyncStatus(boolean inSync, int playerClogCount)
	{
		SwingUtilities.invokeLater(() ->
		{
			this.inSync = inSync;
			this.playerClogCount = playerClogCount;
			updateCounts();
		});
	}

	/** Shows/clears the "Syncing collection log..." line while a full sync runs. Called by
	 *  the plugin from the client thread; marshals to the EDT. */
	public void setSyncing(boolean syncing)
	{
		SwingUtilities.invokeLater(() ->
		{
			this.syncing = syncing;
			updateCounts();
		});
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
		currentCard = card;
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
		// Repaint the whole viewport on every scroll instead of blit-copying. The list's cells are
		// variable-height with async-loading item icons; under the default BLIT_SCROLL_MODE a
		// scrollbar drag copies stale/blank pixels for rows whose icons loaded off-screen (the wheel
		// works only because it scrolls in small contiguous steps). SIMPLE mode renders drag the same.
		scroll.getViewport().setScrollMode(JViewport.SIMPLE_SCROLL_MODE);
		// Kept so the hover popup can anchor to (and hit-test against) the visible list region.
		listScroll = scroll;
		return scroll;
	}

	private JPanel buildFooter()
	{
		// The plugin panel is narrow, so all three controls share one equal-thirds
		// row. GridLayout forces equal column widths that fill the row and never
		// overlap regardless of label length; short labels + tooltips keep meaning.
		JPanel p = new JPanel(new GridLayout(1, 3, 4, 0));
		p.setBackground(ColorScheme.DARK_GRAY_COLOR);

		Insets tight = new Insets(2, 4, 2, 4);

		skipSelectedButton.setMargin(tight);
		skipSelectedButton.setToolTipText("Skip the selected activities "
			+ "— unskip them while viewing the skip list");
		skipSelectedButton.addActionListener(e ->
		{
			List<RankedActivity> sel = list.getSelectedValuesList();
			if (sel.isEmpty())
			{
				return;
			}
			for (RankedActivity r : sel)
			{
				int idx = r.getActivity().getIndex();
				if (showingSkipList)
				{
					engine.unskip(idx);
				}
				else
				{
					engine.skip(idx);
				}
			}
			tracker.persistSkipped();
		});

		viewSkipListButton.setMargin(tight);
		viewSkipListButton.setToolTipText("Show the activities you've skipped; "
			+ "click again to return to the list");
		viewSkipListButton.addActionListener(e ->
		{
			showingSkipList = viewSkipListButton.isSelected();
			// Relabel the toggle so it doubles as the "go back" affordance while
			// the skip list is showing.
			viewSkipListButton.setText(showingSkipList ? "Return" : "Skipped");
			skipSelectedButton.setText(showingSkipList ? "Unskip" : "Skip");
			list.clearSelection();
			refreshListView();
		});

		resetSkipsButton.setMargin(tight);
		resetSkipsButton.setToolTipText("Clear all skips");
		resetSkipsButton.addActionListener(e ->
		{
			engine.unskipAll();
			tracker.persistSkipped();
		});

		p.add(skipSelectedButton);
		p.add(viewSkipListButton);
		p.add(resetSkipsButton);
		// Bottom region is a Y_AXIS BoxLayout — cap the height so the row keeps its
		// natural button height instead of stretching tall to fill spare space.
		p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
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

	public void setIgnoreRequirements(boolean ignore)
	{
		SwingUtilities.invokeLater(() ->
		{
			ignoreReqBoxLoading = true;
			try
			{
				ignoreReqBox.setSelected(ignore);
			}
			finally
			{
				ignoreReqBoxLoading = false;
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
		if (log.isDebugEnabled())
		{
			StringBuilder lockedAt = new StringBuilder();
			for (int i = 0; i < ranking.size(); i++)
			{
				RankedActivity r = ranking.get(i);
				if (r.isLocked())
				{
					lockedAt.append(' ').append('#').append(i)
						.append("=idx").append(r.getActivity().getIndex());
				}
			}
			log.debug("Panel#{} applyRanking: size={}, lockedPositions=[{}]",
				System.identityHashCode(this), ranking.size(), lockedAt.toString().trim());
		}

		lastRanking = ranking;

		// Snapshot each activity's log slots on the EDT so the hover preview never reads the
		// engine off the client thread. Rebuilt every ranking change so it stays current.
		slotSnapshot.clear();
		for (RankedActivity r : ranking)
		{
			int idx = r.getActivity().getIndex();
			slotSnapshot.put(idx, buildSlotEntries(idx));
		}
		// Skipped activities are absent from the normal ranking but still hoverable in the
		// skip-list view, so snapshot them too.
		for (RankedActivity r : engine.getSkippedRanking())
		{
			int idx = r.getActivity().getIndex();
			slotSnapshot.computeIfAbsent(idx, this::buildSlotEntries);
		}

		// The current-target card should never point at a locked activity — surface the
		// first one the player can actually do.
		RankedActivity top = null;
		for (RankedActivity r : ranking)
		{
			if (!r.isLocked())
			{
				top = r;
				break;
			}
		}

		if (top == null)
		{
			currentTopRanked = null;
			currentItem.setText(ranking.isEmpty()
				? "All filtered activities complete"
				: "All available activities complete");
			currentActivity.setText(ranking.isEmpty() ? " " : "(remaining activities are locked)");
			currentHint.setText(" ");
			currentTime.setText(" ");
			currentIcon.setIcon(null);
			skipButton.setEnabled(false);
			refreshListView();
			updateCounts();
			return;
		}

		currentTopRanked = top;
		ActivityItem display = top.getEasiestItem() != null ? top.getEasiestItem() : top.getFastestItem();
		String itemName = display != null ? safeName(display.getItemId(), display.getItemName()) : "—";
		currentItem.setText(itemName);
		currentActivity.setText(top.getActivity().getName());
		ActivityNpcInfo info = staticData.npcInfoFor(top.getActivity().getIndex());
		currentHint.setText(info.getHint().isEmpty() ? "(see activity name)" : info.getHint());
		currentTime.setText("~ " + TargetInfoBox.formatHours(top.getTimeToNextSlotHours()) + " to slot");
		setIconAsync(currentIcon, display != null ? display.getItemId() : 0);
		skipButton.setEnabled(true);

		refreshListView();
		updateCounts();
	}

	/** Repopulates the list box for the active mode: the skipped activities when
	 *  the skip list is toggled on, otherwise the normal ranking. Must run on the EDT. */
	private void refreshListView()
	{
		listModel.clear();
		if (showingSkipList)
		{
			for (RankedActivity r : engine.getSkippedRanking())
			{
				listModel.addElement(r);
			}
			registerIconRefresh();
			forceListRelayout();
			return;
		}
		int desired = upcomingListSize.getAsInt();
		if (desired <= 0)
		{
			desired = 30;
		}
		// Only unlocked activities count toward the size cap; locked ones are always
		// appended at the bottom (also capped) so the player can see what to unlock
		// next instead of them being buried past the cap.
		int shownUnlocked = 0;
		List<RankedActivity> lockedRows = new ArrayList<>();
		for (RankedActivity r : lastRanking)
		{
			if (r.isLocked())
			{
				if (lockedRows.size() < desired)
				{
					lockedRows.add(r);
				}
			}
			else if (shownUnlocked < desired)
			{
				listModel.addElement(r);
				shownUnlocked++;
			}
		}
		for (RankedActivity r : lockedRows)
		{
			listModel.addElement(r);
		}
		registerIconRefresh();
		forceListRelayout();
	}

	/** Forces the JList to recompute its variable cell heights after a model rebuild. The list
	 *  uses a variable-height HTML renderer (no fixed cell height), and BasicListUI can leave some
	 *  rows with a stale/zero cached height when the model is rebuilt while the panel isn't showing
	 *  — those rows stay blank until a click recomputes the layout. Toggling fixedCellHeight fires
	 *  the property change that invalidates that cache, so every row paints immediately. */
	private void forceListRelayout()
	{
		list.setFixedCellHeight(10);
		list.setFixedCellHeight(-1);
		list.revalidate();
		list.repaint();
	}

	/** Repaints the list once each row's item icon finishes loading. The cell
	 *  renderer reuses one shared label and can't refresh itself, so a single
	 *  list-wide repaint per load lets the renderer pick up the now-cached image.
	 *  Runs once per refresh (not per paint) to avoid a repaint loop. */
	private void registerIconRefresh()
	{
		if (itemManager == null)
		{
			return;
		}
		for (int i = 0; i < listModel.size(); i++)
		{
			RankedActivity r = listModel.get(i);
			ActivityItem display = r.getEasiestItem() != null
				? r.getEasiestItem() : r.getFastestItem();
			if (display == null)
			{
				continue;
			}
			AsyncBufferedImage img = itemManager.getImage(display.getItemId());
			if (img != null)
			{
				img.onLoaded(() -> SwingUtilities.invokeLater(list::repaint));
			}
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
				String secondLine;
				if (r.isLocked())
				{
					String req = r.getRequirementLabel() == null ? "" : escape(r.getRequirementLabel());
					secondLine = "<span style='color:#c08a3e'>LOCKED</span>"
						+ " - req: " + req;
				}
				else
				{
					String time = TargetInfoBox.formatHours(r.getTimeToNextSlotHours());
					secondLine = "<span style='color:#9bc7ff'>~ " + time + "</span>"
						+ " - " + (r.getSlotsTotal() - r.getSlotsLeft()) + "/" + r.getSlotsTotal();
				}
				// Explicit body width so Swing's HTML renderer word-wraps long names
				// instead of pushing the cell past the viewport (and adding a hscroll).
				label.setText("<html><body style='width:" + TEXT_WIDTH + "px'>"
					+ "<b>" + escape(name) + "</b><br>"
					+ secondLine
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
				boolean locked = value instanceof RankedActivity && ((RankedActivity) value).isLocked();
				label.setForeground(locked ? new Color(130, 130, 130) : Color.LIGHT_GRAY);
			}
			return label;
		}

		private String escape(String s)
		{
			return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;");
		}
	}
}
