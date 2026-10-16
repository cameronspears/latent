package com.latent.camera;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

/** Host test of the roll model against a fake memory card. */
public class RollTest {
    public static void main(String[] a) throws Exception {
        File card = new File(a[0]);
        {   // a card from before the rename: its FILMLAB folder becomes LATENT, contents and all
            File old = new File(card, "FILMLAB/ROLL001");
            old.mkdirs();
            touch(new File(old, "ROLL.TXT"), 0);
            File home = Library.home(card);
            check(home.getName().equals("LATENT") && new File(home, "ROLL001/ROLL.TXT").exists() && !new File(card, "FILMLAB").exists(),
                    "an old FILMLAB folder is renamed to LATENT with everything in it");
            new File(home, "ROLL001/ROLL.TXT").delete();
            new File(home, "ROLL001").delete();
        }
        File dcim = new File(card, "DCIM/100MSDCF");
        dcim.mkdirs();
        new File(card, "LATENT/LUTS").mkdirs();
        touch(new File(card, "LATENT/LUTS/CUBANNEG.CUB"), 0);
        touch(new File(card, "LATENT/LUTS/PORTRA4W.CUB"), 0);
        touch(new File(card, "LATENT/LUTS/MYLOOK.CUB"), 0);
        long t = 1700000000000L;
        for (int i = 1; i <= 3; i++) touch(new File(dcim, "DSC0100" + i + ".JPG"), t + i * 10000);   // before the roll

        List<Stock> stocks = Stock.all(card);
        check(stocks.size() == 3 && stocks.get(0).id.equals("CUBANNEG") && stocks.get(1).id.equals("PORTRA4W")
                && stocks.get(2).name.equals("MYLOOK"), "stocks: built-ins first, then custom");

        List<File> photos = Library.photos(card);
        Roll r = Roll.create(card, "CUBANNEG", 12, photos.get(0).lastModified());
        check(r.dir.getName().equals("ROLL001") && new File(r.dir, "ROLL.TXT").exists(), "roll created as ROLL001");
        check(r.exposures(Library.photos(card)).isEmpty(), "photos before loading are not on the roll");

        for (int i = 4; i <= 18; i++) touch(new File(dcim, String.format("DSC0%04d.JPG", 1000 + i)), t + i * 10000);
        List<File> exp = r.exposures(Library.photos(card));
        check(exp.size() == 12 && exp.get(0).getName().equals("DSC01004.JPG") && exp.get(11).getName().equals("DSC01015.JPG"),
                "12 exposures, oldest first, capped at the roll length: " + exp.size());

        for (File f : exp) r.frames.add(f.getPath());
        r.state = Roll.DEVELOPING;
        r.save();
        Roll back = Roll.open(r.dir);
        check(back.frames.size() == 12 && back.state.equals(Roll.DEVELOPING) && back.length == 12 && back.stock.equals("CUBANNEG"),
                "ROLL.TXT round-trips");
        check(back.output(0).getName().equals("01_01004.JPG") && back.output(11).getName().equals("12_01015.JPG"), "8.3 output names");
        check(back.developedCount() == 0, "nothing developed yet");
        touch(back.output(0), 0);
        touch(back.output(1), 0);
        touch(new File(back.dir, "03_01006.TMP"), 0);
        check(back.developedCount() == 2 && back.developed().size() == 2, "developed frames counted from files");
        back.cleanTemp();
        check(!new File(back.dir, "03_01006.TMP").exists(), "half-written .TMP removed");

        Roll r2 = Roll.create(card, "PORTRA4W", 24, 0);
        List<Roll> all = Roll.list(card);
        check(all.size() == 2 && all.get(0) .number == 2 && r2.label().equals("Roll 002"), "rolls listed newest first");
        // the duplicate-frame bug: an empty roll replaced by a new one must not grab later photos
        List<File> now = Library.photos(card);
        Roll empty = Roll.create(card, "CUBANNEG", 12, now.get(0).lastModified());
        empty.close(now);
        check(!empty.exists(), "an empty roll that gets replaced is removed");
        Roll a1 = Roll.create(card, "CUBANNEG", 12, now.get(0).lastModified());
        touch(new File(dcim, "DSC01019.JPG"), t + 19 * 10000);
        touch(new File(dcim, "DSC01020.JPG"), t + 20 * 10000);
        now = Library.photos(card);
        a1.close(now);
        Roll b1 = Roll.create(card, "PORTRA4W", 12, now.get(0).lastModified());
        touch(new File(dcim, "DSC01021.JPG"), t + 21 * 10000);
        now = Library.photos(card);
        Roll a2 = Roll.open(a1.dir);
        check(a2.frames.size() == 2 && a2.exposures(now).size() == 2 && a2.until > 0, "replaced roll keeps exactly its 2 frames");
        check(b1.exposures(now).size() == 1 && b1.exposures(now).get(0).getName().equals("DSC01021.JPG"), "new roll gets only the new photo");

        // developing into the camera roll: frame 3 becomes a new photo there, its original a negative
        Roll d = Roll.open(back.dir);
        File print = new File(dcim, "DSC01022.JPG");
        touch(print, t + 22 * 10000);   // Sony's encoder names it, with a new file time
        d.setPrint(2, print);
        d.save();
        Roll d2 = Roll.open(d.dir);
        check(d2.prints.size() == 12 && d2.print(2).equals(print) && d2.print(0) == null && d2.print(11) == null, "prints round-trip");
        check(d2.developed(2).equals(print) && d2.developedCount() == 3, "a print counts as developed");
        List<File> dev = d2.developed();
        check(dev.size() == 3 && dev.get(0).getName().equals("01_01004.JPG") && dev.get(2).equals(print), "developed frames in frame order, prints included");
        check(d2.negative(2).getPath().endsWith("ROLL001/NEG/DSC01006.JPG"), "negative is filed under the roll: " + d2.negative(2));
        check(d2.source(2).getName().equals("DSC01006.JPG") && d2.source(2).getParentFile().equals(dcim), "source is the original while it is there");
        touch(d2.negative(2), 0);
        new File(d2.frames.get(2)).delete();
        check(d2.source(2).equals(d2.negative(2)), "source is the negative once the original has moved");
        check(d2.preview(print).getPath().endsWith("ROLL001/PRV/DSC01022.JPG") && d2.preview(d2.output(0)).getPath().endsWith("ROLL001/PRV/01_01004.JPG"),
                "previews live with the roll, never in DCIM");
        touch(new File(d2.dir, "NEG/DSC01007.TMP"), 0);
        d2.cleanTemp();
        check(!new File(d2.dir, "NEG/DSC01007.TMP").exists() && d2.negative(2).exists(), "half-copied negative removed, filed one kept");
        // a print must never be counted as an exposure of the roll that's loaded
        List<File> all2 = Library.photos(card);
        List<File> ex = Roll.exposable(all2, Roll.list(card));
        check(all2.contains(print) && !ex.contains(print) && ex.size() == all2.size() - 1, "prints are not exposures");
        check(b1.exposures(ex).size() == 1, "loaded roll still has only its own photo");
        // photo numbers, not file times: the camera reports times hours apart from one power-on to the next
        check(Roll.number(new File(dcim, "DSC04592.JPG")) == 1004592 && Roll.number(new File(card, "LATENT/ROLL001/01_01004.JPG")) == -1,
                "photo numbers from folder and file");
        List<File> before = Library.photos(card);
        Roll n1 = Roll.create(card, "PORTRA4W", 12, before.get(0).lastModified() + 10L * 3600 * 1000, Roll.highest(before));   // a clock 10 h off
        touch(new File(dcim, "DSC01023.JPG"), t + 23 * 10000);
        touch(new File(dcim, "DSC01024.JPG"), t - 99 * 10000);   // a time that looks older than everything
        List<File> nowAll = Roll.exposable(Library.photos(card), Roll.list(card));
        List<File> e1 = Roll.open(n1.dir).exposures(nowAll);
        check(e1.size() == 2 && e1.get(0).getName().equals("DSC01023.JPG") && e1.get(1).getName().equals("DSC01024.JPG"),
                "exposures by number, whatever the file times say: " + e1);
        n1 = Roll.open(n1.dir);
        n1.close(nowAll);
        touch(new File(dcim, "DSC01025.JPG"), t + 25 * 10000);
        check(Roll.open(n1.dir).untilNo == 1001024 && Roll.open(n1.dir).exposures(Roll.exposable(Library.photos(card), Roll.list(card))).size() == 2,
                "a replaced roll stops at its last number");
        // a roll loaded by the time-based version (ROLL008 on the camera) is moved onto numbers
        Roll old = Roll.create(card, "PORTRA4W", 12, t + 10L * 3600 * 1000 * 100);
        List<Roll> rolls = Roll.list(card);
        Roll.upgrade(rolls);
        Roll up = Roll.open(old.dir);
        check(up.afterNo == 1001024, "time-based loaded roll upgraded after the last numbered frame: " + up.afterNo);
        check(up.exposures(Roll.exposable(Library.photos(card), Roll.list(card))).size() == 1, "and finds the photo shot since");

        // a camera crash lost Latent's note of the loaded roll: it was loaded again, so two rolls are open (the
        // A5100's ROLL006 / ROLL007, both starting after the same photo), and two photos were shot
        List<File> beforeCrash = Library.photos(card);
        int top = Roll.highest(beforeCrash);
        for (Roll o : Roll.list(card)) if (o.isOpen() && Roll.LOADED.equals(o.state)) o.close(Roll.exposable(beforeCrash, Roll.list(card)));
        Roll r6 = Roll.create(card, "PORTRA4W", 12, 0, top);
        Roll r7 = Roll.create(card, "PORTRA4W", 12, 0, top);
        touch(new File(dcim, "DSC01030.JPG"), t + 30 * 10000);
        touch(new File(dcim, "DSC01031.JPG"), t + 31 * 10000);
        List<Roll> rs = Roll.list(card);
        check(r6.isOpen() && r7.isOpen() && Roll.inCamera(rs, "").number == r7.number, "with no note, the newest open roll is the one in the camera");
        check(Roll.inCamera(rs, r6.dir.getName()).number == r6.number, "with a note naming an open roll, that one");
        String tidied = Roll.tidy(rs, Roll.exposable(Library.photos(card), rs));
        check(tidied != null && !r6.exists() && r7.exists(), "the older, empty roll is removed: " + tidied);
        List<Roll> after = Roll.list(card);
        Roll cam = Roll.inCamera(after, "");
        List<File> shot = cam.exposures(Roll.exposable(Library.photos(card), after));
        check(cam.number == r7.number && shot.size() == 2 && shot.get(0).getName().equals("DSC01030.JPG"), "the two new photos are on the roll in the camera");
        check(Roll.tidy(after, Roll.exposable(Library.photos(card), after)) == null, "tidying again changes nothing");
        // an older open roll that does have photos is closed with exactly those, not removed
        Roll ra = Roll.create(card, "CUBANNEG", 12, 0, Roll.highest(Library.photos(card)));
        touch(new File(dcim, "DSC01032.JPG"), t + 32 * 10000);
        Roll rb = Roll.create(card, "CUBANNEG", 12, 0, Roll.highest(Library.photos(card)));
        touch(new File(dcim, "DSC01033.JPG"), t + 33 * 10000);
        List<Roll> rs2 = Roll.list(card);
        Roll.tidy(rs2, Roll.exposable(Library.photos(card), rs2));
        Roll ra2 = Roll.open(ra.dir), rb2 = Roll.open(rb.dir);
        check(ra2 != null && !ra2.isOpen() && ra2.frames.size() == 1 && ra2.frames.get(0).endsWith("DSC01032.JPG"), "an older roll keeps its own photo and is closed");
        check(rb2.isOpen() && rb2.exposures(Roll.exposable(Library.photos(card), Roll.list(card))).size() == 1, "the newer roll keeps only the photo after it");
        check(Roll.inCamera(Roll.list(card), "") .number == rb2.number, "and is the one in the camera");

        // old ROLL.TXT without prints still reads
        check(Roll.open(a1.dir).prints.isEmpty() && Roll.open(a1.dir).print(0) == null, "rolls without prints read fine");
        System.out.println("ROLL OK");
    }

    static void touch(File f, long time) throws Exception {
        f.getParentFile().mkdirs();
        FileOutputStream o = new FileOutputStream(f);
        o.write(1);
        o.close();
        if (time > 0) f.setLastModified(time);
    }

    static void check(boolean ok, String what) {
        System.out.println((ok ? "  pass  " : "  FAIL  ") + what);
        if (!ok) System.exit(1);
    }
}
