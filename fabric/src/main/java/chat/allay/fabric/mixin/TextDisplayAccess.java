package chat.allay.fabric.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Display.TextDisplay.class)
public interface TextDisplayAccess {
  @Invoker("setText") void allay$text(Component text);
  @Invoker("setFlags") void allay$flags(byte flags);
}
