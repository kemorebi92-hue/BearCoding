package com.example.jingjingai2.security;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Kernel32Util;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.util.List;

/** Windows Crypt32 的 CurrentUser DPAPI。明文和密文都不写日志。 */
public final class WindowsDpapiProtector implements DpapiProtector {

    private static final int CRYPTPROTECT_UI_FORBIDDEN = 0x1;

    @Override
    public boolean supported() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    @Override
    public byte[] protect(byte[] plaintext) {
        requireWindows();
        if (plaintext == null || plaintext.length == 0) {
            throw new DpapiException(DpapiException.Kind.INVALID_INPUT, "DPAPI 输入不能为空");
        }
        return call(true, plaintext);
    }

    @Override
    public byte[] unprotect(byte[] protectedData) {
        requireWindows();
        if (protectedData == null || protectedData.length == 0) {
            throw new DpapiException(DpapiException.Kind.INVALID_INPUT, "DPAPI 输入不能为空");
        }
        return call(false, protectedData);
    }

    private void requireWindows() {
        if (!supported()) {
            throw new DpapiException(DpapiException.Kind.UNSUPPORTED, "DPAPI 只在 Windows 上可用");
        }
    }

    private static byte[] call(boolean protect, byte[] input) {
        Memory in = new Memory(input.length);
        in.write(0, input, 0, input.length);
        Crypt32.DATA_BLOB source = new Crypt32.DATA_BLOB();
        source.cbData = input.length;
        source.pbData = in;
        Crypt32.DATA_BLOB out = new Crypt32.DATA_BLOB();
        boolean ok = protect
                ? Crypt32.INSTANCE.CryptProtectData(source, null, null, null, null,
                        CRYPTPROTECT_UI_FORBIDDEN, out)
                : Crypt32.INSTANCE.CryptUnprotectData(source, null, null, null, null,
                        CRYPTPROTECT_UI_FORBIDDEN, out);
        if (!ok || out.pbData == null || out.cbData <= 0) {
            int code = Native.getLastError();
            throw new DpapiException(protect ? DpapiException.Kind.PROTECT_FAILED
                    : DpapiException.Kind.UNPROTECT_FAILED,
                    "DPAPI " + (protect ? "加密" : "解密") + "失败，系统错误 "
                            + code + "：" + Kernel32Util.formatMessage(code));
        }
        try {
            return out.pbData.getByteArray(0, out.cbData);
        } finally {
            Kernel32.INSTANCE.LocalFree(out.pbData);
        }
    }

    interface Crypt32 extends StdCallLibrary {
        Crypt32 INSTANCE = Native.load("Crypt32", Crypt32.class, W32APIOptions.DEFAULT_OPTIONS);

        boolean CryptProtectData(DATA_BLOB dataIn, Pointer description, Pointer entropy,
                                 Pointer reserved, Pointer prompt, int flags, DATA_BLOB dataOut);

        boolean CryptUnprotectData(DATA_BLOB dataIn, Pointer description, Pointer entropy,
                                   Pointer reserved, Pointer prompt, int flags, DATA_BLOB dataOut);

        class DATA_BLOB extends Structure {
            public int cbData;
            public Pointer pbData;

            @Override
            protected List<String> getFieldOrder() {
                return List.of("cbData", "pbData");
            }
        }
    }
}
