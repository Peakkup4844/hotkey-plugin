package dev.hotkeycmd.config;

public enum KeyTrigger {

    SWAP_HAND,
    SNEAK_SWAP_HAND,
    DROP,
    SNEAK_DROP,
    DROP_STACK,
    SNEAK_DROP_STACK,
    HOTBAR_1, HOTBAR_2, HOTBAR_3, HOTBAR_4, HOTBAR_5,
    HOTBAR_6, HOTBAR_7, HOTBAR_8, HOTBAR_9,
    SNEAK_HOTBAR_1, SNEAK_HOTBAR_2, SNEAK_HOTBAR_3, SNEAK_HOTBAR_4, SNEAK_HOTBAR_5,
    SNEAK_HOTBAR_6, SNEAK_HOTBAR_7, SNEAK_HOTBAR_8, SNEAK_HOTBAR_9;

    /**
     * Returns the hotbar trigger for a given slot (0-based) and sneak state.
     * slot 0 → HOTBAR_1, slot 8 → HOTBAR_9.
     */
    public static KeyTrigger hotbar(int slot, boolean sneak) {
        String name = (sneak ? "SNEAK_" : "") + "HOTBAR_" + (slot + 1);
        return KeyTrigger.valueOf(name);
    }
}
