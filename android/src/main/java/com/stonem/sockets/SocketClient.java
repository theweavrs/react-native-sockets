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
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.io.OutputStreamWriter;
import java.io.BufferedWriter;
import android.util.Base64;

import java.util.Arrays;
import java.util.StringTokenizer;

/**
 * Created by David Stoneham on 2017-08-03.
 */

public class SocketClient {
    public Socket clientSocket;

    private final String eTag = "REACT-NATIVE-SOCKETS";
    private String event_closed = "_closed";
    private String event_data = "_data";
    private String event_error = "_error";
    private String event_connect = "_connected";
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
    private String type = "string";
    private int timeout = 0;
    private final byte EOT = 0x04;

    SocketClient(ReadableMap params, ReactContext reactContext) {
        // String addr, int port, boolean autoReconnect
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
        if (params.hasKey("timeout")) {
            timeout = params.getInt("timeout");
        }
        if (params.hasKey("name")) {
            name = params.getString("name");
        }
        if (params.hasKey("type")) {
            type = params.getString("type");
        }
        event_closed = name + event_closed;
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

    protected void write(String message, final String type) {
        new AsyncTask<String, Void, Void>() {
            @Override
            protected void onPreExecute() {
                super.onPreExecute();
            }

            @Override
            protected Void doInBackground(String... params) {
                try {
                    String message = params[0];
                    Log.d(eTag, "TYPED: " + type);
                    if (type.equals("string")) {
                        OutputStreamWriter outputStreamWriter = new OutputStreamWriter(clientSocket.getOutputStream());
                        BufferedWriter bwriter = new BufferedWriter(outputStreamWriter);
                        bwriter.write(message);
                        bwriter.flush();
                        Log.d(eTag, "client sent message: " + message);
                    } else if (type.equals("base64")) {
                        byte[] array = Base64.decode(message, Base64.DEFAULT);
                        clientSocket.getOutputStream().write(array);
                        clientSocket.getOutputStream().flush();

                        Log.d(eTag, "client sent data: " + message);
                    }
                    // debug log
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
                    // debug log
                    Log.e(eTag, "Client InterruptedException", e);
                }
            }
        }
    }

    /**
     * Convert a TCP/IP address string into a byte array
     * 
     * @param addr String
     * @return byte[]
     */
    private final byte[] asBytes(String addr) {

        // Convert the TCP/IP address string to an integer value

        int ipInt = parseNumericAddress(addr);
        if (ipInt == 0)
            return null;

        // Convert to bytes

        byte[] ipByts = new byte[4];

        ipByts[3] = (byte) (ipInt & 0xFF);
        ipByts[2] = (byte) ((ipInt >> 8) & 0xFF);
        ipByts[1] = (byte) ((ipInt >> 16) & 0xFF);
        ipByts[0] = (byte) ((ipInt >> 24) & 0xFF);

        // Return the TCP/IP bytes

        return ipByts;
    }

    /**
     * Check if the specified address is a valid numeric TCP/IP address and return
     * as an integer value
     * 
     * @param ipaddr String
     * @return int
     */
    public final int parseNumericAddress(String ipaddr) {

        // Check if the string is valid

        if (ipaddr == null || ipaddr.length() < 7 || ipaddr.length() > 15)
            return 0;

        // Check the address string, should be n.n.n.n format

        StringTokenizer token = new StringTokenizer(ipaddr, ".");
        if (token.countTokens() != 4)
            return 0;

        int ipInt = 0;

        while (token.hasMoreTokens()) {

            // Get the current token and convert to an integer value

            String ipNum = token.nextToken();

            try {

                // Validate the current address part

                int ipVal = Integer.valueOf(ipNum).intValue();
                if (ipVal < 0 || ipVal > 255)
                    return 0;

                // Add to the integer address

                ipInt = (ipInt << 8) + ipVal;
            } catch (NumberFormatException ex) {
                return 0;
            }
        }

        // Return the integer address

        return ipInt;
    }

    private boolean connectSocket() {
        try {
            Log.d(eTag, "Starting new socket: " + dstAddress);
            InetAddress inetAddress = null;
            if (type.equals("string"))
                inetAddress = InetAddress.getByName(dstAddress);
            else {
                inetAddress = InetAddress.getByAddress(asBytes(dstAddress));
            }

            clientSocket = new Socket(inetAddress, dstPort, null, 0);
            clientSocket.setSoTimeout(timeout);
            isOpen = true;
            Log.d(eTag, "New socket started: " + dstAddress);

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
            if (type.equals("string")) {
                InputStreamReader inputStreamReader = new InputStreamReader(clientSocket.getInputStream());
                BufferedReader breader = new BufferedReader(inputStreamReader);
                String line = null;
                while ((line = breader.readLine()) != null) {
                    // debug log
                    Log.d(eTag, "client received message: " + line);
                    // emit event
                    WritableMap eventParams = Arguments.createMap();
                    eventParams.putString("data", line);
                    sendEvent(mReactContext, event_data, eventParams);
                    // clear incoming
                    line = "";
                }
            } else {
                Log.d(eTag, "gonna start reading data");
                InputStream inputStream = clientSocket.getInputStream();
                byte[] content = new byte[4096];
                int bytesRead = -1;
                while ((bytesRead = inputStream.read(content)) != -1) {
                    String data = Base64.encodeToString(Arrays.copyOfRange(content, 0, bytesRead), Base64.DEFAULT);
                    Log.d(eTag, "client received data: " + data);
                    WritableMap eventParams = Arguments.createMap();
                    eventParams.putString("data", data);
                    eventParams.putInt("length", bytesRead);
                    sendEvent(mReactContext, event_data, eventParams);
                } // while
                WritableMap eventParams = Arguments.createMap();
                sendEvent(mReactContext, event_closed, eventParams);
            }
        } catch (IOException e) {
            handleIOException(e);
        }
    }

    private void handleIOException(IOException e) {
        // debug log
        Log.e(eTag, "Client IOException", e);
        // emit event
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
        // debug log
        Log.e(eTag, "Client UnknownHostException", e);
        // emit event
        String message = e.getMessage();
        WritableMap eventParams = Arguments.createMap();
        eventParams.putString("error", e.getMessage());
        sendEvent(mReactContext, event_error, eventParams);
    }

}