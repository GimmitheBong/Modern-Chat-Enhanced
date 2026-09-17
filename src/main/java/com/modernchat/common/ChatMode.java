package com.modernchat.common;

import lombok.Getter;

@Getter
public enum ChatMode
{
    // Values match the vanilla chatbox channel types (0 = public, 1 = cheat,
    // 2 = friends chat, 3 = clan chat, 4 = guest clan, 5 = group chat).
    CLAN_GIM(5, -1),
    PRIVATE(-1, -1),
    PUBLIC(0, 0),
    FRIENDS_CHAT(2, 1),
    CLAN_MAIN(3, 2),
    CLAN_GUEST(4, 3);

    final int value, order;
    ChatMode(int value, int order) {
        this.value = value;
        this.order = order;
    }
}
