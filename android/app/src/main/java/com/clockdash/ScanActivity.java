package com.clockdash;

import android.app.Activity;
import android.content.Intent;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.List;

/** 扫二维码（相机 + zxing 解码），识别设置页生成的配置码 */
public class ScanActivity extends Activity implements SurfaceHolder.Callback {

    private Camera mCamera;
    private SurfaceHolder mHolder;
    private int mWidth = 640, mHeight = 480;
    private volatile boolean mBusy = false;
    private boolean mDone = false;
    private final Handler mHandler = new Handler();
    private TextView mHint;

    private final Camera.PreviewCallback mCb = new Camera.PreviewCallback() {
        @Override
        public void onPreviewFrame(final byte[] data, final Camera camera) {
            if (mBusy || mDone) return;
            mBusy = true;
            final int w = mWidth, h = mHeight;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final String text = QrCodec.decodeNv21(data, w, h);
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            mBusy = false;
                            if (mDone) return;
                            if (text != null) {
                                mDone = true;
                                vibrate();
                                setResult(RESULT_OK, new Intent().putExtra("text", text));
                                finish();
                            } else if (camera != null) {
                                requestFrame();
                            }
                        }
                    });
                }
            }).start();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout root = new FrameLayout(this);
        SurfaceView sv = new SurfaceView(this);
        root.addView(sv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        mHint = new TextView(this);
        mHint.setText("对准设置页上的二维码…\n（无摄像头或无法识别时，请在上一页手动输入地址）");
        mHint.setTextColor(0xFFFFFFFF);
        mHint.setTextSize(16);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 60, 0, 0);
        mHint.setLayoutParams(lp);
        root.addView(mHint);
        setContentView(root);

        mHolder = sv.getHolder();
        mHolder.addCallback(this);
        mHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
    }

    private void requestFrame() {
        try {
            if (mCamera != null) mCamera.setOneShotPreviewCallback(mCb);
        } catch (Exception ignored) {
        }
    }

    private void vibrate() {
        try {
            ((android.os.Vibrator) getSystemService(VIBRATOR_SERVICE)).vibrate(80);
        } catch (Exception ignored) {
        }
    }

    private void startCamera() {
        try {
            Camera.CameraInfo info = new Camera.CameraInfo();
            int pick = -1;
            for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
                Camera.getCameraInfo(i, info);
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                    pick = i;
                    break;
                }
                if (pick < 0) pick = i;
            }
            if (pick < 0) {
                mHint.setText("本机没有摄像头，请返回上一页手动输入服务器地址");
                return;
            }
            mCamera = Camera.open(pick);
            Camera.Parameters p = mCamera.getParameters();
            List<Camera.Size> sizes = p.getSupportedPreviewSizes();
            if (sizes != null && !sizes.isEmpty()) {
                Camera.Size best = sizes.get(0);
                for (Camera.Size s : sizes) {
                    if (Math.abs(s.width - 640) + Math.abs(s.height - 480)
                            < Math.abs(best.width - 640) + Math.abs(best.height - 480)) {
                        best = s;
                    }
                }
                mWidth = best.width;
                mHeight = best.height;
                p.setPreviewSize(mWidth, mHeight);
            }
            try {
                List<String> modes = p.getSupportedFocusModes();
                if (modes != null && modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                    p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
                } else if (modes != null && modes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                    p.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
                }
            } catch (Exception ignored) {
            }
            mCamera.setParameters(p);
            mCamera.setPreviewDisplay(mHolder);
            mCamera.startPreview();
            requestFrame();
        } catch (Exception e) {
            mHint.setText("相机启动失败：" + e.getClass().getSimpleName() + "\n请返回手动输入地址");
            if (mCamera != null) {
                try {
                    mCamera.release();
                } catch (Exception ignored) {
                }
                mCamera = null;
            }
        }
    }

    private void stopCamera() {
        if (mCamera != null) {
            try {
                mCamera.stopPreview();
            } catch (Exception ignored) {
            }
            try {
                mCamera.release();
            } catch (Exception ignored) {
            }
            mCamera = null;
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        startCamera();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        stopCamera();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopCamera();
    }
}
