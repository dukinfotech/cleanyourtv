package tv.webremote;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;

import java.util.Locale;

/**
 * The language of this app: English or Vietnamese. By default it follows the TV's language (Vietnamese if the TV is set to
 * Vietnamese, English for anything else); the person can pick one in the app, and a pick wins over the TV's setting. The
 * web page follows whatever this says.
 */
final class Lang {

    static final String EN = "en", VI = "vi", AUTO = "";
    private static final String KEY = "lang";

    private Lang() {}

    /** What was picked in the app: "en", "vi", or "" for automatic. */
    static String choice(Context context) {
        String value = context.getSharedPreferences(Core.PREFS, Context.MODE_PRIVATE).getString(KEY, AUTO);
        return EN.equals(value) || VI.equals(value) ? value : AUTO;
    }

    static void setChoice(Context context, String value) {
        context.getSharedPreferences(Core.PREFS, Context.MODE_PRIVATE).edit().putString(KEY, value).apply();
    }

    /** The language in use: the pick, else the TV's language when it is Vietnamese, else English. */
    static String effective(Context context) {
        String picked = choice(context);
        if (!AUTO.equals(picked)) return picked;
        String system = Resources.getSystem().getConfiguration().getLocales().get(0).getLanguage();
        return VI.equals(system) ? VI : EN;
    }

    /** A context whose resources use the app's language, for screens and services that show text. */
    static Context wrap(Context base) {
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.setLocale(new Locale(effective(base)));
        return base.createConfigurationContext(configuration);
    }
}
