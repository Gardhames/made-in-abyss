package dev.madeinabyss.forge.probe.deep.mixin;

import com.mojang.blaze3d.platform.Window;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Automated test runs (-Dmia.client.background=true) must not steal keyboard focus from the user's
 * foreground window. Requires the FML early window off (earlyWindowControl=false), so that the window
 * is created in this constructor; the launcher additionally sends it to the bottom of the Z order.
 * Mixin 0.8.5 allows constructor injects only at RETURN, and Embeddium already redirects
 * setupMinecraftWindow, so the hints ride on the last vanilla glfwWindowHint before window creation. */
@Mixin(Window.class)
public abstract class BackgroundWindowMixin {
    @Redirect(method = "<init>", at = @At(value = "INVOKE", ordinal = 5, remap = false,
            target = "Lorg/lwjgl/glfw/GLFW;glfwWindowHint(II)V"))
    private void mia$backgroundHints(int hint, int value) {
        GLFW.glfwWindowHint(hint, value);
        // Whenever this constructor creates the window (FML early window off), vanilla would ask for GL 3.2
        // core; the early window yields 4.6 and our renderers use GL 4.x entry points (glBlendFuncSeparatei),
        // so always request the same. With the early window on, these hints are unused.
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 6);
        if (!Boolean.getBoolean("mia.client.background")) return;
        GLFW.glfwWindowHint(GLFW.GLFW_FOCUSED, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
    }
}
