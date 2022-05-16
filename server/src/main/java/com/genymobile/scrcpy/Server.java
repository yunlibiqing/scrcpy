package com.genymobile.scrcpy;

import android.graphics.Rect;
import android.media.MediaCodecInfo;
import android.os.BatteryManager;
import android.os.Build;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.lang.System;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;

import android.os.Handler;

public final class Server {

    private static Handler handler;

    private Server() {
        // not instantiable
    }

    private static void initAndCleanUp(Options options) {
        boolean mustDisableShowTouchesOnCleanUp = false;
        int restoreStayOn = -1;
        boolean restoreNormalPowerMode = options.getControl(); // only restore power mode if control is enabled
        if (options.getShowTouches() || options.getStayAwake()) {
            Settings settings = Device.getSettings();
            if (options.getShowTouches()) {
                try {
                    String oldValue = settings.getAndPutValue(Settings.TABLE_SYSTEM, "show_touches", "1");
                    // If "show touches" was disabled, it must be disabled back on clean up
                    mustDisableShowTouchesOnCleanUp = !"1".equals(oldValue);
                } catch (SettingsException e) {
                    Ln.e("Could not change \"show_touches\"", e);
                }
            }

            if (options.getStayAwake()) {
                int stayOn = BatteryManager.BATTERY_PLUGGED_AC | BatteryManager.BATTERY_PLUGGED_USB | BatteryManager.BATTERY_PLUGGED_WIRELESS;
                try {
                    String oldValue = settings.getAndPutValue(Settings.TABLE_GLOBAL, "stay_on_while_plugged_in", String.valueOf(stayOn));
                    try {
                        restoreStayOn = Integer.parseInt(oldValue);
                        if (restoreStayOn == stayOn) {
                            // No need to restore
                            restoreStayOn = -1;
                        }
                    } catch (NumberFormatException e) {
                        restoreStayOn = 0;
                    }
                } catch (SettingsException e) {
                    Ln.e("Could not change \"stay_on_while_plugged_in\"", e);
                }
            }
        }

        if (options.getCleanup()) {
            try {
                CleanUp.configure(options.getDisplayId(), restoreStayOn, mustDisableShowTouchesOnCleanUp, restoreNormalPowerMode,
                        options.getPowerOffScreenOnClose());
            } catch (IOException e) {
                Ln.e("Could not configure cleanup", e);
            }
        }
    }

    private static void scrcpy(Options options) throws IOException {
        AccessibilityNodeInfoDumper dumper = null;
        Ln.i("Device: " + Build.MANUFACTURER + " " + Build.MODEL + " (Android " + Build.VERSION.RELEASE + ")");
        final Device device = new Device(options);
        List<CodecOption> codecOptions = options.getCodecOptions();

        Thread initThread = startInitThread(options);

        boolean tunnelForward = options.isTunnelForward();
        boolean control = options.getControl();
        boolean sendDummyByte = options.getSendDummyByte();

        try (DesktopConnection connection = DesktopConnection.open(tunnelForward, control, sendDummyByte)) {
            ScreenEncoder screenEncoder = new ScreenEncoder(options, device);
            handler = screenEncoder.getHandler();
//            if (options.getSendDeviceMeta()) {
//                Size videoSize = device.getScreenInfo().getVideoSize();
//                connection.sendDeviceMeta(Device.getDeviceName(), videoSize.getWidth(), videoSize.getHeight());
//            }
//            ScreenEncoder screenEncoder = new ScreenEncoder(options.getSendFrameMeta(), options.getBitRate(), options.getMaxFps(), codecOptions,
//                    options.getEncoderName(), options.getDownsizeOnError());
////            screenEncoder.getHandler();
//
//            Thread controllerThread = null;
//            Thread deviceMessageSenderThread = null;
            if (options.getDumpHierarchy()) {
                dumper = new AccessibilityNodeInfoDumper(handler, device, connection);
                dumper.start();
            }
            if (control) {
//                Controller controller = new Controller(device, connection);
                final Controller controller = new Controller(device, connection, options.getClipboardAutosync(), options.getPowerOn());

                // asynchronous
                startController(controller);
                startDeviceMessageSender(controller.getSender());

                device.setClipboardListener(new Device.ClipboardListener() {
                    @Override
                    public void onClipboardTextChanged(String text) {
                        controller.getSender().pushClipboardText(text);
                    }
                });
            }

            try {
                // synchronous
//                screenEncoder.streamScreen(device, connection.getVideoFd());
                screenEncoder.streamScreen(device, connection.getVideoChannel());
            } catch (IOException e) {
                // this is expected on close
                Ln.i("exit: " + e.getMessage());
                Ln.d("Screen streaming stopped");
            } finally {
                initThread.interrupt();
                if (options.getDumpHierarchy() && dumper != null) {
                    dumper.stop();
                }
                System.exit(0);
//                if (controllerThread != null) {
//                    controllerThread.interrupt();
//                }
//                if (deviceMessageSenderThread != null) {
//                    deviceMessageSenderThread.interrupt();
//                }
            }
        }
    }

    private static Thread startInitThread(final Options options) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                initAndCleanUp(options);
            }
        });
        thread.start();
        return thread;
    }

    private static Thread startController(final Controller controller) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    controller.control();
                } catch (IOException e) {
                    // this is expected on close
                    Ln.d("Controller stopped");
                    Common.stopScrcpy(handler, "control");
                }
            }
        });
        thread.start();
        return thread;
    }

    private static Thread startDeviceMessageSender(final DeviceMessageSender sender) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    sender.loop();
                } catch (IOException | InterruptedException e) {
                    // this is expected on close
                    Ln.d("Device message sender stopped");
                }
            }
        });
        thread.start();
        return thread;
    }

    private static Options createOptions(String... args) {
        if (args.length < 1) {
            throw new IllegalArgumentException("Missing client version");
        }

        String clientVersion = args[0];
        if (!clientVersion.equals(BuildConfig.VERSION_NAME)) {
            throw new IllegalArgumentException(
                    "The server version (" + BuildConfig.VERSION_NAME + ") does not match the client " + "(" + clientVersion + ")");
        }

        Options options = new Options();

        options.setMaxSize(0);
        options.setTunnelForward(true);
        options.setCrop(null);
        options.setControl(true);
        // global
        options.setMaxFps(24);
        options.setScale(480);
        // jpeg
        options.setQuality(60);
        options.setBitRate(1000000);
        options.setSendFrameMeta(true);
        // control
        options.setControlOnly(false);
        // dump
        options.setDumpHierarchy(false);

        for (int i = 1; i < args.length; ++i) {
            String arg = args[i];
            int equalIndex = arg.indexOf('=');
            if (equalIndex == -1) {
                throw new IllegalArgumentException("Invalid key=value pair: \"" + arg + "\"");
            }
            String key = arg.substring(0, equalIndex);
            String value = arg.substring(equalIndex + 1);
            switch (key) {
                case "log_level":
                    Ln.Level level = Ln.Level.valueOf(value.toUpperCase(Locale.ENGLISH));
                    options.setLogLevel(level);
                    break;
                case "max_size":
                    int maxSize = Integer.parseInt(value) & ~7; // multiple of 8
                    options.setMaxSize(maxSize);
                    break;
                case "bit_rate":
                    int bitRate = Integer.parseInt(value);
                    options.setBitRate(bitRate);
                    break;
                case "max_fps":
                    int maxFps = Integer.parseInt(value);
                    options.setMaxFps(maxFps);
                    break;
                case "lock_video_orientation":
                    int lockVideoOrientation = Integer.parseInt(value);
                    options.setLockVideoOrientation(lockVideoOrientation);
                    break;
                case "tunnel_forward":
                    boolean tunnelForward = Boolean.parseBoolean(value);
                    options.setTunnelForward(tunnelForward);
                    break;
                case "crop":
                    Rect crop = parseCrop(value);
                    options.setCrop(crop);
                    break;
                case "control":
                    boolean control = Boolean.parseBoolean(value);
                    options.setControl(control);
                    break;
                case "display_id":
                    int displayId = Integer.parseInt(value);
                    options.setDisplayId(displayId);
                    break;
                case "show_touches":
                    boolean showTouches = Boolean.parseBoolean(value);
                    options.setShowTouches(showTouches);
                    break;
                case "stay_awake":
                    boolean stayAwake = Boolean.parseBoolean(value);
                    options.setStayAwake(stayAwake);
                    break;
                case "codec_options":
                    List<CodecOption> codecOptions = CodecOption.parse(value);
                    options.setCodecOptions(codecOptions);
                    break;
                case "encoder_name":
                    if (!value.isEmpty()) {
                        options.setEncoderName(value);
                    }
                    break;
                case "power_off_on_close":
                    boolean powerOffScreenOnClose = Boolean.parseBoolean(value);
                    options.setPowerOffScreenOnClose(powerOffScreenOnClose);
                    break;
                case "clipboard_autosync":
                    boolean clipboardAutosync = Boolean.parseBoolean(value);
                    options.setClipboardAutosync(clipboardAutosync);
                    break;
                case "downsize_on_error":
                    boolean downsizeOnError = Boolean.parseBoolean(value);
                    options.setDownsizeOnError(downsizeOnError);
                    break;
                case "cleanup":
                    boolean cleanup = Boolean.parseBoolean(value);
                    options.setCleanup(cleanup);
                    break;
                case "power_on":
                    boolean powerOn = Boolean.parseBoolean(value);
                    options.setPowerOn(powerOn);
                    break;
                case "send_device_meta":
                    boolean sendDeviceMeta = Boolean.parseBoolean(value);
                    options.setSendDeviceMeta(sendDeviceMeta);
                    break;
                case "send_frame_meta":
                    boolean sendFrameMeta = Boolean.parseBoolean(value);
                    options.setSendFrameMeta(sendFrameMeta);
                    break;
                case "send_dummy_byte":
                    boolean sendDummyByte = Boolean.parseBoolean(value);
                    options.setSendDummyByte(sendDummyByte);
                    break;
                case "raw_video_stream":
                    boolean rawVideoStream = Boolean.parseBoolean(value);
                    if (rawVideoStream) {
                        options.setSendDeviceMeta(false);
                        options.setSendFrameMeta(false);
                        options.setSendDummyByte(false);
                    }
                    break;
                default:
                    Ln.w("Unknown server option: " + key);
                    break;
            }
        }

        return options;
    }

//    private static Options customOptions(String... args) {
//        org.apache.commons.cli.CommandLine commandLine = null;
//        org.apache.commons.cli.CommandLineParser parser = new org.apache.commons.cli.BasicParser();
//        org.apache.commons.cli.Options options = new org.apache.commons.cli.Options();
//        options.addOption("Q", true, "JPEG quality (0-100)");
//        options.addOption("r", true, "Frame rate (frames/s)");
//        options.addOption("P", true, "Display projection (1080, 720, 480...).");
//        options.addOption("c", false, "Control only");
//        options.addOption("L", false, "Library path");
//        options.addOption("D", false, "Dump window hierarchy");
//        options.addOption("h", false, "Show help");
//        try {
//            commandLine = parser.parse(options, args);
//        } catch (Exception e) {
//            Ln.e(e.getMessage());
//            System.exit(0);
//        }
//
//        if (commandLine.hasOption('h')) {
//            System.out.println(
//                    "Usage: [-h]\n\n"
//                            + "jpeg:\n"
//                            + "  -r <value>:    Frame rate (frames/sec).\n"
//                            + "  -P <value>:    Display projection (1080, 720, 480, 360...).\n"
//                            + "  -Q <value>:    JPEG quality (0-100).\n"
//                            + "\n"
//                            + "  -c:            Control only.\n"
//                            + "  -L:            Library path.\n"
//                            + "  -D:            Dump window hierarchy.\n"
//                            + "  -h:            Show help.\n"
//            );
//            System.exit(0);
//        }
//        if (commandLine.hasOption('L')) {
//            System.out.println(System.getProperty("java.library.path"));
//            System.exit(0);
//        }
//        Options o = new Options();
//        o.setMaxSize(0);
//        o.setTunnelForward(true);
//        o.setCrop(null);
//        o.setControl(true);
//        // global
//        o.setMaxFps(24);
//        o.setScale(480);
//        // jpeg
//        o.setQuality(60);
//        o.setBitRate(1000000);
//        o.setSendFrameMeta(true);
//        // control
//        o.setControlOnly(false);
//        // dump
//        o.setDumpHierarchy(false);
//        if (commandLine.hasOption('Q')) {
//            int i = 0;
//            try {
//                i = Integer.parseInt(commandLine.getOptionValue('Q'));
//            } catch (Exception e) {
//            }
//            if (i > 0 && i <= 100) {
//                o.setQuality(i);
//            }
//        }
//        if (commandLine.hasOption('r')) {
//            int i = 0;
//            try {
//                i = Integer.parseInt(commandLine.getOptionValue('r'));
//            } catch (Exception e) {
//            }
//            if (i > 0 && i <= 100) {
//                o.setMaxFps(i);
//            }
//        }
//        if (commandLine.hasOption('P')) {
//            int i = 0;
//            try {
//                i = Integer.parseInt(commandLine.getOptionValue('P'));
//            } catch (Exception e) {
//            }
//            if (i > 0) {
//                o.setScale(i);
//            }
//        }
//        if (commandLine.hasOption('c')) {
//            o.setControlOnly(true);
//        }
//        if (commandLine.hasOption('D')) {
//            o.setDumpHierarchy(true);
//        }
//        return o;
//    }

    private static Rect parseCrop(String crop) {
        if (crop.isEmpty()) {
            return null;
        }
        // input format: "width:height:x:y"
        String[] tokens = crop.split(":");
        if (tokens.length != 4) {
            throw new IllegalArgumentException("Crop must contains 4 values separated by colons: \"" + crop + "\"");
        }
        int width = Integer.parseInt(tokens[0]);
        int height = Integer.parseInt(tokens[1]);
        int x = Integer.parseInt(tokens[2]);
        int y = Integer.parseInt(tokens[3]);
        return new Rect(x, y, x + width, y + height);
    }

    private static void suggestFix(Throwable e) {
        if (e instanceof InvalidDisplayIdException) {
            InvalidDisplayIdException idie = (InvalidDisplayIdException) e;
            int[] displayIds = idie.getAvailableDisplayIds();
            if (displayIds != null && displayIds.length > 0) {
                Ln.e("Try to use one of the available display ids:");
                for (int id : displayIds) {
                    Ln.e("    scrcpy --display " + id);
                }
            }
        } else if (e instanceof InvalidEncoderException) {
            InvalidEncoderException iee = (InvalidEncoderException) e;
            MediaCodecInfo[] encoders = iee.getAvailableEncoders();
            if (encoders != null && encoders.length > 0) {
                Ln.e("Try to use one of the available encoders:");
                for (MediaCodecInfo encoder : encoders) {
                    Ln.e("    scrcpy --encoder '" + encoder.getName() + "'");
                }
            }
        }
    }

    public static void main(String... args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                Ln.e("Exception on thread " + t, e);
                suggestFix(e);
            }
        });

        Options options = createOptions(args);

        Ln.initLogLevel(options.getLogLevel());
        Ln.i("Options frame rate: " + options.getMaxFps() + " (1 ~ 60)");
        Ln.i("Options quality: " + options.getQuality() + " (1 ~ 100)");
        Ln.i("Options projection: " + options.getScale() + " (1080, 720, 480, 360...)");
        Ln.i("Options control only: " + options.getControlOnly() + " (true / false)");

        scrcpy(options);
    }
}
