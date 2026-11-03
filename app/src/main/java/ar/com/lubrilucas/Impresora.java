package ar.com.lubrilucas;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.graphics.Bitmap;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Manda la etiqueta a la impresora termica por Bluetooth clasico (SPP), en ESC/POS.
 * Usa solo impresoras ya vinculadas desde los ajustes de Android: asi no hace falta
 * permiso de ubicacion, que solo se pide para buscar dispositivos nuevos.
 */
public class Impresora {

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
