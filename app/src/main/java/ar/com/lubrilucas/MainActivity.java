package ar.com.lubrilucas;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.os.Bundle;
import android.util.Base64;
import android.view.KeyEvent;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothSocket;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    /* ------------------------------------------------------------------ *
     *  LA DIRECCION DE LA WEB VA ACA. Es lo unico que hay que cambiar.    *
     * ------------------------------------------------------------------ */
    private static final String WEB = "https://farmex2026.github.io/Lubri-Luca/";

    private WebView web;

    /* para el boton de sacar foto que vive adentro de la web */
    private static final int PEDIDO_FOTO = 7;
    private ValueCallback<Uri[]> respuestaArchivo;
    private Uri fotoDestino;

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
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb,
                                             FileChooserParams params) {
                if (respuestaArchivo != null) respuestaArchivo.onReceiveValue(null);
                respuestaArchivo = cb;

                Intent camara = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                fotoDestino = null;
                try {
                    File dir = new File(getCacheDir(), "fotos");
                    if (!dir.exists() && !dir.mkdirs()) throw new Exception("sin carpeta");
                    File f = File.createTempFile("auto", ".jpg", dir);
                    fotoDestino = FileProvider.getUriForFile(
                            MainActivity.this, getPackageName() + ".fotos", f);
                    camara.putExtra(MediaStore.EXTRA_OUTPUT, fotoDestino);
                } catch (Exception e) {
                    camara = null;
                }

                Intent galeria;
                try { galeria = params.createIntent(); }
                catch (Exception e) {
                    galeria = new Intent(Intent.ACTION_GET_CONTENT);
                    galeria.addCategory(Intent.CATEGORY_OPENABLE);
                    galeria.setType("image/*");
                }

                Intent elegir = new Intent(Intent.ACTION_CHOOSER);
                elegir.putExtra(Intent.EXTRA_INTENT, galeria);
                elegir.putExtra(Intent.EXTRA_TITLE, "Foto del auto");
                if (camara != null) {
                    elegir.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{camara});
                }

                try {
                    startActivityForResult(elegir, PEDIDO_FOTO);
                    return true;
                } catch (Exception e) {
                    respuestaArchivo = null;
                    fotoDestino = null;
                    return false;
                }
            }
        });
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
    protected void onActivityResult(int pedido, int resultado, Intent datos) {
        super.onActivityResult(pedido, resultado, datos);
        if (pedido != PEDIDO_FOTO) return;
        if (respuestaArchivo == null) return;

        Uri[] salida = null;
        if (resultado == RESULT_OK) {
            if (datos != null && datos.getData() != null) {
                salida = new Uri[]{datos.getData()};       // eligio una de la galeria
            } else if (fotoDestino != null) {
                salida = new Uri[]{fotoDestino};           // saco una con la camara
            }
        }
        respuestaArchivo.onReceiveValue(salida);          // null = cancelo
        respuestaArchivo = null;
        fotoDestino = null;
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

    public static class Impresora {
    
        private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    
        /** Convierte el dibujo a puntos blanco y negro en el formato GS v 0 de ESC/POS. */
        public static byte[] aRaster(Bitmap bm) {
            int ancho = bm.getWidth(), alto = bm.getHeight();
            int bytesPorFila = (ancho + 7) / 8;
            byte[] puntos = new byte[bytesPorFila * alto];
    
            int[] px = new int[ancho * alto];
            bm.getPixels(px, 0, ancho, 0, 0, ancho, alto);
    
            for (int y = 0; y < alto; y++) {
                for (int x = 0; x < ancho; x++) {
                    int c = px[y * ancho + x];
                    int alfa = (c >>> 24) & 0xFF;
                    int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                    int luz = (r * 299 + g * 587 + b * 114) / 1000;
                    if (alfa < 128) luz = 255;               // transparente = papel
                    if (luz < 128) {
                        puntos[y * bytesPorFila + (x >> 3)] |= (byte) (0x80 >> (x & 7));
                    }
                }
            }
    
            byte[] orden = new byte[8 + puntos.length];
            orden[0] = 0x1D; orden[1] = 0x76; orden[2] = 0x30; orden[3] = 0x00;  // GS v 0, modo normal
            orden[4] = (byte) (bytesPorFila & 0xFF);
            orden[5] = (byte) ((bytesPorFila >> 8) & 0xFF);
            orden[6] = (byte) (alto & 0xFF);
            orden[7] = (byte) ((alto >> 8) & 0xFF);
            System.arraycopy(puntos, 0, orden, 8, puntos.length);
            return orden;
        }
    
        /** Impresoras ya vinculadas en Android. */
        public static List<BluetoothDevice> vinculadas() throws SecurityException {
            List<BluetoothDevice> lista = new ArrayList<>();
            BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
            if (ad == null) return lista;
            Set<BluetoothDevice> ds = ad.getBondedDevices();
            if (ds != null) lista.addAll(ds);
            return lista;
        }
    
        /**
         * Imprime. Si nombre viene vacio usa la primera impresora vinculada.
         * Devuelve "ok" o el motivo por el que no se pudo.
         */
        public static String imprimir(Bitmap bm, String nombre) {
            BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
            if (ad == null) return "Este celular no tiene Bluetooth.";
            if (!ad.isEnabled()) return "El Bluetooth está apagado. Prendelo y volvé a intentar.";
    
            BluetoothDevice elegida = null;
            try {
                List<BluetoothDevice> ds = vinculadas();
                if (ds.isEmpty()) {
                    return "No hay ninguna impresora vinculada. Vinculala primero desde "
                         + "Ajustes de Android \u2192 Bluetooth (el PIN es 0000 o 1234).";
                }
                for (BluetoothDevice d : ds) {
                    String n = d.getName();
                    if (nombre != null && !nombre.isEmpty()) {
                        if (nombre.equals(n) || nombre.equals(d.getAddress())) { elegida = d; break; }
                    } else if (elegida == null) {
                        elegida = d;
                    }
                }
                if (elegida == null) return "No encontré la impresora \"" + nombre + "\" entre las vinculadas.";
            } catch (SecurityException e) {
                return "Falta el permiso de Bluetooth.";
            }
    
            BluetoothSocket sock = null;
            try {
                ad.cancelDiscovery();
                sock = elegida.createRfcommSocketToServiceRecord(SPP);
                sock.connect();
    
                OutputStream sal = sock.getOutputStream();
                sal.write(new byte[]{0x1B, 0x40});          // ESC @  reiniciar
                sal.flush();
    
                byte[] raster = aRaster(bm);
                // de a pedazos: algunas impresoras se cuelgan si reciben todo de golpe
                int paso = 1024;
                for (int i = 0; i < raster.length; i += paso) {
                    int largo = Math.min(paso, raster.length - i);
                    sal.write(raster, i, largo);
                    sal.flush();
                    try { Thread.sleep(12); } catch (InterruptedException ignored) {}
                }
    
                sal.write(new byte[]{0x0A, 0x0A, 0x0A});    // avanzar el papel
                sal.flush();
                try { Thread.sleep(600); } catch (InterruptedException ignored) {}
                return "ok";
    
            } catch (SecurityException e) {
                return "Falta el permiso de Bluetooth.";
            } catch (Exception e) {
                String m = e.getMessage() == null ? e.toString() : e.getMessage();
                return "No pude conectar con la impresora. Fijate que esté prendida y cerca. (" + m + ")";
            } finally {
                if (sock != null) try { sock.close(); } catch (Exception ignored) {}
            }
        }
    }
}
