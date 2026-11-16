package com.coach.desktop.books.mobi;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** KF8 INDX/TAGX skeleton and fragment records, limited to the book's first (HTML) flow.
 * Format reference: https://wiki.mobileread.com/wiki/MOBI . No external resources are read.
 */
final class Kf8Text {
    private static final int MAX_TEXT = 32 * 1024 * 1024;
    private static final int MAX_ENTRIES = 10000;
    private record Tag(int id, int width, int mask, int end) { }
    private record Entry(String label, Map<Integer, List<Integer>> tags) {
        int value(int tag, int position) throws IOException {
            List<Integer> values = tags.get(tag);
            if (values == null || position >= values.size()) throw invalid();
            return values.get(position);
        }
    }

    static List<byte[]> documents(PalmDatabase pdb, MobiHeader header, byte[] flow) throws IOException {
        long skeletonIndex = header.skeletonIndex(), fragmentIndex = header.fragmentIndex();
        if (absent(skeletonIndex) && absent(fragmentIndex)) return List.of(flow);
        List<Entry> skeletons = index(pdb, skeletonIndex);
        List<Entry> fragments = index(pdb, fragmentIndex);
        List<byte[]> result = new ArrayList<>();
        int next = 0, total = 0;
        for (int file = 0; file < skeletons.size(); file++) {
            Entry skeleton = skeletons.get(file);
            int count = skeleton.value(1, 0), start = skeleton.value(6, 0), length = skeleton.value(6, 1);
            bounds(start, length, flow.length);
            if (count > fragments.size() - next) throw invalid();
            ByteArrayOutputStream document = new ByteArrayOutputStream(length);
            int cursor = 0, inserted = 0;
            for (int i = 0; i < count; i++) {
                Entry fragment = fragments.get(next++);
                if (fragment.value(3, 0) != file) throw invalid();
                int insertion;
                try { insertion = Integer.parseInt(fragment.label) - start; }
                catch (NumberFormatException e) { throw invalid(); }
                int fragmentStart = fragment.value(6, 0), fragmentLength = fragment.value(6, 1);
                long physical = (long) start + length + fragmentStart;
                if (physical > Integer.MAX_VALUE) throw invalid();
                bounds((int) physical, fragmentLength, flow.length);
                int shellPosition = insertion - inserted;
                if (shellPosition < cursor || shellPosition > length || (long) length + inserted + fragmentLength + total > MAX_TEXT) throw invalid();
                document.write(flow, start + cursor, shellPosition - cursor);
                document.write(flow, (int) physical, fragmentLength);
                cursor = shellPosition;
                inserted += fragmentLength;
            }
            document.write(flow, start + cursor, length - cursor);
            total += document.size();
            if (total > MAX_TEXT) throw invalid();
            result.add(document.toByteArray());
        }
        if (next != fragments.size() || result.isEmpty()) throw invalid();
        return result;
    }

    private static boolean absent(long index) { return index == 0 || index == 0xffffffffL; }

    private static List<Entry> index(PalmDatabase pdb, long recordIndex) throws IOException {
        if (recordIndex < 1 || recordIndex >= pdb.recordCount()) throw invalid();
        byte[] primary = pdb.record((int) recordIndex);
        magic(primary, 0, "INDX");
        int tagx = uint(primary, 4), records = uint(primary, 24);
        if (records < 1 || records > MAX_ENTRIES || records >= pdb.recordCount() - recordIndex) throw invalid();
        magic(primary, tagx, "TAGX");
        int tagxLength = uint(primary, tagx + 4), controls = uint(primary, tagx + 8);
        bounds(tagx, tagxLength, primary.length);
        if (tagxLength < 12 || (tagxLength - 12) % 4 != 0 || controls < 1 || controls > 32) throw invalid();
        List<Tag> tags = new ArrayList<>();
        for (int p = tagx + 12; p < tagx + tagxLength; p += 4) {
            tags.add(new Tag(primary[p] & 255, primary[p + 1] & 255, primary[p + 2] & 255, primary[p + 3] & 255));
        }
        List<Entry> entries = new ArrayList<>();
        for (int record = 1; record <= records; record++) {
            byte[] data = pdb.record((int) recordIndex + record);
            magic(data, 0, "INDX");
            int idxt = uint(data, 20), count = uint(data, 24);
            if (count > MAX_ENTRIES - entries.size()) throw invalid();
            magic(data, idxt, "IDXT");
            bounds(idxt + 4, count * 2, data.length);
            for (int i = 0; i < count; i++) {
                int begin = EndianUtils.getUShortBE(data, idxt + 4 + 2 * i);
                int end = i + 1 == count ? idxt : EndianUtils.getUShortBE(data, idxt + 6 + 2 * i);
                if (begin < uint(data, 4) || begin >= end || end > idxt) throw invalid();
                entries.add(entry(data, begin, end, controls, tags));
            }
        }
        return entries;
    }

    private static Entry entry(byte[] data, int begin, int end, int controls, List<Tag> tags) throws IOException {
        int labelLength = data[begin++] & 255;
        if (labelLength + controls > end - begin) throw invalid();
        String label = new String(data, begin, labelLength, StandardCharsets.US_ASCII);
        int controlStart = begin + labelLength;
        Cursor cursor = new Cursor(data, controlStart + controls, end);
        int[] counts = new int[tags.size()];
        boolean[] byteCounts = new boolean[tags.size()];
        int control = 0;
        // Extra byte counts precede the tag values in the entry.
        for (int i = 0; i < tags.size(); i++) {
            Tag tag = tags.get(i);
            if (control >= controls) throw invalid();
            if (tag.mask != 0) {
                int selected = (data[controlStart + control] & 255) & tag.mask;
                byteCounts[i] = selected == tag.mask && Integer.bitCount(tag.mask) > 1;
                counts[i] = byteCounts[i] ? cursor.integer() : selected >>> Integer.numberOfTrailingZeros(tag.mask);
            }
            if (tag.end == 1) control++;
        }
        Map<Integer, List<Integer>> values = new HashMap<>();
        for (int i = 0; i < tags.size(); i++) {
            Tag tag = tags.get(i);
            List<Integer> list = new ArrayList<>();
            if (byteCounts[i]) {
                int limit = cursor.position + counts[i];
                if (limit > end) throw invalid();
                while (cursor.position < limit) list.add(cursor.integer());
                if (cursor.position != limit) throw invalid();
            } else {
                long count = (long) counts[i] * tag.width;
                if (count > end - cursor.position) throw invalid();
                for (int j = 0; j < count; j++) list.add(cursor.integer());
            }
            if (!list.isEmpty()) values.put(tag.id, list);
        }
        return new Entry(label, values);
    }

    private static final class Cursor {
        final byte[] bytes;
        final int end;
        int position;
        Cursor(byte[] bytes, int position, int end) { this.bytes = bytes; this.position = position; this.end = end; }
        int integer() throws IOException {
            long value = 0;
            for (int i = 0; i < 5 && position < end; i++) {
                int b = bytes[position++] & 255;
                value = (value << 7) | (b & 127);
                if (value > Integer.MAX_VALUE) throw invalid();
                if ((b & 128) != 0) return (int) value;
            }
            throw invalid();
        }
    }

    private static int uint(byte[] bytes, int offset) throws IOException {
        bounds(offset, 4, bytes.length);
        long value = EndianUtils.getUIntBE(bytes, offset);
        if (value > Integer.MAX_VALUE) throw invalid();
        return (int) value;
    }
    private static void magic(byte[] bytes, int offset, String expected) throws IOException {
        bounds(offset, 4, bytes.length);
        if (!PalmDatabase.hasMagic(bytes, offset, expected.getBytes(StandardCharsets.US_ASCII))) throw invalid();
    }
    private static void bounds(int start, int length, int limit) throws IOException {
        if (start < 0 || length < 0 || (long) start + length > limit) throw invalid();
    }
    private static IOException invalid() { return new IOException("Invalid Kindle chapter index"); }
}
