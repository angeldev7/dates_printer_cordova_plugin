package com.giorgiofellipe.datecsprinter;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.PluginResult;
import org.json.JSONObject;
import org.json.JSONArray;
import org.json.JSONException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.Exception;
import java.util.Hashtable;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.UUID;
import java.net.Socket;
import java.net.UnknownHostException;
import java.lang.reflect.Method;

import android.app.Activity;
import android.app.ProgressDialog;
import android.widget.Toast;
import android.util.Log;
import android.content.Intent;
import android.os.Handler;
import android.os.Bundle;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Base64;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.datecs.api.BuildInfo;
import com.datecs.api.printer.PrinterInformation;
import com.datecs.api.printer.Printer;
import com.datecs.api.printer.ProtocolAdapter;

public class DatecsSDKWrapper {
    private static final String LOG_TAG = "BluetoothPrinter";
    private Printer mPrinter;
    private ProtocolAdapter mProtocolAdapter;
    private BluetoothSocket mBluetoothSocket;
    private boolean mRestart;
    private String mAddress;
    private CallbackContext mConnectCallbackContext;
    private CallbackContext mCallbackContext;
    private ProgressDialog mDialog;
    private CordovaInterface mCordova;
    private CordovaWebView mWebView;

    /**
     * Interface de eventos da Impressora
     */
    private final ProtocolAdapter.ChannelListener mChannelListener = new ProtocolAdapter.ChannelListener() {
        @Override
        public void onReadEncryptedCard() {
            // TODO: onReadEncryptedCard
        }

        @Override
        public void onReadCard() {
            // TODO: onReadCard
        }

        @Override
        public void onReadBarcode() {
            // TODO: onReadBarcode
        }

        @Override
        public void onPaperReady(boolean state) {
            if (state) {
                showToast("El papel de la impresora esta correcto");
            } else {
                closeActiveConnections();
                showToast("No hay papel en la impresora");
            }
        }

        @Override
        public void onOverHeated(boolean state) {
            if (state) {
                showToast("La impresora se esta sobrecalentando");
            }
        }

        @Override
        public void onLowBattery(boolean state) {
            if (state) {
                showToast("La impresora tiene poca bateria");
            }
        }
    };

    private Map<Integer, String> errorCode = new HashMap<Integer, String>(){{
        put(1, "Adaptador Bluetooth no disponible");
        put(2, "No se encontro ningun dispositivo Bluetooth");
        put(3, "La cantidad de lineas debe ser entre 0 e 255");
        put(4, "Error al alimentar papel el papel de la impresora");
        put(5, "Error al imprimir");
        put(6, "Error al obtener el estado");
        put(7, "Error al obtener la temperatura");
        put(8, "Error al imprimir el c??digo de barras");
        put(9, "Error al imprimir pagina de prueba");
        put(10, "Error al setear configuraciones del c??digo de barras");
        put(11, "Error al imprimir imagenes");
        put(12, "Error al imprimir un rectangulo");
        put(13, "Error al imprimir un rectangulo");
        put(14, "Error al imprimir un rectangulo");
        put(15, "Error al imprimir un rectangulo");
        put(16, "Error al imprimir un rectangulo");
        put(17, "Error al imprimir un rectangulo");
    }};

    private JSONObject getErrorByCode(int code) {
        return this.getErrorByCode(code, null);
    }

    private JSONObject getErrorByCode(int code, Exception exception) {
        JSONObject json = new JSONObject();
        try {
            json.put("errorCode", code);
            json.put("message", errorCode.get(code));
            if (exception != null) {
                json.put("exception", exception.getMessage());
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, e.getMessage());
            showToast(e.getMessage());
        }
        return json;
    }

    /**
     * Busca todos os dispositivos Bluetooth pareados com o device
     *
     * @param callbackContext
     */
    protected void getBluetoothPairedDevices(CallbackContext callbackContext) {
        BluetoothAdapter mBluetoothAdapter = null;
        try {
            mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            if (mBluetoothAdapter == null) {
                callbackContext.error(this.getErrorByCode(1));
                return;
            }
            if (!mBluetoothAdapter.isEnabled()) {
                Intent enableBluetooth = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                this.mCordova.getActivity().startActivityForResult(enableBluetooth, 0);
            }
            Set<BluetoothDevice> pairedDevices = mBluetoothAdapter.getBondedDevices();
            if (pairedDevices.size() > 0) {
                JSONArray json = new JSONArray();
                for (BluetoothDevice device : pairedDevices) {
                    Hashtable map = new Hashtable();
                    map.put("type", device.getType());
                    map.put("address", device.getAddress());
                    map.put("name", device.getName());
                    JSONObject jObj = new JSONObject(map);
                    json.put(jObj);
                }
                callbackContext.success(json);
            } else {
                callbackContext.error(this.getErrorByCode(2));
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, e.getMessage());
            e.printStackTrace();
            callbackContext.error(e.getMessage());
        }
    }

    /**
     * Seta em mem??ria o endere??o da impressora cuja conex??o est?? sendo estabelecida
     *
     * @param address
     */
    protected void setAddress(String address) {
        mAddress = address;
    }

    protected void setWebView(CordovaWebView webView) {
        mWebView = webView;
    }

    public void setCordova(CordovaInterface cordova) {
        mCordova = cordova;
    }

    /**
     * CallbackContext de cada requisi????o, que efetivamente recebe os retornos dos m??todos
     *
     * @param callbackContext
     */
    public void setCallbackContext(CallbackContext callbackContext) {
        mCallbackContext = callbackContext;
    }

    /**
     * Valida o endere??o da impressora e efetua a conex??o
     *
     * @param callbackContext
     */
    protected void connect(CallbackContext callbackContext) {
        mConnectCallbackContext = callbackContext;
        closeActiveConnections();
        if (BluetoothAdapter.checkBluetoothAddress(mAddress)) {
            establishBluetoothConnection(mAddress, callbackContext);
        }
    }

    /**
     * Encerra todas as conex??es com impressoras e dispositivos Bluetooth ativas
     */
    public synchronized void closeActiveConnections() {
        closePrinterConnection();
        closeBluetoothConnection();
    }

    /**
     * Encerra a conex??o com a impressora
     */
    private synchronized void closePrinterConnection() {
        if (mPrinter != null) {
            mPrinter.release();
        }

        if (mProtocolAdapter != null) {
            mProtocolAdapter.release();
        }
    }

    /**
     * Finaliza o socket Bluetooth e encerra todas as conex??es
     */
    private synchronized void closeBluetoothConnection() {
        BluetoothSocket socket = mBluetoothSocket;
        mBluetoothSocket = null;
        if (socket != null) {
            try {
                Thread.sleep(50);
                socket.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Efetiva a conex??o com o dispositivo Bluetooth
     *
     * @param address
     * @param callbackContext
     */
    private void establishBluetoothConnection(final String address, final CallbackContext callbackContext) {
        runJob(new Runnable() {
            @Override
            public void run() {
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                BluetoothDevice device = adapter.getRemoteDevice(address);
                UUID uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
                InputStream in = null;
                OutputStream out = null;
                adapter.cancelDiscovery();

                try {
                    mBluetoothSocket = createBluetoothSocket(device, uuid, callbackContext);
                    Thread.sleep(50);
                    mBluetoothSocket.connect();
                    in = mBluetoothSocket.getInputStream();
                    out = mBluetoothSocket.getOutputStream();
                } catch (Exception e) {
                    e.printStackTrace();
                    sendStatusUpdate(false);
                    showError("Falla al conectar: " + e.getMessage(), false);
                    return;
                }

                try {
                    initializePrinter(in, out, callbackContext);
                    sendStatusUpdate(true);
                    showToast("Impresora conectada!");
                } catch (IOException e) {
                    e.printStackTrace();
                    sendStatusUpdate(false);
                    showError("Falla al inicializar: " + e.getMessage(), false);
                    return;
                }
            }
        }, "Conectando impresora", "Conectando..");
    }

    /**
     * Cria um socket Bluetooth
     *
     * @param device
     * @param uuid
     * @param callbackContext
     * @return BluetoothSocket
     * @throws IOException
     */
    private BluetoothSocket createBluetoothSocket(BluetoothDevice device, UUID uuid, final CallbackContext callbackContext) throws IOException {
        try {
            Method method = device.getClass().getMethod("createRfcommSocketToServiceRecord", new Class[] { UUID.class });
            return (BluetoothSocket) method.invoke(device, uuid);
        } catch (Exception e) {
            e.printStackTrace();
            sendStatusUpdate(false);
            showError("Falla al crear comunicacion: " + e.getMessage(), false);
        }
        return device.createRfcommSocketToServiceRecord(uuid);
    }

    /**
     * Inicializa a troca de dados com a impressora
     * @param inputStream
     * @param outputStream
     * @param callbackContext
     * @throws IOException
     */
    protected void initializePrinter(InputStream inputStream, OutputStream outputStream, CallbackContext callbackContext) throws IOException {
        mProtocolAdapter = new ProtocolAdapter(inputStream, outputStream);
        if (mProtocolAdapter.isProtocolEnabled()) {
            final ProtocolAdapter.Channel channel = mProtocolAdapter.getChannel(ProtocolAdapter.CHANNEL_PRINTER);
            channel.setListener(mChannelListener);
            // Create new event pulling thread
            new Thread(new Runnable() {
                @Override
                public void run() {
                    while (true) {
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }

                        try {
                            channel.pullEvent();
                        } catch (IOException e) {
                            e.printStackTrace();
                            sendStatusUpdate(false);
                            showError(e.getMessage(), mRestart);
                            break;
                        }
                    }
                }
            }).start();
            mPrinter = new Printer(channel.getInputStream(), channel.getOutputStream());
        } else {
            mPrinter = new Printer(mProtocolAdapter.getRawInputStream(), mProtocolAdapter.getRawOutputStream());
        }
        callbackContext.success();
    }

    /**
     * Alimenta papel ?? impressora (rola papel em branco)
     *
     * @param linesQuantity
     */
    public void feedPaper(int linesQuantity) {
        if (linesQuantity < 0 || linesQuantity > 255) {
            mCallbackContext.error(this.getErrorByCode(3));
        }
        try {
            mPrinter.feedPaper(linesQuantity);
            mPrinter.flush();
            mCallbackContext.success();
        } catch (Exception e) {
            mCallbackContext.error(this.getErrorByCode(4, e));
        }
    }

    /**
     * Print text expecting markup formatting tags (default encoding is ISO-8859-1)
     *
     * @param text
     */
    public void printTaggedText(String text) {
        printTaggedText(text, "ISO-8859-1");
    }

    /**
     * Print text expecting markup formatting tags and a defined charset
     *
     * @param text
     * @param charset
     */
    public void printTaggedText(String text, String charset) {
        try {
            mPrinter.printTaggedText(text, charset);
            mPrinter.flush();
            mCallbackContext.success();
        } catch (Exception e) {
            mCallbackContext.error(this.getErrorByCode(5, e));
        }
    }

    /**
     * Return what is the Printer current status
     */
    public void getStatus() {
        try {
            int status = mPrinter.getStatus();
            mCallbackContext.success(status);
        } catch (Exception e) {
            mCallbackContext.error(this.getErrorByCode(6, e));
        }
    }

    /**
     * Return Printer's head temperature
     */
    public void getTemperature() {
        try {
            int temperature = mPrinter.getTemperature();
            mCallbackContext.success(temperature);
        } catch (Exception e) {
            mCallbackContext.error(this.getErrorByCode(7, e));
        }
    }

    public void setBarcode(int align, boolean small, int scale, int hri, int height) {
        try {
            mPrinter.setBarcode(align, small, scale, hri, height);
            mCallbackContext.success();
        } catch (Exception e) {
            mCallbackContext.error(this.getErrorByCode(10, e));
        }
    }

    /**
     * Print a Barcode
     *
     * @param type
     * @param data
     */
    public void printBarcode(int type, String data) {
        try {
            mPrinter.printBarcode(type, data);
            mPrinter.flush();
            mCallbackContext.success();
        } catch (Exception e) {
            mCallbackContext.error(this.getErrorByCode(8, e));
        }
    }

    /**
     * Print a selftest page
     */
    public void printSelfTest() {
        try {
            mPrinter.printSelfTest();
            mPrinter.flush();
            mCallbackContext.success();
        } catch (Exception e) {
            mCallbackContext.error(this.getErrorByCode(9, e));
        }
    }

    public void drawPageRectangle(int x, int y, int width, int height, int fillMode) {
        try {
            mPrinter.drawPageRectangle(x, y, width, height, fillMode);
            mCallbackContext.success();
        } catch (Exception e) {
            mCallbackContext.error(this.getErrorByCode(12, e));
        }
    }

    public void drawPageFrame(int x, int y, int width, int height, int fillMode, int thickness) {
        try {
            mPrinter.drawPageFrame(x, y, width, height, fillMode, thickness);
            mCallbackContext.success();
        } catch (Exception e) {
            mCallbackContext.error(this.getErrorByCode(16, e));
        }
    }

    public void selectStandardMode() {
        try {
            mPrinter.selectStandardMode();
            mCallbackContext.success();
        } catch (Exception e) {
            mCallbackContext.error(this.getErrorByCode(13, e));
        }
    }

    public void selectPageMode() {
        try {
            mPrinter.selectPageMode();
            mCallbackContext.success();
        } catch (Exception e) {
            mCallbackContext.error(this.getErrorByCode(14, e));
        }
    }

    public void printPage() {
        try {
            mPrinter.printPage();
            mPrinter.flush();
            mCallbackContext.success();
        } catch (Exception e) {
            mCallbackContext.error(this.getErrorByCode(17, e));
        }
    }

    public void setPageRegion(int x, int y, int width, int height, int direction) {
        try {
            mPrinter.setPageRegion(x, y, width, height, direction);
            mCallbackContext.success();
        } catch (Exception e) {
            mCallbackContext.error(this.getErrorByCode(15, e));
        }
    }


    /**
     * Print an image
     *
     * @param image String (BASE64 encoded image)
     * @param width
     * @param height
     * @param align
     */
    public void printImage(String image, int width, int height, int align) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            byte[] decodedByte = Base64.decode(image, 0);
            Bitmap bitmap = BitmapFactory.decodeByteArray(decodedByte, 0, decodedByte.length);
            final int imgWidth = bitmap.getWidth();
            final int imgHeight = bitmap.getHeight();
            final int[] argb = new int[imgWidth * imgHeight];

            bitmap.getPixels(argb, 0, imgWidth, 0, 0, imgWidth, imgHeight);
            bitmap.recycle();

            mPrinter.printImage(argb, width, height, align, true);
            mPrinter.flush();
            mCallbackContext.success();
        } catch (Exception e) {
            mCallbackContext.error(this.getErrorByCode(11, e));
        }
    }

    /**
     * Wrapper para cria????o de Threads
     *
     * @param job
     * @param jobTitle
     * @param jobName
     */
    private void runJob(final Runnable job, final String jobTitle, final String jobName) {
        // Start the job from main thread
        mCordova.getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // Progress dialog available due job execution
                final ProgressDialog dialog = new ProgressDialog(mCordova.getActivity());
                dialog.setTitle(jobTitle);
                dialog.setMessage(jobName);
                dialog.setCancelable(false);
                dialog.setCanceledOnTouchOutside(false);
                dialog.show();

                Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            job.run();
                        } finally {
                            dialog.dismiss();
                        }
                    }
                });
                t.start();
            }
        });
    }

    /**
     * Exibe Toast de erro
     *
     * @param text
     * @param resetConnection
     */
    private void showError(final String text, boolean resetConnection) {
        //we'l ignore toasts at the moment
//        mCordova.getActivity().runOnUiThread(new Runnable() {
//            @Override
//            public void run() {
//                Toast.makeText(mCordova.getActivity().getApplicationContext(), text, Toast.LENGTH_SHORT).show();
//            }
//        });
        if (resetConnection) {
            connect(mConnectCallbackContext);
        }
    }

    /**
     * Exibe mensagem Toast
     *
     * @param text
     */
    private void showToast(final String text) {
        //we'l ignore toasts at the moment
//        mCordova.getActivity().runOnUiThread(new Runnable() {
//            @Override
//            public void run() {
//                if (!mCordova.getActivity().isFinishing()) {
//                    Toast.makeText(mCordova.getActivity().getApplicationContext(), text, Toast.LENGTH_SHORT).show();
//                }
//            }
//        });
    }

    /**
     * Create a new plugin result and send it back to JavaScript
     *
     * @param connection status
     */
    private void sendStatusUpdate(boolean isConnected) {
        final Intent intent = new Intent("DatecsPrinter.connectionStatus");

        Bundle b = new Bundle();
        b.putString( "userdata", "{ isConnected: "+ isConnected +"}" );

        intent.putExtras( b);

        LocalBroadcastManager.getInstance(mWebView.getContext()).sendBroadcastSync(intent);
    }
}
