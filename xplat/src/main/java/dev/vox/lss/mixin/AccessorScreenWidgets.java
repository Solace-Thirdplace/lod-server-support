package dev.vox.lss.mixin;

import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Remapped vanilla seam; legacy Sodium hooks never target an inherited MC method. */
@Mixin(Screen.class)
public interface AccessorScreenWidgets {
    @Invoker("addRenderableWidget")
    GuiEventListener lss$addWidget(GuiEventListener widget);
}
