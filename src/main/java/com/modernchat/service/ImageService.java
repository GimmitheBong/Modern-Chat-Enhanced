package com.modernchat.service;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.IndexedSprite;
import net.runelite.client.util.ImageUtil;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Slf4j
@Singleton
public class ImageService implements ChatService
{
    public static final Pattern IMG_TAG = Pattern.compile(".*?<img=(\\d+)>\\s*(?:<[^>]+>\\s*)*([^:<>]+?)\\s*:", Pattern.CASE_INSENSITIVE);

    private final Map<Integer, ModIconCacheEntry> modIconCache = new ConcurrentHashMap<>();
    private BufferedImage filterIcon;

    @Inject private Client client;

    @Override
    public void startUp() {

    }

    @Override
    public void shutDown() {

    }

    public @Nullable BufferedImage getFilterIcon() {
        if (filterIcon == null) {
            try {
                filterIcon = ImageUtil.loadImageResource(getClass(), "/com/modernchat/images/filter.png");
            } catch (Exception e) {
                log.warn("Failed to load filter icon: {}", e.getMessage());
            }
        }
        return filterIcon;
    }

    public @Nullable Image getModIcon(int id) {
        if (id < 0)
            return null;

        IndexedSprite[] icons = client.getModIcons();
        if (icons == null || id >= icons.length || icons[id] == null)
            return null;

        IndexedSprite sprite = icons[id];
        ModIconCacheEntry cached = modIconCache.get(id);
        if (cached != null && cached.getSource() == sprite)
            return cached.getImage();

        BufferedImage img = indexedToBufferedImage(sprite);
        if (img == null)
            return null;

        modIconCache.put(id, new ModIconCacheEntry(sprite, img));
        return img;
    }

    public boolean isValidModIcon(int icon) {
        if (icon < 0)
            return false;

        IndexedSprite[] icons = client.getModIcons();
        return icons != null && icon < icons.length && icons[icon] != null;
    }

    public static BufferedImage indexedToBufferedImage(IndexedSprite s) {
        if (s == null) return null;

        final int w = s.getWidth();
        final int h = s.getHeight();
        final int[] out = new int[w * h];

        final byte[] pix = s.getPixels();
        final int[] pal  = s.getPalette(); // ARGB-ish ints from the client

        // Note: index 0 is usually transparent in RS sprites
        for (int i = 0; i < pix.length && i < out.length; i++) {
            int idx = pix[i] & 0xFF;
            if (idx == 0) {
                out[i] = 0x00000000; // transparent
            } else {
                int argb = pal[idx];
                // Ensure alpha set (some palettes have 0 alpha)
                if ((argb & 0xFF000000) == 0) argb |= 0xFF000000;
                out[i] = argb;
            }
        }

        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        bi.setRGB(0, 0, w, h, out, 0, w);
        return bi;
    }

    private static final class ModIconCacheEntry
    {
        private final IndexedSprite source;
        private final Image image;

        private ModIconCacheEntry(IndexedSprite source, Image image)
        {
            this.source = source;
            this.image = image;
        }

        private IndexedSprite getSource()
        {
            return source;
        }

        private Image getImage()
        {
            return image;
        }
    }
}
