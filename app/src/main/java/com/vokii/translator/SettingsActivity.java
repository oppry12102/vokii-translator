package com.vokii.translator;

import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * LLM configuration. The model radio group selects between DeepSeek V4
 * Flash and Pro (default Flash). The API key field starts blank — we
 * never display the bundled default value; if the user leaves it empty,
 * the app silently uses the BuildConfig default at runtime.
 */
public class SettingsActivity extends AppCompatActivity {

    private ConfigStore config;
    private EditText inputEndpoint;
    private EditText inputApiKey;
    private RadioGroup modelGroup;
    private RadioButton modelFlash;
    private RadioButton modelPro;
    private EditText inputTemp;
    private EditText inputLang;
    private Switch switchDebug;
    private TextView apiKeyStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTheme(R.style.Theme_Vokii_Settings);
        setContentView(R.layout.activity_settings);

        config = new ConfigStore(this);

        inputEndpoint  = findViewById(R.id.input_endpoint);
        inputApiKey    = findViewById(R.id.input_api_key);
        modelGroup     = findViewById(R.id.model_group);
        modelFlash     = findViewById(R.id.model_flash);
        modelPro       = findViewById(R.id.model_pro);
        inputTemp      = findViewById(R.id.input_temperature);
        inputLang      = findViewById(R.id.input_asr_lang);
        switchDebug    = findViewById(R.id.switch_debug);
        apiKeyStatus   = findViewById(R.id.api_key_status);

        inputEndpoint.setText(config.getEndpoint());
        inputTemp.setText(String.valueOf(config.getTemperature()));
        inputLang.setText(config.getAsrLang());
        switchDebug.setChecked(config.isDebugVisible());

        // Pre-select the model radio based on the current model id.
        selectModelRadio(config.getModel());

        // API key: blank by default to avoid revealing the bundled value.
        // Show the user's own value if they've previously typed one.
        String existing = config.getApiKeyForUi();
        if (!TextUtils.isEmpty(existing) && !existing.equals(BuildConfig.DEFAULT_QWEN_API_KEY)) {
            inputApiKey.setText(existing);
        }
        refreshApiKeyLabel();

        Button btnSave  = findViewById(R.id.btn_save);
        Button btnReset = findViewById(R.id.btn_reset);

        btnSave.setOnClickListener(v -> save());
        btnReset.setOnClickListener(v -> reset());
    }

    private void selectModelRadio(String model) {
        if (BuildConfig.QWEN_MODEL_PLUS.equals(model)) {
            modelPro.setChecked(true);
        } else {
            // Flash for anything else (incl. blank, custom). Users can
            // downgrade if Pro's 2x latency or higher API cost is unwelcome.
            modelFlash.setChecked(true);
        }
    }

    private String readSelectedModel() {
        int id = modelGroup.getCheckedRadioButtonId();
        if (id == R.id.model_pro) return BuildConfig.QWEN_MODEL_PLUS;
        return BuildConfig.QWEN_MODEL_FLASH;
    }

    private void reset() {
        config.reset();
        inputEndpoint.setText(BuildConfig.DEFAULT_QWEN_ENDPOINT);
        selectModelRadio(BuildConfig.QWEN_MODEL_PLUS);
        inputApiKey.setText("");
        inputTemp.setText(String.valueOf(Constants.DEFAULT_TEMPERATURE));
        inputLang.setText(Constants.DEFAULT_ASR_LANG);
        switchDebug.setChecked(Constants.DEFAULT_DEBUG_VISIBLE);
        refreshApiKeyLabel();
        Toast.makeText(this, R.string.setting_reset_done, Toast.LENGTH_SHORT).show();
    }

    private void save() {
        config.setEndpoint(inputEndpoint.getText().toString());
        config.setModel(readSelectedModel());
        config.setApiKey(inputApiKey.getText().toString());
        try {
            float t = Float.parseFloat(inputTemp.getText().toString());
            config.setTemperature(t);
        } catch (NumberFormatException ignored) {
            config.setTemperature(Constants.DEFAULT_TEMPERATURE);
        }
        config.setAsrLang(inputLang.getText().toString());
        config.setDebugVisible(switchDebug.isChecked());
        refreshApiKeyLabel();
        Toast.makeText(this, R.string.setting_saved, Toast.LENGTH_SHORT).show();
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