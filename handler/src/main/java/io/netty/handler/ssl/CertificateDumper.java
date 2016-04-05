package io.netty.handler.ssl;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * CertificateDumper: print relevant info for a certificate in pem format.
 * It performs base64 decoding, then uses JDK's CertificateFactory to parse the DER certificate format.
 */

public class CertificateDumper {
    // borrowed from io.netty.handler.codec.base64.Base64Dialet (its members cannot be directly accessed outside of the
    // package, we have to copy them).
    private static final int BASE64_WHITECHAR = -5;
    private static final int BASE64_EQUALSIGN = -1;
    private static final byte[] BASE64_DECODEBET = new byte[] {
        -9, -9, -9, -9, -9, -9,
                -9, -9, -9, // Decimal  0 -  8
                -5, -5, // Whitespace: Tab and Linefeed
                -9, -9, // Decimal 11 - 12
                -5, // Whitespace: Carriage Return
                -9, -9, -9, -9, -9, -9, -9, -9, -9, -9, -9, -9, -9, // Decimal 14 - 26
                -9, -9, -9, -9, -9, // Decimal 27 - 31
                -5, // Whitespace: Space
                -9, -9, -9, -9, -9, -9, -9, -9, -9, -9, // Decimal 33 - 42
                62, // Plus sign at decimal 43
                -9, -9, -9, // Decimal 44 - 46
                63, // Slash at decimal 47
                52, 53, 54, 55, 56, 57, 58, 59, 60, 61, // Numbers zero through nine
                -9, -9, -9, // Decimal 58 - 60
                -1, // Equals sign at decimal 61
                -9, -9, -9, // Decimal 62 - 64
                0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, // Letters 'A' through 'N'
                14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, // Letters 'O' through 'Z'
                -9, -9, -9, -9, -9, -9, // Decimal 91 - 96
                26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, // Letters 'a' through 'm'
                39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, // Letters 'n' through 'z'
                -9, -9, -9, -9, // Decimal 123 - 126
         /* -9,-9,-9,-9,-9,-9,-9,-9,-9,-9,-9,-9,-9,     // Decimal 127 - 139
            -9,-9,-9,-9,-9,-9,-9,-9,-9,-9,-9,-9,-9,     // Decimal 140 - 152
            -9,-9,-9,-9,-9,-9,-9,-9,-9,-9,-9,-9,-9,     // Decimal 153 - 165
            -9,-9,-9,-9,-9,-9,-9,-9,-9,-9,-9,-9,-9,     // Decimal 166 - 178
            -9,-9,-9,-9,-9,-9,-9,-9,-9,-9,-9,-9,-9,     // Decimal 179 - 191
            -9,-9,-9,-9,-9,-9,-9,-9,-9,-9,-9,-9,-9,     // Decimal 192 - 204
            -9,-9,-9,-9,-9,-9,-9,-9,-9,-9,-9,-9,-9,     // Decimal 205 - 217
            -9,-9,-9,-9,-9,-9,-9,-9,-9,-9,-9,-9,-9,     // Decimal 218 - 230
            -9,-9,-9,-9,-9,-9,-9,-9,-9,-9,-9,-9,-9,     // Decimal 231 - 243
            -9,-9,-9,-9,-9,-9,-9,-9,-9,-9,-9,-9         // Decimal 244 - 255 */
    };

    private static ArrayList<byte[]> readLinesFromInputStream(InputStream in) throws IOException {
        ArrayList<byte[]> al = new ArrayList<byte[]>();
        boolean lineStart = true;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        while (true) {
            int b = in.read();
            if (b < 0) {
                if (!lineStart) { // last line is not empty?
                    al.add(buffer.toByteArray());
                }
                break;
            }
            if (b == 0x0A) {
                if (!lineStart) {
                    al.add(buffer.toByteArray());
                    buffer = new ByteArrayOutputStream();
                    lineStart = true;
                }
            } else if (b != 0x0C) {
                buffer.write(b);
                lineStart = false;
            }
        }
        return al;
    }

    private static boolean isDelimiter(byte[] line) {
        // TODO This is too loose a restriction
        for (int i = 0; i < 1; i++) {
            if (line[i] != '-') {
                return false;
            }
        }
        return true;
    }

    private static class ByteArrayListIterator {
        private final ArrayList<byte[]> al;
        private int i;
        private int j;

        public ByteArrayListIterator(ArrayList<byte[]> al) {
            this.al = al;
            if (al.isEmpty()) {
                i = 0; // mark i == al.size()
            } else {
                i = -1; // initialize it
            }
        }

        public byte next() {
            return al.get(i)[j];
        }

        // try to advance to the next available element in the array;
        // when there are none left, i == al.size().
        public boolean hasNext() {
            if (i == al.size()) {
                return false;
            } else if (i == -1 || j >= al.get(i).length - 1) {
                i++;
                while (i < al.size() && al.get(i).length == 0) { // skip empty array(s)
                    i++;
                }
                if (i == al.size()) {
                    return false;
                }
                j = 0;
            } else {
                j++;
            }
            return true;
        }
    }

    private static void base64Decode4To3(byte[] octet4, ByteArrayOutputStream buffer) {
        byte[] b4 = new byte[4];
        for (int n = 0; n < 4; n++) {
            byte b = BASE64_DECODEBET[octet4[n]];
            if (b == BASE64_EQUALSIGN) {
                b = 0;
            }
            b4[n] = b;
        }
        int i1 = (((b4[0] & 0xFF) << 2) | ((b4[1] & 0xFF) >>> 4)) & 0xFF;
        int i2 = (((b4[1] & 0xFF) << 4) | ((b4[2] & 0xFF) >>> 2)) & 0xFF;
        int i3 = (((b4[2] & 0xFF) << 6) | ((b4[3] & 0xFF))) & 0xFF;
        if (octet4[2] != '=' && octet4[3] != '=') {
            buffer.write(i1);
            buffer.write(i2);
            buffer.write(i3);
        } else if (octet4[2] == '=') { // 2 equal signs, then one octet is encoded
            if (octet4[3] != '=' || i2 != 0 || i3 != 0) {
                String err = String.format("invalid base64 chars with two terminal '=': [%s] => " +
                        "[%s], [%x, %x, %x]", Arrays.toString(octet4), Arrays.toString(b4), i1, i2, i3);
                throw new IllegalArgumentException(err);
            }
            buffer.write(i1);
        } else { // 1 equal sign, two octet are encoded
            if (i3 != 0) {
                String err = String.format("invalid base64 char with one terminal '=': [%s] => " +
                        "[%s], [%x, %x, %x]", Arrays.toString(octet4), Arrays.toString(b4), i1, i2, i3);
                throw new IllegalArgumentException(err);
            }
            buffer.write(i1);
            buffer.write(i2);
        }
    }

    @SuppressWarnings("unchecked")
    private static byte[] base64Decode(ArrayList<byte[]> al) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ByteArrayListIterator iter = new ByteArrayListIterator(al);
        byte[] octet4 = new byte[4];
        int pos = 0;
        while (iter.hasNext()) {
            byte b = iter.next();
            // TODO What is this for?
            // Technically speaking, any byte > 0x7f is invalid as constrained by the decodebet array.
            // Therefore, this is no need for it. But the line in Netty dated from 2008. So, let it be ...
            // byte b2 = (byte) (b1 & 0x7F);
            byte c = BASE64_DECODEBET[b];
            // we should have stripped of new line chars
            if (c >= BASE64_WHITECHAR) {
                if (c >= BASE64_EQUALSIGN) {
                    octet4[pos++] = b;
                    if (pos == 4) {
                        base64Decode4To3(octet4, buffer);
                        if (c == BASE64_EQUALSIGN) { // shall ends precisely here
                            break;
                        }
                        pos = 0;
                    }
                }
            } else {
                throw new IllegalArgumentException("invalid base64 char");
            }
        }
        assert (!iter.hasNext() && pos == 0);
        return buffer.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.out.println("Parameter: pem-certificate-path(input) der-certificate-path(output)");
            System.exit(-1);
        }
        Path file = Paths.get(args[0]);
        InputStream in = Files.newInputStream(file);
        ArrayList<byte[]> al = readLinesFromInputStream(in);
        ArrayList<byte[]> data = new ArrayList<byte[]>();
        assert(isDelimiter(al.get(0))); // header
        for (int i = 1; i < al.size(); i++) {
            byte[] arr = al.get(i);
            if (isDelimiter(arr)) { // footer
                assert(i > 1);
            } else {
                data.add(arr);
            }
        }
        System.out.println("Data lines: " + data.size());
        byte[] der = base64Decode(data);
        System.out.println("Decoded bytes: " + der.length + " bytes:");
        ByteArrayInputStream bis = new ByteArrayInputStream(der);
        CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
        X509Certificate cert = (X509Certificate) certFactory.generateCertificate(bis);
        System.out.println(cert);
        FileOutputStream fos = new FileOutputStream(args[1]);
        fos.write(der);
        fos.close();
    }
}
