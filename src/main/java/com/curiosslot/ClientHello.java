package com.curiosslot;

import com.curiosslot.network.ClientHelloPacket;
import com.curiosslot.network.CuriosSlotNetworking;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 客户端专用：登录后向服务端发送握手包，让服务端知道本客户端已安装 curiosslot。
 * 只有客户端装了 mod 才会注册本订阅并发送此包。
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT)
public class ClientHello {

    @SubscribeEvent
    public static void onClientLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.client.player.LocalPlayer) {
            CuriosSlotNetworking.CHANNEL.sendToServer(new ClientHelloPacket());
        }
    }
}
