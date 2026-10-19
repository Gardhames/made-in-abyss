package dev.madeinabyss.kernel.light;

/** Exact IterationT presentation adapter; no physical light or weather values change. */
public final class IterationFogSourceTransform {
    private static final String MARKER = "/* MIA_ITERATION_FOG_V1 */";
    private static final java.util.regex.Pattern HEADER = signaturePattern();
    private IterationFogSourceTransform() {}
    public record Result(String source, boolean changed, String reason) {}
    public static Result transform(String stage, String source) {
        if (!"composite5".equals(stage) && !"composite2".equals(stage)) return new Result(source,false,"other stage");
        if (source.contains(MARKER)) return new Result(source,false,"already adapted");
        String compact=source.replaceAll("\\s+", "");
        if (compact.contains("baseDensity*=eyeBrightnessSmoothCurved")
                || compact.contains("fogColor*=eyeBrightnessSmoothCurved")
                || compact.contains("transmittance=mix(1.0,transmittance,eyeBrightnessSmoothCurved)")
                || compact.contains("fogTimeFactor*=mix(1.0-timeNoon"))
            return new Result(source,false,"requires INDOOR_FOG and VFOG_IGNORE_WORLDTIME");
        var matcher=HEADER.matcher(source);
        if (!matcher.find()) return new Result(source,false,"fog signature mismatch");
        int end=matcher.end();
        if (matcher.find()) return new Result(source,false,"ambiguous fog signature");
        if (!java.util.regex.Pattern.compile("\\bfloat\\s+lightingDensity\\s*=").matcher(source).find()
                || !compact.contains("baseDensity") || !compact.contains("*fogTimeFactor"))
            return new Result(source,false,"fog density path mismatch");
        String insertion="\n"+MARKER+"\nfogTimeFactor *= mix(1.0, 3.0, clamp(wetness, 0.0, 1.0));\n";
        return new Result(source.substring(0,end)+insertion+source.substring(end),true,"rain air extinction x1..3");
    }
    private static java.util.regex.Pattern signaturePattern() {
        String signature="void VolumetricFog(inout vec3 color, vec3 startPos, vec3 endPos, vec3 worldDir, float globalCloudShadow, float fogTimeFactor, out float transmittance){";
        var tokens=java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*|[^\\s]").matcher(signature);
        var regex=new StringBuilder();
        while(tokens.find()) { if(!regex.isEmpty())regex.append("\\s*"); regex.append(java.util.regex.Pattern.quote(tokens.group())); }
        return java.util.regex.Pattern.compile(regex.toString());
    }
}
