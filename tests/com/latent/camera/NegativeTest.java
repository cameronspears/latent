package com.latent.camera;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.util.Arrays;

/** Host test of filing negatives (copy + check) and of the orientation arithmetic. */
public class NegativeTest {
    public static void main(String[] a) throws Exception {
        File photo = new File(a[0]), dir = new File(a[1]);
        File original = new File(dir, "DCIM/100MSDCF/DSC04574.JPG"), neg = new File(dir, "LATENT/ROLL001/NEG/DSC04574.JPG");
        original.getParentFile().mkdirs();
        copy(photo, original);
        long crc = HardwareDevelop.copyNegative(original, neg);
        check(neg.exists() && Arrays.equals(read(neg), read(original)) && crc == HardwareDevelop.crc(original), "negative is a byte-exact copy");
        check(!new File(neg.getParentFile(), "DSC04574.TMP").exists(), "no temporary file left");
        long when = neg.lastModified();
        check(HardwareDevelop.copyNegative(original, neg) == crc && neg.lastModified() == when, "an existing good negative is kept as is");
        RandomAccessFile f = new RandomAccessFile(neg, "rw");
        f.seek(neg.length() / 2);
        int b = f.read();
        f.seek(neg.length() / 2);
        f.write(b ^ 0x10);
        f.close();
        check(HardwareDevelop.crc(neg) != crc, "a damaged negative is noticed");
        HardwareDevelop.copyNegative(original, neg);
        check(Arrays.equals(read(neg), read(original)), "a damaged negative is copied again");
        check(original.exists() && Arrays.equals(read(original), read(photo)), "the original is never changed");
        // Sony's relative angles: +90 = 0, +180 = 1, -90 = 3; -1 nothing to do, -2 unsupported
        check(HardwareDevelop.relativeAngle(1, 1) == -1 && HardwareDevelop.relativeAngle(1, 6) == 0 && HardwareDevelop.relativeAngle(1, 3) == 1
                && HardwareDevelop.relativeAngle(1, 8) == 3 && HardwareDevelop.relativeAngle(6, 1) == 3 && HardwareDevelop.relativeAngle(8, 6) == 1
                && HardwareDevelop.relativeAngle(1, 2) == -2 && HardwareDevelop.relativeAngle(5, 1) == -2, "orientation arithmetic");
        System.out.println("NEGATIVE OK");
    }

    static byte[] read(File f) throws Exception {
        byte[] b = new byte[(int) f.length()];
        FileInputStream in = new FileInputStream(f);
        try { int o = 0; for (int n; o < b.length && (n = in.read(b, o, b.length - o)) > 0; ) o += n; } finally { in.close(); }
        return b;
    }

    static void copy(File from, File to) throws Exception {
        FileOutputStream o = new FileOutputStream(to);
        try { o.write(read(from)); } finally { o.close(); }
    }

    static void check(boolean ok, String what) {
        System.out.println((ok ? "  pass  " : "  FAIL  ") + what);
        if (!ok) System.exit(1);
    }
}
