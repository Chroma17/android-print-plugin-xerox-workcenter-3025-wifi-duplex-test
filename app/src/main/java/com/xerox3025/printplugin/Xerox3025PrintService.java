package com.xerox3025.printplugin;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.print.PrintAttributes;
import android.print.PrinterCapabilitiesInfo;
import android.print.PrinterId;
import android.print.PrinterInfo;
import android.printservice.PrintDocument;
import android.printservice.PrintJob;
import android.printservice.PrintService;
import android.printservice.PrinterDiscoverySession;

import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;

import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public class Xerox3025PrintService extends PrintService {

    private static final String TAG = "Xerox3025Print";
    private static final String CHANNEL_ID = "print_jobs";
    private static final String DUPLEX_CHANNEL_ID = "duplex_prompts";
    private static final long DUPLEX_WAIT_MS = 30 * 60 * 1000L;
    private volatile boolean cancelled = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        PrintLog.i(TAG, "=== PrintService onCreate ===");
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Print Jobs", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Print job status notifications");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);

        NotificationChannel duplexChannel = new NotificationChannel(
                DUPLEX_CHANNEL_ID, "Two-sided printing prompts", NotificationManager.IMPORTANCE_HIGH);
        duplexChannel.setDescription("Asks you to flip the pages for manual two-sided printing");
        getSystemService(NotificationManager.class).createNotificationChannel(duplexChannel);
    }

    @Override
    public int onStartCommand(android.content.Intent intent, int flags, int startId) {
        PrintLog.i(TAG, "=== PrintService onStartCommand ===");
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    protected void onConnected() {
        PrintLog.i(TAG, "=== PrintService onConnected (enabled by user) ===");
    }

    @Override
    protected void onDisconnected() {
        PrintLog.i(TAG, "=== PrintService onDisconnected ===");
    }

    @Override
    protected PrinterDiscoverySession onCreatePrinterDiscoverySession() {
        PrintLog.i(TAG, "=== onCreatePrinterDiscoverySession ===");
        return new Xerox3025DiscoverySession(this);
    }

    @Override
    protected void onRequestCancelPrintJob(PrintJob printJob) {
        cancelled = true;
        DuplexActionReceiver.signal(printJob.getId().hashCode(), false);
        printJob.cancel();
    }

    @Override
    protected void onPrintJobQueued(PrintJob printJob) {
        // All PrintJob methods must be called on the main thread
        String jobName = printJob.getInfo().getLabel();
        int notifId = printJob.getId().hashCode();
        PrintAttributes attrs = printJob.getInfo().getAttributes();
        int duplexMode = (attrs != null) ? attrs.getDuplexMode() : PrintAttributes.DUPLEX_MODE_NONE;
        PrintLog.i(TAG, "onPrintJobQueued called: " + jobName);
        cancelled = false;
        printJob.start();

        // Get document data on main thread
        PrintDocument document = printJob.getDocument();
        ParcelFileDescriptor pfd = document.getData();
        if (pfd == null) {
            PrintLog.e(TAG, "No document data");
            printJob.fail("No document data available");
            PrintJobHistory.addJob(this, new PrintJobHistory.JobRecord(
                    jobName, System.currentTimeMillis(), "FAILED",
                    "No document data available", 0));
            return;
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String printerIp = prefs.getString("printer_ip", "192.168.0.109");

        new Thread(() -> {
            try {
                processPrintJob(printJob, jobName, notifId, pfd, printerIp, duplexMode);
            } catch (Exception e) {
                PrintLog.e(TAG, "Uncaught exception in processPrintJob", e);
                mainHandler.post(() -> {
                    try { printJob.fail("Internal error: " + e.getMessage()); }
                    catch (Exception ignored) {}
                });
                PrintJobHistory.addJob(getApplicationContext(),
                        new PrintJobHistory.JobRecord(jobName,
                                System.currentTimeMillis(), "FAILED",
                                "Crash: " + e.getMessage(), 0));
            }
        }).start();
    }

    private void processPrintJob(PrintJob printJob, String jobName, int notifId,
                                  ParcelFileDescriptor pfd, String printerIp,
                                  int duplexMode) {
        PrintLog.i(TAG, "Starting print job: " + jobName + " (duplexMode=" + duplexMode + ")");

        boolean wantsDuplex = duplexMode == PrintAttributes.DUPLEX_MODE_LONG_EDGE
                || duplexMode == PrintAttributes.DUPLEX_MODE_SHORT_EDGE;
        boolean shortEdge = duplexMode == PrintAttributes.DUPLEX_MODE_SHORT_EDGE;

        // The "flip the stack" prompt is a notification; without permission the user
        // would never see it and pass 1 would be wasted paper.
        if (wantsDuplex && !getSystemService(NotificationManager.class).areNotificationsEnabled()) {
            String reason = "Two-sided printing needs notifications: allow them for this app "
                    + "in Android settings, or choose one-sided";
            mainHandler.post(() -> printJob.fail(reason));
            PrintJobHistory.addJob(this, new PrintJobHistory.JobRecord(
                    jobName, System.currentTimeMillis(), "FAILED", reason, 0));
            closeQuietly(pfd);
            return;
        }

        // Show ongoing notification
        NotificationCompat.Builder notifBuilder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_printer)
                .setContentTitle("Printing...")
                .setContentText(jobName)
                .setOngoing(true)
                .setProgress(0, 0, true);
        try {
            getSystemService(NotificationManager.class).notify(notifId, notifBuilder.build());
        } catch (Exception e) {
            PrintLog.w(TAG, "Could not show notification: " + e.getMessage());
        }

        File tempFile = null;
        try {
            // Copy PFD to temp file (PdfRenderer needs seekable FD)
            tempFile = File.createTempFile("print_", ".pdf", getCacheDir());
            copyToFile(pfd, tempFile);
            PrintLog.i(TAG, "PDF copied to temp file: " + tempFile.length() + " bytes");

            int pageCount;
            {
                ParcelFileDescriptor tmp = ParcelFileDescriptor.open(tempFile,
                        ParcelFileDescriptor.MODE_READ_ONLY);
                PdfRenderer r = new PdfRenderer(tmp);
                pageCount = r.getPageCount();
                r.close();
                tmp.close();
            }
            PrintLog.i(TAG, "PDF has " + pageCount + " page(s)");

            boolean manualDuplex = wantsDuplex && pageCount > 1;

            if (!manualDuplex) {
                // ---- Normal one-sided job: all pages in one go ----
                List<Integer> all = new ArrayList<>();
                for (int i = 0; i < pageCount; i++) all.add(i);
                IppClient.IppResult result = renderAndSend(tempFile, all, false, printerIp, jobName);
                finishJob(printJob, jobName, notifId, notifBuilder, result, pageCount, "");
                return;
            }

            // ---- Manual duplex ----
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            boolean reverseEven = prefs.getBoolean("duplex_reverse_even", true);
            boolean rotatePref = prefs.getBoolean("duplex_rotate_even", false);
            // Short-edge binding means the back sides are turned 180 degrees relative to long-edge.
            boolean rotateEven = rotatePref ^ shortEdge;

            // Pass 1 = pages 1,3,5,... (0-based indexes 0,2,4,...)
            // Pass 2 = pages 2,4,6,...; if the page count is odd the last back side is blank (-1).
            List<Integer> odd = new ArrayList<>();
            List<Integer> even = new ArrayList<>();
            for (int i = 0; i < pageCount; i += 2) {
                odd.add(i);
                even.add(i + 1 < pageCount ? i + 1 : -1);
            }
            if (reverseEven) java.util.Collections.reverse(even);
            int sheets = odd.size();

            // Register before printing so a fast tap can't be missed.
            BlockingQueue<Boolean> answer = DuplexActionReceiver.register(notifId);
            try {
                notifBuilder.setContentText(jobName + " — printing front sides (" + sheets + " sheets)");
                notifySafe(notifId, notifBuilder);

                IppClient.IppResult r1 = renderAndSend(tempFile, odd, false, printerIp,
                        jobName + " (front sides)");
                if (r1 == null) { cancelJob(printJob, jobName, notifId, pageCount); return; }
                if (!r1.success) {
                    finishJob(printJob, jobName, notifId, notifBuilder, r1, pageCount, "");
                    return;
                }

                // Ask the user to turn the stack over and wait for the answer.
                postDuplexPrompt(notifId, jobName, sheets);
                Boolean proceed = null;
                long deadline = System.currentTimeMillis() + DUPLEX_WAIT_MS;
                while (proceed == null && !cancelled && System.currentTimeMillis() < deadline) {
                    proceed = answer.poll(1, TimeUnit.SECONDS);
                }
                if (proceed == null || !proceed || cancelled) {
                    if (proceed == null && !cancelled) {
                        PrintLog.w(TAG, "Timed out waiting for user to flip pages");
                    }
                    cancelJob(printJob, jobName, notifId, pageCount);
                    return;
                }

                notifBuilder.setContentTitle("Printing...")
                        .setContentText(jobName + " — printing back sides")
                        .setOngoing(true).setProgress(0, 0, true)
                        .clearActions();
                notifySafe(notifId, notifBuilder);

                IppClient.IppResult r2 = renderAndSend(tempFile, even, rotateEven, printerIp,
                        jobName + " (back sides)");
                if (r2 == null) { cancelJob(printJob, jobName, notifId, pageCount); return; }
                finishJob(printJob, jobName, notifId, notifBuilder, r2, pageCount,
                        ", manual duplex");
            } finally {
                DuplexActionReceiver.unregister(notifId);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelJob(printJob, jobName, notifId, 0);
        } catch (IOException e) {
            PrintLog.e(TAG, "Print failed: " + e.getMessage(), e);
            String reason = "Could not print to " + printerIp + " — " + e.getMessage();
            mainHandler.post(() -> printJob.fail(reason));
            getSystemService(NotificationManager.class).cancel(notifId);
            PrintJobHistory.addJob(this, new PrintJobHistory.JobRecord(
                    jobName, System.currentTimeMillis(), "FAILED", reason, 0));
        } catch (OutOfMemoryError e) {
            PrintLog.e(TAG, "Out of memory rendering PDF");
            String reason = "Document too large to render at 600 DPI";
            mainHandler.post(() -> printJob.fail(reason));
            getSystemService(NotificationManager.class).cancel(notifId);
            PrintJobHistory.addJob(this, new PrintJobHistory.JobRecord(
                    jobName, System.currentTimeMillis(), "FAILED", reason, 0));
        } finally {
            if (tempFile != null) tempFile.delete();
            closeQuietly(pfd);
        }
    }

    /**
     * Render the given PDF pages (0-based; -1 = blank page) one at a time, encode them as
     * URF and send them as one IPP job. Returns null if the user cancelled meanwhile.
     */
    private IppClient.IppResult renderAndSend(File pdf, List<Integer> pageIdx, boolean rotate180,
                                              String printerIp, String label) throws IOException {
        ParcelFileDescriptor tempPfd = ParcelFileDescriptor.open(pdf,
                ParcelFileDescriptor.MODE_READ_ONLY);
        PdfRenderer renderer = new PdfRenderer(tempPfd);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            UrfEncoder.writeDocumentHeader(out, pageIdx.size());

            for (int n = 0; n < pageIdx.size(); n++) {
                if (cancelled) {
                    PrintLog.i(TAG, "Job cancelled by user");
                    return null;
                }
                int idx = pageIdx.get(n);
                if (idx < 0) {
                    PrintLog.i(TAG, "Adding blank page");
                    UrfEncoder.encodeBlankPage(out);
                    continue;
                }

                long renderStart = System.currentTimeMillis();
                PdfRenderer.Page page = renderer.openPage(idx);
                // Render at 600 DPI, matching printer's expected dimensions
                Bitmap bmp = Bitmap.createBitmap(
                        UrfEncoder.A4_WIDTH_600, UrfEncoder.A4_HEIGHT_600,
                        Bitmap.Config.ARGB_8888);
                // Fill white first (PDF may not fill background)
                bmp.eraseColor(0xFFFFFFFF);
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                page.close();
                PrintLog.i(TAG, "Page " + (idx + 1) + " rendered in "
                        + (System.currentTimeMillis() - renderStart) + " ms");

                UrfEncoder.encodePage(out, bmp, rotate180);
                bmp.recycle();
            }

            byte[] urfData = out.toByteArray();
            PrintLog.i(TAG, "URF encoded: " + urfData.length + " bytes (" + label + ")");
            if (cancelled) return null;
            return IppClient.sendPrintJob(printerIp, urfData, label);
        } finally {
            renderer.close();
            tempPfd.close();
        }
    }

    private void finishJob(PrintJob printJob, String jobName, int notifId,
                           NotificationCompat.Builder notifBuilder, IppClient.IppResult result,
                           int pageCount, String extra) {
        if (result == null) {
            cancelJob(printJob, jobName, notifId, pageCount);
            return;
        }
        if (result.success) {
            PrintLog.i(TAG, "Print job completed: " + jobName);
            mainHandler.post(printJob::complete);
            try {
                notifBuilder.setContentTitle("Print complete")
                        .setContentText(jobName + " — " + pageCount + " page(s)")
                        .setOngoing(false)
                        .setAutoCancel(true)
                        .setProgress(0, 0, false)
                        .clearActions();
                getSystemService(NotificationManager.class).notify(notifId, notifBuilder.build());
            } catch (Exception ignored) {}
            PrintJobHistory.addJob(this, new PrintJobHistory.JobRecord(
                    jobName, System.currentTimeMillis(), "COMPLETED",
                    pageCount + " page(s)" + extra, pageCount));
        } else {
            PrintLog.e(TAG, "IPP failed: " + result.message);
            mainHandler.post(() -> printJob.fail(result.message));
            try {
                NotificationCompat.Builder notif = new NotificationCompat.Builder(this, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_printer)
                        .setContentTitle("Print failed")
                        .setContentText(result.message)
                        .setOngoing(false).setAutoCancel(true);
                getSystemService(NotificationManager.class).notify(notifId, notif.build());
            } catch (Exception ignored) {}
            PrintJobHistory.addJob(this, new PrintJobHistory.JobRecord(
                    jobName, System.currentTimeMillis(), "FAILED",
                    result.message, pageCount));
        }
    }

    private void cancelJob(PrintJob printJob, String jobName, int notifId, int pageCount) {
        PrintLog.i(TAG, "Job cancelled: " + jobName);
        mainHandler.post(() -> {
            try { printJob.cancel(); } catch (Exception ignored) {}
        });
        getSystemService(NotificationManager.class).cancel(notifId);
        PrintJobHistory.addJob(this, new PrintJobHistory.JobRecord(
                jobName, System.currentTimeMillis(), "CANCELLED", "Cancelled", pageCount));
    }

    private void notifySafe(int notifId, NotificationCompat.Builder b) {
        try {
            getSystemService(NotificationManager.class).notify(notifId, b.build());
        } catch (Exception ignored) {}
    }

    /** "Flip the stack" prompt with Continue / Cancel buttons. */
    private void postDuplexPrompt(int notifId, String jobName, int sheets) {
        Intent cont = new Intent(this, DuplexActionReceiver.class)
                .setAction(DuplexActionReceiver.ACTION_CONTINUE)
                .putExtra(DuplexActionReceiver.EXTRA_JOB_KEY, notifId);
        Intent cancel = new Intent(this, DuplexActionReceiver.class)
                .setAction(DuplexActionReceiver.ACTION_CANCEL)
                .putExtra(DuplexActionReceiver.EXTRA_JOB_KEY, notifId);
        int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent contPi = PendingIntent.getBroadcast(this, notifId, cont, flags);
        PendingIntent cancelPi = PendingIntent.getBroadcast(this, notifId, cancel, flags);

        String text = "Front sides done (" + sheets + " sheet" + (sheets == 1 ? "" : "s") + "). "
                + "Take the stack from the output tray and put it back in the input tray "
                + "with the printed side down, then tap Print back sides.";
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, DUPLEX_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_printer)
                .setContentTitle("Flip the pages — " + jobName)
                .setContentText("Put the printed stack back in the tray, then tap Print back sides")
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true)
                .addAction(0, "Print back sides", contPi)
                .addAction(0, "Cancel", cancelPi);
        notifySafe(notifId, b);
    }

    private static void closeQuietly(ParcelFileDescriptor pfd) {
        try { if (pfd != null) pfd.close(); } catch (IOException ignored) {}
    }

    private void copyToFile(ParcelFileDescriptor pfd, File dest) throws IOException {
        InputStream in = new FileInputStream(pfd.getFileDescriptor());
        FileOutputStream out = new FileOutputStream(dest);
        byte[] buf = new byte[8192];
        int len;
        while ((len = in.read(buf)) != -1) {
            out.write(buf, 0, len);
        }
        out.close();
        in.close();
    }

    // -------------------------------------------------------------------------
    // Discovery session — advertises the printer to Android's print system
    // -------------------------------------------------------------------------
    static class Xerox3025DiscoverySession extends PrinterDiscoverySession {

        private final PrintService service;

        Xerox3025DiscoverySession(PrintService service) {
            this.service = service;
        }

        @Override
        public void onStartPrinterDiscovery(List<PrinterId> priorityList) {
            PrintLog.i(TAG, "onStartPrinterDiscovery (priority list size: " + priorityList.size() + ")");
            addPrinters(buildPrinterList());
        }

        @Override
        public void onStopPrinterDiscovery() {
            PrintLog.i(TAG, "onStopPrinterDiscovery");
        }

        @Override
        public void onValidatePrinters(List<PrinterId> printerIds) {
            PrintLog.i(TAG, "onValidatePrinters: " + printerIds.size() + " printer(s)");
        }

        @Override
        public void onStartPrinterStateTracking(PrinterId printerId) {
            PrintLog.i(TAG, "onStartPrinterStateTracking: " + printerId);
            addPrinters(buildPrinterList());
        }

        @Override
        public void onStopPrinterStateTracking(PrinterId printerId) {
            PrintLog.i(TAG, "onStopPrinterStateTracking");
        }

        @Override
        public void onDestroy() {
            PrintLog.i(TAG, "DiscoverySession onDestroy");
        }

        private List<PrinterInfo> buildPrinterList() {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(
                    service.getApplicationContext());
            String ip = prefs.getString("printer_ip", "192.168.0.109");
            String displayName = prefs.getString("printer_name", "Xerox WorkCentre 3025");
            PrintLog.i(TAG, "buildPrinterList: name=" + displayName + ", ip=" + ip);

            PrinterId printerId = service.generatePrinterId("xerox3025_" + ip);

            PrinterCapabilitiesInfo capabilities = new PrinterCapabilitiesInfo.Builder(printerId)
                    .addMediaSize(PrintAttributes.MediaSize.ISO_A4, true)
                    .addMediaSize(PrintAttributes.MediaSize.NA_LETTER, false)
                    .addMediaSize(PrintAttributes.MediaSize.ISO_A5, false)
                    .addResolution(new PrintAttributes.Resolution("600dpi", "600 dpi", 600, 600), true)
                    .addResolution(new PrintAttributes.Resolution("300dpi", "300 dpi", 300, 300), false)
                    .setColorModes(
                            PrintAttributes.COLOR_MODE_MONOCHROME,
                            PrintAttributes.COLOR_MODE_MONOCHROME)
                    .setDuplexModes(
                            PrintAttributes.DUPLEX_MODE_NONE | PrintAttributes.DUPLEX_MODE_LONG_EDGE
                                    | PrintAttributes.DUPLEX_MODE_SHORT_EDGE,
                            PrintAttributes.DUPLEX_MODE_NONE)
                    .build();

            PrinterInfo printer = new PrinterInfo.Builder(
                    printerId, displayName, PrinterInfo.STATUS_IDLE)
                    .setCapabilities(capabilities)
                    .build();

            List<PrinterInfo> list = new ArrayList<>();
            list.add(printer);
            return list;
        }
    }
}
