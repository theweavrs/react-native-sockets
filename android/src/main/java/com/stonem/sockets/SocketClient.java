package com.stonem.sockets;

import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.WritableMap;

import android.util.Log;
import android.os.AsyncTask;
import android.support.annotation.Nullable;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.Socket;
import java.net.InetAddress;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.io.OutputStreamWriter;
import java.io.BufferedWriter;

/**
 * Created by David Stoneham on 2017-08-03.
 */

public class SocketClient {
    public Socket clientSocket;


    private final String eTag = "REACT-NATIVE-SOCKETS";
    private String event_closed = "_closed";
    private  String event_data = "_data";
    private  String event_error = "_error";
    private  String event_connect = "_connected";
    private String dstAddress;
    private int dstPort;
    private ReactContext mReactContext;
    private boolean isOpen = false;
    private boolean reconnectOnClose = false;
    private int reconnectDelay = 500;
    private int maxReconnectAttempts = -1;
    private int reconnectAttempts = 0;
    private boolean userDidClose = false;
    private boolean isFirstConnect = true;
    private BufferedInputStream bufferedInput;
    private boolean readingStream = false;
    private String name = "socketClient";
    private int timeout = 0;
    private final byte EOT = 0x04;

    SocketClient(ReadableMap params, ReactContext reactContext) {
        //String addr, int port, boolean autoReconnect
        mReactContext = reactContext;
        dstAddress = params.getString("address");
        dstPort = params.getInt("port");
        if (params.hasKey("reconnect")) {
            reconnectOnClose = params.getBoolean("reconnect");
        }
        if (params.hasKey("maxReconnectAttempts")) {
            maxReconnectAttempts = params.getInt("maxReconnectAttempts");
        }
        if (params.hasKey("reconnectDelay")) {
            reconnectDelay = params.getInt("reconnectDelay");
        }
        if (params.hasKey("timeout")){
timeout = params.getInt("timeout");
        }
        if(params.hasKey("name")){
            name = params.getString("name");
        }
event_closed = name+ event_closed;
event_connect = name + event_connect;
event_data = name + event_data;
event_error = name + event_error;
        Thread socketClientThread = new Thread(new SocketClientThread());
        socketClientThread.start();
    }

    public void disconnect(boolean wasUser) {
        try {
            if (clientSocket != null) {
                userDidClose = wasUser;
                isOpen = false;
                clientSocket.close();
                clientSocket = null;
                Log.d(eTag, "client closed");
            }
        } catch (IOException e) {
            handleIOException(e);
        }
    }

    private void sendEvent(ReactContext reactContext, String eventName, @Nullable WritableMap params) {
        reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class).emit(eventName, params);
    }

    protected void write(String message) {
        new AsyncTask<String, Void, Void>() {
            @Override
            protected void onPreExecute() {
                super.onPreExecute();
            }

            @Override
            protected Void doInBackground(String... params) {
                try {
                    String message = params[0];
                    OutputStreamWriter outputStreamWriter = new OutputStreamWriter(clientSocket.getOutputStream());
                    BufferedWriter bwriter = new BufferedWriter(outputStreamWriter);                    
                    bwriter.write(message);
                    bwriter.flush();
                    //debug log
                    Log.d(eTag, "client sent message: " + message);
                } catch (IOException e) {
                    handleIOException(e);
                }
                return null;
            }

            protected void onPostExecute(Void dummy) {
            }
        }.execute(message);
    }

    public void onDestroy() {
        if (clientSocket != null) {
            try {
                clientSocket.close();
            } catch (IOException e) {
                Log.e(eTag, "Client Destroy IOException", e);
            }
        }
    }

    private class SocketClientThread extends Thread {
        @Override
        public void run() {
            while (isFirstConnect || (!userDidClose && reconnectOnClose)) {
                try {
                    if (connectSocket()) {
                        watchIncoming();
                        reconnectAttempts = 0;
                    } else {
                        reconnectAttempts++;
                    }
                    isFirstConnect = false;
                    if (maxReconnectAttempts == -1 || maxReconnectAttempts < reconnectAttempts) {
                        Thread.sleep(reconnectDelay);
                    } else {
                        reconnectOnClose = false;
                    }
                } catch (InterruptedException e) {
                    //debug log
                    Log.e(eTag, "Client InterruptedException", e);
                }
            }
        }
    }

    private boolean connectSocket() {
        try {
            Log.d(eTag, "Starting new socket: " + dstAddress)
            InetAddress inetAddress = InetAddress.getByName(dstAddress);

            clientSocket = new Socket(inetAddress, dstPort, null, 0);
            clientSocket.setSoTimeout(timeout);
            isOpen = true;

            WritableMap eventParams = Arguments.createMap();
            sendEvent(mReactContext, event_connect, eventParams);
            return true;
        } catch (UnknownHostException e) {
            handleUnknownHostException(e);
        } catch (IOException e) {
            handleIOException(e);
        }
        return false;
    }

    private void watchIncoming() {
        try {
            InputStreamReader inputStreamReader = new InputStreamReader(clientSocket.getInputStream());
      BufferedReader breader = new BufferedReader(inputStreamReader);
      String line = null;
            while ((line = breader.readLine()) != null) {
                    //debug log
                    Log.d(eTag, "client received message: " + line);
                    //emit event
                    WritableMap eventParams = Arguments.createMap();
                    eventParams.putString("data", line);
                    sendEvent(mReactContext, event_data, eventParams);
                    //clear incoming
                    line = "";
            }
        } catch (IOException e) {
            handleIOException(e);
        }
    }

    private void handleIOException(IOException e) {
        //debug log
        Log.e(eTag, "Client IOException", e);
        //emit event
        String message = e.getMessage();
        WritableMap eventParams = Arguments.createMap();
        eventParams.putString("error", message);
        if (message.equals("Socket closed")) {
            isOpen = false;
            sendEvent(mReactContext, event_closed, eventParams);
        } else {
            sendEvent(mReactContext, event_error, eventParams);
        }
    }

    private void handleUnknownHostException(UnknownHostException e) {
        //debug log
        Log.e(eTag, "Client UnknownHostException", e);
        //emit event
        String message = e.getMessage();
        WritableMap eventParams = Arguments.createMap();
        eventParams.putString("error", e.getMessage());
        sendEvent(mReactContext, event_error, eventParams);
    }

}