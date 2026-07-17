package com.vokii.translator;

import android.os.Bundle;
import android.text.TextUtils;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Minimal configuration screen. The design intent is "极简": only
 * fields that have no voice equivalent appear here.
 *
 *  - API key: a credential, not a runtime toggle. Persisted, hidden
 *    by default so the bundled value isn't accidentally overwritten.
 *  - Cascade mode: a one-time path choice the user makes when first
 *    setting up. After that, switching paths is also possible via the
 *    cascade=false/true voice commands (toggle_cascade), but the user
 *    keeps this control here as a safety net.
 *
 * Everything else (source/target/display mode, debug visibility, mic
 * pause, log level, style, temperature, summary, re-translate, export)
 * is voice-controlled. The full command catalog is returned by the
 * list_commands tool — say "你能做什么" or "help" to see it.
 *
 * Settings auto-save when the user navigates back. There is no Save
 * button (the AndroidX back dispatcher routes toolbar-back and system-
 * back through the same path so neither forgets to persist).
 */
public class SettingsActivity extends AppCompatActivity {

    private ConfigStore config;
    private EditText inputApiKey;
    private Switch switchCascade;
    private TextView apiKeyStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTheme(R.style.Theme_Vokii_Settings);
        setContentView(R.layout.activity_settings);

        config = new ConfigStore(this);

        inputApiKey   = findViewById(R.id.input_api_key);
        switchCascade = findViewById(R.id.switch_cascade);
        apiKeyStatus  = findViewById(R.id.api_key_status);

        switchCascade.setChecked(config.isCascadeMode());

        // API key: blank by default to avoid revealing the bundled value.
        // Show the user's own value if they've previously typed one.
        String existing = config.getApiKeyForUi();
        if (!TextUtils.isEmpty(existing)
                && !existing.equals(BuildConfig.DEFAULT_QWEN_API_KEY)) {
            inputApiKey.setText(existing);
        }
        refreshApiKeyLabel();

        // Auto-save on back press.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                persistAndFinish();
            }
        });
    }

    @Override
    public boolean onSupportNavigateUp() {
        persistAndFinish();
        return true;
    }

    private void persistAndFinish() {
        config.setApiKey(inputApiKey.getText().toString());
        config.setCascadeMode(switchCascade.isChecked());
        refreshApiKeyLabel();
        finish();
    }

    private void refreshApiKeyLabel() {
        String active = config.getApiKey();
        apiKeyStatus.setText(active.isEmpty()
                ? ""
                : (active.equals(BuildConfig.DEFAULT_QWEN_API_KEY)
                        ? getString(R.string.setting_api_key_status_default)
                        : getString(R.string.setting_api_key_status_user)));
    }
}
