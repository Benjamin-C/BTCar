package dev.benjaminc.btcar;

import androidx.appcompat.app.AppCompatActivity;

import android.annotation.SuppressLint;
import android.os.Bundle;

import android.os.Handler;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.view.View;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Set;
import java.util.UUID;

@SuppressLint("ClickableViewAccessibility")
public class MainActivity extends AppCompatActivity {

    private int lastR = 0;
    private int lastL = 0;

    private Button scanButton;
    private TextView statusView;

    private Button lightButton;

    private TextView debug[];

    private SeekBar sensitivityBar;

    BluetoothAdapter mBluetoothAdapter;
    BluetoothSocket mmSocket;
    BluetoothDevice mmDevice;
    OutputStream mmOutputStream;
    InputStream mmInputStream;
    Thread workerThread;
    byte[] readBuffer;
    int readBufferPosition;
    int counter;
    volatile boolean stopWorker;

    int startX;
    int startY;

    private void enable() {
        lightButton.setEnabled(true);
        sensitivityBar.setEnabled(true);

//        sensitivityBar.setMax(Math.min(lightButton.getWidth(), lightButton.getHeight()) / 2);
        sensitivityBar.setProgress(sensitivityBar.getMax());
    }

    private void disable() {
        lightButton.setEnabled(false);
        sensitivityBar.setEnabled(false);
    }

    private float clamp(float val, float min, float max) {
        return Math.min(max, Math.max(min, val));
    }

    private int mapTo(int val, int max, int newMax) {
        return (int) ((float) val / (float) max * newMax);
    }

    private float abs(float val) {
        return Math.abs(val);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusView = (TextView) findViewById(R.id.statusView);
        scanButton = (Button)findViewById(R.id.scanButton);
        lightButton = (Button)findViewById(R.id.forwardButton);

        sensitivityBar = (SeekBar) findViewById(R.id.sensitivityBar);

        debug = new TextView[3];
        debug[0] = (TextView) findViewById(R.id.debug0);
        debug[1] = (TextView) findViewById(R.id.debug1);
        debug[2] = (TextView) findViewById(R.id.debug2);

        disable();
//        enable();
        //Open Button
        scanButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    scanButton.setText("Connecting ...");
                    statusView.setText("Connecting ...");
                    findBT();
                    openBT();
                }
                catch (IOException ex) { }
            }
        });

        //Send Button
//        lightButton.setOnClickListener(new View.OnClickListener() {
//            public void onClick(View v) {
//                controlLight(true);
//            }
//        });
        lightButton.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                int l = 0;
                int r = 0;
                int width = lightButton.getWidth();
                int height = lightButton.getHeight();
                switch(event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                    case MotionEvent.ACTION_BUTTON_PRESS: {
//                        controlLight(true);
                        startX = (int) (width / 2);
                        startY = (int) (height / 2);
                    }
                    case MotionEvent.ACTION_MOVE: {
                        // Subtract start from location to invert axis so that the top is high and bottom is low
                        int dx = mapTo((((int) clamp(event.getX(), 0, width) - startX)), width/2, 510);
                        // Subtract location from start to not invert axis so that the right is high and left is low
                        float speed = ((startY - clamp(event.getY(), 0, height)) * sensitivityBar.getProgress() / 100) / (height / 2);

                        l = (int) ((clamp(dx, -510, 0) + 255) * speed);
                        r = (int) ((255 - (clamp(dx, 0, 510))) * speed);

                        debug[0].setText("l: " + l);
                        debug[1].setText("r: " + r);

                    } break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_BUTTON_RELEASE:
                    case MotionEvent.ACTION_CANCEL: {
//                        controlLight(false);
                        debug[0].setText("No Touch");
                        debug[1].setText("No Touch");
                    } break;
                }
                if(lastL != l || lastR != r) {
                    lastL = l;
                    lastR = r;
                    String msg = "l" + ((l < 0) ? "-" : "+") + String.format("%02X", (int) abs(l)) + "\nr" + ((r < 0) ? "-" : "+") + String.format("%02X", (int) abs(r)) + "\n";
                    sendData(msg);
                    debug[2].setText(msg);
                }
                return true;
            }
        });
    }

    void findBT()
    {
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if(mBluetoothAdapter == null)
        {
            statusView.setText("No bluetooth adapter available");
        }

        if(!mBluetoothAdapter.isEnabled())
        {
            Intent enableBluetooth = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBluetooth, 0);
        }

        Set<BluetoothDevice> pairedDevices = mBluetoothAdapter.getBondedDevices();
        mmDevice = null;
        if(pairedDevices.size() > 0) {
            for(BluetoothDevice device : pairedDevices) {
                debug[0].setText(device.getName());
                if(device.getName().equals("ESP32test")) {
                    mmDevice = device;
                    debug[1].setText("Found");
                    break;
                }
            }
        }
        disable();
        statusView.setText("Disconnected");
        scanButton.setText("Connect");
    }

    void openBT() throws IOException {
        if(mmDevice == null) {
            statusView.setText("No device");
        } else {
            UUID uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"); //Standard SerialPortService ID
            mmSocket = mmDevice.createRfcommSocketToServiceRecord(uuid);
            mmSocket.connect();
            mmOutputStream = mmSocket.getOutputStream();
            mmInputStream = mmSocket.getInputStream();

            beginListenForData();

            enable();

            statusView.setText("Bluetooth Connected");
            scanButton.setText("Disconnect");
        }
    }

    void beginListenForData()
    {
        final Handler handler = new Handler();
        final byte delimiter = 10; //This is the ASCII code for a newline character

        stopWorker = false;
        readBufferPosition = 0;
        readBuffer = new byte[1024];
        workerThread = new Thread(new Runnable()
        {
            public void run()
            {
                while(!Thread.currentThread().isInterrupted() && !stopWorker)
                {
                    try
                    {
                        int bytesAvailable = mmInputStream.available();
                        if(bytesAvailable > 0)
                        {
                            byte[] packetBytes = new byte[bytesAvailable];
                            mmInputStream.read(packetBytes);
                            for(int i=0;i<bytesAvailable;i++)
                            {
                                byte b = packetBytes[i];
                                if(b == delimiter)
                                {
                                    byte[] encodedBytes = new byte[readBufferPosition];
                                    System.arraycopy(readBuffer, 0, encodedBytes, 0, encodedBytes.length);
                                    final String data = new String(encodedBytes, "US-ASCII");
                                    readBufferPosition = 0;

                                    handler.post(new Runnable()
                                    {
                                        public void run()
                                        {
                                            statusView.setText(data);
                                        }
                                    });
                                }
                                else
                                {
                                    readBuffer[readBufferPosition++] = b;
                                }
                            }
                        }
                    }
                    catch (IOException ex)
                    {
                        stopWorker = true;
                    }
                }
            }
        });

        workerThread.start();
    }

    void controlLight(boolean status) {
        sendData(status ? "l" : "u");
    }

    void sendData(String msg) {
        if(mmOutputStream != null) {
            try {
                mmOutputStream.write(msg.getBytes());
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            statusView.setText("Can't send message");
        }
    }

    void closeBT() throws IOException {
        stopWorker = true;
        mmOutputStream.close();
        mmInputStream.close();
        mmSocket.close();
        statusView.setText("Bluetooth Closed");
    }
}