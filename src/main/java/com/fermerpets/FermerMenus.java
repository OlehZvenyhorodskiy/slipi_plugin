
package com.fermerpets;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class FermerMenus {
    private static FermerMenuService inst;
    public static void init(FermerMenuService svc){ inst = svc; }
    public static FermerMenuService get(){ return inst; }
    public static boolean handleMenu(Player p, String[] args){
        if (inst==null){ p.sendMessage("Меню недоступно."); return true; }
        return inst.onCommand(p, null, "fermerpets", args);
    }
}
