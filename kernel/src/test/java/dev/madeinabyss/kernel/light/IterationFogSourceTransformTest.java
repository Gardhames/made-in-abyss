package dev.madeinabyss.kernel.light;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IterationFogSourceTransformTest {
    private static final String HEADER = "void VolumetricFog(inout vec3 color, vec3 startPos, vec3 endPos, vec3 worldDir, float globalCloudShadow, float fogTimeFactor, out float transmittance){";
    private static final String SOURCE = "uniform float wetness;\nuniform ivec2 eyeBrightnessSmooth;\n" + HEADER
            + "\nfogTimeFactor *= timeMidnight * 2.0 + 1.0;\nfloat baseDensity=1.0;"
            + "\nfloat density = (densityNoise * VFOG_DENSITY + baseDensity) * fogTimeFactor;"
            + "\nfloat lightingDensity = (lightingNoise * VFOG_SUNLIGHT_ABSORPTION * VFOG_DENSITY + baseDensity) * fogTimeFactor;\n}\n";

    @Test void strengthensOnlyVolumetricAirWhilePreservingEveryOriginalSourceByte() {
        for (String stage : new String[]{"composite5", "composite2"}) {
            var result=IterationFogSourceTransform.transform(stage,SOURCE);
            assertTrue(result.changed(),result.reason());
            assertTrue(result.source().contains("mix(1.0, 3.0, clamp(wetness, 0.0, 1.0))"));
            String inserted=result.source().substring(SOURCE.indexOf('{')+1,result.source().indexOf("\nfogTimeFactor *= timeMidnight"));
            assertEquals(SOURCE,result.source().replace(inserted,""));
            assertFalse(IterationFogSourceTransform.transform(stage,result.source()).changed());
        }
    }
    @Test void unknownStagesAndChangedSignaturesAreUntouched() {
        assertUnchanged("gbuffers_terrain",SOURCE);
        assertUnchanged("composite5",SOURCE.replace("out float transmittance","out vec3 transmittance"));
        assertUnchanged("composite5",SOURCE+SOURCE);
    }
    @Test void requiresStockIndoorAndTimeOptionsRatherThanOverridingPhysicalUniforms() {
        for(String gate : new String[]{"baseDensity *= eyeBrightnessSmoothCurved;", "fogColor *= eyeBrightnessSmoothCurved;", "transmittance = mix(1.0, transmittance, eyeBrightnessSmoothCurved);", "fogTimeFactor *= mix(1.0 - timeNoon, 1.0, wetness);"})
            assertUnchanged("composite5",SOURCE+gate);
    }
    @Test void allowsPreprocessorWhitespaceButRejectsMissingDensityPath() {
        assertTrue(IterationFogSourceTransform.transform("composite5",SOURCE.replace(", ",",\n ").replace("){", ") {\n")).changed());
        assertUnchanged("composite5",SOURCE.replace("float lightingDensity", "float changedLightingDensity"));
    }
    private static void assertUnchanged(String stage,String source) {
        var result=IterationFogSourceTransform.transform(stage,source);
        assertFalse(result.changed()); assertEquals(source,result.source());
    }
    public static void main(String[] args) {
        var test=new IterationFogSourceTransformTest();
        test.strengthensOnlyVolumetricAirWhilePreservingEveryOriginalSourceByte();
        test.unknownStagesAndChangedSignaturesAreUntouched();
        test.requiresStockIndoorAndTimeOptionsRatherThanOverridingPhysicalUniforms();
        test.allowsPreprocessorWhitespaceButRejectsMissingDensityPath();
        System.out.println("4 IterationFogSourceTransform tests passed");
    }
}
