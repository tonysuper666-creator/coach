package com.coach.desktop.books.mobi;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import org.jsoup.Jsoup;

/** The small Palm/MOBI decoder is isolated from page planning and never opens external files. */
public final class KindleText {
    private static final int MAX_TEXT = 32 * 1024 * 1024;

    public record Book(String title, List<String> documents, Map<Integer, byte[]> images) { }

    /** Inspect the same first MOBI header the decoder uses without loading the whole book. */
    public static boolean isKf8(Path path) throws IOException {
        return MobiHeader.isKf8(path);
    }

    public static Book read(byte[] bytes) throws IOException {
        PalmDatabase pdb = PalmDatabase.read(bytes);
        if (!"BOOKMOBI".equals(pdb.typeAndCreator()) || pdb.declaredRecordCount() != pdb.recordCount()) {
            throw new IOException("Incomplete Kindle record table");
        }
        MobiHeader header = MobiHeader.read(pdb);
        if (header.encryption() != MobiHeader.Encryption.NONE) {
            throw new IllegalArgumentException("这本 Kindle 书有版权保护，打不开。请使用无 DRM、且有权阅读的版本。");
        }
        if (header.textRecordCount() < 1 || header.textRecordCount() >= pdb.recordCount()
                || header.textLength() <= 0 || header.textLength() > MAX_TEXT
                || header.textLength() > (long) bytes.length * 32) {
            throw new IllegalArgumentException("这本 Kindle 书的正文不完整或解压后超过 32 MB，请重新保存或分册导入。");
        }
        long bookLength = header.textLength();
        if (header.isKf8() && header.fdstIndex() > 0 && header.fdstIndex() < pdb.recordCount()) {
            byte[] fdst = pdb.record((int) header.fdstIndex());
            if (fdst.length < 20 || !PalmDatabase.hasMagic(fdst, 0, new byte[]{'F', 'D', 'S', 'T'})) {
                throw new IOException("Invalid Kindle flow table");
            }
            long end = EndianUtils.getUIntBE(fdst, 16);
            if (end <= 0 || end > bookLength) throw new IOException("Invalid Kindle text flow");
            bookLength = end;
        }
        HuffCdic huff = header.compression() == MobiHeader.Compression.HUFF_CDIC
                ? HuffCdic.read(pdb, header.huffRecordIndex(), header.huffRecordCount()) : null;
        ByteArrayOutputStream html = new ByteArrayOutputStream();
        for (int i = 1; i <= header.textRecordCount() && html.size() < bookLength; i++) {
            PalmDatabase.Slice record = pdb.slice(i);
            record = record.trimmed(PalmDoc.trailingEntriesLength(record, header.trailingEntryFlags()));
            int maximum = (int) Math.min(bookLength - html.size(), header.textRecordSize());
            byte[] text = switch (header.compression()) {
                case NONE -> Arrays.copyOfRange(record.data(), record.offset(), record.offset() + Math.min(maximum, record.length()));
                case PALMDOC -> PalmDoc.decompress(record, maximum);
                case HUFF_CDIC -> huff.decode(record, maximum);
                default -> throw new IllegalArgumentException("这本 Kindle 书的压缩方式暂时读不了，请导出为 EPUB 后导入。");
            };
            html.write(text);
        }
        if (html.size() != bookLength) throw new IOException("Incomplete Kindle text");
        List<byte[]> documents = header.isKf8() ? Kf8Text.documents(pdb, header, html.toByteArray()) : List.of(html.toByteArray());
        List<String> text = new ArrayList<>();
        for (byte[] document : documents) text.add(header.charset().newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(document)).toString());
        String title = header.exthString(MobiHeader.EXTH_UPDATED_TITLE);
        Map<Integer, byte[]> images = new HashMap<>();
        long total = 0;
        for (String document : text) {
            for (var image : Jsoup.parse(document).getElementsByTag("img")) {
                String reference = image.attr("recindex");
                int radix = 10;
                if (reference.isBlank()) {
                    var match = java.util.regex.Pattern.compile("(?i)^kindle:embed:([0-9a-v]+)(?:[?].*)?$").matcher(image.attr("src"));
                    if (!match.matches()) continue;
                    reference = match.group(1);
                    radix = 32;
                }
                int index;
                try { index = Integer.parseInt(reference, radix); }
                catch (NumberFormatException e) { throw new IOException("Invalid Kindle image reference", e); }
                if (images.containsKey(index)) continue;
                long record = header.firstImageIndex() + index - 1L;
                if (index < 1 || header.firstImageIndex() <= header.textRecordCount() || record >= pdb.recordCount()
                        || record < 0 || images.size() >= 1000) throw new IOException("Incomplete Kindle image table");
                var slice = pdb.slice((int) record);
                total += slice.length();
                if (slice.length() > 16 * 1024 * 1024 || total > 64 * 1024 * 1024) {
                    throw new IllegalArgumentException("这本 Kindle 书插图超过大小限制，请分册导入。");
                }
                images.put(index, pdb.record((int) record));
            }
        }
        return new Book(title == null ? header.fullName() : title, text, Map.copyOf(images));
    }
}
