package com.latent.camera;

import java.io.File;
import java.io.FileOutputStream;

/** Host test: the probe's chunked name scan finds names anywhere, including across chunk edges. */
public class ProbeScanTest {
    public static void main(String[] a) throws Exception {
        File f = new File(a[0]);
        byte[] data = new byte[300000];
        for (int i = 0; i < data.length; i++) data[i] = (byte) ('a' + i % 23);
        put(data, 10, "_ZN6scalar18DeviceMemoryDiadem7getTypeEv");
        put(data, 65536 - 20, "_ZN6scalar18DeviceMemoryDiadem11writeBufferEPKvjj");   // straddles the first chunk edge
        put(data, 299950, "_ZN6scalar18DeviceMemoryDiadem10readBufferEPvjj");          // at the very end
        FileOutputStream o = new FileOutputStream(f);
        o.write(data);
        o.close();
        String[] names = { "_ZN6scalar18DeviceMemoryDiadem11writeBufferEPKvjj", "_ZN6scalar18DeviceMemoryDiadem10readBufferEPvjj",
                "_ZN6scalar18DeviceMemoryDiadem17getPropertyDiademEiPi", "_ZN6scalar18DeviceMemoryDiadem7getTypeEv" };
        int n = Probe.countNames(f, names);
        System.out.println(n == 3 ? "  pass  found 3 of 4 (edge and end included), missing one not invented" : "  FAIL  found " + n);
        System.out.println(Probe.countNames(new File(a[0] + ".missing"), names) == -1 ? "  pass  unreadable file reported" : "  FAIL  unreadable");
    }

    static void put(byte[] d, int at, String s) { for (int i = 0; i < s.length(); i++) d[at + i] = (byte) s.charAt(i); }
}
