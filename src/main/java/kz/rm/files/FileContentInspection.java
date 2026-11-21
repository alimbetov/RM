package kz.rm.files;

import java.io.IOException;
import java.util.Arrays;

/** Signature admission only, not a replacement for AV, CDR, or safe previews. */
final class FileContentInspection {
    private FileContentInspection() {}
    static void requireExpected(String expected,byte[] first,int count) throws IOException {
        byte[] sig=Arrays.copyOf(first,count);
        boolean valid=switch(expected) {
            case "image/png" -> starts(sig,new byte[]{(byte)137,80,78,71,13,10,26,10});
            case "image/jpeg" -> starts(sig,new byte[]{(byte)0xff,(byte)0xd8,(byte)0xff});
            case "image/webp" -> starts(sig,new byte[]{82,73,70,70})
                    && sig.length>=12 && sig[8]=='W' && sig[9]=='E'
                    && sig[10]=='B' && sig[11]=='P';
            case "application/pdf" -> starts(sig,new byte[]{37,80,68,70,45});
            default -> false;
        };
        if(!valid)throw new IOException("File signature does not match allowlisted MIME");
    }
    private static boolean starts(byte[] actual,byte[] prefix) {
        if(actual.length<prefix.length)return false;
        for(int i=0;i<prefix.length;i++)if(actual[i]!=prefix[i])return false;
        return true;
    }
}
