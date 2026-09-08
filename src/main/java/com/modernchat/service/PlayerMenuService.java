package com.modernchat.service;

import com.modernchat.common.NotificationService;
import com.modernchat.draw.UsernameHit;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.IconID;
import net.runelite.api.MenuAction;
import net.runelite.api.MessageNode;
import net.runelite.api.ScriptEvent;
import net.runelite.api.VarClientInt;
import net.runelite.api.VarClientStr;
import net.runelite.api.widgets.Widget;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.hiscore.HiscoreEndpoint;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.hiscore.HiscorePlugin;
import net.runelite.client.util.Text;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;

/**
 * Recreates the player-name menu exposed by the legacy chatbox for Modern Chat usernames.
 * Add friend / Add ignore are committed through the social panel's own add input, the same
 * mechanism the game uses when a name is typed into the add field.
 */
@Slf4j
@Singleton
public class PlayerMenuService
{
    private static final String ADD_FRIEND = "Add friend";
    private static final String ADD_IGNORE = "Add ignore";
    private static final String LOOKUP = "Look up";
    private static final String COPY_TO_CLIPBOARD = "Copy to clipboard";

    // Side-panel tab index for the social (friends) tab in VarClientInt.INVENTORY_TAB.
    private static final int SOCIAL_TAB = 8;

    private final Client client;
    private final ClientThread clientThread;
    private final PluginManager pluginManager;
    private final NotificationService notificationService;

    private int previousTab = Integer.MIN_VALUE;

    @Inject
    public PlayerMenuService(
        Client client,
        ClientThread clientThread,
        PluginManager pluginManager,
        NotificationService notificationService)
    {
        this.client = client;
        this.clientThread = clientThread;
        this.pluginManager = pluginManager;
        this.notificationService = notificationService;
    }

    /**
     * Adds the standard username actions. Inserting every entry at index {@code 1} in this order
     * produces the same top-to-bottom order as the legacy RuneLite chat menu.
     */
    public void addMenuEntries(UsernameHit hit)
    {
        if (hit == null)
        {
            return;
        }

        final String username = hit.getUsername();
        if (username == null || username.trim().isEmpty())
        {
            return;
        }

        final int messageId = hit.getMessageId();

        client.getMenu().createMenuEntry(1)
            .setOption(ADD_FRIEND)
            .setTarget(username)
            .setType(MenuAction.RUNELITE)
            .onClick(entry -> invokeChatAction(username, messageId, ADD_FRIEND));

        client.getMenu().createMenuEntry(1)
            .setOption(ADD_IGNORE)
            .setTarget(username)
            .setType(MenuAction.RUNELITE)
            .onClick(entry -> invokeChatAction(username, messageId, ADD_IGNORE));

        client.getMenu().createMenuEntry(1)
            .setOption(LOOKUP)
            .setTarget(username)
            .setType(MenuAction.RUNELITE)
            .onClick(entry -> lookupPlayer(username, messageId));

        client.getMenu().createMenuEntry(1)
            .setOption(COPY_TO_CLIPBOARD)
            .setTarget(username)
            .setType(MenuAction.RUNELITE)
            .onClick(entry -> copyUsername(username));
    }

    private void invokeChatAction(String username, int messageId, String action)
    {
        clientThread.invokeLater(() -> dispatchChatAction(username, messageId, action, 0));
    }

    /**
     * Commits an Add friend / Add ignore action through the social panel's own add slot: the name
     * is written to the chat input and the panel's Add Friend/Add Ignore action is dispatched. If
     * the panel is not loaded the social tab is opened once and the attempt retried.
     */
    private void dispatchChatAction(String username, int messageId, String action, int phase)
    {
        try
        {
            final boolean ignore = ADD_IGNORE.equals(action);
            if (!ADD_FRIEND.equals(action) && !ignore)
            {
                notifyUnavailable(action + " is unavailable for " + username + ".");
                return;
            }

            if (executeSocialPanelAdd(username, ignore))
            {
                restoreSocialPanel();
                return;
            }

            if (phase == 0)
            {
                previousTab = rememberCurrentTab();
                openSocialPanel();
                clientThread.invokeAtTickEnd(() -> dispatchChatAction(username, messageId, action, 1));
                return;
            }

            restoreSocialPanel();
            notifyUnavailable(action + " is unavailable for " + username + ".");
        }
        catch (Throwable ex)
        {
            log.warn("Unable to execute chat action {} for {}", action, username, ex);
            notifyUnavailable(action + " is unavailable for " + username + ".");
        }
    }

    /**
     * Dispatches the game's social-panel add action, mirroring how the client commits an entry
     * typed into the Add Friend / Add Ignore field.
     */
    private boolean executeSocialPanelAdd(String username, boolean ignore)
    {
        final String name = Text.removeTags(username);
        final String label = ignore ? "ignore" : "friends";
        final int boxId = ignore ? InterfaceID.Ignore.ADDIGNORE : InterfaceID.Friends.ADDFRIEND;
        final int universeId = ignore ? InterfaceID.Ignore.UNIVERSE : InterfaceID.Friends.UNIVERSE;

        Widget universe = client.getWidget(universeId);
        Widget box = client.getWidget(boxId);
        if (universe == null)
        {
            log.debug("chatmenu-panel: {} universe not loaded for {}", label, username);
        }
        if (box == null)
        {
            log.debug("chatmenu-panel: {} add box missing for {} (universe={})", label, username,
                universe == null ? null : Integer.toHexString(universe.getId()));
            return false;
        }

        Object[] listener = box.getOnOpListener();
        if (listener == null || listener.length == 0)
        {
            log.debug("chatmenu-panel: {} add box has no listener: id={} name={} text={} actions={}",
                label, Integer.toHexString(box.getId()), box.getName(), box.getText(),
                box.getActions() == null ? null : Arrays.toString(box.getActions()));
            return false;
        }

        try
        {
            client.setVarcStrValue(VarClientStr.INPUT_TEXT, name);
            client.setVarcIntValue(VarClientInt.INPUT_TYPE, 1);
        }
        catch (Throwable ex)
        {
            log.debug("Unable to seed the chat input", ex);
        }

        Object[] args = new Object[listener.length];
        System.arraycopy(listener, 0, args, 0, listener.length);
        for (int i = 0; i < args.length; i++)
        {
            if (ScriptEvent.NAME.equals(args[i]))
            {
                args[i] = name;
            }
        }

        ScriptEvent event = client.createScriptEventBuilder(args)
            .setSource(box)
            .setOp(1)
            .build();
        event.run();
        log.debug("chatmenu-panel: dispatched {} for {} on box={} type={} listener={}", label, name,
            Integer.toHexString(box.getId()), inputState(),
            Arrays.toString(listener));
        return true;
    }

    private String inputState()
    {
        try
        {
            return "INPUT_TYPE=" + client.getVarcIntValue(VarClientInt.INPUT_TYPE)
                + " INPUT_TEXT=" + client.getVarcStrValue(VarClientStr.INPUT_TEXT);
        }
        catch (Throwable ex)
        {
            return "INPUT state=unknown";
        }
    }

    private int rememberCurrentTab()
    {
        try
        {
            return client.getVarcIntValue(VarClientInt.INVENTORY_TAB);
        }
        catch (Throwable ex)
        {
            return Integer.MIN_VALUE;
        }
    }

    private void openSocialPanel()
    {
        try
        {
            client.setVarcIntValue(VarClientInt.INVENTORY_TAB, SOCIAL_TAB);
        }
        catch (Throwable ex)
        {
            log.debug("Unable to open the social panel", ex);
        }
    }

    private void restoreSocialPanel()
    {
        if (previousTab == Integer.MIN_VALUE)
        {
            return;
        }
        try
        {
            if (client.getVarcIntValue(VarClientInt.INVENTORY_TAB) == SOCIAL_TAB)
            {
                client.setVarcIntValue(VarClientInt.INVENTORY_TAB, previousTab);
            }
        }
        catch (Throwable ex)
        {
            log.debug("Unable to restore the previous side panel", ex);
        }
        finally
        {
            previousTab = Integer.MIN_VALUE;
        }
    }

    private MessageNode findMessageNode(int messageId)
    {
        if (messageId < 0 || client.getMessages() == null)
        {
            return null;
        }

        try
        {
            return client.getMessages().get(messageId);
        }
        catch (Throwable ex)
        {
            log.debug("Unable to resolve MessageNode {}", messageId, ex);
            return null;
        }
    }

    private void lookupPlayer(String username, int messageId)
    {
        HiscorePlugin hiscorePlugin = findActiveHiscorePlugin();
        if (hiscorePlugin == null)
        {
            notifyUnavailable("Enable the HiScore plugin to look up " + username + ".");
            return;
        }

        try
        {
            Method getWorldEndpoint = HiscorePlugin.class.getDeclaredMethod("getWorldEndpoint");
            Method lookupPlayer = HiscorePlugin.class.getDeclaredMethod(
                "lookupPlayer", String.class, HiscoreEndpoint.class);
            getWorldEndpoint.setAccessible(true);
            lookupPlayer.setAccessible(true);

            HiscoreEndpoint endpoint = (HiscoreEndpoint) getWorldEndpoint.invoke(hiscorePlugin);
            if (endpoint == null)
            {
                notifyUnavailable("HiScore lookup is unavailable right now.");
                return;
            }
            endpoint = resolveChatHiscoreEndpoint(username, messageId, endpoint);
            lookupPlayer.invoke(hiscorePlugin, Text.removeTags(username), endpoint);
        }
        catch (ReflectiveOperationException | RuntimeException ex)
        {
            Throwable cause = ex instanceof InvocationTargetException && ex.getCause() != null
                ? ex.getCause()
                : ex;
            log.warn("Unable to invoke the HiScore lookup for {}", username, cause);
            notifyUnavailable("HiScore lookup is unavailable right now.");
        }
    }

    /**
     * Match the HiScore plugin's chat-menu endpoint selection. Account icons on a chat name take
     * precedence over the current world's endpoint; a name without a league icon on a seasonal
     * world is treated as a normal-world player.
     */
    private HiscoreEndpoint resolveChatHiscoreEndpoint(
        String username, int messageId, HiscoreEndpoint worldEndpoint)
    {
        MessageNode messageNode = findMessageNode(messageId);
        String rawName = messageNode != null && messageNode.getName() != null
            ? messageNode.getName()
            : username;

        HiscoreEndpoint chatEndpoint = HiscoreEndpoint.NORMAL;
        if (rawName.contains(IconID.IRONMAN.toString()))
        {
            chatEndpoint = HiscoreEndpoint.IRONMAN;
        }
        else if (rawName.contains(IconID.ULTIMATE_IRONMAN.toString()))
        {
            chatEndpoint = HiscoreEndpoint.ULTIMATE_IRONMAN;
        }
        else if (rawName.contains(IconID.HARDCORE_IRONMAN.toString()))
        {
            chatEndpoint = HiscoreEndpoint.HARDCORE_IRONMAN;
        }
        else if (rawName.contains(IconID.LEAGUE.toString()))
        {
            chatEndpoint = HiscoreEndpoint.SEASONAL;
        }

        return chatEndpoint != HiscoreEndpoint.NORMAL || worldEndpoint == HiscoreEndpoint.SEASONAL
            ? chatEndpoint
            : worldEndpoint;
    }

    private HiscorePlugin findActiveHiscorePlugin()
    {
        Collection<Plugin> plugins = pluginManager.getPlugins();
        if (plugins == null)
        {
            return null;
        }

        for (Plugin plugin : plugins)
        {
            if (plugin instanceof HiscorePlugin && pluginManager.isPluginActive(plugin))
            {
                return (HiscorePlugin) plugin;
            }
        }
        return null;
    }

    private void copyUsername(String username)
    {
        try
        {
            StringSelection selection = new StringSelection(Text.removeTags(username));
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, null);
        }
        catch (Throwable ex)
        {
            log.warn("Unable to copy username {} to the clipboard", username, ex);
            notifyUnavailable("Unable to copy " + username + " to the clipboard.");
        }
    }

    private void notifyUnavailable(String message)
    {
        try
        {
            notificationService.pushChatMessage(message);
        }
        catch (Throwable ex)
        {
            log.debug("Unable to show player-menu notification: {}", message, ex);
        }
    }
}