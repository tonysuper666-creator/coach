package com.coach.desktop.books.mobi;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

/** The small Palm/MOBI decoder is isolated from page planning and never opens external files. */
public final class KindleText {
    private static final int MAX_TEXT = 32 * 1024 * 1024;

    public record Book(String title, List<String> documents) { }

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
        return new Book(title == null ? header.fullName() : title, text);
    }
}
