package tejashwi.com.tensorflowsample;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.speech.tts.TextToSpeech;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;

import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import tejashwi.com.tensorflowsample.interfaces.Classifier;

public class ObjectActivity extends AppCompatActivity implements View.OnClickListener{

    private TextureView mTextureView;
    private ImageView mPreview;
    private TextView mResult;
    private Button mCapture;

    private TextToSpeech tts;
    private Button button;
    private Button save_btn;

    private final int REQUEST_CAMERA = 1;
    private CameraDevice mCameraDevice;
    private Handler mBackgroundHandler;
    private HandlerThread mBackgroundThread;
    protected CaptureRequest.Builder mCaptureRequestBuilder;
    protected CameraCaptureSession mCameraCaptureSessions;
    private Size imageDimension;
    private ImageReader imageReader;
    private Classifier mClassifier;

    private String userID ="";

    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    private Executor executor = Executors.newSingleThreadExecutor();

    /* MobileNet Quant model */
    private static final String MODEL_PATH_MOBILENET = "mobilenet_quant_v1_224.tflite";
    private static final String LABEL_PATH_MOBILENET = "labels.txt";

    /* Inception v3 2016 slim model */
    private static final String MODEL_PATH_INCEPTION = "inceptionv3_slim_2016.tflite";
    private static final String LABEL_PATH_INCEPTION = "imagenet_slim_labels.txt";

    private boolean classifierInit = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_object);
        if(checkPermission(Manifest.permission.CAMERA, REQUEST_CAMERA)){
            startCamera();
        }

        initTensorFlowAndLoadModel(false);
        button=(Button)findViewById(R.id.TTS_btn);
        save_btn=(Button)findViewById(R.id.save);

        Intent intent =getIntent();
        userID = intent.getStringExtra("userID");

        tts=new TextToSpeech(getApplicationContext(), new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if(status != TextToSpeech.ERROR) {
                    tts.setLanguage(Locale.ENGLISH);
                    tts.setPitch(0.7f);
                    //읽는 속도
                    tts.setSpeechRate(1.0f);
                }
            }
        });


    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closeCamera();
        stopBackgroundThread();
        closeTensorFlow();
        if(tts !=null){
            tts.stop();
            tts.shutdown();
        }
    }

    private boolean checkPermission(String permission, int requestCode){
        if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
                return true;
            } else {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, requestCode);
                return false;
            }
        } else {
            //permission is automatically granted on sdk<23 upon installation
            return true;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if(grantResults[0]== PackageManager.PERMISSION_GRANTED){
            if(requestCode == REQUEST_CAMERA){
                startCamera();
            }
        }
    }

    private void startCamera(){
        mTextureView = findViewById(R.id.texture_view);
        mTextureView.setSurfaceTextureListener(textureListener);

        mPreview = findViewById(R.id.preview);
        mResult = findViewById(R.id.result);

        mCapture = findViewById(R.id.capture);
        mCapture.setOnClickListener(this);


        startBackgroundThread();
        if (mTextureView.isAvailable()) {
            openCamera();
        } else {
            mTextureView.setSurfaceTextureListener(textureListener);
        }
    }

    @Override
    public void onClick(View v) {
                takePicture();
    }



    TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            openCamera();
        }
        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            // Transform you image captured size according to the surface width and height
        }
        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return false;
        }
        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        }
    };

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            mCameraDevice = camera;
            createCameraPreview();
        }
        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            mCameraDevice.close();
        }
        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            mCameraDevice.close();
            mCameraDevice = null;
        }
    };

    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("Camera Background");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    protected void takePicture() {
        if(null == mCameraDevice) {
            return;
        }
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            if(manager != null) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(mCameraDevice.getId());
                StreamConfigurationMap scMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if(scMap == null){
                    Toast.makeText(this, "Configuration map is null", Toast.LENGTH_LONG).show();
                    return;
                }
                Size[] jpegSizes = scMap.getOutputSizes(ImageFormat.JPEG);
                int width = 640;
                int height = 480;
                if (jpegSizes != null && 0 < jpegSizes.length) {
                    width = jpegSizes[0].getWidth();
                    height = jpegSizes[0].getHeight();
                }

                ImageReader reader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 1);
                List<Surface> outputSurfaces = new ArrayList<>(2);
                outputSurfaces.add(reader.getSurface());
                outputSurfaces.add(new Surface(mTextureView.getSurfaceTexture()));

                final CaptureRequest.Builder captureBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                captureBuilder.addTarget(reader.getSurface());
                captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);

                // Orientation
                int rotation = getWindowManager().getDefaultDisplay().getRotation();
                captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATIONS.get(rotation));

                ImageReader.OnImageAvailableListener readerListener = new ImageReader.OnImageAvailableListener() {
                    @Override
                    public void onImageAvailable(ImageReader reader) {
                        Image image = reader.acquireLatestImage();
                        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                        byte[] bytes = new byte[buffer.capacity()];
                        buffer.get(bytes);
                        image.close();

                        final Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                        final List<Classifier.Recognition> results = mClassifier.recognizeImage(bmp);
                        final String text = results.toString();
                        final String demo = text.substring(6);
                        final StringBuffer text2 = new StringBuffer(demo);
                        final String text3 = text2.delete(text2.indexOf("("), text2.length()).toString();

                        button.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {

                                Toast.makeText(getApplicationContext(), text2, Toast.LENGTH_SHORT).show();

                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                    ttsGreater21(text3);
                                } else {
                                    ttsUnder20(text3);
                                }

                            }
                        });

                        save_btn.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                Response.Listener<String> responseListener = new Response.Listener<String>() {
                                    @Override
                                    public void onResponse(String response) {
                                        try {
                                            JSONObject jsonResponse = new JSONObject(response);
                                            boolean success = jsonResponse.getBoolean("success");
                                            //
                                            if(text3.equals("")){
                                                AlertDialog.Builder builder = new AlertDialog.Builder(ObjectActivity.this);
                                                builder.setMessage("사진을 찍어주세요.")
                                                        .setPositiveButton("확인", null)
                                                        .create()
                                                        .show();
                                                return;
                                            } else {
                                                if (success) {
                                                        Toast.makeText(ObjectActivity.this,  "전송에 성공하였습니다.", Toast.LENGTH_SHORT).show();
                                                } else {
                                                    Toast.makeText(ObjectActivity.this,  "전송에 실패하였습니다.", Toast.LENGTH_SHORT).show();
                                                }
                                            }
                                        } catch (Exception e) {
                                            e.printStackTrace();
                                        }
                                    }
                                };
                                WordRequest wordRequest = new WordRequest(userID, text3, responseListener);
                                RequestQueue queue = Volley.newRequestQueue(ObjectActivity.this);
                                queue.add(wordRequest);

                            }
                        });

                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                mPreview.setImageBitmap(bmp);
                                mResult.setText(text3);
                            }
                        });

                    }
                };

                reader.setOnImageAvailableListener(readerListener, mBackgroundHandler);

                final CameraCaptureSession.CaptureCallback captureListener = new CameraCaptureSession.CaptureCallback() {
                    @Override
                    public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request,
                                                   @NonNull TotalCaptureResult result) {
                        super.onCaptureCompleted(session, request, result);
                        Toast.makeText(ObjectActivity.this, "Captured", Toast.LENGTH_SHORT).show();
                        createCameraPreview();
                    }
                };

                mCameraDevice.createCaptureSession(outputSurfaces, new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(@NonNull CameraCaptureSession session) {
                        try {
                            session.capture(captureBuilder.build(), captureListener, mBackgroundHandler);
                        } catch (CameraAccessException e) {
                            e.printStackTrace();
                        }
                    }

                    @Override
                    public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    }
                }, mBackgroundHandler);
            } else {
                Toast.makeText(ObjectActivity.this, "Manager is null", Toast.LENGTH_LONG).show();
            }


        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void createCameraPreview() {
        try {
            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            texture.setDefaultBufferSize(imageDimension.getWidth(), imageDimension.getHeight());
            Surface surface = new Surface(texture);
            mCaptureRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mCaptureRequestBuilder.addTarget(surface);
            mCameraDevice.createCaptureSession(Collections.singletonList(surface), new CameraCaptureSession.StateCallback(){
                @Override
                public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                    //The camera is already closed
                    if (null == mCameraDevice) {
                        return;
                    }
                    // When the session is ready, we start displaying the preview.
                    mCameraCaptureSessions = cameraCaptureSession;
                    updatePreview();
                }
                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                    Toast.makeText(ObjectActivity.this, "Configuration change", Toast.LENGTH_SHORT).show();
                }
            }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    @SuppressLint("MissingPermission")
    private void openCamera() {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            if(manager != null) {
                String cameraId = manager.getCameraIdList()[0];
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map != null) {
                    imageDimension = map.getOutputSizes(SurfaceTexture.class)[0];
                    manager.openCamera(cameraId, stateCallback, null);
                }
            } else {
                Toast.makeText(this, "Manager is null", Toast.LENGTH_LONG).show();
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void updatePreview() {
        if(null == mCameraDevice) {
            return;
        }
        mCaptureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        try {
            mCameraCaptureSessions.setRepeatingRequest(mCaptureRequestBuilder.build(), null, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void closeCamera() {
        if (null != mCameraDevice) {
            mCameraDevice.close();
            mCameraDevice = null;
        }
        if (null != imageReader) {
            imageReader.close();
            imageReader = null;
        }
    }

    private void initTensorFlowAndLoadModel(final boolean isInception){
        closeTensorFlow();
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if(isInception) {
                        mClassifier = TensorFlowImageClassifier.create(getAssets(), MODEL_PATH_INCEPTION, LABEL_PATH_INCEPTION, true);
                    } else {
                        mClassifier = TensorFlowImageClassifier.create(getAssets(), MODEL_PATH_MOBILENET, LABEL_PATH_MOBILENET, false);
                    }

                    setButtonVisibility(View.VISIBLE);
                    classifierInit = true;

                } catch (final Exception e) {
                    classifierInit = false;
                    setButtonVisibility(View.GONE);
                    throw new RuntimeException("Error initializing TensorFlow!", e);
                }
            }
        });
    }

    private void closeTensorFlow(){
        if(classifierInit) {
            setButtonVisibility(View.GONE);
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    mClassifier.close();
                }
            });
        }
    }

    private void setButtonVisibility(final int visible){
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mCapture.setVisibility(visible);
            }
        });
    }
    @SuppressWarnings("deprecation")
    private void ttsUnder20(String text) {
        HashMap<String, String> map = new HashMap<>();
        map.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "MessageId");
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, map);
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void ttsGreater21(String text) {
        String utteranceId=this.hashCode() + "";
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
    }

    public static class WordRequest extends StringRequest {
        final static private String URL = "http://jyyu7777.cafe24.com/SaveWords.php";
        private Map<String, String> parameters;

        public WordRequest(String userID, String userWords, Response.Listener<String> responseListener) {
            super(Method.POST, URL, responseListener,null);
            parameters = new HashMap<>();
            parameters.put("userID", userID);
            parameters.put("userWORDS", userWords);
        }

        @Override
        public Map<String, String> getParams(){
            return parameters;
        }
    }
}

