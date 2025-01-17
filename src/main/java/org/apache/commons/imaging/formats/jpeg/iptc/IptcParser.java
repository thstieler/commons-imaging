/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.commons.imaging.formats.jpeg.iptc;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.imaging.ImagingConstants;
import org.apache.commons.imaging.ImagingException;
import org.apache.commons.imaging.ImagingParameters;
import org.apache.commons.imaging.common.Allocator;
import org.apache.commons.imaging.common.BinaryFileParser;
import org.apache.commons.imaging.common.BinaryFunctions;
import org.apache.commons.imaging.common.BinaryOutputStream;
import org.apache.commons.imaging.common.ByteConversions;
import org.apache.commons.imaging.formats.jpeg.JpegConstants;
import org.apache.commons.imaging.formats.jpeg.JpegImagingParameters;
import org.apache.commons.imaging.internal.Debug;

public class IptcParser extends BinaryFileParser {

    private static final Logger LOGGER = Logger.getLogger(IptcParser.class.getName());

    private static final ByteOrder APP13_BYTE_ORDER = ByteOrder.BIG_ENDIAN;

    /**
     * Block types (or Image Resource IDs) that are not recommended to be interpreted when libraries process Photoshop IPTC metadata.
     *
     * @see <a href="https://www.adobe.com/devnet-apps/photoshop/fileformatashtml/">Adobe Photoshop File Formats Specification</a>
     * @see <a href="https://issues.apache.org/jira/browse/IMAGING-246">IMAGING-246</a>
     * @since 1.0-alpha2
     */
    private static final List<Integer> PHOTOSHOP_IGNORED_BLOCK_TYPE = Arrays.asList(1084, 1085, 1086, 1087);

    private static final Charset DEFAULT_CHARSET = StandardCharsets.ISO_8859_1;
    private static final int ENV_TAG_CODED_CHARACTER_SET = 90;
    private static final byte[] CHARACTER_ESCAPE_SEQUENCE_ISO_8859_1 = { '\u001B', '.', 'A' };
    private static final byte[] CHARACTER_ESCAPE_SEQUENCE_US_ASCII = { '\u001B', '(', 'B' };
    private static final byte[] CHARACTER_ESCAPE_SEQUENCE_UTF8 = { '\u001B', '%', 'G' };

    public IptcParser() {
        super(ByteOrder.BIG_ENDIAN);
    }

    private Charset findCharset(final byte[] codedCharset) {
        final String codedCharsetString = new String(codedCharset, StandardCharsets.ISO_8859_1);
        try {
            if (Charset.isSupported(codedCharsetString)) {
                return Charset.forName(codedCharsetString);
            }
        } catch (final IllegalArgumentException ignored) {
            // ignored
        }
        // check if encoding is a escape sequence
        // normalize encoding byte sequence
        final byte[] codedCharsetNormalized = Allocator.byteArray(codedCharset.length);
        int j = 0;
        for (final byte element : codedCharset) {
            if (element != ' ') {
                codedCharsetNormalized[j++] = element;
            }
        }

        if (Objects.deepEquals(codedCharsetNormalized, CHARACTER_ESCAPE_SEQUENCE_UTF8)) {
            return StandardCharsets.UTF_8;
        }
        if (Objects.deepEquals(codedCharsetNormalized, CHARACTER_ESCAPE_SEQUENCE_ISO_8859_1)) {
            return StandardCharsets.ISO_8859_1;
        }
        if (Objects.deepEquals(codedCharsetNormalized, CHARACTER_ESCAPE_SEQUENCE_US_ASCII)) {
            return StandardCharsets.US_ASCII;
        }
        return DEFAULT_CHARSET;
    }

    public boolean isPhotoshopJpegSegment(final byte[] segmentData) {
        if (!BinaryFunctions.startsWith(segmentData, JpegConstants.PHOTOSHOP_IDENTIFICATION_STRING)) {
            return false;
        }

        final int index = JpegConstants.PHOTOSHOP_IDENTIFICATION_STRING.size();
        return index + 4 <= segmentData.length && ByteConversions.toInt(segmentData, index, APP13_BYTE_ORDER) == JpegConstants.CONST_8BIM;
    }

    protected List<IptcBlock> parseAllBlocks(final byte[] bytes, final boolean strict) throws ImagingException, IOException {
        final List<IptcBlock> blocks = new ArrayList<>();

        try (InputStream bis = new ByteArrayInputStream(bytes)) {

            // Note that these are unsigned quantities. Name is always an even
            // number of bytes (including the 1st byte, which is the size.)

            final byte[] idString = BinaryFunctions.readBytes("", bis, JpegConstants.PHOTOSHOP_IDENTIFICATION_STRING.size(),
                    "App13 Segment missing identification string");
            if (!JpegConstants.PHOTOSHOP_IDENTIFICATION_STRING.equals(idString)) {
                throw new ImagingException("Not a Photoshop App13 Segment");
            }

            // int index = PHOTOSHOP_IDENTIFICATION_STRING.length;

            while (true) {
                final int imageResourceBlockSignature;
                try {
                    imageResourceBlockSignature = BinaryFunctions.read4Bytes("", bis, "Image Resource Block missing identification string", APP13_BYTE_ORDER);
                } catch (final IOException ioEx) {
                    break;
                }
                if (imageResourceBlockSignature != JpegConstants.CONST_8BIM) {
                    throw new ImagingException("Invalid Image Resource Block Signature");
                }

                final int blockType = BinaryFunctions.read2Bytes("", bis, "Image Resource Block missing type", APP13_BYTE_ORDER);
                Debug.debug("blockType: " + blockType + " (0x" + Integer.toHexString(blockType) + ")");

                // skip blocks that the photoshop spec recommends to, see IMAGING-246
                if (PHOTOSHOP_IGNORED_BLOCK_TYPE.contains(blockType)) {
                    Debug.debug("Skipping blockType: " + blockType + " (0x" + Integer.toHexString(blockType) + ")");
                    // if there is still data in this block, before the next image resource block
                    // (8BIM), then we must consume these bytes to leave a pointer ready to read
                    // the next block
                    BinaryFunctions.searchQuad(JpegConstants.CONST_8BIM, bis);
                    continue;
                }

                final int blockNameLength = BinaryFunctions.readByte("Name length", bis, "Image Resource Block missing name length");
                if (blockNameLength > 0) {
                    Debug.debug("blockNameLength: " + blockNameLength + " (0x" + Integer.toHexString(blockNameLength) + ")");
                }
                byte[] blockNameBytes;
                if (blockNameLength == 0) {
                    BinaryFunctions.readByte("Block name bytes", bis, "Image Resource Block has invalid name");
                    blockNameBytes = ImagingConstants.EMPTY_BYTE_ARRAY;
                } else {
                    try {
                        blockNameBytes = BinaryFunctions.readBytes("", bis, blockNameLength, "Invalid Image Resource Block name");
                    } catch (final IOException ioEx) {
                        if (strict) {
                            throw ioEx;
                        }
                        break;
                    }

                    if (blockNameLength % 2 == 0) {
                        BinaryFunctions.readByte("Padding byte", bis, "Image Resource Block missing padding byte");
                    }
                }

                final int blockSize = BinaryFunctions.read4Bytes("", bis, "Image Resource Block missing size", APP13_BYTE_ORDER);
                Debug.debug("blockSize: " + blockSize + " (0x" + Integer.toHexString(blockSize) + ")");

                /*
                 * doesn't catch cases where blocksize is invalid but is still less than bytes.length but will at least prevent OutOfMemory errors
                 */
                if (blockSize > bytes.length) {
                    throw new ImagingException("Invalid Block Size : " + blockSize + " > " + bytes.length);
                }

                final byte[] blockData;
                try {
                    blockData = BinaryFunctions.readBytes("", bis, blockSize, "Invalid Image Resource Block data");
                } catch (final IOException ioEx) {
                    if (strict) {
                        throw ioEx;
                    }
                    break;
                }

                blocks.add(new IptcBlock(blockType, blockNameBytes, blockData));

                if (blockSize % 2 != 0) {
                    BinaryFunctions.readByte("Padding byte", bis, "Image Resource Block missing padding byte");
                }
            }

            return blocks;
        }
    }

    protected List<IptcRecord> parseIptcBlock(final byte[] bytes) {
        Charset charset = DEFAULT_CHARSET;
        final List<IptcRecord> elements = new ArrayList<>();

        int index = 0;
        // Integer recordVersion = null;
        while (index + 1 < bytes.length) {
            final int tagMarker = 0xff & bytes[index++];
            Debug.debug("tagMarker: " + tagMarker + " (0x" + Integer.toHexString(tagMarker) + ")");

            if (tagMarker != IptcConstants.IPTC_RECORD_TAG_MARKER) {
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine("Unexpected record tag marker in IPTC data.");
                }
                return elements;
            }

            final int recordNumber = 0xff & bytes[index++];
            Debug.debug("recordNumber: " + recordNumber + " (0x" + Integer.toHexString(recordNumber) + ")");

            // int recordPrefix = convertByteArrayToShort("recordPrefix", index,
            // bytes);
            // if (verbose)
            // Debug.debug("recordPrefix", recordPrefix + " (0x"
            // + Integer.toHexString(recordPrefix) + ")");
            // index += 2;
            //
            // if (recordPrefix != IPTC_RECORD_PREFIX)
            // {
            // if (verbose)
            // System.out
            // .println("Unexpected record prefix in IPTC data!");
            // return elements;
            // }

            // throw new ImageReadException(
            // "Unexpected record prefix in IPTC data.");

            final int recordType = 0xff & bytes[index];
            Debug.debug("recordType: " + recordType + " (0x" + Integer.toHexString(recordType) + ")");
            index++;

            final int recordSize = ByteConversions.toUInt16(bytes, index, getByteOrder());
            index += 2;

            final boolean extendedDataset = recordSize > IptcConstants.IPTC_NON_EXTENDED_RECORD_MAXIMUM_SIZE;
            final int dataFieldCountLength = recordSize & 0x7fff;
            if (extendedDataset) {
                Debug.debug("extendedDataset. dataFieldCountLength: " + dataFieldCountLength);
            }
            if (extendedDataset) {
                // ignore extended dataset and everything after.
                return elements;
            }

            final byte[] recordData = BinaryFunctions.slice(bytes, index, recordSize);
            index += recordSize;

            // Debug.debug("recordSize", recordSize + " (0x"
            // + Integer.toHexString(recordSize) + ")");

            if (recordNumber == IptcConstants.IPTC_ENVELOPE_RECORD_NUMBER && recordType == ENV_TAG_CODED_CHARACTER_SET) {
                charset = findCharset(recordData);
                continue;
            }

            if (recordNumber != IptcConstants.IPTC_APPLICATION_2_RECORD_NUMBER) {
                continue;
            }

            if (recordType == 0) {
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine("ignore record version record! " + elements.size());
                }
                // ignore "record version" record;
                continue;
            }
            // if (recordVersion == null)
            // {
            // // The first record in a JPEG/Photoshop IPTC block must be
            // // the record version.
            // if (recordType != 0)
            // throw new ImageReadException("Missing record version: "
            // + recordType);
            // recordVersion = new Integer(convertByteArrayToShort(
            // "recordNumber", recordData));
            //
            // if (recordSize != 2)
            // throw new ImageReadException(
            // "Invalid record version record size: " + recordSize);
            //
            // // JPEG/Photoshop IPTC metadata is always in Record version
            // // 2
            // if (recordVersion.intValue() != 2)
            // throw new ImageReadException(
            // "Invalid IPTC record version: " + recordVersion);
            //
            // // Debug.debug("recordVersion", recordVersion);
            // continue;
            // }

            final String value = new String(recordData, charset);

            final IptcType iptcType = IptcTypeLookup.getIptcType(recordType);

            // Debug.debug("iptcType", iptcType);
            // debugByteArray("iptcData", iptcData);
            // Debug.debug();

            // if (recordType == IPTC_TYPE_CREDIT.type
            // || recordType == IPTC_TYPE_OBJECT_NAME.type)
            // {
            // this.debugByteArray("recordData", recordData);
            // Debug.debug("index", IPTC_TYPE_CREDIT.name);
            // }

            final IptcRecord element = new IptcRecord(iptcType, value);
            elements.add(element);
        }

        return elements;
    }

    public PhotoshopApp13Data parsePhotoshopSegment(final byte[] bytes, final boolean strict) throws ImagingException, IOException {
        final List<IptcRecord> records = new ArrayList<>();

        final List<IptcBlock> blocks = parseAllBlocks(bytes, strict);

        for (final IptcBlock block : blocks) {
            // Ignore everything but IPTC data.
            if (!block.isIptcBlock()) {
                continue;
            }

            records.addAll(parseIptcBlock(block.getBlockData()));
        }

        return new PhotoshopApp13Data(records, blocks);
    }

    // private void writeIPTCRecord(BinaryOutputStream bos, )

    /*
     * In practice, App13 segments are only used for Photoshop/IPTC metadata. However, we should not treat App13 signatures without Photoshop's signature as
     * Photoshop/IPTC segments.
     *
     * A Photoshop/IPTC App13 segment begins with the Photoshop Identification string.
     *
     * There follows 0-N blocks (Photoshop calls them "Image Resource Blocks").
     *
     * Each block has the following structure:
     *
     * 1. 4-byte type. This is always "8BIM" for blocks in a Photoshop App13 segment. 2. 2-byte id. IPTC data is stored in blocks with id 0x0404, aka.
     * IPTC_NAA_RECORD_IMAGE_RESOURCE_ID 3. Block name as a Pascal String. This is padded to have an even length. 4. 4-byte size (in bytes). 5. Block data. This
     * is also padded to have an even length.
     *
     * The block data consists of a 0-N records. A record has the following structure:
     *
     * 1. 2-byte prefix. The value is always 0x1C02 2. 1-byte record type. The record types are documented by the IPTC. See IptcConstants. 3. 2-byte record size
     * (in bytes). 4. Record data, "record size" bytes long.
     *
     * Record data (unlike block data) is NOT padded to have an even length.
     *
     * Record data, for IPTC record, should always be ISO-8859-1. But according to SANSELAN-33, this isn't always the case.
     *
     * The exception is the first record in the block, which must always be a record version record, whose value is a two-byte number; the value is 0x02.
     *
     * Some IPTC blocks are missing this first "record version" record, so we don't require it.
     */
    public PhotoshopApp13Data parsePhotoshopSegment(final byte[] bytes, final ImagingParameters<JpegImagingParameters> params)
            throws ImagingException, IOException {
        final boolean strict = params != null && params.isStrict();

        return parsePhotoshopSegment(bytes, strict);
    }

    public byte[] writeIptcBlock(List<IptcRecord> elements) throws ImagingException, IOException {
        boolean useDefaultCharset = charsetIsSuitable(elements, DEFAULT_CHARSET);
        if (useDefaultCharset) {
            return writeIptcBlock(elements, null);
        } else {
            return writeIptcBlock(elements, StandardCharsets.UTF_8);
        }
    }

    private byte[] getEscapeSequence(Charset charset) throws ImagingException {
        if (StandardCharsets.UTF_8.equals(charset)) {
            return CHARACTER_ESCAPE_SEQUENCE_UTF8;
        }
        if (StandardCharsets.ISO_8859_1.equals(charset)) {
            return CHARACTER_ESCAPE_SEQUENCE_ISO_8859_1;
        }
        if (StandardCharsets.US_ASCII.equals(charset)) {
            return CHARACTER_ESCAPE_SEQUENCE_US_ASCII;
        }
        throw new ImagingException(
                "Unsupported character encoding: " + charset + ", only UTF-8, ISO-8859-1 or US_ASCII are allowed!");
    }

    private boolean charsetIsSuitable(List<IptcRecord> elements, Charset charset) {
        boolean isSuitable = true;
        for (final IptcRecord element : elements) {
            final byte[] recordData = element.getValue().getBytes(charset);
            if (!new String(recordData, charset).equals(element.getValue())) {
                isSuitable = false;
                break;
            }
        }
        return isSuitable;
    }

    public byte[] writeIptcBlock(List<IptcRecord> elements, Charset forcedCharset)
            throws ImagingException, IOException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (BinaryOutputStream bos = BinaryOutputStream.create(baos, getByteOrder())) {
            if (forcedCharset != null) {
                bos.write(IptcConstants.IPTC_RECORD_TAG_MARKER);
                bos.write(IptcConstants.IPTC_ENVELOPE_RECORD_NUMBER);
                bos.write(ENV_TAG_CODED_CHARACTER_SET);
                final byte[] codedCharset = getEscapeSequence(forcedCharset);
                bos.write2Bytes(codedCharset.length);
                bos.write(codedCharset);
            }

            Charset effectiveCharset = DEFAULT_CHARSET;
            if (forcedCharset != null) {
                effectiveCharset = forcedCharset;
            }

            // first, right record version record
            bos.write(IptcConstants.IPTC_RECORD_TAG_MARKER);
            bos.write(IptcConstants.IPTC_APPLICATION_2_RECORD_NUMBER);
            bos.write(IptcTypes.RECORD_VERSION.type); // record version record
                                                      // type.
            bos.write2Bytes(2); // record version record size
            bos.write2Bytes(2); // record version value

            // make a copy of the list.
            elements = new ArrayList<>(elements);

            // sort the list. Records must be in numerical order.
            final Comparator<IptcRecord> comparator = (e1, e2) -> e2.iptcType.getType() - e1.iptcType.getType();
            elements.sort(comparator);
            // TODO: make sure order right

            // write the list.
            for (final IptcRecord element : elements) {
                if (element.iptcType == IptcTypes.RECORD_VERSION) {
                    continue; // ignore
                }

                bos.write(IptcConstants.IPTC_RECORD_TAG_MARKER);
                bos.write(IptcConstants.IPTC_APPLICATION_2_RECORD_NUMBER);
                if (element.iptcType.getType() < 0 || element.iptcType.getType() > 0xff) {
                    throw new ImagingException("Invalid record type: " + element.iptcType.getType());
                }
                bos.write(element.iptcType.getType());

                final byte[] recordData = element.getValue().getBytes(effectiveCharset);
                /*
                 * if (!new String(recordData, charset).equals(element.getValue())) { throw new ImageWriteException( "Invalid record value, not " +
                 * charset.name()); }
                 */

                bos.write2Bytes(recordData.length);
                bos.write(recordData);
            }
        }

        return baos.toByteArray();
    }

    public byte[] writePhotoshopApp13Segment(final PhotoshopApp13Data data) throws IOException, ImagingException {
        try (ByteArrayOutputStream os = new ByteArrayOutputStream();
                BinaryOutputStream bos = BinaryOutputStream.bigEndian(os)) {

            JpegConstants.PHOTOSHOP_IDENTIFICATION_STRING.writeTo(bos);

            final List<IptcBlock> blocks = data.getRawBlocks();
            for (final IptcBlock block : blocks) {
                bos.write4Bytes(JpegConstants.CONST_8BIM);

                if (block.getBlockType() < 0 || block.getBlockType() > 0xffff) {
                    throw new ImagingException("Invalid IPTC block type.");
                }
                bos.write2Bytes(block.getBlockType());

                final byte[] blockNameBytes = block.getBlockNameBytes();
                if (blockNameBytes.length > 255) {
                    throw new ImagingException("IPTC block name is too long: " + blockNameBytes.length);
                }
                bos.write(blockNameBytes.length);
                bos.write(blockNameBytes);
                if (blockNameBytes.length % 2 == 0) {
                    bos.write(0); // pad to even size, including length byte.
                }

                final byte[] blockData = block.getBlockData();
                if (blockData.length > IptcConstants.IPTC_NON_EXTENDED_RECORD_MAXIMUM_SIZE) {
                    throw new ImagingException("IPTC block data is too long: " + blockData.length);
                }
                bos.write4Bytes(blockData.length);
                bos.write(blockData);
                if (blockData.length % 2 == 1) {
                    bos.write(0); // pad to even size
                }
            }

            bos.flush();
            return os.toByteArray();
        }
    }

}
