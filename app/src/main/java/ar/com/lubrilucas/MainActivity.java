package ar.com.lubrilucas;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.view.KeyEvent;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

public class MainActivity extends AppCompatActivity {

    /* ------------------------------------------------------------------ *
     *  LA DIRECCION DE LA WEB VA ACA. Es lo unico que hay que cambiar.    *
     * ------------------------------------------------------------------ */
    private static final String WEB = "https://PONER-ACA-LA-DIRECCION/";

    private WebView web;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMediaPlaybackRequiresUserGesture(false);

        web.setWebViewClient(new WebViewClient());      // los links quedan adentro de la app
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Puente(), "Android");

        pedirPermisoBluetooth();

        if (b != null) web.restoreState(b); else web.loadUrl(WEB);
    }

    private void pedirPermisoBluetooth() {
        if (Build.VERSION.SDK_INT >= 31) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                        this, new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 1);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int cod, @NonNull String[] p, @NonNull int[] r) {
        super.onRequestPermissionsResult(cod, p, r);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle b) {
        super.onSaveInstanceState(b);
        web.saveState(b);
    }

    /** El boton de atras navega la web en vez de cerrar la app. */
    @Override
    public boolean onKeyDown(int cod, KeyEvent ev) {
        if (cod == KeyEvent.KEYCODE_BACK && web.canGoBack()) { web.goBack(); return true; }
        return super.onKeyDown(cod, ev);
    }

    /* ---------------- puente que ve el javascript de la web ---------------- */
    public class Puente {

        /** La web lo usa para saber si esta corriendo adentro de la app. */
        @JavascriptInterface
        public boolean hayPuente() { return true; }

        /** Impresoras vinculadas, como JSON: [{"nombre":"...","mac":"..."}] */
        @JavascriptInterface
        public String listar() {
            JSONArray a = new JSONArray();
            try {
                List<BluetoothDevice> ds = Impresora.vinculadas();
                for (BluetoothDevice d : ds) {
                    JSONObject o = new JSONObject();
                    String n = d.getName();
                    o.put("nombre", n == null ? d.getAddress() : n);
                    o.put("mac", d.getAddress());
                    a.put(o);
                }
            } catch (Exception ignored) {}
            return a.toString();
        }

        /**
         * Imprime un PNG en base64 (sin el "data:image/png;base64,").
         * Devuelve "ok" o el motivo del error.
         */
        @JavascriptInterface
        public String imprimir(String base64, String impresora) {
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                Bitmap bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bm == null) return "No pude leer la imagen de la etiqueta.";
                return Impresora.imprimir(bm, impresora == null ? "" : impresora);
            } catch (Exception e) {
                return "Error al imprimir: " + e;
            }
        }
    }
}
