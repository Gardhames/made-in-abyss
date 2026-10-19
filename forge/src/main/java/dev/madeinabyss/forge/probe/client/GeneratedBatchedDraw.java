package dev.madeinabyss.forge.probe.client;

import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.List;

/** Render-thread-only QUADS pass. A layer owns its shader until close; no intervening render callbacks.
 * The model matrix retains the old float translation and JOML operation order. Optional Oculus code
 * is accessed only for the exact supported ExtendedShader class; other shader subclasses use vanilla.
 */
public final class GeneratedBatchedDraw implements AutoCloseable {
    public static final boolean ENABLED=Boolean.parseBoolean(System.getProperty("mia.render.batchedDraw","true"));
    private static final String EXTENDED="net.irisshaders.iris.pipeline.programs.ExtendedShader";
    private static final ClassValue<Layout> LAYOUTS=new ClassValue<>() {
        @Override protected Layout computeValue(Class<?> type){
            if(!type.getName().equals(EXTENDED))return Layout.UNSUPPORTED;
            try{return new Layout(type);}catch(ReflectiveOperationException|RuntimeException unsupported){return Layout.UNSUPPORTED;}
        }
    };
    private final Matrix4f matrix=new Matrix4f(),inverse=new Matrix4f();
    private final Matrix3f normal=new Matrix3f();
    private final float[] inverseFloats=new float[16],normalFloats=new float[9];
    private ShaderInstance shader;private Matrix4f projection;private Layout layout;
    private Uniform modelInverse,normalUniform;
    private Object samplers,uniforms,customUniforms,images,blendOverride;
    private List<?> bufferBlendOverrides;
    private boolean batched,applied,hasOverrides,restoreBlend;

    /** Equivalent to new Matrix4f(base).translate(dx,dy,dz), including JOML's copied properties. */
    public Matrix4f matrix(Matrix4f base,float dx,float dy,float dz){return matrix.set(base).translate(dx,dy,dz);}

    public void begin(ShaderInstance next,Matrix4f projection){
        if(shader!=null)throw new IllegalStateException("Nested native shader batch");
        shader=next;this.projection=projection;applied=false;layout=!ENABLED||next==null?Layout.UNSUPPORTED:LAYOUTS.get(next.getClass());
        batched=ENABLED&&next!=null&&(next.getClass()==ShaderInstance.class||layout!=Layout.UNSUPPORTED);
        if(batched&&layout!=Layout.UNSUPPORTED)try{
            modelInverse=(Uniform)layout.modelInverse.get(next);normalUniform=(Uniform)layout.normal.get(next);
            samplers=layout.samplers.get(next);uniforms=layout.uniforms.get(next);customUniforms=layout.custom.get(next);images=layout.images.get(next);
            blendOverride=layout.blend.get(next);bufferBlendOverrides=(List<?>)layout.bufferBlend.get(next);
            hasOverrides=layout.hasOverrides.getBoolean(next);restoreBlend=blendOverride!=null||hasOverrides;
        }catch(IllegalAccessException unavailable){batched=false;}
    }

    /** Caller binds the original VertexBuffer. draw() preserves its actual mode/index type/count. */
    public void draw(VertexBuffer buffer,Matrix4f model){
        if(!batched){buffer.drawWithShader(model,projection,shader);return;}
        if(shader.MODEL_VIEW_MATRIX!=null)shader.MODEL_VIEW_MATRIX.set(model);
        if(!applied){
            shared(shader,projection);shader.apply();applied=true;
        }else if(layout==Layout.UNSUPPORTED){
            if(shader.MODEL_VIEW_MATRIX!=null)shader.MODEL_VIEW_MATRIX.upload();
        }else{
            // ExtendedShader reads Uniform's FloatBuffer, which deliberately loses JOML property flags.
            // Using invert(model) directly is not an equivalent sequence of floating-point operations.
            if(shader.MODEL_VIEW_MATRIX!=null){
                if(modelInverse!=null)modelInverse.set(inverse.set(shader.MODEL_VIEW_MATRIX.getFloatBuffer()).invert().get(inverseFloats));
                if(normalUniform!=null)normalUniform.set(normal.set(inverse.set(shader.MODEL_VIEW_MATRIX.getFloatBuffer())).invert().transpose().get(normalFloats));
            }
            if(modelInverse!=null)modelInverse.upload();
            if(normalUniform!=null)normalUniform.upload();
            if(shader.MODEL_VIEW_MATRIX!=null)shader.MODEL_VIEW_MATRIX.upload();
            // Preserve the original per-draw dynamic/custom/sampler/image updates and their order.
            invoke(layout.updateSamplers,samplers);invoke(layout.updateUniforms,uniforms);
            try{layout.pushCustom.invokeExact(customUniforms,(Object)shader);}catch(Throwable failure){throw propagate(failure);}
            invoke(layout.updateImages,images);
            // These overrides notify dynamic uniforms. Preserve callbacks' baseline blend state,
            // and the subsequent override notifications, rather than retaining blend across draws.
            if(blendOverride!=null)invoke(layout.applyBlend,blendOverride);
            if(hasOverrides)for(var override:bufferBlendOverrides)invoke(layout.applyBufferBlend,override);
        }
        buffer.draw();
        if(layout!=Layout.UNSUPPORTED){
            // Original clear removes listeners after EVERY draw. Keep that lifecycle, while retaining
            // program/FBO/texture state until the enclosing layer finishes.
            invokeStatic(layout.clearUniforms);invokeStatic(layout.clearSamplers);
            if(restoreBlend)invokeStatic(layout.restoreBlend);
        }
    }

    @Override public void close(){
        try{if(applied)shader.clear();}finally{
            shader=null;projection=null;layout=null;modelInverse=normalUniform=null;
            samplers=uniforms=customUniforms=images=null;applied=false;batched=false;
            blendOverride=null;bufferBlendOverrides=null;hasOverrides=restoreBlend=false;
        }
    }

    private static void shared(ShaderInstance s,Matrix4f projection){
        for(int i=0;i<12;i++)s.setSampler("Sampler"+i,RenderSystem.getShaderTexture(i));
        if(s.PROJECTION_MATRIX!=null)s.PROJECTION_MATRIX.set(projection);
        if(s.INVERSE_VIEW_ROTATION_MATRIX!=null)s.INVERSE_VIEW_ROTATION_MATRIX.set(RenderSystem.getInverseViewRotationMatrix());
        if(s.COLOR_MODULATOR!=null)s.COLOR_MODULATOR.set(RenderSystem.getShaderColor());
        if(s.GLINT_ALPHA!=null)s.GLINT_ALPHA.set(RenderSystem.getShaderGlintAlpha());
        if(s.FOG_START!=null)s.FOG_START.set(RenderSystem.getShaderFogStart());
        if(s.FOG_END!=null)s.FOG_END.set(RenderSystem.getShaderFogEnd());
        if(s.FOG_COLOR!=null)s.FOG_COLOR.set(RenderSystem.getShaderFogColor());
        if(s.FOG_SHAPE!=null)s.FOG_SHAPE.set(RenderSystem.getShaderFogShape().getIndex());
        if(s.TEXTURE_MATRIX!=null)s.TEXTURE_MATRIX.set(RenderSystem.getTextureMatrix());
        if(s.GAME_TIME!=null)s.GAME_TIME.set(RenderSystem.getShaderGameTime());
        if(s.SCREEN_SIZE!=null){var window=Minecraft.getInstance().getWindow();s.SCREEN_SIZE.set((float)window.getWidth(),(float)window.getHeight());}
        // All native buffers are QUADS; VertexBuffer leaves LINE_WIDTH unchanged for this mode.
        RenderSystem.setupShaderLights(s);
    }

    private static void invoke(MethodHandle method,Object receiver){try{method.invokeExact(receiver);}catch(Throwable failure){throw propagate(failure);}}
    private static void invokeStatic(MethodHandle method){try{method.invokeExact();}catch(Throwable failure){throw propagate(failure);}}
    private static RuntimeException propagate(Throwable failure){if(failure instanceof Error error)throw error;return failure instanceof RuntimeException runtime?runtime:new IllegalStateException("Oculus draw continuation failed",failure);}
    private static Field field(Class<?> type,String name)throws NoSuchFieldException{var f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    private static MethodHandle update(Field field)throws ReflectiveOperationException{return MethodHandles.publicLookup().unreflect(field.getType().getMethod("update")).asType(MethodType.methodType(void.class,Object.class));}
    private static MethodHandle clear(Field field,String name)throws ReflectiveOperationException{return MethodHandles.publicLookup().unreflect(field.getType().getMethod(name));}
    private static final class Layout {
        static final Layout UNSUPPORTED=new Layout();
        Field modelInverse,normal,samplers,uniforms,custom,images,blend,bufferBlend,hasOverrides;
        MethodHandle updateSamplers,updateUniforms,pushCustom,updateImages,clearUniforms,clearSamplers,applyBlend,applyBufferBlend,restoreBlend;
        private Layout(){}
        Layout(Class<?> type)throws ReflectiveOperationException{
            modelInverse=field(type,"modelViewInverse");normal=field(type,"normalMatrix");
            samplers=field(type,"samplers");uniforms=field(type,"uniforms");custom=field(type,"customUniforms");images=field(type,"images");
            updateSamplers=update(samplers);updateUniforms=update(uniforms);updateImages=update(images);
            pushCustom=MethodHandles.publicLookup().unreflect(custom.getType().getMethod("push",Object.class)).asType(MethodType.methodType(void.class,Object.class,Object.class));
            clearUniforms=clear(uniforms,"clearActiveUniforms");clearSamplers=clear(samplers,"clearActiveSamplers");
            blend=field(type,"blendModeOverride");bufferBlend=field(type,"bufferBlendOverrides");hasOverrides=field(type,"hasOverrides");
            applyBlend=MethodHandles.publicLookup().unreflect(blend.getType().getMethod("apply")).asType(MethodType.methodType(void.class,Object.class));
            restoreBlend=MethodHandles.publicLookup().unreflect(blend.getType().getMethod("restore"));
            applyBufferBlend=MethodHandles.publicLookup().unreflect(Class.forName("net.irisshaders.iris.gl.blending.BufferBlendOverride",false,type.getClassLoader()).getMethod("apply")).asType(MethodType.methodType(void.class,Object.class));
        }
    }
}
