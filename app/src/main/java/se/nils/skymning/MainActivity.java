package se.nils.skymning;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Build;
import android.annotation.SuppressLint;
import android.text.InputType;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.EditText;

public final class MainActivity extends Activity {
    private GameView game;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams layout=getWindow().getAttributes();
        layout.preferredRefreshRate=60;
        if(Build.VERSION.SDK_INT>=28)layout.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        getWindow().setAttributes(layout);
        game = new GameView(this);
        setContentView(game);
        immersive();
        if(Build.VERSION.SDK_INT>=33)getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,()->game.back());
        if (state != null) game.recover(state);
    }
    private void immersive() {
        if(Build.VERSION.SDK_INT>=30){WindowInsetsController controller=getWindow().getInsetsController();if(controller!=null){controller.hide(WindowInsets.Type.systemBars());controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);}return;}
        getWindow().getDecorView().setSystemUiVisibility(
        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN |
        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE); }
    @Override public void onWindowFocusChanged(boolean focus) { super.onWindowFocusChanged(focus); if (focus) immersive(); }
    @Override protected void onResume() { super.onResume(); if (game != null) game.resume(); }
    @Override protected void onPause() { if (game != null) game.suspend(); super.onPause(); }
    @Override protected void onDestroy() { if (game != null) game.dispose(); super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle state) { if (game != null) game.save(state); super.onSaveInstanceState(state); }
    // API 33+ uses the registered native dispatcher; this is only the legacy fallback.
    @SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() { game.back(); }
    void askAddress() {
        EditText input = new EditText(this);
        input.setSingleLine(true); input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("Exempel: 192.168.1.42"); input.setText(game.lastAddress());
        int pad = (int)(24 * getResources().getDisplayMetrics().density);
        input.setPadding(pad,pad,pad,pad);
        new AlertDialog.Builder(this).setTitle("Anslut till vän").setMessage("Ange adressen som visas på värdens telefon. Använd samma Wi-Fi.")
            .setView(input).setNegativeButton("Avbryt",null).setPositiveButton("Anslut",(d,w)->game.join(input.getText().toString().trim())).show();
    }
}
