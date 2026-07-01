package me.aquaprivate.util;

import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;

/**
 * Tiny NBT helper used only to force "visual glow" without showing enchant text.
 *
 * We avoid hard NMS dependencies by using reflection.
 */
public final class NbtUtil {

    private NbtUtil() {}

    /**
     * Adds an EMPTY enchantments list in raw NBT to force the glint effect
     * WITHOUT any visible enchant lines in tooltip.
     *
     * This is the same trick used by plugins like ProtectionStones for ViaVersion/older clients.
     */
    public static ItemStack withEmptyEnchantGlint(ItemStack stack) {
        try {
            Class<?> craftItemStack = Class.forName("org.bukkit.craftbukkit.inventory.CraftItemStack");
            Method asNmsCopy = craftItemStack.getMethod("asNMSCopy", ItemStack.class);
            Method asBukkitCopy = craftItemStack.getMethod("asBukkitCopy", Object.class);

            Object nms = asNmsCopy.invoke(null, stack);

            // ItemStack#getOrCreateTag (modern)
            Method getOrCreateTag;
            try {
                getOrCreateTag = nms.getClass().getMethod("getOrCreateTag");
            } catch (NoSuchMethodException ex) {
                // 1.17+ mojang-mapped: getOrCreateTag()
                getOrCreateTag = nms.getClass().getMethod("getOrCreateTag");
            }

            Object tag = getOrCreateTag.invoke(nms);

            // HideFlags=127 (hide everything; important part is HIDE_ENCHANTS)
            invokePutInt(tag, "HideFlags", 127);

            // Enchantments: [] (empty list)
            Object emptyList = createEmptyListTag();
            if (emptyList != null) {
                invokePutTag(tag, "Enchantments", emptyList);
            }

            return (ItemStack) asBukkitCopy.invoke(null, nms);
        } catch (Throwable t) {
            return stack;
        }
    }

    private static void invokePutInt(Object compound, String key, int value) throws Exception {
        // Mojang mapped: CompoundTag.putInt(String, int)
        try {
            Method m = compound.getClass().getMethod("putInt", String.class, int.class);
            m.invoke(compound, key, value);
            return;
        } catch (NoSuchMethodException ignored) {}

        // Legacy: setInt(String, int)
        try {
            Method m = compound.getClass().getMethod("setInt", String.class, int.class);
            m.invoke(compound, key, value);
        } catch (NoSuchMethodException ignored) {}
    }

    private static void invokePutTag(Object compound, String key, Object tagValue) throws Exception {
        // Mojang mapped: CompoundTag.put(String, Tag)
        try {
            Class<?> tagClazz = Class.forName("net.minecraft.nbt.Tag");
            Method m = compound.getClass().getMethod("put", String.class, tagClazz);
            m.invoke(compound, key, tagValue);
            return;
        } catch (Throwable ignored) {}

        // Legacy: set(String, NBTBase)
        try {
            Class<?> base = Class.forName("net.minecraft.nbt.Tag");
            Method m = compound.getClass().getMethod("set", String.class, base);
            m.invoke(compound, key, tagValue);
        } catch (Throwable ignored) {}
    }

    private static Object createEmptyListTag() {
        // Mojang mapped: net.minecraft.nbt.ListTag
        try {
            Class<?> listTag = Class.forName("net.minecraft.nbt.ListTag");
            return listTag.getConstructor().newInstance();
        } catch (Throwable ignored) {}

        // Try common legacy names
        for (String name : new String[]{"net.minecraft.server.NBTTagList", "NBTTagList"}) {
            try {
                Class<?> c = Class.forName(name);
                return c.getConstructor().newInstance();
            } catch (Throwable ignored2) {}
        }
        return null;
    }
}
