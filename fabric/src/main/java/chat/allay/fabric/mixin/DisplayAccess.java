package chat.allay.fabric.mixin;

import com.mojang.math.Transformation;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The display entity's setters, private in the game: marks and drafts build their displays with them. */
@Mixin(Display.class)
public interface DisplayAccess {
  @Invoker("setTransformation") void allay$transformation(Transformation transformation);
  @Invoker("setBillboardConstraints") void allay$billboard(Display.BillboardConstraints billboard);
  @Invoker("setBrightnessOverride") void allay$brightness(Brightness brightness);
  @Invoker("setViewRange") void allay$viewRange(float range);
  @Invoker("setGlowColorOverride") void allay$glowColor(int rgb);
}
