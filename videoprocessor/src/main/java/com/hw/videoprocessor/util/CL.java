package com.hw.videoprocessor.util;

import android.util.Log;

/**
 * Created by huangwei on 2017/5/9.
 */

public class CL {
    private static boolean sLogEnable = false;
    private static int sCount = 0;

    private CL() {
    }

    public static boolean isLogEnable() {
        return sLogEnable;
    }

    public static void setLogEnable(boolean enable) {
        if(++sCount > 1) {
            Log.e("L", "setLogEnable() could only be called once");
        } else {
            sLogEnable = enable;
        }

    }

    private static String createLog(CL.TagInfo tagInfo, String log, Object... args) {
        StringBuilder buffer = new StringBuilder();
        buffer.append("[");
        buffer.append(tagInfo.fileName);
        buffer.append(".");
        buffer.append(tagInfo.methodName);
        buffer.append("():");
        buffer.append(tagInfo.lineNumber);
        buffer.append("]");
        buffer.append(formatString(log, args));
        return buffer.toString();
    }

    private static String createLogWithoutFileName(CL.TagInfo tagInfo, String log, Object... args) {
        StringBuilder buffer = new StringBuilder();
        buffer.append("[");
        buffer.append(tagInfo.methodName);
        buffer.append("():");
        buffer.append(tagInfo.lineNumber);
        buffer.append("]");
        buffer.append(formatString(log, args));
        return buffer.toString();
    }

    private static CL.TagInfo getMethodNames(StackTraceElement[] sElements) {
        CL.TagInfo info = new CL.TagInfo();
        if(sElements.length > 1) {
            info.fileName = sElements[1].getFileName();
            if(info.fileName.endsWith(".java")) {
                info.fileName = info.fileName.substring(0, info.fileName.length() - 5);
            }

            info.methodName = sElements[1].getMethodName();
            info.lineNumber = sElements[1].getLineNumber();
        }

        return info;
    }

    private static String formatString(String message, Object... args) {
        return args.length == 0?message: String.format(message, args);
    }

    public static void v(String message, Object... args) {
        if(sLogEnable) {
            CL.TagInfo tagInfo = getMethodNames((new Throwable()).getStackTrace());
            String msg = createLogWithoutFileName(tagInfo, message, args);
            Log.v(tagInfo.fileName, msg);
        }

    }

    public static void v(Throwable ex) {
        if(sLogEnable) {
            CL.TagInfo tagInfo = getMethodNames(ex.getStackTrace());
            Log.v(tagInfo.fileName, "", ex);
        }

    }

    public static void vt(String tag, String message, Object... args) {
        if(sLogEnable) {
            CL.TagInfo tagInfo = getMethodNames((new Throwable()).getStackTrace());
            String msg = createLog(tagInfo, message, args);
            Log.v(tag, msg);
        }

    }

    public static void d(String message, Object... args) {
        if(sLogEnable) {
            CL.TagInfo tagInfo = getMethodNames((new Throwable()).getStackTrace());
            String msg = createLogWithoutFileName(tagInfo, message, args);
            Log.d(tagInfo.fileName, msg);
        }

    }

    public static void d(Throwable ex) {
        if(sLogEnable) {
            CL.TagInfo tagInfo = getMethodNames(ex.getStackTrace());
            Log.d(tagInfo.fileName, "", ex);
        }

    }

    public static void dt(String tag, String message, Object... args) {
        if(sLogEnable) {
            CL.TagInfo tagInfo = getMethodNames((new Throwable()).getStackTrace());
            String msg = createLog(tagInfo, message, args);
            Log.d(tag, msg);
        }

    }

    public static void i(String message, Object... args) {
        if(sLogEnable) {
            CL.TagInfo tagInfo = getMethodNames((new Throwable()).getStackTrace());
            String msg = createLogWithoutFileName(tagInfo, message, args);
            Log.i(tagInfo.fileName, msg);
        }

    }

    public static void i(Throwable ex) {
        if(sLogEnable) {
            CL.TagInfo tagInfo = getMethodNames(ex.getStackTrace());
            Log.i(tagInfo.fileName, "", ex);
        }

    }

    public static void it(String tag, String message, Object... args) {
        if(sLogEnable) {
            CL.TagInfo tagInfo = getMethodNames((new Throwable()).getStackTrace());
            String msg = createLog(tagInfo, message, args);
            Log.i(tag, msg);
        }

    }

    public static void w(String message, Object... args) {
        if(sLogEnable) {
            CL.TagInfo tagInfo = getMethodNames((new Throwable()).getStackTrace());
            String msg = createLogWithoutFileName(tagInfo, message, args);
            Log.w(tagInfo.fileName, msg);
        }

    }

    public static void w(Throwable ex) {
        if(sLogEnable) {
            CL.TagInfo tagInfo = getMethodNames(ex.getStackTrace());
            Log.v(tagInfo.fileName, "", ex);
        }

    }

    public static void wt(String tag, String message, Object... args) {
        if(sLogEnable) {
            CL.TagInfo tagInfo = getMethodNames((new Throwable()).getStackTrace());
            String msg = createLog(tagInfo, message, args);
            Log.w(tag, msg);
        }

    }

    public static void e(String message, Object... args) {
        if(sLogEnable) {
            CL.TagInfo tagInfo = getMethodNames((new Throwable()).getStackTrace());
            String msg = createLogWithoutFileName(tagInfo, message, args);
            Log.e(tagInfo.fileName, msg);
        }

    }

    public static void e(Throwable ex) {
        if(sLogEnable) {
            CL.TagInfo tagInfo = getMethodNames(ex.getStackTrace());
            Log.e(tagInfo.fileName, "", ex);
        }

    }

    public static void et(String tag, String message, Object... args) {
        if(sLogEnable) {
            CL.TagInfo tagInfo = getMethodNames((new Throwable()).getStackTrace());
            String msg = createLog(tagInfo, message, args);
            Log.e(tag, msg);
        }

    }

    public static void wtf(String message, Object... args) {
        if(sLogEnable) {
            CL.TagInfo tagInfo = getMethodNames((new Throwable()).getStackTrace());
            String msg = createLogWithoutFileName(tagInfo, message, args);
            Log.wtf(tagInfo.fileName, msg);
        }

    }

    public static void printStackTrace(String tag) {
        if(sLogEnable) {
            String stackTrace = Log.getStackTraceString(new Throwable());
            Log.d(tag, stackTrace);
        }

    }

    static class TagInfo {
        String fileName;
        String methodName;
        int lineNumber;

        TagInfo() {
        }
    }
}
