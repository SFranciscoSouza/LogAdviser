package com.logadviser.ui;

import com.logadviser.engine.AdviserEngine;
import com.logadviser.engine.RankedActivity;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.List;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/**
 * Box rendered on top of the game viewport (top-right by default, draggable). Mirrors
 * the InfoBox content but is much harder to miss — useful when you want the active
 * step staring at you while you're playing rather than buried in the corner tray.
 */
public class TargetTextOverlay extends OverlayPanel
{
	private static final Color BLUE = new Color(155, 199, 255);
	private static final Color YELLOW = new Color(255, 234, 100);
	private static final Color GRAY = new Color(180, 180, 180);
	private static final Color WHITE = new Color(230, 230, 230);
	private static final int PANEL_WIDTH = 240;

	private final AdviserEngine engine;
	private volatile RankedActivity current;
	private volatile String itemName = "";
	private volatile String hint = "";

	public TargetTextOverlay(AdviserEngine engine)
	{
		this.engine = engine;
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPosition(OverlayPosition.TOP_RIGHT);
		setPriority(OverlayPriority.LOW);
		// Wider than the default so long activity names ("Killing the nightmare of
		// ashihama (5 person team)") don't collide with right-aligned values.
		getPanelComponent().setPreferredSize(new Dimension(PANEL_WIDTH, 0));
	}

	public void setTarget(RankedActivity ranked, String itemName, String hint)
	{
		this.current = ranked;
		this.itemName = itemName == null ? "" : itemName;
		this.hint = hint == null ? "" : hint;
	}

	public void clearTarget()
	{
		this.current = null;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		RankedActivity r = current;
		if (r == null)
		{
			return null;
		}
		panelComponent.getChildren().clear();
		panelComponent.getChildren().add(TitleComponent.builder()
			.text("Log Adviser")
			.color(BLUE)
			.build());

		// Item slot — full-width yellow line (the headline).
		panelComponent.getChildren().add(LineComponent.builder()
			.left(itemName)
			.leftColor(YELLOW)
			.build());

		// Activity name — full-width on its own row so long names use the entire
		// panel width instead of being squeezed into a right-aligned column.
		panelComponent.getChildren().add(LineComponent.builder()
			.left(r.getActivity().getName())
			.leftColor(WHITE)
			.build());

		// Hint — full-width gray line, wraps automatically.
		if (!hint.isEmpty())
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left(hint)
				.leftColor(GRAY)
				.build());
		}

		// Compact left/right summary rows — these short values fit cleanly.
		panelComponent.getChildren().add(LineComponent.builder()
			.left("Time")
			.leftColor(GRAY)
			.right(TargetInfoBox.formatHours(r.getTimeToNextSlotHours()))
			.rightColor(BLUE)
			.build());

		panelComponent.getChildren().add(LineComponent.builder()
			.left("Activity logs")
			.leftColor(GRAY)
			.right((r.getSlotsTotal() - r.getSlotsLeft()) + " / " + r.getSlotsTotal())
			.rightColor(WHITE)
			.build());

		return super.render(graphics);
	}
}
