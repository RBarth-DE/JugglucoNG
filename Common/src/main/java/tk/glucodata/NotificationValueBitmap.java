package tk.glucodata;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

/** Text-only fallback for fonts/weights that cannot be transported to System UI. */
final class NotificationValueBitmap {
    private NotificationValueBitmap() { }

    static Bitmap draw(Context context, CharSequence text, int color, float pixels,
            int weight, boolean systemFont) {
        TextPaint paint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG | TextPaint.SUBPIXEL_TEXT_FLAG);
        paint.setTextSize(pixels * 2f);
        paint.setColor(color);
        if (systemFont) {
            Typeface face = Typeface.create(CustomGlucoseNotification.systemFamily(context, 400), Typeface.NORMAL);
            if (android.os.Build.VERSION.SDK_INT >= 28) face = Typeface.create(face, weight, false);
            paint.setTypeface(face);
        } else {
            paint.setTypeface(context.getResources().getFont(R.font.ibm_plex_sans_var));
            paint.setFontVariationSettings("'wght' " + weight + ", 'wdth' 100");
        }
        int width = Math.max(1, (int) Math.ceil(Layout.getDesiredWidth(text, paint)) + 1);
        StaticLayout layout = StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build();
        Bitmap bitmap = Bitmap.createBitmap(width, Math.max(1, layout.getHeight()), Bitmap.Config.ARGB_8888);
        bitmap.setDensity(Math.round(context.getResources().getDisplayMetrics().densityDpi * 2f));
        layout.draw(new Canvas(bitmap));
        return bitmap;
    }
}
