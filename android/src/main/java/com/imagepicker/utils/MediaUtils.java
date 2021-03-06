package com.imagepicker.utils;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Environment;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.util.Log;
import android.os.Build;
import android.content.Intent;
import android.provider.MediaStore;

import com.facebook.react.bridge.ReadableMap;
import com.imagepicker.ImagePickerModule;
import com.imagepicker.ResponseHelper;
import com.imagepicker.media.ImageConfig;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.TimeZone;
import java.util.UUID;

import static com.imagepicker.ImagePickerModule.REQUEST_LAUNCH_IMAGE_CAPTURE;

/**
 * Created by rusfearuth on 15.03.17.
 */

public class MediaUtils
{
    public static @Nullable File createNewFile(@NonNull final Context reactContext,
                                               @NonNull final ReadableMap options,
                                               @NonNull final boolean forceLocal)
    {
        final String filename = new StringBuilder("image-")
                .append(UUID.randomUUID().toString())
                .append(".jpg")
                .toString();

        // defaults to Public Pictures Directory
        File path;

        if(Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        } else {
            path = reactContext.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        }

        if (ReadableMapUtils.hasAndNotNullReadableMap(options, "storageOptions")) 
        {
            final ReadableMap storageOptions = options.getMap("storageOptions");

            if (storageOptions.hasKey("privateDirectory"))
            {
                boolean saveToPrivateDirectory = storageOptions.getBoolean("privateDirectory");
                if (saveToPrivateDirectory)
                {
                    // if privateDirectory is set then save to app's private files directory
                    path = reactContext.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
                }
            }

            if (ReadableMapUtils.hasAndNotEmptyString(storageOptions, "path"))
            {
                path = new File(path, storageOptions.getString("path"));
            }
            
        }
        else if (forceLocal)
        {
            path = reactContext.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        }

        File result = new File(path, filename);

        try
        {
            path.mkdirs();
            result.createNewFile();
        }
        catch (IOException e)
        {
            e.printStackTrace();
            result = null;
        }

        return result;
    }

    /**
     * Create a resized image to fulfill the maxWidth/maxHeight, quality and rotation values
     *
     * @param context
     * @param options
     * @param imageConfig
     * @param initialWidth
     * @param initialHeight
     * @return updated ImageConfig
     */
    public static @NonNull ImageConfig getResizedImage(@NonNull final Context context,
                                                       @NonNull final ReadableMap options,
                                                       @NonNull final ImageConfig imageConfig,
                                                       int initialWidth,
                                                       int initialHeight,
                                                       final int requestCode)
    {
        BitmapFactory.Options imageOptions = new BitmapFactory.Options();
        imageOptions.inScaled = false;
        imageOptions.inSampleSize = 1;

        if (imageConfig.maxWidth != 0 || imageConfig.maxHeight != 0) {
            while ((imageConfig.maxWidth == 0 || initialWidth > 2 * imageConfig.maxWidth) &&
                   (imageConfig.maxHeight == 0 || initialHeight > 2 * imageConfig.maxHeight)) {
                imageOptions.inSampleSize *= 2;
                initialHeight /= 2;
                initialWidth /= 2;
            }
        }

        Bitmap photo = BitmapFactory.decodeFile(imageConfig.original.getAbsolutePath(), imageOptions);

        if (photo == null)
        {
            return imageConfig;
        }

        ImageConfig result = imageConfig;

        Bitmap scaledPhoto = null;
        if (imageConfig.maxWidth == 0 || imageConfig.maxWidth > initialWidth)
        {
            result = result.withMaxWidth(initialWidth);
        }
        if (imageConfig.maxHeight == 0 || imageConfig.maxWidth > initialHeight)
        {
            result = result.withMaxHeight(initialHeight);
        }

        double widthRatio = (double) result.maxWidth / initialWidth;
        double heightRatio = (double) result.maxHeight / initialHeight;

        double ratio = (widthRatio < heightRatio)
                ? widthRatio
                : heightRatio;

        Matrix matrix = new Matrix();
        matrix.postRotate(result.rotation);
        matrix.postScale((float) ratio, (float) ratio);

        ExifInterface exif;
        try
        {
            exif = new ExifInterface(result.original.getAbsolutePath());

            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0);

            switch (orientation)
            {
                case 6:
                    matrix.postRotate(90);
                    break;
                case 3:
                    matrix.postRotate(180);
                    break;
                case 8:
                    matrix.postRotate(270);
                    break;
            }
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }

        scaledPhoto = Bitmap.createBitmap(photo, 0, 0, photo.getWidth(), photo.getHeight(), matrix, true);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        scaledPhoto.compress(Bitmap.CompressFormat.JPEG, result.quality, bytes);

        final boolean forceLocal = requestCode == REQUEST_LAUNCH_IMAGE_CAPTURE;
        final File resized = createNewFile(context, options, !forceLocal);

        if (resized == null)
        {
            if (photo != null)
            {
                photo.recycle();
                photo = null;
            }
            if (scaledPhoto != null)
            {
                scaledPhoto.recycle();
                scaledPhoto = null;
            }
            return imageConfig;
        }

        result = result.withResizedFile(resized);

        try (FileOutputStream fos = new FileOutputStream(result.resized))
        {
            bytes.writeTo(fos);
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }

        if (photo != null)
        {
            photo.recycle();
            photo = null;
        }
        if (scaledPhoto != null)
        {
            scaledPhoto.recycle();
            scaledPhoto = null;
        }
        return result;
    }

    public static void removeUselessFiles(final int requestCode,
                                          @NonNull final ImageConfig imageConfig)
    {
        if (requestCode != ImagePickerModule.REQUEST_LAUNCH_IMAGE_CAPTURE)
        {
            return;
        }

        if (imageConfig.original != null && imageConfig.original.exists())
        {
            imageConfig.original.delete();
        }

        if (imageConfig.resized != null && imageConfig.resized.exists())
        {
            imageConfig.resized.delete();
        }
    }

    public static void fileScan(@Nullable final Context reactContext,
                                @NonNull final String path)
    {
        if (reactContext == null)
        {
            return;
        }

        /* New Method to scan */
        if(Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
             MediaScannerConnection.scanFile(reactContext,
                new String[] { path }, null,
                new MediaScannerConnection.OnScanCompletedListener()
                {
                    public void onScanCompleted(String path, Uri uri)
                    {
                        Log.i("TAG", new StringBuilder("Finished scanning ").append(path).toString());
                    }
            });
        } else {
            Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
            File f = new File(path);
            Uri contentUri = Uri.fromFile(f);
            mediaScanIntent.setData(contentUri);
            reactContext.sendBroadcast(mediaScanIntent);
        }
    }

    public static ReadExifResult readExifInterface(@NonNull ResponseHelper responseHelper,
                                                   @NonNull final ImageConfig imageConfig)
    {
        ReadExifResult result;
        int currentRotation = 0;

        try
        {
            ExifInterface exif = new ExifInterface(imageConfig.original.getAbsolutePath());

            // extract lat, long, and timestamp and add to the response
            float[] latlng = new float[2];
            exif.getLatLong(latlng);
            float latitude = latlng[0];
            float longitude = latlng[1];
            if(latitude != 0f || longitude != 0f)
            {
                responseHelper.putDouble("latitude", latitude);
                responseHelper.putDouble("longitude", longitude);
            }

            final String timestamp = exif.getAttribute(ExifInterface.TAG_DATETIME);
            final SimpleDateFormat exifDatetimeFormat = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss");

            final DateFormat isoFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
            isoFormat.setTimeZone(TimeZone.getTimeZone("UTC"));

            try
            {
                final String isoFormatString = new StringBuilder(isoFormat.format(exifDatetimeFormat.parse(timestamp)))
                        .append("Z").toString();
                responseHelper.putString("timestamp", isoFormatString);
            }
            catch (Exception e) {}

            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            boolean isVertical = true;
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_270:
                    isVertical = false;
                    currentRotation = 270;
                    break;
                case ExifInterface.ORIENTATION_ROTATE_90:
                    isVertical = false;
                    currentRotation = 90;
                    break;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    currentRotation = 180;
                    break;
            }
            responseHelper.putInt("originalRotation", currentRotation);
            responseHelper.putBoolean("isVertical", isVertical);
            result = new ReadExifResult(currentRotation, null);
        }
        catch (IOException e)
        {
            e.printStackTrace();
            result = new ReadExifResult(currentRotation, e);
        }

        return result;
    }

    public static @Nullable RolloutPhotoResult rolloutPhotoFromCamera(@NonNull final ImageConfig imageConfig)
    {
        RolloutPhotoResult result = null;
        final File oldFile = imageConfig.resized == null ? imageConfig.original: imageConfig.resized;
        final File newDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
        final File newFile = new File(newDir.getPath(), oldFile.getName());

        try
        {
            moveFile(oldFile, newFile);
            ImageConfig newImageConfig;
            if (imageConfig.resized != null)
            {
                newImageConfig = imageConfig.withResizedFile(newFile);
            }
            else
            {
                newImageConfig = imageConfig.withOriginalFile(newFile);
            }
            result = new RolloutPhotoResult(newImageConfig, null);
        }
        catch (IOException e)
        {
            e.printStackTrace();
            result = new RolloutPhotoResult(imageConfig, e);
        }
        return result;
    }

    public static void saveToPublicDirectory(Uri uri, Context context) {
        ContentResolver resolver = context.getContentResolver();
        Uri mediaStoreUri;
        ContentValues fileDetails = new ContentValues();

        fileDetails.put(MediaStore.Images.Media.DISPLAY_NAME, UUID.randomUUID().toString());
        mediaStoreUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, fileDetails);

        copyUri(uri, mediaStoreUri, resolver);
    }

    public static void copyUri(Uri fromUri, Uri toUri, ContentResolver resolver) {
        try {
            OutputStream os = resolver.openOutputStream(toUri);
            InputStream is = resolver.openInputStream(fromUri);

            byte[] buffer = new byte[8192];
            int bytesRead;

            while ((bytesRead = is.read(buffer)) != -1) {
                os.write(buffer, 0, bytesRead);
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /* Copy files interface/middleware */
    public static @Nullable RolloutPhotoResult copyTo(@NonNull final Context reactContext, @NonNull final ImageConfig imageConfig, String des)
    {
         
        RolloutPhotoResult result = null;
        File newDir = null;
        final File oldFile = imageConfig.resized == null ? imageConfig.original: imageConfig.resized;
        
        if(Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            newDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM + "/" + des);
            final File newFile = new File(newDir.getPath(), oldFile.getName());
            try
            {  
                newDir.mkdirs();
                copyFile(oldFile, newFile);
                fileScan(reactContext, newFile.getAbsolutePath());
                result = new RolloutPhotoResult(imageConfig, null);
            }
            catch (IOException e)
            {
                e.printStackTrace();
            }
        } else {
            Uri newUri = Uri.fromFile(oldFile);
            saveToPublicDirectory(newUri, reactContext);
            // Uri imagesUri = MediaStore.Images.Media.getContentUri(MediaStore.EXTERNAL_CONTENT_URI);

            // newDir = reactContext.getExternalFilesDir(Environment.DIRECTORY_DCIM + "/" + des);
            // System.out.println(newDir.getAbsolutePath());
        }
 
        

        
        return result;
    }

    /**
     * Move a file from one location to another.
     *
     * This is done via copy + deletion, because Android will throw an error
     * if you try to move a file across mount points, e.g. to the SD card.
     */
    private static void moveFile(@NonNull final File oldFile,
                                 @NonNull final File newFile) throws IOException
    {
        FileChannel oldChannel = null;
        FileChannel newChannel = null;

        try
        {
            oldChannel = new FileInputStream(oldFile).getChannel();
            newChannel = new FileOutputStream(newFile).getChannel();
            oldChannel.transferTo(0, oldChannel.size(), newChannel);

            oldFile.delete();
        }
        finally
        {
            try
            {
                if (oldChannel != null) oldChannel.close();
                if (newChannel != null) newChannel.close();
            }
            catch (IOException e)
            {
                e.printStackTrace();
            }
        }
    }

    /* Copy files in fact */
    private static void copyFile(@NonNull final File oldFile,
                                 @NonNull final File newFile) throws IOException
    {
        InputStream in = new FileInputStream(oldFile);
        try {
            OutputStream out = new FileOutputStream(newFile);
            try {
                // Transfer bytes from in to out
                byte[] buf = new byte[1024];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }  finally {
                out.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            in.close();
        }
    }


    public static class RolloutPhotoResult
    {
        public final ImageConfig imageConfig;
        public final Throwable error;

        public RolloutPhotoResult(@NonNull final ImageConfig imageConfig,
                                  @Nullable final Throwable error)
        {
            this.imageConfig = imageConfig;
            this.error = error;
        }
    }


    public static class ReadExifResult
    {
        public final int currentRotation;
        public final Throwable error;

        public ReadExifResult(int currentRotation,
                              @Nullable final Throwable error)
        {
            this.currentRotation = currentRotation;
            this.error = error;
        }
    }
}
