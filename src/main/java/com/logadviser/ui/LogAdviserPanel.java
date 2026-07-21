package com.logadviser.ui;

import com.logadviser.data.ActivityItem;
import com.logadviser.data.ActivityNpcInfo;
import com.logadviser.data.LogSlot;
import com.logadviser.data.StaticData;
import com.logadviser.engine.AccountMode;
import com.logadviser.engine.AdviserEngine;
import com.logadviser.engine.MembershipMode;
import com.logadviser.engine.RankedActivity;
import com.logadviser.engine.ShowFilter;
import com.logadviser.sync.CollectionLogTracker;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.MouseMotionListener;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
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
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JToggleButton;
import javax.swing.JViewport;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
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
	private final Consumer<MembershipMode> onMembershipModeChanged;
	private final Consumer<Boolean> onIgnoreRequirementsChanged;
	private final Consumer<Boolean> onPetsOnlyChanged;
	private final IntSupplier upcomingListSize;
	// When this supplies true, the hover preview popup is suppressed (config "Disable hover preview").
	private final BooleanSupplier hoverDisabled;

	// Header
	private final JLabel playerLabel = new JLabel("(not logged in)");
	private final JComboBox<AccountMode> accountModeBox = new JComboBox<>(AccountMode.values());
	private final JComboBox<MembershipMode> membershipModeBox = new JComboBox<>(MembershipMode.values());
	private final JLabel modeBadge = new JLabel("");
	// Filter row — multi-select "Show": tick any of Combat/Minigame/Misc/Slayer (unioned), or the
	// exclusive "Pets Only". A button shows the current selection and opens the checkbox popup; the
	// down-arrow is painted flush-right (matching the other dropdowns) rather than sitting inline.
	private final JPopupMenu filterPopup = new JPopupMenu();
	private final JButton filterTrigger = new JButton()
	{
		@Override
		protected void paintComponent(Graphics g)
		{
			super.paintComponent(g);
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(getForeground());
			int aw = 8;
			int x = getWidth() - aw - 7;
			int y = getHeight() / 2 - 2;
			g2.fillPolygon(new int[]{x, x + aw, x + aw / 2}, new int[]{y, y, y + 4}, 3);
			g2.dispose();
		}
	};
	private final Map<ShowFilter, JCheckBox> filterBoxes = new EnumMap<>(ShowFilter.class);
	private final JCheckBox petsOnlyBox = new JCheckBox("Pets Only", false);
	// When the "Show" popup last closed. Lets a click on an open trigger toggle it shut instead of
	// flashing it: the popup grab cancels on mouse-press, before the trigger's action fires on release.
	private long filterPopupHiddenAt;
	// When ticked, activities you don't meet skill/quest requirements for are ranked
	// normally instead of being demoted to the locked section.
	private final JCheckBox ignoreReqBox = new JCheckBox("Ignore requirements", false);
	// Progress line shown directly under the "Show:" filter. Turns orange with a marker
	// when the plugin's data is behind the player's real collection log.
	private final JLabel progressCountLabel = new JLabel(" ");
	// Permanent hint under the slot count telling the player how to bring the data up to date.
	private final JLabel syncHintLabel = new JLabel(
		"<html><div style='width:180px'>Click the Log Sync button in the collection log to sync</div></html>");
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
	// Mirrors the engine's pets-only mode on the EDT so the renderer / current-card can drop the
	// x/x slot count and relabel the time without reading the client-thread-confined engine.
	private boolean petsOnlyView = false;
	// Most recent normal ranking, so the skip-list toggle can rebuild the default
	// view without re-querying the engine.
	private List<RankedActivity> lastRanking = new ArrayList<>();
	// Newest ranking awaiting an EDT rebuild, or null when none is queued. applyRanking() is
	// expensive (a full slot snapshot + list rebuild), so a burst of engine updates coalesces
	// into a single rebuild of the latest snapshot rather than one rebuild each.
	private final AtomicReference<List<RankedActivity>> pendingRanking = new AtomicReference<>();

	private RankedActivity currentTopRanked;
	private boolean accountModeBoxLoading = false;
	private boolean membershipModeBoxLoading = false;
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
		Consumer<MembershipMode> onMembershipModeChanged,
		Consumer<Boolean> onIgnoreRequirementsChanged,
		Consumer<Boolean> onPetsOnlyChanged,
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
		this.onMembershipModeChanged = onMembershipModeChanged;
		this.onIgnoreRequirementsChanged = onIgnoreRequirementsChanged;
		this.onPetsOnlyChanged = onPetsOnlyChanged;
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
		// Anchor to a CONSTANT position derived only from the visible list region: top-aligned with
		// the first row and flush against the list's left edge. Independent of the row/cursor/scroll
		// offset, so the box never drifts when scrolling, and is always to the left. Flush (no gap)
		// so pollHide's hit test stays contiguous as the pointer moves from a row onto the popup.
		Point vpLoc = vp.getLocationOnScreen();
		// Clamp within the monitor RuneLite is actually on (its bounds can have a negative origin
		// for a left-of-primary monitor) — not the primary screen — so the popup never jumps to the
		// other monitor on a multi-display setup.
		GraphicsConfiguration gc = vp.getGraphicsConfiguration();
		Rectangle b = gc != null
			? gc.getBounds()
			: new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
		int x = vpLoc.x - ActivityLogPopup.FIXED_W;
		x = Math.max(b.x, Math.min(x, b.x + b.width - ActivityLogPopup.FIXED_W));
		int y = vpLoc.y;
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
		// Within each group, easiest first: lower slotDifficulty, tie-broken by fewer attempts.
		// This is the popup's own ordering for the slot list; it is independent of the headline
		// display item (which follows the time estimate). Missing group precedes done.
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
		// Match the "Show" dropdown: render a filled triangle instead of FlatLaf's default unfilled
		// chevron. String key (FlatClientProperties.STYLE) avoids a FlatLaf import; a no-op otherwise.
		accountModeBox.putClientProperty("FlatLaf.style", "arrowType: triangle");
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

		// Membership filter, a sibling of "Account mode": F2P hides members-only slots, P2P shows all.
		JPanel memberPanel = new JPanel(new BorderLayout(4, 2));
		memberPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JLabel memberLabel = new JLabel("Membership:");
		memberLabel.setForeground(Color.LIGHT_GRAY);
		memberPanel.add(memberLabel, BorderLayout.WEST);
		membershipModeBox.putClientProperty("FlatLaf.style", "arrowType: triangle");
		membershipModeBox.addActionListener(e ->
		{
			if (membershipModeBoxLoading)
			{
				return;
			}
			MembershipMode m = (MembershipMode) membershipModeBox.getSelectedItem();
			if (m != null)
			{
				onMembershipModeChanged.accept(m);
			}
		});
		memberPanel.add(membershipModeBox, BorderLayout.CENTER);

		// Stack the two dropdown rows; BorderLayout.SOUTH only takes one component.
		JPanel modeStack = new JPanel();
		modeStack.setLayout(new BoxLayout(modeStack, BoxLayout.Y_AXIS));
		modeStack.setBackground(ColorScheme.DARK_GRAY_COLOR);
		modeStack.add(modePanel);
		modeStack.add(verticalGap(4));
		modeStack.add(memberPanel);
		p.add(modeStack, BorderLayout.SOUTH);
		return p;
	}

	private JPanel buildFilterRow()
	{
		JPanel row = new JPanel(new BorderLayout(4, 0));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JLabel label = new JLabel("Show:");
		label.setForeground(Color.LIGHT_GRAY);
		buildFilterControl();
		row.add(label, BorderLayout.WEST);
		row.add(filterTrigger, BorderLayout.CENTER);

		progressCountLabel.setForeground(Color.WHITE);
		progressCountLabel.setFont(progressCountLabel.getFont().deriveFont(Font.BOLD, 16f));
		progressCountLabel.setAlignmentX(LEFT_ALIGNMENT);

		syncHintLabel.setForeground(Color.GRAY);
		syncHintLabel.setFont(syncHintLabel.getFont().deriveFont(Font.PLAIN, 10f));
		syncHintLabel.setAlignmentX(LEFT_ALIGNMENT);

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
		p.add(syncHintLabel);
		return p;
	}

	private static final Color OUT_OF_SYNC = new Color(255, 152, 0);
	private static final Color SYNCING = new Color(120, 200, 255);

	/** Refreshes the "X / Y log slots" line and its in-sync/out-of-sync indication.
	 *  Must run on the EDT. */
	private void updateCounts()
	{
		String text = petsOnlyView
			? engine.collectedPetCount() + " / " + engine.totalPetCount() + " pets"
			: engine.collectedSlotCount() + " / " + engine.totalSlots() + " log slots";
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
				? "Your collection log has " + playerClogCount + " items logged — open it and click Log Sync to catch up"
				: "Open your collection log and click Log Sync to update Log Adviser");
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

	public void setMembershipMode(MembershipMode mode)
	{
		SwingUtilities.invokeLater(() ->
		{
			membershipModeBoxLoading = true;
			try
			{
				membershipModeBox.setSelectedItem(mode);
			}
			finally
			{
				membershipModeBoxLoading = false;
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

	// Builds the "Show" trigger button and its checkbox popup. Plain JCheckBoxes (not
	// JCheckBoxMenuItems) are added to the JPopupMenu so toggling one does NOT close the popup —
	// the user can tick several before clicking away. Categories union; Pets Only is exclusive.
	private void buildFilterControl()
	{
		filterTrigger.setHorizontalAlignment(SwingConstants.LEFT);
		// Reserve room on the right so a long summary never runs under the painted arrow.
		filterTrigger.setMargin(new Insets(2, 6, 2, 18));
		filterTrigger.setFocusable(false);
		// Record when the popup closes so clicking an open trigger toggles it shut: the click's
		// mouse-press cancels the popup grab (firing this listener), then the trigger's action fires
		// on release — if that release lands right after the close, treat it as the toggle, not a reopen.
		filterPopup.addPopupMenuListener(new PopupMenuListener()
		{
			@Override
			public void popupMenuWillBecomeVisible(PopupMenuEvent e)
			{
			}

			@Override
			public void popupMenuWillBecomeInvisible(PopupMenuEvent e)
			{
				filterPopupHiddenAt = System.currentTimeMillis();
			}

			@Override
			public void popupMenuCanceled(PopupMenuEvent e)
			{
			}
		});
		filterTrigger.addActionListener(e ->
		{
			if (System.currentTimeMillis() - filterPopupHiddenAt > 200)
			{
				filterPopup.show(filterTrigger, 0, filterTrigger.getHeight());
			}
		});

		filterPopup.setBackground(ColorScheme.DARK_GRAY_COLOR);

		for (ShowFilter f : ShowFilter.values())
		{
			JCheckBox cb = new JCheckBox(f.displayName(), false);
			cb.setForeground(Color.LIGHT_GRAY);
			cb.setBackground(ColorScheme.DARK_GRAY_COLOR);
			cb.setFocusable(false);
			cb.addActionListener(e ->
			{
				// Categories and Pets Only are mutually exclusive: picking a category leaves pets mode.
				if (cb.isSelected())
				{
					petsOnlyBox.setSelected(false);
				}
				applyFilter();
			});
			filterBoxes.put(f, cb);
			filterPopup.add(cb);
		}

		filterPopup.addSeparator();

		petsOnlyBox.setForeground(Color.LIGHT_GRAY);
		petsOnlyBox.setBackground(ColorScheme.DARK_GRAY_COLOR);
		petsOnlyBox.setFocusable(false);
		petsOnlyBox.setToolTipText("Re-frame the ranking around the pets you still need "
			+ "(exclusive — ignores the category selection)");
		petsOnlyBox.addActionListener(e ->
		{
			// Pets Only clears every category tick so the two modes never apply at once.
			if (petsOnlyBox.isSelected())
			{
				for (JCheckBox cb : filterBoxes.values())
				{
					cb.setSelected(false);
				}
			}
			applyFilter();
		});
		filterPopup.add(petsOnlyBox);

		updateFilterSummary();
	}

	private void applyFilter()
	{
		boolean pets = petsOnlyBox.isSelected();
		EnumSet<ShowFilter> selected = EnumSet.noneOf(ShowFilter.class);
		if (!pets)
		{
			for (Map.Entry<ShowFilter, JCheckBox> e : filterBoxes.entrySet())
			{
				if (e.getValue().isSelected())
				{
					selected.add(e.getKey());
				}
			}
		}
		// Keep the EDT-side view flag in step with the mode before any repaint, so the renderer and
		// current card pick it up. The show-filter is stored first (no recompute); the pets toggle
		// is marshalled to the client thread and its recompute fires the authoritative snapshot last.
		// In pets mode the show-filter is left empty (= All) so the pet ranking sees every category.
		petsOnlyView = pets;
		engine.setShowFilter(selected);
		if (onPetsOnlyChanged != null)
		{
			onPetsOnlyChanged.accept(pets);
		}
		updateFilterSummary();
	}

	// Reflects the current selection on the trigger button: "Pets Only", "All" (nothing ticked),
	// or the ticked categories joined in enum order (e.g. "Combat, Slayer").
	private void updateFilterSummary()
	{
		String summary;
		if (petsOnlyBox.isSelected())
		{
			summary = "Pets Only";
		}
		else
		{
			StringBuilder sb = new StringBuilder();
			for (Map.Entry<ShowFilter, JCheckBox> e : filterBoxes.entrySet())
			{
				if (e.getValue().isSelected())
				{
					if (sb.length() > 0)
					{
						sb.append(", ");
					}
					sb.append(e.getKey().displayName());
				}
			}
			summary = sb.length() == 0 ? "All" : sb.toString();
		}
		// Arrow is painted separately (see field); the text holds just the summary.
		filterTrigger.setText(summary);
	}

	public void onRankingChanged(List<RankedActivity> ranking)
	{
		// Park the newest snapshot and only queue a rebuild if one isn't already pending —
		// the queued task always applies the latest snapshot, so intermediate ones are dropped
		// instead of each costing a full (and immediately superseded) rebuild on the EDT.
		if (pendingRanking.getAndSet(new ArrayList<>(ranking)) != null)
		{
			return;
		}
		SwingUtilities.invokeLater(() ->
		{
			List<RankedActivity> snapshot = pendingRanking.getAndSet(null);
			if (snapshot != null)
			{
				applyRanking(snapshot);
			}
		});
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
				? (petsOnlyView ? "All available pets collected" : "All filtered activities complete")
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
		ActivityItem display = top.getDisplayItem() != null ? top.getDisplayItem() : top.getFastestItem();
		String itemName = display != null ? safeName(display.getItemId(), display.getItemName()) : "—";
		currentItem.setText(itemName);
		currentActivity.setText(top.getActivity().getName());
		ActivityNpcInfo info = staticData.npcInfoFor(top.getActivity().getIndex());
		currentHint.setText(info.getHint().isEmpty() ? "(see activity name)" : info.getHint());
		currentTime.setText("~ " + TargetInfoBox.formatHours(top.getTimeToNextSlotHours())
			+ (petsOnlyView ? " to pet" : " to slot"));
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
			ActivityItem display = r.getDisplayItem() != null
				? r.getDisplayItem() : r.getFastestItem();
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
				ActivityItem display = r.getDisplayItem() != null ? r.getDisplayItem() : r.getFastestItem();
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
					String timeSpan = "<span style='color:#9bc7ff'>~ " + time + "</span>";
					// Pets mode times the pet drop alone, so the slot x/x count is meaningless here.
					secondLine = petsOnlyView
						? timeSpan
						: timeSpan + " - " + (r.getSlotsTotal() - r.getSlotsLeft()) + "/" + r.getSlotsTotal();
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
