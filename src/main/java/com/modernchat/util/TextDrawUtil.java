package com.modernchat.util;

import java.awt.Color;
import java.awt.Graphics2D;

public final class TextDrawUtil
{
    private TextDrawUtil() {}

    /**
     * Draw text with either a diagonal drop shadow or a stamped outline.
     * <p>
     * When {@code outlineThickness > 0} the shadow color is stamped at every
     * integer offset within a square of half-side {@code outlineThickness}, then
     * the foreground text is drawn on top. This routes through Java2D's cached
     * glyph-bitmap path (the same path {@code drawString} normally uses), which
     * is dramatically faster than the prior {@code GlyphVector.getOutline} +
     * {@code BasicStroke}-stroked-path approach — that combination tessellates
     * offset curves on every call and was the cause of severe FPS drops in the
     * peek overlay (which re-renders every frame).
     * <p>
     * When {@code outlineThickness == 0} the legacy diagonal drop-shadow is used.
     *
     * @param g                graphics context
     * @param text             the string to draw
     * @param x                baseline x
     * @param y                baseline y
     * @param textColor        foreground color
     * @param shadowColor      shadow / outline color
     * @param shadowOffset     pixel distance for the diagonal drop shadow
     * @param outlineThickness outline radius in pixels; 0 = drop shadow only
     */
    public static void drawTextWithShadow(Graphics2D g, String text, int x, int y,
                                           Color textColor, Color shadowColor,
                                           int shadowOffset, int outlineThickness)
    {
        if (outlineThickness > 0 && shadowColor.getAlpha() > 0)
        {
            g.setColor(shadowColor);
            final int t = outlineThickness;
            for (int dy = -t; dy <= t; dy++)
            {
                for (int dx = -t; dx <= t; dx++)
                {
                    if (dx == 0 && dy == 0) continue;
                    g.drawString(text, x + dx, y + dy);
                }
            }
            g.setColor(textColor);
            g.drawString(text, x, y);
        }
        else if (shadowOffset > 0 && shadowColor.getAlpha() > 0)
        {
            g.setColor(shadowColor);
            g.drawString(text, x + shadowOffset, y + shadowOffset);
            g.setColor(textColor);
            g.drawString(text, x, y);
        }
        else
        {
            g.setColor(textColor);
            g.drawString(text, x, y);
        }
    }
}
