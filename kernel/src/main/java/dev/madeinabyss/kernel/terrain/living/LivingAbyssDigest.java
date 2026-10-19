package dev.madeinabyss.kernel.terrain.living;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;
public final class LivingAbyssDigest {
    private LivingAbyssDigest(){}
    public static String of(String prefix,String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((prefix+"\n"+value).getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
}
