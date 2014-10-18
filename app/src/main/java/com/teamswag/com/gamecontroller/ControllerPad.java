package com.teamswag.com.gamecontroller;


import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.UUID;

import com.getpebble.android.kit.PebbleKit;
import com.getpebble.android.kit.PebbleKit.*;
import com.getpebble.android.kit.util.PebbleDictionary;

public class ControllerPad extends Activity {
    /** Called when the activity is first created. */
    public static final String SERVERIP = "192.168.1.147";
    public static final int SERVERPORT = 7777;
    private final static UUID PEBBLE_APP_UUID = UUID.fromString("6de63fbf-aae8-4d4d-912e-2697d344ebd3");
    private final int BATCH_SIZE = 10;
    private Handler handler;
    private PebbleDataReceiver dataReceiver;
    private long accelData[];
    private View view;
    private int bgcolor;
    private String canvasText;
    private boolean notCalibrating;
    private Thread lThread;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        view = new MyView(this);
        setContentView(view);
        PebbleKit.startAppOnPebble(getApplicationContext(), PEBBLE_APP_UUID);

        notCalibrating = true;

        bgcolor = Color.WHITE;
        handler = new Handler();
        canvasText = "Touch screen to calibrate gesture";
        accelData = new long[3*BATCH_SIZE];
        for(int i=0; i<3*BATCH_SIZE; i++){
            accelData[i]=0;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        dataReceiver = new PebbleKit.PebbleDataReceiver(PEBBLE_APP_UUID) {
            @Override
            public void receiveData(final Context context, final int transactionId, final PebbleDictionary data) {
                PebbleKit.sendAckToPebble(getApplicationContext(), transactionId);

                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        for(int i=0; i<3*BATCH_SIZE; i++){
                            accelData[i%3]+=data.getInteger(i);
                        }
                        accelData[0]/=BATCH_SIZE;
                        accelData[1]/=BATCH_SIZE;
                        accelData[2]/=BATCH_SIZE;
                        view.invalidate();
                    }
                });
            }
        };
        PebbleKit.registerReceivedDataHandler(this, dataReceiver);
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(dataReceiver);
    }

    private double accelerationVectorLength(long x, long y, long z){
        long xs = x*x;
        long ys = y*y;
        long zs = z*z;
        return Math.sqrt(xs + ys + zs);
    }

    private void recordGesture(int tolerance){
        canvasText = "Please hold your arm out and still";
        Thread myThread = new GestureCalibrationThread(tolerance);
        myThread.start();
    }

    private void alertConsole(String message){
        Log.i(getLocalClassName(), message);

    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if(e.getAction() == MotionEvent.ACTION_UP && notCalibrating) {
            notCalibrating = false;
            //Need to start recording gesture and find stop
            recordGesture(200);
        }
        return true;
    }

    private void startGestureListener(long[] gravityVector, long[] rightVector, long[] upVector){
        lThread = new GestureListenerThread(650, gravityVector, rightVector, upVector);
        lThread.start();
    }

    public long[] crossLV(long[] vector1, long[] vector2){
        long retu[] = {0,0,0};
        retu[0] = (long)(vector1[1]*vector2[2]-vector1[2]*vector2[1]);
        retu[1] = (long)(vector1[2]*vector2[0]-vector1[0]*vector2[2]);
        retu[2] = (long)(vector1[0]*vector2[2]-vector1[2]*vector2[0]);
        return retu;
    }

    public double dotProductLV(long[] vector1, double[] vector2){
        return vector1[0]*vector2[0]+vector1[1]*vector2[1]+vector1[2]*vector2[2];
    }

    public double[] normalizeLV(long[] vector){
        double retu[] = {0,0,0};
        double length = accelerationVectorLength(vector[0], vector[1], vector[2]);
        if(length != 0){
            retu[0] = (double)(vector[0]/length);
            retu[1] = (double)(vector[1]/length);
            retu[2] = (double)(vector[2]/length);
        }
        return retu;
    }


    public class MyView extends View {
        public MyView(Context context) {
            super(context);
            // TODO Auto-generated constructor stub
        }

        @Override
        protected void onDraw(Canvas canvas) {
            // TODO Auto-generated method stub
            super.onDraw(canvas);
            int x = getWidth();
            int y = getHeight();

            int outerCircle = Math.min(x, y)/2;
            int radius = outerCircle/10;

            Paint paint = new Paint();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(bgcolor);
            canvas.drawPaint(paint);
            // Use Color.parseColor to define HTML colors
            paint.setColor(Color.parseColor("#CD5C5C"));

            long xlength = accelData[0];
            long ylength =  accelData[1];
            long zsize = radius-accelData[2]/100;
            long maxCircleDist = outerCircle-zsize;

            if(xlength > maxCircleDist){
                xlength = maxCircleDist;
            }else if(xlength < -1*maxCircleDist){
                xlength = -1*maxCircleDist;
            }
            if(ylength > maxCircleDist){
                ylength = maxCircleDist;
            }else if(ylength < -1*maxCircleDist){
                ylength = -1*maxCircleDist;
            }

            canvas.drawCircle(x/2+xlength, y/2-ylength, zsize, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth((float) 5.0);
            canvas.drawCircle(x / 2, y / 2, outerCircle, paint);


            paint.setColor(Color.BLACK);
            paint.setTextSize(60);
            canvas.drawText(canvasText, 10, y/4, paint);
        }
    }


    public class GestureCalibrationThread extends Thread{
        private int tolerance;

        public GestureCalibrationThread(int tolerance){
            this.tolerance = tolerance;
        }

        public void run(){
            int gravity = 1000;
            long maxAccel[] = {0,0,0};
            long temp[] = {0,0,0};
            long gravityVector[] = {0,0,0};
            long rightVector[] = {0,0,0};
            long upVector[] = {0,0,0};
            int numFramesNotAtZero = 0;
            int numFramesAtZero = 0;
            double acceleration;

            //finding out which way is down
            do{
                try {
                    Thread.sleep(50);
                } catch (Exception e) {
                }

                temp = accelData;
                acceleration = Math.abs(accelerationVectorLength(temp[0], temp[1], temp[2]) - gravity);

                if (acceleration < tolerance) {
                    gravityVector[0]+= temp[0];
                    gravityVector[1]+= temp[1];
                    gravityVector[2]+= temp[2];
                    numFramesAtZero++;
                } else {
                    numFramesAtZero = 0;

                    gravityVector[0]=0;
                    gravityVector[1]=0;
                    gravityVector[2]=0;
                }
            } while (numFramesAtZero < 20);
            bgcolor = Color.YELLOW;
            canvasText = "Please move your arm to the right";

            gravityVector[0]/=20;
            gravityVector[1]/=20;
            gravityVector[2]/=20;


            //waiting for movement from user
            //once user starts moving his hand....
           do{
                try {
                    Thread.sleep(20);
                } catch (Exception e) {
                }
               temp = accelData;
               acceleration = Math.abs(accelerationVectorLength(temp[0], temp[1], temp[2]) - gravity);
               double maxAcceleration = accelerationVectorLength(maxAccel[0], maxAccel[1], maxAccel[2]);
               if (maxAcceleration == 0 || acceleration > Math.abs(maxAcceleration) - gravity) {
                   maxAccel[0] = temp[0];
                   maxAccel[1] = temp[1];
                   maxAccel[2] = temp[2];
               }
               if (acceleration > tolerance) {
                   numFramesNotAtZero++;
               } else {
                   numFramesNotAtZero = 0;
               }
            } while (numFramesNotAtZero < 7);
            rightVector[0] = maxAccel[0]-gravityVector[0];
            rightVector[1] = maxAccel[1]-gravityVector[1];
            rightVector[2] = maxAccel[2]-gravityVector[2];



            bgcolor = Color.WHITE;
            canvasText = "Please Hold your arm out and still again";
            numFramesAtZero = 0;
            do{
                try {
                    Thread.sleep(100);
                } catch (Exception e) {
                }
                temp = accelData;
                acceleration = Math.abs(accelerationVectorLength(temp[0], temp[1], temp[2]) - gravity);

                if (acceleration < tolerance) {
                    numFramesAtZero++;
                } else {
                    numFramesAtZero = 0;
                }
            } while (numFramesAtZero < 20);
            bgcolor = Color.YELLOW;
            canvasText = "Please move your upwards";

            maxAccel = new long[]{0,0,0};
            //waiting for movement from user
            //once user starts moving his hand....
            do{
                try {
                    Thread.sleep(20);
                } catch (Exception e) {
                }
                temp = accelData;
                acceleration = Math.abs(accelerationVectorLength(temp[0], temp[1], temp[2]) - gravity);
                double maxAcceleration = accelerationVectorLength(maxAccel[0], maxAccel[1], maxAccel[2]);
                if (maxAcceleration == 0 || acceleration > Math.abs(maxAcceleration) - gravity) {
                    maxAccel[0] = temp[0];
                    maxAccel[1] = temp[1];
                    maxAccel[2] = temp[2];
                }
                if (acceleration > tolerance) {
                    numFramesNotAtZero++;
                } else {
                    numFramesNotAtZero = 0;
                }
            } while (numFramesNotAtZero < 7);
            upVector[0] = maxAccel[0]-gravityVector[0];
            upVector[1] = maxAccel[1]-gravityVector[1];
            upVector[2] = maxAccel[2]-gravityVector[2];

            alertConsole("Gravity Vector: " + gravityVector[0] + "," + gravityVector[1] + "," + gravityVector[2]);
            alertConsole("Right Vector: " + rightVector[0] + "," + rightVector[1] + "," + rightVector[2]);
            alertConsole("Up Vector: " + upVector[0] + "," + upVector[1] + "," + upVector[2]);
            canvasText = "Calibration Complete";
            bgcolor = Color.GREEN;
            startGestureListener(gravityVector, rightVector, upVector);
        }
    }

    public class GestureListenerThread extends Thread {
        private int tolerance;
        private long[] gravityVector;
        private double[] rightVector;
        private double[] upVector;
        private double[] forwardVector;
        private ArrayList<long[]> events;

        public GestureListenerThread(int tolerance, long[] gravityVector, long[] rightVector, long[] upVector) {
            this.tolerance = tolerance;
            this.gravityVector = gravityVector;
            this.rightVector = normalizeLV(rightVector);
            this.upVector = normalizeLV(upVector);
            this.forwardVector = normalizeLV(crossLV(upVector, rightVector));
            alertConsole(this.upVector[0] +"," + this.upVector[1] + "," + this.upVector[2]);
        }

        public void run() {
            try {
                InetAddress serverAddr = InetAddress.getByName(SERVERIP);
                alertConsole("\nStart connecting\n");
                DatagramSocket socket = new DatagramSocket();
                byte[] buf = {0};
                DatagramPacket packet = new DatagramPacket(buf, buf.length, serverAddr, SERVERPORT);
                alertConsole("Sending ‘" + new String(buf) + "’ to " + SERVERIP + ":" + SERVERPORT + "\n");
                alertConsole("Messages sending!\n");
                while (true) {
                    try {
                        Thread.sleep(100);
                    } catch (Exception e) {
                    }
                    long vector[] = {0, 0, 0};
                    vector[0] = accelData[0] - gravityVector[0];
                    vector[1] = accelData[1] - gravityVector[1];
                    vector[2] = accelData[2] - gravityVector[2];
                    double d = Math.abs(dotProductLV(vector, upVector));


                    if (d > tolerance) {
                        buf = new byte[]{1};
                        alertConsole("WERE MINING BITCHCOINZ  " + d);
                    }else {
                        buf = new byte[]{0};
                    }
                    packet = new DatagramPacket(buf, buf.length, serverAddr, SERVERPORT);
                    socket.send(packet);
                }
            }catch (Exception e) {
                //updatetrack("Error!\n");
            }
        }
    }

}