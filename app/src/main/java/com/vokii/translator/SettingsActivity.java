package com.vokii.translator;

import android.os.Bundle;
import android.text.TextUtils;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Persisted-user-overrides screen. Settings auto-save when the user
 * navigates back — there is no explicit Save button. The settings that
 * exist here are only those the user can legitimately want to change
 * from the defaults baked into BuildConfig:
 *
 *   - API key override (blank → use bundled default from local.properties)
 *   - Endpoint URL (default → DashScope realtime ASR + Qwen-Omni endpoints)
 *   - ASR language hint (default → "zh en")
 *   - Debug panel visibility
 *   - Cascade mode (Paraformer→qwen-mt-plus opt-in)
 *
 * Intentionally NOT here:
 *   - Model choice — Pro by default; Flash is a fallback if the operator
 *     changes BuildConfig. End users don't pick models.
 *   - Temperature — engine picks the optimal value at runtime.
 *   - Reset — destructive and rarely needed; if settings break, uninstall
 *     + reinstall restores BuildConfig defaults anyway.
 *
 * All edits are applied immediately on back press (or any exit) via
 * {@link OnBackPressedCallback}, so there is no "you forgot to save"
 * failure mode.
 */
public class SettingsActivity extends AppCompatActivity {

    private ConfigStore config;
    private EditText inputEndpoint;
    private EditText inputApiKey;
    private EditText inputLang;
    private Switch switchDebug;
    private Switch switchCascade;
    private TextView apiKeyStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTheme(R.style.Theme_Vokii_Settings);
        setContentView(R.layout.activity_settings);

        config = new ConfigStore(this);

        inputEndpoint = findViewById(R.id.input_endpoint);
        inputApiKey   = findViewById(R.id.input_api_key);
        inputLang     = findViewById(R.id.input_asr_lang);
        switchDebug   = findViewById(R.id.switch_debug);
        switchCascade = findViewById(R.id.switch_cascade);
        apiKeyStatus  = findViewById(R.id.api_key_status);

        // Load current values into the inputs.
        inputEndpoint.setText(config.getEndpoint());
        inputLang.setText(config.getAsrLang());
        switchDebug.setChecked(config.isDebugVisible());
        switchCascade.setChecked(config.isCascadeMode());

        // API key: blank by default to avoid revealing the bundled value.
        // Show the user's own value if they've previously typed one.
        String existing = config.getApiKeyForUi();
        if (!TextUtils.isEmpty(existing)
                && !existing.equals(BuildConfig.DEFAULT_QWEN_API_KEY)) {
            inputApiKey.setText(existing);
        }
        refreshApiKeyLabel();

        // Auto-save on back press. Replaces the old "Save" button.
        // We register a callback (AndroidX back dispatcher) rather than
        // overriding onBackPressed() — the dispatcher is the modern path
        // and works correctly with predictive-back gestures.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                persistAndFinish();
            }
        });
    }

    /** Also handle the toolbar back arrow. AppCompatActivity's default
     *  onSupportNavigateUp() calls finish() directly, bypassing the
     *  OnBackPressedDispatcher — so a user who taps the toolbar arrow
     *  instead of pressing the system Back key would lose their settings
     *  changes. This override routes both paths through persistAndFinish.
     */
    @Override
    public boolean onSupportNavigateUp() {
        persistAndFinish();
        return true;
    }

    /** Persist every editable field, then close the activity. */
    private void persistAndFinish() {
        config.setEndpoint(inputEndpoint.getText().toString());
        config.setApiKey(inputApiKey.getText().toString());
        config.setAsrLang(inputLang.getText().toString());
        config.setDebugVisible(switchDebug.isChecked());
        config.setCascadeMode(switchCascade.isChecked());
        refreshApiKeyLabel();
        finish();
    }

    private void refreshApiKeyLabel() {
        // Use the getApiKey() return value (which already applies the
        // precedence chain) — the label tells the user what will actually
        // be sent on the next request.
        String active = config.getApiKey();
        apiKeyStatus.setText(active.isEmpty()
                ? ""
                : (active.equals(BuildConfig.DEFAULT_QWEN_API_KEY)
                        ? getString(R.string.setting_api_key_status_default)
                        : getString(R.string.setting_api_key_status_user)));
    }
}