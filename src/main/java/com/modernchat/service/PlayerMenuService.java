package com.modernchat.service;

import com.modernchat.common.NotificationService;
import com.modernchat.draw.UsernameHit;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.IconID;
import net.runelite.api.MenuAction;
import net.runelite.api.MessageNode;
import net.runelite.api.ScriptEvent;
import net.runelite.api.ScriptID;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Recreates the player-name menu exposed by the legacy chatbox for Modern Chat usernames.
 */
@Slf4j
@Singleton
public class PlayerMenuService
{
    private static final String ADD_FRIEND = "Add friend";
    private static final String ADD_IGNORE = "Add ignore";
    private static final String LOOKUP = "Look up";
    private static final String COPY_TO_CLIPBOARD = "Copy to clipboard";

    // The game generates chat-line menus dynamically at menu-open time and stores no
    // static actions on the line widgets in this revision, so when the actions array
    // is empty the ops fall back to the game's dynamically generated chat-line menu:
    // 1 = Message, 2 = Add ignore, 3 = Add friend.
    private static final int OP_ADD_IGNORE = 2;
    private static final int OP_ADD_FRIEND = 3;

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
            .onClick(entry -> invokeLegacyChatAction(username, messageId, ADD_FRIEND));

        client.getMenu().createMenuEntry(1)
            .setOption(ADD_IGNORE)
            .setTarget(username)
            .setType(MenuAction.RUNELITE)
            .onClick(entry -> invokeLegacyChatAction(username, messageId, ADD_IGNORE));

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

    /** Alias which makes the call site self-documenting. */
    public void addUsernameMenuEntries(UsernameHit hit)
    {
        addMenuEntries(hit);
    }

    private void invokeLegacyChatAction(String username, int messageId, String action)
    {
        clientThread.invokeLater(() -> dispatchChatAction(username, messageId, action, 0));
    }

    /**
     * Resolves and commits an Add friend / Add ignore action. The social-panel commit is the
     * game's own add mechanism (seeding the chat input with the name and dispatching the
     * panel's Add Friend/Add Ignore slot), fully decoupled from the chat line widgets which
     * no longer stay populated for arbitrary clicks. If the panel is not loaded the social
     * tab is opened once and the attempt retried; only then is the legacy chat line dispatch
     * used as a fallback.
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
                return;
            }

            if (phase == 0)
            {
                openSocialPanel();
                previousTab = rememberPreviousTab();
                clientThread.invokeAtTickEnd(() -> dispatchChatAction(username, messageId, action, 1));
                return;
            }

            Widget chatLine = findChatLineWidget(username, messageId, action);
            if (chatLine != null)
            {
                restoreSocialPanel();
                executeLineAction(chatLine, username, action);
                return;
            }

            if (phase == 1)
            {
                forceChatboxRebuild();
                clientThread.invokeAtTickEnd(() -> dispatchChatAction(username, messageId, action, 2));
                return;
            }

            restoreSocialPanel();
            if (executeSocialPanelAdd(username, ignore))
            {
                return;
            }

            chatLine = findChatLineWidget(username, messageId, action);
            if (chatLine != null)
            {
                executeLineAction(chatLine, username, action);
                return;
            }

            notifyUnavailable(action + " is unavailable for " + username + ".");
            dumpWidgetIntrospection(action, username);
        }
        catch (Throwable ex)
        {
            log.warn("Unable to execute legacy chat action {} for {}", action, username, ex);
            notifyUnavailable(action + " is unavailable for " + username + ".");
        }
    }

    /**
     * Adds a friend or ignore through the game's social-panel action: the name is written to
     * the chat input and the panel's Add Friend/Add Ignore action is dispatched, mirroring
     * how the game itself commits an entry typed into the add field.
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
            Integer.toHexString(box.getId()), logInputState(),
            Arrays.toString(listener));
        return true;
    }

    private String logInputState()
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

    private int rememberPreviousTab()
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

    private void executeLineAction(Widget widget, String username, String action)
    {
        int op = opForAction(widget.getActions(), action);
        Object[] listener = widget.getOnOpListener();
        if (op <= 0 || listener == null || listener.length == 0)
        {
            log.debug("chatmenu: line {} has no usable listener/op for {}", widget.getId(), action);
            notificationService.pushChatMessage(action + " is unavailable for " + username + ".");
            return;
        }

        Object[] eventArgs = new Object[listener.length];
        System.arraycopy(listener, 0, eventArgs, 0, listener.length);
        String targetName = widget.getName();
        if (targetName == null || targetName.isEmpty())
        {
            targetName = Text.removeTags(username);
        }
        for (int i = 0; i < eventArgs.length; i++)
        {
            if (ScriptEvent.NAME.equals(eventArgs[i]))
            {
                eventArgs[i] = targetName;
            }
        }

        ScriptEvent event = client.createScriptEventBuilder(eventArgs)
            .setSource(widget)
            .setOp(op)
            .build();
        event.run();
        log.debug("chatmenu: dispatched {} for {} on line={} op={} listener={}", action, username,
            Integer.toHexString(widget.getId()), op, Arrays.toString(listener));
    }

    private void forceChatboxRebuild()
    {
        Widget root = client.getWidget(InterfaceID.CHATBOX, 0);
        try
        {
            if (root != null)
            {
                root.setHidden(false);
            }
            client.runScript(ScriptID.BUILD_CHATBOX);
        }
        catch (Throwable ex)
        {
            log.debug("Unable to force the chatbox to rebuild", ex);
        }
    }

    private void dumpWidgetIntrospection(String action, String username)
    {
        dumpChildren("FriendsPanel", client.getWidget(InterfaceID.Friends.UNIVERSE));
        dumpChildren("IgnorePanel", client.getWidget(InterfaceID.Ignore.UNIVERSE));
        dumpWidget("ChatInput", client.getWidget(InterfaceID.Chatbox.INPUT));
        dumpWidget("ChatInputClickArea", client.getWidget(InterfaceID.Chatbox.INPUT_CLICKAREA));
        log.debug("chatmenu-input: {}", logInputState());
    }

    private void dumpChildren(String label, Widget widget)
    {
        if (widget == null)
        {
            log.debug("chatmenu-{}: root not loaded", label);
            return;
        }
        Widget[] children = widget.getChildren();
        if (children == null || children.length == 0)
        {
            log.debug("chatmenu-{}: no children", label);
            return;
        }
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(children.length, 30);
        for (int i = 0; i < shown; i++)
        {
            Widget child = children[i];
            if (child == null)
            {
                continue;
            }
            sb.append(String.format("  child=%d id=%s name=%s text=%s actions=%s listener=%s%n",
                i, Integer.toHexString(child.getId()), child.getName(), child.getText(),
                child.getActions() == null ? null : Arrays.toString(child.getActions()),
                child.getOnOpListener() == null ? null : Arrays.toString(child.getOnOpListener())));
        }
        log.debug("chatmenu-{}: {} children:\n{}", label, children.length, sb.toString().trim());
    }

    private void dumpWidget(String label, Widget widget)
    {
        if (widget == null)
        {
            log.debug("chatmenu-{}: not loaded", label);
            return;
        }
        log.debug("chatmenu-{}: id={} name={} text={} actions={} listener={}", label,
            Integer.toHexString(widget.getId()), widget.getName(), widget.getText(),
            widget.getActions() == null ? null : Arrays.toString(widget.getActions()),
            widget.getOnOpListener() == null ? null : Arrays.toString(widget.getOnOpListener()));
    }

    private Widget findChatLineWidget(String username, int messageId, String action)
    {
        final String normalizedUsername = normalize(username);
        if (normalizedUsername.isEmpty())
        {
            return null;
        }

        MessageNode messageNode = findMessageNode(messageId);
        final int expectedType = messageNode != null && messageNode.getType() != null
            ? messageNode.getType().getType()
            : Integer.MIN_VALUE;
        final String expectedBody = messageNode != null ? normalizeBody(messageNode.getValue()) : "";

        List<Widget> candidates = new ArrayList<>();
        collectWidget(client.getWidget(InterfaceID.Chatbox.SCROLLAREA), candidates);
        collectWidget(client.getWidget(InterfaceID.Chatbox.MES_LAYER_SCROLLAREA), candidates);
        collectWidget(client.getWidget(InterfaceID.Chatbox.MES_LAYER_SCROLLCONTENTS), candidates);
        collectWidget(client.getWidget(InterfaceID.Chatbox.CHATDISPLAY), candidates);
        for (int componentId = InterfaceID.Chatbox.LINE0;
             componentId <= InterfaceID.Chatbox.LINE99;
             componentId++)
        {
            collectWidget(client.getWidget(componentId), candidates);
        }

        Set<Integer> seen = new HashSet<>();
        Widget best = null;
        int bestScore = Integer.MIN_VALUE;
        List<String> diagnostics = null;

        for (Widget widget : candidates)
        {
            if (widget == null || !seen.add(widget.getId()))
            {
                continue;
            }

            int op = opForAction(widget.getActions(), action);
            Object[] listener = widget.getOnOpListener();
            boolean nameMatches = matchesUsername(widget, normalizedUsername);
            if (diagnostics == null)
            {
                diagnostics = new ArrayList<>();
            }
            diagnostics.add(String.format(
                "id=%s op=%d nameMatch=%s listener=%s name=%s text=%s actions=%s",
                Integer.toHexString(widget.getId()),
                op,
                nameMatches,
                listener == null ? null : Arrays.toString(listener),
                widget.getName(),
                widget.getText(),
                widget.getActions() == null ? null : Arrays.toString(widget.getActions())));

            if (listener == null || listener.length == 0)
            {
                continue;
            }

            int score = 0;
            if (nameMatches)
            {
                score += 8;
            }
            if (widget.getActions() != null)
            {
                score += 10;
            }
            if (expectedType != Integer.MIN_VALUE && listenerMessageType(listener) == expectedType)
            {
                score += 4;
            }
            if (!expectedBody.isEmpty() && expectedBody.equals(normalizeBody(widget.getText())))
            {
                score += 8;
            }

            if (score > bestScore)
            {
                best = widget;
                bestScore = score;
            }
        }

        if (best == null && diagnostics != null)
        {
            final int cap = 100;
            List<String> shown = diagnostics.size() > cap
                ? new ArrayList<>(diagnostics.subList(0, cap))
                : diagnostics;
            log.debug("chatmenu: unable to resolve {} for {} (msg type={}, body={}); {} candidates, showing {}:\n{}",
                action, normalizedUsername,
                expectedType == Integer.MIN_VALUE ? null : expectedType, expectedBody,
                diagnostics.size(), shown.size(),
                String.join("\n", shown));
        }
        else
        {
            log.debug("chatmenu: resolved {} for {} to {}", action, normalizedUsername,
                best == null ? null : Integer.toHexString(best.getId()));
        }

        return best;
    }

    private static void collectWidget(Widget widget, List<Widget> out)
    {
        collectWidget(widget, out, 0);
    }

    private static void collectWidget(Widget widget, List<Widget> out, int depth)
    {
        if (widget == null || depth > 3)
        {
            return;
        }
        out.add(widget);
        Widget[] children = widget.getChildren();
        if (children != null)
        {
            for (Widget child : children)
            {
                if (child != null)
                {
                    collectWidget(child, out, depth + 1);
                }
            }
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

    /**
     * Returns the 1-based op for the given action in the widget's actions array, or {@code -1}.
     * The op of a widget menu entry is its 1-based position in {@link Widget#getActions()},
     * not a fixed constant; it must be derived from the actions the game has populated.
     */
    private static int actionOp(String[] actions, String action)
    {
        if (actions == null)
        {
            return -1;
        }

        final String wanted = Text.removeTags(action).trim().toLowerCase(Locale.ENGLISH);
        for (int i = 0; i < actions.length; i++)
        {
            if (actions[i] == null || actions[i].isEmpty())
            {
                continue;
            }
            String candidate = Text.removeTags(actions[i]).trim().toLowerCase(Locale.ENGLISH);
            if (wanted.equals(candidate))
            {
                return i + 1;
            }
        }
        return -1;
    }

    /**
     * Chooses the op to dispatch for the action. Prefers the action's position in the
     * widget's actions array when the game has populated one, and otherwise falls back
     * to the fixed ops the game's dynamically generated chat-line menu uses.
     */
    private static int opForAction(String[] actions, String action)
    {
        int derived = actionOp(actions, action);
        if (derived > 0)
        {
            return derived;
        }
        if (ADD_IGNORE.equals(action))
        {
            return OP_ADD_IGNORE;
        }
        if (ADD_FRIEND.equals(action))
        {
            return OP_ADD_FRIEND;
        }
        return -1;
    }

    private static boolean matchesUsername(Widget widget, String normalizedUsername)
    {
        if (normalizedUsername.isEmpty())
        {
            return false;
        }

        String name = normalize(widget.getName());
        if (normalizedUsername.equals(name))
        {
            return true;
        }

        String text = normalizeSenderText(widget.getText());
        if (normalizedUsername.equals(text))
        {
            return true;
        }

        // The line widget may hold the full "name: message" text rather than the sender alone.
        if (text.startsWith(normalizedUsername) && text.length() > normalizedUsername.length())
        {
            char boundary = text.charAt(normalizedUsername.length());
            return boundary == ':' || boundary == ' ' || boundary == '-';
        }
        return false;
    }

    private static int listenerMessageType(Object[] listener)
    {
        Object value = listener[listener.length - 1];
        return value instanceof Number ? ((Number) value).intValue() : Integer.MIN_VALUE;
    }

    private static String normalizeSenderText(String value)
    {
        String normalized = normalize(value);
        while (normalized.endsWith(":"))
        {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        return normalized;
    }

    private static String normalizeBody(String value)
    {
        if (value == null)
        {
            return "";
        }
        return Text.removeTags(value).replace('\u00A0', ' ').trim();
    }

    private static String normalize(String value)
    {
        return value == null ? "" : Text.standardize(value);
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