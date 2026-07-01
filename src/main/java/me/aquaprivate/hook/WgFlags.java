package me.aquaprivate.hook;

import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.StringFlag;
import com.sk89q.worldguard.protection.flags.registry.FlagConflictException;
import com.sk89q.worldguard.protection.flags.registry.FlagRegistry;
import org.bukkit.plugin.Plugin;

/**
 * Регистрируем флаги, которые хотим видеть в regions.yml как в примере (ps-home, ps-tax-autopayer, ps-block-material,
 * farewell-action).
 */
public final class WgFlags {

    private WgFlags() {}

    public static StringFlag PS_HOME;
    public static StringFlag PS_TAX_AUTOPAYER;
    public static StringFlag PS_BLOCK_MATERIAL;
    public static StringFlag FAREWELL_ACTION;

    public static void register(Plugin plugin) {
        FlagRegistry registry = WorldGuard.getInstance().getFlagRegistry();
        PS_HOME = registerString(registry, plugin, "ps-home");
        PS_TAX_AUTOPAYER = registerString(registry, plugin, "ps-tax-autopayer");
        PS_BLOCK_MATERIAL = registerString(registry, plugin, "ps-block-material");
        FAREWELL_ACTION = registerString(registry, plugin, "farewell-action");
    }

    private static StringFlag registerString(FlagRegistry registry, Plugin plugin, String name) {
        try {
            StringFlag flag = new StringFlag(name);
            registry.register(flag);
            return flag;
        } catch (FlagConflictException e) {
            Flag<?> existing = registry.get(name);
            if (existing instanceof StringFlag sf) {
                return sf;
            }
            plugin.getLogger().warning("WorldGuard flag conflict for '" + name + "' (different type). This flag will be skipped.");
            return null;
        } catch (Throwable t) {
            plugin.getLogger().warning("Failed to register WorldGuard flag '" + name + "': " + t.getMessage());
            return null;
        }
    }
}
