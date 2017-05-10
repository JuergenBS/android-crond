package it.faerb.crond;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Build;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.util.Log;

import com.cronutils.descriptor.CronDescriptor;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;

import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;

import static it.faerb.crond.Constants.INTENT_EXTRA_LINE_NAME;
import static it.faerb.crond.Constants.INTENT_EXTRA_LINE_NO_NAME;
import static it.faerb.crond.Constants.PREFERENCES_FILE;
import static it.faerb.crond.Constants.PREF_CRONTAB_HASH;
import static it.faerb.crond.Constants.PREF_ENABLED;


class Crond {

    private static final String TAG = "Crond";

    private final CronParser parser =
            new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX));
    private final CronDescriptor descriptor = CronDescriptor.instance(Locale.getDefault());

    private Context context = null;
    private AlarmManager alarmManager = null;
    private SharedPreferences sharedPrefs = null;
    private String crontab = "";

    private static final String PREF_CRONTAB_LINE_COUNT = "old_tab_line_count";
    private static final String HASH_ALGO = "sha-256";

    public Crond(Context context) {
        this.context = context;
        alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        sharedPrefs = context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE);
    }

    public SpannableStringBuilder processCrontab() {
        SpannableStringBuilder ret = new SpannableStringBuilder();
        String hashedTab = "";
        try {
            MessageDigest messageDigest = MessageDigest.getInstance(HASH_ALGO);
            messageDigest.update(crontab.getBytes());
            hashedTab = new String(messageDigest.digest());
        }
        catch (NoSuchAlgorithmException e) {
            Log.e(TAG, String.format("Algorithm %s not found:", HASH_ALGO));
            e.printStackTrace();
        }
        if (!hashedTab.equals(sharedPrefs.getString(PREF_CRONTAB_HASH, ""))
                && !crontab.equals("")) {
            // only schedule when enabled
            if (sharedPrefs.getBoolean(PREF_ENABLED, false)) {
                IO.logToLogFile(context.getString(R.string.log_crontab_change_detected));
                scheduleCrontab();
            }
            // save in any case such that on installation the crontab is not "new"
            sharedPrefs.edit().putString(PREF_CRONTAB_HASH, hashedTab).apply();
        }
        if (crontab.equals("")) {
            return ret;
        }
        for (String line : crontab.split("\n")){
            ret.append(line + "\n",
                    new TypefaceSpan("monospace"), Spanned.SPAN_COMPOSING);
            ret.append(describeLine(line));
        }
        return ret;
    }

    public void setCrontab(String crontab) {
        this.crontab = crontab;
    }

    public void scheduleCrontab() {
        cancelAllAlarms(sharedPrefs.getInt(PREF_CRONTAB_LINE_COUNT, 0));
        // check here, because this can get called directly
        if (!sharedPrefs.getBoolean(PREF_ENABLED, false)) {
            return;
        }
        int i = 0;
        for (String line : crontab.split("\n")) {
            scheduleLine(line, i);
            i++;
        }
        sharedPrefs.edit().putInt(PREF_CRONTAB_LINE_COUNT, crontab.split("\n").length).apply();
    }

    public void scheduleLine(String line, int lineNo) {
        ParsedLine parsedLine = parseLine(line);
        if (parsedLine == null) {
            return;
        }
        ExecutionTime time = ExecutionTime.forCron(parser.parse(parsedLine.cronExpr));
        DateTime next = time.nextExecution(DateTime.now());
        Intent intent = new Intent(context, AlarmReceiver.class);
        intent.putExtra(INTENT_EXTRA_LINE_NAME, line);
        intent.putExtra(INTENT_EXTRA_LINE_NO_NAME, lineNo);
        PendingIntent alarmIntent = PendingIntent.getBroadcast(context, lineNo, intent,
                PendingIntent.FLAG_UPDATE_CURRENT); // update current to replace the one used
                                                    // for cancelling any previous set alarms
        alarmManager.set(AlarmManager.RTC_WAKEUP, next.getMillis(), alarmIntent);
        IO.logToLogFile(context.getString(R.string.log_scheduled, lineNo,
                DateTimeFormat.forPattern("YYYY-MM-dd HH:mm:ss.SSSS").print(next)));
    }

    public void executeLine(String line, int lineNo) {
        ParsedLine parsedLine = parseLine(line);
        if (parsedLine == null) {
            return;
        }
        IO.logToLogFile(context.getString(R.string.log_execute_pre, lineNo));
        IO.executeCommand(parsedLine.runExpr);
        IO.logToLogFile(context.getString(R.string.log_execute_post, lineNo));
    }

    private SpannableStringBuilder describeLine(String line) {
        SpannableStringBuilder ret = new SpannableStringBuilder();
        ParsedLine parsedLine = parseLine(line);
        if (parsedLine == null) {
            ret.append(context.getResources().getString(R.string.invalid_cron) + "\n",
                    new StyleSpan(Typeface.ITALIC), Spanned.SPAN_COMPOSING);
        }
        else {
            ret.append(context.getResources().getString(R.string.run) + " ",
                    new StyleSpan(Typeface.ITALIC), Spanned.SPAN_COMPOSING);
            ret.append(parsedLine.runExpr + " ",
                    new TypefaceSpan("monospace"), Spanned.SPAN_COMPOSING);
            ret.append(descriptor.describe(parser.parse(parsedLine.cronExpr)) + "\n",
                    new StyleSpan(Typeface.ITALIC), Spanned.SPAN_COMPOSING);
        }
        if (Build.VERSION.SDK_INT >= 23) {
            ret.setSpan(new ForegroundColorSpan(
                            context.getColor(R.color.colorPrimaryDark)), 0,
                    ret.length(), Spanned.SPAN_COMPOSING);
        }
        else {
            ret.setSpan(new ForegroundColorSpan(
                            context.getResources().getColor(R.color.colorPrimaryDark)), 0,
                    ret.length(), Spanned.SPAN_COMPOSING);
        }
        return ret;
    }

    private void cancelAllAlarms(int oldTabLineCount) {
        Intent intent = new Intent(context, AlarmReceiver.class);
        for (int i = 0; i<oldTabLineCount; i++) {
            PendingIntent alarmIntent = PendingIntent.getBroadcast(context, i, intent, 0);
            alarmManager.cancel(alarmIntent);
        }
    }

    private ParsedLine parseLine(String line) {
        line = line.trim();
        if (line.equals("")) {
            return null;
        }
        if (line.charAt(0) != '*'
                && !Character.isDigit(line.charAt(0))) {
            return null;
        }
        String [] splitLine = line.split(" ");
        if (splitLine.length < 6) {
            return null;
        }
        String[] cronExpr = Arrays.copyOfRange(splitLine, 0, 5);
        String[] runExpr = Arrays.copyOfRange(splitLine, 5, splitLine.length);
        String joinedCronExpr = TextUtils.join(" ", cronExpr);
        String joinedRunExpr = TextUtils.join(" ", runExpr);
        return new ParsedLine(joinedCronExpr, joinedRunExpr);
    }

    private class ParsedLine {
        final String cronExpr;
        final String runExpr;
        ParsedLine(String cronExpr, String runExpr) {
            this.cronExpr = cronExpr;
            this.runExpr = runExpr;
        }
    }
}
