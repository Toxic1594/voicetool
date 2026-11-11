package de.voicetool.reader;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;

/**
 * About screen: version, developer profile, source code, and license.
 */
public class AboutActivity extends Activity {

    private static final String PROFILE_URL = "https://github.com/Toxic1594";
    private static final String SOURCE_URL = "https://github.com/Toxic1594/voicetool";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        TextView version = findViewById(R.id.about_version);
        version.setText(getString(R.string.about_version, readVersion()));

        findViewById(R.id.about_developer).setOnClickListener(v -> open(PROFILE_URL));
        findViewById(R.id.about_source).setOnClickListener(v -> open(SOURCE_URL));
        findViewById(R.id.about_back).setOnClickListener(v -> finish());
    }

    private String readVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException | NullPointerException exc) {
            return "?";
        }
    }

    private void open(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (android.content.ActivityNotFoundException ignored) {
            // No browser installed: keep the screen open.
        }
    }
}
