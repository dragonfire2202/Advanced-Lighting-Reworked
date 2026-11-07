package lights.graphics;

import arc.*;
import arc.graphics.*;
import arc.graphics.Pixmap.*;
import arc.graphics.Texture.*;
import arc.graphics.g2d.*;
import arc.graphics.gl.*;
import arc.struct.*;
import arc.util.*;

/**
 * Soft light map built from the glow buffer the mod already renders, plus the vanilla block lights.
 * <p>
 * Every glowing pixel (blocks, lasers, bullets, molten liquids, units...) is an emitter. The glow buffer is shrunk to a small
 * texture and blurred with a multi-scale pyramid, so the light spreads smoothly over any distance for a small cost. The spread is
 * measured in world units, so the light keeps its look when the camera zooms in and out.
 * <p>
 * The map is used in two ways:
 * <ul>
 *     <li><b>Tint</b>: the light is added on top of the scene, so glowing things softly light the blocks around them.</li>
 *     <li><b>Night lighting</b>: replaces the vanilla night darkness. Darkness is lifted wherever the light map has light.
 *     Vanilla block lights are rendered into the same map, so they light their surroundings the same way.</li>
 * </ul>
 */
public class AdvanceLightMap{
    /** Light map resolution divisors, from the lowest to the highest quality preset. */
    public static final int[] divisors = {32, 16, 8, 4, 2, 1};
    private static final int maxLevels = 7;
    /** Map values are small after blurring; this brings them back to a useful brightness. */
    private static final float baseGain = 2f;

    private static final String vert = """
            attribute vec4 a_position;
            attribute vec2 a_texCoord0;
                                    
            varying vec2 v_texCoords;
                                    
            void main(){
                v_texCoords = a_texCoord0;
                gl_Position = a_position;
            }
            """;

    /** Smooth B-spline sampling of a low resolution map, using four bilinear fetches. Needs linear filtering. */
    private static final String cubicLib = """
            vec4 cubic(float v){
                vec4 n = vec4(1.0, 2.0, 3.0, 4.0) - v;
                vec4 s = n * n * n;
                float x = s.x;
                float y = s.y - 4.0 * s.x;
                float z = s.z - 4.0 * s.y + 6.0 * s.x;
                float w = 6.0 - x - y - z;
                return vec4(x, y, z, w) * (1.0 / 6.0);
            }
                        
            vec3 sampleLight(sampler2D tex, vec2 texCoords, vec2 texSize, float useCubic){
                if(useCubic < 0.5){
                    return texture2D(tex, texCoords).rgb;
                }
                        
                vec2 invTexSize = 1.0 / texSize;
                texCoords = texCoords * texSize - 0.5;
                vec2 fxy = fract(texCoords);
                texCoords -= fxy;
                vec4 xcubic = cubic(fxy.x);
                vec4 ycubic = cubic(fxy.y);
                vec4 c = texCoords.xxyy + vec2(-0.5, 1.5).xyxy;
                vec4 s = vec4(xcubic.xz + xcubic.yw, ycubic.xz + ycubic.yw);
                vec4 offset = c + vec4(xcubic.yw, ycubic.yw) / s;
                offset *= invTexSize.xxyy;
                vec3 sample0 = texture2D(tex, offset.xz).rgb;
                vec3 sample1 = texture2D(tex, offset.yz).rgb;
                vec3 sample2 = texture2D(tex, offset.xw).rgb;
                vec3 sample3 = texture2D(tex, offset.yw).rgb;
                float sx = s.x / (s.x + s.y);
                float sy = s.z / (s.z + s.w);
                return mix(mix(sample3, sample2, sx), mix(sample1, sample0, sx), sy);
            }
            """;

    /** Master switch. */
    public boolean enabled = false;
    /** Replace vanilla night lighting. */
    public boolean night = false;
    /** Render vanilla block lights into the light map, instead of discarding them, while night lighting is replaced. */
    public boolean vanilla = true;
    /** Resolution preset, 1 - 6, an index into {@link #divisors}. */
    public int resolution = 4;
    /** How far the light spreads, 1 - 10. Measured in world units, so it does not depend on zoom. */
    public int radius = 5;
    /** Extra smoothing passes over the finished map, 0 - 4. Hides the blocky look of low resolutions. */
    public int blur = 1;
    /** Overall light strength multiplier. */
    public float intensity = 1f;
    /** Strength of the colored light added on top of the scene. */
    public float tint = 0.25f;

    private final Target[] pre = new Target[3];
    private final Target[] levels = new Target[maxLevels + 1];
    private final Target scratch = new Target(), vanillaMap = new Target();
    private final float[] weights = new float[maxLevels + 1];

    private Shader downShader, halveShader, upShader, blurShader, scaleShader, tintShader, nightShader;
    private boolean ready;
    private int srcW = -1, srcH = -1, w0, h0, div = 4;
    private boolean hasMap, vanillaHas;

    public AdvanceLightMap(){
        for(int i = 0; i < pre.length; i++) pre[i] = new Target();
        for(int i = 0; i < levels.length; i++) levels[i] = new Target();
    }

    /** A framebuffer that is created and resized on demand. */
    private static class Target{
        FrameBuffer fb;
        int w = -1, h = -1;

        FrameBuffer get(int nw, int nh){
            if(fb == null){
                fb = new FrameBuffer(Format.rgba8888, nw, nh, false);
            }else if(nw != w || nh != h){
                fb.resize(nw, nh);
            }else{
                return fb;
            }

            w = nw;
            h = nh;
            fb.getTexture().setFilter(TextureFilter.linear, TextureFilter.linear);
            return fb;
        }
    }

    private void init(){
        if(ready) return;
        ready = true;

        //Averages a 4x4 grid across the covered source area, mixed with the maximum so thin lasers don't vanish.
        downShader = new Shader(vert, """
                uniform sampler2D u_texture;
                uniform vec2 u_step;
                uniform float u_mix;
                uniform float u_scale;
                                        
                varying vec2 v_texCoords;
                                        
                void main(){
                    vec3 sum = vec3(0.0);
                    vec3 mx = vec3(0.0);
                    for(int x = 0; x < 4; x++){
                        for(int y = 0; y < 4; y++){
                            vec2 o = vec2(float(x) - 1.5, float(y) - 1.5) * u_step;
                            vec4 t = texture2D(u_texture, v_texCoords + o);
                            vec3 c = t.rgb * t.a;
                            sum += c;
                            mx = max(mx, c);
                        }
                    }
                    gl_FragColor = vec4(mix(sum / 16.0, mx, u_mix) * u_scale, 1.0);
                }
                """);

        //Halves the resolution with a 2x2 box.
        halveShader = new Shader(vert, """
                uniform sampler2D u_texture;
                uniform vec2 u_texel;
                uniform float u_mix;
                uniform float u_scale;
                                        
                varying vec2 v_texCoords;
                                        
                void main(){
                    vec3 a = texture2D(u_texture, v_texCoords + u_texel * vec2(-0.5, -0.5)).rgb;
                    vec3 b = texture2D(u_texture, v_texCoords + u_texel * vec2(0.5, -0.5)).rgb;
                    vec3 c = texture2D(u_texture, v_texCoords + u_texel * vec2(-0.5, 0.5)).rgb;
                    vec3 d = texture2D(u_texture, v_texCoords + u_texel * vec2(0.5, 0.5)).rgb;
                    vec3 avg = (a + b + c + d) * 0.25;
                    vec3 mx = max(max(a, b), max(c, d));
                    gl_FragColor = vec4(mix(avg, mx, u_mix) * u_scale, 1.0);
                }
                """);

        //Smooth upsample of a coarser level, meant to be added on top of a finer one.
        upShader = new Shader(vert, """
                uniform sampler2D u_texture;
                uniform vec2 u_texel;
                uniform float u_weight;
                                        
                varying vec2 v_texCoords;
                                        
                void main(){
                    vec3 c = texture2D(u_texture, v_texCoords + u_texel * vec2(-0.5, -0.5)).rgb;
                    c += texture2D(u_texture, v_texCoords + u_texel * vec2(0.5, -0.5)).rgb;
                    c += texture2D(u_texture, v_texCoords + u_texel * vec2(-0.5, 0.5)).rgb;
                    c += texture2D(u_texture, v_texCoords + u_texel * vec2(0.5, 0.5)).rgb;
                    gl_FragColor = vec4(c * 0.25 * u_weight, 1.0);
                }
                """);

        //9-tap gaussian, one direction at a time.
        blurShader = new Shader(vert, """
                uniform sampler2D u_texture;
                uniform vec2 u_dir;
                                        
                varying vec2 v_texCoords;
                                        
                void main(){
                    vec3 c = texture2D(u_texture, v_texCoords).rgb * 0.227027;
                    c += texture2D(u_texture, v_texCoords + u_dir).rgb * 0.1945946;
                    c += texture2D(u_texture, v_texCoords - u_dir).rgb * 0.1945946;
                    c += texture2D(u_texture, v_texCoords + u_dir * 2.0).rgb * 0.1216216;
                    c += texture2D(u_texture, v_texCoords - u_dir * 2.0).rgb * 0.1216216;
                    c += texture2D(u_texture, v_texCoords + u_dir * 3.0).rgb * 0.054054;
                    c += texture2D(u_texture, v_texCoords - u_dir * 3.0).rgb * 0.054054;
                    c += texture2D(u_texture, v_texCoords + u_dir * 4.0).rgb * 0.016216;
                    c += texture2D(u_texture, v_texCoords - u_dir * 4.0).rgb * 0.016216;
                    gl_FragColor = vec4(c, 1.0);
                }
                """);

        scaleShader = new Shader(vert, """
                uniform sampler2D u_texture;
                uniform float u_scale;
                                        
                varying vec2 v_texCoords;
                                        
                void main(){
                    gl_FragColor = vec4(texture2D(u_texture, v_texCoords).rgb * u_scale, 1.0);
                }
                """);

        //Colored light, meant to be drawn with additive blending.
        tintShader = new Shader(vert, """
                uniform sampler2D u_texture;
                uniform vec2 u_size;
                uniform float u_cubic;
                uniform float u_gain;
                                        
                varying vec2 v_texCoords;
                                        
                """ + cubicLib + """
                                        
                void main(){
                    vec3 l = sampleLight(u_texture, v_texCoords, u_size, u_cubic);
                    gl_FragColor = vec4(l * u_gain, 1.0);
                }
                """);

        //Darkness overlay, meant to be drawn with normal blending. Light lifts the darkness.
        nightShader = new Shader(vert, """
                uniform sampler2D u_texture;
                uniform vec2 u_size;
                uniform float u_cubic;
                uniform vec4 u_ambient;
                uniform float u_gain;
                                        
                varying vec2 v_texCoords;
                                        
                """ + cubicLib + """
                                        
                void main(){
                    vec3 l = sampleLight(u_texture, v_texCoords, u_size, u_cubic);
                    float i = clamp(max(l.r, max(l.g, l.b)) * u_gain, 0.0, 1.0);
                    i = i * i * (3.0 - 2.0 * i);
                    gl_FragColor = vec4(u_ambient.rgb, u_ambient.a * (1.0 - i));
                }
                """);
    }

    /** Computes the map size for the current screen and resolution preset. */
    private void ensureSize(){
        init();

        int sw = Math.max(1, Core.graphics.getWidth());
        int sh = Math.max(1, Core.graphics.getHeight());
        int d = divisors[Math.max(1, Math.min(divisors.length, resolution)) - 1];

        if(sw != srcW || sh != srcH || d != div){
            srcW = sw;
            srcH = sh;
            div = d;
            w0 = Math.max(2, sw / d);
            h0 = Math.max(2, sh / d);

            //Contents are lost when buffers are resized.
            hasMap = false;
            vanillaHas = false;
        }
    }

    /**
     * Renders vanilla lights (block lights, lamps, unit lights...) into their own map. They are already soft gradients,
     * so they are added to the finished light map without extra blur. Must be called before vanilla clears them.
     */
    public void captureVanilla(Seq<Runnable> lights){
        vanillaHas = false;
        if(!enabled || !vanilla || lights.isEmpty()) return;

        ensureSize();
        FrameBuffer vm = vanillaMap.get(w0, h0);

        try{
            Draw.flush();
            vm.begin(Color.clear);
            Draw.proj(Core.camera.mat);
            Draw.blend(Blending.additive);
            for(Runnable r : lights){
                r.run();
            }
            Draw.flush();
            vanillaHas = true;
        }catch(Throwable t){
            vanilla = false;
            vanillaHas = false;
            Log.err("[Advance Lighting] Capturing vanilla lights failed, they are discarded instead. Please report this.", t);
        }finally{
            Draw.blend();
            Draw.reset();
            try{
                vm.end();
            }catch(Throwable ignored){}
        }
    }

    /** Builds the light map from the full-resolution glow buffer. Call after the glow buffer is complete. */
    public void update(FrameBuffer emission){
        if(!enabled) return;
        ensureSize();

        //How far the light spreads, in texels of the finest level. Based on world units, so it follows the camera zoom.
        float camW = Core.camera != null && Core.camera.width > 1f ? Core.camera.width : srcW;
        float spread = radius * 6f * (srcW / camW) / div;

        int maxN = 0;
        while(maxN < maxLevels && (Math.min(w0, h0) >> (maxN + 1)) >= 2) maxN++;

        int n = 0;
        if(spread > 1f){
            n = Math.min(maxN, (int)Math.ceil(Math.log(spread) / Math.log(2.0)));
        }

        //Contribution of each blur level. Normalized, so brightness stays the same when the number of levels changes.
        weights[0] = 1f;
        float total = 1f;
        for(int k = 1; k <= n; k++){
            weights[k] = Math.min(1f, spread / (1 << k));
            total += weights[k];
        }
        float scale = 1f / total;

        Draw.flush();
        Blending.normal.apply();

        FrameBuffer l0 = levels[0].get(w0, h0);

        //Downsample the glow buffer.
        if(div <= 4){
            l0.begin(Color.clear);
            downShader.bind();
            downShader.setUniformf("u_step", (div / 4f) / srcW, (div / 4f) / srcH);
            downShader.setUniformf("u_mix", 0.35f);
            downShader.setUniformf("u_scale", scale);
            emission.blit(downShader);
            l0.end();
        }else{
            //Large steps are done in halves, otherwise thin lasers fall between the sampled pixels.
            int count = 0;
            for(int d = div; d > 4; d >>= 1) count++;

            FrameBuffer prev = null;
            int prevW = 0, prevH = 0;
            for(int i = 0; i < count; i++){
                int pw = Math.max(2, srcW / (4 << i)), ph = Math.max(2, srcH / (4 << i));
                FrameBuffer cur = pre[i].get(pw, ph);

                cur.begin(Color.clear);
                if(i == 0){
                    downShader.bind();
                    downShader.setUniformf("u_step", 1f / srcW, 1f / srcH);
                    downShader.setUniformf("u_mix", 0.35f);
                    downShader.setUniformf("u_scale", 1f);
                    emission.blit(downShader);
                }else{
                    halveShader.bind();
                    halveShader.setUniformf("u_texel", 1f / prevW, 1f / prevH);
                    halveShader.setUniformf("u_mix", 0.5f);
                    halveShader.setUniformf("u_scale", 1f);
                    prev.blit(halveShader);
                }
                cur.end();

                prev = cur;
                prevW = pw;
                prevH = ph;
            }

            l0.begin(Color.clear);
            halveShader.bind();
            halveShader.setUniformf("u_texel", 1f / prevW, 1f / prevH);
            halveShader.setUniformf("u_mix", 0.5f);
            halveShader.setUniformf("u_scale", scale);
            prev.blit(halveShader);
            l0.end();
        }

        //Pyramid: halve n times, then add every level back on top of the finer one.
        for(int i = 1; i <= n; i++){
            Target src = levels[i - 1];
            FrameBuffer dst = levels[i].get(Math.max(2, w0 >> i), Math.max(2, h0 >> i));

            dst.begin(Color.clear);
            halveShader.bind();
            halveShader.setUniformf("u_texel", 1f / src.w, 1f / src.h);
            halveShader.setUniformf("u_mix", 0f);
            halveShader.setUniformf("u_scale", 1f);
            src.fb.blit(halveShader);
            dst.end();
        }

        for(int i = n; i >= 1; i--){
            Target coarse = levels[i];
            FrameBuffer fine = levels[i - 1].fb;

            fine.begin();
            Blending.additive.apply();
            upShader.bind();
            upShader.setUniformf("u_texel", 1f / coarse.w, 1f / coarse.h);
            upShader.setUniformf("u_weight", weights[i] / weights[i - 1]);
            coarse.fb.blit(upShader);
            fine.end();
            Blending.normal.apply();
        }

        //Vanilla lights are added as they are, scaled so they keep their vanilla brightness after the shared gain.
        if(vanillaHas){
            l0.begin();
            Blending.additive.apply();
            scaleShader.bind();
            scaleShader.setUniformf("u_scale", 1f / baseGain);
            vanillaMap.fb.blit(scaleShader);
            l0.end();
            Blending.normal.apply();
            vanillaHas = false;
        }

        //Extra smoothing, cheap because the map is small.
        for(int i = 0; i < blur; i++){
            FrameBuffer tmp = scratch.get(w0, h0);

            tmp.begin(Color.clear);
            blurShader.bind();
            blurShader.setUniformf("u_dir", 1f / w0, 0f);
            l0.blit(blurShader);
            tmp.end();

            l0.begin(Color.clear);
            blurShader.bind();
            blurShader.setUniformf("u_dir", 0f, 1f / h0);
            tmp.blit(blurShader);
            l0.end();
        }

        hasMap = true;
    }

    private void bindMap(Shader s){
        s.setUniformf("u_size", w0, h0);
        s.setUniformf("u_cubic", div >= 2 ? 1f : 0f);
    }

    /** Adds the light on top of the scene, so nearby blocks are softly lit in the color of the emitter. */
    public void drawTint(){
        if(!enabled || !hasMap || tint <= 0.001f) return;

        Draw.flush();
        Blending.additive.apply();

        tintShader.bind();
        bindMap(tintShader);
        tintShader.setUniformf("u_gain", tint * intensity * baseGain);
        levels[0].fb.blit(tintShader);

        Blending.normal.apply();
    }

    /**
     * Draws the night darkness, with light lifting it. Uses the map built in the previous frame, which is
     * not noticeable because the map is blurry.
     */
    public void drawNight(Color ambient){
        if(!enabled || !night) return;
        ensureSize();

        Draw.flush();
        Blending.normal.apply();

        nightShader.bind();
        bindMap(nightShader);
        nightShader.setUniformf("u_ambient", ambient);
        nightShader.setUniformf("u_gain", hasMap ? intensity * baseGain : 0f);
        levels[0].get(w0, h0).blit(nightShader);

        Blending.normal.apply();
    }
}
